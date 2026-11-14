# Multi-stage build for escrow-service
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Build through the reactor: escrow-service's tests depend on mock-payout-provider (they run it
# in-process), and Maven resolves test-scoped dependencies even with -DskipTests.
COPY pom.xml .
COPY escrow-service/pom.xml escrow-service/
COPY escrow-service/src escrow-service/src
COPY mock-payout-provider/pom.xml mock-payout-provider/
COPY mock-payout-provider/src mock-payout-provider/src

RUN --mount=type=cache,target=/root/.m2 \
    apk add --no-cache maven && \
    mvn -q -B -pl escrow-service -am clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

# Create non-root user
RUN addgroup -g 1000 escrow && \
    adduser -D -u 1000 -G escrow escrow

WORKDIR /app

# Copy JAR from builder
COPY --from=builder /build/escrow-service/target/escrow-service-*.jar app.jar

# Set ownership
RUN chown -R escrow:escrow /app

USER escrow

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
