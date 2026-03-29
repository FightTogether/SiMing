package cn.why360.siming.scheduler;

import cn.why360.siming.config.SimingConfig;
import cn.why360.siming.dao.CapacityRecordDAO;
import cn.why360.siming.dao.DiskDAO;
import cn.why360.siming.dao.SmartRecordDAO;
import cn.why360.siming.entity.Disk;
import cn.why360.siming.service.CapacityMonitorService;
import cn.why360.siming.service.SmartReaderService;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Quartz调度器管理器，负责启动和管理监控任务
 */
public class SchedulerManager {
    private static final Logger logger = LoggerFactory.getLogger(SchedulerManager.class);

    private final Scheduler scheduler;
    private final SimingConfig.Monitor monitorConfig;
    private final DiskDAO diskDAO;

    public SchedulerManager(SimingConfig.Monitor monitorConfig,
                            DiskDAO diskDAO,
                            CapacityMonitorService capacityService,
                            SmartReaderService smartReaderService,
                            SmartRecordDAO smartRecordDAO) throws SchedulerException {
        this.monitorConfig = monitorConfig;
        this.diskDAO = diskDAO;

        // 初始化Job
        DiskMonitorJob.init(diskDAO, capacityService, smartReaderService, smartRecordDAO);

        // 创建调度器
        StdSchedulerFactory factory = new StdSchedulerFactory();
        this.scheduler = factory.getScheduler();
    }

    /**
     * 启动调度器并调度所有任务
     */
    public void start() throws SchedulerException {
        // 获取所有已配置监控的硬盘，为每个安排任务
        List<Disk> monitoredDisks = diskDAO.findMonitored();

        if (monitoredDisks.isEmpty()) {
            logger.info("No monitored disks found, checking default cron from config");
            if (monitorConfig.getDefaultCron() != null && !monitorConfig.getDefaultCron().isEmpty()) {
                scheduleGlobalJob(monitorConfig.getDefaultCron());
            }
        } else {
            // 为每个硬盘安排任务，使用每个硬盘自己的cron表达式
            for (Disk disk : monitoredDisks) {
                if (disk.getMonitorCron() != null && !disk.getMonitorCron().isEmpty()) {
                    scheduleJob(disk, disk.getMonitorCron());
                } else if (monitorConfig.getDefaultCron() != null && !monitorConfig.getDefaultCron().isEmpty()) {
                    scheduleJob(disk, monitorConfig.getDefaultCron());
                }
            }
        }

        scheduler.start();
        logger.info("Scheduler started");
    }

    /**
     * 安排全局监控任务（使用默认cron）
     */
    private void scheduleGlobalJob(String cronExpression) throws SchedulerException {
        JobDetail jobDetail = JobBuilder.newJob(DiskMonitorJob.class)
                .withIdentity("global-disk-monitor", "disk-monitoring")
                .storeDurably()
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity("global-disk-monitor-trigger", "disk-monitoring")
                .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
                .build();

        if (!scheduler.checkExists(jobDetail.getKey())) {
            scheduler.scheduleJob(jobDetail, trigger);
            logger.info("Scheduled global monitoring job with cron: {}", cronExpression);
        }
    }

    /**
     * 安排单个硬盘的监控任务
     */
    private void scheduleJob(Disk disk, String cronExpression) throws SchedulerException {
        String jobName = "disk-monitor-" + disk.getId();
        JobDetail jobDetail = JobBuilder.newJob(DiskMonitorJob.class)
                .withIdentity(jobName, "disk-monitoring")
                .usingJobData("diskId", disk.getId())
                .storeDurably()
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(jobName + "-trigger", "disk-monitoring")
                .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
                .build();

        if (scheduler.checkExists(jobDetail.getKey())) {
            scheduler.deleteJob(jobDetail.getKey());
        }

        scheduler.scheduleJob(jobDetail, trigger);
        logger.info("Scheduled monitoring job for disk {} ({}) with cron: {}",
                disk.getId(), disk.getDevicePath(), cronExpression);
    }

    /**
     * 重新调度任务（当监控配置改变时调用）
     */
    public void rescheduleAll() throws SchedulerException {
        // 清除现有作业
        scheduler.clear();

        // 重新调度
        List<Disk> monitoredDisks = diskDAO.findMonitored();
        for (Disk disk : monitoredDisks) {
            String cron = disk.getMonitorCron();
            if (cron == null || cron.isEmpty()) {
                cron = monitorConfig.getDefaultCron();
            }
            if (cron != null && !cron.isEmpty()) {
                scheduleJob(disk, cron);
            }
        }

        logger.info("Rescheduled all monitoring jobs, total: {}", monitoredDisks.size());
    }

    /**
     * 停止调度器
     */
    public void shutdown() throws SchedulerException {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown(true);
            logger.info("Scheduler shutdown complete");
        }
    }

    /**
     * 获取调度器
     */
    public Scheduler getScheduler() {
        return scheduler;
    }
}