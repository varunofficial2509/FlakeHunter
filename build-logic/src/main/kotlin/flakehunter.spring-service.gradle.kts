// A deployable Spring Boot service. Versions of Spring-managed dependencies come from the Boot BOM.
plugins {
    id("flakehunter.java-conventions")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus") // serves /actuator/prometheus
    // Traces over OTLP to Tempo, exported only where an endpoint is set (the Helm chart). Not the
    // opentelemetry starter: it also pushes metrics over OTLP, and Prometheus already scrapes them.
    implementation("org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
