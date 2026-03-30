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
        logger.debug("Built analysis prompt for disk {}: {} characters", disk.getId(), prompt.length());

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
}
