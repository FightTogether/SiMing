FROM hub-mirror.c.163.com/library/maven:3.8.6-jdk-11 AS builder

WORKDIR /app

# 复制pom文件并下载依赖
COPY ../../../pom.xml .
RUN mvn dependency:go-offline -B

# 复制源代码并构建
COPY ../../.. .
RUN mvn package -DskipTests

# 最终运行镜像
FROM hub-mirror.c.163.com/library/openjdk:11-jre

WORKDIR /app

# 安装smartmontools用于获取SMART信息
RUN apt-get update && \
    apt-get install -y smartmontools && \
    rm -rf /var/lib/apt/lists/*

# 复制构建好的jar
COPY --from=builder /app/target/siming-1.0-SNAPSHOT.jar app.jar

# 创建数据目录
VOLUME /app/data

# 创建配置目录
VOLUME /app/config

# 设置环境变量
ENV OPENAI_API_KEY=""
ENV SIMING_CONFIG="/app/config/application.yml"

# 入口点
ENTRYPOINT ["java", "-jar", "app.jar"]
