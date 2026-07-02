FROM gcr.io/hwyhaul-backend/custom-images/openjdk:21-jdk

COPY ./itrade-load-creation-agent-infrastructure/target/itrade-load-creation-agent-infrastructure_*.jar /tmp/itrade-load-creation-agent.jar

EXPOSE 8080

CMD ["java", "-jar", "-Duser.timezone=UTC", "-Denv=${ENV}", "-Dspring.profiles.active=${ENV}", "/tmp/itrade-load-creation-agent.jar"]