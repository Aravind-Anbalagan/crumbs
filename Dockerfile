# 🛠 Stage 1: Build the JAR using Maven
FROM maven:3.9.4-eclipse-temurin-17 AS build
WORKDIR /app

# Copy project files
COPY pom.xml .
COPY lib ./lib
COPY src ./src

# Inject custom JAR into local Maven repo before Maven resolves dependencies
RUN mkdir -p /root/.m2/repository/com/angelbroking/smartapi/smartapi-java/2.2.6 && \
    cp lib/smartapi-java-2.2.6.jar /root/.m2/repository/com/angelbroking/smartapi/smartapi-java/2.2.6/smartapi-java-2.2.6.jar && \
    echo '<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd"><modelVersion>4.0.0</modelVersion><groupId>com.angelbroking.smartapi</groupId><artifactId>smartapi-java</artifactId><version>2.2.6</version><packaging>jar</packaging></project>' \
    > /root/.m2/repository/com/angelbroking/smartapi/smartapi-java/2.2.6/smartapi-java-2.2.6.pom
# ---- Inject Stocknote Bridge JAR (io.samco) ----
RUN mkdir -p /root/.m2/repository/io/samco/stocknote-bridge-java/3.0.0 && \
    cp lib/stocknote-bridge-java-3.0.0.jar \
       /root/.m2/repository/io/samco/stocknote-bridge-java/3.0.0/stocknote-bridge-java-3.0.0.jar && \
    echo '<project xmlns="http://maven.apache.org/POM/4.0.0" \
      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" \
      xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd"> \
      <modelVersion>4.0.0</modelVersion> \
      <groupId>io.samco</groupId> \
      <artifactId>stocknote-bridge-java</artifactId> \
      <version>3.0.0</version> \
      <packaging>jar</packaging> \
    </project>' \
    > /root/.m2/repository/io/samco/stocknote-bridge-java/3.0.0/stocknote-bridge-java-3.0.0.pom

# Debug: confirm local repo override
RUN echo "✅ Custom smartapi-java manually added to local Maven repo"

# Debug: show what's in lib (for troubleshooting)
RUN echo "📁 lib contains:" && ls -l lib

# Build the application (will pick from local repo)
RUN mvn clean package -DskipTests


# 🚀 Stage 2: Run the app
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Persist H2 file-based DB
VOLUME /app/data

# Copy fat JAR only (dependencies are bundled inside)
COPY --from=build /app/target/crumbs.jar crumbs.jar

# Debug: confirm contents
RUN echo "✅ Verifying /app contents:" && ls -l /app

# Optional: Check if main class exists
RUN echo "🔍 Checking CrumbsNewApplication in crumbs.jar..." && \
    jar tf crumbs.jar | grep CrumbsNewApplication || echo "❌ Main class NOT found in JAR!"

# Set JAVA_TOOL_OPTIONS here
ENV JAVA_TOOL_OPTIONS="-Xms512m -Xmx1g -XX:+UnlockDiagnosticVMOptions -XX:NativeMemoryTracking=summary"

# Expose Spring Boot default port
EXPOSE 8080

# ✅ Launch app using CMD so $PORT gets evaluated at runtime
CMD ["sh", "-c", "exec java $JAVA_TOOL_OPTIONS -jar crumbs.jar --server.address=0.0.0.0 --server.port=$PORT"]
#CMD ["java", "-jar", "crumbs.jar", "--server.address=0.0.0.0", "--server.port=$PORT"]