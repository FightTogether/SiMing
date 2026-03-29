package cn.why360.siming.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 应用配置类
 * 从application.properties加载配置
 * LLM配置已迁移到数据库llm_config表，支持在线修改
 */
@Data
@Component
@ConfigurationProperties(prefix = "siming")
public class SimingConfig {
    private Database database = new Database();
    private Monitor monitor = new Monitor();
    private boolean localMode = true;

    @Data
    public static class Database {
        private String databasePath = "./data/siming.db";
    }

    @Data
    public static class Monitor {
        private String defaultCron = "0 0 2 * * ?";
        private boolean autoStart = true;
    }
}