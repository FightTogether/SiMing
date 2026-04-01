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
        // 复制到可变列表再排序
        List<AnalysisResult> mutableResults = new java.util.ArrayList<>(allResults);
        mutableResults.sort((a, b) -> b.getCreateTime().compareTo(a.getCreateTime()));
        return mutableResults;
    }

    /**
     * 获取所有分析结果
     */
    public List<AnalysisResult> findAll() {
        return analysisResultMapper.findAll();
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