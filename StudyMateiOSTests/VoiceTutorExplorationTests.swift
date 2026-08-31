import Foundation
import XCTest
@testable import StudyMate

/// Synthetic, read-only contracts: no AppState, network, microphone, persistence,
/// question grading, quota reservation, or real user data is involved.
final class VoiceTutorExplorationTests: XCTestCase {
    func testLegacyResultKeepsOverallSummaryWithoutExplorations() throws {
        let result = try decodeResult([
            "summary": "기존 전체 요약", "strengths": ["기존 강점"],
            "improvements": ["기존 보완점"], "nextSteps": ["기존 다음 학습"]
        ])
        XCTAssertEqual(result.summaryMarkdown, "기존 전체 요약")
        XCTAssertEqual(result.strengths, ["기존 강점"])
        XCTAssertEqual(result.improvements, ["기존 보완점"])
        XCTAssertEqual(result.nextSteps, ["기존 다음 학습"])
        XCTAssertTrue(result.explorations.isEmpty)
    }

    func testNullExplorationsDecodeAsEmptyWithoutChangingExistingResult() throws {
        let result = try decodeResult(["summaryMarkdown": "전체 요약", "explorations": NSNull()])
        XCTAssertEqual(result.summaryMarkdown, "전체 요약")
        XCTAssertTrue(result.explorations.isEmpty)
    }

    func testBackendExplorationContractKeepsTopicLevelQuestionsScoresAndFeedback() throws {
        let result = try decodeResult(["summaryMarkdown": "전체 요약", "explorations": [exploration()]])
        let topic = try XCTUnwrap(result.explorations.first)
        let exchange = try XCTUnwrap(topic.exchanges.first)
        XCTAssertEqual(topic.topic, "Redis · 만료 정책")
        XCTAssertEqual(topic.studyId, 7)
        XCTAssertEqual(topic.difficulty, 4)
        XCTAssertEqual(topic.depthSummary, "TTL과 lazy expiration을 비교했다.")
        XCTAssertEqual(exchange.kind, .tutorQuestion)
        XCTAssertEqual(exchange.question, "TTL은 무엇인가요?")
        XCTAssertEqual(exchange.answer, "키가 만료되기까지의 시간입니다.")
        XCTAssertEqual(exchange.score, 80)
        XCTAssertEqual(exchange.strengths, ["만료 시간의 의미를 설명했다."])
        XCTAssertEqual(exchange.improvements, ["만료 검사 시점을 더 구분한다."])
        XCTAssertEqual(exchange.questionTurnId, "11")
        XCTAssertEqual(exchange.answerTurnIds, ["12", "14"])
        XCTAssertEqual(exchange.feedbackTurnIds, ["15"])
        XCTAssertEqual(result.summaryMarkdown, "전체 요약")
    }

    func testLearnerQuestionKeepsTutorExplanationWithoutInventingAssessment() throws {
        let item = try decodeExchange([
            "kind": "LEARNER_QUESTION", "question": "만료되면 바로 삭제돼요?",
            "answer": "실제 메모리 삭제 시점은 검사 방식에 따라 달라집니다.",
            "score": NSNull(), "questionTurnId": 16, "answerTurnIds": [17], "feedbackTurnIds": []
        ])
        XCTAssertEqual(item.kind, .learnerQuestion)
        XCTAssertEqual(item.questionTurnId, "16")
        XCTAssertEqual(item.answerTurnIds, ["17"])
        XCTAssertNil(item.score)
        XCTAssertNil(VoiceTutorExplorationPresentation.displayScore(for: item))
        XCTAssertTrue(item.strengths.isEmpty)
        XCTAssertTrue(item.improvements.isEmpty)
    }

    func testUnansweredQuestionRemainsVisibleWithoutZeroScoreOrFabricatedAnswer() throws {
        var payload = exchange()
        payload["answer"] = ""
        payload["score"] = NSNull()
        payload["answerTurnIds"] = [] as [Int]
        payload["feedbackTurnIds"] = [] as [Int]
        payload["strengths"] = [] as [String]
        payload["improvements"] = [] as [String]
        let item = try decodeExchange(payload)
        XCTAssertEqual(VoiceTutorExplorationPresentation.displayExchanges([item]).count, 1)
        XCTAssertEqual(item.answer, "")
        XCTAssertNil(item.score)
        XCTAssertNil(VoiceTutorExplorationPresentation.displayScore(for: item))
        XCTAssertTrue(item.answerTurnIds.isEmpty)
    }

    func testOnlyExplicitValidTutorScoreIsDisplayedIncludingActualZero() throws {
        for score in [0, 80, 100] {
            var payload = exchange()
            payload["score"] = score
            XCTAssertEqual(VoiceTutorExplorationPresentation.displayScore(for: try decodeExchange(payload)), score)
        }
        var learner = exchange()
        learner["kind"] = "LEARNER_QUESTION"
        XCTAssertNil(VoiceTutorExplorationPresentation.displayScore(for: try decodeExchange(learner)))
        var unanswered = exchange()
        unanswered["answer"] = " \n "
        XCTAssertNil(VoiceTutorExplorationPresentation.displayScore(for: try decodeExchange(unanswered)))
    }

