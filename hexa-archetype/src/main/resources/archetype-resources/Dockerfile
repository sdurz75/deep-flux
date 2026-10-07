# Il jar si costruisce prima: `mvn -B package` (i test richiedono Docker). Volume per binari (data/images) e cache.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/*.jar app.jar
VOLUME /app/data
EXPOSE 7070
ENTRYPOINT ["java", "-jar", "app.jar"]
