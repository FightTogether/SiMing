package cn.why360.siming.entity;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 容量监控记录实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapacityRecord {
    private Long id;

    /**
     * 采集批次ID - 同一次上报所有记录使用同一个reportId
     */
    private Long reportId;

    /**
     * 关联硬盘ID
     */
    private Long diskId;

    /**
     * 文件系统路径（/dev/sda1等）
     */
    private String filesystem;

    /**
     * 已用容量（字节）
     */
    private long usedCapacity;

    /**
     * 可用容量（字节）
     */
    private long availableCapacity;

    /**
     * 使用率百分比
     */
    private double usagePercent;

    /**
     * 挂载点
     */
    private String mountPoint;

    /**
     * 记录时间
     */
    private LocalDateTime recordTime;
}
