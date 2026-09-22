package com.buddystudy.backend.community

import kotlinx.coroutines.runBlocking

import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.common.domain.SupportedLanguage
import com.buddystudy.backend.auth.Principal
import com.buddystudy.backend.auth.application.port.outbound.UserPort
import com.buddystudy.backend.common.application.error.ApiErrorCode
import com.buddystudy.backend.common.application.error.ApiException
import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.common.application.outbox.AfterCommitPort
import com.buddystudy.backend.common.application.outbox.OutboxPublishSummary
import com.buddystudy.backend.common.application.outbox.OutboxReference
import com.buddystudy.backend.common.application.outbox.PublishOutboxUseCase
import com.buddystudy.backend.community.application.port.outbound.QuestionCommentPort
import com.buddystudy.backend.community.application.port.outbound.FeedbackPort
import com.buddystudy.backend.community.application.port.outbound.QuestionLikePort
import com.buddystudy.backend.community.application.port.outbound.ReportPort
import com.buddystudy.backend.community.application.port.outbound.UserBlockPort
import com.buddystudy.backend.community.application.port.outbound.NativeAdvertisementPort
import com.buddystudy.backend.community.application.port.outbound.NativeAdvertisementCampaignPerformance
import com.buddystudy.backend.community.application.port.outbound.NativeAdvertisementUserRankingSignals
import com.buddystudy.backend.community.application.port.outbound.NativeAdvertisementViewPublishPort
import com.buddystudy.backend.community.application.port.outbound.NativeAdEligibilityPort
import com.buddystudy.backend.community.application.port.outbound.NativeAdSlotPort
import com.buddystudy.backend.community.application.port.outbound.NativeAdSlotReservation
import com.buddystudy.backend.community.application.model.PublicFeedSort
import com.buddystudy.backend.community.application.model.PublicFeedScope
import com.buddystudy.backend.community.application.model.NativeAdvertisementViewedEvent
import com.buddystudy.backend.community.application.service.CommunityService
import com.buddystudy.backend.community.application.port.inbound.ReportQuestionCommand
import com.buddystudy.backend.community.application.port.outbound.PublicQuestionReactionPublishPort
import com.buddystudy.backend.community.application.port.outbound.PublicQuestionViewLocalization
import com.buddystudy.backend.notification.application.port.inbound.NotificationRequestCommand
import com.buddystudy.backend.notification.application.port.inbound.PublishNotificationUseCase
import com.buddystudy.backend.study.application.port.outbound.QuestionPort
import com.buddystudy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystudy.backend.study.application.service.VoiceRecordContentProjector
import com.buddystudy.backend.study.application.model.TranslationState
import com.buddystudy.backend.localization.application.port.VoiceStudyLearningLocalizationPort
import com.buddystudy.backend.localization.application.port.UnavailableVoiceStudyLearningLocalizationPort
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.community.domain.entity.FeedbackEntity
import com.buddystudy.community.domain.entity.QuestionLikeEntity
import com.buddystudy.community.domain.entity.ReportEntity
import com.buddystudy.community.domain.entity.UserBlockEntity
import com.buddystudy.community.domain.entity.NativeAdvertisementCampaignEntity
import com.buddystudy.community.domain.entity.NativeAdvertisementSelectionEntity
import com.buddystudy.community.domain.entity.NativeAdPlacementPolicyEntity
import com.buddystudy.community.domain.entity.NativeAdSlotEntity
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionStatus
import com.buddystudy.study.domain.entity.QuestionStatsEntity
import com.buddystudy.study.domain.entity.QuestionSource
import com.buddystudy.study.domain.entity.StudyRecordType
import com.buddystudy.voice.domain.VoiceStudyLearningRecord
import com.buddystudy.voice.domain.VoiceTutorExchangeKind
import com.buddystudy.backend.test.EmptyContentLocalizationPort
import com.buddystudy.backend.test.PassthroughLanguageDetector
import com.buddystudy.backend.test.RecordingLocalizationRequests
import com.buddystudy.backend.test.RecordingContentTranslationEventPort
import com.buddystudy.backend.localization.application.service.ContentTranslationRequestManager
import com.buddystudy.backend.localization.application.model.LocalizableContentType
import com.buddystudy.backend.localization.application.model.RecordLocalizationSnapshot
import com.buddystudy.backend.localization.application.model.TextLocalizationSnapshot
import com.buddystudy.backend.localization.application.policy.ContentSourceHashPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.Optional

class CommunityServiceTest {
    private val users = FakeUserPort()
    private val userBlocks = FakeUserBlockPort()
    private val likes = FakeQuestionLikePort()
    private val questions = FakeQuestionPort(userBlocks, likes)
    private val questionStats = FakeQuestionStatsPort()
    private val comments = FakeQuestionCommentPort(userBlocks)
    private val nativeAdvertisements = FakeNativeAdvertisementPort()
    private val nativeAdvertisementViews = FakeNativeAdvertisementViewPublisher()
    private val nativeAdEligibility = FakeNativeAdEligibilityPort()
    private val nativeAdSlots = FakeNativeAdSlotPort()
    private val notificationPublisher = FakeNotificationPublisher()
    private val reactionPublisher = FakeReactionPublisher()
    private val translationEvents = RecordingContentTranslationEventPort()
    private val translationPublisher = RecordingOutboxPublisher()
    private val reports = FakeReportPort()
    private val voiceLocalizations = FakeVoiceLocalizations()
    private val ordinaryLocalizations = RecordingOrdinaryLocalizations()
    private val ordinaryRequests = RecordingLocalizationRequests()
    private val service = CommunityService(
        users = users,
        questions = questions,
        questionStats = questionStats,
        likes = likes,
        comments = comments,
        reports = reports,
        userBlocks = userBlocks,
        feedbacks = FakeFeedbackPort(),
        nativeAdvertisements = nativeAdvertisements,
        nativeAdvertisementViews = nativeAdvertisementViews,
        nativeAdEligibility = nativeAdEligibility,
        nativeAdSlots = nativeAdSlots,
        reactions = reactionPublisher,
        notifications = notificationPublisher,
        languageDetector = PassthroughLanguageDetector(),
        contentLocalizations = ordinaryLocalizations,
        localizationRequests = ordinaryRequests,
        translationRequestManager = ContentTranslationRequestManager(
            EmptyContentLocalizationPort(),
            translationEvents,
        ),
        afterCommit = ImmediateAfterCommit(),
        outboxPublisher = translationPublisher,
        voiceRecordProjector = VoiceRecordContentProjector(voiceLocalizations),
    )
    private val principal = Principal(userId = 7, deviceId = "dev-1", sessionId = 1, anonymous = false)

    @Test
    fun `public list detail liked and search use exact authenticated row ownership rather than author presentation`(): Unit = runBlocking {
        users.rows += UserEntity(id = principal.userId, providerId = "owner", displayName = "Same name")
        users.rows += UserEntity(id = 10, providerId = "other", displayName = "Same name")
        questions.rows += publicQuestion(100, principal.userId, "Redis")
        questions.rows += publicQuestion(101, 10, "Redis")
        likes.rows += QuestionLikeEntity(questionId = 100, userId = principal.userId)
        likes.rows += QuestionLikeEntity(questionId = 101, userId = principal.userId)

        val pages = listOf(
            service.getPublicQuestions(principal, null, "ko", "original", 20, 0),
            service.getPublicQuestions(principal, "Redis", "ko", "original", 20, 0),
            service.getPublicQuestionsV2(principal, null, "ko", "original", 20, 0),
            service.getPublicQuestionsV2(principal, "Redis", "ko", "original", 20, 0),
            service.getPublicQuestionFeedV2(principal, "ko", "original", 20, 0),
            service.getLikedPublicQuestions(principal, null, "ko", "original", 20, 0),
        )
        pages.forEach { page ->
            assertThat(page.questions.associate { it.id to it.isOwnedByMe }).isEqualTo(mapOf("100" to true, "101" to false))
            assertThat(page.items.mapNotNull { it.question }.associate { it.id to it.isOwnedByMe })
                .isEqualTo(page.questions.associate { it.id to it.isOwnedByMe })
        }
        val own = service.getPublicQuestion(principal, 100, "ko", "original")
        val other = service.getPublicQuestion(principal, 101, "ko", "original")
        assertThat(own.isOwnedByMe).isTrue()
        assertThat(other.isOwnedByMe).isFalse()
        assertThat(own.author?.displayName).isEqualTo(other.author?.displayName)
        // Public author profiles intentionally omit private account status.
        assertThat(own.author?.status).isEqualTo("ANONYMOUS")
        assertThat(other.author?.status).isEqualTo("ANONYMOUS")
    }

