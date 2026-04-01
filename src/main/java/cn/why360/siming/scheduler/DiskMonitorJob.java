package cn.why360.siming.scheduler;

import cn.why360.siming.dao.DiskDAO;
import cn.why360.siming.dao.SmartRecordDAO;
import cn.why360.siming.entity.Disk;
import cn.why360.siming.entity.SmartRecord;
import cn.why360.siming.service.CapacityMonitorService;
import cn.why360.siming.service.LlmAnalysisService;
import cn.why360.siming.service.SmartReaderService;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 硬盘监控任务，由Quartz调度器执行
 */
public class DiskMonitorJob implements Job {
    private static final Logger logger = LoggerFactory.getLogger(DiskMonitorJob.class);

    private static volatile DiskDAO diskDAO;
    private static volatile CapacityMonitorService capacityService;
    private static volatile SmartReaderService smartReaderService;
    private static volatile SmartRecordDAO smartRecordDAO;
    private static volatile LlmAnalysisService llmAnalysisService;
    private static final ExecutorService asyncExecutor = Executors.newCachedThreadPool();

    public static void init(DiskDAO diskDAO,
                           CapacityMonitorService capacityService,
                           SmartReaderService smartReaderService,
                           SmartRecordDAO smartRecordDAO,
                           LlmAnalysisService llmAnalysisService) {
        DiskMonitorJob.diskDAO = diskDAO;
        DiskMonitorJob.capacityService = capacityService;
        DiskMonitorJob.smartReaderService = smartReaderService;
        DiskMonitorJob.smartRecordDAO = smartRecordDAO;
        DiskMonitorJob.llmAnalysisService = llmAnalysisService;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        logger.info("Starting disk monitoring job...");

        if (diskDAO == null) {
            logger.error("Job not initialized properly");
            throw new JobExecutionException("Job not initialized");
        }

        List<Disk> monitoredDisks = diskDAO.findMonitored();

        if (monitoredDisks.isEmpty()) {
            logger.info("No disks are configured for monitoring");
            return;
        }

        for (Disk disk : monitoredDisks) {
            try {
                logger.info("Monitoring disk: {}", disk.getDevicePath());

                // 容量监控 - 只统计当前硬盘上的挂载点
                capacityService.checkAndSaveAllMounts(disk.getId(), disk.getDevicePath());
                logger.debug("Capacity check completed for {}", disk.getDevicePath());

                boolean smartCollected = false;
                // SMART监控
                if (smartReaderService.isSmartEnabled(disk.getDevicePath())) {
                    List<SmartRecord> smartRecords = smartReaderService.readSmartData(
                            disk.getId(), disk.getDevicePath());

                    if (!smartRecords.isEmpty()) {
                        smartRecordDAO.batchInsert(disk.getId(), smartRecords);
                        logger.debug("SMART check completed for {}, recorded {} attributes",
                                disk.getDevicePath(), smartRecords.size());
                        smartCollected = true;
                    }
                } else {
                    logger.warn("SMART is not enabled for {}, skipping SMART check",
                            disk.getDevicePath());
                }

                // Smart采集信息入库成功之后，后台异步启动AI分析（分析最近30天数据）
                if (smartCollected && llmAnalysisService != null) {
                    logger.info("SMART data collected successfully, scheduling async AI analysis for disk: {}", disk.getDevicePath());
                    asyncExecutor.submit(() -> {
                        try {
                            llmAnalysisService.analyzeDiskHistory(disk, 30);
                            logger.info("Async AI analysis completed for disk: {}", disk.getDevicePath());
                        } catch (Exception e) {
                            logger.error("Async AI analysis failed for disk: {}", disk.getDevicePath(), e);
                        }
                    });
                }

                logger.info("Completed monitoring for {}", disk.getDevicePath());
            } catch (Exception e) {
                logger.error("Failed to monitor disk: {}", disk.getDevicePath(), e);
            }
        }

        logger.info("Disk monitoring job completed");
    }
}