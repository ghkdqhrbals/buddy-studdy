package com.buddystuddy.backend.api

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.TokenService
import com.buddystuddy.backend.auth.sha256
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.crypto.KeyCipher
import com.buddystuddy.backend.domain.*
import com.buddystuddy.backend.dto.*
import com.buddystuddy.backend.openai.OpenAIClient
import com.buddystuddy.backend.stats.StatsService
import com.buddystuddy.backend.stream.QuestionStreamEventType
import com.buddystuddy.backend.stream.RedisStreamCoordinatorService
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import kotlin.math.min

@Service
class ApiService(
    private val properties: BuddyStuddyProperties,
    private val users: UserRepository,
    private val devices: DeviceRepository,
    private val userDevices: UserDeviceRepository,
    private val schedules: ScheduleRepository,
    private val questions: QuestionRepository,
    private val questionStats: QuestionStatsRepository,
    private val likes: QuestionLikeRepository,
    private val comments: QuestionCommentRepository,
    private val reports: ReportRepository,
    private val tokenService: TokenService,
    private val cipher: KeyCipher,
    private val openAI: OpenAIClient,
    private val statsService: StatsService,
    private val streams: RedisStreamCoordinatorService,
) {
    private val random = SecureRandom()
    private val googleRest = RestClient.builder().baseUrl("https://oauth2.googleapis.com").build()

    @Transactional
    fun register(payload: DeviceRegisterRequest): DeviceRegisterResponse {
        val deviceId = randomToken("dev")
        val secret = randomToken("sec")
        val now = Instant.now()
        val user = users.save(
            UserEntity(
                provider = "ANONYMOUS",
                providerId = deviceId,
                status = "ANONYMOUS",
                email = "",
                displayName = "Buddy",
                avatarColorSeed = "avatar-color-gray",
                createdAt = now,
                updatedAt = now,
            )
        )
        val device = devices.save(
            DeviceEntity(
                deviceId = deviceId,
                clientSecretHash = sha256(secret),
                userId = user.id,
                apnsToken = payload.apnsToken,
                platform = payload.platform,
                apnsEnvironment = payload.apnsEnvironment,
                language = payload.language,
                timezone = payload.timezone,
                createdAt = now,
                updatedAt = now,
                lastSeenAt = now,
            )
        )
        val session = saveSession(user.id, device.deviceId, now, null)
        val token = tokenService.create(user.id, device.deviceId, session.id, true)
        return DeviceRegisterResponse(device.deviceId, secret, token.first, token.second)
    }

    @Transactional
    fun token(deviceId: String, clientSecret: String): AccessTokenResponse {
        val device = devices.findByDeviceId(deviceId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.DEVICE_NOT_FOUND, "Device not found.")
        if (device.clientSecretHash != sha256(clientSecret)) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_DEVICE_CREDENTIALS, "Invalid device credentials.")
        }
        val userId = device.userId ?: ensureAnonymousUser(device).id
        val user = users.findById(userId).orElseThrow()
        val session = saveSession(user.id, device.deviceId, Instant.now(), if (user.status == "ANONYMOUS") null else Instant.now().plusSeconds(90 * 86_400))
        val token = tokenService.create(user.id, device.deviceId, session.id, user.status == "ANONYMOUS")
        return AccessTokenResponse(token.first, token.second)
    }

    @Transactional
    fun updatePushToken(principal: Principal, payload: PushTokenRequest) {
        val device = device(principal.deviceId)
        device.apnsToken = payload.apnsToken
        device.apnsEnvironment = payload.apnsEnvironment
        device.updatedAt = Instant.now()
    }

    @Transactional
    fun emailLogin(principal: Principal, payload: EmailLoginRequest): GoogleLoginResponse {
        val now = Instant.now()
        val normalized = payload.email.trim().lowercase()
        var user = users.findByEmailAndProvider(normalized, "EMAIL")
        if (user == null) {
            // Verification code validation is kept as a backend contract; Redis-backed TTL storage can be expanded here.
            if (payload.verificationCode.isNullOrBlank()) {
                throw ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.AUTH_GOOGLE_REQUIRED, "Email verification code is required.")
            }
            user = users.save(
                UserEntity(
                    provider = "EMAIL",
                    providerId = normalized,
                    email = normalized,
                    passwordHash = sha256(payload.password),
                    status = "ACTIVE",
                    displayName = normalized.substringBefore("@"),
                    avatarColorSeed = "avatar-color-mint",
                    createdAt = now,
                    updatedAt = now,
                )
            )
        } else if (user.passwordHash != sha256(payload.password)) {
            throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_DEVICE_CREDENTIALS, "Invalid email or password.")
        }
        val device = device(principal.deviceId)
        device.userId = user.id
        device.updatedAt = now
        val session = saveSession(user.id, device.deviceId, now, now.plusSeconds(90 * 86_400))
        val token = tokenService.create(user.id, device.deviceId, session.id, false)
        return GoogleLoginResponse(user.toProfile(), token.first, token.second)
    }

    fun emailCode(email: String) = EmailVerificationCodeResponse(email.trim().lowercase(), properties.email.verificationTtlSeconds)

    @Transactional
    fun googleLogin(principal: Principal, idToken: String): GoogleLoginResponse {
        val tokenInfo = googleRest.get()
            .uri { it.path("/tokeninfo").queryParam("id_token", idToken).build() }
            .retrieve()
            .body(Map::class.java)
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "Invalid Google token.")
        val providerId = tokenInfo["sub"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "Invalid Google token.")
        val email = tokenInfo["email"]?.toString() ?: ""
        val name = tokenInfo["name"]?.toString()?.takeIf { it.isNotBlank() } ?: email.substringBefore("@").ifBlank { "Buddy" }
        val now = Instant.now()
        val user = users.findByProviderAndProviderId("GOOGLE", providerId) ?: users.save(
            UserEntity(
                provider = "GOOGLE",
                providerId = providerId,
                email = email,
                status = "ACTIVE",
                displayName = name,
                avatarColorSeed = "avatar-color-mint",
                createdAt = now,
                updatedAt = now,
            )
        )
        val device = device(principal.deviceId)
        device.userId = user.id
        device.updatedAt = now
        val session = saveSession(user.id, device.deviceId, now, now.plusSeconds(90 * 86_400))
        val token = tokenService.create(user.id, device.deviceId, session.id, false)
        return GoogleLoginResponse(user.toProfile(), token.first, token.second)
    }

    @Transactional(readOnly = true)
    fun profile(principal: Principal): UserProfileResponse = user(principal.userId).toProfile()

    @Transactional
    fun updateProfile(principal: Principal, payload: ProfileUpdateRequest): UserProfileResponse {
        val user = user(principal.userId)
        payload.displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { user.displayName = it.take(120) }
        payload.bio?.let { user.bio = it.take(500) }
        payload.avatarSymbolName?.let { user.avatarSymbolName = it.take(64) }
        payload.avatarColorSeed?.let { user.avatarColorSeed = it.take(64) }
        payload.pageAccess?.let { user.allowPublicQuestions = it.publicQuestions }
        user.updatedAt = Instant.now()
        return user.toProfile()
    }

    @Transactional
    fun upsertSchedule(principal: Principal, payload: ScheduleRequest): ScheduleResponse {
        val now = Instant.now()
        val encryptedKey = cipher.encrypt(payload.openaiApiKey)
        val items = payload.schedules?.takeIf { it.isNotEmpty() } ?: listOf(
            ScheduleItemRequest(payload.topic.ifBlank { "SwiftUI" }, payload.difficultyLevel, payload.customPrompt, payload.openaiModel)
        )
        var next: Instant? = null
        items.forEach { item ->
            val schedule = schedules.findByDeviceIdAndUserIdAndTopic(principal.deviceId, principal.userId, item.topic)
                ?: ScheduleEntity(deviceId = principal.deviceId, userId = principal.userId, topic = item.topic, createdAt = now)
            schedule.difficultyLevel = item.difficultyLevel
            schedule.intervalMinutes = payload.intervalMinutes
            schedule.enabled = payload.enabled
            if (encryptedKey != null) schedule.openaiApiKeyCipher = encryptedKey
            schedule.notificationSound = payload.notificationSound
            schedule.customPrompt = item.customPrompt
            schedule.appLanguage = payload.appLanguage
            schedule.openaiModel = item.openaiModel.ifBlank { payload.openaiModel }
            schedule.maxHistoryCount = payload.maxHistoryCount
            schedule.questionPublic = payload.isQuestionPublic && !principal.anonymous
            schedule.nextDueAt = schedule.nextDueAt ?: now.plusSeconds(payload.intervalMinutes.toLong() * 60)
            schedule.updatedAt = now
            next = schedules.save(schedule).nextDueAt
        }
        return ScheduleResponse(principal.deviceId, payload.enabled, next)
    }

    @Transactional(readOnly = true)
    fun settings(principal: Principal): BackendSettingsResponse =
        schedules.findFirstByDeviceIdAndUserIdOrderByUpdatedAtDesc(principal.deviceId, principal.userId).toSettings()

    @Transactional(readOnly = true)
    fun apiStatus(principal: Principal): APIStatusResponse {
        val schedule = schedules.findFirstByDeviceIdAndUserIdOrderByUpdatedAtDesc(principal.deviceId, principal.userId)
        return APIStatusResponse(!schedule?.openaiApiKeyCipher.isNullOrBlank(), schedule?.openaiModel ?: properties.openai.model)
    }

    @Transactional
    fun createQuestion(principal: Principal, topic: String?): StudyRecordResponse {
        val schedule = scheduleFor(principal, topic)
        if (questions.countPendingForStudy(principal.deviceId, principal.userId, schedule.topic) >= properties.scheduler.maxPendingPerStudy) {
            throw ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_ERROR, "A pending question already exists for this study.")
        }
        val generated = openAI.generateQuestion(apiKeyFor(schedule), schedule.openaiModel, schedule.topic, schedule.difficultyLevel, schedule.appLanguage, schedule.customPrompt, recentQuestions(principal))
        val question = questions.save(
            QuestionEntity(
                deviceId = principal.deviceId,
                userId = principal.userId,
                question = generated.question,
                hint = generated.hint,
                topic = schedule.topic,
                difficultyLevel = schedule.difficultyLevel,
                scheduledFor = Instant.now(),
                sentAt = Instant.now(),
                status = "ungraded",
                source = "manual",
                publicQuestion = schedule.questionPublic,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        )
        questionStats.save(QuestionStatsEntity(questionId = question.id))
        return question.toRecord()
    }

    @Transactional
    fun answer(principal: Principal, recordId: Long, answer: String, grade: Boolean): StudyRecordResponse {
        val q = questions.findByIdAndUserIdAndDeletedAtIsNull(recordId, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        q.answer = answer
        q.answeredAt = Instant.now()
        if (grade && q.score == null) {
            val schedule = schedules.findByDeviceIdAndUserIdAndTopic(principal.deviceId, principal.userId, q.topic)
                ?: schedules.findFirstByDeviceIdAndUserIdOrderByUpdatedAtDesc(principal.deviceId, principal.userId)
            val graded = openAI.grade(apiKeyFor(schedule), schedule?.openaiModel ?: properties.openai.model, q.question, answer, q.topic, q.difficultyLevel, schedule?.appLanguage ?: "ko")
            q.score = graded.score
            q.correct = graded.isCorrect
            q.feedback = graded.feedback
            q.explanation = graded.explanation
            q.gradedAt = Instant.now()
            q.status = "graded"
        }
        q.updatedAt = Instant.now()
        return q.toRecord(questionStats.findById(q.id).orElse(null))
    }

    @Transactional(readOnly = true)
    fun records(principal: Principal, limit: Int, offset: Int): RecordsPageResponse {
        val page = questions.findVisibleByUser(principal.userId, includePending = false, PageRequest.of(offset / limit, limit))
        return RecordsPageResponse(page.content.map { it.toRecord(questionStats.findById(it.id).orElse(null)) }, page.totalElements, limit, offset)
    }

    @Transactional(readOnly = true)
    fun pending(principal: Principal, limit: Int, offset: Int): RecordsPageResponse {
        val page = questions.findPendingByUser(principal.userId, PageRequest.of(offset / limit, limit))
        return RecordsPageResponse(page.content.map { it.toRecord(questionStats.findById(it.id).orElse(null)) }, page.totalElements, limit, offset)
    }

    @Transactional(readOnly = true)
    fun record(principal: Principal, id: Long): StudyRecordResponse =
        (questions.findByIdAndUserIdAndDeletedAtIsNull(id, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found."))
            .toRecord(questionStats.findById(id).orElse(null))

    @Transactional
    fun skip(principal: Principal, id: Long): StudyRecordResponse {
        val q = questions.findByIdAndUserIdAndDeletedAtIsNull(id, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        q.skippedAt = Instant.now()
        q.status = "skipped"
        q.updatedAt = Instant.now()
        return q.toRecord(questionStats.findById(id).orElse(null))
    }

    @Transactional
    fun delete(principal: Principal, id: Long) {
        questions.softDelete(id, principal.userId, Instant.now())
    }

    @Transactional
    fun publicity(principal: Principal, id: Long, isPublic: Boolean): StudyRecordResponse {
        val q = questions.findByIdAndUserIdAndDeletedAtIsNull(id, principal.userId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")
        q.publicQuestion = isPublic && q.score != null
        q.updatedAt = Instant.now()
        return q.toRecord(questionStats.findById(id).orElse(null))
    }

    @Transactional(readOnly = true)
    fun stats(principal: Principal, limit: Int, offset: Int) = statsService.stats(principal.userId, limit, offset)

    @Transactional(readOnly = true)
    fun snapshot(principal: Principal, limit: Int, offset: Int): BackendSnapshotResponse {
        val records = records(principal, limit, offset)
        return BackendSnapshotResponse(settings(principal), apiStatus(principal), records.records, stats(principal, 8, 0), records.totalCount, Instant.now())
    }

    @Transactional(readOnly = true)
    fun publicQuestions(principal: Principal?, topic: String?, limit: Int, offset: Int): CommunityQuestionsResponse {
        val pageable = PageRequest.of(offset / limit, limit)
        val normalizedTopic = topic?.takeIf { it.isNotBlank() }
        val page = if (normalizedTopic == null) {
            questions.findPublicAnswered(pageable)
        } else {
            questions.findPublicAnsweredByTopic(normalizedTopic, pageable)
        }
        val rows = page.content.map { community(it, principal) }
        return CommunityQuestionsResponse(rows, page.totalElements, limit, offset)
    }

    @Transactional
    fun publicQuestion(principal: Principal?, id: Long): CommunityQuestionResponse {
        val q = publicAnsweredQuestion(id)
        streams.publishQuestionViewed(id, principal?.userId)
        return community(q, principal)
    }

    @Transactional
    fun setLike(principal: Principal, id: Long, liked: Boolean): CommunityLikeResponse {
        publicAnsweredQuestion(id)
        var delta = 0
        if (liked) {
            if (!likes.existsByQuestionIdAndUserId(id, principal.userId)) {
                likes.save(QuestionLikeEntity(questionId = id, userId = principal.userId))
                delta = 1
                streams.publishQuestionChanged(id, QuestionStreamEventType.QUESTION_LIKED, principal.userId)
            }
        } else {
            if (likes.deleteByQuestionIdAndUserId(id, principal.userId) > 0) {
                delta = -1
                streams.publishQuestionChanged(id, QuestionStreamEventType.QUESTION_UNLIKED, principal.userId)
            }
        }
        val stats = questionStats.findById(id).orElse(QuestionStatsEntity(questionId = id))
        return CommunityLikeResponse(id.toString(), (stats.likeCount + delta).coerceAtLeast(0), liked)
    }

    @Transactional
    fun comment(principal: Principal, id: Long, body: String): CommunityCommentResponse {
        publicAnsweredQuestion(id)
        val saved = comments.save(QuestionCommentEntity(questionId = id, userId = principal.userId, body = body.take(1000)))
        streams.publishQuestionChanged(id, QuestionStreamEventType.QUESTION_COMMENTED, principal.userId)
        return saved.toResponse(user(principal.userId).toProfile())
    }

    @Transactional(readOnly = true)
    fun comments(id: Long, limit: Int, offset: Int): CommunityCommentsResponse {
        publicAnsweredQuestion(id)
        val page = comments.findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtDesc(id, PageRequest.of(offset / limit, limit))
        val profiles = users.findAllById(page.content.map { it.userId }).associateBy { it.id }
        return CommunityCommentsResponse(
            page.content.map { it.toResponse(profiles[it.userId]?.toProfile() ?: UserProfileResponse(0, "Buddy")) },
            page.totalElements,
            limit,
            offset,
        )
    }

    @Transactional
    fun report(principal: Principal, id: Long, payload: ReportQuestionRequest) {
        publicAnsweredQuestion(id)
        reports.save(ReportEntity(questionId = id, reporterDeviceId = principal.deviceId, reporterUserId = principal.userId, reason = payload.reason, message = payload.message))
    }

    private fun community(q: QuestionEntity, principal: Principal?): CommunityQuestionResponse {
        val author = q.userId?.let { users.findById(it).orElse(null)?.toProfile() }
        val stats = questionStats.findById(q.id).orElse(null)
        val liked = principal?.let { likes.existsByQuestionIdAndUserId(q.id, it.userId) } ?: false
        return q.toCommunity(author, stats, liked)
    }

    private fun publicAnsweredQuestion(id: Long): QuestionEntity =
        questions.findPublicAnsweredById(id)
            ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RECORD_NOT_FOUND, "Record not found.")

    private fun apiKeyFor(schedule: ScheduleEntity?): String =
        cipher.decrypt(schedule?.openaiApiKeyCipher) ?: properties.openai.apiKey.takeIf { it.isNotBlank() }
        ?: throw ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.OPENAI_API_KEY_MISSING, "OpenAI API key is not configured.")

    private fun scheduleFor(principal: Principal, topic: String?): ScheduleEntity =
        topic?.takeIf { it.isNotBlank() }?.let { schedules.findByDeviceIdAndUserIdAndTopic(principal.deviceId, principal.userId, it) }
            ?: schedules.findFirstByDeviceIdAndUserIdOrderByUpdatedAtDesc(principal.deviceId, principal.userId)
            ?: throw ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.STUDY_SETTINGS_MISSING, "Study settings are not configured.")

    private fun recentQuestions(principal: Principal): List<String> =
        questions.findVisibleByUser(principal.userId, includePending = true, PageRequest.of(0, 30)).content.map { it.question }

    private fun ensureAnonymousUser(device: DeviceEntity): UserEntity {
        val now = Instant.now()
        val user = users.save(UserEntity(provider = "ANONYMOUS", providerId = device.deviceId, status = "ANONYMOUS", displayName = "Buddy", avatarColorSeed = "avatar-color-gray", createdAt = now, updatedAt = now))
        device.userId = user.id
        return user
    }

    private fun saveSession(userId: Long, deviceId: String, now: Instant, expiresAt: Instant?): UserDeviceEntity {
        val session = userDevices.findByUserIdAndDeviceId(userId, deviceId) ?: UserDeviceEntity(userId = userId, deviceId = deviceId, createdAt = now)
        session.lastLoginAt = now
        session.lastSeenAt = now
        session.updatedAt = now
        session.sessionExpiresAt = expiresAt
        return userDevices.save(session)
    }

    private fun user(id: Long) = users.findById(id).orElseThrow {
        ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTH_INVALID_ACCESS_TOKEN, "User not found.")
    }

    private fun device(deviceId: String) = devices.findByDeviceId(deviceId)
        ?: throw ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.DEVICE_NOT_FOUND, "Device not found.")

    private fun randomToken(prefix: String): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return "$prefix-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
