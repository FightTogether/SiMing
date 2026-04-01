package cn.why360.siming.dao;

import cn.why360.siming.entity.CapacityRecord;
import cn.why360.siming.mapper.CapacityRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 容量监控记录数据访问对象 - MyBatis实现
 */
@Repository
public class CapacityRecordDAO {
    private static final Logger logger = LoggerFactory.getLogger(CapacityRecordDAO.class);
    private final CapacityRecordMapper capacityRecordMapper;

    public CapacityRecordDAO(CapacityRecordMapper capacityRecordMapper) {
        this.capacityRecordMapper = capacityRecordMapper;
    }

    /**
     * 插入容量记录
     */
    public void insert(CapacityRecord record) {
        capacityRecordMapper.insert(record);
        logger.debug("Inserted capacity record for disk id {}", record.getDiskId());
    }

    /**
     * 获取指定硬盘在时间区间内的容量记录
     */
    public List<CapacityRecord> findByDiskIdAndTimeRange(Long diskId, LocalDateTime startTime, LocalDateTime endTime) {
        // MyBatis不直接支持复杂的时间范围查询，这里保留原有逻辑但基于MyBatis基础扩展
        // 由于SQLite对JDBC时间类型支持良好，我们直接获取后过滤
        List<CapacityRecord> allRecords = capacityRecordMapper.findByDiskId(diskId);
        return allRecords.stream()
                .filter(record -> !record.getRecordTime().isBefore(startTime) && !record.getRecordTime().isAfter(endTime))
                .sorted((a, b) -> a.getRecordTime().compareTo(b.getRecordTime()))
                .collect(Collectors.toList());
    }

    /**
     * 获取指定硬盘最近N条记录
     */
    public List<CapacityRecord> findRecentByDiskId(Long diskId, int limit) {
        return capacityRecordMapper.findRecentByDiskId(diskId, limit);
    }

    /**
     * 获取指定硬盘在截止时间之前最新的一条容量记录
     * 用于四个时间点对比分析
     */
    public CapacityRecord findLatestBefore(Long diskId, LocalDateTime beforeTime) {
        List<CapacityRecord> records = capacityRecordMapper.findByDiskId(diskId);
        return records.stream()
                .filter(record -> !record.getRecordTime().isAfter(beforeTime))
                .sorted((a, b) -> b.getRecordTime().compareTo(a.getRecordTime()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取指定硬盘的所有容量记录
     */
    public List<CapacityRecord> findByDiskId(Long diskId) {
        return capacityRecordMapper.findByDiskId(diskId);
    }
}