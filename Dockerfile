FROM eclipse-temurin:21-jre

WORKDIR /app
COPY marketing-agent-app/target/marketing-agent-app-0.0.1-SNAPSHOT.jar /app/marketing-agent.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/marketing-agent.jar"]
