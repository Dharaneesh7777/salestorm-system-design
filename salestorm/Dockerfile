# Stage 1: Build the Application
FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /app

# Copy maven wrapper and pom.xml first to leverage Docker layer caching
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy source code and build jar
COPY src src
RUN ./mvnw clean package -DskipTests -B

# Stage 2: Minimal Runtime Image
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Create non-root system user for security
RUN groupadd -r salestorm && useradd -r -g salestorm salestorm

# Copy packaged jar from builder stage
COPY --from=builder /app/target/salestorm-*.jar app.jar

RUN chown -R salestorm:salestorm /app
USER salestorm

EXPOSE 8080

ENV JAVA_OPTS="-Xms512m -Xmx2048m -XX:+UseG1GC"
ENV SPRING_PROFILES_ACTIVE="docker"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
