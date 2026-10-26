FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew
COPY src ./src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 portfolio && useradd --uid 10001 --gid portfolio --no-create-home portfolio
COPY --from=build --chown=10001:10001 /workspace/build/libs/*-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
