# Build: compila o jar com Maven (testes já rodam no CI)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

# Runtime: só o JRE + o jar
FROM eclipse-temurin:21-jre
# Roda sem root: se a aplicação for comprometida, o processo não tem privilégio no container
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build --chown=app:app /app/target/ms-inventra-api-*.jar app.jar
USER app
# O Render injeta a porta em $PORT (lida pelo server.port do application.properties)
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
