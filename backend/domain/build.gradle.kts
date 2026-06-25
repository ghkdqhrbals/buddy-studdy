plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.jpa")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    api("jakarta.persistence:jakarta.persistence-api:3.2.0")
    api(kotlin("reflect"))

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
