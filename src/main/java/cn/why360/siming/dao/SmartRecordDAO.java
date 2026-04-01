package cn.why360.siming.dao;

import cn.why360.siming.entity.SmartRecord;
import cn.why360.siming.mapper.SmartRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * SMART监控记录数据访问对象 - MyBatis实现
 */
@Repository
public class SmartRecordDAO {
    private static final Logger logger = LoggerFactory.getLogger(SmartRecordDAO.class);
    private final SmartRecordMapper smartRecordMapper;

    public SmartRecordDAO(SmartRecordMapper smartRecordMapper) {
        this.smartRecordMapper = smartRecordMapper;
    }

    /**
     * 插入SMART记录
     */
    public void insert(SmartRecord record) {
        smartRecordMapper.insert(record);
        logger.debug("Inserted SMART record for disk id {}, attribute {}", record.getDiskId(), record.getAttributeName());
    }

    /**
     * 批量插入SMART记录
     */
    public void batchInsert(Long diskId, List<SmartRecord> records) {
        // 设置每个记录的diskId（保证一致性）
        records.forEach(record -> record.setDiskId(diskId));
        int inserted = smartRecordMapper.batchInsert(records);
        logger.debug("Batch inserted {} SMART records for disk id {}", inserted, diskId);
    }

    /**
     * 获取指定硬盘在时间区间内的SMART记录（按属性分组，取每个属性最早和最新记录）
     */
    public List<SmartRecord> findByDiskIdAndTimeRange(Long diskId, LocalDateTime startTime, LocalDateTime endTime) {
        List<SmartRecord> allRecords = smartRecordMapper.findByDiskId(diskId);
        return allRecords.stream()
                .filter(record -> !record.getRecordTime().isBefore(startTime) && !record.getRecordTime().isAfter(endTime))
                .sorted(Comparator.comparing(SmartRecord::getAttributeId).thenComparing(SmartRecord::getRecordTime))
                .toList();
    }

    /**
     * 获取指定硬盘最新的SMART记录
     */
    public List<SmartRecord> findLatestByDiskId(Long diskId) {
        List<SmartRecord> allRecords = smartRecordMapper.findByDiskId(diskId);
        Map<Integer, SmartRecord> latestMap = new HashMap<>();
        for (SmartRecord record : allRecords) {
            int attrId = record.getAttributeId();
            if (!latestMap.containsKey(attrId) ||
                    record.getRecordTime().isAfter(latestMap.get(attrId).getRecordTime())) {
                latestMap.put(attrId, record);
            }
        }
        return latestMap.values().stream()
                .sorted(Comparator.comparing(SmartRecord::getAttributeId))
                .toList();
    }

    /**
     * 获取指定硬盘在截止时间之前的最新SMART属性集合（每个属性取最新一条）
     * 用于四个时间点对比分析
     */
    public List<SmartRecord> findLatestAttributesBefore(Long diskId, LocalDateTime beforeTime) {
        List<SmartRecord> allRecords = smartRecordMapper.findByDiskId(diskId);
        Map<Integer, SmartRecord> latestMap = new HashMap<>();
        for (SmartRecord record : allRecords) {
            if (!record.getRecordTime().isAfter(beforeTime)) {
                int attrId = record.getAttributeId();
                if (!latestMap.containsKey(attrId) ||
                        record.getRecordTime().isAfter(latestMap.get(attrId).getRecordTime())) {
                    latestMap.put(attrId, record);
                }
            }
        }
        return latestMap.values().stream()
                .sorted(Comparator.comparing(SmartRecord::getAttributeId))
                .toList();
    }

    /**
     * 获取指定硬盘的所有SMART记录
     */
    public List<SmartRecord> findByDiskId(Long diskId) {
        return smartRecordMapper.findByDiskId(diskId);
    }
}