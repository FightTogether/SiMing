FROM docker.1ms.run/library/maven:3.8.6-jdk-11 AS builder

WORKDIR /app

# 复制pom文件并下载依赖
COPY ../../../pom.xml .
RUN mvn dependency:go-offline -B

# 复制源代码并构建
COPY ../../.. .
RUN mvn package -DskipTests

# 最终运行镜像
FROM docker.1ms.run/azul/zulu-openjdk-alpine:11-jre-latest

WORKDIR /app

# 安装smartmontools用于获取SMART信息和sqlite3用于数据库初始化
RUN apk add --no-cache smartmontools sqlite3 bash

# 复制构建好的jar
COPY --from=builder /app/target/siming-1.0-SNAPSHOT.jar app.jar

# 复制数据库初始化脚本
COPY ../../../../init-database.sh .
RUN chmod +x init-database.sh

# 创建数据目录
VOLUME /app/data

# 创建配置目录
VOLUME /app/config

# 设置环境变量
ENV OPENAI_API_KEY=""
ENV DB_PATH="/app/data/siming.db"

# 复制启动脚本
COPY --from=builder /app/src/main/docker/start.sh .
RUN chmod +x start.sh

# 入口点
ENTRYPOINT ["./start.sh"]
