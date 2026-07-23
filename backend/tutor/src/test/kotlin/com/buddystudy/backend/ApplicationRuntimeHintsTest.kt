package com.buddystudy.backend

import com.buddystudy.study.domain.entity.QuestionEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates

class ApplicationRuntimeHintsTest {
    @Test
    fun `discovers table entities used by entity template repositories`() {
        val registrar = ApplicationRuntimeHints()

        val entities = registrar.persistentEntityTypes(javaClass.classLoader)

        assertThat(entities).contains(QuestionEntity::class.java.name)
    }

    @Test
    fun `registers constructors and fields for persistent entities`() {
        val hints = RuntimeHints()

        ApplicationRuntimeHints().registerHints(hints, javaClass.classLoader)

        assertThat(
            RuntimeHintsPredicates.reflection()
                .onType(QuestionEntity::class.java)
                .withMemberCategories(
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.DECLARED_FIELDS,
                )
                .test(hints),
        ).isTrue()
    }
}
