package cn.why360.siming.entity;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * SMART监控记录实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmartRecord {
    private Long id;

    /**
     * 关联硬盘ID
     */
    private Long diskId;

    /**
     * 属性ID
     */
    private Integer attributeId;

    /**
     * 属性名称
     */
    private String attributeName;

    /**
     * 当前值
     */
    private Integer currentValue;

    /**
     * 最差值
     */
    private Integer worstValue;

    /**
     * 阈值
     */
    private Integer threshold;

    /**
     * 原始值
     */
    private Long rawValue;

    /**
     * 是否已失败
     */
    private boolean failed;

    /**
     * 温度（摄氏度）- 专门用于温度属性
     */
    private Integer temperature;

    /**
     * 记录时间
     */
    private LocalDateTime recordTime;
}