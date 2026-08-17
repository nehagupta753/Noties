# Build stage using official Maven image (no wrapper dependency needed)
FROM maven:3.9.6-eclipse-temurin-17-alpine AS build
WORKDIR /app

# Copy pom.xml and download dependencies
COPY pom.xml ./
RUN mvn dependency:go-offline -B

# Copy source code and build package
COPY src ./src
RUN mvn clean package -DskipTests -B

# Run stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Copy built jar from build stage
COPY --from=build /app/target/youtube-notes-generator-1.0.0.jar app.jar

# Port configuration
ENV PORT=3000
EXPOSE 3000

# Run Spring Boot application
ENTRYPOINT ["java", "-jar", "app.jar"]
