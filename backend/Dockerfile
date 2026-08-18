# Stage 1: Build with Maven
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-focal AS runtime
WORKDIR /app

# 基础镜像默认 Etc/UTC。实体表用不带时区的 LocalDateTime，若不指定时区，写库时间会比
# 实际早 8 小时。docker-compose 会再显式覆盖一次，这里保证脱离 compose 单独运行也正确。
ENV TZ=Asia/Shanghai

COPY --from=build /app/target/*.jar app.jar
RUN mkdir -p /app/uploads

EXPOSE 8080
# Shell form so JAVA_OPTS (heap sizing etc.) set via docker-compose is honored.
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
