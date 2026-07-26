plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
    id("org.jooq.jooq-codegen-gradle")
}

dependencyManagement {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
        mavenBom("org.springframework.ai:spring-ai-bom:2.0.0")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    api(project(":application"))

    implementation(kotlin("reflect"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("io.lettuce:lettuce-core")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.springframework:spring-aop")
    implementation("org.aspectj:aspectjweaver")
    implementation("org.springframework.security:spring-security-core")
    implementation("org.springdoc:springdoc-openapi-starter-webflux-api:3.0.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("org.jooq:jooq:3.21.6")
    implementation("org.jooq:jooq-kotlin-coroutines:3.21.6")

    runtimeOnly("io.asyncer:r2dbc-mysql")
    runtimeOnly("io.r2dbc:r2dbc-pool")
    runtimeOnly("io.netty:netty-resolver-dns-native-macos") {
        artifact {
            classifier = "osx-aarch_64"
        }
    }

    jooqCodegen("org.jooq:jooq-meta-extensions:3.21.6")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.r2dbc:r2dbc-h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val outboxDdlFile = projectDir.resolve("src/main/resources/jooq/redis-event-outbox.sql")

jooq {
    configuration {
        generator {
            database {
                name = "org.jooq.meta.extensions.ddl.DDLDatabase"
                includes = "redis_event_outbox"
                properties {
                    property {
                        key = "scripts"
                        value = outboxDdlFile.absolutePath
                    }
                    property {
                        key = "sort"
                        value = "flyway"
                    }
                    property {
                        key = "defaultNameCase"
                        value = "lower"
                    }
                    property {
                        key = "parseIgnoreComments"
                        value = "true"
                    }
                }
            }
            generate {
                isDeprecated = false
                isRecords = true
                isPojos = false
                isDaos = false
            }
            target {
                packageName = "com.buddystudy.backend.jooq"
                directory = layout.buildDirectory.dir("generated-sources/jooq").get().asFile.absolutePath
            }
        }
    }
}

tasks.named("jooqCodegen") {
    inputs.file(outboxDdlFile)
}

tasks.named("compileKotlin") {
    dependsOn(tasks.named("jooqCodegen"))
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
