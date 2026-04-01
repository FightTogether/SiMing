package cn.why360.siming.dao;

import cn.why360.siming.entity.LlmConfig;
import cn.why360.siming.mapper.LlmConfigMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * LLM配置数据访问对象 - MyBatis实现
 */
@Repository
public class LlmConfigDAO {
    private final LlmConfigMapper llmConfigMapper;

    public LlmConfigDAO(LlmConfigMapper llmConfigMapper) {
        this.llmConfigMapper = llmConfigMapper;
    }

    /**
     * 获取当前LLM配置（只有一条记录）
     */
    public Optional<LlmConfig> getCurrent() {
        return llmConfigMapper.getCurrent();
    }

    /**
     * 保存LLM配置（插入新记录）
     */
    public LlmConfig save(LlmConfig config) {
        llmConfigMapper.insert(config);
        return config;
    }

    /**
     * 更新现有配置
     */
    public void update(LlmConfig config) {
        llmConfigMapper.update(config);
    }
}