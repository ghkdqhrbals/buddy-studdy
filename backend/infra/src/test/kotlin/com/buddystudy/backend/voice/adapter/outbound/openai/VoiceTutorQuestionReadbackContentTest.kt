package com.buddystudy.backend.voice.adapter.outbound.openai

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class VoiceTutorQuestionReadbackContentTest {
    @Test
    fun `Korean saved question allows Markdown decoration and spacing changes`() {
        assertThat(VoiceTutorQuestionReadbackContent.matches(
            "## **의존성 주입**을 설명하세요.\n- `Service(repo)`의 테스트 예시를 드세요.",
            "의존성주입을 설명하세요! Service(repo)의 테스트 예시를 드세요.",
        )).isTrue()
    }

    @Test
    fun `English matching uses root case folding and preserves the complete wording`() {
        assertThat(VoiceTutorQuestionReadbackContent.matches(
            "Explain *REDIS* and __TTL__.\nGive ONE example!", "explain Redis and ttl give one example",
        )).isTrue()
    }

    @Test
    fun `Japanese matching allows width and punctuation differences`() {
        assertThat(VoiceTutorQuestionReadbackContent.matches(
            "**Ｒｅｄｉｓ**のＴＴＬを説明してください。例を１つ挙げてください。",
            "Redis の TTL を説明してください。例を1つ挙げてください！",
        )).isTrue()
    }

    @Test
    fun `source technical names and acronym pronunciation keep the rest of the full question required`() {
        val question = "Redis의 TTL과 API를 설명하고 예시를 2개 드세요."
        assertThat(VoiceTutorQuestionReadbackContent.matches(question, "레디스의 티티엘과 에이피아이를 설명하고 예시를 두 개 드세요.")).isTrue()
        assertThat(VoiceTutorQuestionReadbackContent.matches(question, "레디스의 티티엘과 에이피아이를 설명하세요.")).isFalse()
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = [
        "Redis의 TTL을 설명하고 예시를 2개 드세요.|그럼 문제를 그대로 읽어드릴게요. 레디스의 티티엘을 설명하고 예시를 두 개 드세요.",
        "Redis의 TTL을 설명하고 예시를 2개 드세요.|문제를 그대로 읽어드릴게요. Redis의 TTL을 설명하고 예시를 2개 드세요.",
        "Explain Redis TTL.|I'll read the question exactly. Explain Redis TTL.",
        "RedisのTTLを説明してください。|それでは、問題をそのまま読み上げます。RedisのTTLを説明してください。",
    ])
    fun `one fixed short delivery preface permits only a complete unchanged question`(question: String, transcript: String) {
        assertThat(VoiceTutorQuestionReadbackContent.matches(question, transcript)).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "그럼 문제를 그대로 읽어드릴게요.",
        "그럼 문제를 그대로 읽어드릴게요. Redis의 TTL을 설명하세요.",
        "그럼 문제를 그대로 읽어드릴게요. Redis의 TTL을 설명하고 예시를 3개 드세요.",
        "그럼 문제를 그대로 읽어드릴게요. Redis의 만료 시간을 설명하고 예시를 2개 드세요.",
        "그럼 문제를 그대로 읽어드릴게요. Redis의 TTL을 설명하고 예시를 2개 드세요. 힌트는 만료 시간입니다.",
        "힌트는 만료 시간입니다. Redis의 TTL을 설명하고 예시를 2개 드세요.",
        "그럼 문제를 그대로 읽어드릴게요. 문제를 그대로 읽어드릴게요. Redis의 TTL을 설명하고 예시를 2개 드세요.",
        "Redis의 TTL을 설명하고 예시를 2개 드세요. 그럼 문제를 그대로 읽어드릴게요.",
        "곧 질문이 도착해요. Redis의 TTL을 설명하고 예시를 2개 드세요.",
    ])
    fun `preface tolerance never strips arbitrary words omissions changes trailing hints or repeated introductions`(transcript: String) {
        assertThat(VoiceTutorQuestionReadbackContent.matches("Redis의 TTL을 설명하고 예시를 2개 드세요.", transcript)).isFalse()
    }

    @Test
    fun `Unicode composition and nonbreaking spaces normalize consistently`() {
        assertThat(VoiceTutorQuestionReadbackContent.matches(
            "\u1100\u1161\u11A8 항목의 café를 설명하세요.", "각\u00A0항목의 CAFE\u0301를 설명하세요",
        )).isTrue()
    }

    @Test
    fun `actual saved A B question permits spoken Korean option labels without omitting either option`() {
        val question = """
            결제 서비스는 승인됐지만 재고 서비스 호출이 네트워크 장애로 실패했다면, 일관성과 경계를 기준으로 더 맞는 설명은 어느 쪽인가요?
            - **A.** 로컬 트랜잭션만 성공해도 전체 비즈니스 상태는 자동으로 일관적이다.
            - **B.** 글로벌 관점에서는 보상 작업이나 후속 조정이 없으면 전체 상태가 불일치할 수 있다.
        """.trimIndent()
        val spoken = question.replace("- **A.**", "에이.").replace("- **B.**", "비.")
        assertThat(VoiceTutorQuestionReadbackContent.matches(question, spoken)).isTrue()
        assertThat(VoiceTutorQuestionReadbackContent.matches(question, spoken.replace("비.", "씨."))).isFalse()
        assertThat(VoiceTutorQuestionReadbackContent.matches(question, spoken.substringBefore("비."))).isFalse()
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = [
        "레벨 8의 예시를 2개 드세요.|레벨 팔의 예시를 두 개 드세요.",
        "레벨 8의 예시를 드세요.|레벨 여덟의 예시를 드세요.",
        "Give 8 examples.|Give eight examples.",
        "例を8つ挙げてください。|例を八つ挙げてください。",
    ])
    fun `saved small integers allow ordinary spoken forms`(question: String, spoken: String) {
        assertThat(VoiceTutorQuestionReadbackContent.matches(question, spoken)).isTrue()
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = [
        "레벨 8의 예시를 드세요.|레벨 구의 예시를 드세요.",
        "1.5를 설명하세요.|일오를 설명하세요.",
        "-8을 설명하세요.|팔을 설명하세요.",
    ])
    fun `pronunciation alternatives cannot change numbers or remove numeric operators`(question: String, spoken: String) {
        assertThat(VoiceTutorQuestionReadbackContent.matches(question, spoken)).isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "질문 생성을 요청했어요. 도착하면 읽어 드릴게요.",
        "Redis의 TTL을 설명하세요.",
        "Redis의 TTL을 설명하고 예시를 3개 드세요.",
        "Redis의 TTL을 설명하지 말고 예시를 2개 드세요.",
        "Redis의 TTL을 설명하고 예시를 2개 드세요. 힌트는 만료 시간입니다.",
        "Redis의 TTL을 설명하고 예시를 2개 드세요. 다음 주제는 무엇인가요?",
        "Redis의 만료 시간을 설명하고 예시를 2개 드세요.",
    ])
    fun `status messages partial reading changed words negation numbers and extra guidance are rejected`(transcript: String) {
        assertThat(VoiceTutorQuestionReadbackContent.matches("Redis의 TTL을 설명하고 예시를 2개 드세요.", transcript)).isFalse()
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = [
        "a != b|a = b", "a / b|ab", "a * b|ab", "a**b|ab", "1-2|12", "-2|2", "1.5|15", "1:2|12",
        "a + b|ab", "a < b|a > b", "50%|50", "!ready|ready", "a && b|a b", "cache_key|cachekey",
    ])
    fun `operator and numeric punctuation cannot disappear during normalization`(question: String, transcript: String) {
        assertThat(VoiceTutorQuestionReadbackContent.matches(question, transcript)).isFalse()
    }

    @Test
    fun `literal operators still allow whitespace changes`() {
        assertThat(VoiceTutorQuestionReadbackContent.matches(
            "`a != b`와 `a * b`, `1.5`, `-2`, `cache_key`를 설명하세요.",
            "a!=b와 a*b, 1.5, -2, cache_key를 설명하세요",
        )).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "\n\t", "**...**", "。！？"])
    fun `empty or decoration only source and transcript cannot count as a question`(empty: String) {
        assertThat(VoiceTutorQuestionReadbackContent.matches(empty, empty)).isFalse()
        assertThat(VoiceTutorQuestionReadbackContent.matches("질문을 설명하세요.", empty)).isFalse()
        assertThat(VoiceTutorQuestionReadbackContent.matches(empty, "질문을 설명하세요.")).isFalse()
    }

    @Test
    fun `limits apply before normalization and include their exact boundaries`() {
        val question = "가".repeat(8_000)
        assertThat(VoiceTutorQuestionReadbackContent.matches(question, question)).isTrue()
        assertThat(VoiceTutorQuestionReadbackContent.matches(question + " ", question)).isFalse()
        assertThat(VoiceTutorQuestionReadbackContent.matches(question, question + " ".repeat(12_000))).isTrue()
        assertThat(VoiceTutorQuestionReadbackContent.matches(question, question + " ".repeat(12_001))).isFalse()
    }
}
