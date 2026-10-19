plugins {
    id("flakehunter.java-conventions")
    `java-library`
}

// This jar is loaded into the target repository's test JVM, so it compiles against JUnit 5
// and leaves the JUnit jars to the target build (compileOnly) instead of shipping its own.
dependencies {
    compileOnly(platform(libs.junit5.runner.bom))
    compileOnly("org.junit.jupiter:junit-jupiter-api")
    compileOnly("org.junit.platform:junit-platform-launcher")

    testImplementation(platform(libs.junit5.runner.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.platform:junit-platform-launcher")
}
