package com.buddystuddy.backend

import com.buddystuddy.backend.domain.QuestionStatsEntity
import com.buddystuddy.backend.study.application.port.outbound.QuestionStatsPort
import com.buddystuddy.backend.community.adapter.inbound.stream.QuestionStatsStreamListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:buddystuddy-streams;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "buddystuddy.scheduler.enabled=false",
        "buddystuddy.streams.enabled=false",
        "buddystuddy.crypto.master-key=test-master-key",
        "buddystuddy.auth.jwt-secret=test-jwt-secret",
        "spring.autoconfigure.exclude=com.redisstream.RedisStreamCoordinatorAutoConfiguration,com.redisstream.producer.ProducerRoutingAutoConfiguration,com.redisstream.consumer.CoordinatorConsumerAutoConfiguration",
    ]
)
class QuestionStatsStreamListenerTest {
    @Autowired lateinit var listener: QuestionStatsStreamListener
    @Autowired lateinit var stats: QuestionStatsPort

    @Test
    fun `view events increment question view count`() {
        stats.save(QuestionStatsEntity(questionId = 101))

        listener.processViewEvent(mapOf("eventType" to "CONTENT_VIEWED", "questionId" to "101"))
        listener.processViewEvent(mapOf("eventType" to "CONTENT_VIEWED", "questionId" to "101"))

        assertThat(stats.findById(101).orElseThrow().viewCount).isEqualTo(2)
    }

    @Test
    fun `like and comment action events update aggregate counts`() {
        stats.save(QuestionStatsEntity(questionId = 202))

        listener.processActionEvent(mapOf("eventType" to "QUESTION_LIKED", "questionId" to "202"))
        listener.processActionEvent(mapOf("eventType" to "QUESTION_COMMENTED", "questionId" to "202"))
        listener.processActionEvent(mapOf("eventType" to "QUESTION_COMMENT_DELETED", "questionId" to "202"))
        listener.processActionEvent(mapOf("eventType" to "QUESTION_UNLIKED", "questionId" to "202"))

        val updated = stats.findById(202).orElseThrow()
        assertThat(updated.likeCount).isEqualTo(0)
        assertThat(updated.commentCount).isEqualTo(0)
    }

    @Test
    fun `stream event creates stats row when aggregate row is missing`() {
        listener.processActionEvent(mapOf("eventType" to "QUESTION_LIKED", "questionId" to "303"))

        assertThat(stats.findById(303).orElseThrow().likeCount).isEqualTo(1)
    }

    @Test
    fun `view event accepts record id fallback`() {
        stats.save(QuestionStatsEntity(questionId = 404))

        listener.processViewEvent(mapOf("eventType" to "CONTENT_VIEWED", "recordId" to "404"))

        assertThat(stats.findById(404).orElseThrow().viewCount).isEqualTo(1)
    }

    @Test
    fun `invalid ids and unknown action events are ignored`() {
        stats.save(QuestionStatsEntity(questionId = 505, likeCount = 2, commentCount = 3, viewCount = 4))

        listener.processViewEvent(mapOf("eventType" to "CONTENT_VIEWED", "questionId" to "not-a-number"))
        listener.processActionEvent(mapOf("eventType" to "UNKNOWN", "questionId" to "505"))
        listener.processActionEvent(mapOf("eventType" to "QUESTION_LIKED"))

        val updated = stats.findById(505).orElseThrow()
        assertThat(updated.likeCount).isEqualTo(2)
        assertThat(updated.commentCount).isEqualTo(3)
        assertThat(updated.viewCount).isEqualTo(4)
    }
}
