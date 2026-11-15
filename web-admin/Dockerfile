# VM Monitor — web admin (Java / Spring Boot)
# Build:  docker build -t vm-monitor-admin web-admin
# Chạy:   docker run -d -p 3000:3000 -v <thư mục portal>:/portal vm-monitor-admin
#         (/portal chứa config.json + data/ — dùng chung với bản Node; giao diện đã nằm trong jar)
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/vm-monitor-admin.jar /app/vm-monitor-admin.jar
ENV TZ=Asia/Ho_Chi_Minh MONITOR_ROOT=/portal
VOLUME ["/portal"]
EXPOSE 3000 3443
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/vm-monitor-admin.jar"]
