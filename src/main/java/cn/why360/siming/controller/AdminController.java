package cn.why360.siming.controller;

import cn.why360.siming.config.SimingConfig;
import cn.why360.siming.dao.AnalysisResultDAO;
import cn.why360.siming.dao.CapacityRecordDAO;
import cn.why360.siming.dao.DiskDAO;
import cn.why360.siming.dao.SmartRecordDAO;
import cn.why360.siming.entity.AnalysisResult;
import cn.why360.siming.entity.CapacityRecord;
import cn.why360.siming.entity.Disk;
import cn.why360.siming.entity.SmartRecord;
import cn.why360.siming.scheduler.SchedulerManager;
import cn.why360.siming.service.LlmAnalysisService;
import cn.why360.siming.service.SmartReaderService;
import org.quartz.SchedulerException;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 管理接口 - 使用SpringMVC注解
 */
@RestController
@RequestMapping("/api")
public class AdminController {

    private static final Logger logger = LoggerFactory.getLogger(AdminController.class);
    private final DiskDAO diskDAO;
    private final CapacityRecordDAO capacityDAO;
    private final SmartRecordDAO smartDAO;
    private final AnalysisResultDAO analysisDAO;
    private final LlmAnalysisService llmService;
    private final SimingConfig config;
    private final SchedulerManager schedulerManager;

    public AdminController(DiskDAO diskDAO, CapacityRecordDAO capacity,
                          SmartRecordDAO smartDAO, AnalysisResultDAO analysisDAO,
                          LlmAnalysisService llmService, SmartReaderService smartReaderService,
                          SimingConfig config,
                          SchedulerManager schedulerManager) {
        this.diskDAO = diskDAO;
        this.capacityDAO = capacity;
        this.smartDAO = smartDAO;
        this.analysisDAO = analysisDAO;
        this.llmService = llmService;
        this.smartReaderService = smartReaderService;
        this.config = config;
        this.schedulerManager = schedulerManager;
    }

    private final SmartReaderService smartReaderService;

    /**
     * 获取硬盘列表
     */
    @GetMapping("/disks")
    public List<Disk> getDisks() {
        return diskDAO.findAll();
    }

