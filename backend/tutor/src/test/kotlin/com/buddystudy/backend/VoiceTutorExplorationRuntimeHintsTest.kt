package com.buddystudy.backend

import com.buddystudy.backend.voice.application.model.VoiceTutorExplorationResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorLearningExchangeResponse
import com.buddystudy.backend.voice.application.model.VoiceTutorResultResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates

class VoiceTutorExplorationRuntimeHintsTest {
    @Test
    fun `native application model scanning includes every nested voice result response`() {
        val hints = RuntimeHints()
        ApplicationRuntimeHints().registerHints(hints, javaClass.classLoader)
        listOf(VoiceTutorResultResponse::class.java, VoiceTutorExplorationResponse::class.java, VoiceTutorLearningExchangeResponse::class.java)
            .forEach { type ->
                assertThat(RuntimeHintsPredicates.reflection().onType(type).withMemberCategories(
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.DECLARED_FIELDS,
                    MemberCategory.INVOKE_PUBLIC_METHODS,
                ).test(hints)).describedAs(type.simpleName).isTrue()
            }
    }
}
