# 🛠 Stage 1: Build the JAR using Maven
FROM maven:3.9.4-eclipse-temurin-17 AS build
WORKDIR /app

# 1. Copy ONLY pom.xml and local libs first (moved src/frontend-app down) # ⬅️ CHANGED
COPY pom.xml .
COPY lib ./lib

# Inject custom JAR into local Maven repo before Maven resolves dependencies
RUN mkdir -p /root/.m2/repository/com/angelbroking/smartapi/smartapi-java/2.2.6 && \
    cp lib/smartapi-java-2.2.6.jar /root/.m2/repository/com/angelbroking/smartapi/smartapi-java/2.2.6/smartapi-java-2.2.6.jar && \
    echo '<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd"><modelVersion>4.0.0</modelVersion><groupId>com.angelbroking.smartapi</groupId><artifactId>smartapi-java</artifactId><version>2.2.6</version><packaging>jar</packaging></project>' \
    > /root/.m2/repository/com/angelbroking/smartapi/smartapi-java/2.2.6/smartapi-java-2.2.6.pom

# Inject New Samco SDK 3.2.0 JAR into local container Maven repo
RUN mkdir -p /root/.m2/repository/io/samco/samco-bridge-java/3.2.0 && \
    cp lib/samco-bridge-java-3.2.0.jar /root/.m2/repository/io/samco/samco-bridge-java/3.2.0/samco-bridge-java-3.2.0.jar && \
    echo '<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd"><modelVersion>4.0.0</modelVersion><groupId>io.samco</groupId><artifactId>samco-bridge-java</artifactId><version>3.2.0</version><packaging>jar</packaging></project>' \
    > /root/.m2/repository/io/samco/samco-bridge-java/3.2.0/samco-bridge-java-3.2.0.pom

# Cache internet dependencies offline to prevent re-downloading on every code edit
RUN mvn dependency:go-offline -B                                           

# 2. Copy application source and compile ONLY after dependencies are cached
COPY src ./src                                                             
COPY frontend-app ./frontend-app                                          
RUN mvn clean package -DskipTests

# 🚀 Stage 2: Run the app
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/crumbs.jar crumbs.jar

# Replaced old diagnostics & uncapped memory with strict off-heap & thread limits
ENV JAVA_TOOL_OPTIONS="-Xms128m -Xmx350m -XX:MaxMetaspaceSize=160m -Xss256k -XX:MaxDirectMemorySize=64m -XX:+UseSerialGC -XX:+UseContainerSupport"

EXPOSE 8080
CMD ["sh", "-c", "exec java \
  -Dhttp.proxyHost=${PROXY_HOST} \
  -Dhttp.proxyPort=${PROXY_PORT} \
  -Dhttps.proxyHost=${PROXY_HOST} \
  -Dhttps.proxyPort=${PROXY_PORT} \
  -jar crumbs.jar"]