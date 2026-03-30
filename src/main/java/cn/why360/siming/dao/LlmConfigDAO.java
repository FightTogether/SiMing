package cn.why360.siming.dao;

import cn.why360.siming.database.DatabaseManager;
import cn.why360.siming.entity.LlmConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.Optional;

/**
 * LLM配置数据访问对象
 */
public class LlmConfigDAO {
    private static final Logger logger = LoggerFactory.getLogger(LlmConfigDAO.class);
    private final DatabaseManager databaseManager;

    public LlmConfigDAO(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * 获取当前LLM配置（只有一条记录）
     */
    public Optional<LlmConfig> getCurrent() {
        String sql = "SELECT * FROM llm_config ORDER BY id DESC LIMIT 1";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                return Optional.of(mapResultSetToLlmConfig(rs));
            }
        } catch (SQLException e) {
            logger.error("Failed to get current LLM config", e);
        }

        return Optional.empty();
    }

    /**
     * 保存LLM配置（插入新记录）
     */
    public LlmConfig save(LlmConfig config) {
        String sql = "INSERT INTO llm_config (api_base_url, api_key, model, timeout, temperature, max_tokens, prompt_template) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "RETURNING id";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, config.getApiBaseUrl());
            stmt.setString(2, config.getApiKey());
            stmt.setString(3, config.getModel());
            stmt.setInt(4, config.getTimeout() != null ? config.getTimeout() : 60000);
            stmt.setObject(5, config.getTemperature());
            stmt.setObject(6, config.getMaxTokens());
            stmt.setString(7, config.getPromptTemplate());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    config.setId(rs.getLong(1));
                }
            }

            logger.info("Saved new LLM configuration, id: {}", config.getId());
        } catch (SQLException e) {
            logger.error("Failed to save LLM config", e);
            throw new RuntimeException("Failed to save LLM config", e);
        }

        return config;
    }

    /**
     * 更新现有配置
     */
    public void update(LlmConfig config) {
        String sql = "UPDATE llm_config SET api_base_url = ?, api_key = ?, model = ?, " +
                "timeout = ?, temperature = ?, max_tokens = ?, prompt_template = ?, update_time = CURRENT_TIMESTAMP WHERE id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, config.getApiBaseUrl());
            stmt.setString(2, config.getApiKey());
            stmt.setString(3, config.getModel());
            stmt.setInt(4, config.getTimeout() != null ? config.getTimeout() : 60000);
            stmt.setObject(5, config.getTemperature());
            stmt.setObject(6, config.getMaxTokens());
            stmt.setString(7, config.getPromptTemplate());
            stmt.setLong(8, config.getId());

            stmt.executeUpdate();

            logger.info("Updated LLM configuration, id: {}", config.getId());
        } catch (SQLException e) {
            logger.error("Failed to update LLM config, id: {}", config.getId(), e);
            throw new RuntimeException("Failed to update LLM config", e);
        }
    }

    /**
     * ResultSet映射到LlmConfig对象
     */
    private LlmConfig mapResultSetToLlmConfig(ResultSet rs) throws SQLException {
        // SQLite JDBC对null值处理：必须先get，再检查wasNull
        Double temperature = null;
        double tempVal = rs.getDouble("temperature");
        if (!rs.wasNull()) {
            temperature = tempVal;
        }
        
        Integer maxTokens = null;
        int tokensVal = rs.getInt("max_tokens");
        if (!rs.wasNull()) {
            maxTokens = tokensVal;
        }
        
        return LlmConfig.builder()
                .id(rs.getLong("id"))
                .apiBaseUrl(rs.getString("api_base_url"))
                .apiKey(rs.getString("api_key"))
                .model(rs.getString("model"))
                .timeout(rs.getInt("timeout"))
                .temperature(temperature)
                .maxTokens(maxTokens)
                .promptTemplate(rs.getString("prompt_template"))
                .createTime(rs.getTimestamp("create_time").toLocalDateTime())
                .updateTime(rs.getTimestamp("update_time").toLocalDateTime())
                .build();
    }
}