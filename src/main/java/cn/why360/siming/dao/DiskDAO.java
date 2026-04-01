package cn.why360.siming.dao;

import cn.why360.siming.entity.Disk;
import cn.why360.siming.mapper.DiskMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 硬盘数据访问对象 - MyBatis实现
 */
@Repository
public class DiskDAO {
    private final DiskMapper diskMapper;

    public DiskDAO(DiskMapper diskMapper) {
        this.diskMapper = diskMapper;
    }

    /**
     * 获取所有硬盘
     */
    public List<Disk> findAll() {
        return diskMapper.findAll();
    }

    /**
     * 获取所有需要监控的硬盘
     */
    public List<Disk> findMonitored() {
        return diskMapper.findMonitored();
    }

    /**
     * 根据设备路径查找
     */
    public Optional<Disk> findByDevicePath(String devicePath) {
        return diskMapper.findByDevicePath(devicePath);
    }

    /**
     * 根据客户端ID和设备路径查找硬盘
     * 分布式部署时，不同客户端可能有相同的设备路径，需要组合查找
     */
    public Optional<Disk> findByClientIdAndDevicePath(String clientId, String devicePath) {
        return diskMapper.findByClientIdAndDevicePath(clientId, devicePath);
    }

    /**
     * 根据ID查找
     */
    public Optional<Disk> findById(Long id) {
        return diskMapper.findById(id);
    }

    /**
     * 插入新硬盘
     */
    public Long insert(Disk disk) {
        diskMapper.save(disk);
        return disk.getId();
    }

    /**
     * 更新硬盘信息
     */
    public void update(Disk disk) {
        diskMapper.save(disk);
    }

    /**
     * 保存硬盘（插入或更新）
     */
    public Disk save(Disk disk) {
        diskMapper.save(disk);
        return disk;
    }

    /**
     * 更新监控设置
     */
    public void updateMonitoring(Long diskId, boolean monitored, String cron) {
        diskMapper.updateMonitoring(diskId, monitored, cron);
    }

    /**
     * 删除硬盘
     */
    public void delete(Long id) {
        diskMapper.delete(id);
    }
}