package com.buddystudy.backend

import com.buddystudy.backend.admin.management.application.model.AdminMembershipTierResponse
import com.buddystudy.backend.admin.management.application.model.AdminUserPageResponse
import com.buddystudy.backend.admin.management.application.model.AdminUserSummary
import com.buddystudy.backend.community.application.model.CommunityQuestionResponse
import com.buddystudy.backend.community.application.model.CommunityQuestionsResponse
import com.buddystudy.backend.notification.application.model.AppNotificationsResponse
import com.buddystudy.backend.stats.application.model.StatsResponse
import com.buddystudy.backend.study.application.model.StudyPageResponse
import com.buddystudy.backend.study.application.port.outbound.AiCriterionAssessment
import com.buddystudy.backend.study.application.port.outbound.AiGradingAssessment
import com.buddystudy.backend.study.application.port.outbound.AiGradingCriterion
import com.buddystudy.backend.study.application.port.outbound.AiGradingRubric
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

    @Test
    fun `registers admin management responses for native JSON serialization`() {
        val hints = RuntimeHints()

        ApplicationRuntimeHints().registerHints(hints, javaClass.classLoader)

        listOf(
            AdminMembershipTierResponse::class.java,
            AdminUserPageResponse::class.java,
            AdminUserSummary::class.java,
        ).forEach { responseType ->
            assertThat(
                RuntimeHintsPredicates.reflection()
                    .onType(responseType)
                    .withMemberCategories(
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.DECLARED_FIELDS,
                        MemberCategory.INVOKE_PUBLIC_METHODS,
                    )
                    .test(hints),
            ).isTrue()
        }
    }

    @Test
    fun `registers application response models for native JSON serialization`() {
        val hints = RuntimeHints()

        ApplicationRuntimeHints().registerHints(hints, javaClass.classLoader)

        listOf(
            CommunityQuestionsResponse::class.java,
            CommunityQuestionResponse::class.java,
            StudyPageResponse::class.java,
            StatsResponse::class.java,
            AppNotificationsResponse::class.java,
        ).forEach { responseType ->
            assertThat(
                RuntimeHintsPredicates.reflection()
                    .onType(responseType)
                    .withMemberCategories(
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.DECLARED_FIELDS,
                        MemberCategory.INVOKE_PUBLIC_METHODS,
                    )
                    .test(hints),
            ).isTrue()
        }
    }

    @Test
    fun `registers jooq decfloat array used during native data type initialization`() {
        val hints = RuntimeHints()

        ApplicationRuntimeHints().registerHints(hints, javaClass.classLoader)

        val decfloatArrayType = Class.forName("[Lorg.jooq.Decfloat;")
        assertThat(
            RuntimeHintsPredicates.reflection()
                .onType(decfloatArrayType)
                .test(hints),
        ).isTrue()
    }

    @Test
    fun `registers question notification metadata for native Jackson serialization`() {
        val hints = RuntimeHints()

        ApplicationRuntimeHints().registerHints(hints, javaClass.classLoader)

        val metadataType = Class.forName(
            "com.buddystudy.backend.study.application.service.QuestionNotificationMetadata",
        )
        assertThat(
            RuntimeHintsPredicates.reflection()
                .onType(metadataType)
                .withMemberCategories(
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.DECLARED_FIELDS,
                    MemberCategory.INVOKE_PUBLIC_METHODS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                )
                .test(hints),
        ).isTrue()
    }

    @Test
    fun `registers grading metadata types for native Jackson serialization`() {
        val hints = RuntimeHints()

        ApplicationRuntimeHints().registerHints(hints, javaClass.classLoader)

        listOf(
            AiGradingAssessment::class.java,
            AiCriterionAssessment::class.java,
            AiGradingRubric::class.java,
            AiGradingCriterion::class.java,
        ).forEach { gradingType ->
            assertThat(
                RuntimeHintsPredicates.reflection()
                    .onType(gradingType)
                    .withMemberCategories(
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.DECLARED_FIELDS,
                        MemberCategory.INVOKE_PUBLIC_METHODS,
                        MemberCategory.INVOKE_DECLARED_METHODS,
                    )
                    .test(hints),
            ).isTrue()
        }
    }
}
