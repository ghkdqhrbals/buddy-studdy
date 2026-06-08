plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.jpa")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(24)
    }
}

dependencies {
    api("jakarta.persistence:jakarta.persistence-api:3.2.0")
    api(kotlin("reflect"))
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}
