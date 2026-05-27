FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /workspace
COPY api-spec ./api-spec
COPY backend/pom.xml backend/pom.xml
COPY backend/src backend/src
WORKDIR /workspace/backend
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /workspace/backend/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
