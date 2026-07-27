package com.buddystudy.backend.study

import com.buddystudy.backend.study.application.content.MarkdownContentPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MarkdownContentPolicyTest {
    @Test
    fun `plain text projection removes markdown used by push notifications`() {
        val markdown = """
            ## Redis 점검

            - `INFO memory`를 실행합니다.
            - [공식 문서](https://redis.io/docs)를 확인합니다.

            ```sql
            SELECT * FROM metrics;
            ```
        """.trimIndent()

        assertThat(MarkdownContentPolicy.plainText(markdown))
            .isEqualTo("Redis 점검 INFO memory를 실행합니다. 공식 문서를 확인합니다. SELECT * FROM metrics;")
    }

    @Test
    fun `plain text projection preserves ordinary text`() {
        assertThat(MarkdownContentPolicy.plainText("WHERE 절에서 status = 'FAILED'를 찾으세요."))
            .isEqualTo("WHERE 절에서 status = 'FAILED'를 찾으세요.")
    }
}
