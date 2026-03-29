#!/bin/bash

# SiMing 硬盘监控采集客户端
# 功能：采集硬盘信息并发送到服务端

# 默认配置文件路径
CONFIG_FILE="./config.yaml"

# 日志函数
log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

error() {
    log "ERROR: $1" >&2
}

# 检查依赖
check_dependencies() {
    local deps=("lsblk" "smartctl" "curl" "jq" "yq")
    for dep in "${deps[@]}"; do
        if ! command -v "$dep" &> /dev/null; then
            error "依赖工具 '$dep' 未安装，请先安装它"
            exit 1
        fi
    done
}

# 加载配置
load_config() {
    if [ ! -f "$CONFIG_FILE" ]; then
        error "配置文件 $CONFIG_FILE 不存在，请复制 config.yaml.example 并修改配置"
        exit 1
    fi

    SERVER_URL=$(yq e '.server.url' "$CONFIG_FILE")
    CLIENT_ID=$(yq e '.client.id' "$CONFIG_FILE")
    
    if [ -z "$SERVER_URL" ] || [ "$SERVER_URL" = "null" ]; then
        error "配置文件中缺少 server.url 配置"
        exit 1
    fi

    if [ -z "$CLIENT_ID" ] || [ "$CLIENT_ID" = "null" ]; then
        CLIENT_ID=$(hostname)
    fi

    log "配置加载完成，服务端地址: $SERVER_URL, 客户端ID: $CLIENT_ID"
}

# 发现系统中的硬盘
discover_disks() {
    log "开始发现硬盘..."
    local disks=$(lsblk -o NAME,SIZE,VENDOR,MODEL,SERIAL,ROTA -n -d | grep -E 'sd|nvme' | grep -v '^ ' | awk '{$1=$1};1')
    
    local disk_json="["
    local first=true

    while IFS= read -r line; do
        if [ -z "$line" ]; continue; fi

        local name=$(echo "$line" | awk '{print $1}')
        local device_path="/dev/$name"
        
        if [[ ! $device_path =~ ^/dev/(sd[a-z]+|nvme[0-9]+n[0-9]+)$ ]]; then
            continue
        fi

        # 获取磁盘大小（字节）
        local size=$(blockdev --getsize64 "$device_path" 2>/dev/null || echo 0)
        
        local vendor=$(echo "$line" | awk '{print $3}' | sed 's/^ *//; s/ *$//')
        local model=$(echo "$line" | awk '{print $4}' | sed 's/^ *//; s/ *$//')
        local serial=$(echo "$line" | awk '{print $5}' | sed 's/^ *//; s/ *$//')
        local is_rotational=$(echo "$line" | awk '{print $6}')
        local is_ssd=$([ "$is_rotational" -eq 0 ] && echo "true" || echo "false")

        if [ "$first" = false ]; then
            disk_json="$disk_json,"
        fi
        first=false

        disk_json="$disk_json{\"device_path\":\"$device_path\",\"brand\":\"$vendor\",\"model\":\"$model\",\"serial_number\":\"$serial\",\"total_capacity\":$size,\"is_ssd\":$is_ssd}"
    done <<< "$disks"

    disk_json="$disk_json]"
    echo "$disk_json"
}

# 获取硬盘SMART信息
read_smart() {
    local device_path=$1
    local disk_id=$2

    local smart_json="["
    local first=true

    # 使用smartctl获取SMART信息
    local output=$(smartctl -A "$device_path")
    local started=false
    local temperature=""

    while IFS= read -r line; do
        if [[ $line == "ID# ATTRIBUTE_NAME"* ]]; then
            started=true
            continue
        fi

        if [ "$started" != "true" ] || [[ -z $line ]]; then
            # 尝试提取温度
            if [[ $line =~ Temperature.*([0-9]+).*C ]]; then
                temperature="${BASH_REMATCH[1]}"
            fi
            continue
        fi

        # 解析SMART属性
        # 格式:  5  Reallocated_Sector_Ct   0x0033   100   100   050    Old_age   Always   -       0
        if [[ $line =~ ^[[:space:]]*([0-9]+)[[:space:]]+([A-Za-z0-9_]+)[[:space:]]+[0-9a-fx]+[[:space:]]+([0-9]+)[[:space:]]+([0-9]+)[[:space:]]+([0-9]+)[[:space:]]+.*-[[:space:]]+([0-9]+)$ ]]; then
            local attribute_id="${BASH_REMATCH[1]}"
            local attribute_name="${BASH_REMATCH[2]}"
            local current_value="${BASH_REMATCH[3]}"
            local worst_value="${BASH_REMATCH[4]}"
            local threshold="${BASH_REMATCH[5]}"
            local raw_value="${BASH_REMATCH[6]}"
            local failed=$(( current_value <= threshold ? 1 : 0 ))

            # 检查是否是温度属性
            if [[ "$attribute_name" =~ Temperature ]]; then
                temperature="$raw_value"
            fi

            if [ "$first" = false ]; then
                smart_json="$smart_json,"
            fi
            first=false

            smart_json="$smart_json{\"attribute_id\":$attribute_id,\"attribute_name\":\"$attribute_name\",\"current_value\":$current_value,\"worst_value\":$worst_value,\"threshold\":$threshold,\"raw_value\":$raw_value,\"failed\":$failed"
            if [[ "$attribute_name" =~ Temperature ]]; then
                smart_json="$smart_json,\"temperature\":$temperature"
            fi
            smart_json="$smart_json}"
        fi
    done <<< "$output"

    # 如果找到温度但不在属性列表中，添加它
    if [ -n "$temperature" ] && ! echo "$smart_json" | grep -q "Temperature_Celsius"; then
        if [ "$first" = false ]; then
            smart_json="$smart_json,"
        fi
        smart_json="$smart_json{\"attribute_id\":194,\"attribute_name\":\"Temperature_Celsius\",\"current_value\":$temperature,\"worst_value\":$temperature,\"threshold\":100,\"raw_value\":$temperature,\"failed\":0,\"temperature\":$temperature}"
    fi

    smart_json="$smart_json]"
    echo "$smart_json"
}

