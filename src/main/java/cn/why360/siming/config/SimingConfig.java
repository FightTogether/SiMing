package cn.why360.siming.config;

import lombok.Data;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.InputStream;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * 应用配置类
 */
@Data
public class SimingConfig {

    private WebConfig web = new WebConfig();
    private DatabaseConfig database = new DatabaseConfig();
    private MonitorConfig monitor = new MonitorConfig();
    private LlmConfig llm = new LlmConfig();

    /**
     * Web服务配置
     */
    @Data
    public static class WebConfig {
        private boolean enabled = true;
        private int port = 8080;
    }

    /**
     * 从文件加载配置
     */
    public static SimingConfig loadFromFile(String path) throws IOException {
        LoaderOptions options = new LoaderOptions();
        Yaml yaml = new Yaml(new Constructor(SimingConfig.class, options));
        try (InputStream inputStream = new FileInputStream(path)) {
            return yaml.load(inputStream);
        }
    }

    /**
     * 数据库配置
     */
    @Data
    public static class DatabaseConfig {
        private String path = "./data/siming.db";
        private String databasePath = "./data/siming.db";
    }

    /**
     * 监控配置
     */
    @Data
    public static class MonitorConfig {
        private String defaultCron = "0 0 2 * * ?";
        private boolean autoStart = true;
    }

    /**
     * 大模型配置
     */
    @Data
    public static class LlmConfig {
        private String apiBaseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private String model = "gpt-3.5-turbo";
        private int timeout = 60000;
        private String promptTemplate = "";
    }
}