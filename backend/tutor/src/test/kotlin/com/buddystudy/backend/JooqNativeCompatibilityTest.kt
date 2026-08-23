package com.buddystudy.backend

import org.assertj.core.api.Assertions.assertThat
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test

class JooqNativeCompatibilityTest {
    @Test
    fun `jooq DSL initializes and renders MySQL SQL`() {
        val sql = DSL.using(SQLDialect.MYSQL)
            .selectOne()
            .sql

        assertThat(sql).isEqualTo("select 1 as `one`")
    }
}
