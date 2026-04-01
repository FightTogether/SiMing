package cn.why360.siming.mapper;

import cn.why360.siming.entity.LlmConfig;
import org.apache.ibatis.annotations.*;

import java.util.Optional;

/**
 * LLM配置Mapper接口
 */
@Mapper
public interface LlmConfigMapper {

    /**
     * 获取当前LLM配置（只有一条记录）
     */
    @Select("SELECT * FROM llm_config ORDER BY id DESC LIMIT 1")
    @Results(id = "llmConfigResultMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "apiBaseUrl", column = "api_base_url"),
            @Result(property = "apiKey", column = "api_key"),
            @Result(property = "model", column = "model"),
            @Result(property = "timeout", column = "timeout"),
            @Result(property = "temperature", column = "temperature"),
            @Result(property = "maxTokens", column = "max_tokens"),
            @Result(property = "promptTemplate", column = "prompt_template"),
            @Result(property = "createTime", column = "create_time"),
            @Result(property = "updateTime", column = "update_time")
    })
    Optional<LlmConfig> getCurrent();

    /**
     * 保存LLM配置（插入新记录）
     */
    @Insert("INSERT INTO llm_config (api_base_url, api_key, model, timeout, temperature, max_tokens, prompt_template) " +
            "VALUES (#{config.apiBaseUrl}, #{config.apiKey}, #{config.model}, #{config.timeout}, " +
            "#{config.temperature}, #{config.maxTokens}, #{config.promptTemplate})")
    @SelectKey(statement = "SELECT last_insert_rowid()", keyProperty = "config.id", before = false, resultType = Long.class)
    int insert(@Param("config") LlmConfig config);

    /**
     * 更新现有配置
     */
    @Update("UPDATE llm_config SET api_base_url = #{config.apiBaseUrl}, api_key = #{config.apiKey}, model = #{config.model}, " +
            "timeout = #{config.timeout}, temperature = #{config.temperature}, max_tokens = #{config.maxTokens}, " +
            "prompt_template = #{config.promptTemplate}, update_time = CURRENT_TIMESTAMP WHERE id = #{config.id}")
    int update(@Param("config") LlmConfig config);
}