    @Test
    fun `unauthenticated viewer never owns public rows while missing author profile cannot erase actual ownership`(): Unit = runBlocking {
        questions.rows += publicQuestion(100, principal.userId, "Redis")
        val own = service.getPublicQuestion(principal, 100, "ko", "original")
        assertThat(own.author).isNull()
        assertThat(own.isOwnedByMe).isTrue()
        assertThat(service.getPublicQuestion(null, 100, "ko", "original").isOwnedByMe).isFalse()
        assertThat(service.getPublicQuestions(null, null, "ko", "original", 20, 0).questions.single().isOwnedByMe).isFalse()
        assertThat(service.getPublicQuestionsV2(null, "Redis", "ko", "original", 20, 0).questions.single().isOwnedByMe).isFalse()
        assertThat(service.getPublicQuestionFeedV2(null, "ko", "original", 20, 0).questions.single().isOwnedByMe).isFalse()
        // This field states ownership only; permission/sign-in gates remain separate.
        assertThat(service.getPublicQuestion(principal.copy(anonymous = true), 100, "ko", "original").isOwnedByMe).isTrue()
    }

    @Test
    fun `voice record ownership is canonical and independent of author lookup on public and liked surfaces`(): Unit = runBlocking {
        val own = voiceQuestion(200, principal.userId)
        val other = voiceQuestion(201, 10)
        questions.rows += listOf(own, other)
        voiceLocalizations.rows += listOf(voiceRecord(own), voiceRecord(other))
        likes.rows += QuestionLikeEntity(questionId = own.id, userId = principal.userId)
        likes.rows += QuestionLikeEntity(questionId = other.id, userId = principal.userId)

        val detail = service.getPublicQuestion(principal, own.id, "ko", "original")
        assertThat(detail.id).isEqualTo("200")
        assertThat(detail.recordType).isEqualTo(StudyRecordType.VOICE_TUTOR)
        assertThat(detail.author).isNull()
        assertThat(detail.isOwnedByMe).isTrue()
        assertThat(service.getPublicQuestion(principal, other.id, "ko", "original").isOwnedByMe).isFalse()
        assertThat(service.getPublicQuestion(null, own.id, "ko", "original").isOwnedByMe).isFalse()
        listOf(service.getPublicQuestions(principal, null, "ko", "original", 20, 0),
            service.getLikedPublicQuestions(principal, null, "ko", "original", 20, 0)).forEach { page ->
            assertThat(page.questions.associate { it.id to it.isOwnedByMe }).isEqualTo(mapOf("200" to true, "201" to false))
        }
    }

    @Test
    fun `community endpoints that can repair voice translations are read write transactions`(): Unit {
        val methods = CommunityService::class.java.declaredMethods
            .filter { it.name == "getPublicQuestionsV2" || it.name == "getLikedPublicQuestions" }
        assertThat(methods.map { it.name }).containsExactlyInAnyOrder("getPublicQuestionsV2", "getLikedPublicQuestions")
        assertThat(methods).allSatisfy { method ->
            val transaction = method.getAnnotation(Transactional::class.java)
            assertThat(transaction).describedAs("%s transaction", method.name).isNotNull
            assertThat(transaction.readOnly).describedAs("%s readOnly", method.name).isFalse()
        }
    }

    @Test
    fun `public feed returns backend ordered typed items with advertisement deep link`(): Unit = runBlocking {
        users.rows += UserEntity(id = 10, providerId = "author", displayName = "Author")
        (100L..103L).forEach { questions.rows += publicQuestion(it, 10, "Topic $it") }
        nativeAdvertisements.campaigns += NativeAdvertisementCampaignEntity(
            id = 1,
            campaignKey = "feedback-credit",
            titleKo = "의견을 남겨주세요",
            titleEn = "Share feedback",
            titleJa = "ご意見をください",
            imageUrl = "https://thumbnail6.coupangcdn.com/example.jpg",
            affiliateDisclosureKo = "이 포스팅은 쿠팡 파트너스 활동의 일환으로, 이에 따른 일정액의 수수료를 제공받습니다.",
            affiliateDisclosureEn = "Affiliate disclosure",
            affiliateDisclosureJa = "広告開示",
            deepLink = "https://link.coupang.com/a/example",
            minimumSecondsBetweenSelections = 0,
        )

        val response = service.getPublicQuestions(principal, query = null, language = "ko", limit = 20, offset = 0)

        assertThat(response.items).hasSize(5)
        assertThat(response.items.count { it.type.name == "ADVERTISEMENT" }).isEqualTo(1)
        val advertisement = response.items.single { it.advertisement != null }.advertisement!!
        assertThat(advertisement.deepLink).isEqualTo("https://link.coupang.com/a/example")
        assertThat(advertisement.providerName).isEqualTo("쿠팡")
        assertThat(advertisement.imageUrl).contains("coupangcdn.com")
        assertThat(advertisement.affiliateDisclosure).contains("쿠팡 파트너스")
        assertThat(advertisement.selectionId).isNotBlank()
        assertThat(response.questions).hasSize(4)
        assertThat(nativeAdvertisements.selections).hasSize(1)
    }

    @Test
    fun `liked public questions are searched and paged without advertisements`(): Unit = runBlocking {
        users.rows += UserEntity(id = 10, providerId = "author", displayName = "Author")
        val older = publicQuestion(id = 100, userId = 10, topic = "Redis")
        val newer = publicQuestion(id = 101, userId = 10, topic = "SwiftUI")
        questions.rows += listOf(older, newer)
        likes.rows += QuestionLikeEntity(
            questionId = older.id,
            userId = principal.userId,
            createdAt = Instant.parse("2026-06-11T00:00:00Z"),
        )
        likes.rows += QuestionLikeEntity(
            questionId = newer.id,
            userId = principal.userId,
            createdAt = Instant.parse("2026-06-12T00:00:00Z"),
        )
        nativeAdvertisements.campaigns += NativeAdvertisementCampaignEntity(
            id = 1,
            campaignKey = "must-not-appear",
            titleKo = "광고",
            titleEn = "Advertisement",
            titleJa = "広告",
            deepLink = "https://example.com/ad",
            minimumSecondsBetweenSelections = 0,
        )

        val searched = service.getLikedPublicQuestions(
            principal = principal,
            query = "  ReDiS  ",
            language = "EN",
            limit = 20,
            offset = 0,
        )
        assertThat(questions.lastLikedQuery).isEqualTo("ReDiS")
        assertThat(questions.lastLikedLanguage).isEqualTo("en")
        val secondPage = service.getLikedPublicQuestions(
            principal = principal,
            query = null,
            language = "ko",
            limit = 20,
            offset = 1,
        )

        assertThat(searched.questions.map { it.id }).containsExactly(older.id.toString())
        assertThat(searched.questions.single().isLikedByMe).isTrue()
        assertThat(searched.totalCount).isEqualTo(1)
        assertThat(searched.items).allMatch { it.advertisement == null }
        assertThat(secondPage.questions.map { it.id }).containsExactly(older.id.toString())
        assertThat(secondPage.totalCount).isEqualTo(2)
        assertThat(secondPage.offset).isEqualTo(1)
        assertThat(nativeAdvertisements.selections).isEmpty()
        assertThat(questions.lastLikedQuery).isNull()
        assertThat(questions.lastLikedLanguage).isEqualTo("ko")
    }

    @Test
    fun `opening a selected advertisement publishes one stable view event`(): Unit = runBlocking {
        nativeAdvertisements.selections += NativeAdvertisementSelectionEntity(
            selectionId = "selection-1",
            campaignId = 1,
            userId = principal.userId,
            deviceId = principal.deviceId,
        )

        service.recordNativeAdvertisementView(principal, "selection-1")

        assertThat(nativeAdvertisementViews.events).hasSize(1)
        val event = nativeAdvertisementViews.events.single()
        assertThat(event.eventId).isEqualTo("native-ad-view-selection-1")
        assertThat(event.selectionId).isEqualTo("selection-1")
        assertThat(event.userId).isEqualTo(principal.userId)
        assertThat(event.deviceId).isEqualTo(principal.deviceId)
    }

    @Test
    fun `visible advertisement records one idempotent impression`(): Unit = runBlocking {
        nativeAdvertisements.selections += NativeAdvertisementSelectionEntity(
            selectionId = "selection-1",
            campaignId = 1,
            userId = principal.userId,
            deviceId = principal.deviceId,
        )

        service.recordNativeAdvertisementImpression(principal, "selection-1")
        val firstRecordedAt = nativeAdvertisements.selections.single().impressionAt
        service.recordNativeAdvertisementImpression(principal, "selection-1")

        assertThat(firstRecordedAt).isNotNull
        assertThat(nativeAdvertisements.selections.single().impressionAt).isEqualTo(firstRecordedAt)
    }

