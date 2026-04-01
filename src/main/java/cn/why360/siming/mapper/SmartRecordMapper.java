package cn.why360.siming.mapper;

import cn.why360.siming.entity.SmartRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * SMART监控记录Mapper接口
 */
@Mapper
public interface SmartRecordMapper {

    /**
     * 根据硬盘ID获取所有SMART记录
     */
    @Select("SELECT * FROM smart_records WHERE disk_id = #{diskId} ORDER BY record_time DESC, attribute_id")
    @Results(id = "smartRecordResultMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "diskId", column = "disk_id"),
            @Result(property = "attributeId", column = "attribute_id"),
            @Result(property = "attributeName", column = "attribute_name"),
            @Result(property = "currentValue", column = "current_value"),
            @Result(property = "worstValue", column = "worst_value"),
            @Result(property = "threshold", column = "threshold"),
            @Result(property = "rawValue", column = "raw_value"),
            @Result(property = "failed", column = "failed"),
            @Result(property = "temperature", column = "temperature"),
            @Result(property = "recordTime", column = "record_time")
    })
    List<SmartRecord> findByDiskId(Long diskId);

    /**
     * 获取最近N条记录
     */
    @Select("SELECT * FROM smart_records WHERE disk_id = #{diskId} ORDER BY record_time DESC LIMIT #{limit}")
    @ResultMap("smartRecordResultMap")
    List<SmartRecord> findRecentByDiskId(@Param("diskId") Long diskId, @Param("limit") int limit);

    /**
     * 获取指定硬盘指定属性的最新记录
     */
    @Select("SELECT * FROM smart_records WHERE disk_id = #{diskId} AND attribute_id = #{attributeId} ORDER BY record_time DESC LIMIT 1")
    @ResultMap("smartRecordResultMap")
    SmartRecord findLatestByDiskIdAndAttributeId(@Param("diskId") Long diskId, @Param("attributeId") Integer attributeId);

    /**
     * 批量插入SMART记录
     */
    @Insert({
            "<script>",
            "INSERT INTO smart_records (disk_id, attribute_id, attribute_name, current_value, worst_value, threshold, raw_value, failed, temperature) VALUES ",
            "<foreach collection='records' item='record' separator=','>",
            "(#{record.diskId}, #{record.attributeId}, #{record.attributeName}, #{record.currentValue}, #{record.worstValue}, #{record.threshold}, #{record.rawValue}, #{record.failed}, #{record.temperature})",
            "</foreach>",
            "</script>"
    })
    int batchInsert(@Param("records") List<SmartRecord> records);

    /**
     * 插入单条SMART记录
     */
    @Insert("INSERT INTO smart_records (disk_id, attribute_id, attribute_name, current_value, worst_value, threshold, raw_value, failed, temperature) " +
            "VALUES (#{record.diskId}, #{record.attributeId}, #{record.attributeName}, #{record.currentValue}, #{record.worstValue}, " +
            "#{record.threshold}, #{record.rawValue}, #{record.failed}, #{record.temperature})")
    @SelectKey(statement = "SELECT last_insert_rowid()", keyProperty = "record.id", before = false, resultType = Long.class)
    int insert(@Param("record") SmartRecord record);

    /**
     * 删除指定硬盘的所有记录
     */
    @Delete("DELETE FROM smart_records WHERE disk_id = #{diskId}")
    int deleteByDiskId(Long diskId);

    /**
     * 统计指定硬盘的记录数量
     */
    @Select("SELECT COUNT(*) FROM smart_records WHERE disk_id = #{diskId}")
    long countByDiskId(Long diskId);
}