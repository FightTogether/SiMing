package cn.why360.siming.entity;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 硬盘实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Disk {
    private Long id;

    /**
     * 设备路径（如 /dev/sda）
     */
    private String devicePath;

    /**
     * 品牌
     */
    private String brand;

    /**
     * 型号
     */
    private String model;

    /**
     * 序列号
     */
    private String serialNumber;

    /**
     * 总容量（字节）
     */
    private long totalCapacity;

    /**
     * 是否SSD
     */
    private boolean isSSD;

    /**
     * 是否被监控
     */
    private boolean monitored;

    /**
     * 监控cron表达式
     */
    private String monitorCron;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 获取格式化容量（GB）
     */
    public double getTotalCapacityGB() {
        return (double) totalCapacity / (1024 * 1024 * 1024);
    }
}