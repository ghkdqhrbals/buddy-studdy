package com.buddystudy.backend.common.adapter.outbound.persistence

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class JooqR2dbcConfigurationTest {
    @Test
    fun `jooq linkage errors become non-fatal exceptions with their cause preserved`() {
        val linkageError = NoClassDefFoundError("Could not initialize class org.jooq.impl.DefaultDSLContext")

        assertThatThrownBy {
            translateJooqLinkageError<Unit> {
                throw linkageError
            }
        }
            .isInstanceOf(JooqRuntimeInitializationException::class.java)
            .hasMessageContaining("NoClassDefFoundError")
            .hasMessageContaining("org.jooq.impl.DefaultDSLContext")
            .hasCause(linkageError)
    }
}
