package cn.why360.siming.service;

import cn.why360.siming.dao.LlmConfigDAO;
import cn.why360.siming.dao.AnalysisResultDAO;
import cn.why360.siming.dao.CapacityRecordDAO;
import cn.why360.siming.dao.SmartRecordDAO;
import cn.why360.siming.entity.LlmConfig;
import cn.why360.siming.entity.AnalysisResult;
import cn.why360.siming.entity.CapacityRecord;
import cn.why360.siming.entity.Disk;
import cn.why360.siming.entity.SmartRecord;
import com.theokanning.openai.client.OpenAiApi;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;

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
 * LLM配置存储在数据库中，支持在线修改
 */
public class LlmAnalysisService {
    private static final Logger logger = LoggerFactory.getLogger(LlmAnalysisService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LlmConfigDAO llmConfigDAO;
    private final AnalysisResultDAO analysisResultDAO;
    private final CapacityRecordDAO capacityRecordDAO;
    private final SmartRecordDAO smartRecordDAO;
    private volatile OpenAiService openAiService;

    public LlmAnalysisService(LlmConfigDAO llmConfigDAO,
                              AnalysisResultDAO analysisResultDAO,
                              CapacityRecordDAO capacityRecordDAO,
                              SmartRecordDAO smartRecordDAO) {
        this.llmConfigDAO = llmConfigDAO;
        this.analysisResultDAO = analysisResultDAO;
        this.capacityRecordDAO = capacityRecordDAO;
        this.smartRecordDAO = smartRecordDAO;

        // 初始化OpenAi服务
        reloadOpenAiClient();
    }

    /**
     * 重新加载配置并重建OpenAi客户端
     */
    public synchronized void reloadOpenAiClient() {
        LlmConfig config = getCurrentConfig();
        if (config == null) {
            logger.warn("No LLM configuration found in database, analysis service will be disabled");
            this.openAiService = null;
            return;
        }

        String apiKey = config.getApiKey();
        if (apiKey != null && !apiKey.isEmpty() && !"your-api-key-here".equals(apiKey)) {
            String apiBaseUrl = config.getApiBaseUrl();
            Duration timeout = Duration.ofMillis(config.getTimeout());
            
            // OpenAiApi接口中endpoint已经是"/v1/chat/completions"
            // 支持两种模式：
            // 1. 用户只提供基础URL（如 https://ark.cn-beijing.volces.com/api/coding），我们添加 /v1/chat/completions
            // 2. 用户已经提供版本路径（如 https://ark.cn-beijing.volces.com/api/coding/v3），我们只添加 /chat/completions
            String baseUrl = apiBaseUrl;
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = "https://api.openai.com/";
            }
            // 确保baseUrl以斜杠结尾
            if (!baseUrl.endsWith("/")) {
                baseUrl = baseUrl + "/";
            }

            // 检查用户的baseUrl是否已经包含了版本号路径（如v1, v2, v3等）
            boolean containsVersionPath = baseUrl.matches(".*/v\\d+/?$");
            String baseUrlForRetrofit;
            String endpointToRemove = "/v1";

            // 对于0.16.1版本，手动构建支持自定义baseUrl
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
            logging.setLevel(HttpLoggingInterceptor.Level.BASIC);
            
            String userBaseUrl = baseUrl;
            OkHttpClient client = new OkHttpClient.Builder()
                    .addInterceptor(chain -> {
                        okhttp3.Request originalRequest = chain.request();
                        okhttp3.HttpUrl originalHttpUrl = originalRequest.url();
                        okhttp3.Request.Builder requestBuilder = originalRequest.newBuilder()
                                .addHeader("Authorization", "Bearer " + apiKey);
                        
                        // 如果用户已经提供了版本路径，完整替换整个路径
                        // 原始endpoint是 /v1/chat/completions
                        // 如果用户baseUrl已经包含版本，我们使用用户baseUrl + /chat/completions
                        if (userBaseUrl.matches(".*/v\\d+/?$")) {
                            // 构建完整URL：用户提供的baseUrl + chat/completions
                            String fullUrl = userBaseUrl + "chat/completions";
                            okhttp3.HttpUrl newHttpUrl = okhttp3.HttpUrl.parse(fullUrl);
                            if (newHttpUrl != null) {
                                requestBuilder.url(newHttpUrl);
                                logger.debug("Using complete URL with user-provided version: {}", fullUrl);
                            }
                        }
                        // 如果用户没有提供版本，保持原有路径：baseUrl + v1/chat/completions
                        
                        return chain.proceed(requestBuilder.build());
                    })
                    .addInterceptor(logging)
                    .connectTimeout(timeout)
                    .readTimeout(timeout)
                    .writeTimeout(timeout)
                    .build();
            
            // Retrofit需要baseUrl，对于包含版本的情况，我们使用根路径作为baseUrl
            // 实际URL会在拦截器中被替换
            baseUrlForRetrofit = baseUrl;
            logger.info("LLM baseUrl configured as: {}, contains version path: {}", baseUrl, containsVersionPath);
            
            // 配置Jackson忽略未知属性，解决第三方API返回额外字段导致的反序列化错误
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            
            Retrofit retrofit = new Retrofit.Builder()
                    .baseUrl(baseUrlForRetrofit)
                    .client(client)
                    .addConverterFactory(JacksonConverterFactory.create(objectMapper))
                    .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                    .build();
            
            OpenAiApi openAiApi = retrofit.create(OpenAiApi.class);
            this.openAiService = new OpenAiService(openAiApi);
            
            if (apiBaseUrl != null && !apiBaseUrl.isEmpty()) {
                logger.info("LLM service initialized/reloaded with model: {} at {}", config.getModel(), apiBaseUrl);
            } else {
                logger.info("LLM service initialized/reloaded with model: {}", config.getModel());
            }
        } else {
            this.openAiService = null;
            logger.warn("LLM API key not configured, analysis service will be disabled");
        }
    }

