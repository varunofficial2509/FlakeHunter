// Runs inside the runner image after the tests: reads Surefire XML, publishes test.results and run.completed.
// Plain Java, no Spring: it is a short-lived command, so it starts fast and stays small (ADR 0002).
plugins {
    id("flakehunter.java-conventions")
    application
}

application {
    mainClass = "dev.flakehunter.runner.publisher.Publisher"
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":libs:events"))
    implementation("org.apache.kafka:kafka-clients")
    implementation("tools.jackson.core:jackson-databind")
    runtimeOnly("org.slf4j:slf4j-simple") // kafka-clients logs through SLF4J

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
