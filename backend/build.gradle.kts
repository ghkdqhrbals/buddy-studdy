plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.spring") version "2.2.21" apply false
    kotlin("plugin.jpa") version "2.2.21" apply false
    id("org.springframework.boot") version "4.0.6" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    jacoco
}

group = "com.buddystuddy"
version = "0.2.0"

subprojects {
    group = rootProject.group
    version = rootProject.version
}

tasks.register("bootJar") {
    dependsOn(":tutor:bootJar")
}

tasks.register("test") {
    dependsOn(subprojects.map { "${it.path}:test" })
}
