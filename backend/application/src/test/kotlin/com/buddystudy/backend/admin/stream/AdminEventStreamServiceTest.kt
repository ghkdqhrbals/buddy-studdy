package com.buddystudy.backend.admin.stream

import com.buddystudy.backend.admin.stream.application.model.AdminCursorPage
import com.buddystudy.backend.admin.stream.application.model.AdminPushOutboxEntry
import com.buddystudy.backend.admin.stream.application.model.AdminRedisEventOutboxEntry
import com.buddystudy.backend.admin.stream.application.model.AdminStreamEntry
import com.buddystudy.backend.admin.stream.application.model.AdminStreamTopicSummary
import com.buddystudy.backend.admin.stream.application.port.outbound.AdminOutboxInspectionPort
import com.buddystudy.backend.admin.stream.application.port.outbound.AdminRedisStreamInspectionPort
import com.buddystudy.backend.admin.stream.application.service.AdminEventStreamService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AdminEventStreamServiceTest {
    private val streams = RecordingStreamPort()
    private val outboxes = RecordingOutboxPort()
    private val service = AdminEventStreamService(streams, outboxes)

    @Test
    fun `stream cursor filters and limits are normalized`(): Unit = runBlocking {
        service.streamEntries(
            topic = "domain-events",
            cursor = " invalid ",
            limit = 500,
            eventType = " question.created ",
        )

        assertThat(streams.request).isEqualTo(
            StreamRequest("domain-events", null, 100, "question.created"),
        )
    }

    @Test
    fun `valid redis stream cursor is retained`(): Unit = runBlocking {
        service.streamEntries("domain-events", "1785000998000-0", 0, " ")

        assertThat(streams.request).isEqualTo(
            StreamRequest("domain-events", "1785000998000-0", 1, null),
        )
    }

    @Test
    fun `database cursors must be positive numeric ids`(): Unit = runBlocking {
        service.redisEventOutbox("-9", 20, " PENDING ", " question.created ")
        assertThat(outboxes.eventRequest).isEqualTo(
            EventRequest(null, 20, "PENDING", "question.created"),
        )

        service.pushOutbox("94", 25, " FAILED ")
        assertThat(outboxes.pushRequest).isEqualTo(
            PushRequest(94, 25, "FAILED"),
        )
    }

    @Test
    fun `topics are searched by logical topic and redis key`(): Unit = runBlocking {
        streams.topicItems = listOf(
            topic("domain-events", "buddystudy:events"),
            topic("notifications", "buddystudy:push"),
        )

        assertThat(service.topics(" PUSH ").map { it.topic })
            .containsExactly("notifications")
        assertThat(service.topics("domain").map { it.topic })
            .containsExactly("domain-events")
        assertThat(service.topics(" ")).hasSize(2)
    }

    @Test
    fun `stream entry lookup validates id and returns the exact entry`(): Unit = runBlocking {
        streams.entryResult = AdminStreamEntry(
            id = "1785000998000-2",
            eventType = "question.created",
            eventId = "event-1",
            recordId = "42",
            userId = "7",
            deviceId = null,
            fields = mapOf("eventType" to "question.created"),
        )

        assertThat(service.streamEntry("domain-events", " 1785000998000-2 "))
            .isEqualTo(streams.entryResult)
        assertThat(streams.entryRequest).isEqualTo("domain-events" to "1785000998000-2")

        assertThatThrownBy {
            runBlocking { service.streamEntry("domain-events", "not-an-id") }
        }.hasMessageContaining("milliseconds")
    }

    @Test
    fun `missing stream entry is reported as not found`(): Unit {
        assertThatThrownBy {
            runBlocking { service.streamEntry("domain-events", "1785000998000-9") }
        }.hasMessage("Redis Stream entry was not found.")
    }

    private class RecordingStreamPort : AdminRedisStreamInspectionPort {
        var request: StreamRequest? = null
        var entryRequest: Pair<String, String>? = null
        var entryResult: AdminStreamEntry? = null
        var topicItems: List<AdminStreamTopicSummary> = emptyList()

        override suspend fun topics(): List<AdminStreamTopicSummary> = topicItems

        override suspend fun entries(
            topic: String,
            cursor: String?,
            limit: Int,
            eventType: String?,
        ): AdminCursorPage<AdminStreamEntry> {
            request = StreamRequest(topic, cursor, limit, eventType)
            return AdminCursorPage(emptyList(), null, false, limit)
        }

        override suspend fun entry(topic: String, entryId: String): AdminStreamEntry? {
            entryRequest = topic to entryId
            return entryResult
        }
    }

    private class RecordingOutboxPort : AdminOutboxInspectionPort {
        var eventRequest: EventRequest? = null
        var pushRequest: PushRequest? = null

        override suspend fun redisEvents(
            cursor: Long?,
            limit: Int,
            status: String?,
            eventType: String?,
        ): AdminCursorPage<AdminRedisEventOutboxEntry> {
            eventRequest = EventRequest(cursor, limit, status, eventType)
            return AdminCursorPage(emptyList(), null, false, limit)
        }

        override suspend fun pushes(
            cursor: Long?,
            limit: Int,
            status: String?,
        ): AdminCursorPage<AdminPushOutboxEntry> {
            pushRequest = PushRequest(cursor, limit, status)
            return AdminCursorPage(emptyList(), null, false, limit)
        }
    }

    private data class StreamRequest(
        val topic: String,
        val cursor: String?,
        val limit: Int,
        val eventType: String?,
    )

    private data class EventRequest(
        val cursor: Long?,
        val limit: Int,
        val status: String?,
        val eventType: String?,
    )

    private data class PushRequest(
        val cursor: Long?,
        val limit: Int,
        val status: String?,
    )

    private fun topic(topic: String, key: String) =
        AdminStreamTopicSummary(topic, key, 1_000, 0, null, null, emptyList())
}
