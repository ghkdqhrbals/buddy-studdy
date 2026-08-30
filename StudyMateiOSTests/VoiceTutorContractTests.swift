import Foundation
import Combine
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

    func testVoiceTutorWebRTCAndRecordingContractDecodes() throws {
        let session = try decoder.decode(
            BackendVoiceTutorSessionStart.self,
            from: Data(
                #"""
                {
                  "sessionId": "2B8EBF8D-340A-49CE-B9AC-9F02493E380C",
                  "state": "READY",
                  "realtimeTransport": "WEBRTC",
                  "sdpUrl": "/api/v1/voice-tutor/sessions/2B8EBF8D-340A-49CE-B9AC-9F02493E380C/webrtc",
                  "controlWebsocketUrl": "wss://api.example.com/api/v1/voice-tutor/sessions/2B8EBF8D-340A-49CE-B9AC-9F02493E380C/control",
                  "controlWebsocketProtocol": "buddystudy.voice.control.v2",
                  "recording": {
                    "enabled": true,
                    "consentRequired": true,
                    "available": false,
                    "status": "PENDING",
                    "retentionDays": 30
                  },
                  "quota": {
                    "limitSeconds": 3600,
                    "usedSeconds": 0,
                    "reservedSeconds": 60,
                    "remainingSeconds": 3540
                  }
                }
                """#.utf8
            )
        )

        XCTAssertEqual(session.realtimeTransport, "WEBRTC")
        XCTAssertEqual(session.sdpURL?.relativeString.hasPrefix("/api/"), true)
        XCTAssertEqual(session.controlWebSocketProtocol, "buddystudy.voice.control.v2")
        XCTAssertEqual(session.recording?.retentionDays, 30)
        XCTAssertEqual(session.recording?.consentRequired, true)

        let defaulted = try decoder.decode(
            BackendVoiceTutorSessionStart.self,
            from: Data(
                #"{"sessionId":"2B8EBF8D-340A-49CE-B9AC-9F02493E380C"}"#.utf8
            )
        )
        XCTAssertEqual(defaulted.controlWebSocketProtocol, "buddystudy.voice.control.v2")
    }

    func testSDPExchangeUsesPOSTAndSDPMediaTypesWithoutDroppingAuthentication() throws {
        var authenticatedRequest = URLRequest(
            url: try XCTUnwrap(URL(string: "https://voice-tutor.test/session/webrtc"))
        )
        authenticatedRequest.httpMethod = "GET"
        authenticatedRequest.timeoutInterval = 17
        authenticatedRequest.httpBody = Data("old-body".utf8)
        authenticatedRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        authenticatedRequest.setValue("application/json", forHTTPHeaderField: "Accept")
        authenticatedRequest.setValue("0", forHTTPHeaderField: "Content-Length")
        authenticatedRequest.setValue("Bearer fixture-token", forHTTPHeaderField: "Authorization")
        authenticatedRequest.setValue("fixture-device", forHTTPHeaderField: "X-Device-Id")
        authenticatedRequest.setValue("fixture-secret", forHTTPHeaderField: "X-Client-Secret")
        let offer = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"

        let request = try VoiceTutorWebRTCTransport.sdpExchangeRequest(
            offer: offer,
            authenticatedRequest: authenticatedRequest
        )

        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/sdp")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Accept"), "application/sdp")
        XCTAssertNil(request.value(forHTTPHeaderField: "Content-Length"))
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer fixture-token")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Device-Id"), "fixture-device")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Client-Secret"), "fixture-secret")
        XCTAssertEqual(request.httpBody, Data(offer.utf8))
        XCTAssertEqual(request.url, authenticatedRequest.url)
        XCTAssertEqual(request.timeoutInterval, authenticatedRequest.timeoutInterval)
        XCTAssertEqual(authenticatedRequest.httpBody, Data("old-body".utf8))
    }

    func testSDPExchangeRejectsEmptyIncompleteAndNonAudioOffersBeforeSending() throws {
        let request = URLRequest(url: try XCTUnwrap(URL(string: "https://voice-tutor.test/session/webrtc")))
        let invalidOffers = [
            "", " \r\n", "v=0\r\n", "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n",
            "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n",
            "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n",
            "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=sctp-port:5000\r\n",
            "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=x:" + String(repeating: "x", count: 65_536)
        ]
        for offer in invalidOffers {
            XCTAssertThrowsError(try VoiceTutorWebRTCTransport.sdpExchangeRequest(
                offer: offer, authenticatedRequest: request
            )) { error in
                guard case VoiceTutorWebRTCError.offerCreationFailed = error else {
                    return XCTFail("Expected a local offer validation failure")
                }
            }
        }
    }

    func testSDPSnapshotAndRepeatedAttemptsKeepTheirOwnNonemptyOutgoingBodies() throws {
        let authenticated = URLRequest(url: try XCTUnwrap(URL(string: "https://voice-tutor.test/session/webrtc")))
        var offeredSDP = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=ice-ufrag:first\r\n"
        let firstSnapshot = try VoiceTutorWebRTCTransport.validatedOfferSDP(offeredSDP)
        offeredSDP = ""
        let first = try VoiceTutorWebRTCTransport.sdpExchangeRequest(
            offer: firstSnapshot, authenticatedRequest: authenticated
        )
        let secondSDP = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=ice-ufrag:second\r\n"
        let second = try VoiceTutorWebRTCTransport.sdpExchangeRequest(
            offer: secondSDP, authenticatedRequest: first
        )

        XCTAssertTrue(offeredSDP.isEmpty)
        XCTAssertEqual(first.httpBody, Data(firstSnapshot.utf8))
        XCTAssertEqual(second.httpBody, Data(secondSDP.utf8))
        XCTAssertNotEqual(first.httpBody, second.httpBody)
        XCTAssertGreaterThan(try XCTUnwrap(second.httpBody).count, 0)
    }

    func testMediaReadinessWaitsForCombinedICEAndDTLSConnection() {
        XCTAssertEqual(VoiceTutorWebRTCTransport.mediaReadiness(state: .new, elapsedSeconds: 0), .waiting)
        XCTAssertEqual(VoiceTutorWebRTCTransport.mediaReadiness(state: .connecting, elapsedSeconds: 14.9), .waiting)
        XCTAssertEqual(VoiceTutorWebRTCTransport.mediaReadiness(state: .connected, elapsedSeconds: 1), .connected)
        XCTAssertEqual(VoiceTutorWebRTCTransport.mediaReadiness(state: .failed, elapsedSeconds: 1), .failed)
        XCTAssertEqual(VoiceTutorWebRTCTransport.mediaReadiness(state: .closed, elapsedSeconds: 1), .failed)
        XCTAssertEqual(VoiceTutorWebRTCTransport.mediaReadiness(state: .connecting, elapsedSeconds: 15), .timedOut)
        XCTAssertEqual(VoiceTutorWebRTCTransport.mediaReadiness(state: .disconnected, elapsedSeconds: 15), .timedOut)
    }

    func testDelayedOldConnectionCallbackCannotEndARetriedAttempt() {
        var fence = VoiceTutorConnectionAttemptFence()
        let firstAttempt = fence.begin()
        var disconnects = 0
        let delayedFirstFailure = {
            if fence.isCurrent(firstAttempt) { disconnects += 1 }
        }
        let retryAttempt = fence.begin()
        delayedFirstFailure()

        XCTAssertFalse(fence.isCurrent(firstAttempt))
        XCTAssertTrue(fence.isCurrent(retryAttempt))
        XCTAssertEqual(disconnects, 0)
        if fence.isCurrent(retryAttempt) { disconnects += 1 }
        XCTAssertEqual(disconnects, 1)
    }

    func testFailedVoiceCallCannotBecomeSuccessfulDuringSettlement() {
        XCTAssertEqual(VoiceTutorSessionPhase.completed(outcome: .failed, serverState: "ENDED"), .failed)
        XCTAssertEqual(VoiceTutorSessionPhase.completed(outcome: .ended, serverState: "FAILED"), .failed)
        XCTAssertEqual(VoiceTutorSessionPhase.completed(outcome: .ended, serverState: "ENDED"), .ended)
        for phase in [VoiceTutorSessionPhase.ending, .ended, .failed] {
            XCTAssertFalse(phase.isLive, "Terminal/settling calls must not show a live countdown")
        }
    }

    func testServerFailureReasonSurvivesUnavailableSessionDetail() {
        for reason in ["PROVIDER_ERROR", "CLIENT_PROTOCOL_ERROR", "RELAY_HEARTBEAT_TIMEOUT", "AUTH_REVOKED"] {
            XCTAssertEqual(
                VoiceTutorSessionPhase.completed(outcome: .ended, serverState: nil, serverReason: reason),
                .failed
            )
        }
        for reason in ["USER_ENDED", "TIME_LIMIT", "SERVER_FINALIZED"] {
            XCTAssertEqual(
                VoiceTutorSessionPhase.completed(outcome: .ended, serverState: nil, serverReason: reason),
                .ended
            )
        }
    }

    @MainActor
    func testDelayedAttemptDeliveryDropsOldSuccessAndFailureAfterRetry() async {
        for didSend in [true, false] {
            var fence = VoiceTutorConnectionAttemptFence()
            let oldAttempt = fence.begin()
            let started = expectation(description: "old acknowledgement suspended")
            let gate = VoiceTutorContractResponseGate()
            var appliedOutcomes: [Bool] = []
            let delivery = Task {
                await VoiceTutorAttemptDelivery.deliver(
                    isCurrent: { fence.isCurrent(oldAttempt) },
                    operation: {
                        started.fulfill()
                        await gate.wait()
                        return didSend
                    },
                    apply: { appliedOutcomes.append($0) }
                )
            }
            let ready = await XCTWaiter.fulfillment(of: [started], timeout: 5)
            XCTAssertEqual(ready, .completed)
            let newAttempt = fence.begin()
            gate.open()
            await delivery.value

            XCTAssertTrue(fence.isCurrent(newAttempt))
            XCTAssertTrue(appliedOutcomes.isEmpty, "Old send success/failure must not mutate or stop the retry")
        }
    }

    @MainActor
    func testCancelledDelayedResultDeliveryCannotPublishEvenWithinSameAttempt() async {
        let gate = VoiceTutorContractResponseGate()
        let started = expectation(description: "session detail suspended")
        var publishedDetail: String?
        let delivery = Task {
            await VoiceTutorAttemptDelivery.deliver(
                isCurrent: { true },
                operation: {
                    started.fulfill()
                    await gate.wait()
                    return "old-session-detail"
                },
                apply: { publishedDetail = $0 }
            )
        }
        let ready = await XCTWaiter.fulfillment(of: [started], timeout: 5)
        XCTAssertEqual(ready, .completed)
        delivery.cancel()
        gate.open()
        await delivery.value

        XCTAssertNil(publishedDetail)
    }

    func testReleasedReservationReplacesTheCallsZeroRemainingSnapshot() {
        var state = VoiceTutorSessionQuotaState()
        state.apply(BackendVoiceTutorQuota(
            limitSeconds: 3_600, usedSeconds: 0, reservedSeconds: 3_600, remainingSeconds: 0
        ))
        XCTAssertEqual(state.remainingSeconds, 0)
        XCTAssertEqual(state.reservedSeconds, 3_600)

        state.apply(BackendVoiceTutorQuota(
            limitSeconds: 3_600, usedSeconds: 4, reservedSeconds: 0, remainingSeconds: 3_596
        ))
        XCTAssertEqual(state.remainingSeconds, 3_596)
        XCTAssertEqual(state.reservedSeconds, 0)
        XCTAssertEqual(state.limitSeconds, 3_600)
        XCTAssertEqual(AppStrings(language: .korean).voiceTutorRemainingTime(state.remainingSeconds), "60분 남음")
        XCTAssertTrue(AppStrings(language: .korean).voiceTutorReservedTime(3_600).contains("예약"))
        XCTAssertTrue(AppStrings(language: .english).voiceTutorReservedTime(3_600).contains("reserved"))
    }

    func testReceiveFailureFinalizationDoesNotCancelItsOwnQuotaRefresh() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let sourceURL = root.appendingPathComponent("StudyMate/ViewModels/VoiceTutorViewModel.swift")
        guard FileManager.default.fileExists(atPath: sourceURL.path) else {
            throw XCTSkip("Source-contract check requires the local repository; behavior tests run on iPhone.")
        }
        let source = try String(
            contentsOf: sourceURL,
            encoding: .utf8
        )
        let stop = try XCTUnwrap(source.range(of: "private func stop(\n"))
        let nextMethod = try XCTUnwrap(source.range(of: "private func stopForInvalidatedContext"))
        let teardown = String(source[stop.lowerBound..<nextMethod.lowerBound])
        XCTAssertFalse(teardown.contains("receiveTask?.cancel()"))
        XCTAssertTrue(teardown.contains("clearSessionCountdown()"))
        XCTAssertTrue(teardown.contains("outcome: .completed(outcome: outcome"))
    }

    func testConnectionIdentityFenceAllowsOnlyCurrentSignedInOwnerDeviceAndSecret() {
        var communityState = CommunitySessionStateStore(isSignedIn: true)
        let registration = RemotePushRegistration(
            deviceID: "device-a",
            clientSecret: "secret-a",
            apnsToken: "",
            accessToken: "old-token"
        )
        let fence = VoiceTutorConnectionIdentityFence(
            communityGeneration: communityState.generation,
            ownerUserID: 7,
            deviceID: registration.deviceID,
            clientSecret: registration.clientSecret
        )
        func matches(
            owner: Int? = 7,
            registration candidate: RemotePushRegistration?
        ) -> Bool {
            fence.matches(
                communityState: communityState,
                currentOwnerUserID: owner,
                currentRegistration: candidate
            )
        }

        XCTAssertTrue(matches(registration: registration))
        var refreshed = registration
        refreshed.accessToken = "refreshed-token"
        refreshed.accessTokenExpiresAt = Date().addingTimeInterval(3_600)
        refreshed.apnsToken = "refreshed-apns-token"
        XCTAssertTrue(matches(registration: refreshed), "Token refresh must not replace account identity")
        XCTAssertFalse(matches(owner: 8, registration: registration))
        XCTAssertFalse(matches(owner: nil, registration: registration))
        XCTAssertFalse(matches(registration: nil))

        var changedDevice = registration
        changedDevice.deviceID = "device-b"
        XCTAssertFalse(matches(registration: changedDevice))
        var changedSecret = registration
        changedSecret.clientSecret = "secret-b"
        XCTAssertFalse(matches(registration: changedSecret))
        XCTAssertFalse(fence.matches(
            communityState: CommunitySessionStateStore(isSignedIn: false),
            currentOwnerUserID: 7,
            currentRegistration: registration
        ))

        communityState.signOut()
        XCTAssertFalse(matches(registration: registration))
        communityState.signIn()
        XCTAssertFalse(matches(registration: registration), "Signing back into the same owner must not revive a prior generation")
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
                text: #"{"type":"output_audio_buffer.stopped","response_id":"response-1"}"#
            ),
            .outputAudioBufferStopped(responseID: "response-1")
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
        XCTAssertTrue(state.assistantAudioBegan(responseID: "response-1"))
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

    func testWebRTCPlayoutDrainRequiresBothServerSignalsAndLatestRenderedPCM() throws {
        var state = VoiceTutorWebRTCPlayoutDrainState()
        state.responseStarted("response-1")
        state.markResponseDone("response-1")
        XCTAssertNil(state.drainDeadline(additionalLatency: 0.07))

        state.markOutputBufferStopped("response-1", at: 11)
        XCTAssertNil(state.drainDeadline(additionalLatency: 0.07))

        state.rendered(at: 12)
        let first = try XCTUnwrap(state.drainDeadline(additionalLatency: 0.07))
        XCTAssertEqual(first.0, "response-1")
        XCTAssertEqual(first.2, 12.07, accuracy: 0.0001)

        state.rendered(at: 13)
        XCTAssertFalse(state.isCurrent(responseID: first.0, generation: first.1))
        let rescheduled = try XCTUnwrap(state.drainDeadline(additionalLatency: 0.07))
        XCTAssertEqual(rescheduled.2, 13.07, accuracy: 0.0001)
        XCTAssertTrue(
            state.isCurrent(responseID: rescheduled.0, generation: rescheduled.1)
        )
    }

    func testWebRTCPlayoutDrainKeepsPCMThatArrivesBeforeResponseCreated() throws {
        var state = VoiceTutorWebRTCPlayoutDrainState()

        state.rendered(at: 12)
        state.responseStarted("response-1")
        state.markResponseDone("response-1")
        state.markOutputBufferStopped("response-1", at: 11)

        let drain = try XCTUnwrap(state.drainDeadline(additionalLatency: 0.07))
        XCTAssertEqual(drain.responseID, "response-1")
        XCTAssertEqual(drain.deadline, 12.07, accuracy: 0.0001)
        XCTAssertEqual(drain.renderedThrough, 12, accuracy: 0.0001)
    }

    func testWebRTCPlayoutDrainDoesNotSeedNextResponseWithAcknowledgedPCM() throws {
        var state = VoiceTutorWebRTCPlayoutDrainState()
        state.responseStarted("response-1")
        state.rendered(at: 10)
        state.markResponseDone("response-1")
        state.markOutputBufferStopped("response-1", at: 10.5)
        let first = try XCTUnwrap(state.drainDeadline(additionalLatency: 0.07))
        XCTAssertTrue(
            state.markDrainDispatched(
                responseID: first.responseID,
                generation: first.generation,
                renderedThrough: first.renderedThrough
            )
        )

        state.responseStarted("response-2")
        state.markResponseDone("response-2")
        state.markOutputBufferStopped("response-2", at: 11)
        XCTAssertNil(state.drainDeadline(additionalLatency: 0.07))

        state.rendered(at: 12)
        let second = try XCTUnwrap(state.drainDeadline(additionalLatency: 0.07))
        XCTAssertEqual(second.responseID, "response-2")
        XCTAssertEqual(second.deadline, 12.07, accuracy: 0.0001)
    }

    func testWebRTCPlayoutDrainDoesNotSeedNextResponseWithLatePriorPCM() throws {
        var state = VoiceTutorWebRTCPlayoutDrainState()
        state.responseStarted("response-1")
        state.rendered(at: 10)
        state.markResponseDone("response-1")
        state.markOutputBufferStopped("response-1", at: 10.5)
        let first = try XCTUnwrap(state.drainDeadline(additionalLatency: 0.07))
        XCTAssertTrue(
            state.markDrainDispatched(
                responseID: first.responseID,
                generation: first.generation,
                renderedThrough: first.renderedThrough
            )
        )

        // RTP has no response identifier. A late buffer from the drained
        // response must not become the next response's completion watermark.
        state.rendered(at: 11)
        state.responseStarted("response-2")
        state.markResponseDone("response-2")
        state.markOutputBufferStopped("response-2", at: 12)
        XCTAssertNil(state.drainDeadline(additionalLatency: 0.07))

        state.rendered(at: 13)
        let second = try XCTUnwrap(state.drainDeadline(additionalLatency: 0.07))
        XCTAssertEqual(second.responseID, "response-2")
        XCTAssertEqual(second.deadline, 13.07, accuracy: 0.0001)
    }

    func testLearnerOverlapContractHasNoClientCancelOrTruncationPath() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let sources = try [
            "StudyMate/Services/VoiceTutorAudioEngine.swift",
            "StudyMate/Services/VoiceTutorRealtimeClient.swift",
            "StudyMate/Services/VoiceTutorWebRTCTransport.swift",
            "StudyMate/ViewModels/VoiceTutorViewModel.swift"
        ].map {
            try String(contentsOf: root.appendingPathComponent($0), encoding: .utf8)
        }.joined(separator: "\n")

        XCTAssertFalse(sources.contains("interruptPlayback"))
        XCTAssertFalse(sources.contains("sendBargeIn"))
        XCTAssertFalse(sources.contains("buddystudy.voice.barge-in"))
        XCTAssertFalse(sources.contains("conversation.item.truncate"))
        XCTAssertFalse(sources.contains("response.cancel"))
        XCTAssertFalse(sources.contains("session.update"))
        XCTAssertFalse(sources.contains("dataChannel(forLabel:"))
        XCTAssertFalse(sources.contains(#""type":"output_audio_buffer.clear""#))
        XCTAssertTrue(sources.contains("completionCallbackType: .dataPlayedBack"))
        XCTAssertTrue(sources.contains("sendPlaybackCompleted"))
        XCTAssertTrue(sources.contains("buddystudy.voice.playout.drained"))
    }

    func testRecordingFailureCleanupRemovesEveryUnfinalizedSensitiveFile() throws {
        let sessionID = UUID().uuidString
        let directory = try VoiceTutorRecordingStore.recordingsDirectory()
        let urls = [
            directory.appendingPathComponent("\(sessionID)-learner.m4a"),
            directory.appendingPathComponent("\(sessionID)-tutor.m4a"),
            directory.appendingPathComponent("\(sessionID).m4a"),
            directory.appendingPathComponent("\(sessionID).json")
        ]
        defer {
            try? VoiceTutorRecordingStore.removeUnfinalizedFiles(sessionID: sessionID)
        }
        for url in urls {
            try Data("sensitive recording".utf8).write(to: url)
        }

        try VoiceTutorRecordingStore.removeUnfinalizedFiles(sessionID: sessionID)

        for url in urls {
            XCTAssertFalse(FileManager.default.fileExists(atPath: url.path), url.lastPathComponent)
        }
    }

    func testRecordingCleanupRejectsPathLikeSessionID() {
        XCTAssertThrowsError(
            try VoiceTutorRecordingStore.removeUnfinalizedFiles(sessionID: "../../recording")
        )
    }

    func testPendingRecordingManifestCannotEscapeProtectedDirectory() async throws {
        let sessionID = UUID().uuidString
        let directory = try VoiceTutorRecordingStore.recordingsDirectory()
        let parent = directory.deletingLastPathComponent()
        let victimURL = parent.appendingPathComponent("voice-recording-victim-\(UUID().uuidString)")
        let manifestURL = directory.appendingPathComponent("\(sessionID).json")
        let pending = VoiceTutorPendingRecording(
            ownerUserID: 7,
            sessionID: sessionID,
            fileName: "../\(victimURL.lastPathComponent)",
            contentType: "audio/mp4",
            contentLength: 1,
            sha256: String(repeating: "0", count: 64),
            durationMilliseconds: 1,
            createdAt: Date()
        )
        defer {
            try? FileManager.default.removeItem(at: victimURL)
            try? FileManager.default.removeItem(at: manifestURL)
        }
        try Data("private".utf8).write(to: victimURL)
        try JSONEncoder().encode(pending).write(to: manifestURL)

        do {
            _ = try await VoiceTutorRecordingStore.shared.mediaURL(for: pending)
            XCTFail("A path-like recording file name must be rejected")
        } catch {
            XCTAssertTrue(FileManager.default.fileExists(atPath: victimURL.path))
        }
        let decoded = try await VoiceTutorRecordingStore.shared.pendingRecordings(ownerUserID: 7)
        XCTAssertFalse(decoded.contains(where: { $0.sessionID == sessionID }))

        do {
            try await VoiceTutorRecordingStore.shared.remove(pending)
            XCTFail("Removing a path-like recording file name must be rejected")
        } catch {
            XCTAssertTrue(FileManager.default.fileExists(atPath: victimURL.path))
        }
    }

    func testForegroundCleanupDeletesExpiredRecordingWithoutOpeningTutorView() async throws {
        let sessionID = UUID().uuidString
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let directory = try VoiceTutorRecordingStore.recordingsDirectory()
        let mediaURL = directory.appendingPathComponent("\(sessionID).m4a")
        let manifestURL = directory.appendingPathComponent("\(sessionID).json")
        let pending = VoiceTutorPendingRecording(
            ownerUserID: 77,
            sessionID: sessionID,
            fileName: "\(sessionID).m4a",
            contentType: "audio/mp4",
            contentLength: 1,
            sha256: String(repeating: "0", count: 64),
            durationMilliseconds: 1,
            createdAt: now.addingTimeInterval(-(31 * 24 * 60 * 60))
        )
        defer {
            try? FileManager.default.removeItem(at: mediaURL)
            try? FileManager.default.removeItem(at: manifestURL)
        }
        try Data([0]).write(to: mediaURL)
        try JSONEncoder().encode(pending).write(to: manifestURL)

        try await VoiceTutorRecordingStore.shared.cleanupExpiredAndOrphaned(now: now)

        XCTAssertFalse(FileManager.default.fileExists(atPath: mediaURL.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: manifestURL.path))
    }

    func testPurgePendingRecoveryDeletesEveryResidualArtifactBeforeClearingMarker() async throws {
        try VoiceTutorRecordingStore.purgeAll()
        let staleGeneration = VoiceTutorRecordingStore.lifecycleGeneration()
        let sessionID = UUID().uuidString
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let directory = try VoiceTutorRecordingStore.recordingsDirectory()
        let markerURL = try VoiceTutorRecordingStore.purgePendingMarkerURL()
        XCTAssertNotEqual(
            markerURL.deletingLastPathComponent().standardizedFileURL,
            directory.standardizedFileURL,
            "Purge intent must remain writable independently of the locked media directory"
        )
        let mediaURL = directory.appendingPathComponent("\(sessionID).m4a")
        let manifestURL = directory.appendingPathComponent("\(sessionID).json")
        let unrelatedArtifactURL = directory.appendingPathComponent("left-behind.tmp")
        let pending = VoiceTutorPendingRecording(
            ownerUserID: 9_999,
            sessionID: sessionID,
            fileName: "\(sessionID).m4a",
            contentType: "audio/mp4",
            contentLength: 1,
            sha256: String(repeating: "0", count: 64),
            durationMilliseconds: 1,
            createdAt: now
        )
        defer {
            try? VoiceTutorRecordingStore.purgeAll()
        }

        // Model a terminated purge after its durable marker was committed but
        // before all old-account artifacts disappeared. The manifest is fresh
        // and belongs to another owner, so normal age/owner cleanup must not be
        // what makes this test pass.
        try Data("voice-tutor-recording-purge-v1\n".utf8).write(
            to: markerURL,
            options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
        )
        var markerValues = URLResourceValues()
        markerValues.isExcludedFromBackup = true
        var protectedMarkerURL = markerURL
        try protectedMarkerURL.setResourceValues(markerValues)
        try Data([0]).write(to: mediaURL)
        try JSONEncoder().encode(pending).write(to: manifestURL)
        try Data("residual".utf8).write(to: unrelatedArtifactURL)

        #if !targetEnvironment(simulator)
        let markerAttributes = try FileManager.default.attributesOfItem(atPath: markerURL.path)
        XCTAssertEqual(
            markerAttributes[.protectionKey] as? FileProtectionType,
            .completeUntilFirstUserAuthentication
        )
        #endif
        XCTAssertEqual(
            try markerURL.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup,
            true
        )

        try await VoiceTutorRecordingStore.shared.cleanupExpiredAndOrphaned(now: now)

        XCTAssertFalse(FileManager.default.fileExists(atPath: mediaURL.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: manifestURL.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: unrelatedArtifactURL.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: markerURL.path))

        do {
            try await VoiceTutorRecordingStore.shared.save(
                pending,
                lifecycleGeneration: staleGeneration
            )
            XCTFail("Recovery must keep the lifecycle fence ahead of a stale exporter")
        } catch VoiceTutorRecordingError.lifecycleInvalidated {
            XCTAssertFalse(FileManager.default.fileExists(atPath: manifestURL.path))
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testDurablePurgeMarkerFencesEveryEntryPointBeforeStartupCleanup() async throws {
        enum FirstAccess: CaseIterable {
            case newRecorder, existingRecorder, save, mediaURL, lifecycleGeneration
        }
        defer { try? VoiceTutorRecordingStore.purgeAll() }

        for firstAccess in FirstAccess.allCases {
            try VoiceTutorRecordingStore.purgeAll()
            let staleGeneration = VoiceTutorRecordingStore.lifecycleGeneration()
            let directory = try VoiceTutorRecordingStore.recordingsDirectory()
            let markerURL = try VoiceTutorRecordingStore.purgePendingMarkerURL()
            let sessionID = UUID().uuidString
            let pending = VoiceTutorPendingRecording(
                ownerUserID: 9_999,
                sessionID: sessionID,
                fileName: "\(sessionID).m4a",
                contentType: "audio/mp4",
                contentLength: 1,
                sha256: String(repeating: "0", count: 64),
                durationMilliseconds: 1,
                createdAt: Date()
            )
            let mediaURL = directory.appendingPathComponent(pending.fileName)
            let manifestURL = directory.appendingPathComponent("\(sessionID).json")
            let manifest = try JSONEncoder().encode(pending)
            try Data([0]).write(to: mediaURL)
            try manifest.write(to: manifestURL)
            // No in-memory purge API is called after writing this marker. Each
            // entry point must independently discover a previous process's purge.
            try Data("voice-tutor-recording-purge-v1\n".utf8).write(
                to: markerURL,
                options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
            )

            do {
                switch firstAccess {
                case .newRecorder:
                    _ = try VoiceTutorSessionRecorder(sessionID: sessionID, ownerUserID: 7)
                case .existingRecorder:
                    _ = try VoiceTutorSessionRecorder(
                        sessionID: sessionID,
                        ownerUserID: 7,
                        lifecycleGeneration: staleGeneration
                    )
                case .save:
                    try await VoiceTutorRecordingStore.shared.save(
                        pending,
                        lifecycleGeneration: staleGeneration
                    )
                case .mediaURL:
                    _ = try await VoiceTutorRecordingStore.shared.mediaURL(for: pending)
                case .lifecycleGeneration:
                    let generation = VoiceTutorRecordingStore.lifecycleGeneration()
                    XCTAssertNotEqual(generation, staleGeneration)
                    XCTAssertFalse(VoiceTutorRecordingStore.isCurrentLifecycleGeneration(generation))
                    throw VoiceTutorRecordingError.lifecycleInvalidated
                }
                XCTFail("\(firstAccess) must be fenced before startup cleanup")
            } catch VoiceTutorRecordingError.lifecycleInvalidated {
                // Expected: access never publishes, opens, or removes old audio.
            } catch {
                XCTFail("Unexpected \(firstAccess) error: \(error)")
            }

            XCTAssertTrue(FileManager.default.fileExists(atPath: markerURL.path))
            XCTAssertEqual(try Data(contentsOf: mediaURL), Data([0]))
            XCTAssertEqual(try Data(contentsOf: manifestURL), manifest)
            XCTAssertFalse(FileManager.default.fileExists(
                atPath: directory.appendingPathComponent("\(sessionID)-learner.m4a").path
            ))
            XCTAssertFalse(VoiceTutorRecordingStore.isCurrentLifecycleGeneration(staleGeneration))
            try await VoiceTutorRecordingStore.shared.cleanupExpiredAndOrphaned()
            XCTAssertFalse(FileManager.default.fileExists(atPath: markerURL.path))
            XCTAssertFalse(FileManager.default.fileExists(atPath: mediaURL.path))
            XCTAssertFalse(FileManager.default.fileExists(atPath: manifestURL.path))
            XCTAssertTrue(VoiceTutorRecordingStore.isCurrentLifecycleGeneration(
                VoiceTutorRecordingStore.lifecycleGeneration()
            ))
        }
    }

    func testLogoutLifecycleFenceRejectsPostPurgeRecordingSave() async throws {
        let sessionID = UUID().uuidString
        let generation = VoiceTutorRecordingStore.lifecycleGeneration()
        let pending = VoiceTutorPendingRecording(
            ownerUserID: 7,
            sessionID: sessionID,
            fileName: "\(sessionID).m4a",
            contentType: "audio/mp4",
            contentLength: 1,
            sha256: String(repeating: "0", count: 64),
            durationMilliseconds: 1,
            createdAt: Date()
        )
        let manifestURL = try VoiceTutorRecordingStore.recordingsDirectory()
            .appendingPathComponent("\(sessionID).json")
        defer {
            try? VoiceTutorRecordingStore.purgeAll()
        }

        try VoiceTutorRecordingStore.purgeAll()
        do {
            try await VoiceTutorRecordingStore.shared.save(
                pending,
                lifecycleGeneration: generation
            )
            XCTFail("A recorder created before logout must not recreate its manifest")
        } catch VoiceTutorRecordingError.lifecycleInvalidated {
            XCTAssertFalse(FileManager.default.fileExists(atPath: manifestURL.path))
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testRecorderExcludesSetupAndDelayedPreReadyFramesFromDuration() async throws {
        try VoiceTutorRecordingStore.purgeAll()
        defer { try? VoiceTutorRecordingStore.purgeAll() }
        let recorder = try VoiceTutorSessionRecorder(
            sessionID: UUID().uuidString,
            ownerUserID: 7
        )
        let frame: (TimeInterval) -> VoiceTutorPCMFrame = { capturedAt in
            VoiceTutorPCMFrame(
                sampleRate: 48_000,
                channels: [Array(repeating: Float(0.1), count: 480)],
                frameCount: 480,
                capturedAtUptime: capturedAt
            )
        }
        recorder.append(frame(100), participant: .learner)
        recorder.markSessionReady(atUptime: 200)
        recorder.append(frame(150), participant: .tutor)
        recorder.append(frame(201), participant: .learner)
        recorder.append(frame(201.05), participant: .tutor)

        let pending = try await recorder.finish()

        XCTAssertEqual(pending.durationMilliseconds, 60)
        XCTAssertGreaterThan(pending.contentLength, 0)
        let mediaURL = try await VoiceTutorRecordingStore.shared.mediaURL(for: pending)
        XCTAssertTrue(FileManager.default.fileExists(atPath: mediaURL.path))
    }

    func testRecorderStopCutoffRejectsDelayedPostHangupFrames() async throws {
        try VoiceTutorRecordingStore.purgeAll()
        defer { try? VoiceTutorRecordingStore.purgeAll() }
        let recorder = try VoiceTutorSessionRecorder(
            sessionID: UUID().uuidString,
            ownerUserID: 7
        )
        let frame: (TimeInterval) -> VoiceTutorPCMFrame = { capturedAt in
            VoiceTutorPCMFrame(
                sampleRate: 48_000,
                channels: [Array(repeating: Float(0.1), count: 480)],
                frameCount: 480,
                capturedAtUptime: capturedAt
            )
        }
        recorder.markSessionReady(atUptime: 100)
        recorder.append(frame(100), participant: .learner)
        recorder.stopAcceptingFrames(atUptime: 100.025)
        recorder.append(frame(100.02), participant: .tutor)
        recorder.append(frame(101), participant: .learner)
        recorder.stopAcceptingFrames(atUptime: 200)
        recorder.append(frame(150), participant: .tutor)

        let pending = try await recorder.finish()

        XCTAssertEqual(pending.durationMilliseconds, 30)
        XCTAssertGreaterThan(pending.contentLength, 0)
    }

    func testRecorderCannotStartWithPreLogoutLifecycleGeneration() throws {
        let generation = VoiceTutorRecordingStore.lifecycleGeneration()
        try VoiceTutorRecordingStore.purgeAll()
        defer {
            try? VoiceTutorRecordingStore.purgeAll()
        }

        XCTAssertThrowsError(
            try VoiceTutorSessionRecorder(
                sessionID: UUID().uuidString,
                ownerUserID: 7,
                lifecycleGeneration: generation
            )
        ) { error in
            guard case VoiceTutorRecordingError.lifecycleInvalidated = error else {
                return XCTFail("Unexpected error: \(error)")
            }
        }
    }

    func testRecordingUploadCanCompleteAfterSuccessOrPreconditionFailure() {
        XCTAssertTrue(
            RemotePushBackendClient.canCompleteVoiceTutorRecordingUpload(
                afterHTTPStatus: 200
            )
        )
        XCTAssertTrue(
            RemotePushBackendClient.canCompleteVoiceTutorRecordingUpload(
                afterHTTPStatus: 204
            )
        )
        XCTAssertTrue(
            RemotePushBackendClient.canCompleteVoiceTutorRecordingUpload(
                afterHTTPStatus: 412
            )
        )
        XCTAssertFalse(
            RemotePushBackendClient.canCompleteVoiceTutorRecordingUpload(
                afterHTTPStatus: 409
            )
        )
        XCTAssertFalse(
            RemotePushBackendClient.canCompleteVoiceTutorRecordingUpload(
                afterHTTPStatus: 500
            )
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

    @MainActor
    func testStaleTokenBootstrapCannotReplaceNewAccountsStoredRegistration() async throws {
        let bootstrapArrived = expectation(description: "A token bootstrap is in flight")
        let releaseResponse = VoiceTutorContractResponseGate()
        let refreshedA = try VoiceTutorContractAppFixture.registration(ownerUserID: 7, tokenID: "refreshed-a")
        let registrationB = try VoiceTutorContractAppFixture.registration(ownerUserID: 8, tokenID: "b")
        let registrationA = RemotePushRegistration(
            deviceID: refreshedA.deviceID,
            clientSecret: refreshedA.clientSecret,
            apnsToken: ""
        )
        var requests: [URLRequest] = []
        let fixture = try VoiceTutorContractAppFixture(registration: registrationA) { request in
            requests.append(request)
            guard request.url?.path == "/api/v1/auth/token" else {
                XCTFail("A stale bootstrap must not proceed to session creation")
                throw URLError(.badServerResponse)
            }
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "X-Client-Secret"), registrationA.clientSecret)
            bootstrapArrived.fulfill()
            await releaseResponse.wait()
            return VoiceTutorContractAppFixture.response(
                for: request,
                body: VoiceTutorContractAppFixture.tokenResponse(refreshedA)
            )
        }
        defer {
            releaseResponse.open()
            fixture.close()
        }
        let creation = Task { try await fixture.appState.createVoiceTutorConnection(studyID: 42) }
        defer { creation.cancel() }
        let bootstrapWait = await XCTWaiter.fulfillment(of: [bootstrapArrived], timeout: 5)
        XCTAssertEqual(bootstrapWait, .completed)

        fixture.replaceAccount(ownerUserID: 8, registration: registrationB)
        releaseResponse.open()
        do {
            _ = try await creation.value
            XCTFail("A token bootstrap response must not authorize a connection after account replacement")
        } catch VoiceTutorPreparationError.missingRegistration {
            // The original account was fenced before its token could be saved.
        } catch is CancellationError {
            // Cancellation is also an acceptable stale-operation boundary.
        }

        XCTAssertEqual(fixture.store.loadRemotePushRegistration(), registrationB)
        XCTAssertEqual(fixture.appState.communityProfile?.id, 8)
        XCTAssertTrue(fixture.appState.isCommunitySessionActive)
        XCTAssertEqual(requests.map { $0.url?.path }, ["/api/v1/auth/token"])
    }

    @MainActor
    func testInvalidSDPOriginEndsCreatedSessionWithExactlyTheRecoveredRegistration() async throws {
        try await assertInvalidWebRTCOriginEndsRecoveredSession(invalidField: "sdpUrl")
    }

    @MainActor
    func testInvalidControlOriginEndsCreatedSessionWithExactlyTheRecoveredRegistration() async throws {
        try await assertInvalidWebRTCOriginEndsRecoveredSession(invalidField: "controlWebsocketUrl")
    }

    @MainActor
    func testStaleHistoryStatusAndSummaryResponsesCannotEnterReplacementAccountCache() async throws {
        let requestsArrived = expectation(description: "A history, status, and summary requests are in flight")
        requestsArrived.expectedFulfillmentCount = 3
        let releaseResponses = VoiceTutorContractResponseGate()
        let registrationA = try VoiceTutorContractAppFixture.registration(ownerUserID: 7, tokenID: "a")
        let registrationB = try VoiceTutorContractAppFixture.registration(ownerUserID: 8, tokenID: "b")
        let sessionA = UUID().uuidString
        let sessionB = UUID().uuidString
        let fixture = try VoiceTutorContractAppFixture(registration: registrationA) { request in
            let body: String
            if request.value(forHTTPHeaderField: "Authorization") == "Bearer \(registrationA.accessToken!)" {
                switch request.url?.path {
                case "/api/v1/voice-tutor/status":
                    body = #"{"eligible":true,"tierCode":"TIER2","quota":{"limitSeconds":18000,"usedSeconds":120,"reservedSeconds":0,"remainingSeconds":17880}}"#
                case "/api/v1/voice-tutor/sessions":
                    body = """
                    {"sessions":[{"sessionId":"\(sessionA)","topic":"A private lesson"}],"nextCursor":"a-next-page"}
                    """
                case "/api/v1/voice-tutor/sessions/\(sessionA)":
                    body = """
                    {"sessionId":"\(sessionA)","result":{"summaryMarkdown":"A private summary"}}
                    """
                default:
                    XCTFail("Unexpected A request: \(request.url?.path ?? "")")
                    throw URLError(.badServerResponse)
                }
                requestsArrived.fulfill()
                await releaseResponses.wait()
            } else {
                XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer \(registrationB.accessToken!)")
                switch request.url?.path {
                case "/api/v1/voice-tutor/sessions/\(sessionB)":
                    body = """
                    {"sessionId":"\(sessionB)","result":{"summaryMarkdown":"B private summary"}}
                    """
                case "/api/v1/voice-tutor/sessions":
                    body = """
                    {"sessions":[{"sessionId":"\(sessionB)","topic":"B private lesson"}]}
                    """
                default:
                    XCTFail("Unexpected B request: \(request.url?.path ?? "")")
                    throw URLError(.badServerResponse)
                }
            }
            return VoiceTutorContractAppFixture.response(for: request, body: body)
        }
        defer {
            releaseResponses.open()
            fixture.close()
        }
        let history = Task { await fixture.appState.loadVoiceTutorSessions() }
        let status = Task { await fixture.appState.refreshVoiceTutorStatus() }
        let summary = Task { await fixture.appState.loadVoiceTutorSessionDetail(sessionID: sessionA) }
        defer {
            history.cancel()
            status.cancel()
            summary.cancel()
        }
        let requestsWait = await XCTWaiter.fulfillment(of: [requestsArrived], timeout: 5)
        XCTAssertEqual(requestsWait, .completed)

        fixture.replaceAccount(ownerUserID: 8, registration: registrationB)
        let currentSummary = await fixture.appState.loadVoiceTutorSessionDetail(sessionID: sessionB)
        XCTAssertEqual(currentSummary?.result?.summaryMarkdown, "B private summary")
        releaseResponses.open()
        await history.value
        await status.value
        let staleSummary = await summary.value

        XCTAssertNil(staleSummary)
        XCTAssertNil(fixture.appState.voiceTutorStatus)
        XCTAssertTrue(fixture.appState.voiceTutorSessions.isEmpty)
        XCTAssertNil(fixture.appState.voiceTutorNextCursor)
        XCTAssertNil(fixture.appState.voiceTutorSessionDetails[sessionA])
        XCTAssertEqual(fixture.appState.voiceTutorSessionDetails[sessionB]?.result?.summaryMarkdown, "B private summary")
        XCTAssertNil(fixture.appState.voiceTutorErrorMessage)
        XCTAssertEqual(fixture.store.loadRemotePushRegistration(), registrationB)

        await fixture.appState.loadVoiceTutorSessions()
        XCTAssertEqual(fixture.appState.voiceTutorSessions.map(\.sessionId), [sessionB])
    }

    @MainActor
    func testStaleEndSessionResponseCannotPublishSummaryToReplacementAccount() async throws {
        let endArrived = expectation(description: "A session end is in flight")
        let releaseResponse = VoiceTutorContractResponseGate()
        let registrationA = try VoiceTutorContractAppFixture.registration(ownerUserID: 7, tokenID: "a")
        let registrationB = try VoiceTutorContractAppFixture.registration(ownerUserID: 8, tokenID: "b")
        let sessionID = UUID().uuidString
        let fixture = try VoiceTutorContractAppFixture(registration: registrationA) { request in
            XCTAssertEqual(request.url?.path, "/api/v1/voice-tutor/sessions/\(sessionID)/end")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer \(registrationA.accessToken!)")
            endArrived.fulfill()
            await releaseResponse.wait()
            return VoiceTutorContractAppFixture.response(
                for: request,
                body: """
                {"sessionId":"\(sessionID)","state":"COMPLETED","result":{"summaryMarkdown":"A private summary"}}
                """
            )
        }
        defer {
            releaseResponse.open()
            fixture.close()
        }
        let ending = Task { try await fixture.appState.endVoiceTutorSession(sessionID: sessionID) }
        defer { ending.cancel() }
        let endWait = await XCTWaiter.fulfillment(of: [endArrived], timeout: 5)
        XCTAssertEqual(endWait, .completed)

        fixture.replaceAccount(ownerUserID: 8, registration: registrationB)
        releaseResponse.open()
        do {
            _ = try await ending.value
            XCTFail("A finalized summary must not be returned into B's screen")
        } catch is CancellationError {
            // The server may finish A's session, but its result remains fenced.
        } catch VoiceTutorPreparationError.missingRegistration {
            // Explicit stale-identity failures are also safe.
        }

        XCTAssertTrue(fixture.appState.voiceTutorSessionDetails.isEmpty)
        XCTAssertEqual(fixture.store.loadRemotePushRegistration(), registrationB)
    }

    @MainActor
    private func assertInvalidWebRTCOriginEndsRecoveredSession(invalidField: String) async throws {
        let original = try VoiceTutorContractAppFixture.registration(ownerUserID: 7, tokenID: "original-a")
        let recovered = try VoiceTutorContractAppFixture.registration(ownerUserID: 7, tokenID: "recovered-a")
        let sessionID = UUID().uuidString
        var requests: [URLRequest] = []
        var createCount = 0
        let fixture = try VoiceTutorContractAppFixture(registration: original) { request in
            requests.append(request)
            switch request.url?.path {
            case "/api/v1/auth/token":
                return VoiceTutorContractAppFixture.response(
                    for: request,
                    body: VoiceTutorContractAppFixture.tokenResponse(recovered)
                )
            case "/api/v1/voice-tutor/sessions":
                createCount += 1
                if createCount == 1 {
                    return VoiceTutorContractAppFixture.response(
                        for: request,
                        status: 401,
                        body: #"{"code":"AUTH_INVALID_ACCESS_TOKEN","message":"Expired"}"#
                    )
                }
                let sdpURL = invalidField == "sdpUrl"
                    ? "https://different-origin.test/session/webrtc"
                    : "/api/v1/voice-tutor/sessions/\(sessionID)/webrtc"
                let controlURL = invalidField == "controlWebsocketUrl"
                    ? "wss://different-origin.test/session/control"
                    : "/api/v1/voice-tutor/sessions/\(sessionID)/control"
                return VoiceTutorContractAppFixture.response(
                    for: request,
                    body: """
                    {"sessionId":"\(sessionID)","state":"READY","realtimeTransport":"WEBRTC","sdpUrl":"\(sdpURL)","controlWebsocketUrl":"\(controlURL)"}
                    """
                )
            case "/api/v1/voice-tutor/sessions/\(sessionID)/end":
                // Best-effort cleanup must not replace the original URL error
                // even if the finalization request itself fails.
                return VoiceTutorContractAppFixture.response(
                    for: request,
                    status: 503,
                    body: #"{"code":"TEMPORARILY_UNAVAILABLE","message":"Retry later"}"#
                )
            default:
                XCTFail("Unexpected request: \(request.url?.path ?? "")")
                throw URLError(.badServerResponse)
            }
        }
        defer { fixture.close() }

        do {
            _ = try await fixture.appState.createVoiceTutorConnection(studyID: 42)
            XCTFail("Cross-origin \(invalidField) must fail after session creation")
        } catch VoiceTutorPreparationError.invalidWebSocketURL {
            // The original contract failure survives a best-effort end failure.
        }

        XCTAssertEqual(createCount, 2)
        XCTAssertEqual(requests.map { $0.url?.path }, [
            "/api/v1/voice-tutor/sessions",
            "/api/v1/auth/token",
            "/api/v1/voice-tutor/sessions",
            "/api/v1/voice-tutor/sessions/\(sessionID)/end"
        ])
        let creates = requests.filter { $0.url?.path == "/api/v1/voice-tutor/sessions" }
        XCTAssertEqual(creates.first?.value(forHTTPHeaderField: "Authorization"), "Bearer \(original.accessToken!)")
        XCTAssertEqual(creates.last?.value(forHTTPHeaderField: "Authorization"), "Bearer \(recovered.accessToken!)")
        XCTAssertNotNil(creates.first?.value(forHTTPHeaderField: "Idempotency-Key"))
        XCTAssertEqual(
            creates.first?.value(forHTTPHeaderField: "Idempotency-Key"),
            creates.last?.value(forHTTPHeaderField: "Idempotency-Key")
        )
        let endRequest = try XCTUnwrap(requests.last)
        XCTAssertEqual(endRequest.httpMethod, "POST")
        XCTAssertEqual(endRequest.value(forHTTPHeaderField: "Authorization"), "Bearer \(recovered.accessToken!)")
        XCTAssertEqual(endRequest.value(forHTTPHeaderField: "X-Device-Id"), recovered.deviceID)
        XCTAssertEqual(endRequest.value(forHTTPHeaderField: "X-Client-Secret"), recovered.clientSecret)
        XCTAssertEqual(fixture.store.loadRemotePushRegistration()?.accessToken, recovered.accessToken)
        XCTAssertTrue(fixture.appState.voiceTutorSessionDetails.isEmpty)
    }
}

@MainActor
private final class VoiceTutorContractResponseGate {
    private var isOpen = false
    private var continuations: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        guard !isOpen else { return }
        await withCheckedContinuation { continuation in
            continuations.append(continuation)
        }
    }

    func open() {
        isOpen = true
        let waiting = continuations
        continuations.removeAll()
        for continuation in waiting { continuation.resume() }
    }
}

@MainActor
private final class VoiceTutorContractAppFixture {
    let store: SettingsStore
    let appState: AppState
    private let defaults: UserDefaults
    private let suiteName: String
    private let host: String
    private let session: URLSession

    init(
        registration: RemotePushRegistration,
        handler: @escaping VoiceTutorContractURLProtocol.Handler
    ) throws {
        let identifier = UUID().uuidString.lowercased()
        suiteName = "VoiceTutorContractTests-\(identifier)"
        defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        store = SettingsStore(defaults: defaults, usesSecureBackendIdentityStorage: false)
        store.saveIsCommunitySignedIn(true)
        store.saveRemotePushRegistration(registration)
        host = "\(identifier).voice-tutor.test"
        VoiceTutorContractURLProtocol.install(handler, forHost: host)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [VoiceTutorContractURLProtocol.self]
        configuration.timeoutIntervalForRequest = 5
        configuration.timeoutIntervalForResource = 5
        session = URLSession(configuration: configuration)
        let client = RemotePushBackendClient(
            baseURL: URL(string: "https://\(host)")!,
            session: session
        )
        appState = AppState(
            settingsStore: store,
            remotePushBackendClient: client,
            appNotificationEventProvider: VoiceTutorContractNotificationEvents()
        )
        appState.communityProfile = Self.profile(ownerUserID: 7)
    }

    func replaceAccount(ownerUserID: Int, registration: RemotePushRegistration) {
        store.saveRemotePushRegistration(registration)
        appState.communityProfile = Self.profile(ownerUserID: ownerUserID)
    }

    func close() {
        session.invalidateAndCancel()
        VoiceTutorContractURLProtocol.removeHandler(forHost: host)
        defaults.removePersistentDomain(forName: suiteName)
    }

    static func registration(ownerUserID: Int, tokenID: String) throws -> RemotePushRegistration {
        // Both accounts use the same installation and client secret. Owner
        // changes must still invalidate requests even without device rotation.
        let payload = try JSONSerialization.data(withJSONObject: [
            "device_id": "voice-fixture-device",
            "user_id": ownerUserID,
            "is_anonymous": false,
            "status": "ACTIVE",
            "jti": tokenID
        ], options: [.sortedKeys])
        let encoded = payload.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return RemotePushRegistration(
            deviceID: "voice-fixture-device",
            clientSecret: "voice-fixture-secret",
            apnsToken: "",
            accessToken: "e30.\(encoded).fixture-signature",
            accessTokenExpiresAt: Date(timeIntervalSince1970: 4_102_444_800)
        )
    }

    static func tokenResponse(_ registration: RemotePushRegistration) -> String {
        """
        {"accessToken":"\(registration.accessToken!)","accessTokenExpiresAt":"2100-01-01T00:00:00Z"}
        """
    }

    static func response(
        for request: URLRequest,
        status: Int = 200,
        body: String
    ) -> (HTTPURLResponse, Data) {
        (
            HTTPURLResponse(
                url: request.url!,
                statusCode: status,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!,
            Data(body.utf8)
        )
    }

    private static func profile(ownerUserID: Int) -> CommunityUserProfile {
        CommunityUserProfile(
            id: ownerUserID,
            displayName: "Fixture-\(ownerUserID)",
            status: "ACTIVE",
            provider: "GOOGLE",
            bio: "",
            avatarURL: nil
        )
    }
}

@MainActor
private struct VoiceTutorContractNotificationEvents: AppNotificationEventProviding {
    func observeAPITrafficLogs(
        _ handler: @MainActor @escaping (APITrafficLogEntry) -> Void
    ) -> AnyCancellable {
        AnyCancellable {}
    }

    func observeBackendUnauthorized(
        _ handler: @MainActor @escaping (BackendUnauthorizedRequestIdentity) -> Void
    ) -> AnyCancellable {
        AnyCancellable {}
    }
}

private final class VoiceTutorContractURLProtocol: URLProtocol, @unchecked Sendable {
    typealias Handler = @MainActor (URLRequest) async throws -> (HTTPURLResponse, Data)

    private static let lock = NSLock()
    private nonisolated(unsafe) static var handlers: [String: Handler] = [:]

    static func install(_ handler: @escaping Handler, forHost host: String) {
        lock.lock()
        defer { lock.unlock() }
        handlers[host] = handler
    }

    static func removeHandler(forHost host: String) {
        lock.lock()
        defer { lock.unlock() }
        handlers.removeValue(forKey: host)
    }

    private static func handler(forHost host: String) -> Handler? {
        lock.lock()
        defer { lock.unlock() }
        return handlers[host]
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let reference = VoiceTutorContractUncheckedSendableBox(self)
        Task { @MainActor in
            let protocolInstance = reference.value
            do {
                let handler = Self.handler(forHost: protocolInstance.request.url?.host ?? "")
                guard let handler else { throw URLError(.unsupportedURL) }
                let (response, data) = try await handler(protocolInstance.request)
                protocolInstance.client?.urlProtocol(protocolInstance, didReceive: response, cacheStoragePolicy: .notAllowed)
                protocolInstance.client?.urlProtocol(protocolInstance, didLoad: data)
                protocolInstance.client?.urlProtocolDidFinishLoading(protocolInstance)
            } catch {
                protocolInstance.client?.urlProtocol(protocolInstance, didFailWithError: error)
            }
        }
    }

    override func stopLoading() {}
}

private final class VoiceTutorContractUncheckedSendableBox<Value>: @unchecked Sendable {
    let value: Value

    init(_ value: Value) {
        self.value = value
    }
}
