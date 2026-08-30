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
            text: #"{"type":"response.output_audio.delta","item_id":"item_assistant_1","content_index":2,"delta":"AAECAw=="}"#
        )
        XCTAssertEqual(
            audio,
            .audioDelta(
                VoiceTutorRealtimeAudioDelta(
                    audio: audioBytes,
                    itemID: "item_assistant_1",
                    contentIndex: 2
                )
            )
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
        XCTAssertEqual(intervention, .responseStarted(isTutorIntervention: true))
    }

    func testBargeInPayloadUsesValidatedPlaybackPositionContract() throws {
        let text = try XCTUnwrap(
            VoiceTutorRealtimeBargeInPayload.text(
                cancelResponse: true,
                truncation: VoiceTutorPlaybackTruncation(
                    itemID: "item_assistant_1",
                    contentIndex: 2,
                    audioEndMilliseconds: 1_250
                )
            )
        )
        let object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any]
        )

        XCTAssertEqual(object["type"] as? String, "buddystudy.voice.barge-in")
        XCTAssertEqual(object["cancelResponse"] as? Bool, true)
        XCTAssertEqual(object["itemId"] as? String, "item_assistant_1")
        XCTAssertEqual(object["contentIndex"] as? Int, 2)
        XCTAssertEqual(object["audioEndMs"] as? Int, 1_250)
    }

    func testPlaybackTimelineCountsOnlyRenderedPCMFrames() {
        var timeline = VoiceTutorPlaybackTimeline()
        timeline.recordScheduled(
            itemID: "item_assistant_1",
            contentIndex: 0,
            frameCount: 2_400,
            currentSampleTime: 1_000
        )
        timeline.recordScheduled(
            itemID: "item_assistant_1",
            contentIndex: 0,
            frameCount: 2_400,
            currentSampleTime: 2_000
        )

        XCTAssertEqual(
            timeline.truncation(at: 4_000, sampleRate: 24_000),
            VoiceTutorPlaybackTruncation(
                itemID: "item_assistant_1",
                contentIndex: 0,
                audioEndMilliseconds: 125
            )
        )

        timeline.reset()
        XCTAssertNil(timeline.truncation(at: 4_000, sampleRate: 24_000))
    }

    func testMarkedTutorInterventionOwnsFloorDuringContinuousLearnerSpeech() {
        var state = VoiceTutorDuplexPlaybackState()

        XCTAssertEqual(state.userSpeechStarted(), .bargeIn(cancelResponse: false))
        XCTAssertFalse(state.responseStarted(isTutorIntervention: true))
        XCTAssertTrue(
            state.permitsAssistantAudio(itemID: "item-intervention", interruptedItemID: nil)
        )
        XCTAssertEqual(state.userSpeechStarted(), .interventionContinuation)

        state.assistantAudioBegan()
        state.userSpeechStopped()
        XCTAssertEqual(state.userSpeechStarted(), .bargeIn(cancelResponse: true))
    }

    func testUnmarkedResponseRacingWithLearnerSpeechIsCancelledAndMuted() {
        var state = VoiceTutorDuplexPlaybackState()
        _ = state.userSpeechStarted()

        XCTAssertTrue(state.responseStarted(isTutorIntervention: false))
        XCTAssertFalse(
            state.permitsAssistantAudio(itemID: "item-racing", interruptedItemID: nil)
        )
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
