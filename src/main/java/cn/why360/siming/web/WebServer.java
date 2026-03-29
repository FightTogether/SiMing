package cn.why360.siming.web;

import cn.why360.siming.config.SimingConfig;
import cn.why360.siming.dao.*;
import cn.why360.siming.entity.*;
import cn.why360.siming.service.LlmAnalysisService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Web服务器 - 提供Web界面查看监控数据和分析结果
 */
public class WebServer {
    private static final Logger logger = LoggerFactory.getLogger(WebServer.class);

    private final Server server;
    private final SimingConfig config;
    private final DiskDAO diskDAO;
    private final CapacityRecordDAO capacityDAO;
    private final SmartRecordDAO smartDAO;
    private final AnalysisResultDAO analysisDAO;
    private final LlmAnalysisService llmService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String indexHtml;

    public WebServer(SimingConfig config, DiskDAO diskDAO, CapacityRecordDAO capacityDAO,
                     SmartRecordDAO smartDAO, AnalysisResultDAO analysisDAO, LlmAnalysisService llmService) throws IOException {
        this.config = config;
        this.diskDAO = diskDAO;
        this.capacityDAO = capacityDAO;
        this.smartDAO = smartDAO;
        this.analysisDAO = analysisDAO;
        this.llmService = llmService;
        this.server = new Server(config.getWeb().getPort());
        this.indexHtml = loadIndexHtml();
    }

    private String loadIndexHtml() throws IOException {
        try (InputStreamReader isr = new InputStreamReader(
                getClass().getResourceAsStream("/static/index.html"), StandardCharsets.UTF_8);
             BufferedReader reader = new BufferedReader(isr)) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    public void start() throws Exception {
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        // 主页 - 返回静态HTML
        context.addServlet(new ServletHolder(new IndexServlet()), "/");
        // API - 获取硬盘列表
        context.addServlet(new ServletHolder(new DisksServlet()), "/api/disks");
        // API - 获取硬盘详情和历史数据
        context.addServlet(new ServletHolder(new DiskDetailServlet()), "/api/disk/*");
        // API - 获取分析历史
        context.addServlet(new ServletHolder(new AnalysesServlet()), "/api/analyses");
        // API - 获取单个分析结果
        context.addServlet(new ServletHolder(new AnalysisDetailServlet()), "/api/analyses/*");
        // API - 触发分析
        context.addServlet(new ServletHolder(new TriggerAnalysisServlet()), "/api/analyze");
        // API - 获取当前配置
        context.addServlet(new ServletHolder(new ConfigServlet()), "/api/config");
        // API - 更新配置
        context.addServlet(new ServletHolder(new UpdateConfigServlet()), "/api/config/update");

        server.setHandler(context);
        server.start();
        logger.info("Web server started on http://localhost:{}", config.getWeb().getPort());
    }

    public void stop() throws Exception {
        server.stop();
    }

    /**
     * 主页 - 返回静态HTML
     */
    private class IndexServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            resp.setContentType("text/html;charset=UTF-8");
            PrintWriter out = resp.getWriter();
            out.write(indexHtml);
        }
    }

    /**
     * 获取硬盘列表API
     */
    private class DisksServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            resp.setContentType("application/json;charset=UTF-8");
            List<Disk> disks = diskDAO.findAll();
            objectMapper.writeValue(resp.getWriter(), disks);
        }
    }

    /**
     * 获取硬盘详情和历史数据API
     */
    private class DiskDetailServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            String path = req.getPathInfo();
            String[] parts = path.split("/");
            if (parts.length < 2) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid disk id");
                return;
            }
            long diskId = Long.parseLong(parts[1]);
            resp.setContentType("application/json;charset=UTF-8");
            Optional<Disk> diskOpt = diskDAO.findById(diskId);
            if (diskOpt.isEmpty()) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Disk not found");
                return;
            }
            List<CapacityRecord> capacity = capacityDAO.findByDiskId(diskId);
            List<SmartRecord> smart = smartDAO.findByDiskId(diskId);
            Map<String, Object> result = new HashMap<>();
            result.put("disk", diskOpt.get());
            result.put("capacity", capacity);
            result.put("smart", smart);
            objectMapper.writeValue(resp.getWriter(), result);
        }
    }

    /**
     * 获取分析历史列表API
     */
    private class AnalysesServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            resp.setContentType("application/json;charset=UTF-8");
            List<AnalysisResult> analyses = analysisDAO.findAll();
            objectMapper.writeValue(resp.getWriter(), analyses);
        }
    }

    /**
     * 获取单个分析结果API
     */
    private class AnalysisDetailServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            String path = req.getPathInfo();
            String[] parts = path.split("/");
            if (parts.length < 2) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid analysis id");
                return;
            }
            long analysisId = Long.parseLong(parts[1]);
            resp.setContentType("application/json;charset=UTF-8");
            Optional<AnalysisResult> analysisOpt = analysisDAO.findById(analysisId);
            if (analysisOpt.isEmpty()) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Analysis not found");
                return;
            }
            objectMapper.writeValue(resp.getWriter(), analysisOpt.get());
        }
    }

    /**
     * 触发AI分析API
     */
    private class TriggerAnalysisServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            resp.setContentType("application/json;charset=UTF-8");
            Map<String, Object> request = objectMapper.readValue(req.getInputStream(), Map.class);
            long diskId = ((Number) request.get("diskId")).longValue();
            int days = ((Number) request.get("days")).intValue();

            try {
                Optional<Disk> diskOpt = diskDAO.findById(diskId);
                if (diskOpt.isEmpty()) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("success", false);
                    result.put("error", "Disk not found");
                    objectMapper.writeValue(resp.getWriter(), result);
                    return;
                }

                String analysisResult = llmService.analyzeDiskHistory(diskOpt.get(), days);
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("result", analysisResult);
                objectMapper.writeValue(resp.getWriter(), result);
            } catch (Exception e) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("error", e.getMessage());
                objectMapper.writeValue(resp.getWriter(), result);
            }
        }
    }

    /**
     * 获取当前大模型配置API
     */
    private class ConfigServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            resp.setContentType("application/json;charset=UTF-8");
            SimingConfig.LlmConfig llmConfig = config.getLlm();
            objectMapper.writeValue(resp.getWriter(), llmConfig);
        }
    }

    /**
     * 更新配置API
     */
    private class UpdateConfigServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            resp.setContentType("application/json;charset=UTF-8");
            try {
                Map<String, Object> updates = objectMapper.readValue(req.getInputStream(), Map.class);
                SimingConfig.LlmConfig llmConfig = config.getLlm();

                if (updates.containsKey("apiBaseUrl")) {
                    llmConfig.setApiBaseUrl((String) updates.get("apiBaseUrl"));
                }
                if (updates.containsKey("apiKey")) {
                    llmConfig.setApiKey((String) updates.get("apiKey"));
                }
                if (updates.containsKey("model")) {
                    llmConfig.setModel((String) updates.get("model"));
                }
                if (updates.containsKey("timeout")) {
                    llmConfig.setTimeout((Integer) updates.get("timeout"));
                }
                if (updates.containsKey("promptTemplate")) {
                    llmConfig.setPromptTemplate((String) updates.get("promptTemplate"));
                }

                // 重新初始化LLM服务
                llmService.reloadConfig(config);

                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                objectMapper.writeValue(resp.getWriter(), result);
            } catch (Exception e) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("error", e.getMessage());
                objectMapper.writeValue(resp.getWriter(), result);
            }
        }
    }
}