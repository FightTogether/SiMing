package cn.why360.siming.mapper;

import cn.why360.siming.entity.Disk;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Optional;

/**
 * 硬盘Mapper接口
 */
@Mapper
public interface DiskMapper {

    /**
     * 获取所有硬盘
     */
    @Select("SELECT * FROM disks ORDER BY client_id, device_path")
    @Results(id = "diskResultMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "clientId", column = "client_id"),
            @Result(property = "devicePath", column = "device_path"),
            @Result(property = "brand", column = "brand"),
            @Result(property = "model", column = "model"),
            @Result(property = "serialNumber", column = "serial_number"),
            @Result(property = "totalCapacity", column = "total_capacity"),
            @Result(property = "SSD", column = "is_ssd"),
            @Result(property = "monitored", column = "monitored"),
            @Result(property = "monitorCron", column = "monitor_cron"),
            @Result(property = "createTime", column = "create_time"),
            @Result(property = "updateTime", column = "update_time")
    })
    List<Disk> findAll();

    /**
     * 获取所有需要监控的硬盘
     */
    @Select("SELECT * FROM disks WHERE monitored = 1 ORDER BY client_id, device_path")
    @ResultMap("diskResultMap")
    List<Disk> findMonitored();

    /**
     * 根据设备路径查找
     */
    @Select("SELECT * FROM disks WHERE device_path = #{devicePath}")
    @ResultMap("diskResultMap")
    Optional<Disk> findByDevicePath(String devicePath);

    /**
     * 根据客户端ID和设备路径查找硬盘
     */
    @Select("SELECT * FROM disks WHERE client_id = #{clientId} AND device_path = #{devicePath}")
    @ResultMap("diskResultMap")
    Optional<Disk> findByClientIdAndDevicePath(@Param("clientId") String clientId, @Param("devicePath") String devicePath);

    /**
     * 根据ID查找
     */
    @Select("SELECT * FROM disks WHERE id = #{id}")
    @ResultMap("diskResultMap")
    Optional<Disk> findById(Long id);

    /**
     * 保存硬盘（插入或更新）
     */
    @Insert("INSERT INTO disks (client_id, device_path, brand, model, serial_number, total_capacity, is_ssd, monitored, monitor_cron) " +
            "VALUES (#{disk.clientId}, #{disk.devicePath}, #{disk.brand}, #{disk.model}, #{disk.serialNumber}, #{disk.totalCapacity}, " +
            "#{disk.SSD}, #{disk.monitored}, #{disk.monitorCron}) " +
            "ON CONFLICT(client_id, device_path) DO UPDATE SET " +
            "  brand = excluded.brand, " +
            "  model = excluded.model, " +
            "  serial_number = excluded.serial_number, " +
            "  total_capacity = excluded.total_capacity, " +
            "  is_ssd = excluded.is_ssd, " +
            "  monitored = excluded.monitored, " +
            "  monitor_cron = excluded.monitor_cron, " +
            "  update_time = CURRENT_TIMESTAMP")
    @SelectKey(statement = "SELECT last_insert_rowid()", keyProperty = "disk.id", before = false, resultType = Long.class)
    int save(@Param("disk") Disk disk);

    /**
     * 更新监控设置
     */
    @Update("UPDATE disks SET monitored = #{monitored}, monitor_cron = #{cron}, update_time = CURRENT_TIMESTAMP WHERE id = #{id}")
    int updateMonitoring(@Param("id") Long id, @Param("monitored") boolean monitored, @Param("cron") String cron);

    /**
     * 删除硬盘
     */
    @Delete("DELETE FROM disks WHERE id = #{id}")
    int delete(Long id);
}