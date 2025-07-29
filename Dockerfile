# Use an official Eclipse Temurin base image with Java 17
FROM eclipse-temurin:17

# Set the working directory inside the container
WORKDIR /app

# Copy the compiled Spring Boot JAR from local machine into the container
COPY target/crumbs.jar app.jar

# Expose the default Spring Boot port
EXPOSE 8080

# Run the JAR file
ENTRYPOINT ["java", "-jar", "app.jar"]