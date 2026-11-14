# Multi-stage build for mock-payout-provider
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Copy Maven files
COPY mock-payout-provider/pom.xml .
COPY pom.xml ../pom.xml

# Copy source code
COPY mock-payout-provider/src ./src

# Build the application
RUN --mount=type=cache,target=/root/.m2 \
    apk add --no-cache maven && \
    mvn -q -B clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

# Create non-root user
RUN addgroup -g 1000 provider && \
    adduser -D -u 1000 -G provider provider

WORKDIR /app

# Copy JAR from builder
COPY --from=builder /build/target/mock-payout-provider-*-exec.jar app.jar

# Set ownership
RUN chown -R provider:provider /app

USER provider

EXPOSE 9090

ENTRYPOINT ["java", "-jar", "app.jar"]
