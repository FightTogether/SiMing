#!/bin/bash

set -e

# 默认数据库路径
if [ -z "$DB_PATH" ]; then
    DB_PATH="/app/data/siming.db"
fi

# 日志函数
log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

# 检查数据库文件是否存在，如果不存在则初始化
if [ ! -f "$DB_PATH" ]; then
    log "数据库文件不存在，开始初始化..."
    ./init-database.sh "$DB_PATH"
    if [ $? -ne 0 ]; then
        log "数据库初始化失败!"
        exit 1
    fi
    log "数据库初始化完成"
else
    log "数据库文件已存在，跳过初始化"
fi

log "启动SiMing应用..."
exec java -jar app.jar