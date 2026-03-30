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
import cn.why360.siming.service.LlmAnalysisService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 管理接口 - 使用SpringMVC注解
 */
@RestController
@RequestMapping("/api")
public class AdminController {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(AdminController.class);
    private final DiskDAO diskDAO;
    private final CapacityRecordDAO capacityDAO;
    private final SmartRecordDAO smartDAO;
    private final AnalysisResultDAO analysisDAO;
    private final LlmAnalysisService llmService;
    private final SimingConfig config;

    public AdminController(DiskDAO diskDAO, CapacityRecordDAO capacityDAO,
                          SmartRecordDAO smartDAO, AnalysisResultDAO analysisDAO,
                          LlmAnalysisService llmService, SimingConfig config) {
        this.diskDAO = diskDAO;
        this.capacityDAO = capacityDAO;
        this.smartDAO = smartDAO;
        this.analysisDAO = analysisDAO;
        this.llmService = llmService;
        this.config = config;
    }

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
    @GetMapping("/disk/{id}")
    public Map<String, Object> getDiskDetail(@PathVariable Long id) {
        Optional<Disk> diskOpt = diskDAO.findById(id);
        if (diskOpt.isEmpty()) {
            throw new RuntimeException("Disk not found with id: " + id);
        }
        List<CapacityRecord> capacity = capacityDAO.findByDiskId(id);
        List<SmartRecord> smart = smartDAO.findByDiskId(id);
        Map<String, Object> result = new HashMap<>();
        result.put("disk", diskOpt.get());
        result.put("capacity", capacity);
        result.put("smart", smart);
        return result;
    }

    /**
     * 获取分析历史列表
     */
    @GetMapping("/analyses")
    public List<AnalysisResult> getAnalyses() {
        return analysisDAO.findAll();
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
     * 触发AI分析
     */
    @PostMapping("/analyze")
    public Map<String, Object> triggerAnalysis(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new HashMap<>();
        try {
            long diskId = ((Number) request.get("diskId")).longValue();
            int days = ((Number) request.get("days")).intValue();

            Optional<Disk> diskOpt = diskDAO.findById(diskId);
            if (diskOpt.isEmpty()) {
                result.put("success", false);
                result.put("error", "Disk not found");
                return result;
            }

            String analysisResult = llmService.analyzeDiskHistory(diskOpt.get(), days);
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
                Number timeout = (Number) updates.get("timeout");
                newConfig.setTimeout(timeout.intValue());
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
                Number temperature = (Number) updates.get("temperature");
                newConfig.setTemperature(temperature.doubleValue());
            } else if (currentConfig != null && currentConfig.getTemperature() != null) {
                newConfig.setTemperature(currentConfig.getTemperature());
            } else {
                newConfig.setTemperature(0.7);
            }

            if (updates.containsKey("maxTokens")) {
                Number maxTokens = (Number) updates.get("maxTokens");
                newConfig.setMaxTokens(maxTokens.intValue());
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
     * 客户端数据上报API，接收分布式客户端采集的硬盘数据
     */
    @PostMapping("/report")
    public Map<String, Object> report(@RequestBody String reportJson) {
        Map<String, Object> result = new HashMap<>();

        try {
            // 使用Jackson手动解析，避免类型转换问题
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> report = mapper.readValue(reportJson, Map.class);
            
            String clientId = (String) report.get("client_id");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> disks = (List<Map<String, Object>>) report.get("disks");

            if (disks == null || disks.isEmpty()) {
                result.put("success", false);
                result.put("error", "No disk data in report");
                return result;
            }

            int savedCount = 0;
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

                // 检查是否已经存在这个硬盘（通过设备路径+客户端ID判断）
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

                // 保存SMART记录
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> smartAttributes = (List<Map<String, Object>>) diskData.get("smart_attributes");
                if (smartAttributes != null) {
                    for (Map<String, Object> smartData : smartAttributes) {
                        SmartRecord record = new SmartRecord();
                        record.setDiskId(diskId);
                        record.setAttributeId(((Number) smartData.get("attribute_id")).intValue());
                        record.setAttributeName((String) smartData.get("attribute_name"));
                        record.setCurrentValue(((Number) smartData.get("current_value")).intValue());
                        record.setWorstValue(((Number) smartData.get("worst_value")).intValue());
                        record.setThreshold(((Number) smartData.get("threshold")).intValue());
                        record.setRawValue(((Number) smartData.get("raw_value")).longValue());
                        // 兼容处理：既能接收布尔true/false，也能接收数字0/1
                        Object failedObj = smartData.get("failed");
                        boolean failed;
                        if (failedObj instanceof Boolean) {
                            failed = (Boolean) failedObj;
                        } else if (failedObj instanceof Number) {
                            failed = ((Number) failedObj).intValue() != 0;
                        } else {
                            failed = false;
                        }
                        record.setFailed(failed);

                        Number temp = (Number) smartData.get("temperature");
                        if (temp != null) {
                            record.setTemperature(temp.intValue());
                        }

                        smartDAO.insert(record);
                    }
                }

                // 保存容量记录
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> capacityRecords = (List<Map<String, Object>>) diskData.get("capacity_records");
                if (capacityRecords != null) {
                    for (Map<String, Object> capacityData : capacityRecords) {
                        CapacityRecord record = new CapacityRecord();
                        record.setDiskId(diskId);
                        record.setUsedCapacity(((Number) capacityData.get("used_capacity")).longValue());
                        record.setAvailableCapacity(((Number) capacityData.get("available_capacity")).longValue());
                        record.setUsagePercent(((Number) capacityData.get("usage_percent")).intValue());
                        record.setMountPoint((String) capacityData.get("mount_point"));
                        capacityDAO.insert(record);
                    }
                }

                savedCount++;
            }

            result.put("success", true);
            result.put("message", "Saved " + savedCount + " disks from client " + clientId);
            return result;
        } catch (Exception e) {
            logger.error("Failed to process report", e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }
    }
}