    @Test
    fun `advertisement view rejects a selection owned by another device`(): Unit = runBlocking {
        nativeAdvertisements.selections += NativeAdvertisementSelectionEntity(
            selectionId = "selection-1",
            campaignId = 1,
            userId = principal.userId,
            deviceId = "another-device",
        )

        assertThatThrownBy {
            runBlocking { service.recordNativeAdvertisementView(principal, "selection-1") }
        }.isInstanceOf(ApiException::class.java)

        assertThat(nativeAdvertisementViews.events).isEmpty()
    }

    @Test
    fun `not interested permanently removes the campaign from user ranking`(): Unit = runBlocking {
        users.rows += UserEntity(id = 10, providerId = "author", displayName = "Author")
        (100L..103L).forEach { questions.rows += publicQuestion(it, 10, "Topic $it") }
        nativeAdvertisements.campaigns += NativeAdvertisementCampaignEntity(
            id = 1,
            campaignKey = "coupang-lamp",
            titleKo = "집중 조명",
            titleEn = "Focus lamp",
            titleJa = "集中ライト",
            deepLink = "https://link.coupang.com/a/example",
            minimumSecondsBetweenSelections = 0,
        )

        val first = service.getPublicQuestions(principal, query = null, language = "ko", limit = 20, offset = 0)
        val advertisement = first.items.single { it.advertisement != null }.advertisement!!

        service.suppressNativeAdvertisement(principal, advertisement.selectionId)
        val refreshed = service.getPublicQuestions(principal, query = null, language = "ko", limit = 20, offset = 0)

        assertThat(nativeAdvertisements.suppressedCampaignIds(principal.userId)).containsExactly(1L)
        assertThat(refreshed.items).noneMatch { it.advertisement != null }
    }

    @Test
    fun `public question list loads authors stats and liked flags in batches`(): Unit = runBlocking {
        users.rows += UserEntity(id = 10, providerId = "u10", displayName = "Author A")
        users.rows += UserEntity(id = 11, providerId = "u11", displayName = "Author B")
        questions.rows += publicQuestion(id = 100, userId = 10, topic = "Redis")
        questions.rows += publicQuestion(id = 101, userId = 11, topic = "SwiftUI")
        questionStats.rows += QuestionStatsEntity(questionId = 100, likeCount = 2, commentCount = 1, viewCount = 20)
        questionStats.rows += QuestionStatsEntity(questionId = 101, likeCount = 3, commentCount = 2, viewCount = 30)
        likes.rows += QuestionLikeEntity(questionId = 101, userId = 7)

        val response = service.getPublicQuestions(principal, query = null, language = "ko", limit = 20, offset = 0)

        assertThat(response.questions.map { it.author?.displayName }).containsExactly("Author B", "Author A")
        assertThat(response.questions.map { it.viewCount }).containsExactly(30, 20)
        assertThat(response.questions.map { it.isLikedByMe }).containsExactly(true, false)
        assertThat(users.findByIdCalls).isZero()
        assertThat(users.findAllByIdCalls).isEqualTo(1)
        assertThat(questionStats.findByIdCalls).isZero()
        assertThat(questionStats.findAllByIdsCalls).isEqualTo(1)
        assertThat(likes.existsCalls).isZero()
        assertThat(likes.findLikedQuestionIdsCalls).isEqualTo(1)
    }

    @Test
    fun `public question v2 returns canonical question text`(): Unit = runBlocking {
        users.rows += UserEntity(
            id = 7,
            providerId = "viewer",
            displayName = "Viewer",
            appLanguage = SupportedLanguage.ENGLISH,
        )
        users.rows += UserEntity(id = 10, providerId = "author", displayName = "Author")
        questions.rows += publicQuestion(id = 100, userId = 10, topic = "원본 주제")
        val response = service.getPublicQuestionsV2(principal, query = null, language = "en", limit = 20, offset = 0)

        val question = response.questions.single()
        assertThat(question.topic).isEqualTo("원본 주제")
        assertThat(question.question).isEqualTo("Question 원본 주제")
        assertThat(question.answer).isEqualTo("Answer")
        assertThat(question.gradingResult?.feedback).isEqualTo("Good")
        assertThat(question.gradingResult?.explanation).isEqualTo("Because")
    }

    @Test
    fun `public feed v2 inserts one safe native ad slot only for ad eligible first page`(): Unit = runBlocking {
        users.rows += UserEntity(id = 10, providerId = "author", displayName = "Author")
        (100L..103L).forEach { questions.rows += publicQuestion(it, 10, "Topic $it") }
        nativeAdEligibility.adFree = false
        nativeAdSlots.policy = NativeAdPlacementPolicyEntity(
            enabled = true,
            minimumSecondsBetweenDeliveries = 0,
        )

        val response = service.getPublicQuestionFeedV2(principal, language = "ko", limit = 20, offset = 0)

        assertThat(response.questions).hasSize(4)
        assertThat(response.items.count { it.type.name == "NATIVE_AD_SLOT" }).isEqualTo(1)
        val slotIndex = response.items.indexOfFirst { it.nativeAdSlot != null }
        assertThat(slotIndex).isBetween(2, 3)
        assertThat(response.items.last().nativeAdSlot).isNull()
        assertThat(response.items[slotIndex].nativeAdSlot?.placement).isEqualTo("COMMUNITY_FEED")
        assertThat(nativeAdSlots.lastMinimumSecondsBetweenDeliveries).isZero()

        val laterPage = service.getPublicQuestionFeedV2(principal, language = "ko", limit = 1, offset = 1)
        val search = service.getPublicQuestionsV2(principal, query = "Topic", language = "ko", limit = 20, offset = 0)
        assertThat(laterPage.items).allMatch { it.nativeAdSlot == null }
        assertThat(search.items).allMatch { it.nativeAdSlot == null }
    }

    @Test
    fun `public feed v2 fails closed when ad entitlement is paid or unresolved`(): Unit = runBlocking {
        users.rows += UserEntity(id = 10, providerId = "author", displayName = "Author")
        (100L..103L).forEach { questions.rows += publicQuestion(it, 10, "Topic $it") }
        nativeAdSlots.policy = NativeAdPlacementPolicyEntity(enabled = true)

        nativeAdEligibility.adFree = true
        val paid = service.getPublicQuestionFeedV2(principal, language = "ko", limit = 20, offset = 0)
        nativeAdEligibility.adFree = null
        val unresolved = service.getPublicQuestionFeedV2(principal, language = "ko", limit = 20, offset = 0)

        assertThat(paid.items).allMatch { it.nativeAdSlot == null }
        assertThat(unresolved.items).allMatch { it.nativeAdSlot == null }
        assertThat(nativeAdSlots.slots).isEmpty()
    }

    @Test
    fun `native ad slot fallback and AdMob events are idempotent and owner scoped`(): Unit = runBlocking {
        nativeAdEligibility.adFree = false
        nativeAdvertisements.campaigns += NativeAdvertisementCampaignEntity(
            id = 91,
            campaignKey = "fallback-campaign",
            titleKo = "Fallback",
            titleEn = "Fallback",
            titleJa = "Fallback",
            deepLink = "buddystudy://feedback",
        )
        nativeAdSlots.slots += NativeAdSlotEntity(
            id = 1,
            slotId = "slot-1",
            userId = principal.userId,
            deviceId = principal.deviceId,
            language = "ko",
            position = 2,
            feedItemCount = 4,
        )

        val first = service.nativeAdSlotFallback(principal, "slot-1")
        val second = service.nativeAdSlotFallback(principal, "slot-1")
        service.recordNativeAdSlotImpression(principal, "slot-1", "ADMOB")
        val impressionAt = nativeAdSlots.slots.single().adMobImpressionAt
        service.recordNativeAdSlotImpression(principal, "slot-1", "admob")
        service.recordNativeAdSlotClick(principal, "slot-1", "ADMOB")

        assertThat(first?.selectionId).isEqualTo(second?.selectionId)
        assertThat(nativeAdvertisements.selections).hasSize(1)
        assertThat(nativeAdvertisements.selections.single().nativeAdSlotId).isEqualTo("slot-1")
        assertThat(nativeAdSlots.slots.single().adMobImpressionAt).isEqualTo(impressionAt)
        assertThat(nativeAdSlots.slots.single().adMobClickAt).isNotNull()

        nativeAdEligibility.adFree = true
        assertThat(service.nativeAdSlotFallback(principal, "slot-1")).isNull()
        assertThatThrownBy {
            runBlocking {
                service.nativeAdSlotFallback(principal.copy(deviceId = "other-device"), "slot-1")
            }
        }.isInstanceOf(ApiException::class.java)
    }

