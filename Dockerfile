# 🛠 Stage 1: Build the JAR using Maven
FROM maven:3.9.4-eclipse-temurin-17 AS build
WORKDIR /app

# Copy project files
COPY pom.xml .
COPY lib ./lib  
COPY src ./src

# Show what's in lib (for debugging)
RUN echo "📁 lib contains:" && ls -l lib

# Build the application
RUN mvn clean package -DskipTests


# 🚀 Stage 2: Run the app
FROM eclipse-temurin:17
WORKDIR /app

# Persist H2 file-based DB
VOLUME /app/data

# Copy app and dependencies
COPY --from=build /app/target/crumbs.jar crumbs.jar
COPY --from=build /app/lib /app/lib

# Expose Spring Boot port
EXPOSE 8080

# Run the app with external JARs on the classpath
ENTRYPOINT ["java", "-cp", "crumbs.jar:lib/*", "com.crumbs.trade.CrumbsNewApplication"]
