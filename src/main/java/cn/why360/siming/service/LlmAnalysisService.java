package cn.why360.siming.service;

import cn.why360.siming.config.SimingConfig;
import cn.why360.siming.dao.AnalysisResultDAO;
import cn.why360.siming.dao.CapacityRecordDAO;
import cn.why360.siming.dao.SmartRecordDAO;
import cn.why360.siming.entity.AnalysisResult;
import cn.why360.siming.entity.CapacityRecord;
import cn.why360.siming.entity.Disk;
import cn.why360.siming.entity.SmartRecord;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 大模型分析服务，基于历史监控数据进行硬盘健康分析
 */
public class LlmAnalysisService {
    private static final Logger logger = LoggerFactory.getLogger(LlmAnalysisService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SimingConfig.LlmConfig config;
    private final AnalysisResultDAO analysisResultDAO;
    private final CapacityRecordDAO capacityRecordDAO;
    private final SmartRecordDAO smartRecordDAO;
    private OpenAiService openAiService;

    public LlmAnalysisService(SimingConfig.LlmConfig config,
                              AnalysisResultDAO analysisResultDAO,
                              CapacityRecordDAO capacityRecordDAO,
                              SmartRecordDAO smartRecordDAO) {
        this.config = config;
        this.analysisResultDAO = analysisResultDAO;
        this.capacityRecordDAO = capacityRecordDAO;
        this.smartRecordDAO = smartRecordDAO;

        if (config.getApiKey() != null && !config.getApiKey().isEmpty() && !"your-api-key-here".equals(config.getApiKey())) {
            this.openAiService = new OpenAiService(config.getApiKey(), Duration.ofMillis(config.getTimeout()));
            logger.info("LLM service initialized with model: {}", config.getModel());
        } else {
            logger.warn("LLM API key not configured, analysis service will be disabled");
        }
    }

    /**
     * 检查是否配置了API Key
     */
    public boolean isConfigured() {
        return openAiService != null && config.getApiKey() != null &&
               !config.getApiKey().isEmpty() && !"your-api-key-here".equals(config.getApiKey());
    }

    /**
     * 分析指定时间区间内的硬盘数据
     */
    public AnalysisResult analyzeDisk(Disk disk, LocalDateTime startTime, LocalDateTime endTime) {
        if (!isConfigured()) {
            throw new IllegalStateException("LLM service is not configured, please check your API key in config file");
        }

        // 获取容量数据
        List<CapacityRecord> capacityRecords = capacityRecordDAO.findByDiskIdAndTimeRange(disk.getId(), startTime, endTime);

        // 获取SMART数据
        List<SmartRecord> smartRecords = smartRecordDAO.findByDiskIdAndTimeRange(disk.getId(), startTime, endTime);

        // 构建提示词
        String prompt = buildPrompt(disk, startTime, endTime, capacityRecords, smartRecords);
        logger.debug("Built analysis prompt for disk {}: {} characters", disk.getId(), prompt.length());

        // 调用大模型
        String response = callLlm(prompt);
        if (response == null || response.isEmpty()) {
            throw new RuntimeException("Failed to get response from LLM");
        }

        // 解析结果，提取评分和建议
        Integer healthScore = extractHealthScore(response);
        String healthLevel = extractHealthLevel(response, healthScore);
        String recommendations = extractRecommendations(response);

        // 保存分析结果
        AnalysisResult result = AnalysisResult.builder()
                .diskId(disk.getId())
                .startTime(startTime)
                .endTime(endTime)
                .analysisContent(response)
                .healthScore(healthScore)
                .healthLevel(healthLevel)
                .recommendations(recommendations)
                .build();

        return analysisResultDAO.save(result);
    }

    /**
     * 构建提示词
     */
    private String buildPrompt(Disk disk, LocalDateTime startTime, LocalDateTime endTime,
                                List<CapacityRecord> capacityRecords, List<SmartRecord> smartRecords) {
        String template = config.getPromptTemplate();
        if (template == null || template.isEmpty()) {
            template = getDefaultPromptTemplate();
        }

        Map<String, String> replacements = new HashMap<>();
        replacements.put("brand", disk.getBrand() != null ? disk.getBrand() : "Unknown");
        replacements.put("model", disk.getModel() != null ? disk.getModel() : "Unknown");
        replacements.put("serial", disk.getSerialNumber() != null ? disk.getSerialNumber() : "Unknown");
        replacements.put("totalSize", String.format("%.2f", disk.getTotalCapacityGB()));
        replacements.put("startTime", startTime.format(DATE_FORMATTER));
        replacements.put("endTime", endTime.format(DATE_FORMATTER));
        replacements.put("capacityData", formatCapacityData(capacityRecords));
        replacements.put("smartData", formatSmartData(smartRecords));

        // 替换占位符
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            template = template.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }

        return template;
    }

