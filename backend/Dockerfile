FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B package -DskipTests
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 foodify && mkdir -p /app/data && chown -R foodify /app
COPY --from=build /workspace/target/foodify-backend-0.0.1-SNAPSHOT.jar app.jar
USER foodify
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=65","-jar","app.jar"]
