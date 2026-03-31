package cn.why360.siming.scheduler;

import cn.why360.siming.dao.DiskDAO;
import cn.why360.siming.dao.SmartRecordDAO;
import cn.why360.siming.entity.Disk;
import cn.why360.siming.entity.SmartRecord;
import cn.why360.siming.service.CapacityMonitorService;
import cn.why360.siming.service.SmartReaderService;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 硬盘监控任务，由Quartz调度器执行
 */
public class DiskMonitorJob implements Job {
    private static final Logger logger = LoggerFactory.getLogger(DiskMonitorJob.class);

    private static volatile DiskDAO diskDAO;
    private static volatile CapacityMonitorService capacityService;
    private static volatile SmartReaderService smartReaderService;
    private static volatile SmartRecordDAO smartRecordDAO;

    public static void init(DiskDAO diskDAO,
                           CapacityMonitorService capacityService,
                           SmartReaderService smartReaderService,
                           SmartRecordDAO smartRecordDAO) {
        DiskMonitorJob.diskDAO = diskDAO;
        DiskMonitorJob.capacityService = capacityService;
        DiskMonitorJob.smartReaderService = smartReaderService;
        DiskMonitorJob.smartRecordDAO = smartRecordDAO;
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

                // SMART监控
                if (smartReaderService.isSmartEnabled(disk.getDevicePath())) {
                    List<SmartRecord> smartRecords = smartReaderService.readSmartData(
                            disk.getId(), disk.getDevicePath());

                    if (!smartRecords.isEmpty()) {
                        smartRecordDAO.batchInsert(disk.getId(), smartRecords);
                        logger.debug("SMART check completed for {}, recorded {} attributes",
                                disk.getDevicePath(), smartRecords.size());
                    }
                } else {
                    logger.warn("SMART is not enabled for {}, skipping SMART check",
                            disk.getDevicePath());
                }

                logger.info("Completed monitoring for {}", disk.getDevicePath());
            } catch (Exception e) {
                logger.error("Failed to monitor disk: {}", disk.getDevicePath(), e);
            }
        }

        logger.info("Disk monitoring job completed");
    }
}