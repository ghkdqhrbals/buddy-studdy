package com.buddystudy.backend.community

import com.buddystudy.backend.common.application.json.JsonMapperProvider
import com.buddystudy.backend.community.application.model.toCommunityQuestionResponse
import com.buddystudy.community.domain.PublicQuestionProjection
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class PublicQuestionProjectionMappersTest {
    @Test
    fun `ownership defaults false and serializes as a typed viewer flag independently of missing author`() {
        val projection = projection()
        assertThat(projection.toCommunityQuestionResponse().isOwnedByMe).isFalse()
        val response = projection.toCommunityQuestionResponse(isOwnedByMe = true)
        val json = JsonMapperProvider.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(response)
        assertThat(json.path("id").asText()).isEqualTo("42")
        assertThat(json.path("isOwnedByMe").isBoolean).isTrue()
        assertThat(json.path("isOwnedByMe").booleanValue()).isTrue()
        assertThat(json.path("author").isNull).isTrue()
        assertThat(json.path("isLikedByMe").booleanValue()).isFalse()
    }

    @Test
    fun `production bean serialization preserves exact isOwnedByMe spelling without Kotlin module`() {
        val mapper = ObjectMapper().registerModule(JavaTimeModule())
        for (owned in listOf(false, true)) {
            val response = projection().toCommunityQuestionResponse(isOwnedByMe = owned)
            val json = mapper.readTree(mapper.writeValueAsString(response))
            assertThat(json.path("isOwnedByMe").isBoolean).isTrue()
            assertThat(json.path("isOwnedByMe").booleanValue()).isEqualTo(owned)
            assertThat(json.has("ownedByMe")).isFalse()
        }
    }

    private fun projection() = PublicQuestionProjection(
        id = "42", question = "Question", answer = "Answer", score = 90, correct = true,
        feedback = "Feedback", explanation = "Explanation", topic = "Topic", difficultyLevel = 5,
        status = "answered", source = "scheduled", createdAt = Instant.EPOCH, answeredAt = Instant.EPOCH,
        author = null, likeCount = 0, commentCount = 0, viewCount = 0, isLikedByMe = false,
    )
}
