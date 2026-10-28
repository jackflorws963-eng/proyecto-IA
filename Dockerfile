FROM maven:3.9-eclipse-temurin-21 AS compilacion
WORKDIR /src
COPY backend/pom.xml .
RUN mvn -B -q dependency:go-offline
COPY backend/src src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --create-home smartstock
WORKDIR /app
COPY --from=compilacion /src/target/smartstock-iot.jar app.jar
USER smartstock
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.awt.headless=true" \
    SMARTSTOCK_HOME=/home/smartstock/.smartstock
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3 && grep -q UP <&3'
ENTRYPOINT ["java", "-jar", "app.jar", "--servidor"]
