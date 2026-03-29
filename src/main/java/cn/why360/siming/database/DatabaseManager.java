package cn.why360.siming.database;

import cn.why360.siming.config.SimingConfig;
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
    private final SimingConfig.DatabaseConfig config;

    public DatabaseManager(SimingConfig config) {
        this.config = config.getDatabase();

        // 确保数据目录存在
        String dbPath = config.getDatabase().getDatabasePath() != null ?
                config.getDatabase().getDatabasePath() : config.getDatabase().getPath();
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
            "  device_path TEXT NOT NULL UNIQUE," +
            "  brand TEXT," +
            "  model TEXT," +
            "  serial_number TEXT," +
            "  total_capacity INTEGER NOT NULL," +
            "  is_ssd INTEGER NOT NULL DEFAULT 0," +
            "  monitored INTEGER NOT NULL DEFAULT 0," +
            "  monitor_cron TEXT," +
            "  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
            "  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP" +
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
            "  start_time TIMESTAMP NOT NULL," +
            "  end_time TIMESTAMP NOT NULL," +
            "  analysis_content TEXT," +
            "  health_score INTEGER," +
            "  health_level TEXT," +
            "  recommendations TEXT," +
            "  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
            "  FOREIGN KEY (disk_id) REFERENCES disks(id)" +
            ")"
        };

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            for (String sql : createTables) {
                stmt.execute(sql);
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