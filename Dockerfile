# Build with Maven on JDK 27, run on the JRE 27. Both images pinned by digest.
FROM maven:3.10.0-eclipse-temurin-27@sha256:095c056353550ea93605f9cbd200a7cbbc489a1989eeec8b8600749a157a0096 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package

FROM eclipse-temurin:27-jre@sha256:d6bfa738df69e8dec9061b3e26f2ae564d92ad169faa36086dcfa33f0a901d35
WORKDIR /app
COPY --from=build /src/target/demo.jar /app/demo.jar
# The scenario runner is the MCP client; it starts every server as a stdio subprocess of itself.
ENTRYPOINT ["java", "-jar", "/app/demo.jar"]
