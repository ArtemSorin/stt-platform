FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml ./
COPY common/pom.xml common/pom.xml
COPY analytics-service/pom.xml analytics-service/pom.xml
COPY stt-service/pom.xml stt-service/pom.xml
COPY common/src common/src
COPY analytics-service/src analytics-service/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -pl analytics-service -am package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/analytics-service/target/analytics-service-1.0.0.jar app.jar
RUN mkdir -p /data/reports && chown -R 10001:10001 /data
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
