#!/bin/bash

# SiMing 硬盘监控采集客户端
# 功能：采集硬盘信息并发送到服务端，本地存储一份

# 默认配置文件路径
CONFIG_FILE="./client-config.conf"
# 数据存储根目录
DATA_ROOT="./data"

# 日志函数
log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >&2
}

error() {
    log "ERROR: $1"
}

# 检查依赖
check_dependencies() {
    local deps=("lsblk" "smartctl" "curl" "blockdev")
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
        error "配置文件 $CONFIG_FILE 不存在，请复制 client-config.conf.example 并修改配置"
        exit 1
    fi

    # 简单配置文件格式，每行是 KEY=VALUE
    while IFS= read -r line; do
        if [ -z "$line" ]; then
            continue
        fi
        if [[ $line == \#* ]]; then
            continue
        fi
        eval "$line"
    done < "$CONFIG_FILE"
    
    if [ -z "$SERVER_URL" ]; then
        error "配置文件中缺少 SERVER_URL 配置"
        exit 1
    fi

    if [ -z "$CLIENT_ID" ]; then
        CLIENT_ID=$(hostname)
    fi

    if [ -z "$DATA_ROOT" ]; then
        DATA_ROOT="./data"
    fi

    # 创建数据根目录
    mkdir -p "$DATA_ROOT"

    log "配置加载完成，服务端地址: $SERVER_URL, 客户端ID: $CLIENT_ID, 数据存储目录: $DATA_ROOT"
}

# 发现系统中的硬盘
discover_disks() {
    log "开始发现硬盘..."
    local disks=$(lsblk -o NAME,SIZE,VENDOR,MODEL,SERIAL,ROTA -n -d | grep -E 'sd|nvme' | grep -v '^ ' | awk '{$1=$1};1')
    
    local disk_json="["
    local first=true

    while IFS= read -r line; do
        if [ -z "$line" ]; then
            continue
        fi

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
        # 确保is_rotational是数字
        if ! [[ $is_rotational =~ ^[0-9]+$ ]]; then
            is_rotational=1
        fi
        # nvme固态硬盘默认是SSD，即使is_rotational识别错了
        local is_ssd
        if [[ $device_path == /dev/nvme* ]]; then
            is_ssd="true"
        else
            is_ssd=$([ "$is_rotational" -eq 0 ] && echo "true" || echo "false")
        fi

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

        if [ "$started" != "true" ] || [ -z "$line" ]; then
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
            # 移除所有数字的前导零，保证JSON格式正确
            attribute_id=$((10#$attribute_id))
            current_value=$((10#$current_value))
            worst_value=$((10#$worst_value))
            threshold=$((10#$threshold))
            raw_value=$((10#$raw_value))
            local current_value_clean=$current_value
            local threshold_clean=$threshold
            local failed=$(( current_value_clean <= threshold_clean ? 1 : 0 ))

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

# 生成硬盘唯一ID
generate_disk_id() {
    local vendor=$1
    local model=$2
    local serial=$3
    
    # 使用序列号、型号和厂商组合生成唯一ID
    echo "${vendor}_${model}_$serial" | tr ' /:' '_'
}

# 保存硬盘数据到本地
save_disk_data() {
    local disk_id=$1
    local disk_data=$2
    
    local timestamp=$(date +%s)
    local disk_dir="$DATA_ROOT/$disk_id"
    local output_file="$disk_dir/${disk_id}_${timestamp}.json"
    
    # 创建硬盘目录
    mkdir -p "$disk_dir"
    
    # 保存数据
    echo "$disk_data" > "$output_file"
    
    log "硬盘数据已保存到: $output_file"
}

# 异步发送数据到服务端
send_data_async() {
    local data=$1
    
    # 后台异步发送
    (
        log "正在异步发送数据到服务端: $SERVER_URL"

        local response=$(curl -s -w "\n%{http_code}" -X POST "$SERVER_URL/api/report" \
            -H "Content-Type: application/json" \
            -d "$data")

        local http_code=$(echo "$response" | tail -n1)
        local body=$(echo "$response" | head -n -1)

        if [ "$http_code" -eq 200 ]; then
            log "数据发送成功！响应: $body"
        else
            error "数据发送失败，HTTP状态码: $http_code，响应: $body"
        fi
    ) &
}

# 采集所有数据
collect_all_data() {
    log "开始采集所有硬盘数据..."

    local disks_json=$(discover_disks 2>/dev/null)
    local current_time=$(date +%s)

    # 构建完整的JSON
    local full_json="{\"client_id\":\"$CLIENT_ID\",\"timestamp\":$current_time,\"disks\":["

    # 解析手动构建的JSON数组，不需要jq
    # 移除开头的[和结尾的]
    local disks_content="${disks_json#[}"
    disks_content="${disks_content%]}"
    
    # 如果没有硬盘
    if [ -z "$disks_content" ]; then
        error "未发现任何硬盘"
        exit 1
    fi
    
    # 分割多个磁盘JSON对象
    local current_disk=""
    local brace_count=0
    local in_quote=false
    local escaped=false
    local first_disk=true
    
    while IFS= read -r -n1 char; do
        if [ "$char" = '"' ] && [ "$escaped" != "true" ]; then
            in_quote=$([ "$in_quote" = "false" ] && echo "true" || echo "false")
        fi
        if [ "$char" = '\\' ] && [ "$in_quote" = "true" ] && [ "$escaped" = "false" ]; then
            escaped=true
            current_disk="$current_disk$char"
            continue
        fi
        escaped=false
        
        if [ "$in_quote" = "false" ]; then
            if [ "$char" = "{" ]; then
                brace_count=$((brace_count + 1))
            elif [ "$char" = "}" ]; then
                brace_count=$((brace_count - 1))
                if [ $brace_count -eq 0 ]; then
                    # 完整的一个磁盘对象
                    current_disk="$current_disk$char"
                    
                    # 提取各个字段，通过简单的字符串处理
                    # 格式: {"device_path":"/dev/xxx","brand":"xxx","model":"xxx","serial_number":"xxx","total_capacity":xxx,"is_ssd":xxx}
                    device_path=$(echo "$current_disk" | sed -E 's/.*"device_path":"([^"]+)".*/\1/')
                    vendor=$(echo "$current_disk" | sed -E 's/.*"brand":"([^"]*)".*/\1/')
                    model=$(echo "$current_disk" | sed -E 's/.*"model":"([^"]+)".*/\1/')
                    serial=$(echo "$current_disk" | sed -E 's/.*"serial_number":"([^"]*)".*/\1/')
                    
                    # 生成硬盘唯一ID
                    local disk_id=$(generate_disk_id "$vendor" "$model" "$serial")
                    # 子函数调用确保只捕获JSON输出，日志去stderr
                    local smart_data=$(read_smart "$device_path" "$disk_id" 2>/dev/null)
                    local capacity_data=$(check_capacity 2>/dev/null)
                    
                    # 添加 disk_id, smart_attributes, capacity_records 构建完整磁盘JSON
                    local disk_json="{\"device_path\":\"$device_path\",\"brand\":\"$vendor\",\"model\":\"$model\",\"serial_number\":\"$serial\""
                    disk_json="$disk_json,\"disk_id\":\"$disk_id\",\"total_capacity\":$(echo "$current_disk" | sed -E 's/.*"total_capacity":([0-9]+).*/\1/'),\"is_ssd\":$(echo "$current_disk" | sed -E 's/.*"is_ssd":(true|false).*/\1/')"
                    disk_json="$disk_json,\"smart_attributes\":$smart_data,\"capacity_records\":$capacity_data}"
                    
                    # 保存单个硬盘数据到本地，日志去stderr，不会污染捕获输出
                    save_disk_data "$disk_id" "$disk_json"

                    if [ "$first_disk" = false ]; then
                        full_json="$full_json,"
                    fi

                    full_json="$full_json$disk_json"
                    first_disk=false
                    
                    current_disk=""
                    continue
                fi
            fi
        fi
        
        current_disk="$current_disk$char"
    done <<< "$disks_content"

    full_json="$full_json]}"

    echo "$full_json"
}

# 主函数
main() {
    log "SiMing 硬盘监控采集客户端启动"

    check_dependencies
    load_config

    local full_data=$(collect_all_data)
    
    # 保存完整采集数据到根目录
    local timestamp=$(date +%s)
    local full_report_file="$DATA_ROOT/full_report_${CLIENT_ID}_${timestamp}.json"
    echo "$full_data" > "$full_report_file"
    log "完整采集数据已保存到: $full_report_file"
    
    # 异步发送数据到服务端
    send_data_async "$full_data"
    
    log "采集完成，数据已本地存储，异步发送已启动"
    exit 0
}

# 如果被直接执行则运行主函数
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    # 允许通过参数指定配置文件
    if [ $# -ge 1 ]; then
        CONFIG_FILE="$1"
    fi
    main
fi