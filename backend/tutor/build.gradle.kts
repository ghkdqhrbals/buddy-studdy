plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.graalvm.buildtools.native")
    jacoco
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

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencyManagement {
    imports {
        mavenBom("io.awspring.cloud:spring-cloud-aws-dependencies:4.0.2")
        mavenBom("org.springframework.ai:spring-ai-bom:2.0.0")
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":infra"))

    implementation(kotlin("reflect"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework:spring-aop")
    implementation("org.aspectj:aspectjweaver")
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:3.0.3")
    implementation("org.flywaydb:flyway-core")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-secrets-manager")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testImplementation("org.testcontainers:postgresql:1.21.3")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
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
        }
    }
}
