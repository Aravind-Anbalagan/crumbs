# 🛠 Stage 1: Build the JAR using Maven
FROM maven:3.9.4-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# 🚀 Stage 2: Run the built JAR in a smaller image
FROM eclipse-temurin:17
WORKDIR /app

# OPTIONAL: Create a persistent data folder for H2
VOLUME /app/data

# Copy only the final JAR
COPY --from=build /app/target/crumbs.jar app.jar

# Expose default Spring Boot port
EXPOSE 8080

# Run the Spring Boot app
ENTRYPOINT ["java", "-jar", "app.jar"]
