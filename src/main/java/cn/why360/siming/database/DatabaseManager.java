package cn.why360.siming.database;

import cn.why360.siming.config.SimingConfig;
import cn.why360.siming.entity.LlmConfig;
import cn.why360.siming.dao.LlmConfigDAO;
import cn.why360.siming.service.LlmAnalysisService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库管理器
 */
public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);

    private final HikariDataSource dataSource;
    private final SimingConfig.Database config;

    public DatabaseManager(SimingConfig config) {
        this.config = config.getDatabase();

        // 确保数据目录存在
        String dbPath = this.config.getDatabasePath();
        File dbFile = new File(dbPath);
        File dbDir = dbFile.getParentFile();
        if (dbDir != null && !dbDir.exists()) {
            boolean created = dbDir.mkdirs();
            if (created) {
                logger.info("Created data directory: {}", dbDir.getAbsolutePath());
            }
        }

        // 配置连接池
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:sqlite:" + dbPath);
        hikariConfig.setDriverClassName("org.sqlite.JDBC");
        hikariConfig.setMaximumPoolSize(5);
        hikariConfig.setConnectionTestQuery("SELECT 1");
        hikariConfig.setIdleTimeout(30000);

        this.dataSource = new HikariDataSource(hikariConfig);

        // 初始化表结构
        initDatabase();
    }

    /**
     * 初始化数据库表
     */
    public void initDatabase() {
        String[] createTables = {
            // 硬盘表
            "CREATE TABLE IF NOT EXISTS disks (" +
            "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  client_id TEXT," +
            "  device_path TEXT NOT NULL," +
            "  brand TEXT," +
            "  model TEXT," +
            "  serial_number TEXT," +
            "  total_capacity INTEGER NOT NULL," +
            "  is_ssd INTEGER NOT NULL DEFAULT 0," +
            "  monitored INTEGER NOT NULL DEFAULT 0," +
            "  monitor_cron TEXT," +
            "  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
            "  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
            "  UNIQUE(client_id, device_path)" +
            ")",

            // 容量监控记录表
            "CREATE TABLE IF NOT EXISTS capacity_records (" +
            "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  disk_id INTEGER NOT NULL," +
            "  used_capacity INTEGER NOT NULL," +
            "  available_capacity INTEGER NOT NULL," +
            "  usage_percent REAL NOT NULL," +
            "  mount_point TEXT," +
            "  record_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
            "  FOREIGN KEY (disk_id) REFERENCES disks(id)" +
            ")",

            // SMART监控记录表
            "CREATE TABLE IF NOT EXISTS smart_records (" +
            "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  disk_id INTEGER NOT NULL," +
            "  attribute_id INTEGER NOT NULL," +
            "  attribute_name TEXT," +
            "  current_value INTEGER," +
            "  worst_value INTEGER," +
            "  threshold INTEGER," +
            "  raw_value INTEGER," +
            "  failed INTEGER NOT NULL DEFAULT 0," +
            "  temperature INTEGER," +
            "  record_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
            "  FOREIGN KEY (disk_id) REFERENCES disks(id)" +
            ")",

            // 分析结果表
            "CREATE TABLE IF NOT EXISTS analysis_results (" +
            "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  disk_id INTEGER NOT NULL," +
            "  start_time TEXT NOT NULL," +
            "  end_time TEXT NOT NULL," +
            "  analysis_content TEXT," +
            "  health_score INTEGER," +
            "  health_level TEXT," +
            "  recommendations TEXT," +
            "  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
            "  FOREIGN KEY (disk_id) REFERENCES disks(id)" +
            ")",

            // LLM配置表 - 存储大模型API配置和提示词模板
            "CREATE TABLE IF NOT EXISTS llm_config (" +
            "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  api_base_url TEXT NOT NULL," +
            "  api_key TEXT," +
            "  model TEXT NOT NULL," +
            "  timeout INTEGER NOT NULL DEFAULT 60000," +
            "  temperature REAL," +
            "  max_tokens INTEGER," +
            "  prompt_template TEXT NOT NULL," +
            "  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
            "  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP" +
            ")"
        };

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            for (String sql : createTables) {
                logger.debug("Executing DDL SQL: {}", sql);
                stmt.execute(sql);
            }
            
            // 数据库迁移：为已存在的disks表添加client_id列
            try {
                // 先尝试添加client_id列，如果表已经存在的话
                stmt.execute("ALTER TABLE disks ADD COLUMN client_id TEXT");
                try {
                    stmt.execute("CREATE UNIQUE INDEX idx_disks_client_device ON disks(client_id, device_path)");
                } catch (SQLException e2) {
                    // 索引创建失败也没关系
                    logger.info("Note: Could not create unique index (may already exist): {}", e2.getMessage());
                }
            } catch (SQLException e) {
                // 如果列已经存在，SQLite会报错，这是正常的，忽略即可
                logger.info("Note: client_id column already exists, skipping migration");
            }

            // 检查是否有LLM配置，如果没有则插入默认配置
            try {
                java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM llm_config");
                if (rs.next() && rs.getInt(1) == 0) {
                    // 插入默认配置
                    String defaultPrompt = LlmAnalysisService.getDefaultPromptTemplate();
                    stmt.execute("INSERT INTO llm_config (api_base_url, api_key, model, timeout, temperature, max_tokens, prompt_template) " +
                            "VALUES ('https://api.openai.com/v1', '', 'gpt-3.5-turbo', 60000, 0.7, 1000, '" +
                            defaultPrompt.replace("'", "''") + "')");
                    logger.info("Initialized default LLM configuration in database");
                }
                rs.close();
            } catch (SQLException e) {
                logger.warn("Could not check/initialize default LLM config: {}", e.getMessage());
            }

            // 数据库迁移：为已存在的llm_config表添加temperature和max_tokens列
            try {
                stmt.execute("ALTER TABLE llm_config ADD COLUMN temperature REAL");
                logger.info("Added temperature column to llm_config table");
            } catch (SQLException e) {
                // 如果列已经存在，SQLite会报错，这是正常的，忽略即可
                logger.info("Note: temperature column already exists in llm_config, skipping migration");
            }
            try {
                stmt.execute("ALTER TABLE llm_config ADD COLUMN max_tokens INTEGER");
                logger.info("Added max_tokens column to llm_config table");
            } catch (SQLException e) {
                // 如果列已经存在，SQLite会报错，这是正常的，忽略即可
                logger.info("Note: max_tokens column already exists in llm_config, skipping migration");
            }

            logger.info("Database initialized successfully");
        } catch (SQLException e) {
            logger.error("Failed to initialize database", e);
            throw new RuntimeException("Failed to initialize database", e);
        }
    }

    /**
     * 获取数据库连接
     */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * 关闭数据源
     */
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}