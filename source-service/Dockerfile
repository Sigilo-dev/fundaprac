FROM eclipse-temurin:17-jre

WORKDIR /app

COPY target/campusspace-source.jar /app/campusspace-source.jar

ENV PORT=4570
EXPOSE 4570

ENTRYPOINT ["java", "-jar", "/app/campusspace-source.jar"]