    /**
     * 格式化容量数据
     */
    private String formatCapacityData(List<CapacityRecord> records) {
        if (records.isEmpty()) {
            return "No capacity data available for this period";
        }

        StringJoiner sj = new StringJoiner("\n");
        sj.add("时间 | 挂载点 | 使用率(%) | 已用(GB) | 可用(GB)");
        sj.add("-----|-------|----------|---------|--------");

        int limit = Math.min(records.size(), 100); // 限制输出行数
        for (int i = 0; i < limit; i++) {
            CapacityRecord r = records.get(i);
            double usedGB = (double) r.getUsedCapacity() / (1024 * 1024 * 1024);
            double availableGB = (double) r.getAvailableCapacity() / (1024 * 1024 * 1024);
            sj.add(String.format("%s | %s | %.1f | %.2f | %.2f",
                    r.getRecordTime().format(DATE_FORMATTER),
                    r.getMountPoint() != null ? r.getMountPoint() : "-",
                    r.getUsagePercent(),
                    usedGB,
                    availableGB));
        }

        if (records.size() > limit) {
            sj.add("... (total " + records.size() + " records)");
        }

        return sj.toString();
    }

    /**
     * 格式化SMART数据，只关注关键属性
     */
    private String formatSmartData(List<SmartRecord> records) {
        if (records.isEmpty()) {
            return "No SMART data available for this period";
        }

        // 只关注关键属性
        List<String> importantAttributes = List.of(
                "Reallocated_Sector_Ct",
                "Current_Pending_Sector",
                "Offline_Uncorrectable",
                "Reallocated_Event_Count",
                "Temperature_Celsius",
                "Airflow_Temperature_Cel",
                "Power_On_Hours",
                "Power_Cycle_Count",
                "Media_Errors",
                "Total_LBAs_Written"
        );

        // 按属性分组，找出最早和最新的值
        Map<String, List<SmartRecord>> grouped = new HashMap<>();
        for (SmartRecord record : records) {
            String name = record.getAttributeName();
            if (importantAttributes.contains(name) || importantAttributes.stream().anyMatch(name::contains)) {
                grouped.computeIfAbsent(name, k -> new ArrayList<>()).add(record);
            }
        }

        if (grouped.isEmpty()) {
            return "No important SMART attributes found";
        }

        StringJoiner sj = new StringJoiner("\n");
        sj.add("属性名称 | 初始值 | 当前值 | 阈值 | 原始值初始 | 原始值当前");
        sj.add("--------|-------|-------|------|------------|-----------");

        for (Map.Entry<String, List<SmartRecord>> entry : grouped.entrySet()) {
            List<SmartRecord> attrRecords = entry.getValue();
            attrRecords.sort((a, b) -> a.getRecordTime().compareTo(b.getRecordTime()));

            SmartRecord first = attrRecords.get(0);
            SmartRecord last = attrRecords.get(attrRecords.size() - 1);

            sj.add(String.format("%s | %d | %d | %d | %d | %d",
                    entry.getKey(),
                    first.getCurrentValue(),
                    last.getCurrentValue(),
                    last.getThreshold(),
                    first.getRawValue(),
                    last.getRawValue()));
        }

        // 添加温度统计
        List<SmartRecord> temperatureRecords = new ArrayList<>();
        for (SmartRecord r : records) {
            if (r.getAttributeName().contains("Temperature") && r.getTemperature() != null) {
                temperatureRecords.add(r);
            }
        }

        if (!temperatureRecords.isEmpty()) {
            int avgTemp = (int) temperatureRecords.stream()
                    .mapToInt(SmartRecord::getTemperature)
                    .average()
                    .orElse(0);
            int maxTemp = temperatureRecords.stream()
                    .mapToInt(SmartRecord::getTemperature)
                    .max()
                    .orElse(0);
            sj.add("\n温度统计: 平均 " + avgTemp + "°C, 最高 " + maxTemp + "°C");
        }

        return sj.toString();
    }

