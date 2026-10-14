FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build
COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY build.gradle settings.gradle ./
COPY src src
RUN chmod +x gradlew && ./gradlew bootJar

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y protobuf-compiler && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /build/build/libs/grpc-mcp-*.jar app.jar
ENV GRPC_WKT_DIR=/wkt
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
