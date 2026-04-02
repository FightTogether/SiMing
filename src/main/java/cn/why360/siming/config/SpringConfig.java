package cn.why360.siming.config;

import cn.why360.siming.dao.CapacityRecordDAO;
import cn.why360.siming.dao.DiskDAO;
import cn.why360.siming.dao.SmartRecordDAO;
import cn.why360.siming.dao.AnalysisResultDAO;
import cn.why360.siming.dao.LlmConfigDAO;
import cn.why360.siming.database.DatabaseManager;
import cn.why360.siming.service.CapacityMonitorService;
import cn.why360.siming.service.DiskDiscoveryService;
import cn.why360.siming.service.LlmAnalysisService;
import cn.why360.siming.service.SmartReaderService;
import com.zaxxer.hikari.HikariDataSource;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Spring配置类 - 注册所有Bean
 */
@Configuration
@MapperScan("cn.why360.siming.mapper")
public class SpringConfig {

    @Bean
    public DataSource dataSource(SimingConfig config) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:sqlite:" + config.getDatabase().getDatabasePath());
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setMaximumPoolSize(1);
        dataSource.setPoolName("SiMingHikariPool");
        return dataSource;
    }

    @Bean
    public DatabaseManager databaseManager(SimingConfig config) {
        return new DatabaseManager(config);
    }

    @Bean
    public DiskDiscoveryService diskDiscoveryService() {
        return new DiskDiscoveryService();
    }

    @Bean
    public CapacityMonitorService capacityMonitorService(CapacityRecordDAO capacityRecordDAO) {
        return new CapacityMonitorService(capacityRecordDAO);
    }

    @Bean
    public SmartReaderService smartReaderService() {
        return new SmartReaderService();
    }

    @Bean
    public LlmAnalysisService llmAnalysisService(LlmConfigDAO llmConfigDAO, AnalysisResultDAO analysisResultDAO,
                                               CapacityRecordDAO capacityRecordDAO, SmartRecordDAO smartRecordDAO) {
        return new LlmAnalysisService(llmConfigDAO, analysisResultDAO, capacityRecordDAO, smartRecordDAO);
    }
}
