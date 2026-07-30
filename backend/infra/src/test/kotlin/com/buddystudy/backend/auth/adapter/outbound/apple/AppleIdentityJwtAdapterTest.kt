package com.buddystudy.backend.auth.adapter.outbound.apple

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AppleIdentityJwtAdapterTest {
    @Test
    fun `malformed identity token is rejected`(): Unit = runBlocking {
        val adapter = AppleIdentityJwtAdapter("io.github.ghkdqhrbals.StudyMate")

        assertThat(adapter.verify("not-a-jwt")).isNull()
    }
}
