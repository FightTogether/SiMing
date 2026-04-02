#!/bin/bash

# SiMing 硬盘监控采集客户端
# 功能：采集硬盘原始信息并发送到服务端
# 客户端只采集原始数据，解析和存储由服务端负责

# 默认配置文件路径
CONFIG_FILE="./client-config.conf"

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

    log "配置加载完成，服务端地址: $SERVER_URL, 客户端ID: $CLIENT_ID"
}

# 发现系统中的硬盘，获取基础信息和原始lsblk输出
discover_disks() {
    log "开始发现硬盘..."
    local disks_output=$(lsblk -o NAME,SIZE,VENDOR,MODEL,SERIAL,ROTA -n -d)
    local filtered_disks=$(echo "$disks_output" | grep -E 'sd|nvme' | grep -v '^ ' | awk '{$1=$1};1')
    
    local disks_json="["
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
        
        # lsblk -o NAME,SIZE,VENDOR,MODEL,SERIAL,ROTA -n -d
        # 字段顺序: NAME SIZE VENDOR MODEL SERIAL ROTA
        # 由于VENDOR可能是空字符串导致对齐问题，我们需要从后往前取
        # 最后一个字段一定是ROTA（0或1）
        local fields=($line)
        local nfields=${#fields[@]}
        local is_rotational="${fields[nfields-1]}"
        local serial="${fields[nfields-2]}"
        
        # 从第三个字段开始到倒数第二个字段之前是 VENDOR + MODEL，可能多个词
        local vendor=""
        local model=""
        local model_start
        if [ $nfields -ge 6 ]; then
            vendor="${fields[2]}"
            model_start=3
        else
            vendor=""
            model_start=2
        fi
        # 合并 model_start 到倒数第三个之前的所有字段都是model（允许model包含多个单词和空格）
        for ((i=model_start; i<=nfields-3; i++)); do
            if [ -z "$model" ]; then
                model="${fields[i]}"
            else
                model="$model ${fields[i]}"
            fi
        done

        # 去除首尾空格
        vendor=$(echo "$vendor" | sed 's/^ *//; s/ *$//')
        model=$(echo "$model" | sed 's/^ *//; s/ *$//')
        serial=$(echo "$serial" | sed 's/^ *//; s/ *$//')
        
        # 跳过品牌为QEMU的虚拟硬盘
        if [ "$vendor" = "QEMU" ]; then
            log "跳过QEMU虚拟硬盘: $device_path"
            continue
        fi
        
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

        # 获取SMART JSON格式输出，使用Python进行正确的JSON转义，保证格式正确
        local smart_json=$(smartctl -j -a "$device_path")
        # 使用Python对JSON字符串进行正确转义
        local smart_json_escaped=$(python3 -c "import json, sys; print(json.dumps(sys.stdin.read()))" <<< "$smart_json")

        if [ "$first" = false ]; then
            disks_json="$disks_json,"
        fi
        first=false

        disks_json="$disks_json{"
        disks_json="$disks_json\"device_path\":\"$device_path\","
        disks_json="$disks_json\"brand\":\"$vendor\","
        disks_json="$disks_json\"model\":\"$model\","
        disks_json="$disks_json\"serial_number\":\"$serial\","
        disks_json="$disks_json\"total_capacity\":$size,"
        disks_json="$disks_json\"is_ssd\":$is_ssd,"
        disks_json="$disks_json\"smart_json\":$smart_json_escaped"
        disks_json="$disks_json}"
    done <<< "$filtered_disks"

    disks_json="$disks_json]"
    echo "$disks_json"
}

# 获取容量信息原始df输出
get_df_raw() {
    local df_output=$(df -k)
    # 使用Python进行正确的JSON转义，保证格式正确
    df_output=$(python3 -c "import json, sys; print(json.dumps(sys.stdin.read()))" <<< "$df_output")
    echo "$df_output"
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

# 同步发送数据到服务端（用于守护进程模式）
send_data_sync() {
    local data=$1
    log "正在发送数据到服务端: $SERVER_URL"

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
}

# 采集所有数据
collect_all_data() {
    log "开始采集所有硬盘原始数据..."

    local disks_json=$(discover_disks 2>/dev/null)
    local df_raw=$(get_df_raw 2>/dev/null)
    local current_time=$(date +%s)

    # 构建完整的JSON - df_raw使用JSON标准转义
    local full_json="{\"client_id\":\"$CLIENT_ID\",\"timestamp\":$current_time,\"disks\":$disks_json,\"df_raw\":$df_raw}"

    echo "$full_json"
}

# PID文件路径，用于后台守护进程管理
PID_FILE="./disk-monitor.pid"
# 日志文件路径
LOG_FILE="./disk-monitor.log"

# 显示帮助
show_help() {
    echo "SiMing 硬盘监控采集客户端"
    echo "用法: ./disk-monitor.sh [命令] [配置文件路径]"
    echo
    echo "命令:"
    echo "  once        单次采集并退出（默认行为）"
    echo "  start       启动守护进程，定期采集"
    echo "  stop        停止守护进程"
    echo "  restart     重启守护进程"
    echo "  status      查看守护进程状态"
    echo "  update      从服务端更新到最新版本脚本"
    echo "  help        显示帮助"
    echo
    echo "默认配置文件路径: $CONFIG_FILE"
}

# 检查守护进程是否运行
is_running() {
    if [ -f "$PID_FILE" ]; then
        local pid=$(cat "$PID_FILE")
        if kill -0 "$pid" 2>/dev/null; then
            return 0
        else
            # PID文件存在但进程不存在，清理它
            rm -f "$PID_FILE"
            return 1
        fi
    fi
    return 1
}

# 启动守护进程
start_daemon() {
    if is_running; then
        log "守护进程已经在运行了，请查看status或使用restart"
        exit 0
    fi

    log "正在启动SiMing采集守护进程..."
    load_config

    # 后台运行，使用循环定期采集
    # 读取配置中的采集间隔（秒），默认86400秒（每天）
    if [ -z "$COLLECT_INTERVAL" ]; then
        COLLECT_INTERVAL=86400
    fi

    (
        while true; do
            log "=== 开始定时采集原始数据 ==="
            check_dependencies
            local full_data=$(collect_all_data 2>/dev/null)
            
            # 发送数据到服务端（同步等待完成，避免重叠采集）
            send_data_sync "$full_data"
            
            log "=== 采集完成，等待 $COLLECT_INTERVAL 秒后下次采集 ==="
            sleep "$COLLECT_INTERVAL"
        done
    ) >> "$LOG_FILE" 2>&1 &

    local pid=$!
    echo "$pid" > "$PID_FILE"
    log "守护进程已启动，PID: $pid，日志输出到: $LOG_FILE"
    log "采集间隔: $COLLECT_INTERVAL 秒"
    exit 0
}

# 停止守护进程
stop_daemon() {
    if [ -f "$PID_FILE" ]; then
        local pid=$(cat "$PID_FILE")
        if kill -0 "$pid" 2>/dev/null; then
            log "正在停止守护进程 PID: $pid..."
            kill "$pid"
            wait "$pid" 2>/dev/null
            rm -f "$PID_FILE"
            log "守护进程已停止"
        else
            log "守护进程未运行，清理过期PID文件"
            rm -f "$PID_FILE"
        fi
    else
        log "守护进程未运行，PID文件不存在"
    fi
    exit 0
}

# 查看状态
show_status() {
    if is_running; then
        local pid=$(cat "$PID_FILE")
        log "SiMing采集守护进程正在运行，PID: $pid"
        log "日志文件: $LOG_FILE"
        if [ -f "$LOG_FILE" ]; then
            local lines=$(wc -l < "$LOG_FILE")
            log "当前日志行数: $lines"
        fi
    else
        log "SiMing采集守护进程未运行"
    fi
    exit 0
}

# 从服务端更新到最新版本脚本
update_script() {
    log "开始从服务端检查更新..."
    
    # 加载配置获取服务端地址
    load_config
    
    # 获取当前脚本路径
    local script_path=$(realpath "$0")
    local script_dir=$(dirname "$script_path")
    local backup_path="${script_path}.backup.$(date +%Y%m%d%H%M%S)"
    
    log "当前脚本路径: $script_path"
    log "备份路径: $backup_path"
    
    # 构建更新URL - 从服务端下载最新的disk-monitor.sh
    # Spring Boot默认静态资源从classpath:/static/映射到根路径，所以路径是 /disk-monitor.sh
    local update_url="$SERVER_URL/disk-monitor.sh"
    
    log "正在从 $update_url 下载最新版本..."
    
    # 下载新版本到临时文件
    local temp_file="${script_dir}/disk-monitor.sh.tmp"
    local http_code=$(curl -s -w "%{http_code}" -o "$temp_file" "$update_url")
    
    if [ "$http_code" -ne 200 ]; then
        error "下载失败，HTTP状态码: $http_code"
        rm -f "$temp_file"
        exit 1
    fi
    
    # 检查下载的文件是否为有效的脚本
    if ! grep -q "SiMing" "$temp_file" || ! grep -q "disk-monitor" "$temp_file"; then
        error "下载的文件不是有效的disk-monitor脚本，更新已取消"
        rm -f "$temp_file"
        exit 1
    fi
    
    # 备份当前版本
    cp "$script_path" "$backup_path"
    log "当前版本已备份到: $backup_path"
    
    # 替换为新版本
    mv "$temp_file" "$script_path"
    chmod +x "$script_path"
    
    log "✅ 更新成功！已更新到最新版本"
    log "新脚本路径: $script_path"
    log "如果守护进程正在运行，请执行 ./disk-monitor.sh restart 重启以应用更新"
    
    exit 0
}

# 主函数 - 单次采集模式
main_single() {
    log "SiMing 硬盘监控采集客户端启动 [单次采集模式]"

    check_dependencies
    load_config

    local full_data=$(collect_all_data)
    
    # 异步发送数据到服务端
    send_data_async "$full_data"
    
    log "采集完成，数据已发送"
    exit 0
}

# 处理命令行参数
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    COMMAND="once"
    if [ $# -ge 1 ]; then
        COMMAND="$1"
        if [ $# -ge 2 ]; then
            CONFIG_FILE="$2"
        fi
    fi

    case "$COMMAND" in
        "once")
            main_single
            ;;
        "start")
            start_daemon
            ;;
        "stop")
            stop_daemon
            ;;
        "restart")
            stop_daemon
            sleep 1
            start_daemon
            ;;
        "status")
            show_status
            ;;
        "update")
            update_script
            ;;
        "help" | "-h" | "--help")
            show_help
            ;;
        *)
            error "未知命令: $COMMAND"
            show_help
            exit 1
            ;;
    esac
fi