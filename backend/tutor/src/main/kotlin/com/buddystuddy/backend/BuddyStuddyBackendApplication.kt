package com.buddystuddy.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@EntityScan("com.buddystuddy")
@SpringBootApplication(scanBasePackages = ["com.buddystuddy"])
class BuddyStuddyBackendApplication

fun main(args: Array<String>) {
    runApplication<BuddyStuddyBackendApplication>(*args)
}
