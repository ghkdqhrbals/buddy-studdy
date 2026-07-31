package com.buddystudy.common.domain

import com.buddystudy.account.domain.entity.AvatarMode
import com.buddystudy.account.domain.entity.MembershipStatus
import com.buddystudy.account.domain.entity.UserEntity
import com.buddystudy.account.domain.entity.UserMembershipEntity
import com.buddystudy.account.domain.entity.UserProvider
import com.buddystudy.account.domain.entity.UserStatus
import com.buddystudy.auth.domain.entity.ApnsEnvironment
import com.buddystudy.auth.domain.entity.DeviceEntity
import com.buddystudy.auth.domain.entity.DevicePlatform
import com.buddystudy.avatar.domain.entity.AvatarCategoryEntity
import com.buddystudy.avatar.domain.entity.AvatarGrantSource
import com.buddystudy.avatar.domain.entity.AvatarItemEntity
import com.buddystudy.avatar.domain.entity.AvatarSlot
import com.buddystudy.avatar.domain.entity.UserAvatarItemEntity
import com.buddystudy.community.domain.entity.FeedbackEntity
import com.buddystudy.community.domain.entity.FeedbackStatus
import com.buddystudy.community.domain.entity.QuestionCommentEntity
import com.buddystudy.notification.domain.entity.AppNotificationEntity
import com.buddystudy.notification.domain.entity.NotificationThreadType
import com.buddystudy.notification.domain.entity.NotificationType
import com.buddystudy.study.domain.entity.AnswerGradingStatus
import com.buddystudy.study.domain.entity.GradingVerdict
import com.buddystudy.study.domain.entity.QuestionEntity
import com.buddystudy.study.domain.entity.QuestionSource
import com.buddystudy.study.domain.entity.QuestionStatus
import com.buddystudy.study.domain.entity.StudyQuestionJobEntity
import com.buddystudy.study.domain.entity.StudyQuestionJobStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class EntityEnumContractTest {
    @Test
    fun `closed entity attributes are represented by enums`() {
        assertFieldType<UserEntity>("provider", UserProvider::class.java)
        assertFieldType<UserEntity>("status", UserStatus::class.java)
        assertFieldType<UserEntity>("avatarMode", AvatarMode::class.java)
        assertFieldType<UserEntity>("appLanguage", SupportedLanguage::class.java)
        assertFieldType<UserMembershipEntity>("status", MembershipStatus::class.java)

        assertFieldType<DeviceEntity>("platform", DevicePlatform::class.java)
        assertFieldType<DeviceEntity>("apnsEnvironment", ApnsEnvironment::class.java)
        assertFieldType<DeviceEntity>("language", SupportedLanguage::class.java)

        assertFieldType<AvatarCategoryEntity>("slot", AvatarSlot::class.java)
        assertFieldType<AvatarItemEntity>("slot", AvatarSlot::class.java)
        assertFieldType<UserAvatarItemEntity>("grantedSource", AvatarGrantSource::class.java)
        assertFieldType<FeedbackEntity>("status", FeedbackStatus::class.java)
        assertFieldType<QuestionCommentEntity>("sourceLanguage", SupportedLanguage::class.java)

        assertFieldType<AppNotificationEntity>("type", NotificationType::class.java)
        assertFieldType<AppNotificationEntity>("threadType", NotificationThreadType::class.java)

        assertFieldType<QuestionEntity>("sourceLanguage", SupportedLanguage::class.java)
        assertFieldType<QuestionEntity>("answerSourceLanguage", SupportedLanguage::class.java)
        assertFieldType<QuestionEntity>("aiResponseSourceLanguage", SupportedLanguage::class.java)
        assertFieldType<QuestionEntity>("status", QuestionStatus::class.java)
        assertFieldType<QuestionEntity>("source", QuestionSource::class.java)
        assertFieldType<QuestionEntity>("gradingVerdict", GradingVerdict::class.java)
        assertFieldType<QuestionEntity>("gradingStatus", AnswerGradingStatus::class.java)
        assertFieldType<StudyQuestionJobEntity>("status", StudyQuestionJobStatus::class.java)
    }

    private inline fun <reified T : Any> assertFieldType(fieldName: String, expected: Class<*>) {
        assertEquals(expected, T::class.java.getDeclaredField(fieldName).type, "${T::class.java.simpleName}.$fieldName")
    }
}
