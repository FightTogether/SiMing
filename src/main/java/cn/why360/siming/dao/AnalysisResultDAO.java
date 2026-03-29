package cn.why360.siming.dao;

import cn.why360.siming.database.DatabaseManager;
import cn.why360.siming.entity.AnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 分析结果数据访问对象
 */
public class AnalysisResultDAO {
    private static final Logger logger = LoggerFactory.getLogger(AnalysisResultDAO.class);
    private final DatabaseManager databaseManager;

    public AnalysisResultDAO(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * 保存分析结果
     */
    public AnalysisResult save(AnalysisResult result) {
        String sql = "INSERT INTO analysis_results (disk_id, start_time, end_time, analysis_content, health_score, health_level, recommendations) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "RETURNING id";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, result.getDiskId());
            stmt.setTimestamp(2, Timestamp.valueOf(result.getStartTime()));
            stmt.setTimestamp(3, Timestamp.valueOf(result.getEndTime()));
            stmt.setString(4, result.getAnalysisContent());
            stmt.setInt(5, result.getHealthScore() != null ? result.getHealthScore() : -1);
            stmt.setString(6, result.getHealthLevel());
            stmt.setString(7, result.getRecommendations());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    result.setId(rs.getLong(1));
                }
            }

            logger.info("Saved analysis result for disk id {}, health score: {}", result.getDiskId(), result.getHealthScore());
        } catch (SQLException e) {
            logger.error("Failed to save analysis result for disk id {}", result.getDiskId(), e);
            throw new RuntimeException("Failed to save analysis result", e);
        }

        return result;
    }

    /**
     * 获取所有分析结果
     */
    public List<AnalysisResult> findAll() {
        List<AnalysisResult> results = new ArrayList<>();
        String sql = "SELECT * FROM analysis_results " +
                "ORDER BY create_time DESC";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                results.add(mapResultSetToResult(rs));
            }
        } catch (SQLException e) {
            logger.error("Failed to query all analysis results", e);
        }

        return results;
    }

    /**
     * 获取指定硬盘的所有分析结果
     */
    public List<AnalysisResult> findByDiskId(Long diskId) {
        List<AnalysisResult> results = new ArrayList<>();
        String sql = "SELECT * FROM analysis_results " +
                "WHERE disk_id = ? " +
                "ORDER BY create_time DESC";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, diskId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(mapResultSetToResult(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to query analysis results for disk id {}", diskId, e);
        }

        return results;
    }

    /**
     * 根据ID获取分析结果
     */
    public java.util.Optional<AnalysisResult> findById(Long id) {
        String sql = "SELECT * FROM analysis_results " +
                "WHERE id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, id);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return java.util.Optional.of(mapResultSetToResult(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to query analysis result by id {}", id, e);
        }

        return java.util.Optional.empty();
    }

    /**
     * ResultSet映射到AnalysisResult对象
     */
    private AnalysisResult mapResultSetToResult(ResultSet rs) throws SQLException {
        Integer score = rs.getInt("health_score");
        if (score == -1) {
            score = null;
        }

        return AnalysisResult.builder()
                .id(rs.getLong("id"))
                .diskId(rs.getLong("disk_id"))
                .startTime(rs.getTimestamp("start_time").toLocalDateTime())
                .endTime(rs.getTimestamp("end_time").toLocalDateTime())
                .analysisContent(rs.getString("analysis_content"))
                .healthScore(score)
                .healthLevel(rs.getString("health_level"))
                .recommendations(rs.getString("recommendations"))
                .createTime(rs.getTimestamp("create_time").toLocalDateTime())
                .build();
    }
}
