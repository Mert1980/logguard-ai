# Build stage — compiles the Spring Boot fat jar (no local JDK needed on the host).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Cache dependencies in their own layer: only re-resolved when pom.xml changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# Runtime stage — slim JRE image running the packaged jar.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
