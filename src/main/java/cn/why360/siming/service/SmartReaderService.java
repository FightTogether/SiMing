package cn.why360.siming.service;

import cn.why360.siming.entity.SmartRecord;
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
 * SMART信息读取服务，使用smartctl工具获取硬盘SMART信息
 */
public class SmartReaderService {
    private static final Logger logger = LoggerFactory.getLogger(SmartReaderService.class);

    // 匹配SMART属性行的正则表达式
    // 格式示例:  5  Reallocated_Sector_Ct   0x0033   100   100   050    Old_age   Always   -       0
    private static final Pattern SMART_ATTRIBUTE_PATTERN = Pattern.compile(
            "^\\s*(\\d+)\\s+([A-Za-z0-9_]+)\\s+[0-9a-fx]+\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+.*\\-\\s+(\\d+)$"
    );

    private static final Pattern TEMPERATURE_PATTERN = Pattern.compile(".*Temperature.*\\b(\\d+)\\s*(C|°C|F)\\b.*", Pattern.CASE_INSENSITIVE);

    /**
     * 检查smartctl是否可用
     */
    public boolean isSmartctlAvailable() {
        try {
            Process process = new ProcessBuilder("smartctl", "--version")
                    .redirectErrorStream(true)
                    .start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            logger.error("smartctl not found, please install smartmontools first");
            return false;
        }
    }

    /**
     * 读取硬盘SMART信息
     */
    public List<SmartRecord> readSmartData(Long diskId, String devicePath) {
        List<SmartRecord> attributes = new ArrayList<>();

        if (!isSmartctlAvailable()) {
            logger.error("smartctl is not available, cannot read SMART data");
            return attributes;
        }

        try {
            Process process = new ProcessBuilder("smartctl", "-A", devicePath)
                    .redirectErrorStream(true)
                    .start();

            boolean started = false;
            Integer temperature = null;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 查找属性表开始
                    if (line.startsWith("ID# ATTRIBUTE_NAME")) {
                        started = true;
                        continue;
                    }
                    if (!started) {
                        // 尝试在这里找到温度信息（某些设备格式不同）
                        Matcher tempMatcher = TEMPERATURE_PATTERN.matcher(line);
                        if (tempMatcher.matches()) {
                            temperature = Integer.parseInt(tempMatcher.group(1));
                        }
                        continue;
                    }
                    if (line.trim().isEmpty()) {
                        continue;
                    }

                    Matcher matcher = SMART_ATTRIBUTE_PATTERN.matcher(line);
                    if (matcher.matches()) {
                        int attributeId = Integer.parseInt(matcher.group(1));
                        String attributeName = matcher.group(2);
                        int currentValue = Integer.parseInt(matcher.group(3));
                        int worstValue = Integer.parseInt(matcher.group(4));
                        int threshold = Integer.parseInt(matcher.group(5));
                        long rawValue = Long.parseLong(matcher.group(6));

                        // 判断是否失败
                        boolean failed = currentValue <= threshold;

                        // 检查是否是温度属性
                        if (attributeName.equals("Temperature_Celsius") ||
                            attributeName.equals("Airflow_Temperature_Cel") ||
                            attributeName.equals("Temperature")) {
                            temperature = (int) rawValue;
                        }

                        SmartRecord record = SmartRecord.builder()
                                .diskId(diskId)
                                .attributeId(attributeId)
                                .attributeName(attributeName)
                                .currentValue(currentValue)
                                .worstValue(worstValue)
                                .threshold(threshold)
                                .rawValue(rawValue)
                                .failed(failed)
                                .temperature(attributeName.toLowerCase().contains("temperature") ? (int) rawValue : null)
                                .build();

                        attributes.add(record);
                    }
                }
            }

            // 如果我们找到了温度，确保它在列表中
            if (temperature != null && attributes.stream().noneMatch(r -> "Temperature_Celsius".equals(r.getAttributeName()))) {
                SmartRecord record = SmartRecord.builder()
                        .diskId(diskId)
                        .attributeId(194)
                        .attributeName("Temperature_Celsius")
                        .currentValue(temperature)
                        .worstValue(temperature)
                        .threshold(100)
                        .rawValue((long) temperature)
                        .failed(false)
                        .temperature(temperature)
                        .build();
                attributes.add(record);
            }

            int exitCode = process.waitFor();
            if (exitCode != 0 && exitCode != 2) {
                // exit code 2 means okay but warning, so we still accept it
                logger.warn("smartctl exited with code {} for {}", exitCode, devicePath);
            }

            logger.debug("Read {} SMART attributes from {}", attributes.size(), devicePath);
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to read SMART data from {}", devicePath, e);
        }

        return attributes;
    }

    /**
     * 检查硬盘是否支持SMART
     */
    public boolean isSmartEnabled(String devicePath) {
        if (!isSmartctlAvailable()) {
            return false;
        }

        try {
            Process process = new ProcessBuilder("smartctl", "-i", devicePath)
                    .redirectErrorStream(true)
                    .start();

            boolean enabled = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("SMART support is: Enabled")) {
                        enabled = true;
                        break;
                    }
                }
            }

            process.waitFor();
            return enabled;
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to check SMART status for {}", devicePath, e);
            return false;
        }
    }
}