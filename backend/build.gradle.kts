plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.spring") version "2.2.21" apply false
    id("org.springframework.boot") version "4.0.6" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("org.graalvm.buildtools.native") version "0.11.3" apply false
    id("org.jooq.jooq-codegen-gradle") version "3.21.6" apply false
    jacoco
}

group = "com.buddystudy"
version = "0.2.0"

subprojects {
    group = rootProject.group
    version = rootProject.version
    extra["jooq.version"] = "3.21.6"

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(24)
    }
}

tasks.register("bootJar") {
    dependsOn(":tutor:bootJar")
}

tasks.register("test") {
    dependsOn(subprojects.map { "${it.path}:test" })
}
