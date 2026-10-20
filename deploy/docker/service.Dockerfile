# One image per Spring service. Build the jar first (deploy/k3d/up.sh does both):
#   ./gradlew :services:<name>:bootJar
#   docker build -f deploy/docker/service.Dockerfile --build-arg JRE_IMAGE=... --build-arg SERVICE=<name> .
ARG JRE_IMAGE
FROM ${JRE_IMAGE}
ARG SERVICE
WORKDIR /app
COPY services/${SERVICE}/build/libs/${SERVICE}-0.0.1-SNAPSHOT.jar app.jar
USER 10001
# Heap as a share of the container's memory limit, so the chart's limit is the only knob.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"
ENTRYPOINT ["java", "-jar", "app.jar"]
