package com.buddystudy.backend.config

import com.buddystudy.auth.domain.entity.ApnsEnvironment
import com.buddystudy.auth.domain.entity.DevicePlatform
import com.buddystudy.avatar.domain.entity.AvatarSlot
import com.buddystudy.common.domain.SupportedLanguage
import com.buddystudy.notification.domain.entity.NotificationThreadType
import com.buddystudy.study.domain.entity.QuestionSource
import com.buddystudy.study.domain.entity.QuestionStatus
import io.r2dbc.spi.ConnectionFactoryOptions
import org.springframework.boot.r2dbc.autoconfigure.R2dbcConnectionDetails
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions
import org.springframework.data.r2dbc.dialect.MySqlDialect
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Configuration(proxyBeanMethods = false)
class R2dbcConnectionDetailsConfig {
    @Bean
    fun r2dbcConnectionDetails(environment: Environment): R2dbcConnectionDetails =
        EnvironmentR2dbcConnectionDetails(environment)

    @Bean
    fun r2dbcCustomConversions(): R2dbcCustomConversions =
        R2dbcCustomConversions.of(
            MySqlDialect.INSTANCE,
            OffsetDateTimeToInstantConverter,
            LocalDateTimeToInstantConverter,
            InstantToLocalDateTimeConverter,
            StringToSupportedLanguageConverter,
            SupportedLanguageToStringConverter,
            StringToDevicePlatformConverter,
            DevicePlatformToStringConverter,
            StringToApnsEnvironmentConverter,
            ApnsEnvironmentToStringConverter,
            StringToAvatarSlotConverter,
            AvatarSlotToStringConverter,
            StringToNotificationThreadTypeConverter,
            NotificationThreadTypeToStringConverter,
            StringToQuestionStatusConverter,
            QuestionStatusToStringConverter,
            StringToQuestionSourceConverter,
            QuestionSourceToStringConverter,
        )
}

@ReadingConverter
internal object OffsetDateTimeToInstantConverter : Converter<OffsetDateTime, Instant> {
    override fun convert(source: OffsetDateTime): Instant = source.toInstant()
}

@ReadingConverter
internal object LocalDateTimeToInstantConverter : Converter<LocalDateTime, Instant> {
    override fun convert(source: LocalDateTime): Instant = source.toInstant(ZoneOffset.UTC)
}

@WritingConverter
internal object InstantToLocalDateTimeConverter : Converter<Instant, LocalDateTime> {
    override fun convert(source: Instant): LocalDateTime = LocalDateTime.ofInstant(source, ZoneOffset.UTC)
}

@ReadingConverter
internal object StringToSupportedLanguageConverter : Converter<String, SupportedLanguage> {
    override fun convert(source: String): SupportedLanguage = SupportedLanguage.fromDatabaseValue(source)
}

@WritingConverter
internal object SupportedLanguageToStringConverter : Converter<SupportedLanguage, String> {
    override fun convert(source: SupportedLanguage): String = source.databaseValue
}

@ReadingConverter
internal object StringToDevicePlatformConverter : Converter<String, DevicePlatform> {
    override fun convert(source: String): DevicePlatform = DevicePlatform.fromDatabaseValue(source)
}

@WritingConverter
internal object DevicePlatformToStringConverter : Converter<DevicePlatform, String> {
    override fun convert(source: DevicePlatform): String = source.databaseValue
}

@ReadingConverter
internal object StringToApnsEnvironmentConverter : Converter<String, ApnsEnvironment> {
    override fun convert(source: String): ApnsEnvironment = ApnsEnvironment.fromDatabaseValue(source)
}

@WritingConverter
internal object ApnsEnvironmentToStringConverter : Converter<ApnsEnvironment, String> {
    override fun convert(source: ApnsEnvironment): String = source.databaseValue
}

