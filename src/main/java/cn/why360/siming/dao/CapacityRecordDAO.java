package cn.why360.siming.dao;

import cn.why360.siming.database.DatabaseManager;
import cn.why360.siming.entity.CapacityRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 容量监控记录数据访问对象
 */
public class CapacityRecordDAO {
    private static final Logger logger = LoggerFactory.getLogger(CapacityRecordDAO.class);
    private final DatabaseManager databaseManager;

    public CapacityRecordDAO(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * 插入容量记录
     */
    public void insert(CapacityRecord record) {
        String sql = "INSERT INTO capacity_records (disk_id, used_capacity, available_capacity, usage_percent, mount_point) " +
                "VALUES (?, ?, ?, ?, ?)";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, record.getDiskId());
            stmt.setLong(2, record.getUsedCapacity());
            stmt.setLong(3, record.getAvailableCapacity());
            stmt.setDouble(4, record.getUsagePercent());
            stmt.setString(5, record.getMountPoint());
            stmt.executeUpdate();

            logger.debug("Inserted capacity record for disk id {}", record.getDiskId());
        } catch (SQLException e) {
            logger.error("Failed to insert capacity record for disk id {}", record.getDiskId(), e);
            throw new RuntimeException("Failed to insert capacity record", e);
        }
    }

    /**
     * 获取指定硬盘在时间区间内的容量记录
     */
    public List<CapacityRecord> findByDiskIdAndTimeRange(Long diskId, LocalDateTime startTime, LocalDateTime endTime) {
        List<CapacityRecord> records = new ArrayList<>();
        String sql = "SELECT * FROM capacity_records " +
                "WHERE disk_id = ? AND record_time BETWEEN ? AND ? " +
                "ORDER BY record_time ASC";

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
            logger.error("Failed to query capacity records for disk id {}", diskId, e);
        }

        return records;
    }

    /**
     * 获取指定硬盘最近N条记录
     */
    public List<CapacityRecord> findRecentByDiskId(Long diskId, int limit) {
        List<CapacityRecord> records = new ArrayList<>();
        String sql = "SELECT * FROM capacity_records " +
                "WHERE disk_id = ? " +
                "ORDER BY record_time DESC " +
                "LIMIT ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, diskId);
            stmt.setInt(2, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    records.add(mapResultSetToRecord(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to query recent capacity records for disk id {}", diskId, e);
        }

        return records;
    }

    /**
     * 获取指定硬盘在截止时间之前最新的一条容量记录
     * 用于四个时间点对比分析
     */
    public CapacityRecord findLatestBefore(Long diskId, LocalDateTime beforeTime) {
        String sql = "SELECT * FROM capacity_records " +
                "WHERE disk_id = ? AND record_time <= ? " +
                "ORDER BY record_time DESC " +
                "LIMIT 1";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, diskId);
            stmt.setTimestamp(2, Timestamp.valueOf(beforeTime));

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapResultSetToRecord(rs);
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to query latest capacity before {} for disk id {}", beforeTime, diskId, e);
        }

        return null;
    }

    /**
     * 获取指定硬盘的所有容量记录
     */
    public List<CapacityRecord> findByDiskId(Long diskId) {
        List<CapacityRecord> records = new ArrayList<>();
        String sql = "SELECT * FROM capacity_records " +
                "WHERE disk_id = ? " +
                "ORDER BY record_time ASC";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, diskId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    records.add(mapResultSetToRecord(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to query all capacity records for disk id {}", diskId, e);
        }

        return records;
    }

    /**
     * ResultSet映射到CapacityRecord对象
     */
    private CapacityRecord mapResultSetToRecord(ResultSet rs) throws SQLException {
        return CapacityRecord.builder()
                .id(rs.getLong("id"))
                .diskId(rs.getLong("disk_id"))
                .usedCapacity(rs.getLong("used_capacity"))
                .availableCapacity(rs.getLong("available_capacity"))
                .usagePercent(rs.getDouble("usage_percent"))
                .mountPoint(rs.getString("mount_point"))
                .recordTime(rs.getTimestamp("record_time").toLocalDateTime())
                .build();
    }
}
