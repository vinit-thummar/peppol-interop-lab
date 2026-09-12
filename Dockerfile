FROM maven:3.9.11-eclipse-temurin-21 AS build
ARG LAB_VERSION=0.1.0-SNAPSHOT
WORKDIR /source
COPY pom.xml ./
COPY peppol-lab-api/pom.xml peppol-lab-api/pom.xml
COPY peppol-lab-core/pom.xml peppol-lab-core/pom.xml
COPY peppol-lab-adapters/pom.xml peppol-lab-adapters/pom.xml
COPY peppol-lab-cli/pom.xml peppol-lab-cli/pom.xml
RUN mvn -B -ntp -Drevision="${LAB_VERSION}" dependency:go-offline
COPY peppol-lab-api peppol-lab-api
COPY peppol-lab-core peppol-lab-core
COPY peppol-lab-adapters peppol-lab-adapters
COPY peppol-lab-cli peppol-lab-cli
RUN mvn -B -ntp -Drevision="${LAB_VERSION}" clean verify

FROM eclipse-temurin:21-jre
ARG LAB_VERSION=0.1.0-SNAPSHOT
LABEL org.opencontainers.image.source="https://github.com/vinit-thummar/peppol-interop-lab"
LABEL org.opencontainers.image.licenses="Apache-2.0"
LABEL org.opencontainers.image.version="${LAB_VERSION}"
RUN useradd --system --uid 10001 --create-home peppollab
COPY --from=build /source/peppol-lab-cli/target/peppol-lab.jar /opt/peppol-lab/peppol-lab.jar
USER 10001
WORKDIR /work
ENTRYPOINT ["java", "-jar", "/opt/peppol-lab/peppol-lab.jar"]
CMD ["run", "--output", "/work/reports"]