    /**
     * 调用大模型API
     */
    private String callLlm(String prompt) {
        try {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new ChatMessage(ChatMessageRole.USER.value(), prompt));

            ChatCompletionRequest request = ChatCompletionRequest.builder()
                    .model(config.getModel())
                    .messages(messages)
                    .temperature(0.7)
                    .maxTokens(1000)
                    .build();

            String response = openAiService.createChatCompletion(request)
                    .getChoices()
                    .get(0)
                    .getMessage()
                    .getContent();

            logger.info("Received response from LLM, {} characters", response.length());
            return response;
        } catch (Exception e) {
            logger.error("Failed to call LLM API", e);
            return null;
        }
    }

    /**
     * 从响应中提取健康评分
     */
    private Integer extractHealthScore(String response) {
        // 尝试寻找类似 "健康评分: 85" 的模式
        if (response.matches(".*\\b(健康评分|health score|health_score)\\b.*\\b(\\d{1,3})\\b.*")) {
            // 简单的正则提取
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(".*\\b(健康评分|health score|health_score)\\b.*\\b(\\d{1,3})\\b.*", java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher matcher = pattern.matcher(response);
            if (matcher.find()) {
                int score = Integer.parseInt(matcher.group(2));
                if (score >= 0 && score <= 100) {
                    return score;
                }
            }
        }
        return null;
    }

    /**
     * 提取健康等级
     */
    private String extractHealthLevel(String response, Integer score) {
        if (response.contains("GOOD") || response.contains("良好") || response.contains("健康")) {
            return "GOOD";
        } else if (response.contains("WARNING") || response.contains("警告")) {
            return "WARNING";
        } else if (response.contains("CRITICAL") || response.contains("严重") || response.contains("危险")) {
            return "CRITICAL";
        }

        if (score != null) {
            if (score >= 80) return "GOOD";
            else if (score >= 50) return "WARNING";
            else return "CRITICAL";
        }

        return "UNKNOWN";
    }

    /**
     * 提取建议部分
     */
    private String extractRecommendations(String response) {
        // 查找建议部分
        String[] parts = response.split("(?i)(建议|recommendations|suggestions)[:：]");
        if (parts.length > 1) {
            return parts[1].trim();
        }
        // 如果没有找到，返回最后一段
        return response;
    }

    /**
     * 获取默认提示词模板
     */
    private String getDefaultPromptTemplate() {
        return "你是一位专业的存储硬件健康分析师，请根据以下硬盘监控数据进行分析：\n\n" +
                "硬盘基本信息：\n" +
                "品牌：{{brand}}\n" +
                "型号：{{model}}\n" +
                "序列号：{{serial}}\n" +
                "总容量：{{totalSize}} GB\n\n" +
                "时间区间：{{startTime}} 到 {{endTime}}\n\n" +
                "容量变化数据：\n" +
                "{{capacityData}}\n\n" +
                "SMART属性变化数据：\n" +
                "{{smartData}}\n\n" +
                "请分析：\n" +
                "1. 容量使用趋势是怎样的，是否存在异常增长\n" +
                "2. SMART各项指标是否有异常变化，特别是：\n" +
                "   - 坏块数量（Reallocated_Sector_Ct）\n" +
                "   - 待映射扇区（Current_Pending_Sector）\n" +
                "   - 不可修复错误（Offline_Uncorrectable）\n" +
                "   - 温度变化情况\n" +
                "3. 根据历史数据变化推测硬盘健康状况\n" +
                "4. 给出明确的建议：是否需要备份数据，是否需要更换硬盘等\n" +
                "5. 最后给出一个0-100的健康评分\n\n" +
                "请用中文给出清晰专业的分析结论和建议。";
    }

    /**
     * 重新加载配置（更新API配置）
     */
    public void reloadConfig(SimingConfig config) {
        SimingConfig.LlmConfig llmConfig = config.getLlm();
        if (llmConfig.getApiKey() != null && !llmConfig.getApiKey().isEmpty() && !"your-api-key-here".equals(llmConfig.getApiKey())) {
            this.openAiService = new OpenAiService(llmConfig.getApiKey(), Duration.ofMillis(llmConfig.getTimeout()));
            logger.info("LLM service reloaded with model: {}", llmConfig.getModel());
        } else {
            this.openAiService = null;
            logger.warn("LLM API key not configured after reload, analysis service disabled");
        }
    }

    /**
     * 分析最近N天的硬盘历史（便捷方法，用于API调用）
     */
    public String analyzeDiskHistory(Disk disk, int days) {
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusDays(days);
        AnalysisResult result = analyzeDisk(disk, startTime, endTime);
        return result.getAnalysisContent();
    }
}