    /**
     * 获取当前配置（从数据库）
     */
    public LlmConfig getCurrentConfig() {
        return llmConfigDAO.getCurrent().orElse(null);
    }

    /**
     * 检查是否配置了API Key
     */
    public boolean isConfigured() {
        return openAiService != null;
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

        // 获取当前配置并构建提示词
        LlmConfig config = getCurrentConfig();
        String prompt = buildPrompt(config, disk, startTime, endTime, capacityRecords, smartRecords);
        logger.info("=== LLM Analysis Prompt for disk {} ===\n{}", disk.getId(), prompt);
        logger.info("=== End of LLM Prompt, total {} characters ===", prompt.length());

        // 调用大模型
        String response = callLlm(prompt, config);
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
                .startTime(startTime.format(DATE_FORMATTER))
                .endTime(endTime.format(DATE_FORMATTER))
                .analysisContent(response)
                .healthScore(healthScore)
                .healthLevel(healthLevel)
                .recommendations(recommendations)
                .build();

        return analysisResultDAO.save(result);
    }

    /**
     * 分析硬盘健康趋势：获取四个时间点（当前、7天前、30天前、365天前）的最新数据进行对比分析
     */
    public AnalysisResult analyzeDiskTrend(Disk disk) {
        if (!isConfigured()) {
            throw new IllegalStateException("LLM service is not configured, please check your API key in config file");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime sevenDaysAgo = now.minusDays(7);
        LocalDateTime thirtyDaysAgo = now.minusDays(30);
        LocalDateTime threeHundredSixtyFiveDaysAgo = now.minusDays(365);

        // 获取四个时间点的容量数据（每个时间点取最新的一条）
        CapacityRecord capacityNow = capacityRecordDAO.findLatestBefore(disk.getId(), now);
        CapacityRecord capacity7d = capacityRecordDAO.findLatestBefore(disk.getId(), sevenDaysAgo);
        CapacityRecord capacity30d = capacityRecordDAO.findLatestBefore(disk.getId(), thirtyDaysAgo);
        CapacityRecord capacity365d = capacityRecordDAO.findLatestBefore(disk.getId(), threeHundredSixtyFiveDaysAgo);

        // 获取四个时间点的SMART数据（每个时间点取完整的属性集合）
        List<SmartRecord> smartNow = smartRecordDAO.findLatestAttributesBefore(disk.getId(), now);
        List<SmartRecord> smart7d = smartRecordDAO.findLatestAttributesBefore(disk.getId(), sevenDaysAgo);
        List<SmartRecord> smart30d = smartRecordDAO.findLatestAttributesBefore(disk.getId(), thirtyDaysAgo);
        List<SmartRecord> smart365d = smartRecordDAO.findLatestAttributesBefore(disk.getId(), threeHundredSixtyFiveDaysAgo);

        // 获取当前配置并构建提示词
        LlmConfig config = getCurrentConfig();
        String prompt = buildTrendPrompt(config, disk,
                capacityNow, capacity7d, capacity30d, capacity365d,
                smartNow, smart7d, smart30d, smart365d);
        logger.info("=== LLM Trend Analysis Prompt for disk {} ===\n{}", disk.getId(), prompt);
        logger.info("=== End of LLM Prompt, total {} characters ===", prompt.length());

        // 调用大模型
        String response = callLlm(prompt, config);
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
                .startTime(threeHundredSixtyFiveDaysAgo.format(DATE_FORMATTER))
                .endTime(now.format(DATE_FORMATTER))
                .analysisContent(response)
                .healthScore(healthScore)
                .healthLevel(healthLevel)
                .recommendations(recommendations)
                .build();

        return analysisResultDAO.save(result);
    }

    /**
     * 构建传统区间分析提示词
     */
    private String buildPrompt(LlmConfig config, Disk disk, LocalDateTime startTime, LocalDateTime endTime,
                                List<CapacityRecord> capacityRecords, List<SmartRecord> smartRecords) {
        String template = config.getPromptTemplate();
        if (template == null || template.isEmpty()) {
            template = getDefaultPromptTemplate();
        }

        Map<String, String> replacements = new HashMap<>();
        replacements.put("brand", disk.getBrand() != null ? disk.getBrand() : "Unknown");
        replacements.put("model", disk.getModel() != null ? disk.getModel() : "Unknown");
        replacements.put("serial", disk.getSerialNumber() != null ? disk.getSerialNumber() : "Unknown");
        replacements.put("serialNumber", disk.getSerialNumber() != null ? disk.getSerialNumber() : "Unknown");
        replacements.put("totalSize", String.format("%.2f", disk.getTotalCapacityGB()));
        replacements.put("total_capacity_gb", String.format("%.2f", disk.getTotalCapacityGB()));
        replacements.put("is_ssd", disk.isSSD() ? "SSD" : "HDD");
        replacements.put("startTime", startTime.format(DATE_FORMATTER));
        replacements.put("endTime", endTime.format(DATE_FORMATTER));
        replacements.put("capacityData", formatCapacityData(capacityRecords));
        replacements.put("smart_data", formatSmartData(smartRecords));
        replacements.put("smartData", formatSmartData(smartRecords));
        replacements.put("capacity_data", formatCapacityData(capacityRecords));
        replacements.put("capacityData", formatCapacityData(capacityRecords));

        // 替换占位符 - 同时兼容 {{xxx}} 和 {xxx} 两种格式（兼容新旧模板）
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            template = template.replace("{{" + entry.getKey() + "}}", entry.getValue());
            template = template.replace("{" + entry.getKey() + "}", entry.getValue());
        }

        return template;
    }

