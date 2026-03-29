package cn.why360.siming.service;

import cn.why360.siming.entity.Disk;
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
 * 硬盘探测服务，用于发现系统中的硬盘并获取其信息
 */
public class DiskDiscoveryService {
    private static final Logger logger = LoggerFactory.getLogger(DiskDiscoveryService.class);

    private static final Pattern DEVICE_NAME_PATTERN = Pattern.compile("^/dev/(sd[a-z]+|nvme\\d+n\\d+)$");

    /**
     * 发现系统中所有硬盘
     */
    public List<Disk> discoverDisks() {
        List<Disk> disks = new ArrayList<>();
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("linux")) {
            disks = discoverDisksOnLinux();
        } else if (os.contains("mac")) {
            disks = discoverDisksOnMac();
        } else {
            logger.error("Unsupported operating system: {}", os);
        }

        logger.info("Discovered {} disks", disks.size());
        return disks;
    }

    /**
     * Linux平台硬盘探测
     */
    private List<Disk> discoverDisksOnLinux() {
        List<Disk> disks = new ArrayList<>();

        // 使用lsblk获取块设备信息
        try {
            Process process = new ProcessBuilder("lsblk", "-o", "NAME,SIZE,VENDOR,MODEL,SERIAL,ROTA", "-n", "-d")
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    String[] parts = line.split("\\s+", 6);
                    if (parts.length < 6) continue;

                    String name = parts[0];
                    if (!name.startsWith("sd") && !name.startsWith("nvme")) continue;

                    String devicePath = "/dev/" + name;
                    if (!DEVICE_NAME_PATTERN.matcher(devicePath).matches()) continue;

                    // 解析大小（lsblk输出的大小单位可能不同，我们通过另一命令获取）
                    long size = getDiskSize(devicePath);
                    String vendor = cleanField(parts[2]);
                    String model = cleanField(parts[3]);
                    String serial = cleanField(parts[4]);
                    // ROTA = 1 means rotating (HDD), 0 means non-rotating (SSD)
                    int isRotational = Integer.parseInt(parts[5]);
                    boolean isSSD = isRotational == 0;

                    Disk disk = Disk.builder()
                            .devicePath(devicePath)
                            .brand(vendor)
                            .model(model)
                            .serialNumber(serial)
                            .totalCapacity(size)
                            .isSSD(isSSD)
                            .monitored(false)
                            .build();

                    disks.add(disk);
                    logger.info("Found disk: {} - {} {} {} {:.2f} GB",
                            devicePath, vendor, model, isSSD ? "SSD" : "HDD", (double) size / (1024*1024*1024));
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.warn("lsblk exited with code {}", exitCode);
            }
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to discover disks on Linux", e);
        }

        return disks;
    }

    /**
     * macOS平台硬盘探测
     */
    private List<Disk> discoverDisksOnMac() {
        List<Disk> disks = new ArrayList<>();

        try {
            Process process = new ProcessBuilder("diskutil", "list")
                    .redirectErrorStream(true)
                    .start();

            Pattern diskPattern = Pattern.compile("^/dev/(disk\\d+)");
            boolean inPhysicalDiskSection = false;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.contains("Physical Disks")) {
                        inPhysicalDiskSection = true;
                        continue;
                    }
                    if (!inPhysicalDiskSection || line.isEmpty()) continue;

                    Matcher matcher = diskPattern.matcher(line);
                    if (matcher.find()) {
                        String devicePath = matcher.group(1);
                        devicePath = "/dev/" + devicePath;
                        disks.addAll(getMacDiskInfo(devicePath));
                    }
                }
            }

            process.waitFor();
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to discover disks on macOS", e);
        }

        return disks;
    }

    /**
     * 获取macOS上单块硬盘信息
     */
    private List<Disk> getMacDiskInfo(String devicePath) {
        List<Disk> disks = new ArrayList<>();
        try {
            Process process = new ProcessBuilder("diskutil", "info", devicePath)
                    .redirectErrorStream(true)
                    .start();

            String vendor = "";
            String model = "";
            String serial = "";
            long size = 0;
            boolean isSSD = false;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("Device / Media Name:")) {
                        model = line.split(":", 2)[1].trim();
                    } else if (line.contains("Media Type:")) {
                        isSSD = line.toLowerCase().contains("solid");
                    } else if (line.contains("Total Size:")) {
                        // 解析大小，这里简化处理
                        size = getDiskSize(devicePath);
                    } else if (line.contains("Serial Number:")) {
                        serial = line.split(":", 2)[1].trim();
                    } else if (line.contains("Manufacturer:")) {
                        vendor = line.split(":", 2)[1].trim();
                    }
                }
            }

            if (size > 0) {
                Disk disk = Disk.builder()
                        .devicePath(devicePath)
                        .brand(vendor)
                        .model(model)
                        .serialNumber(serial)
                        .totalCapacity(size)
                        .isSSD(isSSD)
                        .monitored(false)
                        .build();
                disks.add(disk);
                logger.info("Found disk: {} - {} {} {} {:.2f} GB",
                        devicePath, vendor, model, isSSD ? "SSD" : "HDD", (double) size / (1024*1024*1024));
            }

            process.waitFor();
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to get disk info for {} on macOS", devicePath, e);
        }

        return disks;
    }

    /**
     * 获取磁盘大小（字节）
     */
    private long getDiskSize(String devicePath) {
        try {
            if (System.getProperty("os.name").toLowerCase().contains("linux")) {
                Process process = new ProcessBuilder("blockdev", "--getsize64", devicePath)
                        .redirectErrorStream(true)
                        .start();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null && line.matches("\\d+")) {
                        return Long.parseLong(line.trim());
                    }
                }

                process.waitFor();
            }
        } catch (Exception e) {
            logger.warn("Failed to get disk size for {}", devicePath, e);
        }

        return 0;
    }

    /**
     * 清理字段，去除多余空白
     */
    private String cleanField(String field) {
        if (field == null) return "";
        return field.trim().replaceAll("\\s+", " ");
    }
}