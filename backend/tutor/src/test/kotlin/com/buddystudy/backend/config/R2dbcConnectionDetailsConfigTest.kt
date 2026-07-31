package com.buddystudy.backend.config

import com.buddystudy.auth.domain.entity.ApnsEnvironment
import com.buddystudy.auth.domain.entity.DevicePlatform
import com.buddystudy.avatar.domain.entity.AvatarSlot
import com.buddystudy.common.domain.SupportedLanguage
import com.buddystudy.notification.domain.entity.NotificationThreadType
import com.buddystudy.study.domain.entity.QuestionSource
import com.buddystudy.study.domain.entity.QuestionStatus
import io.r2dbc.spi.ConnectionFactoryOptions
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class R2dbcConnectionDetailsConfigTest {
    @Test
    fun `explicit enum converters preserve lowercase database contracts`() {
        assertThat(SupportedLanguageToStringConverter.convert(SupportedLanguage.JAPANESE)).isEqualTo("ja")
        assertThat(StringToSupportedLanguageConverter.convert("en")).isEqualTo(SupportedLanguage.ENGLISH)
        assertThat(DevicePlatformToStringConverter.convert(DevicePlatform.IOS)).isEqualTo("ios")
        assertThat(StringToDevicePlatformConverter.convert("ios")).isEqualTo(DevicePlatform.IOS)
        assertThat(ApnsEnvironmentToStringConverter.convert(ApnsEnvironment.SANDBOX)).isEqualTo("sandbox")
        assertThat(StringToApnsEnvironmentConverter.convert("production")).isEqualTo(ApnsEnvironment.PRODUCTION)
        assertThat(AvatarSlotToStringConverter.convert(AvatarSlot.BACKGROUND)).isEqualTo("background")
        assertThat(StringToAvatarSlotConverter.convert("item")).isEqualTo(AvatarSlot.ITEM)
        assertThat(NotificationThreadTypeToStringConverter.convert(NotificationThreadType.STUDY_QUESTION))
            .isEqualTo("study_question")
        assertThat(StringToNotificationThreadTypeConverter.convert("comment")).isEqualTo(NotificationThreadType.COMMENT)
        assertThat(QuestionStatusToStringConverter.convert(QuestionStatus.UNGRADED)).isEqualTo("ungraded")
        assertThat(QuestionStatusToStringConverter.convert(QuestionStatus.GRADING)).isEqualTo("grading")
        assertThat(StringToQuestionStatusConverter.convert("skipped")).isEqualTo(QuestionStatus.SKIPPED)
        assertThat(QuestionSourceToStringConverter.convert(QuestionSource.SCHEDULED)).isEqualTo("scheduled")
        assertThat(StringToQuestionSourceConverter.convert("manual")).isEqualTo(QuestionSource.MANUAL)
    }

    @Test
    fun `Spring connection settings override deployment fallbacks`() {
        val details =
            EnvironmentR2dbcConnectionDetails(
                MockEnvironment()
                    .withProperty("spring.r2dbc.url", "r2dbc:mysql://test-db:3306/test")
                    .withProperty("spring.r2dbc.username", "test-user")
                    .withProperty("spring.r2dbc.password", "test-password")
                    .withProperty("R2DBC_DATABASE_URL", "r2dbc:mysql://production-db:3306/production")
                    .withProperty("R2DBC_DATABASE_USERNAME", "production-user")
                    .withProperty("R2DBC_DATABASE_PASSWORD", "production-password"),
            )

        val options = details.connectionFactoryOptions

        assertThat(options.getValue(ConnectionFactoryOptions.HOST)).isEqualTo("test-db")
        assertThat(options.getValue(ConnectionFactoryOptions.DATABASE)).isEqualTo("test")
        assertThat(options.getValue(ConnectionFactoryOptions.USER)).isEqualTo("test-user")
        assertThat(options.getValue(ConnectionFactoryOptions.PASSWORD).toString()).isEqualTo("test-password")
    }

    @Test
    fun `builds connection options from Spring R2DBC settings`() {
        val details =
            EnvironmentR2dbcConnectionDetails(
                MockEnvironment()
                    .withProperty("spring.r2dbc.url", "r2dbc:mysql://db:3306/buddystudy")
                    .withProperty("spring.r2dbc.username", "app-user")
                    .withProperty("spring.r2dbc.password", "app-password"),
            )

        val options = details.connectionFactoryOptions

        assertThat(options.getValue(ConnectionFactoryOptions.HOST)).isEqualTo("db")
        assertThat(options.getValue(ConnectionFactoryOptions.DATABASE)).isEqualTo("buddystudy")
        assertThat(options.getValue(ConnectionFactoryOptions.USER)).isEqualTo("app-user")
        assertThat(options.getValue(ConnectionFactoryOptions.PASSWORD).toString()).isEqualTo("app-password")
    }

    @Test
    fun `keeps credentials embedded in the R2DBC URL`() {
        val details =
            EnvironmentR2dbcConnectionDetails(
                MockEnvironment()
                    .withProperty(
                        "spring.r2dbc.url",
                        "r2dbc:mysql://url-user:url-password@db:3306/buddystudy",
                    )
                    .withProperty("spring.r2dbc.username", "property-user")
                    .withProperty("spring.r2dbc.password", "property-password"),
            )

        val options = details.connectionFactoryOptions

        assertThat(options.getValue(ConnectionFactoryOptions.USER)).isEqualTo("url-user")
        assertThat(options.getValue(ConnectionFactoryOptions.PASSWORD).toString()).isEqualTo("url-password")
    }

    @Test
    fun `falls back to JDBC deployment settings when R2DBC settings are absent`() {
        val details =
            EnvironmentR2dbcConnectionDetails(
                MockEnvironment()
                    .withProperty("DATABASE_URL", "jdbc:mysql://buddystudy-db:3306/buddystudy")
                    .withProperty("DATABASE_USERNAME", "deploy-user")
                    .withProperty("DATABASE_PASSWORD", "deploy-password"),
            )

        val options = details.connectionFactoryOptions

        assertThat(options.getValue(ConnectionFactoryOptions.HOST)).isEqualTo("buddystudy-db")
        assertThat(options.getValue(ConnectionFactoryOptions.DATABASE)).isEqualTo("buddystudy")
        assertThat(options.getValue(ConnectionFactoryOptions.USER)).isEqualTo("deploy-user")
        assertThat(options.getValue(ConnectionFactoryOptions.PASSWORD).toString()).isEqualTo("deploy-password")
    }

    @Test
    fun `maps the JDBC MySQL timezone option to R2DBC`() {
        val details =
            EnvironmentR2dbcConnectionDetails(
                MockEnvironment()
                    .withProperty(
                        "DATABASE_URL",
                        "jdbc:mysql://buddystudy-db:3306/buddystudy?serverTimezone=UTC",
                    ),
            )

        val options = details.connectionFactoryOptions

        assertThat(options.getValue(ConnectionFactoryOptions.HOST)).isEqualTo("buddystudy-db")
        assertThat(options.getValue(ConnectionFactoryOptions.DATABASE)).isEqualTo("buddystudy")
    }

    @Test
    fun `rejects a PostgreSQL URL with an actionable configuration error`() {
        val details =
            EnvironmentR2dbcConnectionDetails(
                MockEnvironment()
                    .withProperty(
                        "spring.r2dbc.url",
                        "r2dbc:postgresql://localhost:5432/buddystudy",
                    ),
            )

        assertThatThrownBy { details.connectionFactoryOptions }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("supports only MySQL R2DBC URLs")
            .hasMessageContaining("R2DBC_DATABASE_URL")
            .hasMessageContaining("active AWS secret")
    }
}
