package cn.why360.siming.dao;

import cn.why360.siming.database.DatabaseManager;
import cn.why360.siming.entity.Disk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 硬盘数据访问对象
 */
public class DiskDAO {
    private static final Logger logger = LoggerFactory.getLogger(DiskDAO.class);
    private final DatabaseManager databaseManager;

    public DiskDAO(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * 获取所有硬盘
     */
    public List<Disk> findAll() {
        List<Disk> disks = new ArrayList<>();
        String sql = "SELECT * FROM disks ORDER BY device_path";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                disks.add(mapResultSetToDisk(rs));
            }
        } catch (SQLException e) {
            logger.error("Failed to query all disks", e);
        }

        return disks;
    }

    /**
     * 获取所有需要监控的硬盘
     */
    public List<Disk> findMonitored() {
        List<Disk> disks = new ArrayList<>();
        String sql = "SELECT * FROM disks WHERE monitored = 1 ORDER BY device_path";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                disks.add(mapResultSetToDisk(rs));
            }
        } catch (SQLException e) {
            logger.error("Failed to query monitored disks", e);
        }

        return disks;
    }

    /**
     * 根据设备路径查找
     */
    public Optional<Disk> findByDevicePath(String devicePath) {
        String sql = "SELECT * FROM disks WHERE device_path = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, devicePath);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToDisk(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to find disk by device path: {}", devicePath, e);
        }

        return Optional.empty();
    }

    /**
     * 根据ID查找
     */
    public Optional<Disk> findById(Long id) {
        String sql = "SELECT * FROM disks WHERE id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToDisk(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to find disk by id: {}", id, e);
        }

        return Optional.empty();
    }

    /**
     * 插入新硬盘
     */
    public Disk insert(Disk disk) {
        return save(disk);
    }

    /**
     * 保存硬盘（插入或更新）
     */
    public Disk save(Disk disk) {
        String sql = "INSERT INTO disks (device_path, brand, model, serial_number, total_capacity, is_ssd, monitored, monitor_cron) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(device_path) DO UPDATE SET " +
                "  brand = excluded.brand, " +
                "  model = excluded.model, " +
                "  serial_number = excluded.serial_number, " +
                "  total_capacity = excluded.total_capacity, " +
                "  is_ssd = excluded.is_ssd, " +
                "  update_time = CURRENT_TIMESTAMP " +
                "RETURNING id";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, disk.getDevicePath());
            stmt.setString(2, disk.getBrand());
            stmt.setString(3, disk.getModel());
            stmt.setString(4, disk.getSerialNumber());
            stmt.setLong(5, disk.getTotalCapacity());
            stmt.setInt(6, disk.isSSD() ? 1 : 0);
            stmt.setInt(7, disk.isMonitored() ? 1 : 0);
            stmt.setString(8, disk.getMonitorCron());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    disk.setId(rs.getLong(1));
                }
            }

            logger.info("Saved disk: {}", disk.getDevicePath());
        } catch (SQLException e) {
            logger.error("Failed to save disk: {}", disk.getDevicePath(), e);
            throw new RuntimeException("Failed to save disk", e);
        }

        return disk;
    }

    /**
     * 更新监控设置
     */
    public void updateMonitoring(Long diskId, boolean monitored, String cron) {
        String sql = "UPDATE disks SET monitored = ?, monitor_cron = ?, update_time = CURRENT_TIMESTAMP WHERE id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, monitored ? 1 : 0);
            stmt.setString(2, cron);
            stmt.setLong(3, diskId);
            stmt.executeUpdate();

            logger.info("Updated monitoring for disk id {}: monitored={}, cron={}", diskId, monitored, cron);
        } catch (SQLException e) {
            logger.error("Failed to update monitoring for disk id {}", diskId, e);
            throw new RuntimeException("Failed to update monitoring", e);
        }
    }

    /**
     * 删除硬盘
     */
    public void delete(Long id) {
        String sql = "DELETE FROM disks WHERE id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, id);
            stmt.executeUpdate();

            logger.info("Deleted disk id {}", id);
        } catch (SQLException e) {
            logger.error("Failed to delete disk id {}", id, e);
            throw new RuntimeException("Failed to delete disk", e);
        }
    }

    /**
     * ResultSet映射到Disk对象
     */
    private Disk mapResultSetToDisk(ResultSet rs) throws SQLException {
        return Disk.builder()
                .id(rs.getLong("id"))
                .devicePath(rs.getString("device_path"))
                .brand(rs.getString("brand"))
                .model(rs.getString("model"))
                .serialNumber(rs.getString("serial_number"))
                .totalCapacity(rs.getLong("total_capacity"))
                .isSSD(rs.getInt("is_ssd") == 1)
                .monitored(rs.getInt("monitored") == 1)
                .monitorCron(rs.getString("monitor_cron"))
                .createTime(rs.getTimestamp("create_time").toLocalDateTime())
                .updateTime(rs.getTimestamp("update_time").toLocalDateTime())
                .build();
    }
}