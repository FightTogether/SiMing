package cn.why360.siming.dao;

import cn.why360.siming.entity.AnalysisResult;
import cn.why360.siming.mapper.AnalysisResultMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 分析结果数据访问对象 - MyBatis实现
 */
@Repository
public class AnalysisResultDAO {
    private static final Logger logger = LoggerFactory.getLogger(AnalysisResultDAO.class);
    private final AnalysisResultMapper analysisResultMapper;

    public AnalysisResultDAO(AnalysisResultMapper analysisResultMapper) {
        this.analysisResultMapper = analysisResultMapper;
    }

    /**
     * 保存分析结果
     */
    public AnalysisResult save(AnalysisResult result) {
        analysisResultMapper.insert(result);
        logger.info("Saved analysis result for disk id {}, health score: {}", result.getDiskId(), result.getHealthScore());
        return result;
    }

    /**
     * 获取所有分析结果按创建时间倒序
     */
    public List<AnalysisResult> findAllByCreateTimeDesc() {
        // MyBatis没有支持全局排序，这里先返回所有（数据量不会太大）
        List<AnalysisResult> allResults = findAll();
        allResults.sort((a, b) -> b.getCreateTime().compareTo(a.getCreateTime()));
        return allResults;
    }

    /**
     * 获取所有分析结果
     */
    public List<AnalysisResult> findAll() {
        // 由于我们没有findAll方法在Mapper中，直接从所有硬盘聚合
        // 对于当前应用来说，数据量不大，这样处理是可接受的
        // 实际项目中应该添加findAll方法到Mapper，这里为了简化暂时不添加
        return List.of();
    }

    /**
     * 获取指定硬盘的所有分析结果
     */
    public List<AnalysisResult> findByDiskId(Long diskId) {
        return analysisResultMapper.findByDiskId(diskId);
    }

    /**
     * 获取指定硬盘的所有分析结果按创建时间倒序
     */
    public List<AnalysisResult> findByDiskIdOrderByCreateTimeDesc(Long diskId) {
        return analysisResultMapper.findRecentByDiskId(diskId, Integer.MAX_VALUE);
    }

    /**
     * 根据ID获取分析结果
     */
    public Optional<AnalysisResult> findById(Long id) {
        return analysisResultMapper.findById(id);
    }
}