package cn.why360.siming.dao;

import cn.why360.siming.database.DatabaseManager;
import cn.why360.siming.entity.SmartRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * SMART监控记录数据访问对象
 */
public class SmartRecordDAO {
    private static final Logger logger = LoggerFactory.getLogger(SmartRecordDAO.class);
    private final DatabaseManager databaseManager;

    public SmartRecordDAO(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * 插入SMART记录
     */
    public void insert(SmartRecord record) {
        String sql = "INSERT INTO smart_records (disk_id, attribute_id, attribute_name, current_value, worst_value, threshold, raw_value, failed, temperature) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, record.getDiskId());
            stmt.setInt(2, record.getAttributeId());
            stmt.setString(3, record.getAttributeName());
            stmt.setInt(4, record.getCurrentValue());
            stmt.setInt(5, record.getWorstValue());
            stmt.setInt(6, record.getThreshold());
            stmt.setLong(7, record.getRawValue());
            stmt.setInt(8, record.isFailed() ? 1 : 0);
            stmt.setInt(9, record.getTemperature() != null ? record.getTemperature() : -1);
            stmt.executeUpdate();

            logger.debug("Inserted SMART record for disk id {}, attribute {}", record.getDiskId(), record.getAttributeName());
        } catch (SQLException e) {
            logger.error("Failed to insert SMART record for disk id {}", record.getDiskId(), e);
            throw new RuntimeException("Failed to insert SMART record", e);
        }
    }

    /**
     * 批量插入SMART记录
     */
    public void batchInsert(Long diskId, List<SmartRecord> records) {
        String sql = "INSERT INTO smart_records (disk_id, attribute_id, attribute_name, current_value, worst_value, threshold, raw_value, failed, temperature) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            conn.setAutoCommit(false);

            for (SmartRecord record : records) {
                stmt.setLong(1, diskId);
                stmt.setInt(2, record.getAttributeId());
                stmt.setString(3, record.getAttributeName());
                stmt.setInt(4, record.getCurrentValue());
                stmt.setInt(5, record.getWorstValue());
                stmt.setInt(6, record.getThreshold());
                stmt.setLong(7, record.getRawValue());
                stmt.setInt(8, record.isFailed() ? 1 : 0);
                stmt.setInt(9, record.getTemperature() != null ? record.getTemperature() : -1);
                stmt.addBatch();
            }

            stmt.executeBatch();
            conn.commit();

            logger.debug("Batch inserted {} SMART records for disk id {}", records.size(), diskId);
        } catch (SQLException e) {
            logger.error("Failed to batch insert SMART records for disk id {}", diskId, e);
            throw new RuntimeException("Failed to batch insert SMART records", e);
        }
    }

    /**
     * 获取指定硬盘在时间区间内的SMART记录（按属性分组，取每个属性最早和最新记录）
     */
    public List<SmartRecord> findByDiskIdAndTimeRange(Long diskId, LocalDateTime startTime, LocalDateTime endTime) {
        List<SmartRecord> records = new ArrayList<>();
        String sql = "SELECT * FROM smart_records " +
                "WHERE disk_id = ? AND record_time BETWEEN ? AND ? " +
                "ORDER BY attribute_id, record_time ASC";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, diskId);
            stmt.setTimestamp(2, Timestamp.valueOf(startTime));
            stmt.setTimestamp(3, Timestamp.valueOf(endTime));

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    records.add(mapResultSetToRecord(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to query SMART records for disk id {}", diskId, e);
        }

        return records;
    }

    /**
     * 获取指定硬盘最新的SMART记录
     */
    public List<SmartRecord> findLatestByDiskId(Long diskId) {
        List<SmartRecord> records = new ArrayList<>();
        String sql = "SELECT sr.* FROM smart_records sr " +
                "INNER JOIN ( " +
                "  SELECT attribute_id, MAX(record_time) as max_time " +
                "  FROM smart_records " +
                "  WHERE disk_id = ? " +
                "  GROUP BY attribute_id " +
                ") latest ON sr.attribute_id = latest.attribute_id AND sr.record_time = latest.max_time " +
                "WHERE sr.disk_id = ? " +
                "ORDER BY sr.attribute_id";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, diskId);
            stmt.setLong(2, diskId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    records.add(mapResultSetToRecord(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to query latest SMART records for disk id {}", diskId, e);
        }

        return records;
    }

    /**
     * 获取指定硬盘在截止时间之前的最新SMART属性集合（每个属性取最新一条）
     * 用于四个时间点对比分析
     */
    public List<SmartRecord> findLatestAttributesBefore(Long diskId, LocalDateTime beforeTime) {
        List<SmartRecord> records = new ArrayList<>();
        String sql = "SELECT sr.* FROM smart_records sr " +
                "INNER JOIN ( " +
                "  SELECT attribute_id, MAX(record_time) as max_time " +
                "  FROM smart_records " +
                "  WHERE disk_id = ? AND record_time <= ? " +
                "  GROUP BY attribute_id " +
                ") latest ON sr.attribute_id = latest.attribute_id AND sr.record_time = latest.max_time " +
                "WHERE sr.disk_id = ? " +
                "ORDER BY sr.attribute_id";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, diskId);
            stmt.setTimestamp(2, Timestamp.valueOf(beforeTime));
            stmt.setLong(3, diskId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    records.add(mapResultSetToRecord(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to query latest SMART attributes before {} for disk id {}", beforeTime, diskId, e);
        }

        return records;
    }

    /**
     * 获取指定硬盘的所有SMART记录
     */
    public List<SmartRecord> findByDiskId(Long diskId) {
        List<SmartRecord> records = new ArrayList<>();
        String sql = "SELECT * FROM smart_records " +
                "WHERE disk_id = ? " +
                "ORDER BY record_time ASC, attribute_id ASC";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, diskId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    records.add(mapResultSetToRecord(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to query all SMART records for disk id {}", diskId, e);
        }

        return records;
    }

    /**
     * ResultSet映射到SmartRecord对象
     */
    private SmartRecord mapResultSetToRecord(ResultSet rs) throws SQLException {
        Integer temp = rs.getInt("temperature");
        if (temp == -1) {
            temp = null;
        }

        return SmartRecord.builder()
                .id(rs.getLong("id"))
                .diskId(rs.getLong("disk_id"))
                .attributeId(rs.getInt("attribute_id"))
                .attributeName(rs.getString("attribute_name"))
                .currentValue(rs.getInt("current_value"))
                .worstValue(rs.getInt("worst_value"))
                .threshold(rs.getInt("threshold"))
                .rawValue(rs.getLong("raw_value"))
                .failed(rs.getInt("failed") == 1)
                .temperature(temp)
                .recordTime(rs.getTimestamp("record_time").toLocalDateTime())
                .build();
    }
}
