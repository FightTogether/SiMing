#!/bin/bash

# SiMing 客户端一键安装脚本
# 用法: curl -fsSL <server-url>/install.sh | bash -s <server-url>

set -e

# 日志函数
log() {
    echo -e "\033[1;32m[INFO]\033[0m $1"
}

error() {
    echo -e "\033[1;31m[ERROR]\033[0m $1" >&2
    exit 1
}

# 检查参数
if [ $# -ne 1 ]; then
    error "用法错误\n正确用法: curl -fsSL <server-url>/install.sh | bash -s <server-url>\n示例: curl -fsSL https://siming.example.com/install.sh | bash -s https://siming.example.com"
fi

SERVER_URL="$1"

# 去除末尾斜杠
SERVER_URL=$(echo "$SERVER_URL" | sed 's/\/$//')

log "开始安装 SiMing 硬盘监控客户端..."
log "服务端地址: $SERVER_URL"

# 检查依赖
check_dependency() {
    if ! command -v "$1" &> /dev/null; then
        error "依赖工具 '$1' 未安装，请先安装它"
    fi
}

log "检查依赖..."
check_dependency curl
check_dependency python3
check_dependency bash

# 默认安装目录
INSTALL_DIR="$HOME/siming"

# 创建安装目录
if [ ! -d "$INSTALL_DIR" ]; then
    log "创建安装目录: $INSTALL_DIR"
    mkdir -p "$INSTALL_DIR"
fi

cd "$INSTALL_DIR" || error "无法进入目录 $INSTALL_DIR"

# 下载 disk-monitor.sh
log "从服务端下载客户端脚本..."
SCRIPT_URL="$SERVER_URL/disk-monitor.sh"
if ! curl -fSL "$SCRIPT_URL" -o disk-monitor.sh; then
    error "下载失败: $SCRIPT_URL"
fi

# 设置执行权限
chmod +x disk-monitor.sh
log "客户端脚本下载完成"

# 获取客户端ID（主机名）
CLIENT_ID=$(hostname)
log "客户端ID: $CLIENT_ID"

# 生成配置文件
log "生成配置文件 client-config.conf..."
cat > client-config.conf << EOF
# SiMing 采集客户端配置文件
# 由 install.sh 自动生成

# 服务端地址（自动配置）
SERVER_URL="$SERVER_URL"

# 客户端ID，用于标识不同的客户端（自动使用主机名）
CLIENT_ID="$CLIENT_ID"

# 数据存储根目录，采集到的数据会保存在这里（默认是 ./data）
# DATA_ROOT="./data"

# 守护进程模式采集间隔（秒），默认 86400 = 每天采集一次
# 只有在使用 ./disk-monitor.sh start 启动守护进程时生效
# COLLECT_INTERVAL=86400
EOF

log "配置文件生成完成"
log "安装完成！"
echo
log "👉 下一步："
echo "   cd $INSTALL_DIR"
echo "   ./disk-monitor.sh once          # 测试采集"
echo "   ./disk-monitor.sh start         # 启动守护进程"
echo "   ./disk-monitor.sh help          # 查看更多帮助"
echo