    @Test
    fun `public question returns translated answer to its author`(): Unit = runBlocking {
        users.rows += UserEntity(id = principal.userId, providerId = "author", displayName = "Author")
        val question = publicQuestion(id = 103, userId = principal.userId, topic = "Redis").apply {
            sourceLanguage = SupportedLanguage.KOREAN
            answer = "Use AOF for stronger durability."
            answerSourceLanguage = SupportedLanguage.ENGLISH
        }
        questions.rows += question
        val answerHash = requireNotNull(ContentSourceHashPolicy.recordHashes(question).answer)
        val localizedService = CommunityService(
            users = users,
            questions = questions,
            questionStats = questionStats,
            likes = likes,
            comments = comments,
            reports = FakeReportPort(),
            userBlocks = userBlocks,
            feedbacks = FakeFeedbackPort(),
            nativeAdvertisements = FakeNativeAdvertisementPort(),
            nativeAdvertisementViews = FakeNativeAdvertisementViewPublisher(),
            nativeAdEligibility = FakeNativeAdEligibilityPort(),
            nativeAdSlots = FakeNativeAdSlotPort(),
            reactions = reactionPublisher,
            notifications = notificationPublisher,
            languageDetector = PassthroughLanguageDetector(),
            contentLocalizations = object : EmptyContentLocalizationPort() {
                override suspend fun record(questionId: Long, targetLanguage: String) =
                    RecordLocalizationSnapshot(
                        question = null,
                        answer = TextLocalizationSnapshot(
                            sourceLanguage = "en",
                            targetLanguage = "ko",
                            sourceHash = answerHash,
                            status = "READY",
                            fields = mapOf("answer" to "더 강한 내구성을 위해 AOF를 사용합니다."),
                            provider = "test",
                        ),
                        aiResponse = null,
                    )
            },
            localizationRequests = RecordingLocalizationRequests(),
            translationRequestManager = ContentTranslationRequestManager(
                EmptyContentLocalizationPort(),
                translationEvents,
            ),
            afterCommit = ImmediateAfterCommit(),
            outboxPublisher = translationPublisher,
        )

        val response = localizedService.getPublicQuestion(
            principal = principal,
            id = question.id,
            language = "ko",
            view = "localized",
        )

        assertThat(response.answer).isEqualTo("더 강한 내구성을 위해 AOF를 사용합니다.")
        assertThat(response.localization?.answer?.isTranslated).isTrue()
        assertThat(response.localization?.answer?.displayLanguage).isEqualTo("ko")
    }

    @Test
    fun `blocked authors are hidden from public question lists details and comments`(): Unit = runBlocking {
        users.rows += UserEntity(id = 10, providerId = "blocked", displayName = "Blocked")
        users.rows += UserEntity(id = 11, providerId = "visible", displayName = "Visible")
        questions.rows += publicQuestion(id = 102, userId = 10, topic = "Blocked topic")
        questions.rows += publicQuestion(id = 101, userId = 11, topic = "Visible topic")
        comments.rows += QuestionCommentEntity(id = 1, questionId = 101, userId = 10, body = "hidden")
        comments.rows += QuestionCommentEntity(id = 2, questionId = 101, userId = 11, body = "visible")
        userBlocks.rows += UserBlockEntity(blockerUserId = principal.userId, blockedUserId = 10)

        val questionsResponse = service.getPublicQuestions(
            principal,
            query = null,
            language = "ko",
            limit = 1,
            offset = 0,
        )
        val commentsResponse = service.getComments(
            id = 101,
            limit = 1,
            offset = 0,
            principal = principal,
        )

        assertThat(questionsResponse.questions.map { it.author?.id }).containsExactly(11)
        assertThat(questionsResponse.totalCount).isEqualTo(1)
        assertThat(commentsResponse.comments.map { it.author.id }).containsExactly(11)
        assertThat(commentsResponse.totalCount).isEqualTo(1)
        assertThatThrownBy {
            runBlocking { service.getPublicQuestion(principal, 102) }
        }
            .isInstanceOf(ApiException::class.java)
            .extracting("code")
            .isEqualTo(ApiErrorCode.RECORD_NOT_FOUND)
        assertThatThrownBy {
            runBlocking { service.getComments(id = 102, limit = 1, offset = 0, principal = principal) }
        }
            .isInstanceOf(ApiException::class.java)
            .extracting("code")
            .isEqualTo(ApiErrorCode.RECORD_NOT_FOUND)
    }

    @Test
    fun `blocking a user is idempotent and can be reversed`(): Unit = runBlocking {
        users.rows += UserEntity(id = 10, providerId = "author", displayName = "Author")

        assertThat(service.setUserBlocked(principal, userId = 10, blocked = true).blocked).isTrue()
        assertThat(service.setUserBlocked(principal, userId = 10, blocked = true).blocked).isTrue()
        assertThat(userBlocks.rows).hasSize(1)

        assertThat(service.setUserBlocked(principal, userId = 10, blocked = false).blocked).isFalse()
        assertThat(service.setUserBlocked(principal, userId = 10, blocked = false).blocked).isFalse()
        assertThat(userBlocks.rows).isEmpty()
    }

