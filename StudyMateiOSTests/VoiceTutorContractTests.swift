import Foundation
import XCTest
import AVFoundation
@testable import StudyMate

final class VoiceTutorContractTests: XCTestCase {
    private let decoder = RemotePushBackendClient.makeDecoder()

    func testBillingStatusDecodesFlatVoiceTutorQuota() throws {
        let status = try decoder.decode(
            BackendBillingStatus.self,
            from: Data(
                #"""
                {
                  "tierCode": "TIER2",
                  "adFree": true,
                  "source": "APPLE",
                  "accessStatus": "ACTIVE",
                  "renewalStatus": "ACTIVE",
                  "willRenew": true,
                  "synchronizedAt": "2026-08-30T01:00:00Z",
                  "quota": {
                    "periodStartedAt": "2026-08-01T00:00:00Z",
                    "resetAt": "2026-09-01T00:00:00Z",
                    "anchorType": "CALENDAR_MONTH",
                    "baseLimit": 300,
                    "bonusLimit": 0,
                    "usedCount": 12,
                    "reservedCount": 0,
                    "remainingCount": 288,
                    "policyVersion": 1
                  },
                  "voiceTutor": {
                    "enabled": true,
                    "periodStartedAt": "2026-08-01T00:00:00Z",
                    "resetAt": "2026-09-01T00:00:00Z",
                    "limitSeconds": 18000,
                    "usedSeconds": 120,
                    "reservedSeconds": 60,
                    "remainingSeconds": 17820
                  }
                }
                """#.utf8
            )
        )

        XCTAssertEqual(status.voiceTutor?.enabled, true)
        XCTAssertEqual(status.voiceTutor?.quota?.limitSeconds, 18_000)
        XCTAssertEqual(status.voiceTutor?.quota?.remainingSeconds, 17_820)
        XCTAssertEqual(
            AppStrings(language: .korean).voiceTutorMonthlyAllowance(18_000),
            "매월 음성 300분"
        )
        XCTAssertEqual(
            AppStrings(language: .english).voiceTutorMonthlyAllowance(18_000),
            "300 min of voice each month"
        )
    }

    func testBillingProductVoiceAllowanceIsAdditiveAndDefaultsToZero() throws {
        let product = try decoder.decode(
            BackendBillingTierProduct.self,
            from: Data(
                #"""
                {
                  "tierCode": "TIER2",
                  "adFree": true,
                  "description": "Pro",
                  "monthlyQuestionLimit": 300,
                  "monthlyVoiceSecondsLimit": 18000,
                  "productId": "pro.monthly",
                  "productType": "AUTO_RENEWABLE_SUBSCRIPTION",
                  "billingPeriod": "P1M",
                  "sortOrder": 2
                }
                """#.utf8
            )
        )
        XCTAssertEqual(product.monthlyVoiceSecondsLimit, 18_000)

        let legacyProduct = try decoder.decode(
            BackendBillingTierProduct.self,
            from: Data(
                #"""
                {
                  "tierCode": "TIER1",
                  "description": "Free",
                  "monthlyQuestionLimit": 30,
                  "productId": "free",
                  "productType": "FREE",
                  "sortOrder": 1
                }
                """#.utf8
            )
        )
        XCTAssertEqual(legacyProduct.monthlyVoiceSecondsLimit, 0)
    }

    func testVoiceTutorStatusDecodesServerSessionLimit() throws {
        let status = try decoder.decode(
            BackendVoiceTutorStatus.self,
            from: Data(
                #"""
                {
                  "eligible": true,
                  "tierCode": "TIER2",
                  "maxSessionSeconds": 3600,
                  "quota": {
                    "limitSeconds": 18000,
                    "usedSeconds": 120,
                    "reservedSeconds": 0,
                    "remainingSeconds": 17880
                  }
                }
                """#.utf8
            )
        )

        XCTAssertTrue(status.eligible)
        XCTAssertEqual(status.maxSessionSeconds, 3_600)
        XCTAssertEqual(status.quota.limitSeconds, 18_000)
    }

    func testVoiceTutorFlatDetailAcceptsNumericTranscriptIDAndOccurredAt() throws {
        let detail = try decoder.decode(
            BackendVoiceTutorSessionDetail.self,
            from: Data(
                #"""
                {
                  "sessionId": "voice-123",
                  "studyId": 42,
                  "topic": "Swift Concurrency",
                  "difficulty": 3,
                  "language": "ko",
                  "state": "ENDED",
                  "createdAt": "2026-08-30T01:00:00Z",
                  "endedAt": "2026-08-30T01:10:00Z",
                  "durationSeconds": 600,
                  "chargedSeconds": 600,
                  "resultStatus": "COMPLETED",
                  "transcriptTurns": [
                    {
                      "id": 77,
                      "role": "user",
                      "text": "actor 격리가 무엇인가요?",
                      "occurredAt": "2026-08-30T01:00:05Z"
                    }
                  ],
                  "result": {
                    "status": "COMPLETED",
                    "summaryMarkdown": "동시성 기본 개념을 복습했습니다.",
                    "strengths": ["actor의 역할"],
                    "improvements": ["Sendable"],
                    "nextSteps": ["실습 문제 풀기"],
                    "errorMessage": null
                  }
                }
                """#.utf8
            )
        )

        XCTAssertEqual(detail.sessionId, "voice-123")
        XCTAssertEqual(detail.startedAt, try XCTUnwrap(ISO8601DateFormatter().date(from: "2026-08-30T01:00:00Z")))
        XCTAssertEqual(detail.transcriptTurns.first?.id, "77")
        XCTAssertNotNil(detail.transcriptTurns.first?.createdAt)
        XCTAssertEqual(detail.result?.status, "COMPLETED")
        XCTAssertNil(detail.result?.errorMessage)
        XCTAssertEqual(detail.result?.strengths, ["actor의 역할"])
    }

    func testRealtimeParserHandlesProviderAndBuddyStudyEvents() throws {
        let quota = try VoiceTutorRealtimeEventParser.parse(
            text: #"{"type":"buddystudy.voice.quota.updated","limitSeconds":18000,"usedSeconds":120,"reservedSeconds":60,"remainingSeconds":17820,"chargedSeconds":30}"#
        )
        XCTAssertEqual(
            quota,
            .quotaUpdated(
                VoiceTutorRealtimeQuotaUpdate(
                    limitSeconds: 18_000,
                    usedSeconds: 120,
                    reservedSeconds: 60,
                    remainingSeconds: 17_820,
                    chargedSeconds: 30
                )
            )
        )

        let audioBytes = Data([0, 1, 2, 3])
        let audio = try VoiceTutorRealtimeEventParser.parse(
            text: #"{"type":"response.output_audio.delta","response_id":"response-1","item_id":"item_assistant_1","content_index":2,"delta":"AAECAw=="}"#
        )
        XCTAssertEqual(
            audio,
            .audioDelta(
                VoiceTutorRealtimeAudioDelta(
                    audio: audioBytes,
                    responseID: "response-1",
                    itemID: "item_assistant_1",
                    contentIndex: 2
                )
            )
        )

        XCTAssertEqual(
            try VoiceTutorRealtimeEventParser.parse(
                text: #"{"type":"response.output_audio.done","response_id":"response-1","item_id":"item_assistant_1"}"#
            ),
            .ignored(type: "response.output_audio.done")
        )
        XCTAssertEqual(
            try VoiceTutorRealtimeEventParser.parse(
                text: #"{"type":"response.done","response":{"id":"response-1","status":"completed"}}"#
            ),
            .responseFinished(responseID: "response-1")
        )
        XCTAssertEqual(
            try VoiceTutorRealtimeEventParser.parse(
                text: #"{"type":"response.output_audio_transcript.delta","response_id":"response-1","delta":"문장"}"#
            ),
            .assistantTranscriptDelta(responseID: "response-1", delta: "문장")
        )
        XCTAssertEqual(
            try VoiceTutorRealtimeEventParser.parse(
                text: #"{"type":"response.output_audio_transcript.done","response_id":"response-1","transcript":"문장입니다."}"#
            ),
            .assistantTranscriptDone(responseID: "response-1", transcript: "문장입니다.")
        )

        let transcript = try VoiceTutorRealtimeEventParser.parse(
            text: #"{"type":"conversation.item.input_audio_transcription.completed","transcript":"안녕하세요"}"#
        )
        XCTAssertEqual(transcript, .userTranscript("안녕하세요"))

        let heartbeat = try VoiceTutorRealtimeEventParser.parse(
            text: #"{"type":"buddystudy.voice.heartbeat.ack","sessionId":"voice-123","serverTime":"2026-08-30T01:00:00Z"}"#
        )
        XCTAssertEqual(heartbeat, .heartbeatAcknowledged)

        let intervention = try VoiceTutorRealtimeEventParser.parse(
            text: #"{"type":"response.created","response":{"id":"response-1"},"buddystudyTutorIntervention":true}"#
        )
        XCTAssertEqual(
            intervention,
            .responseStarted(responseID: "response-1", isTutorIntervention: true)
        )
    }

    func testLearnerOverlapKeepsCurrentTutorSentenceActive() {
        var state = VoiceTutorDuplexPlaybackState()

        state.responseStarted(responseID: "response-1", isTutorIntervention: false)
        state.assistantAudioBegan(responseID: "response-1")
        state.userSpeechStarted()

        XCTAssertTrue(state.isUserSpeaking)
        XCTAssertTrue(state.assistantResponseActive)
        XCTAssertFalse(state.tutorInterventionActive)

        state.userSpeechStopped()
        XCTAssertTrue(state.assistantResponseActive)

        XCTAssertTrue(state.responseFinished(responseID: "response-1"))
        XCTAssertFalse(state.assistantResponseActive)
    }

    func testTutorCanTakeTheFloorWhileLearnerIsSpeaking() {
        var state = VoiceTutorDuplexPlaybackState()
        state.userSpeechStarted()
        state.responseStarted(responseID: "response-1", isTutorIntervention: true)

        XCTAssertTrue(state.isUserSpeaking)
        XCTAssertTrue(state.assistantResponseActive)
        XCTAssertTrue(state.tutorInterventionActive)
    }

    func testStalePlaybackCompletionCannotClearTheCurrentTutorResponse() {
        var state = VoiceTutorDuplexPlaybackState()
        state.responseStarted(responseID: "response-new", isTutorIntervention: false)

        XCTAssertFalse(state.responseFinished(responseID: "response-old"))
        XCTAssertTrue(state.assistantResponseActive)
        XCTAssertEqual(state.activeResponseID, "response-new")

        XCTAssertTrue(state.responseFinished(responseID: "response-new"))
        XCTAssertFalse(state.assistantResponseActive)
    }

    func testAssistantEventsMustMatchTheServerOwnedResponseGeneration() {
        var state = VoiceTutorDuplexPlaybackState()
        XCTAssertTrue(state.responseStarted(responseID: "response-new", isTutorIntervention: false))

        XCTAssertFalse(state.assistantAudioBegan(responseID: "response-old"))
        XCTAssertFalse(state.matchesActiveResponse(responseID: "response-old"))
        XCTAssertTrue(state.assistantAudioBegan(responseID: "response-new"))
        XCTAssertTrue(state.matchesActiveResponse(responseID: "response-new"))

        XCTAssertTrue(state.responseStarted(responseID: "response-next", isTutorIntervention: true))
        XCTAssertEqual(state.activeResponseID, "response-next")
        XCTAssertTrue(state.tutorInterventionActive)
        XCTAssertFalse(state.assistantAudioBegan(responseID: "response-new"))
        XCTAssertTrue(state.assistantAudioBegan(responseID: "response-next"))
    }

    func testPlaybackCompletionRequiresProviderSealAndLastRenderedBuffer() {
        var state = VoiceTutorPlaybackCompletionState()
        state.recordScheduled(responseID: "response-1")
        state.recordScheduled(responseID: "response-1")

        XCTAssertFalse(state.recordPlayed(responseID: "response-1"))
        XCTAssertFalse(state.seal(responseID: "response-1"))
        XCTAssertTrue(state.recordPlayed(responseID: "response-1"))
        XCTAssertFalse(state.recordPlayed(responseID: "response-1"))

        state.recordScheduled(responseID: "response-2")
        XCTAssertFalse(state.recordPlayed(responseID: "response-2"))
        XCTAssertTrue(state.seal(responseID: "response-2"))
    }

    func testLearnerOverlapContractHasNoClientCancelOrTruncationPath() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let sources = try [
            "StudyMate/Services/VoiceTutorAudioEngine.swift",
            "StudyMate/Services/VoiceTutorRealtimeClient.swift",
            "StudyMate/ViewModels/VoiceTutorViewModel.swift"
        ].map {
            try String(contentsOf: root.appendingPathComponent($0), encoding: .utf8)
        }.joined(separator: "\n")

        XCTAssertFalse(sources.contains("interruptPlayback"))
        XCTAssertFalse(sources.contains("sendBargeIn"))
        XCTAssertFalse(sources.contains("buddystudy.voice.barge-in"))
        XCTAssertFalse(sources.contains("conversation.item.truncate"))
        XCTAssertFalse(sources.contains("response.cancel"))
        XCTAssertTrue(sources.contains("completionCallbackType: .dataPlayedBack"))
        XCTAssertTrue(sources.contains("sendPlaybackCompleted"))
    }

    func testAudioSessionInterruptionOnlyEndsOnBeganNotification() {
        let began = Notification(
            name: AVAudioSession.interruptionNotification,
            userInfo: [
                AVAudioSessionInterruptionTypeKey: NSNumber(
                    value: AVAudioSession.InterruptionType.began.rawValue
                )
            ]
        )
        let ended = Notification(
            name: AVAudioSession.interruptionNotification,
            userInfo: [
                AVAudioSessionInterruptionTypeKey: NSNumber(
                    value: AVAudioSession.InterruptionType.ended.rawValue
                )
            ]
        )

        XCTAssertTrue(VoiceTutorAudioSessionInterruption.began(began))
        XCTAssertFalse(VoiceTutorAudioSessionInterruption.began(ended))
        XCTAssertFalse(VoiceTutorAudioSessionInterruption.began(Notification(name: .init("other"))))
    }

    func testLiveTutorTextIsBoundedInMemory() {
        let oversized = String(repeating: "가", count: 20_000)
        let draft = VoiceTutorLiveTextBounds.appending(delta: oversized, to: "")
        XCTAssertEqual(draft.count, VoiceTutorLiveTextBounds.maximumDraftCharacters)
        XCTAssertEqual(
            VoiceTutorLiveTextBounds.boundedCaption(oversized).count,
            VoiceTutorLiveTextBounds.maximumCaptionCharacters
        )

        var captions = (0..<100).map { _ in
            VoiceTutorCaption(speaker: .tutor, text: String(repeating: "x", count: 1_000))
        }
        VoiceTutorLiveTextBounds.trim(&captions)

        XCTAssertLessThanOrEqual(captions.count, VoiceTutorLiveTextBounds.maximumCaptionCount)
        XCTAssertLessThanOrEqual(
            captions.reduce(0) { $0 + $1.text.count },
            VoiceTutorLiveTextBounds.maximumTotalCaptionCharacters
        )
    }

    func testPrivateSummaryUsesInertTextRenderer() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let source = try String(
            contentsOf: root.appendingPathComponent("StudyMate/Views/VoiceTutorView.swift"),
            encoding: .utf8
        )

        XCTAssertTrue(source.contains("Text(verbatim: result.summaryMarkdown)"))
        XCTAssertFalse(source.contains("Markdown(result.summaryMarkdown)"))
        XCTAssertFalse(source.contains("AsyncImage(url:"))
    }
}