    func testAbsentNullOutOfRangeAndWrongTypeScoresAreNeverClampedOrInvented() throws {
        let invalidScores: [Any] = [NSNull(), -1, 101, 82.5, "80", true]
        for score in invalidScores {
            var payload = exchange()
            payload["score"] = score
            XCTAssertNil(try decodeExchange(payload).score)
        }
        var missing = exchange()
        missing.removeValue(forKey: "score")
        XCTAssertNil(try decodeExchange(missing).score)
    }

    func testUnknownExchangeKindDoesNotGetMisattributedToTheTutor() throws {
        var payload = exchange()
        payload["kind"] = "FUTURE_EXCHANGE"
        let unknown = try decodeExchange(payload)
        let known = try decodeExchange(exchange())
        XCTAssertEqual(unknown.kind, .unknown)
        XCTAssertEqual(VoiceTutorExplorationPresentation.displayExchanges([unknown, known]), [known])
        XCTAssertNil(VoiceTutorExplorationPresentation.displayScore(for: unknown))
    }

    func testCompletedExplorationOnlyResultIsReadyButProcessingStillWaits() throws {
        let content: [String: Any] = ["explorations": [exploration()]]
        XCTAssertEqual(VoiceTutorSummaryState(detail: try decodeDetail(status: "COMPLETED", result: content)), .ready)
        XCTAssertEqual(VoiceTutorSummaryState(detail: try decodeDetail(status: "PROCESSING", result: content)), .pending)
        XCTAssertEqual(VoiceTutorSummaryState(detail: try decodeDetail(status: "FAILED", result: content)), .failed)
        XCTAssertEqual(VoiceTutorSummaryState(detail: try decodeDetail(status: "UNKNOWN", result: content)), .unknown)
    }

    func testDepthSummaryAloneIsContentButEmptyTopicShellIsNot() throws {
        var depthOnly = exploration()
        depthOnly["exchanges"] = [] as [[String: Any]]
        let withDepth = try decodeTopic(depthOnly)
        XCTAssertTrue(VoiceTutorExplorationPresentation.hasContent(withDepth))
        depthOnly["depthSummary"] = " \n\t "
        let shell = try decodeTopic(depthOnly)
        XCTAssertFalse(VoiceTutorExplorationPresentation.hasContent(shell))
        XCTAssertEqual(
            VoiceTutorSummaryState(detail: try decodeDetail(status: "COMPLETED", result: ["explorations": [depthOnly]])),
            .empty
        )
    }

    func testSourceReferencesResolveOnlyExactSessionTurnsInTheirOriginalOrder() throws {
        var payload = exchange()
        payload["answerTurnIds"] = ["14", "12", "14", "999"]
        let item = try decodeExchange(payload)
        let transcript = try decodeTranscript([
            ["id": 9, "role": "USER", "text": "무관한 대화"],
            ["id": 11, "role": "ASSISTANT", "text": "질문"],
            ["id": 12, "role": "USER", "text": "첫 답변"],
            ["id": 14, "role": "USER", "text": "추가 답변"],
            ["id": 15, "role": "ASSISTANT", "text": "피드백"],
            ["id": 15, "role": "ASSISTANT", "text": "중복 이벤트"],
            ["id": 20, "role": "USER", "text": "다음 주제"]
        ])
        let source = VoiceTutorExplorationPresentation.sourceTurns(for: item, in: transcript)
        XCTAssertEqual(source.map(\.id), ["11", "12", "14", "15"])
        XCTAssertEqual(source.map(\.text), ["질문", "첫 답변", "추가 답변", "피드백"])
    }

    func testMissingSourceReferencesNeverFallBackToAllPrivateTranscript() throws {
        let item = try decodeExchange(["kind": "TUTOR_QUESTION", "question": "질문", "answer": "답변"])
        let transcript = try decodeTranscript([["id": 12, "role": "USER", "text": "원문"]])
        XCTAssertTrue(VoiceTutorExplorationPresentation.sourceTurns(for: item, in: transcript).isEmpty)
    }

    func testModelTextIsPreservedVerbatimForInertRendering() throws {
        let text = "![image](https://invalid.example/private) <script>not executable</script>\n**원문**"
        var payload = exploration()
        payload["topic"] = text
        payload["depthSummary"] = text
        payload["exchanges"] = [["kind": "LEARNER_QUESTION", "question": text, "answer": text]]
        let item = try decodeTopic(payload)
        XCTAssertEqual(item.topic, text)
        XCTAssertEqual(item.depthSummary, text)
        XCTAssertEqual(item.exchanges.first?.question, text)
        XCTAssertEqual(item.exchanges.first?.answer, text)
    }

