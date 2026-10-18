plugins {
    id("flakehunter.spring-service")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation(platform(libs.fabric8.bom))
    implementation(libs.fabric8.client)
}
