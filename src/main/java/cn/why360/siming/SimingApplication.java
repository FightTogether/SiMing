package cn.why360.siming;

import cn.why360.siming.config.SimingConfig;
import cn.why360.siming.dao.AnalysisResultDAO;
import cn.why360.siming.dao.CapacityRecordDAO;
import cn.why360.siming.dao.DiskDAO;
import cn.why360.siming.dao.SmartRecordDAO;
import cn.why360.siming.database.DatabaseManager;
import cn.why360.siming.entity.AnalysisResult;
import cn.why360.siming.entity.Disk;
import cn.why360.siming.scheduler.SchedulerManager;
import cn.why360.siming.service.CapacityMonitorService;
import cn.why360.siming.service.DiskDiscoveryService;
import cn.why360.siming.service.LlmAnalysisService;
import cn.why360.siming.service.SmartReaderService;
import cn.why360.siming.web.WebServer;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;

/**
 * 司命 - 硬件守护者主应用程序
 */
public class SimingApplication {
    private static final Logger logger = LoggerFactory.getLogger(SimingApplication.class);

    private static SimingConfig config;
    private static DatabaseManager dbManager;
    private static DiskDAO diskDAO;
    private static CapacityRecordDAO capacityRecordDAO;
    private static SmartRecordDAO smartRecordDAO;
    private static AnalysisResultDAO analysisResultDAO;
    private static DiskDiscoveryService discoveryService;
    private static CapacityMonitorService capacityService;
    private static SmartReaderService smartReaderService;
    private static LlmAnalysisService llmAnalysisService;
    private static SchedulerManager schedulerManager;
    private static WebServer webServer;

    public static void main(String[] args) {
        try {
            logger.info("=" + "=".repeat(50));
            logger.info("  司命 (SiMing) - 硬件守护者");
            logger.info("  版本: 1.0.0");
            logger.info("  GitHub: https://github.com/why360/SiMing");
            logger.info("=" + "=".repeat(50));

            // 加载配置
            loadConfig();
            logger.info("Configuration loaded");

            // 初始化数据库
            initDatabase();
            logger.info("Database initialized at {}", config.getDatabase().getDatabasePath());

            // 初始化服务
            initServices();
            logger.info("All services initialized");

            // 发现硬盘
            discoverAndSaveDisks();

            // 启动调度器
            startScheduler();

            // 交互式CLI
            runInteractiveCli();

        } catch (Exception e) {
            logger.error("Application failed to start", e);
            System.exit(1);
        }
    }

    /**
     * 加载配置文件
     */
    private static void loadConfig() throws IOException {
        String configPath = System.getProperty("siming.config", "config/application.yml");
        java.io.File file = new java.io.File(configPath);
        if (file.exists()) {
            config = SimingConfig.loadFromFile(configPath);
        } else {
            // 创建默认配置
            config = new SimingConfig();
            if (System.getenv("OPENAI_API_KEY") != null) {
                config.getLlm().setApiKey(System.getenv("OPENAI_API_KEY"));
            }
        }
    }

    /**
     * 初始化数据库
     */
    private static void initDatabase() {
        dbManager = new DatabaseManager(config);
        dbManager.initDatabase();
        diskDAO = new DiskDAO(dbManager);
        capacityRecordDAO = new CapacityRecordDAO(dbManager);
        smartRecordDAO = new SmartRecordDAO(dbManager);
        analysisResultDAO = new AnalysisResultDAO(dbManager);
    }

    /**
     * 初始化所有服务
     */
    private static void initServices() throws Exception {
        discoveryService = new DiskDiscoveryService();
        capacityService = new CapacityMonitorService(capacityRecordDAO);
        smartReaderService = new SmartReaderService();
        llmAnalysisService = new LlmAnalysisService(
                config.getLlm(),
                analysisResultDAO,
                capacityRecordDAO,
                smartRecordDAO);

        schedulerManager = new SchedulerManager(
                config.getMonitor(),
                diskDAO,
                capacityService,
                smartReaderService,
                smartRecordDAO);

        // 启动Web服务器
        if (config.getWeb().isEnabled()) {
            webServer = new WebServer(
                    config,
                    diskDAO,
                    capacityRecordDAO,
                    smartRecordDAO,
                    analysisResultDAO,
                    llmAnalysisService);
            webServer.start();
        }
    }

