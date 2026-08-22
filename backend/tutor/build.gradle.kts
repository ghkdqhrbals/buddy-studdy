plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.graalvm.buildtools.native")
    jacoco
}

tasks.named<org.springframework.boot.gradle.tasks.aot.ProcessAot>("processAot") {
    args("--spring.profiles.active=aot")
    systemProperties(
        mapOf(
            "spring.r2dbc.url" to "r2dbc:mysql://localhost:3306/buddystudy?serverZoneId=UTC",
            "spring.r2dbc.username" to "buddystudy",
            "spring.r2dbc.password" to "aot-build-only",
            "buddystudy.mcp.enabled" to "true",
            "SMTP_HOST" to "smtp.aot.invalid",
            "SMTP_PORT" to "587",
            "SMTP_USERNAME" to "aot@invalid.example",
            "SMTP_PASSWORD" to "aot-build-only",
            "SMTP_FROM" to "BuddyStudy <aot@invalid.example>",
        ),
    )
}

tasks.named<org.springframework.boot.gradle.tasks.aot.ProcessTestAot>("processTestAot") {
    systemProperties(
        mapOf(
            "SMTP_HOST" to "smtp.test.invalid",
            "SMTP_PORT" to "587",
            "SMTP_USERNAME" to "test@invalid.example",
            "SMTP_PASSWORD" to "test-only",
            "SMTP_FROM" to "BuddyStudy <test@invalid.example>",
        ),
    )
}

tasks.register("patchNativeReachabilityMetadata") {
    val aotMetadata = layout.buildDirectory.file("resources/aot/META-INF/native-image/com.buddystudy/tutor/reachability-metadata.json")
    dependsOn("processAotResources")
    inputs.file(aotMetadata)
    outputs.file(aotMetadata)

    doLast {
        val file = aotMetadata.get().asFile
        if (!file.exists()) {
            return@doLast
        }
        val text = file.readText()
        val patched = text
            .replace(
                Regex("""(?s),\s*\{\s*"bundle"\s*:\s*"org\.springframework\.security\.messages"\s*\}"""),
                "",
            )
            .replace(
                Regex("""(?s),\s*\{\s*"bundle"\s*:\s*"sun\.util\.resources\.LocaleNames"\s*\}"""),
                "",
            )
        if (patched != text) {
            file.writeText(patched)
        }
    }
}

tasks.named("generateResourcesConfigFile") {
    dependsOn("patchNativeReachabilityMetadata")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("buddystudy-backend.jar")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencyManagement {
    imports {
        mavenBom("io.awspring.cloud:spring-cloud-aws-dependencies:4.0.2")
        mavenBom("org.springframework.ai:spring-ai-bom:2.0.1")
        mavenBom("io.modelcontextprotocol.sdk:mcp-bom:2.0.1")
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":infra"))

    implementation(kotlin("reflect"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework:spring-aop")
    implementation("org.aspectj:aspectjweaver")
    implementation("org.springframework.ai:mcp-spring-webflux")
    implementation("io.modelcontextprotocol.sdk:mcp-json-jackson2")
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:3.0.3")
    implementation("org.flywaydb:flyway-core")
    implementation("com.mysql:mysql-connector-j")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-secrets-manager")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    runtimeOnly("io.asyncer:r2dbc-mysql")
    runtimeOnly("io.r2dbc:r2dbc-pool")
    runtimeOnly("org.flywaydb:flyway-mysql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jooq:jooq:3.21.6")
    testImplementation("io.r2dbc:r2dbc-h2")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testImplementation("org.testcontainers:mysql:1.21.3")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // The tutor suite boots several distinct Spring contexts. Keep the worker from
    // exhausting the default 512 MiB heap during the full integration-test run.
    maxHeapSize = "1g"
}

jacoco {
    toolVersion = "0.8.14"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("buddystudy-backend")
            buildArgs.add("--no-fallback")
            buildArgs.add("--parallelism=2")
            buildArgs.add("-Ob")
            buildArgs.add("-J-Xmx12g")
        }
    }
}