    /**
     * 构建趋势分析提示词（基于四个时间点对比）
     */
    private String buildTrendPrompt(LlmConfig config, Disk disk,
            CapacityRecord capacityNow, CapacityRecord capacity7d, CapacityRecord capacity30d, CapacityRecord capacity365d,
            List<SmartRecord> smartNow, List<SmartRecord> smart7d, List<SmartRecord> smart30d, List<SmartRecord> smart365d) {
        String template = config.getPromptTemplate();
        if (template == null || template.isEmpty()) {
            template = getDefaultTrendPromptTemplate();
        }

        Map<String, String> replacements = new HashMap<>();
        replacements.put("brand", disk.getBrand() != null ? disk.getBrand() : "Unknown");
        replacements.put("model", disk.getModel() != null ? disk.getModel() : "Unknown");
        replacements.put("serial", disk.getSerialNumber() != null ? disk.getSerialNumber() : "Unknown");
        replacements.put("serialNumber", disk.getSerialNumber() != null ? disk.getSerialNumber() : "Unknown");
        replacements.put("totalSize", String.format("%.2f", disk.getTotalCapacityGB()));
        replacements.put("total_capacity_gb", String.format("%.2f", disk.getTotalCapacityGB()));
        replacements.put("is_ssd", disk.isSSD() ? "SSD" : "HDD");

        // 格式化容量数据
        replacements.put("capacity_data", formatFourPointCapacityData(capacityNow, capacity7d, capacity30d, capacity365d));
        replacements.put("capacityData", replacements.get("capacity_data"));

        // 格式化SMART数据
        replacements.put("smart_data", formatFourPointSmartData(smartNow, smart7d, smart30d, smart365d));
        replacements.put("smartData", replacements.get("smart_data"));

        // 替换占位符 - 同时兼容 {{xxx}} 和 {xxx} 两种格式（兼容新旧模板）
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            template = template.replace("{{" + entry.getKey() + "}}", entry.getValue());
            template = template.replace("{" + entry.getKey() + "}", entry.getValue());
        }

