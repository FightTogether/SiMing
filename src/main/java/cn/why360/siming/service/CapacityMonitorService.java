package cn.why360.siming.service;

import cn.why360.siming.dao.CapacityRecordDAO;
import cn.why360.siming.entity.CapacityRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 容量监控服务，用于读取和存储分区容量信息
 */
public class CapacityMonitorService {
    private static final Logger logger = LoggerFactory.getLogger(CapacityMonitorService.class);

    private final CapacityRecordDAO capacityRecordDAO;

    // df输出的匹配模式
    private static final Pattern DF_PATTERN = Pattern.compile(
            "^(\\S+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)%\\s+(.+)$"
    );

    public CapacityMonitorService(CapacityRecordDAO capacityRecordDAO) {
        this.capacityRecordDAO = capacityRecordDAO;
    }

    /**
     * 获取所有挂载点容量信息并保存
     */
    public List<CapacityRecord> checkAndSaveAllMounts(Long diskId) {
        List<CapacityRecord> records = new ArrayList<>();

        try {
            Process process = new ProcessBuilder("df", "-k")
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                // 跳过第一行表头
                reader.readLine();

                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    Matcher matcher = DF_PATTERN.matcher(line);
                    if (matcher.matches()) {
                        String filesystem = matcher.group(1);
                        long kBlocks = Long.parseLong(matcher.group(2));
                        long used = Long.parseLong(matcher.group(3)) * 1024; // 转换为字节
                        long available = Long.parseLong(matcher.group(4)) * 1024;
                        int percent = Integer.parseInt(matcher.group(5));
                        String mountPoint = matcher.group(6);

                        // 只保存物理文件系统，跳过虚拟文件系统
                        if (isPhysicalFilesystem(filesystem, mountPoint)) {
                            CapacityRecord record = CapacityRecord.builder()
                                    .diskId(diskId)
                                    .usedCapacity(used)
                                    .availableCapacity(available)
                                    .usagePercent(percent)
                                    .mountPoint(mountPoint)
                                    .build();

                            capacityRecordDAO.insert(record);
                            records.add(record);

                            logger.debug("Saved capacity record for {}: {}% used, {} GB available",
                                    mountPoint, percent, (double) available / (1024 * 1024 * 1024));
                        }
                    }
                }
            }

            process.waitFor();
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to check capacity", e);
        }

        return records;
    }

    /**
     * 判断是否是物理文件系统
     */
    private boolean isPhysicalFilesystem(String filesystem, String mountPoint) {
        // 跳过这些文件系统类型
        if (filesystem.startsWith("tmpfs") ||
            filesystem.startsWith("devtmpfs") ||
            filesystem.startsWith("sysfs") ||
            filesystem.startsWith("proc") ||
            filesystem.startsWith("devfs") ||
            mountPoint.startsWith("/sys") ||
            mountPoint.startsWith("/proc") ||
            mountPoint.startsWith("/dev") ||
            mountPoint.startsWith("/run")) {
            return false;
        }

        // 只保留块设备
        return filesystem.startsWith("/dev/");
    }
}