@ReadingConverter
internal object StringToAvatarSlotConverter : Converter<String, AvatarSlot> {
    override fun convert(source: String): AvatarSlot = AvatarSlot.fromDatabaseValue(source)
}

@WritingConverter
internal object AvatarSlotToStringConverter : Converter<AvatarSlot, String> {
    override fun convert(source: AvatarSlot): String = source.databaseValue
}

@ReadingConverter
internal object StringToNotificationThreadTypeConverter : Converter<String, NotificationThreadType> {
    override fun convert(source: String): NotificationThreadType = NotificationThreadType.fromDatabaseValue(source)
}

@WritingConverter
internal object NotificationThreadTypeToStringConverter : Converter<NotificationThreadType, String> {
    override fun convert(source: NotificationThreadType): String = source.databaseValue
}

@ReadingConverter
internal object StringToQuestionStatusConverter : Converter<String, QuestionStatus> {
    override fun convert(source: String): QuestionStatus = QuestionStatus.fromDatabaseValue(source)
}

@WritingConverter
internal object QuestionStatusToStringConverter : Converter<QuestionStatus, String> {
    override fun convert(source: QuestionStatus): String = source.databaseValue
}

@ReadingConverter
internal object StringToQuestionSourceConverter : Converter<String, QuestionSource> {
    override fun convert(source: String): QuestionSource = QuestionSource.fromDatabaseValue(source)
}

@WritingConverter
internal object QuestionSourceToStringConverter : Converter<QuestionSource, String> {
    override fun convert(source: QuestionSource): String = source.databaseValue
}

internal class EnvironmentR2dbcConnectionDetails(
    private val environment: Environment,
) : R2dbcConnectionDetails {
    override fun getConnectionFactoryOptions(): ConnectionFactoryOptions {
        val url =
            environment.firstNonBlank("spring.r2dbc.url")
                ?: environment.firstNonBlank("R2DBC_DATABASE_URL")
                ?: environment.firstNonBlank("DATABASE_URL")?.toR2dbcUrl()
                ?: error("R2DBC database URL is required.")
        val username =
            environment.firstNonBlank(
                "spring.r2dbc.username",
                "R2DBC_DATABASE_USERNAME",
                "DATABASE_USERNAME",
            )
        val password =
            environment.firstNonBlank(
                "spring.r2dbc.password",
                "R2DBC_DATABASE_PASSWORD",
                "DATABASE_PASSWORD",
            )
        val parsed = ConnectionFactoryOptions.parse(url)
        val driver = parsed.getRequiredValue(ConnectionFactoryOptions.DRIVER).toString()
        val protocol = parsed.getValue(ConnectionFactoryOptions.PROTOCOL)?.toString()
        val databaseDriver = if (driver == "pool") protocol else driver
        require(databaseDriver == "mysql") {
            "BuddyStudy supports only MySQL R2DBC URLs, but '$databaseDriver' was configured. " +
                "Check spring.r2dbc.url, R2DBC_DATABASE_URL, DATABASE_URL, and the active AWS secret."
        }
        val builder = parsed.mutate()

        if (!parsed.hasOption(ConnectionFactoryOptions.USER) && username != null) {
            builder.option(ConnectionFactoryOptions.USER, username)
        }
        if (!parsed.hasOption(ConnectionFactoryOptions.PASSWORD) && password != null) {
            builder.option(ConnectionFactoryOptions.PASSWORD, password)
        }
        return builder.build()
    }

    private fun Environment.firstNonBlank(vararg names: String): String? =
        names.firstNotNullOfOrNull { getProperty(it)?.takeIf(String::isNotBlank) }

    private fun String.toR2dbcUrl(): String =
        when {
            startsWith("jdbc:mysql:") ->
                replaceFirst("jdbc:mysql:", "r2dbc:mysql:")
                    .replace("serverTimezone=", "serverZoneId=")
            startsWith("jdbc:") -> removePrefix("jdbc:").let { "r2dbc:$it" }
            else -> this
        }
}
