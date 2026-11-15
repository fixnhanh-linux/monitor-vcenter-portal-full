# VM Monitor — web khách hàng (Java / Spring Boot)
# Build:  docker build -t vm-monitor-khach web-khach
# Chạy:   docker run -d -p 8080:8080 -e ADMIN_URL=http://<web-admin nội bộ>:3000 vm-monitor-khach
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/vm-monitor-khach.jar /app/vm-monitor-khach.jar
ENV TZ=Asia/Ho_Chi_Minh PORT=8080 ADMIN_URL=http://web-admin:3000
RUN useradd --system --uid 10002 khach
USER khach
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s CMD ["sh", "-c", "exec 3<>/dev/tcp/127.0.0.1/${PORT} || exit 1"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/vm-monitor-khach.jar"]
