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
import com.buddystudy.billing.domain.BillingActionStatus
import com.buddystudy.billing.domain.BillingActionType
import com.buddystudy.billing.domain.BillingEnvironment
import com.buddystudy.billing.domain.BillingEventSource
import com.buddystudy.billing.domain.BillingJobStatus
import com.buddystudy.billing.domain.BillingJobType
import com.buddystudy.billing.domain.BillingPeriod
import com.buddystudy.billing.domain.BillingProductType
import com.buddystudy.billing.domain.BillingProvider
import com.buddystudy.billing.domain.BillingReceiptStatus
import com.buddystudy.billing.domain.InvoiceEventType
import com.buddystudy.billing.domain.InvoiceStatus
import com.buddystudy.billing.domain.InvoiceType
import com.buddystudy.billing.domain.PaymentHistoryEventType
import com.buddystudy.billing.domain.PaymentStatus
import com.buddystudy.billing.domain.entity.AppleBillingNotificationEntity
import com.buddystudy.billing.domain.entity.BillingActionEntity
import com.buddystudy.billing.domain.entity.BillingJobEntity
import com.buddystudy.billing.domain.entity.InvoiceEntity
import com.buddystudy.billing.domain.entity.InvoiceEventEntity
import com.buddystudy.billing.domain.entity.MembershipTierProductEntity
import com.buddystudy.billing.domain.entity.PaymentEntity
import com.buddystudy.billing.domain.entity.PaymentHistoryEntity
import com.buddystudy.billing.domain.entity.RevenueCatBillingEventEntity
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

        assertFieldType<MembershipTierProductEntity>("provider", BillingProvider::class.java)
        assertFieldType<MembershipTierProductEntity>("productType", BillingProductType::class.java)
        assertFieldType<MembershipTierProductEntity>("billingPeriod", BillingPeriod::class.java)
        assertFieldType<InvoiceEntity>("provider", BillingProvider::class.java)
        assertFieldType<InvoiceEntity>("type", InvoiceType::class.java)
        assertFieldType<InvoiceEntity>("status", InvoiceStatus::class.java)
        assertFieldType<InvoiceEventEntity>("eventType", InvoiceEventType::class.java)
        assertFieldType<InvoiceEventEntity>("source", BillingEventSource::class.java)
        assertFieldType<PaymentEntity>("provider", BillingProvider::class.java)
        assertFieldType<PaymentEntity>("productType", BillingProductType::class.java)
        assertFieldType<PaymentEntity>("environment", BillingEnvironment::class.java)
        assertFieldType<PaymentEntity>("status", PaymentStatus::class.java)
        assertFieldType<PaymentHistoryEntity>("eventType", PaymentHistoryEventType::class.java)
        assertFieldType<PaymentHistoryEntity>("source", BillingEventSource::class.java)
        assertFieldType<BillingActionEntity>("actionType", BillingActionType::class.java)
        assertFieldType<BillingActionEntity>("status", BillingActionStatus::class.java)
        assertFieldType<BillingJobEntity>("jobType", BillingJobType::class.java)
        assertFieldType<BillingJobEntity>("status", BillingJobStatus::class.java)
        assertFieldType<AppleBillingNotificationEntity>("environment", BillingEnvironment::class.java)
        assertFieldType<AppleBillingNotificationEntity>("processingStatus", BillingReceiptStatus::class.java)
        assertFieldType<RevenueCatBillingEventEntity>("environment", BillingEnvironment::class.java)
        assertFieldType<RevenueCatBillingEventEntity>("processingStatus", BillingReceiptStatus::class.java)
    }

    private inline fun <reified T : Any> assertFieldType(fieldName: String, expected: Class<*>) {
        assertEquals(expected, T::class.java.getDeclaredField(fieldName).type, "${T::class.java.simpleName}.$fieldName")
    }
}
