FROM eclipse-temurin:17-jre

WORKDIR /app

COPY target/campusspace-composer.jar /app/campusspace-composer.jar

ENV PORT=4571
ENV SOURCE_SERVICE_URL=http://campusspace-source:4570
EXPOSE 4571

ENTRYPOINT ["java", "-jar", "/app/campusspace-composer.jar"]
