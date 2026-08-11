# Stage 1: Build the Spring Boot executable JAR using Maven
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder

WORKDIR /app

# Copy Maven wrapper & POM configuration
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw

# Copy source code and package application
COPY src ./src
RUN ./mvnw clean package -DskipTests

# Stage 2: Production JRE runtime container
FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="MOSIP UIN Deletion Team"
LABEL description="Identity Data Deletion Service - Multi-store UIN Data Purging Portal"

WORKDIR /app

# Create non-root application user for secure execution
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy built artifact from builder stage
COPY --from=builder /app/target/mosip-uin-deletion-0.0.1-SNAPSHOT.jar app.jar

# Set file ownership
RUN chown -R appuser:appgroup /app
USER appuser

# Expose default HTTP port
EXPOSE 8081

# Container healthcheck
HEALTHCHECK --interval=15s --timeout=5s --retries=5 --start-period=20s \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8081/ || exit 1

ENV JAVA_OPTS="-Xms256m -Xmx512m"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
