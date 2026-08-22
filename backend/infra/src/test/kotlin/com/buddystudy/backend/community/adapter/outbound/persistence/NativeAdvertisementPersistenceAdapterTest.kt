package com.buddystudy.backend.community.adapter.outbound.persistence

import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.data.r2dbc.repository.Query
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Instant

class NativeAdvertisementPersistenceAdapterTest {
    private val database = DatabaseClient.create(
        ConnectionFactories.get(
            "r2dbc:h2:mem:///native-ad-admin-users;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        ),
    )
    private val adapter = NativeAdvertisementPersistenceAdapter(
        campaigns = mock(NativeAdvertisementCampaignRepository::class.java),
        selections = mock(NativeAdvertisementSelectionRepository::class.java),
        database = database,
    )

    @BeforeEach
    fun setUp() {
        runBlocking {
            execute("drop table if exists native_ad_selection_history")
            execute("drop table if exists users")
            execute(
                """
                create table users (
                    id bigint primary key,
                    status varchar(24) not null,
                    email varchar(320) not null,
                    display_name varchar(120) not null
                )
                """.trimIndent(),
            )
            execute(
                """
                create table native_ad_selection_history (
                    id bigint auto_increment primary key,
                    campaign_id bigint not null,
                    user_id bigint not null,
                    device_id varchar(255) not null,
                    selected_at timestamp not null,
                    viewed_at timestamp null
                )
                """.trimIndent(),
            )
            execute(
                """
                insert into users (id, status, email, display_name) values
                    (10, 'ACTIVE', 'member@example.com', 'Member'),
                    (11, 'ANONYMOUS', '', 'Buddy'),
                    (12, 'ACTIVE', 'other@example.com', 'Other')
                """.trimIndent(),
            )
            execute(
                """
                insert into native_ad_selection_history
                    (campaign_id, user_id, device_id, selected_at, viewed_at)
                values
                    (7, 10, 'device-a', timestamp '2026-08-01 00:00:00', timestamp '2026-08-01 00:05:00'),
                    (7, 10, 'device-a', timestamp '2026-08-02 00:00:00', null),
                    (7, 10, 'device-b', timestamp '2026-08-03 00:00:00', timestamp '2026-08-03 00:05:00'),
                    (7, 11, 'anonymous-device', timestamp '2026-08-04 00:00:00', null),
                    (8, 12, 'other-device', timestamp '2026-08-05 00:00:00', timestamp '2026-08-05 00:05:00')
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `campaign users aggregate deliveries opens devices and timestamps`(): Unit = runBlocking {
        val page = adapter.campaignUsers(7, query = null, status = null, limit = 20, offset = 0)

        assertThat(page.totalCount).isEqualTo(2)
        val users = page.users.associateBy { it.userId }
        assertThat(users.getValue(10).selectionCount).isEqualTo(3)
        assertThat(users.getValue(10).destinationOpenCount).isEqualTo(2)
        assertThat(users.getValue(10).openRate).isEqualTo(2.0 / 3.0)
        assertThat(users.getValue(10).distinctDeviceCount).isEqualTo(2)
        assertThat(users.getValue(10).firstSelectedAt).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"))
        assertThat(users.getValue(10).lastSelectedAt).isEqualTo(Instant.parse("2026-08-03T00:00:00Z"))
        assertThat(users.getValue(10).lastViewedAt).isEqualTo(Instant.parse("2026-08-03T00:05:00Z"))
        assertThat(users.getValue(11).selectionCount).isEqualTo(1)
        assertThat(users.getValue(11).destinationOpenCount).isZero()
    }

    @Test
    fun `campaign users filter by identity query and aggregate open status`(): Unit = runBlocking {
        val opened = adapter.campaignUsers(7, query = "MEMBER@EXAMPLE.COM", status = "OPENED", limit = 20, offset = 0)
        val notOpened = adapter.campaignUsers(7, query = null, status = "NOT_OPENED", limit = 20, offset = 0)

        assertThat(opened.totalCount).isEqualTo(1)
        assertThat(opened.users).extracting<Long> { it.userId }.containsExactly(10)
        assertThat(notOpened.totalCount).isEqualTo(1)
        assertThat(notOpened.users).extracting<Long> { it.userId }.containsExactly(11)
    }

    @Test
    fun `campaign performance opens use the same selected cohort as deliveries`() {
        val query = NativeAdvertisementSelectionRepository::class.java.methods
            .single { it.name == "countCampaignViewsSince" }
            .getAnnotation(Query::class.java)
            .value

        assertThat(query).contains("selected_at >= :since")
        assertThat(query).contains("viewed_at is not null")
        assertThat(query).doesNotContain("viewed_at >= :since")
    }

    private suspend fun execute(sql: String) {
        database.sql(sql).fetch().rowsUpdated().awaitSingle()
    }
}