    @Test
    fun `blocking my own account is rejected`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking { service.setUserBlocked(principal, userId = principal.userId, blocked = true) }
        }
            .isInstanceOf(ApiException::class.java)
            .extracting("code")
            .isEqualTo(ApiErrorCode.VALIDATION_ERROR)
        assertThat(userBlocks.rows).isEmpty()
    }

    @Test
    fun `liking a public question increments stats without recounting likes`(): Unit = runBlocking {
        users.rows += UserEntity(id = 10, providerId = "u10", displayName = "Author A")
        questions.rows += publicQuestion(id = 100, userId = 10, topic = "Redis")
        questionStats.rows += QuestionStatsEntity(questionId = 100, likeCount = 3, commentCount = 1, viewCount = 20)

        val response = service.setLike(principal, id = 100, liked = true)

        assertThat(response.likeCount).isEqualTo(4)
        assertThat(response.isLikedByMe).isTrue()
        assertThat(questionStats.incrementLikeCalls).isEqualTo(1)
        assertThat(questionStats.findByIdCalls).isEqualTo(1)
        assertThat(reactionPublisher.events).containsExactly("QUESTION_LIKED:100:7")
        val notification = notificationPublisher.rows.single()
        assertThat(notification.eventId).isEqualTo("question-like-100-7")
        assertThat(notification.userId).isEqualTo(10)
        assertThat(notification.actorUserId).isEqualTo(7)
        assertThat(notification.threadType).isEqualTo("question")
        assertThat(notification.threadId).isEqualTo("100")
        assertThat(notification.shouldPush).isFalse()
    }

    @Test
    fun `unliking a public question publishes the unlike event only when state changes`(): Unit = runBlocking {
        questions.rows += publicQuestion(id = 100, userId = 10, topic = "Redis")
        questionStats.rows += QuestionStatsEntity(questionId = 100, likeCount = 1)
        likes.rows += QuestionLikeEntity(questionId = 100, userId = principal.userId)

        val response = service.setLike(principal, id = 100, liked = false)

        assertThat(response.isLikedByMe).isFalse()
        assertThat(response.likeCount).isZero()
        assertThat(reactionPublisher.events).containsExactly("QUESTION_UNLIKED:100:7")
    }

    @Test
    fun `commenting on another user's question publishes push eligible thread notification`(): Unit = runBlocking {
        users.rows += UserEntity(id = 7, providerId = "u7", displayName = "Commenter")
        users.rows += UserEntity(id = 10, providerId = "u10", displayName = "Author A")
        questions.rows += publicQuestion(id = 100, userId = 10, topic = "Redis")

        val response = service.createComment(principal, id = 100, body = "좋은 질문입니다.")

        assertThat(response.body).isEqualTo("좋은 질문입니다.")
        val notification = notificationPublisher.rows.single()
        assertThat(notification.eventId).isEqualTo("question-comment-1")
        assertThat(notification.userId).isEqualTo(10)
        assertThat(notification.actorUserId).isEqualTo(7)
        assertThat(notification.threadType).isEqualTo("question")
        assertThat(notification.threadId).isEqualTo("100")
        assertThat(notification.deepLink).isEqualTo("buddystudy://public/questions/100")
        assertThat(notification.shouldPush).isTrue()
        assertThat(notification.title).isEqualTo("댓글")
        assertThat(reactionPublisher.events).containsExactly("QUESTION_COMMENTED:100:1:7")
        assertThat(translationEvents.events.map { it.contentType to it.targetLanguage })
            .containsExactlyInAnyOrder(
                LocalizableContentType.COMMENT to "en",
                LocalizableContentType.COMMENT to "ja",
            )
        assertThat(translationPublisher.published).hasSize(2)
    }

    @Test
    fun `deleting my comment publishes the comment deleted event`() = runBlocking<Unit> {
        questions.rows += publicQuestion(id = 100, userId = 10, topic = "Redis")
        comments.rows += QuestionCommentEntity(id = 5, questionId = 100, userId = principal.userId, body = "삭제할 댓글")

        service.deleteComment(principal, id = 100, commentId = 5)

        assertThat(comments.rows.single().deletedAt).isNotNull()
        assertThat(reactionPublisher.events).containsExactly("QUESTION_COMMENT_DELETED:100:5:7")
    }

    @Test
    fun `empty comment page skips author lookup`(): Unit = runBlocking {
        questions.rows += publicQuestion(id = 100, userId = 10, topic = "Redis")

        val response = service.getComments(id = 100, limit = 20, offset = 0)

        assertThat(response.comments).isEmpty()
        assertThat(response.totalCount).isZero()
        assertThat(users.findAllByIdCalls).isZero()
    }

    @Test
    fun `public voice detail uses source backed text and spoken score without fabricated grading or private evidence`(): Unit = runBlocking {
        users.rows += UserEntity(id = 10, providerId = "voice-author", displayName = "Voice author")
        val question = voiceQuestion(200, 10).apply {
            question = "Do not expose stale canonical cache"
            answer = "Do not expose stale cached answer"
            score = 99
            correct = true
        }
        questions.rows += question
        val source = voiceRecord(question)
        voiceLocalizations.rows += source

        val response = service.getPublicQuestion(principal, 200, "en", "original")

        assertThat(response.id).isEqualTo("200")
        assertThat(response.recordType).isEqualTo(StudyRecordType.VOICE_TUTOR)
        assertThat(response.question).isEqualTo(source.question)
        assertThat(response.answer).isEqualTo(source.answer)
        assertThat(response.gradingResult).isNull()
        assertThat(response.voiceRecord?.score).isEqualTo(85)
        assertThat(response.voiceRecord?.feedback).isEqualTo("85점입니다. 근거를 잘 설명했어요.")
        assertThat(response.voiceRecord?.strengths).containsExactly("근거 설명")
        assertThat(response.localization?.question?.translationState).isEqualTo(TranslationState.ORIGINAL)
        assertThat(ordinaryLocalizations.recordReads).isEmpty()
        assertThat(ordinaryRequests.records).isEmpty()
        assertThat(voiceLocalizations.requests).isEmpty()
        val json = JsonMapperProvider.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(response)
        assertThat(json["voiceRecord"].fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
            "kind", "score", "feedback", "strengths", "improvements", "depthSummary",
            "sourceLanguage", "requestedLanguage", "displayLanguage", "translationPending",
        )
        assertThat(json.has("voiceRecordId")).isFalse()
        assertThat(json.has("studyId")).isFalse()
        assertThat(json.toString()).doesNotContain("private-voice-session", "sourceHash", "questionTurnId", "answerTurnIds", "feedbackTurnIds")
    }

    @Test
    fun `public voice localization uses its validated source hash stream and not question localization`(): Unit = runBlocking {
        val question = voiceQuestion(201, 10)
        questions.rows += question
        val source = voiceRecord(question)
        voiceLocalizations.rows += source
        val translatedFields = source.translatableFields().mapValues { (key, _) -> "English $key" }
        voiceLocalizations.snapshots[source.id to "en"] = TextLocalizationSnapshot(
            "ko", "en", source.sourceHash, "READY", translatedFields,
        )

        val response = service.getPublicQuestion(principal, 201, "en", "localized")

        assertThat(response.question).isEqualTo("English question")
        assertThat(response.answer).isEqualTo("English answer")
        assertThat(response.voiceRecord?.feedback).isEqualTo("English feedback")
        assertThat(response.voiceRecord?.score).isEqualTo(85)
        assertThat(response.voiceRecord?.displayLanguage).isEqualTo("en")
        assertThat(response.localization?.question?.translationState).isEqualTo(TranslationState.TRANSLATED)
        assertThat(voiceLocalizations.requests).isEmpty()
        assertThat(ordinaryLocalizations.recordReads).isEmpty()
        assertThat(ordinaryRequests.records).isEmpty()
    }

    @Test
    fun `stale voice translation falls back to intact original and requests only voice stream read repair`(): Unit = runBlocking {
        val question = voiceQuestion(202, 10)
        questions.rows += question
        val source = voiceRecord(question).copy(score = null)
        voiceLocalizations.rows += source
        voiceLocalizations.snapshots[source.id to "en"] = TextLocalizationSnapshot(
            "ko", "en", "old-source-hash", "READY", source.translatableFields().mapValues { "Untrusted old translation" },
        )

        val response = service.getPublicQuestion(principal, 202, "en", "localized")

        assertThat(response.question).isEqualTo(source.question)
        assertThat(response.answer).isEqualTo(source.answer)
        assertThat(response.gradingResult).isNull()
        assertThat(response.voiceRecord?.score).isNull()
        assertThat(response.voiceRecord?.translationPending).isTrue()
        assertThat(voiceLocalizations.requests).containsExactly(source.id to "en")
        assertThat(ordinaryLocalizations.recordReads).isEmpty()
        assertThat(ordinaryRequests.records).isEmpty()
    }

    @Test
    fun `public and liked pages retain both record types and canonical ids`(): Unit = runBlocking {
        users.rows += UserEntity(id = 10, providerId = "author", displayName = "Author")
        val ordinary = publicQuestion(203, 10, "Redis")
        val voice = voiceQuestion(204, 10)
        questions.rows += listOf(ordinary, voice)
        voiceLocalizations.rows += voiceRecord(voice)
        likes.rows += QuestionLikeEntity(questionId = ordinary.id, userId = principal.userId)
        likes.rows += QuestionLikeEntity(questionId = voice.id, userId = principal.userId)

        val publicPage = service.getPublicQuestions(principal, null, "ko", "original", 20, 0)
        val likedPage = service.getLikedPublicQuestions(principal, null, "ko", "original", 20, 0)

        listOf(publicPage, likedPage).forEach { page ->
            assertThat(page.questions.map { it.id }).containsExactlyInAnyOrder("203", "204")
            assertThat(page.totalCount).isEqualTo(2)
            assertThat(page.questions.single { it.id == "203" }.recordType).isEqualTo(StudyRecordType.QUESTION)
            assertThat(page.questions.single { it.id == "203" }.voiceRecord).isNull()
            assertThat(page.questions.single { it.id == "203" }.gradingResult?.score).isEqualTo(90)
            assertThat(page.questions.single { it.id == "204" }.recordType).isEqualTo(StudyRecordType.VOICE_TUTOR)
            assertThat(page.questions.single { it.id == "204" }.gradingResult).isNull()
        }
    }

    @Test
    fun `voice comments likes reports and thread notifications all address the shared record id`(): Unit = runBlocking {
        users.rows += UserEntity(id = 10, providerId = "author", displayName = "Author")
        users.rows += UserEntity(id = principal.userId, providerId = "viewer", displayName = "Viewer")
        val question = voiceQuestion(205, 10)
        questions.rows += question
        voiceLocalizations.rows += voiceRecord(question)
        questionStats.rows += QuestionStatsEntity(questionId = question.id)

        service.setLike(principal, question.id, true)
        val comment = service.createComment(principal, question.id, "대화 근거가 도움이 됐어요", "ko")
        service.reportQuestion(principal, question.id, ReportQuestionCommand("OTHER", "test report"))
        val thread = service.getComments(question.id, "ko", "original", 20, 0, principal)

        assertThat(comment.questionId).isEqualTo("205")
        assertThat(thread.comments.single().id).isEqualTo(comment.id)
        assertThat(likes.rows.single().questionId).isEqualTo(205)
        assertThat(reports.rows.single().questionId).isEqualTo(205)
        assertThat(comments.rows.single().questionId).isEqualTo(205)
        assertThat(notificationPublisher.rows).hasSize(2).allSatisfy { notification ->
            assertThat(notification.threadId).isEqualTo("205")
            assertThat(notification.userId).isEqualTo(10)
        }
        assertThat(reactionPublisher.events).contains("QUESTION_LIKED:205:7", "QUESTION_COMMENTED:205:${comment.id}:7")
        assertThat(translationEvents.events).allMatch { it.contentType == LocalizableContentType.COMMENT }
        service.setLike(principal, question.id, false)
        service.deleteComment(principal, question.id, comment.id.toLong())
        assertThat(likes.rows).isEmpty()
        assertThat(comments.rows.single().deletedAt).isNotNull()
        assertThat(ordinaryRequests.records).isEmpty()
    }

    @Test
    fun `private or blocked voice is rejected before source reads and hidden from common feeds`(): Unit = runBlocking {
        val privateVoice = voiceQuestion(206, 10).apply { publicQuestion = false }
        val blockedVoice = voiceQuestion(207, 11)
        questions.rows += listOf(privateVoice, blockedVoice)
        userBlocks.rows += UserBlockEntity(blockerUserId = principal.userId, blockedUserId = 11)
        listOf(privateVoice, blockedVoice).forEach { question ->
            assertThatThrownBy {
                runBlocking { service.getPublicQuestion(principal, question.id, "en", "localized") }
            }.isInstanceOf(ApiException::class.java)
            assertThatThrownBy {
                runBlocking { service.getComments(question.id, "en", "localized", 20, 0, principal) }
            }.isInstanceOf(ApiException::class.java)
        }

        assertThat(service.getPublicQuestions(principal, null, "ko", "original", 20, 0).questions).isEmpty()
        assertThat(voiceLocalizations.contentReads).isEmpty()
        assertThat(voiceLocalizations.requests).isEmpty()
        assertThat(reactionPublisher.events).isEmpty()
    }

    @Test
    fun `voice extension absent wrong owner or wrong canonical id never falls back to cached public text`(): Unit = runBlocking {
        val question = voiceQuestion(208, 10)
        questions.rows += question
        val source = voiceRecord(question)
        listOf(null, source.copy(userId = 99), source.copy(recordId = 999)).forEach { invalid ->
            voiceLocalizations.rows.clear()
            invalid?.let { voiceLocalizations.rows += it }
            val error = runCatching { service.getPublicQuestion(principal, question.id, "ko", "original") }.exceptionOrNull()
            assertThat(error).isInstanceOf(ApiException::class.java)
            assertThat((error as ApiException).code).isEqualTo(ApiErrorCode.RECORD_NOT_FOUND)
        }
        assertThat(reactionPublisher.events).isEmpty()
        assertThat(ordinaryLocalizations.recordReads).isEmpty()
    }

    private fun voiceQuestion(id: Long, userId: Long) = publicQuestion(id, userId, "Redis").apply {
        recordType = StudyRecordType.VOICE_TUTOR
        voiceRecordId = id + 1000
        source = QuestionSource.VOICE_TUTOR
        status = QuestionStatus.COMPLETED
        score = null
        correct = null
        feedback = null
        explanation = null
        gradedAt = null
    }

    private fun voiceRecord(question: QuestionEntity) = VoiceStudyLearningRecord(
        id = checkNotNull(question.voiceRecordId),
        userId = checkNotNull(question.userId),
        sessionId = "private-voice-session",
        studyId = 8001,
        parentStudyId = 8000,
        topic = question.topic,
        difficulty = question.difficultyLevel,
        createdAt = question.createdAt,
        kind = VoiceTutorExchangeKind.TUTOR_QUESTION,
        question = "레디스의 만료 정책을 설명해 주세요.",
        answer = "키가 만료되면 삭제되고 메모리가 반환됩니다.",
        score = 85,
        strengths = listOf("근거 설명"),
        improvements = listOf("지연 삭제와 주기적 삭제도 구분해 보세요."),
        depthSummary = "현재 저장된 Redis 주제의 만료 정책 학습",
        feedback = "85점입니다. 근거를 잘 설명했어요.",
        questionTurnId = 9101,
        answerTurnIds = listOf(9102),
        feedbackTurnIds = listOf(9103),
        sourceLanguage = "ko",
        sourceLanguages = emptyMap(),
        sourceHash = "voice-source-hash-${question.id}",
        recordId = question.id,
    )

    private fun publicQuestion(id: Long, userId: Long, topic: String) = QuestionEntity(
        id = id,
        deviceId = "dev-1",
        userId = userId,
        question = "Question $topic",
        topic = topic,
        difficultyLevel = 5,
        scheduledFor = Instant.parse("2026-06-10T00:00:00Z"),
        status = QuestionStatus.GRADED,
        answer = "Answer",
        score = 90,
        correct = true,
        feedback = "Good",
        explanation = "Because",
        answeredAt = Instant.parse("2026-06-10T01:00:00Z"),
        gradedAt = Instant.parse("2026-06-10T01:00:00Z"),
        publicQuestion = true,
        createdAt = Instant.parse("2026-06-10T00:00:00Z").plusSeconds(id),
        updatedAt = Instant.parse("2026-06-10T01:00:00Z"),
    )

    private class FakeUserPort : UserPort {
        val rows = mutableListOf<UserEntity>()
        var findByIdCalls = 0
        var findAllByIdCalls = 0

        override suspend fun save(entity: UserEntity): UserEntity = entity
        override suspend fun findById(id: Long): UserEntity? {
            findByIdCalls += 1
            return rows.firstOrNull { it.id == id }
        }

        override suspend fun findAllById(ids: Iterable<Long>): MutableList<UserEntity> {
            findAllByIdCalls += 1
            val idSet = ids.toSet()
            return rows.filter { it.id in idSet }.toMutableList()
        }

        override suspend fun findByProviderAndProviderId(provider: String, providerId: String): UserEntity? = null
        override suspend fun findByEmailAndProvider(email: String, provider: String): UserEntity? = null
    }

    private class FakeQuestionPort(
        private val userBlocks: FakeUserBlockPort,
        private val likes: FakeQuestionLikePort,
    ) : QuestionPort {
        val rows = mutableListOf<QuestionEntity>()
        var lastLikedQuery: String? = null
        var lastLikedLanguage: String? = null
        override suspend fun save(entity: QuestionEntity): QuestionEntity = entity
        override suspend fun findQuestionById(id: Long): QuestionEntity? = null
        override suspend fun findByIdAndUserIdAndDeletedAtIsNull(id: Long, userId: Long): QuestionEntity? = null
        override suspend fun findGradedByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findGradedByUserAndQuery(userId: Long, query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findGradedByUserAndTopics(userId: Long, topics: Collection<String>, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findLatestGradedByUserAndTopics(userId: Long, topics: Collection<String>, perTopicLimit: Int): List<QuestionEntity> = emptyList()
        override suspend fun findAllGradedForStats(pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findPendingByUser(userId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findPendingByStudyId(studyId: Long, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findLatestPendingByStudyIds(studyIds: Collection<Long>): List<QuestionEntity> = emptyList()
        override suspend fun findVisibleByUser(userId: Long, includePending: Boolean, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findVisibleByUserAndQuery(userId: Long, includePending: Boolean, query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findRecentQuestionTextsByStudyIdAndTopic(studyId: Long, topic: String, pageable: Pageable): List<String> = emptyList()
        override suspend fun findRecentQuestionTextsByUserIdAndTopic(userId: Long, topic: String, pageable: Pageable): List<String> = emptyList()
        override suspend fun countPendingForStudy(studyId: Long): Long = 0
        override suspend fun countPendingByStudyIds(studyIds: Collection<Long>): Map<Long, Long> = emptyMap()
        override suspend fun findPublicAnswered(pageable: Pageable): Page<QuestionEntity> = publicPage(null, pageable)
        override suspend fun findPublicAnsweredVisibleTo(
            viewerUserId: Long?,
            pageable: Pageable,
        ): Page<QuestionEntity> = publicPage(viewerUserId, pageable)
        override suspend fun findPublicAnsweredByTopic(topic: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findPublicAnsweredByQuery(query: String, pageable: Pageable): Page<QuestionEntity> = Page.empty()
        override suspend fun findPublicAnsweredByLanguageAndQueryVisibleTo(
            viewerUserId: Long?,
            language: String,
            query: String,
            pageable: Pageable,
        ): Page<QuestionEntity> = publicPage(viewerUserId, pageable, query)
        override suspend fun findPersonalizedPublicAnswered(
            viewerUserId: Long?, query: String?, language: String, sort: PublicFeedSort,
            scope: PublicFeedScope, limit: Int, offset: Int,
        ): Page<QuestionEntity> = publicPage(
            viewerUserId, org.springframework.data.domain.PageRequest.of(offset / limit, limit), query,
        )
        override suspend fun findLikedPublicAnsweredVisibleTo(
            viewerUserId: Long,
            query: String?,
            language: String,
            limit: Int,
            offset: Int,
        ): Page<QuestionEntity> {
            lastLikedQuery = query
            lastLikedLanguage = language
            val blockedUserIds = userBlocks.findBlockedUserIds(viewerUserId)
            val likedAtByQuestionId = likes.rows
                .filter { it.userId == viewerUserId }
                .associate { it.questionId to it.createdAt }
            val normalizedQuery = query?.lowercase()
            val visible = rows
                .asSequence()
                .filter { it.id in likedAtByQuestionId }
                .filterNot { it.userId in blockedUserIds }
                .filter(::eligible)
                .filter { row ->
                    normalizedQuery == null || listOf(row.topic, row.question, row.answer.orEmpty())
                        .any { normalizedQuery in it.lowercase() }
                }
                .sortedWith(
                    compareByDescending<QuestionEntity> { likedAtByQuestionId[it.id] }
                        .thenByDescending { it.id },
                )
                .toList()
            val start = offset.coerceAtMost(visible.size)
            val end = (start + limit).coerceAtMost(visible.size)
            return PageImpl(
                visible.subList(start, end),
                Pageable.unpaged(),
                visible.size.toLong(),
            )
        }
        override suspend fun findPublicAnsweredById(id: Long): QuestionEntity? = rows.firstOrNull { it.id == id && eligible(it) }
        override suspend fun findPublicAnsweredByIds(ids: Collection<Long>): List<QuestionEntity> = rows.filter { it.id in ids && eligible(it) }
        override suspend fun softDelete(id: Long, userId: Long, now: Instant): Int = 0
        override suspend fun softDeleteByUserId(userId: Long, now: Instant): Int = 0
        override suspend fun softDeleteByUserIdAndTopic(userId: Long, topic: String, now: Instant): Int = 0

        private suspend fun publicPage(
            viewerUserId: Long?,
            pageable: Pageable,
            query: String? = null,
        ): Page<QuestionEntity> {
            val blockedUserIds = viewerUserId?.let { userBlocks.findBlockedUserIds(it) }.orEmpty()
            val normalizedQuery = query?.lowercase()
            val visible = rows
                .asSequence()
                .filterNot { it.userId in blockedUserIds }
                .filter(::eligible)
                .filter { row ->
                    normalizedQuery == null || listOf(row.topic, row.question, row.answer.orEmpty())
                        .any { normalizedQuery in it.lowercase() }
                }
                .sortedWith(compareByDescending<QuestionEntity> { it.createdAt }.thenByDescending { it.id })
                .toList()
            val start = pageable.offset.toInt().coerceAtMost(visible.size)
            val end = (start + pageable.pageSize).coerceAtMost(visible.size)
            return PageImpl(visible.subList(start, end), pageable, visible.size.toLong())
        }

        private fun eligible(question: QuestionEntity): Boolean = question.publicQuestion &&
            question.deletedAt == null && !question.answer.isNullOrBlank() && when (question.recordType) {
                StudyRecordType.QUESTION -> question.status == QuestionStatus.GRADED
                StudyRecordType.VOICE_TUTOR -> question.status == QuestionStatus.COMPLETED &&
                    question.voiceRecordId != null && question.question.isNotBlank()
            }
    }

    private class FakeQuestionStatsPort : QuestionStatsPort {
        val rows = mutableListOf<QuestionStatsEntity>()
        var findByIdCalls = 0
        var findAllByIdsCalls = 0
        var incrementLikeCalls = 0

        override suspend fun save(entity: QuestionStatsEntity): QuestionStatsEntity = entity
        override suspend fun findById(id: Long): QuestionStatsEntity? {
            findByIdCalls += 1
            return rows.firstOrNull { it.questionId == id }
        }

        override suspend fun findAllByIds(ids: Collection<Long>): List<QuestionStatsEntity> {
            findAllByIdsCalls += 1
            return rows.filter { it.questionId in ids }
        }

        override suspend fun incrementView(questionId: Long, delta: Int, now: Instant): Int = 0
        override suspend fun incrementLike(questionId: Long, delta: Int, now: Instant): Int {
            incrementLikeCalls += 1
            val row = rows.firstOrNull { it.questionId == questionId } ?: return 0
            row.likeCount = maxOf(0, row.likeCount + delta)
            row.updatedAt = now
            return 1
        }
        override suspend fun incrementComment(questionId: Long, delta: Int, now: Instant): Int = 0
        override suspend fun setLikeCount(questionId: Long, count: Int, now: Instant): Int = 0
    }

    private class FakeQuestionLikePort : QuestionLikePort {
        val rows = mutableListOf<QuestionLikeEntity>()
        var existsCalls = 0
        var findLikedQuestionIdsCalls = 0

        override suspend fun save(entity: QuestionLikeEntity): QuestionLikeEntity {
            rows += entity
            return entity
        }
        override suspend fun existsByQuestionIdAndUserId(questionId: Long, userId: Long): Boolean {
            existsCalls += 1
            return rows.any { it.questionId == questionId && it.userId == userId }
        }

        override suspend fun findLikedQuestionIds(userId: Long, questionIds: Collection<Long>): Set<Long> {
            findLikedQuestionIdsCalls += 1
            return rows.filter { it.userId == userId && it.questionId in questionIds }.map { it.questionId }.toSet()
        }

        override suspend fun deleteByQuestionIdAndUserId(questionId: Long, userId: Long): Long {
            val removed = rows.removeIf { it.questionId == questionId && it.userId == userId }
            return if (removed) 1 else 0
        }
    }

    private class FakeQuestionCommentPort(
        private val userBlocks: FakeUserBlockPort,
    ) : QuestionCommentPort {
        val rows = mutableListOf<QuestionCommentEntity>()
        private var nextId = 1L
        override suspend fun save(entity: QuestionCommentEntity): QuestionCommentEntity {
            if (entity.id == 0L) {
                entity.id = nextId++
            }
            if (entity !in rows) rows += entity
            return entity
        }
        override suspend fun findByIdAndQuestionIdAndDeletedAtIsNull(
            id: Long,
            questionId: Long,
        ): QuestionCommentEntity? = rows.firstOrNull {
            it.id == id && it.questionId == questionId && it.deletedAt == null
        }
        override suspend fun findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtAsc(
            questionId: Long,
            pageable: Pageable,
        ): Page<QuestionCommentEntity> = commentPage(questionId, null, pageable)

        override suspend fun findVisibleByQuestionIdOrderByCreatedAtAsc(
            questionId: Long,
            viewerUserId: Long?,
            pageable: Pageable,
        ): Page<QuestionCommentEntity> = commentPage(questionId, viewerUserId, pageable)

        private suspend fun commentPage(
            questionId: Long,
            viewerUserId: Long?,
            pageable: Pageable,
        ): Page<QuestionCommentEntity> {
            val blockedUserIds = viewerUserId?.let { userBlocks.findBlockedUserIds(it) }.orEmpty()
            val visible = rows
                .filter { it.questionId == questionId && it.deletedAt == null && it.userId !in blockedUserIds }
                .sortedWith(compareBy<QuestionCommentEntity> { it.createdAt }.thenBy { it.id })
            val start = pageable.offset.toInt().coerceAtMost(visible.size)
            val end = (start + pageable.pageSize).coerceAtMost(visible.size)
            return PageImpl(visible.subList(start, end), pageable, visible.size.toLong())
        }
    }

    private class FakeReportPort : ReportPort {
        val rows = mutableListOf<ReportEntity>()
        override suspend fun save(entity: ReportEntity): ReportEntity = entity.also { rows += it }
    }

    private class RecordingOrdinaryLocalizations : EmptyContentLocalizationPort() {
        val recordReads = mutableListOf<Pair<Long, String>>()
        override suspend fun record(questionId: Long, targetLanguage: String): RecordLocalizationSnapshot {
            recordReads += questionId to targetLanguage
            return super.record(questionId, targetLanguage)
        }
    }

    private class FakeVoiceLocalizations : VoiceStudyLearningLocalizationPort by UnavailableVoiceStudyLearningLocalizationPort {
        val rows = mutableListOf<VoiceStudyLearningRecord>()
        val snapshots = mutableMapOf<Pair<Long, String>, TextLocalizationSnapshot>()
        val contentReads = mutableListOf<Long>()
        val requests = mutableListOf<Pair<Long, String>>()
        override suspend fun content(recordId: Long): VoiceStudyLearningRecord? {
            contentReads += recordId
            return rows.firstOrNull { it.id == recordId }
        }
        override suspend fun snapshot(recordId: Long, targetLanguage: String): TextLocalizationSnapshot? =
            snapshots[recordId to targetLanguage]
        override suspend fun request(record: VoiceStudyLearningRecord, targetLanguage: String, now: Instant) {
            requests += record.id to targetLanguage
        }
    }

    private class FakeFeedbackPort : FeedbackPort {
        override suspend fun save(entity: FeedbackEntity): FeedbackEntity = entity
    }

    private class FakeNativeAdvertisementPort : NativeAdvertisementPort {
        val campaigns = mutableListOf<NativeAdvertisementCampaignEntity>()
        val selections = mutableListOf<NativeAdvertisementSelectionEntity>()
        private val suppressions = mutableSetOf<Pair<Long, Long>>()

        override suspend fun findEligibleCampaigns(placement: String, now: Instant) =
            campaigns.filter { it.placement == placement && it.active }
        override suspend fun findCampaign(id: Long) = campaigns.firstOrNull { it.id == id }
        override suspend fun findUserRankingSignals(
            campaignIds: Collection<Long>,
            userId: Long,
            today: Instant,
        ): Map<Long, NativeAdvertisementUserRankingSignals> = emptyMap()
        override suspend fun findCampaignPerformance(
            campaignIds: Collection<Long>,
            since: Instant,
        ): Map<Long, NativeAdvertisementCampaignPerformance> = emptyMap()
        override suspend fun saveSelection(entity: NativeAdvertisementSelectionEntity): NativeAdvertisementSelectionEntity {
            selections += entity
            return entity
        }
        override suspend fun saveFallbackSelectionIfAbsent(
            slotId: String,
            entity: NativeAdvertisementSelectionEntity,
        ): NativeAdvertisementSelectionEntity = selections.firstOrNull { it.nativeAdSlotId == slotId }
            ?: entity.also(selections::add)
        override suspend fun findSelectionByNativeAdSlotId(slotId: String) =
            selections.firstOrNull { it.nativeAdSlotId == slotId }
        override suspend fun findSelection(selectionId: String) = selections.firstOrNull { it.selectionId == selectionId }
        override suspend fun markImpression(selectionId: String, userId: Long, deviceId: String, at: Instant) {
            selections.firstOrNull { it.selectionId == selectionId && it.userId == userId && it.deviceId == deviceId }
                ?.let { if (it.impressionAt == null) it.impressionAt = at }
        }
        override suspend fun markView(selectionId: String, userId: Long, deviceId: String, at: Instant) {
            selections.firstOrNull { it.selectionId == selectionId && it.userId == userId && it.deviceId == deviceId }
                ?.let { if (it.viewedAt == null) it.viewedAt = at }
        }
        override suspend fun findSuppressedCampaignIds(userId: Long): Set<Long> = suppressedCampaignIds(userId)
        override suspend fun suppressCampaign(campaignId: Long, userId: Long, at: Instant) {
            suppressions += userId to campaignId
        }
        fun suppressedCampaignIds(userId: Long): Set<Long> = suppressions
            .filter { it.first == userId }
            .map { it.second }
            .toSet()
    }

    private class FakeNativeAdEligibilityPort(
        var adFree: Boolean? = true,
    ) : NativeAdEligibilityPort {
        override suspend fun isAdFree(userId: Long): Boolean? = adFree
    }

    private class FakeNativeAdSlotPort : NativeAdSlotPort {
        var policy: NativeAdPlacementPolicyEntity? = null
        val slots = mutableListOf<NativeAdSlotEntity>()
        var lastMinimumSecondsBetweenDeliveries: Int? = null

        override suspend fun findPlacementPolicy(placement: String) = policy?.takeIf { it.placement == placement }
        override suspend fun savePlacementPolicy(entity: NativeAdPlacementPolicyEntity): NativeAdPlacementPolicyEntity {
            policy = entity
            return entity
        }
        override suspend fun reserveSlot(
            reservation: NativeAdSlotReservation,
            dailyDeliveryCap: Int,
            minimumSecondsBetweenDeliveries: Int,
        ): NativeAdSlotEntity? {
            if (dailyDeliveryCap <= 0) return null
            lastMinimumSecondsBetweenDeliveries = minimumSecondsBetweenDeliveries
            return NativeAdSlotEntity(
                id = (slots.size + 1).toLong(),
                slotId = reservation.slotId,
                userId = reservation.userId,
                deviceId = reservation.deviceId,
                placement = reservation.placement,
                language = reservation.language,
                position = reservation.position,
                feedItemCount = reservation.feedItemCount,
                deliveredAt = reservation.deliveredAt,
            ).also(slots::add)
        }
        override suspend fun findOwnedSlot(slotId: String, userId: Long, deviceId: String) =
            slots.firstOrNull { it.slotId == slotId && it.userId == userId && it.deviceId == deviceId }
        override suspend fun markAdMobImpression(slotId: String, userId: Long, deviceId: String, at: Instant) {
            findOwnedSlot(slotId, userId, deviceId)?.let { if (it.adMobImpressionAt == null) it.adMobImpressionAt = at }
        }
        override suspend fun markAdMobClick(slotId: String, userId: Long, deviceId: String, at: Instant) {
            findOwnedSlot(slotId, userId, deviceId)?.let { if (it.adMobClickAt == null) it.adMobClickAt = at }
        }
    }

    private class FakeNativeAdvertisementViewPublisher : NativeAdvertisementViewPublishPort {
        val events = mutableListOf<NativeAdvertisementViewedEvent>()
        override suspend fun publish(event: NativeAdvertisementViewedEvent): Boolean {
            events += event
            return true
        }
    }

    private class FakeUserBlockPort : UserBlockPort {
        val rows = mutableListOf<UserBlockEntity>()

        override suspend fun insertIfAbsent(entity: UserBlockEntity): Boolean = synchronized(rows) {
            if (rows.any {
                    it.blockerUserId == entity.blockerUserId && it.blockedUserId == entity.blockedUserId
                }
            ) {
                false
            } else {
                if (entity.id == 0L) entity.id = (rows.maxOfOrNull { it.id } ?: 0L) + 1L
                rows += entity
                true
            }
        }

        override suspend fun exists(blockerUserId: Long, blockedUserId: Long): Boolean =
            rows.any { it.blockerUserId == blockerUserId && it.blockedUserId == blockedUserId }

        override suspend fun findBlockedUserIds(blockerUserId: Long): Set<Long> =
            rows.filter { it.blockerUserId == blockerUserId }.map { it.blockedUserId }.toSet()

        override suspend fun delete(blockerUserId: Long, blockedUserId: Long): Long {
            val removed = rows.removeIf {
                it.blockerUserId == blockerUserId && it.blockedUserId == blockedUserId
            }
            return if (removed) 1 else 0
        }
    }

    private class FakeReactionPublisher : PublicQuestionReactionPublishPort {
        val events = mutableListOf<String>()

        override suspend fun publishViewed(
            questionId: Long,
            userId: Long?,
            localization: PublicQuestionViewLocalization?,
        ): Boolean {
            events += "CONTENT_VIEWED:$questionId:$userId"
            return true
        }

        override suspend fun publishLiked(questionId: Long, userId: Long): Boolean {
            events += "QUESTION_LIKED:$questionId:$userId"
            return true
        }

        override suspend fun publishUnliked(questionId: Long, userId: Long): Boolean {
            events += "QUESTION_UNLIKED:$questionId:$userId"
            return true
        }

        override suspend fun publishCommented(questionId: Long, commentId: Long, userId: Long): Boolean {
            events += "QUESTION_COMMENTED:$questionId:$commentId:$userId"
            return true
        }

        override suspend fun publishCommentDeleted(questionId: Long, commentId: Long, userId: Long): Boolean {
            events += "QUESTION_COMMENT_DELETED:$questionId:$commentId:$userId"
            return true
        }
    }

    private class FakeNotificationPublisher : PublishNotificationUseCase {
        val rows = mutableListOf<NotificationRequestCommand>()
        override suspend fun publish(command: NotificationRequestCommand): Boolean {
            rows += command
            return true
        }
    }

    private class ImmediateAfterCommit : AfterCommitPort {
        override suspend fun execute(action: suspend () -> Unit) = action()
    }

    private class RecordingOutboxPublisher : PublishOutboxUseCase {
        val published = mutableListOf<OutboxReference>()

        override suspend fun publishNow(references: Collection<OutboxReference>): OutboxPublishSummary {
            published += references
            return OutboxPublishSummary(references.size, references.size, 0)
        }
    }
}
