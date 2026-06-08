package com.buddystuddy.backend.study.application.service

import com.buddystuddy.backend.auth.Principal
import com.buddystuddy.backend.auth.application.port.outbound.UserPort
import com.buddystuddy.backend.common.application.error.ApiErrorCode
import com.buddystuddy.backend.common.application.error.ApiException
import com.buddystuddy.backend.config.BuddyStuddyProperties
import com.buddystuddy.backend.crypto.KeyCipher
import com.buddystuddy.domain.ScheduleEntity
import com.buddystuddy.backend.study.application.port.outbound.QuestionPort
import com.buddystuddy.backend.study.application.port.outbound.SchedulePort
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

@Component
class StudyContextProvider(
    private val properties: BuddyStuddyProperties,
    private val users: UserPort,
    private val schedules: SchedulePort,
    private val questions: QuestionPort,
    private val cipher: KeyCipher,
) {
    fun apiKeyFor(principal: Principal, schedule: ScheduleEntity?): String {
        val user = users.findById(principal.userId).orElse(null)
        return cipher.decrypt(user?.openaiApiKeyCipher)
            ?: cipher.decrypt(schedule?.openaiApiKeyCipher)
            ?: properties.openai.apiKey.takeIf { it.isNotBlank() }
            ?: throw ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.OPENAI_API_KEY_MISSING, "OpenAI API key is not configured.")
    }

    fun openAIModelFor(schedule: ScheduleEntity?): String = schedule?.openaiModel ?: properties.openai.model

    fun scheduleFor(principal: Principal, topic: String?): ScheduleEntity =
        topic?.takeIf { it.isNotBlank() }?.let { schedules.findByDeviceIdAndUserIdAndTopic(principal.deviceId, principal.userId, it) ?: schedules.findByUserIdAndTopic(principal.userId, it) }
            ?: schedules.findFirstByUserIdOrderByUpdatedAtDesc(principal.userId)
            ?: throw ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.STUDY_SETTINGS_MISSING, "Study settings are not configured.")

    fun recentQuestions(principal: Principal): List<String> =
        questions.findVisibleByUser(principal.userId, includePending = true, PageRequest.of(0, 30)).content.map { it.question }
}