    /**
     * 获取硬盘详情和历史数据
     */
    @GetMapping("/disk/{id}/history")
    public Map<String, Object> getDiskHistory(@PathVariable Long id) {
        Optional<Disk> diskOpt = diskDAO.findById(id);
        if (diskOpt.isEmpty()) {
            throw new RuntimeException("Disk not found with id: " + id);
        }
        List<CapacityRecord> capacity = capacityDAO.findByDiskId(id);
        List<SmartRecord> allSmart = smartDAO.findByDiskId(id);
        
        // 按照record_time分组，将同一时间采集的所有属性放在一起
        Map<LocalDateTime, List<SmartRecord>> groupedByTime = new LinkedHashMap<>();
        for (SmartRecord record : allSmart) {
            LocalDateTime time = record.getRecordTime();
            groupedByTime.computeIfAbsent(time, k -> new ArrayList<>()).add(record);
        }
        
        // 转换为便于前端处理的格式
        List<Map<String, Object>> groupedSmart = new ArrayList<>();
        for (Map.Entry<LocalDateTime, List<SmartRecord>> entry : groupedByTime.entrySet()) {
            Map<String, Object> timeEntry = new HashMap<>();
            timeEntry.put("timestamp", entry.getKey().toString().replace('T', ' '));
            timeEntry.put("attributes", entry.getValue());
            
            // 提取整体温度（如果有的话）
            Integer temp = null;
            for (SmartRecord attr : entry.getValue()) {
                if (attr.getTemperature() != null && attr.getTemperature() >= 0) {
                    temp = attr.getTemperature();
                    break;
                }
            }
            timeEntry.put("temperature", temp);
            
            groupedSmart.add(timeEntry);
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("disk", diskOpt.get());
        result.put("capacity", capacity);
        result.put("smart", groupedSmart);
        return result;
    }

    /**
     * 获取分析历史列表
     */
    @GetMapping("/analyses")
    public List<AnalysisResult> getAnalyses() {
        return analysisDAO.findAllByCreateTimeDesc();
    }

    /**
     * 获取指定硬盘的分析历史列表（倒序展示）
     */
    @GetMapping("/disk/{diskId}/analyses")
    public List<AnalysisResult> getDiskAnalyses(@PathVariable Long diskId) {
        return analysisDAO.findByDiskIdOrderByCreateTimeDesc(diskId);
    }

    /**
     * 获取单个分析结果
     */
    @GetMapping("/analyses/{id}")
    public AnalysisResult getAnalysisDetail(@PathVariable Long id) {
        Optional<AnalysisResult> analysisOpt = analysisDAO.findById(id);
        if (analysisOpt.isEmpty()) {
            throw new RuntimeException("Analysis not found with id: " + id);
        }
        return analysisOpt.get();
    }

    /**
     * 触发AI分析（四时间点趋势分析）
     */
    @PostMapping("/analyze")
    public Map<String, Object> triggerAnalysis(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new HashMap<>();
        try {
            long diskId = ((Number) request.get("diskId")).longValue();

            Optional<Disk> diskOpt = diskDAO.findById(diskId);
            if (diskOpt.isEmpty()) {
                result.put("success", false);
                result.put("error", "Disk not found");
                return result;
            }

            String analysisResult = llmService.analyzeDiskTrendAPI(diskOpt.get());
            result.put("success", true);
            result.put("result", analysisResult);
            return result;
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    /**
     * 获取当前大模型配置
     */
    @GetMapping("/config")
    public cn.why360.siming.entity.LlmConfig getConfig() {
        return llmService.getCurrentConfig();
    }

    /**
     * 更新配置 - 现在存储在数据库
     */
    @PostMapping("/config/update")
    public Map<String, Object> updateConfig(@RequestBody Map<String, Object> updates) {
        Map<String, Object> result = new HashMap<>();
        try {
            cn.why360.siming.entity.LlmConfig currentConfig = llmService.getCurrentConfig();
            cn.why360.siming.entity.LlmConfig newConfig = currentConfig != null ?
                    new cn.why360.siming.entity.LlmConfig() : new cn.why360.siming.entity.LlmConfig();

            if (currentConfig != null) {
                newConfig.setId(currentConfig.getId());
            }

            if (updates.containsKey("apiBaseUrl")) {
                newConfig.setApiBaseUrl((String) updates.get("apiBaseUrl"));
            } else if (currentConfig != null) {
                newConfig.setApiBaseUrl(currentConfig.getApiBaseUrl());
            } else {
                newConfig.setApiBaseUrl("https://api.openai.com/v1");
            }

            if (updates.containsKey("apiKey")) {
                newConfig.setApiKey((String) updates.get("apiKey"));
            } else if (currentConfig != null) {
                newConfig.setApiKey(currentConfig.getApiKey());
            }

            if (updates.containsKey("model")) {
                newConfig.setModel((String) updates.get("model"));
            } else if (currentConfig != null) {
                newConfig.setModel(currentConfig.getModel());
            } else {
                newConfig.setModel("gpt-3.5-turbo");
            }

            if (updates.containsKey("timeout")) {
                Object timeoutObj = updates.get("timeout");
                if (timeoutObj instanceof String) {
                    newConfig.setTimeout(Integer.parseInt((String) timeoutObj));
                } else if (timeoutObj instanceof Number) {
                    newConfig.setTimeout(((Number) timeoutObj).intValue());
                } else {
                    newConfig.setTimeout(60000);
                }
            } else if (currentConfig != null) {
                newConfig.setTimeout(currentConfig.getTimeout());
            } else {
                newConfig.setTimeout(60000);
            }

            if (updates.containsKey("promptTemplate")) {
                newConfig.setPromptTemplate((String) updates.get("promptTemplate"));
            } else if (currentConfig != null) {
                newConfig.setPromptTemplate(currentConfig.getPromptTemplate());
            } else {
                newConfig.setPromptTemplate(cn.why360.siming.service.LlmAnalysisService.getDefaultPromptTemplate());
            }

            if (updates.containsKey("temperature")) {
                Object tempObj = updates.get("temperature");
                if (tempObj instanceof String) {
                    newConfig.setTemperature(Double.parseDouble((String) tempObj));
                } else if (tempObj instanceof Number) {
                    newConfig.setTemperature(((Number) tempObj).doubleValue());
                } else {
                    newConfig.setTemperature(0.7);
                }
            } else if (currentConfig != null && currentConfig.getTemperature() != null) {
                newConfig.setTemperature(currentConfig.getTemperature());
            } else {
                newConfig.setTemperature(0.7);
            }

            if (updates.containsKey("maxTokens")) {
                Object maxObj = updates.get("maxTokens");
                if (maxObj instanceof String) {
                    newConfig.setMaxTokens(Integer.parseInt((String) maxObj));
                } else if (maxObj instanceof Number) {
                    newConfig.setMaxTokens(((Number) maxObj).intValue());
                } else {
                    newConfig.setMaxTokens(1000);
                }
            } else if (currentConfig != null && currentConfig.getMaxTokens() != null) {
                newConfig.setMaxTokens(currentConfig.getMaxTokens());
            } else {
                newConfig.setMaxTokens(1000);
            }

            // 保存到数据库并重新初始化LLM服务
            llmService.saveConfig(newConfig);

            result.put("success", true);
            result.put("config", newConfig);
            return result;
        } catch (Exception e) {
            logger.error("Failed to update LLM config", e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    /**
     * 客户端数据上报API，接收分布式客户端采集的硬盘原始数据
     * 按照用户要求：以序列号为目录，时间和类型为文件名，存储对应的数据，后台解析入库
     * 客户端使用base64编码原始数据，避免JSON转义问题
     */
    @PostMapping("/report")
    public Map<String, Object> report(@RequestBody String reportJson) {
        Map<String, Object> result = new HashMap<>();

        try {
            logger.info("Received report from client, length: {}", reportJson.length());
            // 使用Jackson手动解析，避免类型转换问题
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> report = mapper.readValue(reportJson, Map.class);
            
            String clientId = (String) report.get("client_id");
            Number timestampNum = (Number) report.get("timestamp");
            long timestamp = timestampNum != null ? timestampNum.longValue() : System.currentTimeMillis() / 1000;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> disks = (List<Map<String, Object>>) report.get("disks");
            String dfRaw = (String) report.get("df_raw");

            if (disks == null || disks.isEmpty()) {
                logger.warn("Received empty report from client {}, no disks data", clientId);
                result.put("success", false);
                result.put("error", "No disk data in report");
                return result;
            }

            // 获取原始数据存储根目录
            String basePathStr = config.getRawData().getBasePath();
            Path basePath = Paths.get(basePathStr);
            
            // 确保根目录存在
            if (!Files.exists(basePath)) {
                Files.createDirectories(basePath);
                logger.info("Created raw data storage directory: {}", basePath);
            }

            int savedCount = 0;
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
            String timeStr = LocalDateTime.now().format(formatter);

            // 保存df原始输出（整个报告一份，客户端已经包含所有分区，这里整体保存）
            if (dfRaw != null && !dfRaw.isEmpty()) {
                // df原始数据存放在根目录，按时间戳命名
                String dfFileName = String.format("%s_df.txt", timeStr);
                Path dfFilePath = basePath.resolve(dfFileName);
                Files.write(dfFilePath, dfRaw.getBytes(StandardCharsets.UTF_8));
                logger.info("Saved df raw data to: {}, original size: {} bytes", dfFilePath, dfRaw.getBytes(StandardCharsets.UTF_8).length);
            }

            // 保存整个报告JSON
            String fullReportFileName = String.format("%s_full_report.json", timeStr);
            Path fullReportPath = basePath.resolve(fullReportFileName);
            Files.write(fullReportPath, reportJson.getBytes(StandardCharsets.UTF_8));
            logger.info("Saved full report to: {}", fullReportPath);

            for (Map<String, Object> diskData : disks) {
                // 构建Disk对象
                Disk disk = new Disk();
                disk.setClientId(clientId);
                disk.setDevicePath((String) diskData.get("device_path"));
                disk.setBrand((String) diskData.get("brand"));
                disk.setModel((String) diskData.get("model"));
                disk.setSerialNumber((String) diskData.get("serial_number"));
                disk.setTotalCapacity(((Number) diskData.get("total_capacity")).longValue());
                // 兼容处理：既能接收布尔true/false，也能接收数字0/1
                Object isSsdObj = diskData.get("is_ssd");
                boolean isSsd;
                if (isSsdObj instanceof Boolean) {
                    isSsd = (Boolean) isSsdObj;
                } else if (isSsdObj instanceof Number) {
                    isSsd = ((Number) isSsdObj).intValue() != 0;
                } else {
                    isSsd = false;
                }
                disk.setSSD(isSsd);
                disk.setMonitored(true);

                // 检查是否已经存在这个硬盘（通过序列号+客户端ID判断？不，同一客户端同设备）
                Optional<Disk> existingDisk = diskDAO.findByClientIdAndDevicePath(clientId, disk.getDevicePath());
                Long diskId;
                if (existingDisk.isPresent()) {
                    // 更新现有硬盘
                    disk.setId(existingDisk.get().getId());
                    disk.setMonitored(existingDisk.get().isMonitored());
                    diskDAO.update(disk);
                    diskId = existingDisk.get().getId();
                } else {
                    // 插入新硬盘
                    diskId = diskDAO.insert(disk);
                }

                // 获取序列号，创建序列号目录
                String serialNumber = disk.getSerialNumber();
                if (serialNumber == null || serialNumber.isEmpty()) {
                    serialNumber = "unknown_" + disk.getDevicePath().replace("/", "_");
                }
                // 清理序列号文件名，去除特殊字符
                serialNumber = serialNumber.replaceAll("[^a-zA-Z0-9_\\-]", "_");
                Path serialDir = basePath.resolve(serialNumber);
                if (!Files.exists(serialDir)) {
                    Files.createDirectories(serialDir);
                }

                // 获取smart JSON，shell已做正确转义，直接使用
                String smartJson = (String) diskData.get("smart_json");
                if (smartJson != null && !smartJson.isEmpty()) {
                    // 保存原始JSON内容到文件
                    String smartFileName = String.format("%s_%d_smart.json", timeStr, timestamp);
                    Path smartFilePath = serialDir.resolve(smartFileName);
                    Files.write(smartFilePath, smartJson.getBytes(StandardCharsets.UTF_8));
                    logger.info("Saved SMART JSON data to: {}, original size: {} bytes", smartFilePath, smartJson.getBytes(StandardCharsets.UTF_8).length);
                }

                // 解析smart JSON数据并入库
                if (smartJson != null && !smartJson.isEmpty()) {
                    List<SmartRecord> records = smartReaderService.parseSmartJson(diskId, smartJson);
                    for (SmartRecord record : records) {
                        smartDAO.insert(record);
                    }
                    logger.info("Parsed and saved {} SMART attributes for disk {} ({})", records.size(), diskId, disk.getDevicePath());
                } else {
                    logger.warn("No SMART JSON data for disk {} ({})", diskId, disk.getDevicePath());
                }

                savedCount++;
            }

            // 解析df原始数据并保存容量记录
            if (dfRaw != null && !dfRaw.isEmpty()) {
                List<CapacityRecord> capacityRecords = smartReaderService.parseDfRaw(dfRaw);
                int capacitySaved = 0;
                for (CapacityRecord record : capacityRecords) {
                    // 匹配文件系统到对应的磁盘
                    // 文件系统路径格式: /dev/sda1, /dev/nvme0n1p1 等
                    String filesystem = record.getMountPoint();
                    // 查找匹配的磁盘：磁盘设备路径是文件系统路径的前缀
                    // 例如 /dev/sda1 属于 /dev/sda, /dev/nvme0n1p1 属于 /dev/nvme0n1
                    for (Map<String, Object> diskData : disks) {
                        String diskDevicePath = (String) diskData.get("device_path");
                        String fsPath = record.getFilesystem();
                        if (fsPath != null && fsPath.startsWith(diskDevicePath)) {
                            // 检查匹配规则：分区名称是硬盘名加上数字或p
                            if (fsPath.length() > diskDevicePath.length()) {
                                char nextChar = fsPath.charAt(diskDevicePath.length());
                                if (!Character.isLetter(nextChar) || nextChar == 'p') {
                                    // 找到对应的磁盘ID
                                    Optional<Disk> existingDisk = diskDAO.findByClientIdAndDevicePath(clientId, diskDevicePath);
                                    if (existingDisk.isPresent()) {
                                        record.setDiskId(existingDisk.get().getId());
                                        capacityDAO.insert(record);
                                        capacitySaved++;
                                        logger.debug("Saved capacity record for filesystem {} on disk {}",
                                                fsPath, existingDisk.get().getId());
                                    }
                                }
                            } else if (fsPath.equals(diskDevicePath)) {
                                // 正好相等说明整个硬盘就是一个分区
                                Optional<Disk> existingDisk = diskDAO.findByClientIdAndDevicePath(clientId, diskDevicePath);
                                if (existingDisk.isPresent()) {
                                    record.setDiskId(existingDisk.get().getId());
                                    capacityDAO.insert(record);
                                    capacitySaved++;
                                    logger.debug("Saved capacity record for entire disk {}: {}",
                                            diskDevicePath, existingDisk.get().getId());
                                }
                            }
                        }
                    }
                }
                logger.info("Parsed df raw data, saved {} capacity records to database", capacitySaved);
            }

            result.put("success", true);
            result.put("message", "Saved " + savedCount + " disks from client " + clientId + 
                    ", raw data stored in " + basePathStr);
            logger.info("Successfully processed report from client {}, saved {} disks", clientId, savedCount);
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // JSON解析异常，详细打印
            logger.error("JSON parsing failed when processing report, content length: {}", reportJson.length(), e);
            result.put("success", false);
            result.put("error", "JSON parse error: " + e.getMessage());
            return result;
        } catch (IOException e) {
            // 文件IO异常，详细打印
            logger.error("IO error when saving raw data file", e);
            result.put("success", false);
            result.put("error", "File IO error: " + e.getMessage());
            return result;
        } catch (Exception e) {
            // 其他异常，完整打印堆栈
            logger.error("Unexpected error processing client report", e);
            result.put("success", false);
            result.put("error", e.getMessage() + " (" + e.getClass().getSimpleName() + ")");
            return result;
        }
    }

    /**
     * 更新硬盘监控配置（监控状态和Cron表达式）
     */
    @PostMapping("/disk/{id}/update")
    public Map<String, Object> updateDiskConfig(@PathVariable Long id, @RequestBody Map<String, Object> updates) {
        Map<String, Object> result = new HashMap<>();
        try {
            Optional<Disk> diskOpt = diskDAO.findById(id);
            if (diskOpt.isEmpty()) {
                result.put("success", false);
                result.put("error", "Disk not found");
                return result;
            }

            Disk disk = diskOpt.get();

            // 更新监控状态
            if (updates.containsKey("monitored")) {
                Object monitoredObj = updates.get("monitored");
                boolean monitored;
                if (monitoredObj instanceof Boolean) {
                    monitored = (Boolean) monitoredObj;
                } else if ("true".equals(monitoredObj)) {
                    monitored = true;
                } else {
                    monitored = false;
                }
                disk.setMonitored(monitored);
            }

            // 更新Cron表达式
            if (updates.containsKey("monitorCron")) {
                String cron = (String) updates.get("monitorCron");
                if (cron != null && cron.trim().isEmpty()) {
                    cron = null;
                }
                disk.setMonitorCron(cron);
            }

            // 更新到数据库
            diskDAO.update(disk);

            // 重新调度所有任务
            schedulerManager.rescheduleAll();

            result.put("success", true);
            result.put("disk", disk);
            logger.info("Updated disk {} config, rescheduled all jobs", id);
            return result;
        } catch (SchedulerException e) {
            logger.error("Failed to reschedule after disk config update", e);
            result.put("success", false);
            result.put("error", "Failed to reschedule: " + e.getMessage());
            return result;
        } catch (Exception e) {
            logger.error("Failed to update disk config", e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }
    }
}
