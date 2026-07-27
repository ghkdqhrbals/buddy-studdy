package com.buddystudy.backend.profile.adapter.inbound.web.dto

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

class ProfileRequestsBindingTest {
    @Test
    fun `profile update binds avatar fields from json`() {
        WebTestClient.bindToController(BindingController())
            .build()
            .patch()
            .uri("/profile")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """
                {
                  "avatarSymbolName": "pixel-cat",
                  "avatarColorSeed": "avatar-color-teal",
                  "avatarMode": "PIXEL",
                  "allowPublicQuestions": false
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.avatarSymbolName").isEqualTo("pixel-cat")
            .jsonPath("$.avatarColorSeed").isEqualTo("avatar-color-teal")
            .jsonPath("$.avatarMode").isEqualTo("PIXEL")
            .jsonPath("$.allowPublicQuestions").isEqualTo(false)
    }

    @RestController
    private class BindingController {
        @PatchMapping("/profile")
        fun update(@RequestBody body: ProfileUpdateRequest): ProfileUpdateRequest = body
    }
}
