🏃 Getting Started
Prerequisites
JDK 17 or higher

Maven 3.6+

PostgreSQL Database

Installation & Run
Clone the repository:

Bash
git clone <your-repo-url>
cd crumbs
Configure for Local Run:
Open src/main/resources/application.properties and ensure scheduling.enabled is set to false if you do not want automated trades to trigger during development.

Build the project:

Bash
./mvnw clean install
Run the application:

Bash
./mvnw spring-boot:run
Access API Docs:
Navigate to http://localhost:8080/swagger-ui.html to view and test available endpoints.

📁 Project Structure Highlights
Main Class: com.crumbs.trade.CrumbsNewApplication

Broker Logic: com.crumbs.trade.broker

Entities: com.crumbs.trade.entity

Resources: Contains application.properties for configuration and logback-spring.xml for logging.

⚠️ Important Notes
Timezone: The application defaults to Asia/Kolkata for all scheduling operations.

Production Safety: application.properties defaults to production values. Always verify scheduling.enabled and spring.devtools.restart.enabled before deploying.
