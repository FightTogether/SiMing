package cn.why360.siming.entity;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * LLM配置实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmConfig {
    private Long id;
    
    /**
     * API基础地址
     */
    private String apiBaseUrl;
    
    /**
     * API Key
     */
    private String apiKey;
    
    /**
     * 模型名称
     */
    private String model;
    
    /**
     * 请求超时（毫秒）
     */
    private Integer timeout;
    
    /**
     * 提示词模板
     */
    private String promptTemplate;
    
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}