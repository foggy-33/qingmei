## syntax=docker/dockerfile:1.7
FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /app

COPY pom.xml ./
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests clean package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN mkdir -p /app/storage
COPY --from=builder /app/target/review-platform-0.0.1-SNAPSHOT.jar /app/review-server.jar

EXPOSE 8080
CMD ["java", "-jar", "/app/review-server.jar"]