# 获取容量信息
check_capacity() {
    local output=$(df -k)
    local capacity_json="["
    local first=true

    # 跳过表头
    echo "$output" | tail -n +1 | while IFS= read -r line; do
        if [[ $line != /dev/* ]]; then
            continue
        fi

        # 跳过虚拟文件系统
        if [[ $line =~ (tmpfs|devtmpfs|sysfs|proc|devfs) ]]; then
            continue
        fi

        if [[ $line =~ ^[[:space:]]*(/dev/[^\s]+)[[:space:]]+([0-9]+)[[:space:]]+([0-9]+)[[:space:]]+([0-9]+)[[:space:]]+([0-9]+)%[[:space:]]+(.+)$ ]]; then
            local filesystem="${BASH_REMATCH[1]}"
            local k_blocks="${BASH_REMATCH[2]}"
            local used="${BASH_REMATCH[3]}"
            local available="${BASH_REMATCH[4]}"
            local percent="${BASH_REMATCH[5]}"
            local mount_point="${BASH_REMATCH[6]}"

            # 转换为字节
            used=$(( used * 1024 ))
            available=$(( available * 1024 ))

            if [ "$first" = false ]; then
                capacity_json="$capacity_json,"
            fi
            first=false

            capacity_json="$capacity_json{\"filesystem\":\"$filesystem\",\"mount_point\":\"$mount_point\",\"used_capacity\":$used,\"available_capacity\":$available,\"usage_percent\":$percent}"
        fi
    done

    capacity_json="$capacity_json]"
    echo "$capacity_json"
}

# 采集所有数据
collect_all_data() {
    log "开始采集所有硬盘数据..."

    local disks_json=$(discover_disks)
    local current_time=$(date +%s)

    # 构建完整的JSON
    local full_json="{\"client_id\":\"$CLIENT_ID\",\"timestamp\":$current_time,\"disks\":["

    local disk_count=$(echo "$disks_json" | jq length)
    local i=0

    while [ $i -lt $disk_count ]; do
        local disk=$(echo "$disks_json" | jq -c .[$i])
        local device_path=$(echo "$disk" | jq -r .device_path)
        local disk_json=$(echo "$disk" | jq '. + { '".smart_attributes": $(read_smart "$device_path"), "capacity_records": $(check_capacity) ' }')

        if [ $i -gt 0 ]; then
            full_json="$full_json,"
        fi

        full_json="$full_json$disk_json"
        i=$((i + 1))
    done

    full_json="$full_json]}"

    echo "$full_json"
}

# 发送数据到服务端
send_data() {
    local data=$1
    log "正在发送数据到服务端: $SERVER_URL"

    local response=$(curl -s -w "\n%{http_code}" -X POST "$SERVER_URL/api/report" \
        -H "Content-Type: application/json" \
        -d "$data")

    local http_code=$(echo "$response" | tail -n1)
    local body=$(echo "$response" | head -n -1)

    if [ "$http_code" -eq 200 ]; then
        log "数据发送成功！响应: $body"
        return 0
    else
        error "数据发送失败，HTTP状态码: $http_code，响应: $body"
        return 1
    fi
}

# 主函数
main() {
    log "SiMing 硬盘监控采集客户端启动"

    check_dependencies
    load_config

    local data=$(collect_all_data)
    send_data "$data"

    if [ $? -eq 0 ]; then
        log "采集完成，成功上报"
        exit 0
    else
        error "采集完成，但上报失败"
        exit 1
    fi
}

# 如果被直接执行则运行主函数
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    # 允许通过参数指定配置文件
    if [ $# -ge 1 ]; then
        CONFIG_FILE="$1"
    fi
    main
fi