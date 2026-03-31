package cn.why360.siming.service;

import cn.why360.siming.entity.CapacityRecord;
import cn.why360.siming.entity.SmartRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SMART信息读取服务，使用smartctl工具获取硬盘SMART信息
 * 使用JSON格式输出解析，更稳定可靠
 */
public class SmartReaderService {
    private static final Logger logger = LoggerFactory.getLogger(SmartReaderService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

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
     * 读取硬盘SMART信息 - 使用JSON格式输出
     */
    public List<SmartRecord> readSmartData(Long diskId, String devicePath) {
        List<SmartRecord> attributes = new ArrayList<>();

        if (!isSmartctlAvailable()) {
            logger.error("smartctl is not available, cannot read SMART data");
            return attributes;
        }

        try {
            // 使用 -j 获取JSON格式输出
            Process process = new ProcessBuilder("smartctl", "-j", "-a", devicePath)
                    .redirectErrorStream(true)
                    .start();

            StringBuilder jsonOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonOutput.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0 && exitCode != 2) {
                // exit code 2 means okay but warning, so we still accept it
                logger.warn("smartctl exited with code {} for {}", exitCode, devicePath);
            }

            // 解析JSON并提取SMART属性
            return parseSmartJson(diskId, jsonOutput.toString());
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
            Process process = new ProcessBuilder("smartctl", "-j", "-i", devicePath)
                    .redirectErrorStream(true)
                    .start();

            StringBuilder jsonOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonOutput.append(line).append("\n");
                }
            }

            process.waitFor();

