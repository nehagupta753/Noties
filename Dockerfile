# Build stage
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app

# Copy maven wrapper and project descriptor
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x ./mvnw

# Copy source code and package
COPY src ./src
RUN ./mvnw clean package -DskipTests

# Run stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Copy the built jar from the build stage
COPY --from=build /app/target/youtube-notes-generator-1.0.0.jar app.jar

# Render exposes PORT env var
ENV PORT=3000
EXPOSE 3000

# Start the Spring Boot application
ENTRYPOINT ["java", "-jar", "app.jar"]
