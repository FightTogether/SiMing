package cn.why360.siming.mapper;

import cn.why360.siming.entity.CapacityRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 容量监控记录Mapper接口
 */
@Mapper
public interface CapacityRecordMapper {

    /**
     * 根据硬盘ID获取所有容量记录
     */
    @Select("SELECT * FROM capacity_records WHERE disk_id = #{diskId} ORDER BY record_time DESC")
    @Results(id = "capacityRecordResultMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "diskId", column = "disk_id"),
            @Result(property = "reportId", column = "report_id"),
            @Result(property = "filesystem", column = "filesystem"),
            @Result(property = "usedCapacity", column = "used_capacity"),
            @Result(property = "availableCapacity", column = "available_capacity"),
            @Result(property = "usagePercent", column = "usage_percent"),
            @Result(property = "mountPoint", column = "mount_point"),
            @Result(property = "recordTime", column = "record_time")
    })
    List<CapacityRecord> findByDiskId(Long diskId);

    /**
     * 获取最近N条记录
     */
    @Select("SELECT * FROM capacity_records WHERE disk_id = #{diskId} ORDER BY record_time DESC LIMIT #{limit}")
    @ResultMap("capacityRecordResultMap")
    List<CapacityRecord> findRecentByDiskId(@Param("diskId") Long diskId, @Param("limit") int limit);

    /**
     * 插入容量记录
     */
    @Insert("INSERT INTO capacity_records (disk_id, report_id, used_capacity, available_capacity, usage_percent, mount_point, filesystem) " +
            "VALUES (#{record.diskId}, #{record.reportId}, #{record.usedCapacity}, #{record.availableCapacity}, #{record.usagePercent}, " +
            "#{record.mountPoint}, #{record.filesystem})")
    @SelectKey(statement = "SELECT last_insert_rowid()", keyProperty = "record.id", before = false, resultType = Long.class)
    int insert(@Param("record") CapacityRecord record);

    /**
     * 删除指定硬盘的所有记录
     */
    @Delete("DELETE FROM capacity_records WHERE disk_id = #{diskId}")
    int deleteByDiskId(Long diskId);

    /**
     * 统计指定硬盘的记录数量
     */
    @Select("SELECT COUNT(*) FROM capacity_records WHERE disk_id = #{diskId}")
    long countByDiskId(Long diskId);
}