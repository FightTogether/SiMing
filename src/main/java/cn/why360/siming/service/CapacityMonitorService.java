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

    // df输出的匹配模式 - 匹配开头的文件系统和五个数字字段，最后包含挂载点（可以包含空格）
    private static final Pattern DF_PATTERN = Pattern.compile(
            "^(\\S+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)%\\s+"
    );

    public CapacityMonitorService(CapacityRecordDAO capacityRecordDAO) {
        this.capacityRecordDAO = capacityRecordDAO;
    }

    /**
     * 获取所有挂载点容量信息，聚合后保存一条总容量记录
     */
    public List<CapacityRecord> checkAndSaveAllMounts(Long diskId, String diskDevicePath) {
        List<CapacityRecord> records = new ArrayList<>();

        long totalUsed = 0;
        long totalAvailable = 0;

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
                    if (matcher.find()) {
                        String filesystem = matcher.group(1);
                        long used = Long.parseLong(matcher.group(3)) * 1024; // 转换为字节
                        long available = Long.parseLong(matcher.group(4)) * 1024;
                        int percent = Integer.parseInt(matcher.group(5));
                        // 挂载点是剩下的所有内容，可以包含空格
                        String mountPoint = line.substring(matcher.end());

                        // 只统计属于当前硬盘的物理文件系统
                        if (isPhysicalFilesystem(filesystem, mountPoint) && 
                            isFilesystemOnDisk(filesystem, diskDevicePath)) {
                            totalUsed += used;
                            totalAvailable += available;
                            logger.debug("Added capacity for {} on {}: {} used, {} available",
                                    mountPoint, diskDevicePath, used, available);
                        }
                    } else {
                        logger.warn("Failed to parse df line: {}", line);
                    }
                }
            }

            process.waitFor();
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to check capacity", e);
        }

        // 只保存一条总容量记录
        if (totalUsed > 0 || totalAvailable > 0) {
            long totalCapacity = totalUsed + totalAvailable;
            double usagePercent = totalCapacity > 0 ? (double) totalUsed * 100 / totalCapacity : 0;

            CapacityRecord record = CapacityRecord.builder()
                    .diskId(diskId)
                    .filesystem("total") // 标记为总容量
                    .usedCapacity(totalUsed)
                    .availableCapacity(totalAvailable)
                    .usagePercent(usagePercent)
                    .mountPoint("total") // 标记为总容量
                    .build();

            capacityRecordDAO.insert(record);
            records.add(record);

            double availGB = (double) totalAvailable / (1024 * 1024 * 1024);
            logger.info("Saved total capacity record for disk {}: {:.1f}% used, {:.2f} GB available",
                    diskId, usagePercent, availGB);
        }

        return records;
    }

    /**
     * 判断是否是物理文件系统（非虚拟）
     */
    private boolean isPhysicalFilesystem(String filesystem, String mountPoint) {
        // 跳过这些虚拟文件系统类型
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

    /**
     * 判断文件系统是否属于指定硬盘
     * 例如: /dev/nvme0n1p1 属于 /dev/nvme0n1
     * 例如: /dev/sda1 属于 /dev/sda
     */
    private boolean isFilesystemOnDisk(String filesystem, String diskDevicePath) {
        // 文件系统路径以硬盘设备路径开头（分区编号在后面）
        // 如果硬盘路径是 /dev/nvme0n1，则分区 /dev/nvme0n1p1 应该被包含
        if (filesystem.startsWith(diskDevicePath)) {
            // 分区名称是硬盘名加上数字，所以检查下一个字符不是字母（即数字或p分区符）
            if (filesystem.length() > diskDevicePath.length()) {
                char nextChar = filesystem.charAt(diskDevicePath.length());
                return !Character.isLetter(nextChar) || nextChar == 'p';
            }
            // 正好相等说明整个硬盘就是一个分区（少见但可能）
            return true;
        }
        return false;
    }
}