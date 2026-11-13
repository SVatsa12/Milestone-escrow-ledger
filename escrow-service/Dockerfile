# Multi-stage build for escrow-service
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Copy Maven files
COPY escrow-service/pom.xml .
COPY pom.xml ../pom.xml

# Copy source code
COPY escrow-service/src ./src

# Build the application
RUN apk add --no-cache maven && \
    mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

# Create non-root user
RUN addgroup -g 1000 escrow && \
    adduser -D -u 1000 -G escrow escrow

WORKDIR /app

# Copy JAR from builder
COPY --from=builder /build/target/escrow-service-*.jar app.jar

# Set ownership
RUN chown -R escrow:escrow /app

USER escrow

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
