package cn.why360.siming;

import cn.why360.siming.config.SimingConfig;
import cn.why360.siming.dao.DiskDAO;
import cn.why360.siming.dao.SmartRecordDAO;
import cn.why360.siming.entity.Disk;
import cn.why360.siming.scheduler.SchedulerManager;
import cn.why360.siming.service.CapacityMonitorService;
import cn.why360.siming.service.DiskDiscoveryService;
import cn.why360.siming.service.LlmAnalysisService;
import cn.why360.siming.service.SmartReaderService;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.Optional;

/**
 * 司命 - 硬件守护者主应用程序
 * Spring Boot版本
 */
@SpringBootApplication
@EnableConfigurationProperties
public class SimingApplication {
    private static final Logger logger = LoggerFactory.getLogger(SimingApplication.class);

    public static void main(String[] args) {
        try {
            logger.info("=" + "=".repeat(50));
            logger.info("  司命 (SiMing) - 硬件守护者");
            logger.info("  版本: 1.0.0");
            logger.info("  GitHub: https://github.com/why360/SiMing");
            logger.info("=" + "=".repeat(50));

            // 启动Spring Boot - Spring Boot自动处理DispatcherServlet和所有@Controller注解
            ConfigurableApplicationContext context = SpringApplication.run(SimingApplication.class, args);

            // 获取配置和Bean
            SimingConfig config = context.getBean(SimingConfig.class);
            DiskDAO diskDAO = context.getBean(DiskDAO.class);
            DiskDiscoveryService discoveryService = context.getBean(DiskDiscoveryService.class);
            CapacityMonitorService capacityService = context.getBean(CapacityMonitorService.class);
            SmartReaderService smartReaderService = context.getBean(SmartReaderService.class);
            LlmAnalysisService llmAnalysisService = context.getBean(LlmAnalysisService.class);

            logger.info("Configuration loaded from application.properties");

            // 如果是本地模式，发现硬盘并启动调度
            if (config.isLocalMode()) {
                // 发现硬盘
                discoverAndSaveDisks(discoveryService, diskDAO);
                // 启动调度器
                SchedulerManager schedulerManager = new SchedulerManager(
                        config.getMonitor(),
                        diskDAO,
                        capacityService,
                        smartReaderService,
                        context.getBean(SmartRecordDAO.class),
                        llmAnalysisService);
                startScheduler(schedulerManager);
                logger.info("Local mode: disk discovery and monitoring scheduler started");
            } else {
                logger.info("Running in server-only mode, all data will come from remote clients");
            }

            // Spring Boot已经自动启动Tomcat，不需要手动启动Web服务器
            logger.info("SiMing started successfully!");
            int port = context.getEnvironment().getProperty("server.port", Integer.class, 8080);
            logger.info("Web interface available at http://localhost:{}", port);

        } catch (Exception e) {
            logger.error("Application failed to start", e);
            System.exit(1);
        }
    }

    /**
     * 发现硬盘并保存到数据库
     */
    private static void discoverAndSaveDisks(DiskDiscoveryService discoveryService, DiskDAO diskDAO) {
        logger.info("Discovering disks...");
        List<Disk> disks = discoveryService.discoverDisks();

        if (disks.isEmpty()) {
            logger.warn("No disks discovered on this system");
            return;
        }

        logger.info("Found {} disk(s)", disks.size());

        // 保存到数据库，如果设备路径已存在则跳过
        for (Disk disk : disks) {
            Optional<Disk> existing = diskDAO.findByDevicePath(disk.getDevicePath());
            if (existing.isEmpty()) {
                diskDAO.insert(disk);
                logger.info("Added new disk: {} {} ({})",
                        disk.getBrand(), disk.getModel(), disk.getDevicePath());
            } else {
                logger.debug("Disk {} already exists, skipping", disk.getDevicePath());
            }
        }
    }

    /**
     * 启动调度器
     */
    private static void startScheduler(SchedulerManager schedulerManager) throws SchedulerException {
        schedulerManager.start();
        logger.info("Monitoring scheduler started");
    }
}
