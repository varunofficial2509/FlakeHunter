# check=skip=InvalidDefaultArgInFrom
# (The image tags have no defaults on purpose: they live only in deploy/images.env, passed by build.sh.)

# Runner base image: the target's JDK + Maven, plus the publisher on its own Java 21 runtime (ADR 0002, 0003).
# One Dockerfile, built once per JDK by build.sh; nothing in it depends on the project being tested.
# Build context: runner/ (after ./gradlew :runner:publisher:installDist).
ARG MAVEN_IMAGE
ARG PUBLISHER_JRE_IMAGE

FROM ${PUBLISHER_JRE_IMAGE} AS publisher-jre

FROM ${MAVEN_IMAGE}

# Kafka 4 clients need Java 11+, but targets like fastjson need JDK 8, so the publisher brings its own runtime.
COPY --from=publisher-jre /opt/java/openjdk /opt/publisher-jre
COPY publisher/build/install/publisher /opt/publisher
COPY images/entrypoint.sh /opt/flakehunter/entrypoint.sh

# The tests are untrusted code: never run them as root (tech doc section 11).
RUN useradd --uid 10001 --create-home runner \
    && mkdir /workspace && chown runner /workspace \
    && chmod 0755 /opt/flakehunter/entrypoint.sh
USER 10001
WORKDIR /workspace

ENTRYPOINT ["/opt/flakehunter/entrypoint.sh"]
