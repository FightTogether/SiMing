#!/bin/bash

# SiMing 服务端数据库初始化脚本
# 用途：当数据库文件损坏或需要重新初始化时，可以运行此脚本来清空并重新创建数据库表结构

# 默认数据库路径
DEFAULT_DB_PATH="./data/siming.db"

# 日志函数
log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >&2
}

error() {
    log "ERROR: $1"
    exit 1
}

# 显示帮助
show_help() {
    echo "SiMing 服务端数据库初始化脚本"
    echo "用法: ./init-database.sh [数据库路径]"
    echo
    echo "默认数据库路径: $DEFAULT_DB_PATH"
    echo
    echo "示例:"
    echo "  ./init-database.sh                      # 使用默认路径初始化"
    echo "  ./init-database.sh /path/to/siming.db   # 指定路径初始化"
    echo
}

# 主函数
main() {
    local db_path="$1"
    
    if [ -z "$db_path" ]; then
        db_path="$DEFAULT_DB_PATH"
    fi

    # 检查sqlite3是否安装
    if ! command -v sqlite3 &> /dev/null; then
        error "sqlite3 未安装，请先安装sqlite3"
    fi

    # 如果数据库文件已经存在，询问是否确认删除
    if [ -f "$db_path" ]; then
        read -p "数据库文件 $db_path 已经存在，是否删除并重新初始化？(y/N) " confirm
        if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then
            log "操作已取消"
            exit 0
        fi
        rm -f "$db_path"
        log "已删除旧数据库文件: $db_path"
    else
        # 确保目录存在
        local db_dir=$(dirname "$db_path")
        if [ ! -d "$db_dir" ]; then
            mkdir -p "$db_dir"
            log "已创建数据目录: $db_dir"
        fi
    fi

    log "开始创建数据库表结构..."

    # 创建表结构的SQL
    sqlite3 "$db_path" << 'EOF'
-- 硬盘表
CREATE TABLE disks (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  client_id TEXT,
  device_path TEXT NOT NULL,
  brand TEXT,
  model TEXT,
  serial_number TEXT,
  total_capacity INTEGER NOT NULL,
  is_ssd INTEGER NOT NULL DEFAULT 0,
  monitored INTEGER NOT NULL DEFAULT 0,
  monitor_cron TEXT,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(client_id, device_path)
);

-- 容量监控记录表
CREATE TABLE capacity_records (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  disk_id INTEGER NOT NULL,
  filesystem TEXT,
  used_capacity INTEGER NOT NULL,
  available_capacity INTEGER NOT NULL,
  usage_percent REAL NOT NULL,
  mount_point TEXT,
  record_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (disk_id) REFERENCES disks(id)
);

-- SMART监控记录表
CREATE TABLE smart_records (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  disk_id INTEGER NOT NULL,
  attribute_id INTEGER NOT NULL,
  attribute_name TEXT,
  current_value INTEGER,
  worst_value INTEGER,
  threshold INTEGER,
  raw_value INTEGER,
  failed INTEGER NOT NULL DEFAULT 0,
  temperature INTEGER,
  record_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (disk_id) REFERENCES disks(id)
);

-- 分析结果表
CREATE TABLE analysis_results (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  disk_id INTEGER NOT NULL,
  start_time TIMESTAMP NOT NULL,
  end_time TIMESTAMP NOT NULL,
  analysis_content TEXT,
  health_score INTEGER,
  health_level TEXT,
  recommendations TEXT,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (disk_id) REFERENCES disks(id)
);

-- LLM配置表 - 存储大模型API配置和提示词模板
CREATE TABLE llm_config (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  api_base_url TEXT NOT NULL,
  api_key TEXT,
  model TEXT NOT NULL,
  timeout INTEGER NOT NULL DEFAULT 60000,
  temperature REAL,
  max_tokens INTEGER,
  prompt_template TEXT NOT NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
EOF

    if [ $? -ne 0 ]; then
        error "创建表结构失败"
    fi

    # 插入默认LLM配置
    # 默认提示词模板来自 LlmAnalysisService.java
    local default_prompt="你是一位专业的硬盘健康分析专家。请根据以下提供的硬盘SMART监控数据和容量使用历史，分析硬盘的健康状况，并给出专业的评估和建议。

## 硬盘基本信息
- 品牌: {brand}
- 型号: {model}
- 序列号: {serial}
- 总容量: {total_capacity_gb:.2f} GB
- 类型: {is_ssd:SSD:HDD}

## SMART数据摘要
{smart_data}

## 容量使用历史
{capacity_data}

## 分析要求:
1. 请给出硬盘健康评分，范围0-100分
2. 给出健康等级: 良好/预警/危险
3. 分析关键SMART指标，特别是坏块、温度、磨损等级等关键指标
4. 根据数据趋势分析硬盘老化情况
5. 给出具体的建议：是否需要更换、备份策略、使用注意事项等
6. 用简洁专业的markdown格式输出

现在请给出你的分析："

    # 插入默认配置，替换单引号
    default_prompt_escaped=$(echo "$default_prompt" | sed "s/'/''/g")
    
    sqlite3 "$db_path" "INSERT INTO llm_config (api_base_url, api_key, model, timeout, temperature, max_tokens, prompt_template) VALUES ('https://api.openai.com/v1', '', 'gpt-3.5-turbo', 60000, 0.7, 1000, '$default_prompt_escaped');"

    if [ $? -ne 0 ]; then
        error "插入默认LLM配置失败"
    fi

    log "✅ 数据库初始化完成！"
    log "数据库文件: $(realpath "$db_path")"
    log "已创建表: disks, capacity_records, smart_records, analysis_results, llm_config"
    log "已插入默认LLM配置"
}

# 处理命令行参数
if [ $# -gt 1 ]; then
    show_help
    exit 1
fi

if [ $# -eq 1 ] && [ "$1" = "-h" ] || [ "$1" = "--help" ] || [ "$1" = "help" ]; then
    show_help
    exit 0
fi

main "$@"