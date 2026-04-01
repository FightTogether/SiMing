package cn.why360.siming.mapper;

import cn.why360.siming.entity.AnalysisResult;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Optional;

/**
 * 大模型分析结果Mapper接口
 */
@Mapper
public interface AnalysisResultMapper {

    /**
     * 根据硬盘ID获取所有分析结果
     */
    @Select("SELECT * FROM analysis_results WHERE disk_id = #{diskId} ORDER BY create_time DESC")
    @Results(id = "analysisResultResultMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "diskId", column = "disk_id"),
            @Result(property = "startTime", column = "start_time"),
            @Result(property = "endTime", column = "end_time"),
            @Result(property = "analysisContent", column = "analysis_content"),
            @Result(property = "healthScore", column = "health_score"),
            @Result(property = "healthLevel", column = "health_level"),
            @Result(property = "recommendations", column = "recommendations"),
            @Result(property = "createTime", column = "create_time")
    })
    List<AnalysisResult> findByDiskId(Long diskId);

    /**
     * 获取最近N条分析结果
     */
    @Select("SELECT * FROM analysis_results WHERE disk_id = #{diskId} ORDER BY create_time DESC LIMIT #{limit}")
    @ResultMap("analysisResultResultMap")
    List<AnalysisResult> findRecentByDiskId(@Param("diskId") Long diskId, @Param("limit") int limit);

    /**
     * 根据ID查找
     */
    @Select("SELECT * FROM analysis_results WHERE id = #{id}")
    @ResultMap("analysisResultResultMap")
    Optional<AnalysisResult> findById(Long id);

    /**
     * 插入分析结果
     */
    @Insert("INSERT INTO analysis_results (disk_id, start_time, end_time, analysis_content, health_score, health_level, recommendations) " +
            "VALUES (#{result.diskId}, #{result.startTime}, #{result.endTime}, #{result.analysisContent}, " +
            "#{result.healthScore}, #{result.healthLevel}, #{result.recommendations})")
    @SelectKey(statement = "SELECT last_insert_rowid()", keyProperty = "result.id", before = false, resultType = Long.class)
    int insert(@Param("result") AnalysisResult result);

    /**
     * 删除指定硬盘的所有分析结果
     */
    @Delete("DELETE FROM analysis_results WHERE disk_id = #{diskId}")
    int deleteByDiskId(Long diskId);

    /**
     * 统计指定硬盘的分析结果数量
     */
    @Select("SELECT COUNT(*) FROM analysis_results WHERE disk_id = #{diskId}")
    long countByDiskId(Long diskId);

    /**
     * 获取所有分析结果
     */
    @Select("SELECT * FROM analysis_results")
    @ResultMap("analysisResultResultMap")
    List<AnalysisResult> findAll();
}