    func testInvalidStudyIdentityAndDifficultyAreNotDisplayedAsValidMetadata() throws {
        for invalid in [-1, 0, 11, 100] {
            var payload = exploration()
            payload["studyId"] = 0
            payload["difficulty"] = invalid
            let item = try decodeTopic(payload)
            XCTAssertNil(item.studyId)
            XCTAssertNil(item.difficulty)
        }
    }

    func testPaginationIsBoundedAndOverflowSafe() {
        XCTAssertEqual(VoiceTutorExplorationPresentation.topicPageSize, 4)
        XCTAssertEqual(VoiceTutorExplorationPresentation.exchangePageSize, 4)
        XCTAssertEqual(VoiceTutorExplorationPresentation.transcriptPageSize, 20)
        let next = VoiceTutorExplorationPresentation.nextVisibleCount
        XCTAssertEqual(next(4, 12, 4), 8)
        XCTAssertEqual(next(8, 10, 4), 10)
        XCTAssertEqual(next(10, 10, 4), 10)
        XCTAssertEqual(next(100, 12, 4), 12)
        XCTAssertEqual(next(-10, 12, 4), 4)
        XCTAssertEqual(next(4, -1, 4), 0)
        XCTAssertEqual(next(4, 12, -1), 4)
        XCTAssertEqual(next(Int.max - 1, Int.max, Int.max), Int.max)
    }

    func testExplorationLabelsAreLocalizedAndDoNotClaimQuestionStatistics() {
        for language in [AppLanguage.korean, .english, .japanese] {
            let strings = AppStrings(language: language)
            let labels = [
                strings.voiceTutorExplorationsTitle, strings.voiceTutorExplorationDepth,
                strings.voiceTutorExplorationTutorQuestion, strings.voiceTutorExplorationLearnerQuestion,
                strings.voiceTutorExplorationLearnerAnswer, strings.voiceTutorExplorationTutorAnswer,
                strings.voiceTutorExplorationNoAnswer, strings.voiceTutorExplorationSource,
                strings.voiceTutorExplorationMoreTopics, strings.voiceTutorExplorationMoreExchanges
            ]
            XCTAssertTrue(labels.allSatisfy { !$0.isEmpty })
            XCTAssertNotEqual(strings.voiceTutorExplorationTutorQuestion, strings.voiceTutorExplorationLearnerQuestion)
            XCTAssertTrue(strings.voiceTutorExplorationCount(2).contains("2"))
            XCTAssertTrue(strings.voiceTutorExplorationScore(80).contains("80"))
        }
        XCTAssertEqual(AppStrings(language: .korean).voiceTutorExplorationScore(80), "통화 평가 80점")
    }

    private func exploration() -> [String: Any] {
        [
            "topic": "Redis · 만료 정책", "studyId": 7, "difficulty": 4,
            "depthSummary": "TTL과 lazy expiration을 비교했다.", "exchanges": [exchange()]
        ]
    }

    private func exchange() -> [String: Any] {
        [
            "kind": "TUTOR_QUESTION", "question": "TTL은 무엇인가요?",
            "answer": "키가 만료되기까지의 시간입니다.", "score": 80,
            "strengths": ["만료 시간의 의미를 설명했다."],
            "improvements": ["만료 검사 시점을 더 구분한다."],
            "questionTurnId": 11, "answerTurnIds": [12, 14], "feedbackTurnIds": [15]
        ]
    }

    private func decodeResult(_ payload: [String: Any]) throws -> BackendVoiceTutorSessionResult {
        try JSONDecoder().decode(BackendVoiceTutorSessionResult.self, from: JSONSerialization.data(withJSONObject: payload))
    }

    private func decodeTopic(_ payload: [String: Any]) throws -> BackendVoiceTutorExploration {
        try JSONDecoder().decode(BackendVoiceTutorExploration.self, from: JSONSerialization.data(withJSONObject: payload))
    }

    private func decodeExchange(_ payload: [String: Any]) throws -> BackendVoiceTutorExplorationExchange {
        try JSONDecoder().decode(BackendVoiceTutorExplorationExchange.self, from: JSONSerialization.data(withJSONObject: payload))
    }

    private func decodeTranscript(_ payload: [[String: Any]]) throws -> [BackendVoiceTutorTranscriptTurn] {
        try JSONDecoder().decode([BackendVoiceTutorTranscriptTurn].self, from: JSONSerialization.data(withJSONObject: payload))
    }

    private func decodeDetail(status: String, result: [String: Any]) throws -> BackendVoiceTutorSessionDetail {
        try JSONDecoder().decode(BackendVoiceTutorSessionDetail.self, from: JSONSerialization.data(withJSONObject: [
            "sessionId": "synthetic-exploration-session", "topic": "Redis", "state": "ENDED",
            "resultStatus": status, "result": result
        ]))
    }
}