            // 解析JSON检查SMART是否启用
            JsonNode root = objectMapper.readTree(jsonOutput.toString());
            if (root.has("smart_support")) {
                JsonNode smartSupport = root.get("smart_support");
                return smartSupport.has("enabled") && smartSupport.get("enabled").asBoolean(false);
            }
            return false;
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to check SMART status for {}", devicePath, e);
            return false;
        }
    }

    /**
     * 从smartctl JSON格式输出解析SMART属性
     * 用于分布式模式，客户端采集JSON格式数据，服务端解析
     */
    public List<SmartRecord> parseSmartJson(Long diskId, String smartJson) {
        List<SmartRecord> attributes = new ArrayList<>();

        if (smartJson == null || smartJson.isEmpty()) {
            logger.warn("Empty SMART JSON data for disk {}", diskId);
            return attributes;
        }

        try {
            JsonNode root = objectMapper.readTree(smartJson);

            // 检测设备类型 - SATA/ATA vs NVMe
            if (root.has("ata_smart_attributes")) {
                // 传统SATA设备
                parseAtaSmartAttributes(diskId, root, attributes);
            } else if (root.has("nvme_smart_health_information_log")) {
                // NVMe设备
                parseNvmeSmartAttributes(diskId, root, attributes);
            } else if (root.has("scsi_self_test")) {
                // SCSI设备
                parseScsiSmartAttributes(diskId, root, attributes);
            } else {
                logger.warn("Unknown SMART format for disk {}, no known SMART attribute section found", diskId);
            }

            logger.debug("Parsed {} SMART attributes from JSON for disk {}", attributes.size(), diskId);
        } catch (IOException e) {
            logger.error("Failed to parse SMART JSON data for disk {}", diskId, e);
        }

        return attributes;
    }

    /**
     * 解析SATA/ATA设备的SMART属性
     * JSON格式: root.ata_smart_attributes.table[*]
     */
    private void parseAtaSmartAttributes(Long diskId, JsonNode root, List<SmartRecord> attributes) {
        JsonNode ataNode = root.get("ata_smart_attributes");
        if (!ataNode.has("table")) {
            return;
        }

        JsonNode table = ataNode.get("table");
        if (!table.isArray()) {
            return;
        }

        Integer temperature = null;

        for (JsonNode attr : table) {
            int id = attr.has("id") ? attr.get("id").asInt() : 0;
            String name = attr.has("name") ? attr.get("name").asText() : "unknown";
            int value = attr.has("value") ? attr.get("value").asInt() : 0;
            int worst = attr.has("worst") ? attr.get("worst").asInt() : 0;
            int thresh = attr.has("thresh") ? attr.get("thresh").asInt() : 0;
            long rawValue = 0;
            if (attr.has("raw")) {
                JsonNode rawNode = attr.get("raw");
                if (rawNode.has("value")) {
                    rawValue = rawNode.get("value").asLong();
                }
            }

            // 判断是否失败
            boolean failed = value <= thresh;

            // 检查是否是温度属性
            Integer temp = null;
            if (name.equals("Temperature_Celsius") ||
                name.equals("Airflow_Temperature_Cel") ||
                name.toLowerCase().contains("temperature")) {
                temperature = (int) rawValue;
                temp = (int) rawValue;
            }

            SmartRecord record = SmartRecord.builder()
                    .diskId(diskId)
                    .attributeId(id)
                    .attributeName(name)
                    .currentValue(value)
                    .worstValue(worst)
                    .threshold(thresh)
                    .rawValue(rawValue)
                    .failed(failed)
                    .temperature(temp)
                    .build();

            attributes.add(record);
        }

        // 如果我们找到了温度，但是列表中没有（理论上不会发生），确保它在列表中
        if (temperature != null && attributes.stream().noneMatch(r ->
            "Temperature_Celsius".equals(r.getAttributeName()) ||
            r.getAttributeName().toLowerCase().contains("temperature"))) {
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
    }

    /**
     * 解析NVMe设备的SMART属性
     * JSON格式: root.nvme_smart_health_information_log.*
     */
    private void parseNvmeSmartAttributes(Long diskId, JsonNode root, List<SmartRecord> attributes) {
        JsonNode nvmeNode = root.get("nvme_smart_health_information_log");

        // 遍历NVMe SMART日志的所有字段
        Iterator<String> fieldNames = nvmeNode.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            JsonNode valueNode = nvmeNode.get(fieldName);
            if (!valueNode.isNumber()) {
                continue;
            }

            long value = valueNode.asLong();
            String attributeName = fieldName;
            int attributeId = getNvmeAttributeId(attributeName);

            // 对于NVMe，currentValue和rawValue使用相同值
            int currentValue = (int) value;
            int threshold = 0;

            // 为一些关键属性设置合理的阈值和失败判断
            // 只有告警类型才需要判断失败
            boolean failed = false;
            switch (attributeName) {
                case "temperature":
                    threshold = 100;
                    failed = value > threshold;
                    break;
                case "critical_warning":
                    threshold = 1;
                    failed = value >= threshold; // 任何非0都是错误
                    break;
                case "available_spare":
                    // 剩余空间，获取阈值对比
                    JsonNode thresholdNode = nvmeNode.get("available_spare_threshold");
                    int availableThreshold = 1;
                    if (thresholdNode != null && thresholdNode.isNumber()) {
                        availableThreshold = thresholdNode.asInt();
                    }
                    threshold = availableThreshold;
                    failed = value < availableThreshold;
                    break;
                case "available_spare_threshold":
                    // 这个就是阈值本身，不需要告警
                    threshold = 0;
                    failed = false;
                    break;
                case "warning_comp_temperature_time":
                case "critical_comp_temperature_time":
                    threshold = 1;
                    failed = value > 0; // 时间大于0表示出过高温告警
                    break;
                case "media_and_data_integrity_errors":
                    threshold = 1;
                    failed = value > 0; // 有错误就是失败
                    break;
                default:
                    // 数据单元读取、写入、百分比、命令计数等都是统计数据，不需要告警
                    threshold = 0;
                    failed = false;
                    break;
            }
            Integer temp = null;

            // 处理温度属性
            if (fieldName.contains("temperature")) {
                // NVMe温度是以开尔文为单位吗？smartctl已经转换为摄氏度了，是的
                temp = (int) value;
            }

            // 转换为骆驼峰转下划线命名，保持和SATA一致
            attributeName = camelToSnake(attributeName);

            SmartRecord record = SmartRecord.builder()
                    .diskId(diskId)
                    .attributeId(attributeId)
                    .attributeName(attributeName)
                    .currentValue(currentValue)
                    .worstValue(currentValue)
                    .threshold(threshold)
                    .rawValue(value)
                    .failed(failed)
                    .temperature(temp)
                    .build();

            attributes.add(record);
        }
    }

    /**
     * 解析SCSI设备的SMART属性
     */
    private void parseScsiSmartAttributes(Long diskId, JsonNode root, List<SmartRecord> attributes) {
        // 简单处理，提取温度信息
        Integer temperature = null;

        // 遍历查找temperature相关字段
        if (root.has("temperature")) {
            JsonNode tempNode = root.get("temperature");
            if (tempNode.has("current")) {
                temperature = tempNode.get("current").asInt();
            }
        }

        if (temperature != null) {
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
    }

    /**
     * 骆驼峰转下划线命名
     */
    private String camelToSnake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    /**
     * 为NVMe属性分配一个ID
     */
    private int getNvmeAttributeId(String attributeName) {
        String lowerName = attributeName.toLowerCase();
        switch (lowerName) {
            case "criticalwarning":
            case "critical_warning":
                return 1;
            case "temperature":
                return 194;
            case "temperature_sensor_1":
                return 195;
            case "temperature_sensor_2":
                return 196;
            case "available_spare":
                return 2;
            case "available_spare_threshold":
                return 3;
            case "percentage_used":
                return 4;
            case "data_units_read":
            case "dataunitsread":
                return 5;
            case "data_units_written":
            case "dataunitswritten":
                return 6;
            case "host_read_commands":
            case "hostreadcommands":
                return 7;
            case "host_write_commands":
            case "hostwritecommands":
                return 8;
            case "controller_busy_time":
            case "controllerbusytime":
                return 9;
            case "power_cycles":
            case "powercycles":
                return 10;
            case "power_on_hours":
            case "poweronhours":
                return 12;
            case "unsafe_shutdowns":
            case "unsafeshutdowns":
                return 13;
            case "media_and_data_integrity_errors":
            case "mediaanddataintegrityerrors":
                return 14;
            case "error_information_log_entries":
            case "errorinformationlogentries":
                return 15;
            case "warning_temp_time":
            case "warning_comp_temperature_time":
                return 16;
            case "critical_temp_time":
            case "critical_comp_temperature_time":
                return 17;
            default:
                // 使用更稳定的hash，确保不会冲突太多
                return Math.abs(attributeName.hashCode() % 200) + 1;
        }
    }

    /**
     * 从原始df -k输出解析容量信息
     */
    public List<CapacityRecord> parseDfRaw(String dfRaw) {
        List<CapacityRecord> records = new ArrayList<>();

        if (dfRaw == null || dfRaw.isEmpty()) {
            return records;
        }

        // df -k 格式:
        // Filesystem     1K-blocks      Used Available Use% Mounted on
        // /dev/sda1      104857600  23456789  81400811  23% /

        // 匹配 df 行，忽略表头
        // 正则匹配: 任何非空行，不包含"Filesystem"
        boolean firstLine = true;
        Pattern DF_LINE_PATTERN = Pattern.compile(
                "^\\S+\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)%\\s+(.*)$"
        );

        try (BufferedReader reader = new BufferedReader(new StringReader(dfRaw))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (firstLine) {
                    firstLine = false;
                    continue; // skip header
                }
                if (line.trim().isEmpty()) {
                    continue;
                }

                Matcher matcher = DF_LINE_PATTERN.matcher(line);
                if (matcher.matches()) {
                    long totalBlocks = Long.parseLong(matcher.group(1));
                    long usedBlocks = Long.parseLong(matcher.group(2));
                    long availableBlocks = Long.parseLong(matcher.group(3));
                    int usagePercent = Integer.parseInt(matcher.group(4));
                    String mountPoint = matcher.group(5);

                    // 转换为字节 (1K blocks -> bytes)
                    long totalCapacity = totalBlocks * 1024;
                    long usedCapacity = usedBlocks * 1024;
                    long availableCapacity = availableBlocks * 1024;

                    CapacityRecord record = new CapacityRecord();
                    // 注意：这里无法直接关联diskId，客户端已经关联好了吗？不，分布式模式下由客户端已经划分好每个磁盘包含哪些分区
                    // 但是在分布式模式下客户端不会解析，所以这里只保存原始数据到文件，容量解析暂时不入库
                    // 保留方法用于将来扩展
                    record.setDiskId(null);
                    record.setUsedCapacity(usedCapacity);
                    record.setAvailableCapacity(availableCapacity);
                    record.setUsagePercent(usagePercent);
                    record.setMountPoint(mountPoint);

                    records.add(record);
                }
            }
        } catch (IOException e) {
            logger.error("Failed to parse df raw data", e);
        }

        return records;
    }
}