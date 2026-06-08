package com.buddystuddy.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@SpringBootApplication
class BuddyStuddyBackendApplication

fun main(args: Array<String>) {
    runApplication<BuddyStuddyBackendApplication>(*args)
}
