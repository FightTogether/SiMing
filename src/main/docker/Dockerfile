
# 最终运行镜像
FROM eclipse-temurin:11-jre

WORKDIR /app


# 复制构建好的jar
#ADD *-siming-1.0-SNAPSHOT.jar app.jar
ADD *-dependencies.jar application.jar
# 创建数据目录
VOLUME /app/data

# 创建配置目录
VOLUME /app/config

# 设置环境变量
ENV SIMING_CONFIG="/app/config/application.yml"

# 入口点
ENTRYPOINT ["java", "-jar", "app.jar"]