    /**
     * 发现硬盘并保存到数据库
     */
    private static void discoverAndSaveDisks() {
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
    private static void startScheduler() throws SchedulerException {
        schedulerManager.start();
        logger.info("Monitoring scheduler started");
    }

    /**
     * 运行交互式命令行界面
     */
    private static void runInteractiveCli() {
        Scanner scanner = new Scanner(System.in);
        boolean running = true;

        logger.info("\n=== SiMing Interactive CLI ===");
        printHelp();

        while (running) {
            System.out.print("\nSiMing> ");
            if (!scanner.hasNextLine()) {
                break;
            }

            String command = scanner.nextLine().trim().toLowerCase();
            switch (command) {
                case "list":
                    listDisks();
                    break;
                case "help":
                    printHelp();
                    break;
                case "exit":
                case "quit":
                    running = false;
                    shutdown();
                    break;
                case "discover":
                    discoverAndSaveDisks();
                    break;
                case "monitor":
                    handleMonitorCommand(scanner);
                    break;
                case "unmonitor":
                    handleUnmonitorCommand(scanner);
                    break;
                case "analyze":
                    handleAnalysisCommand(scanner);
                    break;
                case "analyses":
                    listAnalyses();
                    break;
                default:
                    if (!command.isEmpty()) {
                        System.out.println("Unknown command: " + command);
                        printHelp();
                    }
            }
        }

        scanner.close();
    }

    /**
     * 打印帮助信息
     */
    private static void printHelp() {
        System.out.println("Available commands:");
        System.out.println("  list      - List all discovered disks");
        System.out.println("  discover  - Rescan and discover new disks");
        System.out.println("  monitor   - Start monitoring a disk (usage: monitor <id> [cron])");
        System.out.println("  unmonitor - Stop monitoring a disk (usage: unmonitor <id>)");
        System.out.println("  analyze   - Analyze disk health with LLM (usage: analyze <id> <days>)");
        System.out.println("  analyses  - List all analysis results");
        System.out.println("  help      - Show this help");
        System.out.println("  exit/quit - Exit the application");
    }

    /**
     * 列出所有硬盘
     */
    private static void listDisks() {
        List<Disk> disks = diskDAO.findAll();
        System.out.println("\nDiscovered disks:");
        System.out.printf("%-3s | %-12s | %-10s | %-20s | %-10s | %s%n",
                "ID", "Device", "Type", "Model", "Size (GB)", "Monitored");
        System.out.println("--------------------------------------------------------------------------");

        for (Disk disk : disks) {
            System.out.printf("%3d | %-12s | %-10s | %-20s | %10.2f | %b%n",
                    disk.getId(),
                    disk.getDevicePath(),
                    disk.isSSD() ? "SSD" : "HDD",
                    (disk.getBrand() + " " + disk.getModel()).trim(),
                    disk.getTotalCapacityGB(),
                    disk.isMonitored());
        }
        System.out.println();
    }

    /**
     * 处理监控命令
     */
    private static void handleMonitorCommand(Scanner scanner) {
        System.out.print("Enter disk ID to monitor: ");
        if (!scanner.hasNextInt()) {
            System.out.println("Invalid disk ID");
            scanner.nextLine();
            return;
        }

        int diskId = scanner.nextInt();
        scanner.nextLine(); // 消费换行

        Optional<Disk> diskOpt = diskDAO.findById((long) diskId);
        if (diskOpt.isEmpty()) {
            System.out.println("Disk with ID " + diskId + " not found");
            return;
        }
        Disk disk = diskOpt.get();

        System.out.printf("Current default cron is: %s%n", config.getMonitor().getDefaultCron());
        System.out.print("Enter custom cron expression (or press Enter to use default): ");
        String cron = scanner.nextLine().trim();

        if (cron.isEmpty()) {
            cron = config.getMonitor().getDefaultCron();
        }

        disk.setMonitored(true);
        disk.setMonitorCron(cron);
        diskDAO.save(disk);

        try {
            schedulerManager.rescheduleAll();
        } catch (SchedulerException e) {
            logger.error("Failed to reschedule", e);
            System.out.println("Failed to reschedule: " + e.getMessage());
            return;
        }

        System.out.printf("Now monitoring disk %d (%s) with cron: %s%n",
                diskId, disk.getDevicePath(), cron);
    }

    /**
     * 处理停止监控命令
     */
    private static void handleUnmonitorCommand(Scanner scanner) {
        System.out.print("Enter disk ID to stop monitoring: ");
        if (!scanner.hasNextInt()) {
            System.out.println("Invalid disk ID");
            scanner.nextLine();
            return;
        }

        int diskId = scanner.nextInt();
        scanner.nextLine();

        Optional<Disk> diskOpt = diskDAO.findById((long) diskId);
        if (diskOpt.isEmpty()) {
            System.out.println("Disk with ID " + diskId + " not found");
            return;
        }
        Disk disk = diskOpt.get();

        disk.setMonitored(false);
        diskDAO.save(disk);

        try {
            schedulerManager.rescheduleAll();
        } catch (SchedulerException e) {
            logger.error("Failed to reschedule", e);
            System.out.println("Failed to reschedule: " + e.getMessage());
            return;
        }

        System.out.printf("Stopped monitoring disk %d (%s)%n", diskId, disk.getDevicePath());
    }

    /**
     * 处理分析命令
     */
    private static void handleAnalysisCommand(Scanner scanner) {
        if (!llmAnalysisService.isConfigured()) {
            System.out.println("LLM service is not configured. Please set OPENAI_API_KEY in environment.");
            return;
        }

        System.out.print("Enter disk ID to analyze: ");
        if (!scanner.hasNextInt()) {
            System.out.println("Invalid disk ID");
            scanner.nextLine();
            return;
        }

        int diskId = scanner.nextInt();
        System.out.print("Enter number of days to analyze: ");
        if (!scanner.hasNextInt()) {
            System.out.println("Invalid number of days");
            scanner.nextLine();
            return;
        }

        int days = scanner.nextInt();
        scanner.nextLine();

        Optional<Disk> diskOpt = diskDAO.findById((long) diskId);
        if (diskOpt.isEmpty()) {
            System.out.println("Disk with ID " + diskId + " not found");
            return;
        }
        Disk disk = diskOpt.get();

        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusDays(days);

        System.out.printf("Analyzing disk %s (%s) from last %d days...%n",
                diskId, disk.getDevicePath(), days);

        try {
            long startTs = System.currentTimeMillis();
            AnalysisResult result = llmAnalysisService.analyzeDisk(disk, startTime, endTime);
            long elapsed = System.currentTimeMillis() - startTs;

            System.out.println("\n=== Analysis Result ===");
            System.out.println("Disk: " + disk.getBrand() + " " + disk.getModel());
            System.out.println("Time range: " + startTime + " to " + endTime);
            if (result.getHealthScore() != null) {
                System.out.println("Health Score: " + result.getHealthScore() + "/100");
            }
            if (result.getHealthLevel() != null) {
                System.out.println("Health Level: " + result.getHealthLevel());
            }
            System.out.println("\nFull Analysis:");
            System.out.println(result.getAnalysisContent());
            System.out.printf("%nAnalysis completed in %.2f seconds%n", (double) elapsed / 1000);
        } catch (Exception e) {
            logger.error("Analysis failed", e);
            System.out.println("Analysis failed: " + e.getMessage());
        }
    }

    /**
     * 列出所有分析结果
     */
    private static void listAnalyses() {
        List<AnalysisResult> results = analysisResultDAO.findAll();
        System.out.println("\nAnalysis Results:");
        System.out.printf("%-3s | %-4s | %-10s | %-10s | %s%n",
                "ID", "DiskID", "Score", "Level", "Created");
        System.out.println("------------------------------------------------");

        for (AnalysisResult result : results) {
            System.out.printf("%3d | %4d | %10s | %10s | %s%n",
                    result.getId(),
                    result.getDiskId(),
                    result.getHealthScore() != null ? result.getHealthScore() : "-",
                    result.getHealthLevel() != null ? result.getHealthLevel() : "-",
                    result.getCreateTime());
        }
        System.out.println();
    }

    /**
     * 关闭应用程序
     */
    private static void shutdown() {
        try {
            logger.info("Shutting down...");
            if (webServer != null) {
                webServer.stop();
            }
            if (schedulerManager != null) {
                schedulerManager.shutdown();
            }
            if (dbManager != null) {
                dbManager.close();
            }
            logger.info("Goodbye!");
        } catch (Exception e) {
            logger.error("Error during shutdown", e);
        }
    }
}