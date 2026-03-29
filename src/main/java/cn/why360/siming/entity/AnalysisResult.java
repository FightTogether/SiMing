package cn.why360.siming.entity;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 大模型分析结果实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisResult {
    private Long id;

    /**
     * 关联硬盘ID
     */
    private Long diskId;

    /**
     * 分析开始时间
     */
    private LocalDateTime startTime;

    /**
     * 分析结束时间
     */
    private LocalDateTime endTime;

    /**
     * 大模型生成的分析内容
     */
    private String analysisContent;

    /**
     * 健康评分（0-100）
     */
    private Integer healthScore;

    /**
     * 健康等级：GOOD/WARNING/CRITICAL
     */
    private String healthLevel;

    /**
     * 建议
     */
    private String recommendations;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}