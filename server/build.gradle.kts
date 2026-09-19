plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "money.hejje"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

extra["springModulithVersion"] = "1.4.13"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("com.bucket4j:bucket4j_jdk17-core:8.19.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-mail") // email notifications (M5.5)
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jdbc")
    implementation("org.springframework.modulith:spring-modulith-events-core") // EventPublicationRegistry: the SIM replay waits for in-flight events (M7.2)
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("net.logstash.logback:logstash-logback-encoder:8.1")
    implementation("com.zerodhatech.kiteconnect:kiteconnect:4.0.1")
    implementation("org.duckdb:duckdb_jdbc:1.5.5.1")
    runtimeOnly("org.postgresql:postgresql")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
    }
}

springBoot {
    buildInfo()
}

// Bundled strategy definitions (repo strategies/) ship inside the jar under classpath:strategies/.
tasks.processResources {
    from("../strategies") {
        into("strategies")
        include("*.yaml")
    }
    // Context config (regime thresholds, universes) ships inside the jar as the fallback for config/ overrides.
    from("../config") {
        include("regime.yaml", "pulse.yaml", "events.yaml", "news-sources.yaml", "aliases.yaml")
    }
    from("../config/events") {
        into("events")
        include("*.yaml")
    }
    from("../config/universe") {
        into("universe")
        include("*.yaml")
    }
    // The strategy DSL reference is part of the NL strategy builder prompt (M4.6), so the prompt never drifts from the doc.
    from("../docs") {
        into("prompts")
        include("strategy-dsl.md")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
