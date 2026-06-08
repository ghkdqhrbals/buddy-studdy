pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven {
            name = "GitHubPackagesRedisStreamCoordinator"
            url = uri("https://maven.pkg.github.com/ghkdqhrbals/redis-stream-coordinator")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GHCR_USERNAME"))
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .orElse("ghkdqhrbals")
                    .get()
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GHCR_TOKEN"))
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .orElse("")
                    .get()
            }
        }
        flatDir {
            dirs("libs")
        }
    }
}

rootProject.name = "buddystuddy-backend"
