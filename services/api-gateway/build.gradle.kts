plugins {
    id("flakehunter.spring-service")
}

dependencies {
    implementation(platform(libs.spring.cloud.bom))
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
}