        return template;
    }

    /**
     * 格式化四个时间点的容量数据
     */
    private String formatFourPointCapacityData(CapacityRecord now, CapacityRecord d7, CapacityRecord d30, CapacityRecord d365) {
        StringJoiner sj = new StringJoiner("\n");
        sj.add("时间点 | 记录时间 | 已用容量(GB) | 可用容量(GB) | 使用率(%)");
        sj.add("------|----------|--------------|--------------|---------");

        if (now != null) {
            double usedGB = (double) now.getUsedCapacity() / (1024 * 1024 * 1024);
            double availableGB = (double) now.getAvailableCapacity() / (1024 * 1024 * 1024);
            sj.add(String.format("当前 | %s | %.2f | %.2f | %.1f",
                    now.getRecordTime().format(DATE_FORMATTER), usedGB, availableGB, now.getUsagePercent()));
        } else {
            sj.add("当前 | 无数据 | - | - | -");
        }

        if (d7 != null) {
            double usedGB = (double) d7.getUsedCapacity() / (1024 * 1024 * 1024);
            double availableGB = (double) d7.getAvailableCapacity() / (1024 * 1024 * 1024);
            sj.add(String.format("7天前 | %s | %.2f | %.2f | %.1f",
                    d7.getRecordTime().format(DATE_FORMATTER), usedGB, availableGB, d7.getUsagePercent()));
        } else {
            sj.add("7天前 | 无数据 | - | - | -");
        }

        if (d30 != null) {
            double usedGB = (double) d30.getUsedCapacity() / (1024 * 1024 * 1024);
            double availableGB = (double) d30.getAvailableCapacity() / (1024 * 1024 * 1024);
            sj.add(String.format("30天前 | %s | %.2f | %.2f | %.1f",
                    d30.getRecordTime().format(DATE_FORMATTER), usedGB, availableGB, d30.getUsagePercent()));
        } else {
            sj.add("30天前 | 无数据 | - | - | -");
        }

        if (d365 != null) {
            double usedGB = (double) d365.getUsedCapacity() / (1024 * 1024 * 1024);
            double availableGB = (double) d365.getAvailableCapacity() / (1024 * 1024 * 1024);
            sj.add(String.format("365天前 | %s | %.2f | %.2f | %.1f",
                    d365.getRecordTime().format(DATE_FORMATTER), usedGB, availableGB, d365.getUsagePercent()));
        } else {
            sj.add("365天前 | 无数据 | - | - | -");
        }

        return sj.toString();
    }

    /**
     * 格式化四个时间点的SMART数据
     */
    private String formatFourPointSmartData(List<SmartRecord> now, List<SmartRecord> d7, List<SmartRecord> d30, List<SmartRecord> d365) {
        if (now.isEmpty() && d7.isEmpty() && d30.isEmpty() && d365.isEmpty()) {
            return "No SMART data available";
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
                "Total_LBAs_Written",
                "Data_Units_Written",
                "Data_Units_Read",
                "Percentage_Used",
                "Host_Writes",
                "Host_Reads",
                "Host_Write_Commands",
                "Host_Read_Commands",
                "Available_Spare",
                "Percent_Life_Remaining",
                "Endurance_Remaining"
        );

        // 收集所有出现过的关键属性名称
        java.util.Set<String> allAttrNames = new java.util.HashSet<>();
        for (SmartRecord r : now) {
            String name = r.getAttributeName();
            if (importantAttributes.stream().anyMatch(imp -> name.contains(imp) || imp.contains(name))) {
                allAttrNames.add(name);
            }
        }
        for (SmartRecord r : d7) {
            String name = r.getAttributeName();
            if (importantAttributes.stream().anyMatch(imp -> name.contains(imp) || imp.contains(name))) {
                allAttrNames.add(name);
            }
        }
        for (SmartRecord r : d30) {
            String name = r.getAttributeName();
            if (importantAttributes.stream().anyMatch(imp -> name.contains(imp) || imp.contains(name))) {
                allAttrNames.add(name);
            }
        }
        for (SmartRecord r : d365) {
            String name = r.getAttributeName();
            if (importantAttributes.stream().anyMatch(imp -> name.contains(imp) || imp.contains(name))) {
                allAttrNames.add(name);
            }
        }

        if (allAttrNames.isEmpty()) {
            return "No important SMART attributes found";
        }

        StringJoiner sj = new StringJoiner("\n");
        sj.add("以下是四个时间点的SMART关键属性数据，用于对比分析健康变化趋势：");
        sj.add("");
        sj.add("属性名称 | 365天前 | 30天前 | 7天前 | 当前 | 阈值");
        sj.add("--------|---------|-------|------|------|------");

        // 查找每个属性在不同时间点的值
        for (String attrName : allAttrNames) {
            SmartRecord r365 = findAttributeByName(d365, attrName);
            SmartRecord r30 = findAttributeByName(d30, attrName);
            SmartRecord r7 = findAttributeByName(d7, attrName);
            SmartRecord rNow = findAttributeByName(now, attrName);

            Long v365 = r365 != null ? r365.getRawValue() : null;
            Long v30 = r30 != null ? r30.getRawValue() : null;
            Long v7 = r7 != null ? r7.getRawValue() : null;
            Long vNow = rNow != null ? rNow.getRawValue() : null;
            Integer threshold = rNow != null ? rNow.getThreshold() : null;
            if (threshold == null && r30 != null) threshold = r30.getThreshold();
            if (threshold == null && r7 != null) threshold = r7.getThreshold();
            if (threshold == null && r365 != null) threshold = r365.getThreshold();

            String s365 = v365 != null ? String.valueOf(v365) : "-";
            String s30 = v30 != null ? String.valueOf(v30) : "-";
            String s7 = v7 != null ? String.valueOf(v7) : "-";
            String sNow = vNow != null ? String.valueOf(vNow) : "-";
            String sThreshold = threshold != null ? String.valueOf(threshold) : "-";

            sj.add(String.format("%s | %s | %s | %s | %s | %s",
                    attrName, s365, s30, s7, sNow, sThreshold));
        }

        sj.add("");
        sj.add("说明：");
        sj.add("- Data_Units_Read / Data_Units_Written：每单位 = 1000 个扇区 = 512KB，可直接计算增量");
        sj.add("- Reallocated_Sector_Ct / Current_Pending_Sector / Offline_Uncorrectable：数值越高表示坏道越多");
        sj.add("- Percentage_Used：已用寿命百分比，SSD专用，数值越大寿命剩余越少");
        sj.add("- 通过对比四个时间点计算增量，可以得到每天/每周/每月的平均消耗速度");

        // 添加温度统计（如果有数据）
        Integer tNow = null, t7 = null, t30 = null, t365 = null;
        for (SmartRecord r : now) { if (r.getAttributeName().contains("Temperature") && r.getTemperature() != null) tNow = r.getTemperature(); }
        for (SmartRecord r : d7) { if (r.getAttributeName().contains("Temperature") && r.getTemperature() != null) t7 = r.getTemperature(); }
        for (SmartRecord r : d30) { if (r.getAttributeName().contains("Temperature") && r.getTemperature() != null) t30 = r.getTemperature(); }
        for (SmartRecord r : d365) { if (r.getAttributeName().contains("Temperature") && r.getTemperature() != null) t365 = r.getTemperature(); }
        if (tNow != null || t7 != null || t30 != null || t365 != null) {
            sj.add("\n温度数据：");
            sj.add("时间点 | 温度(°C)");
            sj.add("------|--------");
            if (t365 != null) sj.add(String.format("365天前 | %d", t365)); else sj.add("365天前 | -");
            if (t30 != null) sj.add(String.format("30天前 | %d", t30)); else sj.add("30天前 | -");
            if (t7 != null) sj.add(String.format("7天前 | %d", t7)); else sj.add("7天前 | -");
            if (tNow != null) sj.add(String.format("当前 | %d", tNow)); else sj.add("当前 | -");
        }

        return sj.toString();
    }

    /**
     * 根据属性名称查找记录
     */
    private SmartRecord findAttributeByName(List<SmartRecord> records, String attrName) {
        if (records == null || records.isEmpty()) return null;
        for (SmartRecord r : records) {
            if (r.getAttributeName().equals(attrName)) {
                return r;
            }
        }
        return null;
    }

    /**
     * 获取默认趋势分析提示词模板
     */
    public static String getDefaultTrendPromptTemplate() {
        return "你是一位专业的存储硬件健康分析师，请根据以下四个时间点的硬盘监控数据对比分析硬盘的健康状况：\n\n" +
                "硬盘基本信息：\n" +
                "品牌：{{brand}}\n" +
                "型号：{{model}}\n" +
                "序列号：{{serial}}\n" +
                "总容量：{{totalSize}} GB\n" +
                "类型：{{is_ssd}}\n\n" +
                "容量使用数据（四个时间点对比）：\n" +
                "{{capacityData}}\n\n" +
                "SMART关键属性数据（四个时间点对比）：\n" +
                "{{smartData}}\n\n" +
                "请完成以下分析：\n" +
                "1. 通过对比365天前、30天前、7天前和当前的容量使用数据，计算容量消耗速度：\n" +
                "   - 每天平均增加多少已用容量\n" +
                "   - 按照当前速度，预计多久会占满整个硬盘\n" +
                "2. 通过对比SMART属性计算消耗速度：\n" +
                "   - 对于SSD：计算每天平均写入量增长(PBW)，百分比寿命消耗速度\n" +
                "   - 对于HDD：计算坏块数量增长趋势，是否在加速增长\n" +
                "   - 通电时间累计速度是否正常\n" +
                "   - 温度是否有明显升高\n" +
                "3. 判断硬盘健康劣化速度：\n" +
                "   - 是否在正常范围内\n" +
                "   - 是否出现快速劣化迹象\n" +
                "4. 综合评价硬盘当前健康状况\n" +
                "5. 给出明确建议：是否需要备份数据，是否需要提前更换硬盘\n" +
                "6. 最后给出一个0-100的健康评分\n\n" +
                "请用中文给出清晰专业的分析结论，计算过程简单说明即可。";
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
     * 格式化SMART数据，提供昨天、上周、上个月三份历史数据，让大模型分析趋势
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
                "Total_LBAs_Written",
                "Data_Units_Written",
                "Data_Units_Read",
                "Percentage_Used",
                "Host_Writes",
                "Host_Reads",
                "Host_Write_Commands",
                "Host_Read_Commands"
        );

        // 先按属性名称分组，每个属性下有多个时间点的记录
        Map<String, List<SmartRecord>> grouped = new HashMap<>();
        for (SmartRecord record : records) {
            String name = record.getAttributeName();
            boolean isImportant = importantAttributes.stream()
                    .anyMatch(imp -> name.contains(imp) || imp.contains(name));
            if (isImportant) {
                grouped.computeIfAbsent(name, k -> new ArrayList<>()).add(record);
            }
        }

        if (grouped.isEmpty()) {
            return "No important SMART attributes found";
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime yesterday = now.minusDays(1);
        LocalDateTime oneWeekAgo = now.minusWeeks(1);
        LocalDateTime oneMonthAgo = now.minusMonths(1);

        StringJoiner sj = new StringJoiner("\n");
        sj.add("以下是不同时间点的SMART关键属性数据，用于分析健康趋势：");
        sj.add("");
        sj.add("属性名称 | 一个月前 | 一周前 | 昨天 | 当前 | 阈值");
        sj.add("--------|---------|-------|------|------|------");

        for (Map.Entry<String, List<SmartRecord>> entry : grouped.entrySet()) {
            String attrName = entry.getKey();
            List<SmartRecord> attrRecords = entry.getValue();
            // 按时间排序
            attrRecords.sort((a, b) -> a.getRecordTime().compareTo(b.getRecordTime()));

            // 找每个时间点最接近的记录
            SmartRecord monthAgoRecord = findClosestRecord(attrRecords, oneMonthAgo);
            SmartRecord weekAgoRecord = findClosestRecord(attrRecords, oneWeekAgo);
            SmartRecord yesterdayRecord = findClosestRecord(attrRecords, yesterday);
            SmartRecord currentRecord = attrRecords.get(attrRecords.size() - 1); // 最新

            Long monthAgoRaw = monthAgoRecord != null ? monthAgoRecord.getRawValue() : null;
            Long weekAgoRaw = weekAgoRecord != null ? weekAgoRecord.getRawValue() : null;
            Long yesterdayRaw = yesterdayRecord != null ? yesterdayRecord.getRawValue() : null;
            Long currentRaw = currentRecord != null ? currentRecord.getRawValue() : null;
            Integer threshold = currentRecord != null ? currentRecord.getThreshold() : null;

            String monthStr = monthAgoRaw != null ? String.valueOf(monthAgoRaw) : "-";
            String weekStr = weekAgoRaw != null ? String.valueOf(weekAgoRaw) : "-";
            String yesterdayStr = yesterdayRaw != null ? String.valueOf(yesterdayRaw) : "-";
            String currentStr = currentRaw != null ? String.valueOf(currentRaw) : "-";
            String thresholdStr = threshold != null ? String.valueOf(threshold) : "-";

            sj.add(String.format("%s | %s | %s | %s | %s | %s",
                    attrName, monthStr, weekStr, yesterdayStr, currentStr, thresholdStr));
        }

        sj.add("");
        sj.add("说明：");
        sj.add("- Data_Units_Read / Data_Units_Written：每单位 = 1000 个扇区 = 512KB，可直接累积计算总读写量");
        sj.add("- Reallocated_Sector_Ct / Current_Pending_Sector / Offline_Uncorrectable：数值越高表示坏道越多");
        sj.add("- 对于累积计数器（Power_On_Hours、Data_Units_Written、Total_LBAs_Written等），增量即为对应时间范围内的变化");

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
            int minTemp = temperatureRecords.stream()
                    .mapToInt(SmartRecord::getTemperature)
                    .min()
                    .orElse(0);
            sj.add("\n温度统计: 平均 " + avgTemp + "°C, 最低 " + minTemp + "°C, 最高 " + maxTemp + "°C");
        }

        return sj.toString();
    }

    /**
     * 辅助类：存储单个属性在不同时间点的数据
     */
    private static class SmartAttributePoint {
        String type; // monthAgo, weekAgo, yesterday, current
        long rawValue;
        int threshold;

        public SmartAttributePoint(String type, long rawValue, int threshold) {
            this.type = type;
            this.rawValue = rawValue;
            this.threshold = threshold;
        }

        public String getType() {
            return type;
        }

        public long getRawValue() {
            return rawValue;
        }

        public int getThreshold() {
            return threshold;
        }
    }

    /**
     * 找到最接近目标时间的记录
     */
    private SmartRecord findClosestRecord(List<SmartRecord> sortedRecords, LocalDateTime targetTime) {
        if (sortedRecords.isEmpty()) {
            return null;
        }
        // 记录已经按时间排序
        SmartRecord closest = null;
        long minDiff = Long.MAX_VALUE;
        for (SmartRecord record : sortedRecords) {
            LocalDateTime recordTime = record.getRecordTime();
            long diff = Math.abs(recordTime.toEpochSecond(java.time.ZoneOffset.UTC) - targetTime.toEpochSecond(java.time.ZoneOffset.UTC));
            if (diff < minDiff) {
                minDiff = diff;
                closest = record;
            }
        }
        // 如果最接近的记录相差超过2天，说明没有这个时间点的数据，返回null
        if (minDiff > 2 * 24 * 60 * 60) {
            return null;
        }
        return closest;
    }

    private Long getRawForType(List<SmartAttributePoint> points, String type) {
        for (SmartAttributePoint p : points) {
            if (type.equals(p.getType())) {
                return p.getRawValue();
            }
        }
        return null;
    }

    private Integer getThreshold(List<SmartAttributePoint> points) {
        for (SmartAttributePoint p : points) {
            if (p.getThreshold() > 0) {
                return p.getThreshold();
            }
        }
        return null;
    }

    /**
     * 调用大模型API
     */
    private String callLlm(String prompt, LlmConfig config) {
        try {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new ChatMessage(ChatMessageRole.USER.value(), prompt));

            // 使用配置中的参数，没有设置则使用默认值
            double temperature = config.getTemperature() != null ? config.getTemperature() : 0.7;
            int maxTokens = config.getMaxTokens() != null ? config.getMaxTokens() : 1000;

            ChatCompletionRequest request = ChatCompletionRequest.builder()
                    .model(config.getModel())
                    .messages(messages)
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .build();

            String response = openAiService.createChatCompletion(request)
                    .getChoices()
                    .get(0)
                    .getMessage()
                    .getContent();

            logger.info("=== LLM Response received, {} characters ===\n{}", response.length(), response);
            logger.info("=== End of LLM Response ===");
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
    public static String getDefaultPromptTemplate() {
        return "你是一位专业的存储硬件健康分析师，请根据以下硬盘监控数据进行分析：\n\n" +
                "硬盘基本信息：\n" +
                "品牌：{{brand}}\n" +
                "型号：{{model}}\n" +
                "序列号：{{serial}}\n" +
                "总容量：{{totalSize}} GB\n\n" +
                "时间区间：{{startTime}} 到 {{endTime}}\n\n" +
                "容量变化数据：\n" +
                "{{capacityData}}\n\n" +
                "SMART属性历史数据（按不同时间点提供：一个月前、一周前、昨天、当前）：\n" +
                "{{smartData}}\n\n" +
                "请分析：\n" +
                "1. 容量使用趋势是怎样的，是否存在异常增长\n" +
                "2. 通过对比一个月前、一周前、昨天、当前的数据，分析各指标的变化趋势：\n" +
                "   - 坏块数量变化（Reallocated_Sector_Ct、Current_Pending_Sector、Offline_Uncorrectable）\n" +
                "   - 累积读写量增长计算（Data_Units_Read、Data_Units_Written）\n" +
                "   - 通电时间增长（Power_On_Hours）\n" +
                "   - 温度变化情况\n" +
                "   - 寿命百分比变化（Percentage_Used）\n" +
                "3. 根据趋势判断硬盘健康状况变化速度，是否在快速劣化\n" +
                "4. SMART各项指标是否存在异常值\n" +
                "5. 给出明确的建议：是否需要备份数据，是否需要更换硬盘等\n" +
                "6. 最后给出一个0-100的健康评分\n\n" +
                "请用中文给出清晰专业的分析结论和建议。";
    }

    /**
     * 保存或更新配置
     */
    public void saveConfig(LlmConfig config) {
        LlmConfig current = getCurrentConfig();
        if (current != null) {
            config.setId(current.getId());
            llmConfigDAO.update(config);
        } else {
            llmConfigDAO.save(config);
        }
        // 重新加载OpenAi客户端
        reloadOpenAiClient();
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

    /**
     * 趋势分析（便捷方法，用于API调用）
     */
    public String analyzeDiskTrendAPI(Disk disk) {
        AnalysisResult result = analyzeDiskTrend(disk);
        return result.getAnalysisContent();
    }
}
