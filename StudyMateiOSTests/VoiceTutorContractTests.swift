import Foundation
import Combine
import XCTest
import AVFoundation
import SwiftUI
import UIKit
import QuartzCore
import LiveKitWebRTC
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
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Voice-Turn-Protocol"), "local-vad-v1")
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

    func testInputAssessmentRetryIsASeparateNonterminalEvent() throws {
        let event = try VoiceTutorRealtimeEventParser.parse(
            text: #"{"type":"buddystudy.voice.input.retry","reason":"ignored","message":"not UI content"}"#
        )
        XCTAssertEqual(event, .inputRetry)
        XCTAssertNotEqual(event, .serviceError(code: nil, message: "", retryable: true))
    }

    func testInputRetryKeepsCompactCallLiveAndDoesNotOverrideTutorSpeechOrMute() {
        let strings = AppStrings(language: .korean)
        var call = VoiceTutorCallPresentation(
            phase: .listening,
            inputNeedsRepeat: true,
            sessionSecondsRemaining: 120
        )
        XCTAssertEqual(call.statusText(strings), strings.voiceTutorInputRepeat)
        XCTAssertEqual(call.primaryAction, .end)
        XCTAssertEqual(call.remainingTime, .call(120))
        XCTAssertTrue(call.canMute)
        XCTAssertFalse(call.showsConnectionFailure(strings, errorMessage: nil))
        call.phase = .speaking
        XCTAssertEqual(call.statusText(strings), strings.voiceTutorCallSpeaking)
        call.phase = .listening
        call.isMuted = true
        XCTAssertEqual(call.statusText(strings), strings.voiceTutorCallMuted)
        call.isMuted = false
        call.inputNeedsRepeat = false
        XCTAssertEqual(call.statusText(strings), strings.voiceTutorCallListening)
        XCTAssertEqual(AppStrings(language: .english).voiceTutorInputRepeat, "Please say that again")
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

    func testWebRTCResponseFinishesOnBothMatchingServerSignalsInEitherOrder() {
        for stopFirst in [false, true] {
            var state = VoiceTutorWebRTCResponseState()
            state.responseStarted("response-1")
            let first = stopFirst
                ? state.markOutputBufferStopped("response-1")
                : state.markResponseDone("response-1")
            XCTAssertNil(first)
            XCTAssertEqual(state.responseID, "response-1")
            let second = stopFirst
                ? state.markResponseDone("response-1")
                : state.markOutputBufferStopped("response-1")
            XCTAssertEqual(second, "response-1")
            XCTAssertNil(state.responseID)
            XCTAssertNil(state.markResponseDone("response-1"))
            XCTAssertNil(state.markOutputBufferStopped("response-1"))
        }
    }

    func testWebRTCResponseIsNotBlockedByContinuousNonzeroComfortNoise() throws {
        var fixture = VoiceTutorContractRenderFixture()
        fixture.response.responseStarted("response-1")
        fixture.response.markOutputBufferStarted("response-1")
        fixture.render(try makeRenderBuffer([0, 100, 0, 0]))
        XCTAssertNil(fixture.response.markResponseDone("response-1"))
        let comfortNoise = try makeRenderBuffer(Array(repeating: Int16(1), count: 480))

        // Unlike an all-zero fixture, this reproduces the callbacks that kept
        // cancelling the production drain task throughout the reported call.
        for _ in 1...200 { fixture.render(comfortNoise) }
        XCTAssertEqual(fixture.response.markOutputBufferStopped("response-1"), "response-1")
        fixture.phase = .listening
        for _ in 1...200 { fixture.render(comfortNoise) }

        XCTAssertEqual(fixture.callbackCount, 401)
        XCTAssertNil(fixture.response.responseID)
        XCTAssertEqual(fixture.phase, .listening)
        fixture.response.responseStarted("response-2")
        XCTAssertNil(fixture.response.markResponseDone("response-2"))
        XCTAssertEqual(fixture.response.markOutputBufferStopped("response-2"), "response-2")
    }

    func testWebRTCResponseCompletionDoesNotModifyLateQuietSamples() throws {
        var fixture = VoiceTutorContractRenderFixture()
        fixture.response.responseStarted("response-1")
        fixture.response.markOutputBufferStarted("response-1")
        fixture.render(try makeRenderBuffer([100, 0]))
        XCTAssertNil(fixture.response.markResponseDone("response-1"))
        XCTAssertEqual(fixture.response.markOutputBufferStopped("response-1"), "response-1")
        fixture.phase = .listening
        let quietTail = try makeRenderBuffer([0, -1])
        fixture.render(try makeRenderBuffer([0, 0]))
        fixture.render(quietTail)

        XCTAssertEqual(fixture.callbackCount, 2, "The renderer still receives the late tail")
        XCTAssertEqual(quietTail.frameLength, 2)
        XCTAssertEqual(quietTail.int16ChannelData?[0][1], -1, "Completion is not a playback operation")
        XCTAssertEqual(fixture.phase, .listening, "An old tail must not restart the speaking label")
    }

    func testWebRTCInitialSilenceIsNotShownAsSpeakingOrRequiredToEndAResponse() throws {
        var fixture = VoiceTutorContractRenderFixture()
        fixture.response.responseStarted("response-1")
        fixture.response.markOutputBufferStarted("response-1")
        fixture.render(try makeRenderBuffer([0, 0]))
        XCTAssertEqual(fixture.phase, .listening, "Provider streaming alone is not a rendered sound")
        fixture.render(try makeRenderBuffer([1, 0]))
        XCTAssertEqual(fixture.phase, .speaking)
        XCTAssertNil(fixture.response.markOutputBufferStopped("response-1"))
        XCTAssertEqual(fixture.response.markResponseDone("response-1"), "response-1")
    }

    func testWebRTCEarlyMediaCannotStrandFirstOrSubsequentShortResponses() throws {
        var fixture = VoiceTutorContractRenderFixture()
        fixture.phase = .connecting
        let shortAudio = try makeRenderBuffer([0, 1])
        for turn in 1...3 {
            // All of a short response's media can precede its sideband control
            // messages. No subsequent PCM callback may be required to finish.
            fixture.render(shortAudio)
            let id = "response-\(turn)"
            fixture.response.responseStarted(id)
            XCTAssertNil(fixture.response.markOutputBufferStopped(id))
            XCTAssertEqual(fixture.response.markResponseDone(id), id)
        }
        XCTAssertEqual(fixture.callbackCount, 3)
        XCTAssertEqual(fixture.phase, .connecting, "Early media must not break the startup phase guard")
    }

    func testWebRTCRendererPreservesQuietSamplesInEveryStereoChannelLayout() throws {
        for interleaved in [true, false] {
            let silence = try makeRenderBuffer([0, 0, 0, 0], channels: 2, interleaved: interleaved)
            XCTAssertFalse(VoiceTutorRemoteAudioRenderer.containsNonzeroSamples(silence))
            for quietSample in [Int16(-1), Int16(1)] {
                let audio = try makeRenderBuffer([0, 0, 0, quietSample], channels: 2, interleaved: interleaved)
                XCTAssertTrue(VoiceTutorRemoteAudioRenderer.containsNonzeroSamples(audio))
            }
        }
        let format = try XCTUnwrap(AVAudioFormat(
            commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 2, interleaved: false
        ))
        let floatBuffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 2))
        floatBuffer.frameLength = 2
        let channels = try XCTUnwrap(floatBuffer.floatChannelData)
        for channel in 0..<2 {
            for sample in 0..<2 { channels[channel][sample] = 0 }
        }
        XCTAssertFalse(VoiceTutorRemoteAudioRenderer.containsNonzeroSamples(floatBuffer))
        channels[1][1] = Float.leastNonzeroMagnitude
        XCTAssertTrue(VoiceTutorRemoteAudioRenderer.containsNonzeroSamples(floatBuffer))
    }

    func testWebRTCRenderedAudioOnlyChangesAnActiveListeningIndication() {
        XCTAssertEqual(VoiceTutorSessionPhase.listening.afterRenderedTutorAudio(assistantResponseActive: true), .speaking)
        XCTAssertEqual(VoiceTutorSessionPhase.listening.afterRenderedTutorAudio(assistantResponseActive: false), .listening)
        for phase in [VoiceTutorSessionPhase.idle, .requestingPermission, .connecting, .speaking, .ending, .ended, .failed] {
            XCTAssertEqual(phase.afterRenderedTutorAudio(assistantResponseActive: true), phase)
        }
    }

    func testVoiceTutorErrorDiagnosticsExcludeDescriptionsAndUntrustedDomains() {
        let error = NSError(
            domain: "private-transcript-or-address",
            code: 42,
            userInfo: [NSLocalizedDescriptionKey: "private speech and secret URL"]
        )
        let fields = VoiceTutorDiagnosticError.fields(for: error)
        XCTAssertTrue(fields.contains("errorDomain=other"))
        XCTAssertTrue(fields.contains("errorCode=42"))
        XCTAssertFalse(fields.contains("private"))
        XCTAssertFalse(fields.contains("secret"))
        XCTAssertTrue(VoiceTutorDiagnosticError.fields(for: URLError(.networkConnectionLost)).contains("errorDomain=NSURLErrorDomain"))
        XCTAssertTrue(VoiceTutorDiagnosticError.fields(for: VoiceTutorRealtimeEventParser.ParseError.invalidJSON).contains("parser.invalidJSON"))
    }

    func testWebRTCResponseRejectsMissingStaleAndDuplicateCompletionSignals() {
        var state = VoiceTutorWebRTCResponseState()
        state.responseStarted("response-2")
        XCTAssertNil(state.markResponseDone(nil))
        XCTAssertNil(state.markOutputBufferStopped(nil))
        XCTAssertNil(state.markResponseDone("response-1"))
        XCTAssertNil(state.markOutputBufferStopped("response-1"))
        XCTAssertFalse(state.responseDone)
        XCTAssertFalse(state.outputBufferStopped)
        XCTAssertNil(state.markResponseDone("response-2"))
        XCTAssertNil(state.markResponseDone("response-2"))
        state.responseStarted("response-2")
        XCTAssertTrue(state.responseDone, "A repeated start must not erase a received completion")
        XCTAssertEqual(state.markOutputBufferStopped("response-2"), "response-2")
        XCTAssertNil(state.markOutputBufferStopped("response-2"))
    }

    func testWebRTCResponseResetAndPreviousResponseCannotFinishANewTurn() {
        var state = VoiceTutorWebRTCResponseState()
        state.responseStarted("response-old")
        XCTAssertNil(state.markResponseDone("response-old"))
        state.reset()
        state.responseStarted("response-2")
        XCTAssertNil(state.markOutputBufferStopped("response-old"))
        XCTAssertNil(state.markResponseDone("response-old"))
        XCTAssertFalse(state.responseDone)
        XCTAssertNil(state.markOutputBufferStopped("response-2"))
        XCTAssertEqual(state.markResponseDone("response-2"), "response-2")
    }

    func testWebRTCRenderNoiseOutsideProviderStreamingCannotRestartSpeaking() throws {
        var fixture = VoiceTutorContractRenderFixture()
        let noise = try makeRenderBuffer([1, -1])
        fixture.response.responseStarted("response-1")
        fixture.render(noise)
        XCTAssertEqual(fixture.phase, .listening)
        fixture.response.markOutputBufferStarted("response-other")
        fixture.render(noise)
        XCTAssertEqual(fixture.phase, .listening)
        fixture.response.markOutputBufferStarted("response-1")
        fixture.render(noise)
        XCTAssertEqual(fixture.phase, .speaking)
        XCTAssertNil(fixture.response.markOutputBufferStopped("response-1"))
        fixture.response.markOutputBufferStarted("response-1")
        XCTAssertFalse(fixture.response.mayIndicateSpeaking, "A duplicate start cannot reopen stopped audio")
        XCTAssertEqual(fixture.response.markResponseDone("response-1"), "response-1")
        fixture.phase = .listening
        fixture.render(noise)
        XCTAssertEqual(fixture.phase, .listening)
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
        XCTAssertFalse(sources.contains("sendPlayoutDrained"), "WebRTC PCM silence is not a response gate")
    }

    func testCompactVoiceCallUsesTheReservedSessionCountdownWhileLive() {
        for phase in [VoiceTutorSessionPhase.requestingPermission, .connecting, .listening, .speaking] {
            var presentation = VoiceTutorCallPresentation(
                phase: phase,
                sessionSecondsRemaining: 3_596,
                quotaRemainingSeconds: 0,
                quotaReservedSeconds: 3_600,
                quotaLimitSeconds: 3_600
            )
            XCTAssertEqual(presentation.remainingTime, .call(3_596), "\(phase)")
            presentation.sessionSecondsRemaining = -1
            XCTAssertEqual(presentation.remainingTime, .call(0), "\(phase)")
        }
    }

    func testCompactVoiceCallDoesNotInventTimeBeforeTheServerCountdownArrives() {
        for phase in [VoiceTutorSessionPhase.requestingPermission, .connecting, .listening, .speaking] {
            let presentation = VoiceTutorCallPresentation(
                phase: phase,
                sessionSecondsRemaining: nil,
                quotaRemainingSeconds: 2_400,
                quotaReservedSeconds: 1_200,
                quotaLimitSeconds: 3_600
            )
            XCTAssertNil(presentation.remainingTime, "Monthly availability is not the active call timer: \(phase)")
        }
    }

    func testCompactVoiceCallTerminalTimeUsesOnlySettledMonthlyQuota() {
        for phase in [VoiceTutorSessionPhase.ended, .failed] {
            var presentation = VoiceTutorCallPresentation(
                phase: phase,
                sessionSecondsRemaining: 3_596,
                quotaRemainingSeconds: 3_472,
                quotaReservedSeconds: 0,
                quotaLimitSeconds: 3_600
            )
            XCTAssertEqual(presentation.remainingTime, .monthly(3_472), "Ignore the stale call countdown")
            presentation.quotaReservedSeconds = 3_600
            XCTAssertNil(presentation.remainingTime, "Do not expose an unsettled reservation as exhausted time")
            presentation.quotaReservedSeconds = 0
            presentation.quotaRemainingSeconds = -1
            XCTAssertEqual(presentation.remainingTime, .monthly(0))
            presentation.quotaLimitSeconds = 0
            XCTAssertNil(presentation.remainingTime)
        }
        for phase in [VoiceTutorSessionPhase.idle, .ending] {
            let presentation = VoiceTutorCallPresentation(
                phase: phase,
                sessionSecondsRemaining: 3_596,
                quotaRemainingSeconds: 3_472,
                quotaLimitSeconds: 3_600
            )
            XCTAssertNil(presentation.remainingTime)
        }
    }

    func testCompactVoiceCallActionsMuteAndStatusFollowTheConnectionPhase() {
        for language in [AppLanguage.korean, .english, .japanese] {
            let strings = AppStrings(language: language)
            let cases: [(VoiceTutorSessionPhase, Bool, VoiceTutorCallPresentation.PrimaryAction, String)] = [
                (.idle, false, .wait, strings.voiceTutorCallConnecting),
                (.requestingPermission, false, .end, strings.voiceTutorCallConnecting),
                (.connecting, false, .end, strings.voiceTutorCallConnecting),
                (.listening, true, .end, strings.voiceTutorCallListening),
                (.speaking, true, .end, strings.voiceTutorCallSpeaking),
                (.ending, false, .wait, strings.voiceTutorCallEnding),
                (.ended, false, .dismiss, strings.voiceTutorCallEnded),
                (.failed, false, .retry, strings.voiceTutorCallFailed)
            ]
            for (phase, canMute, action, text) in cases {
                var presentation = VoiceTutorCallPresentation(phase: phase)
                XCTAssertEqual(presentation.canMute, canMute, "\(language): \(phase)")
                XCTAssertEqual(presentation.primaryAction, action, "\(language): \(phase)")
                XCTAssertEqual(presentation.statusText(strings), text, "\(language): \(phase)")
                presentation.isMuted = true
                XCTAssertEqual(presentation.canMute, canMute)
                XCTAssertEqual(presentation.primaryAction, action)
                XCTAssertEqual(
                    presentation.statusText(strings),
                    phase == .listening ? strings.voiceTutorCallMuted : text
                )
            }
        }
    }

    @MainActor
    func testCompactVoiceCallDoesNotInferSpeakingFromCaptionsOrTranscriptDraft() {
        let strings = AppStrings(language: .korean)
        let screen = VoiceTutorCallScreen(
            topic: "Redis",
            presentation: VoiceTutorCallPresentation(phase: .listening),
            strings: strings,
            captions: [VoiceTutorCaption(speaker: .tutor, text: "합성 테스트 대화입니다.")],
            assistantTranscriptDraft: "텍스트가 도착해도 아직 음성이 재생되지 않았습니다.",
            showsTranscript: .constant(true),
            showsSummary: .constant(false)
        )
        XCTAssertEqual(screen.presentation.phase, .listening)
        XCTAssertEqual(screen.presentation.statusText(strings), strings.voiceTutorCallListening)
        XCTAssertNotEqual(screen.presentation.statusText(strings), strings.voiceTutorCallSpeaking)

        let speaking = VoiceTutorCallPresentation(phase: .speaking, isMuted: true)
        XCTAssertEqual(speaking.statusText(strings), strings.voiceTutorCallSpeaking)
    }

    func testCompactVoiceCallFailureSurvivesACompletedLearningResult() throws {
        let strings = AppStrings(language: .korean)
        var detail = try makeCompactCallDetail(
            resultStatus: "COMPLETED",
            result: ["status": "COMPLETED", "summaryMarkdown": "합성 학습 기록입니다."]
        )
        detail.state = "COMPLETED"
        let presentation = VoiceTutorCallPresentation(phase: .failed, detail: detail)
        XCTAssertEqual(presentation.statusText(strings), strings.voiceTutorCallFailed)
        XCTAssertNotEqual(presentation.statusText(strings), strings.voiceTutorCallEnded)
        XCTAssertEqual(presentation.primaryAction, .retry)
        XCTAssertEqual(presentation.summaryState, .ready)
        XCTAssertFalse(presentation.canMute)
    }

    func testCompactVoiceCallShowsDisconnectionWhileFailureSettlementIsStillPending() {
        for language in [AppLanguage.korean, .english, .japanese] {
            let strings = AppStrings(language: language)
            let presentation = VoiceTutorCallPresentation(phase: .ending)
            let failure = " \n\(strings.voiceTutorConnectionFailed) \n"
            XCTAssertTrue(presentation.showsConnectionFailure(strings, errorMessage: failure))
            XCTAssertEqual(presentation.statusText(strings, errorMessage: failure), strings.voiceTutorCallFailed)
            XCTAssertNil(presentation.supplementaryError(strings, errorMessage: failure))
            XCTAssertFalse(presentation.canMute)
            XCTAssertEqual(presentation.primaryAction, .wait, "Retry must wait for the original call to settle")
            XCTAssertFalse(presentation.showsConnectionFailure(strings, errorMessage: nil))
            XCTAssertEqual(presentation.statusText(strings), strings.voiceTutorCallEnding)
        }
    }

    func testCompactVoiceCallKeepsActionableErrorsAndSuppressesOnlyDuplicateDisconnectText() {
        for language in [AppLanguage.korean, .english, .japanese] {
            let strings = AppStrings(language: language)
            let presentation = VoiceTutorCallPresentation(phase: .failed)
            XCTAssertTrue(presentation.showsConnectionFailure(strings, errorMessage: nil))
            XCTAssertNil(presentation.supplementaryError(strings, errorMessage: strings.voiceTutorConnectionFailed))
            XCTAssertNil(presentation.supplementaryError(strings, errorMessage: nil))
            XCTAssertNil(presentation.supplementaryError(strings, errorMessage: " \n\t "))
            for actionableError in [
                strings.voiceTutorMicrophoneDenied, strings.voiceTutorProRequiredMessage,
                strings.voiceTutorQuotaReached, strings.voiceTutorSignInRequired
            ] {
                XCTAssertEqual(
                    presentation.supplementaryError(strings, errorMessage: " \n\(actionableError) \n"),
                    actionableError
                )
            }
            let connecting = VoiceTutorCallPresentation(phase: .connecting)
            XCTAssertFalse(connecting.showsConnectionFailure(strings, errorMessage: strings.voiceTutorConnectionFailed))
            XCTAssertEqual(
                connecting.supplementaryError(strings, errorMessage: strings.voiceTutorConnectionFailed),
                strings.voiceTutorConnectionFailed,
                "Never hide an error unless the compact status already communicates it"
            )
        }
    }

    func testCompactVoiceCallSummaryStatesDistinguishPendingFailedReadyAndAbsentContent() throws {
        typealias State = VoiceTutorCallPresentation.SummaryState
        let content: [String: Any] = ["status": "COMPLETED", "summaryMarkdown": "합성 요약"]
        let cases: [(String?, [String: Any]?, State)] = [
            (nil, nil, .hidden),
            ("PENDING", nil, .pending),
            ("processing", nil, .pending),
            ("FAILED", nil, .failed),
            ("COMPLETED", nil, .hidden),
            ("COMPLETED", [:], .hidden),
            ("COMPLETED", ["summaryMarkdown": " \n\t ", "strengths": [], "improvements": [], "nextSteps": []], .hidden),
            ("COMPLETED", ["summaryMarkdown": " ", "strengths": [" \n"], "improvements": [""], "nextSteps": ["\t"]], .hidden),
            ("COMPLETED", content, .ready),
            (nil, content, .ready),
            (nil, ["status": "pending"], .pending),
            (nil, ["status": "PROCESSING"], .pending),
            (nil, ["status": "FAILED"], .failed),
            ("FAILED", content, .failed),
            ("PENDING", content, .pending),
            ("COMPLETED", ["status": "FAILED", "summaryMarkdown": "불완전한 합성 요약"], .failed),
            ("COMPLETED", ["status": "PROCESSING", "summaryMarkdown": "불완전한 합성 요약"], .pending),
            ("UNKNOWN", ["summaryMarkdown": "아직 완료되지 않은 합성 요약"], .hidden),
            (nil, ["summaryMarkdown": "완료 상태가 없는 합성 요약"], .hidden)
        ]
        for (index, entry) in cases.enumerated() {
            let detail = try makeCompactCallDetail(resultStatus: entry.0, result: entry.1)
            for phase in [VoiceTutorSessionPhase.ended, .failed] {
                let presentation = VoiceTutorCallPresentation(phase: phase, detail: detail)
                XCTAssertEqual(presentation.summaryState, entry.2, "Case \(index), \(phase)")
            }
        }

        let completedDetail = try makeCompactCallDetail(resultStatus: "COMPLETED", result: content)
        for phase in [VoiceTutorSessionPhase.idle, .requestingPermission, .connecting, .listening, .speaking, .ending] {
            XCTAssertEqual(VoiceTutorCallPresentation(phase: phase, detail: completedDetail).summaryState, .hidden)
        }
        XCTAssertEqual(VoiceTutorCallPresentation(phase: .ended).summaryState, .hidden)
        XCTAssertEqual(VoiceTutorCallPresentation(phase: .failed).summaryState, .hidden)
    }

    func testCompactVoiceCallCompletedSummaryAcceptsEachSupportedContentSection() throws {
        let supportedContent: [[String: Any]] = [
            ["summaryMarkdown": "합성 요약"],
            ["strengths": ["합성 강점"]],
            ["improvements": ["합성 보완점"]],
            ["nextSteps": ["합성 다음 학습"]]
        ]
        for content in supportedContent {
            let detail = try makeCompactCallDetail(resultStatus: "completed", result: content)
            XCTAssertEqual(VoiceTutorCallPresentation(phase: .ended, detail: detail).summaryState, .ready)
        }
    }

    func testCompactVoiceCallClockKeepsSecondsAndHandlesBoundsInEveryLanguage() {
        let cases: [(Int, String)] = [
            (Int.min, "00:00"), (-1, "00:00"), (0, "00:00"),
            (1, "00:01"), (59, "00:59"), (60, "01:00"), (61, "01:01"),
            (3_600, "60:00"), (Int.max, "153722867280912930:07")
        ]
        for (seconds, clock) in cases {
            let korean = AppStrings(language: .korean)
            XCTAssertEqual(korean.voiceTutorCallRemaining(seconds), "\(clock) 남음")
            XCTAssertEqual(korean.voiceTutorCallMonthlyRemaining(seconds), "이번 달 \(clock) 남음")
            let english = AppStrings(language: .english)
            XCTAssertEqual(english.voiceTutorCallRemaining(seconds), "\(clock) left")
            XCTAssertEqual(english.voiceTutorCallMonthlyRemaining(seconds), "\(clock) left this month")
            let japanese = AppStrings(language: .japanese)
            XCTAssertEqual(japanese.voiceTutorCallRemaining(seconds), "残り\(clock)")
            XCTAssertEqual(japanese.voiceTutorCallMonthlyRemaining(seconds), "今月の残り\(clock)")
        }
    }

    func testSingleOrbTranscriptGestureRequiresAnIntentionalVerticalSwipe() {
        typealias Gesture = VoiceTutorCallTranscriptGesture
        XCTAssertEqual(
            Gesture.action(translation: CGSize(width: 0, height: 44), isExpanded: false),
            .reveal
        )
        XCTAssertEqual(
            Gesture.action(translation: CGSize(width: 0, height: -44), isExpanded: true),
            .collapse
        )
        XCTAssertNil(Gesture.action(translation: CGSize(width: 0, height: -80), isExpanded: false))
        XCTAssertNil(Gesture.action(translation: CGSize(width: 0, height: 80), isExpanded: true))
        XCTAssertNil(Gesture.action(translation: CGSize(width: 0, height: 43), isExpanded: false))
        XCTAssertNil(Gesture.action(translation: CGSize(width: 80, height: 60), isExpanded: false))
        XCTAssertNil(Gesture.action(translation: CGSize(width: 40, height: 44), isExpanded: false))
        XCTAssertEqual(
            Gesture.action(translation: CGSize(width: 20, height: 80), isExpanded: false),
            .reveal
        )
    }

    @MainActor
    func testCompactVoiceCallScreensRenderWithoutCreatingARealSession() async throws {
        let pending = try makeCompactCallDetail(resultStatus: "PROCESSING")
        let completed = try makeCompactCallDetail(
            resultStatus: "COMPLETED",
            result: ["summaryMarkdown": "Redis의 만료 시간과 캐시 갱신을 복습했습니다."]
        )
        var pausedState = VoiceTutorCallPauseState()
        pausedState.isSupported = true
        let pause = try XCTUnwrap(pausedState.requestPause())
        XCTAssertTrue(pausedState.acknowledge(sequence: pause.sequence, paused: true))
        let fixtures: [VoiceTutorCompactCallSnapshot] = [
            .init(name: "01-connecting", phase: .connecting, seconds: nil),
            .init(name: "02-listening", phase: .listening, seconds: 3_596),
            .init(name: "03-speaking-recording", phase: .speaking, isRecording: true),
            .init(
                name: "04-paused-static", phase: .listening,
                pauseState: pausedState
            ),
            .init(name: "05-failed-with-saved-summary", phase: .failed, detail: completed),
            .init(name: "06-ended-summary-pending", phase: .ended, detail: pending),
            .init(name: "07-expanded-conversation", phase: .speaking, showsTranscript: true),
            .init(
                name: "08-narrow-accessibility-english", phase: .listening,
                showsTranscript: true, language: .english,
                topic: "Redis caching and concurrent updates",
                size: CGSize(width: 320, height: 696), dynamicType: .accessibility3
            )
        ]
        var capturedPNGs = Set<Data>()
        for fixture in fixtures {
            let image = try await renderCompactCallSnapshot(fixture)
            XCTAssertEqual(image.size, fixture.size, fixture.name)
            let png = try XCTUnwrap(image.pngData(), fixture.name)
            XCTAssertGreaterThan(png.count, 2_000, "A blank or unrendered snapshot is not useful: \(fixture.name)")
            capturedPNGs.insert(png)
            // Freeze the already encoded bitmap while this fixture owns its
            // render resources; do not defer UIImage encoding across fixtures.
            let attachment = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
            attachment.name = "compact-call-\(fixture.name)"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        XCTAssertEqual(capturedPNGs.count, fixtures.count, "Each state must produce a distinct rendered screen")
    }

    func testLocalVoiceActivitySilenceNeverStartsASpeakingTurn() {
        var detector = VoiceTutorLocalSpeechDetector()
        XCTAssertNil(detector.updateGate(mediaReady: true, muted: false))
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0, frames: 500).isEmpty)
        XCTAssertFalse(detector.isSpeaking)
        XCTAssertEqual(detector.sequence, 0)
    }

    func testLocalVoiceActivityRejectsShortSpikesWithoutAccumulatingAcrossQuietGaps() {
        var detector = VoiceTutorLocalSpeechDetector()
        _ = detector.updateGate(mediaReady: true, muted: false)
        for _ in 0..<20 {
            XCTAssertTrue(localSpeechEvents(&detector, rms: 0.03, frames: 7).isEmpty)
            XCTAssertTrue(localSpeechEvents(&detector, rms: 0, frames: 10).isEmpty)
        }
        XCTAssertFalse(detector.isSpeaking)
        XCTAssertEqual(detector.sequence, 0)
        XCTAssertEqual(localSpeechEvents(&detector, rms: 0.03, frames: 8), [
            VoiceTutorLocalSpeechEvent(activity: .started, sequence: 1)
        ])
    }

    func testLocalVoiceActivityStartsOnceAndStopsAfterSevenHundredMillisecondsOfQuiet() {
        var detector = VoiceTutorLocalSpeechDetector()
        _ = detector.updateGate(mediaReady: true, muted: false)
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0.03, frames: 7).isEmpty)
        XCTAssertFalse(detector.isSpeaking)
        XCTAssertEqual(detector.process(normalizedRMS: 0.03, duration: 0.01),
                       VoiceTutorLocalSpeechEvent(activity: .started, sequence: 1))
        XCTAssertTrue(detector.isSpeaking)
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0.03, frames: 200).isEmpty,
                      "A sustained utterance starts only once")
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0, frames: 69).isEmpty)
        XCTAssertTrue(detector.isSpeaking, "A brief pause must not end a turn early")
        XCTAssertEqual(detector.process(normalizedRMS: 0, duration: 0.01),
                       VoiceTutorLocalSpeechEvent(activity: .stopped, sequence: 1))
        XCTAssertFalse(detector.isSpeaking)
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0, frames: 200).isEmpty)
        XCTAssertEqual(detector.sequence, 1)
    }

    func testLocalVoiceActivityHysteresisPreservesQuietSpeechAndResetsTheSilenceHold() {
        var detector = VoiceTutorLocalSpeechDetector()
        _ = detector.updateGate(mediaReady: true, muted: false)
        XCTAssertEqual(localSpeechEvents(&detector, rms: 0.03, frames: 8).count, 1)
        // 0.004 is below the onset floor but above the release floor. Once
        // speaking, it is quiet continuing speech rather than a new onset.
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0.004, frames: 200).isEmpty)
        XCTAssertTrue(detector.isSpeaking)
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0, frames: 60).isEmpty)
        XCTAssertNil(detector.process(normalizedRMS: 0.004, duration: 0.01))
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0, frames: 69).isEmpty,
                      "Continuing speech restarts the complete 700ms silence hold")
        XCTAssertEqual(detector.process(normalizedRMS: 0, duration: 0.01),
                       VoiceTutorLocalSpeechEvent(activity: .stopped, sequence: 1))
    }

    func testLocalVoiceActivityNormalizedAndNativeWebRTCSamplesProduceIdenticalEvents() throws {
        var normalizedDetector = VoiceTutorLocalSpeechDetector()
        var nativeDetector = VoiceTutorLocalSpeechDetector()
        _ = normalizedDetector.updateGate(mediaReady: true, muted: false)
        _ = nativeDetector.updateGate(mediaReady: true, muted: false)
        let segments: [(Float, Int)] = [(0, 30), (0.012, 16), (0.004, 40), (0, 80), (0.02, 12), (0, 80)]
        var normalizedEvents: [VoiceTutorLocalSpeechEvent] = []
        var nativeEvents: [VoiceTutorLocalSpeechEvent] = []
        for (amplitude, frames) in segments {
            let normalizedSamples: [Float] = [amplitude, -amplitude, amplitude, -amplitude]
            let nativeSamples = normalizedSamples.map { $0 * 32_768 }
            let normalizedRMS = try XCTUnwrap(VoiceTutorLocalSpeechDetector.normalizedRMS(
                samples: normalizedSamples, scale: .normalizedFloat
            ))
            let nativeRMS = try XCTUnwrap(VoiceTutorLocalSpeechDetector.normalizedRMS(
                samples: nativeSamples, scale: .webRTCFloatS16
            ))
            XCTAssertEqual(normalizedRMS, nativeRMS, accuracy: 0.000_000_001)
            normalizedEvents += localSpeechEvents(&normalizedDetector, rms: normalizedRMS, frames: frames)
            nativeEvents += localSpeechEvents(&nativeDetector, rms: nativeRMS, frames: frames)
        }
        XCTAssertEqual(normalizedEvents, nativeEvents)
        XCTAssertEqual(normalizedEvents, [
            VoiceTutorLocalSpeechEvent(activity: .started, sequence: 1),
            VoiceTutorLocalSpeechEvent(activity: .stopped, sequence: 1),
            VoiceTutorLocalSpeechEvent(activity: .started, sequence: 2),
            VoiceTutorLocalSpeechEvent(activity: .stopped, sequence: 2)
        ])
    }

    func testLocalVoiceActivityQuietNativeFloatSamplesNeverGuessNormalizedScale() throws {
        let samples: [Float] = [0.5, -0.5, 0.5, -0.5]
        let nativeRMS = try XCTUnwrap(VoiceTutorLocalSpeechDetector.normalizedRMS(
            samples: samples, scale: .webRTCFloatS16
        ))
        let normalizedRMS = try XCTUnwrap(VoiceTutorLocalSpeechDetector.normalizedRMS(
            samples: samples, scale: .normalizedFloat
        ))
        XCTAssertEqual(nativeRMS, 0.5 / 32_768, accuracy: 0.000_000_000_001)
        XCTAssertEqual(normalizedRMS, 0.5, accuracy: 0.000_000_001)
        var detector = VoiceTutorLocalSpeechDetector()
        _ = detector.updateGate(mediaReady: true, muted: false)
        XCTAssertTrue(localSpeechEvents(&detector, rms: nativeRMS, frames: 300).isEmpty)
        XCTAssertFalse(detector.isSpeaking,
                       "A native FloatS16 sample below one is not half-scale microphone audio")
    }

    func testLocalVoiceActivityRMSRejectsEmptyAndNonFiniteSamples() throws {
        let invalidSamples: [[Float]] = [
            [], [.nan], [.infinity], [-.infinity], [0, .nan, 0], [0.2, .infinity, -0.2]
        ]
        for scale in [VoiceTutorSpeechSampleScale.normalizedFloat, .webRTCFloatS16] {
            for samples in invalidSamples {
                XCTAssertNil(VoiceTutorLocalSpeechDetector.normalizedRMS(samples: samples, scale: scale))
            }
            XCTAssertEqual(VoiceTutorLocalSpeechDetector.normalizedRMS(samples: [0, 0, 0], scale: scale), 0)
        }
        let quiet = try XCTUnwrap(VoiceTutorLocalSpeechDetector.normalizedRMS(
            samples: [Float.leastNonzeroMagnitude], scale: .normalizedFloat
        ))
        XCTAssertGreaterThan(quiet, 0, "Finite quiet samples must not become NaN or be discarded")
    }

    func testLocalVoiceActivityInvalidRMSAndDurationNeverAdvanceDetectionEvidence() {
        var detector = VoiceTutorLocalSpeechDetector()
        _ = detector.updateGate(mediaReady: true, muted: false)
        let invalidRMS: [Double] = [.nan, .infinity, -.infinity, -1, 1.000_001, .greatestFiniteMagnitude]
        let invalidDurations: [TimeInterval] = [0, -0.01, .nan, .infinity, -.infinity, 0.250_001]
        for _ in 0..<10 {
            for rms in invalidRMS {
                XCTAssertNil(detector.process(normalizedRMS: rms, duration: 0.01))
            }
            for duration in invalidDurations {
                XCTAssertNil(detector.process(normalizedRMS: 0.03, duration: duration))
            }
        }
        XCTAssertFalse(detector.isSpeaking)
        XCTAssertEqual(detector.sequence, 0)
        XCTAssertEqual(localSpeechEvents(&detector, rms: 0.03, frames: 8), [
            VoiceTutorLocalSpeechEvent(activity: .started, sequence: 1)
        ])
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0, frames: 69).isEmpty)
        for rms in invalidRMS {
            XCTAssertNil(detector.process(normalizedRMS: rms, duration: 0.01))
        }
        for duration in invalidDurations {
            XCTAssertNil(detector.process(normalizedRMS: 0, duration: duration))
        }
        XCTAssertTrue(detector.isSpeaking,
                      "Invalid capture metadata is not evidence of silence or permission to stop")
        XCTAssertEqual(detector.process(normalizedRMS: 0, duration: 0.01),
                       VoiceTutorLocalSpeechEvent(activity: .stopped, sequence: 1))
    }

    func testLocalVoiceActivityReadinessAndMuteGateDiscardPreReadyAudio() {
        var detector = VoiceTutorLocalSpeechDetector()
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0.05, frames: 100).isEmpty)
        XCTAssertNil(detector.updateGate(mediaReady: false, muted: false))
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0.05, frames: 100).isEmpty)
        XCTAssertNil(detector.updateGate(mediaReady: true, muted: true))
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0.05, frames: 100).isEmpty)
        XCTAssertEqual(detector.sequence, 0)
        XCTAssertNil(detector.updateGate(mediaReady: true, muted: false))
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0.03, frames: 7).isEmpty)
        XCTAssertEqual(detector.process(normalizedRMS: 0.03, duration: 0.01),
                       VoiceTutorLocalSpeechEvent(activity: .started, sequence: 1))
        XCTAssertEqual(detector.updateGate(mediaReady: false, muted: false),
                       VoiceTutorLocalSpeechEvent(activity: .stopped, sequence: 1))
        XCTAssertNil(detector.updateGate(mediaReady: false, muted: false))
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0.05, frames: 100).isEmpty)
        XCTAssertFalse(detector.isSpeaking)
    }

    func testLocalVoiceActivityMuteStopsOnceAndUnmuteRequiresANewOnset() {
        var detector = VoiceTutorLocalSpeechDetector()
        _ = detector.updateGate(mediaReady: true, muted: false)
        XCTAssertEqual(localSpeechEvents(&detector, rms: 0.03, frames: 8), [
            VoiceTutorLocalSpeechEvent(activity: .started, sequence: 1)
        ])
        XCTAssertEqual(detector.updateGate(mediaReady: true, muted: true),
                       VoiceTutorLocalSpeechEvent(activity: .stopped, sequence: 1))
        XCTAssertNil(detector.updateGate(mediaReady: true, muted: true))
        XCTAssertFalse(detector.isSpeaking)
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0.1, frames: 100).isEmpty)
        XCTAssertNil(detector.updateGate(mediaReady: true, muted: false))
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0.03, frames: 7).isEmpty)
        XCTAssertEqual(detector.process(normalizedRMS: 0.03, duration: 0.01),
                       VoiceTutorLocalSpeechEvent(activity: .started, sequence: 2))
        XCTAssertNil(detector.updateGate(mediaReady: true, muted: false))
        XCTAssertTrue(detector.isSpeaking, "Reasserting an open gate must not interrupt an utterance")
        XCTAssertEqual(localSpeechEvents(&detector, rms: 0, frames: 70), [
            VoiceTutorLocalSpeechEvent(activity: .stopped, sequence: 2)
        ])
    }

    func testLocalVoiceActivityResetDiscardsPriorOnsetAndRestartsAttemptSequence() {
        var detector = VoiceTutorLocalSpeechDetector()
        _ = detector.updateGate(mediaReady: true, muted: false)
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0.03, frames: 7).isEmpty)
        detector.reset()
        XCTAssertEqual(detector.sequence, 0)
        XCTAssertFalse(detector.isSpeaking)
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0.03, frames: 100).isEmpty)
        _ = detector.updateGate(mediaReady: true, muted: false)
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0.03, frames: 7).isEmpty)
        XCTAssertEqual(detector.process(normalizedRMS: 0.03, duration: 0.01),
                       VoiceTutorLocalSpeechEvent(activity: .started, sequence: 1))
        detector.reset()
        XCTAssertFalse(detector.isSpeaking)
        XCTAssertEqual(detector.sequence, 0)
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0, frames: 100).isEmpty)
        _ = detector.updateGate(mediaReady: true, muted: false)
        XCTAssertEqual(localSpeechEvents(&detector, rms: 0.03, frames: 8), [
            VoiceTutorLocalSpeechEvent(activity: .started, sequence: 1)
        ])
    }

    func testLocalVoiceActivityCloseRejectsLateCallbacksUntilAnExplicitReset() {
        var detector = VoiceTutorLocalSpeechDetector()
        _ = detector.updateGate(mediaReady: true, muted: false)
        XCTAssertEqual(localSpeechEvents(&detector, rms: 0.03, frames: 8).count, 1)
        detector.close()
        XCTAssertFalse(detector.isSpeaking)
        XCTAssertNil(detector.updateGate(mediaReady: true, muted: false))
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0, frames: 100).isEmpty)
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0.03, frames: 100).isEmpty)
        detector.close()
        XCTAssertNil(detector.updateGate(mediaReady: true, muted: true))
        detector.reset()
        XCTAssertEqual(detector.sequence, 0)
        _ = detector.updateGate(mediaReady: true, muted: false)
        XCTAssertEqual(localSpeechEvents(&detector, rms: 0.03, frames: 8), [
            VoiceTutorLocalSpeechEvent(activity: .started, sequence: 1)
        ])
    }

    func testLocalVoiceActivityKeepsListeningWhileTheTutorFinishesTheCurrentSentence() {
        var detector = VoiceTutorLocalSpeechDetector()
        var playback = VoiceTutorDuplexPlaybackState()
        XCTAssertTrue(playback.responseStarted(responseID: "synthetic-tutor-sentence", isTutorIntervention: false))
        XCTAssertTrue(playback.assistantAudioBegan(responseID: "synthetic-tutor-sentence"))
        _ = detector.updateGate(mediaReady: true, muted: false)
        XCTAssertEqual(localSpeechEvents(&detector, rms: 0.03, frames: 8), [
            VoiceTutorLocalSpeechEvent(activity: .started, sequence: 1)
        ])
        playback.userSpeechStarted()
        XCTAssertTrue(playback.isUserSpeaking)
        XCTAssertTrue(playback.assistantResponseActive)
        XCTAssertEqual(playback.activeResponseID, "synthetic-tutor-sentence")
        XCTAssertEqual(localSpeechEvents(&detector, rms: 0, frames: 70), [
            VoiceTutorLocalSpeechEvent(activity: .stopped, sequence: 1)
        ])
        playback.userSpeechStopped()
        XCTAssertFalse(playback.isUserSpeaking)
        XCTAssertTrue(playback.assistantResponseActive,
                      "Local speech edges never cancel, clear, or truncate tutor playback")
        XCTAssertEqual(playback.activeResponseID, "synthetic-tutor-sentence")
    }

    func testLocalVoiceActivityMessagesPairEdgesWithPositiveIncreasingUtteranceSequences() {
        var detector = VoiceTutorLocalSpeechDetector()
        _ = detector.updateGate(mediaReady: true, muted: false)
        var events: [VoiceTutorLocalSpeechEvent] = []
        for _ in 0..<6 {
            events += localSpeechEvents(&detector, rms: 0.03, frames: 8)
            events += localSpeechEvents(&detector, rms: 0, frames: 70)
        }
        XCTAssertEqual(events.count, 12)
        for (index, event) in events.enumerated() {
            XCTAssertGreaterThan(event.sequence, 0)
            XCTAssertEqual(event.sequence, index / 2 + 1)
            XCTAssertEqual(event.activity, index.isMultiple(of: 2) ? .started : .stopped)
            XCTAssertEqual(event.messageType, index.isMultiple(of: 2)
                ? "buddystudy.voice.input.speech.started"
                : "buddystudy.voice.input.speech.stopped")
        }
    }

    func testLocalVoiceActivityPayloadContainsOnlyTheAppEventAndUtteranceSequence() throws {
        for activity in [VoiceTutorLocalSpeechActivity.started, .stopped] {
            for sequence in [1, 2, Int.max] {
                let event = VoiceTutorLocalSpeechEvent(activity: activity, sequence: sequence)
                let payload = try VoiceTutorLocalSpeechProtocol.payload(for: event)
                XCTAssertEqual(Set(payload.keys), ["type", "sequence"])
                XCTAssertEqual(payload["type"] as? String, event.messageType)
                XCTAssertEqual(payload["sequence"] as? Int, sequence)
                let serialized = try JSONSerialization.data(withJSONObject: payload)
                XCTAssertLessThan(serialized.count, 128,
                                  "Speech activity sends tiny metadata, never microphone audio or provider commands")
                let decoded = try XCTUnwrap(JSONSerialization.jsonObject(with: serialized) as? [String: Any])
                XCTAssertEqual(decoded["sequence"] as? Int, sequence)
                XCTAssertEqual(decoded["type"] as? String, event.messageType)
            }
        }
    }

    func testLocalVoiceActivityPayloadRejectsNonpositiveUtteranceSequences() {
        for activity in [VoiceTutorLocalSpeechActivity.started, .stopped] {
            for sequence in [Int.min, -1, 0] {
                XCTAssertThrowsError(try VoiceTutorLocalSpeechProtocol.payload(for:
                    VoiceTutorLocalSpeechEvent(activity: activity, sequence: sequence)
                )) { error in
                    XCTAssertEqual(error as? VoiceTutorLocalSpeechDeliveryError, .invalidSequence)
                }
            }
        }
    }

    func testLocalVoiceActivityCapabilityPreservesAuthenticatedControlRequests() throws {
        var original = URLRequest(url: try XCTUnwrap(URL(
            string: "wss://voice-tutor.test/api/v1/voice-tutor/sessions/synthetic/control"
        )))
        original.httpMethod = "GET"
        original.timeoutInterval = 17
        original.setValue("Bearer fixture-token", forHTTPHeaderField: "Authorization")
        original.setValue("fixture-device", forHTTPHeaderField: "X-Device-Id")
        original.setValue("fixture-secret", forHTTPHeaderField: "X-Client-Secret")
        original.setValue("buddystudy.voice.control.v2", forHTTPHeaderField: "Sec-WebSocket-Protocol")
        let prepared = VoiceTutorLocalSpeechProtocol.addingCapability(to: original)
        XCTAssertEqual(VoiceTutorLocalSpeechProtocol.capabilityHeader, "X-Voice-Turn-Protocol")
        XCTAssertEqual(VoiceTutorLocalSpeechProtocol.capabilityValue, "local-vad-v1")
        XCTAssertEqual(prepared.value(forHTTPHeaderField: "X-Voice-Turn-Protocol"), "local-vad-v1")
        XCTAssertNil(original.value(forHTTPHeaderField: "X-Voice-Turn-Protocol"))
        XCTAssertEqual(prepared.url, original.url)
        XCTAssertEqual(prepared.httpMethod, original.httpMethod)
        XCTAssertEqual(prepared.timeoutInterval, original.timeoutInterval)
        for name in ["Authorization", "X-Device-Id", "X-Client-Secret", "Sec-WebSocket-Protocol"] {
            XCTAssertEqual(prepared.value(forHTTPHeaderField: name), original.value(forHTTPHeaderField: name))
        }
        XCTAssertEqual(VoiceTutorLocalSpeechProtocol.addingCapability(to: prepared), prepared)
        var staleCapability = prepared
        staleCapability.setValue("old-capability", forHTTPHeaderField: "X-Voice-Turn-Protocol")
        XCTAssertEqual(VoiceTutorLocalSpeechProtocol.addingCapability(to: staleCapability), prepared)
    }

    func testLocalVoiceActivityBoundedStreamPreservesPairedFIFOOrder() async throws {
        let events = [
            VoiceTutorLocalSpeechEvent(activity: .started, sequence: 1),
            VoiceTutorLocalSpeechEvent(activity: .stopped, sequence: 1),
            VoiceTutorLocalSpeechEvent(activity: .started, sequence: 2),
            VoiceTutorLocalSpeechEvent(activity: .stopped, sequence: 2)
        ]
        let pipe = VoiceTutorLocalSpeechEventStream(capacity: events.count)
        for event in events { pipe.yield(event) }
        pipe.finish()
        var received: [VoiceTutorLocalSpeechEvent] = []
        for try await event in pipe.stream { received.append(event) }
        XCTAssertEqual(received, events)
    }

    func testLocalVoiceActivityBoundedStreamOverflowFailsInsteadOfSilentlyDroppingAnEdge() async {
        let first = VoiceTutorLocalSpeechEvent(activity: .started, sequence: 1)
        let second = VoiceTutorLocalSpeechEvent(activity: .stopped, sequence: 1)
        let pipe = VoiceTutorLocalSpeechEventStream(capacity: 2)
        pipe.yield(first)
        pipe.yield(second)
        pipe.yield(VoiceTutorLocalSpeechEvent(activity: .started, sequence: 2))
        pipe.yield(VoiceTutorLocalSpeechEvent(activity: .stopped, sequence: 2))
        // Always terminate the fixture even if overflow handling regresses, so
        // the test fails rather than waiting indefinitely on a broken stream.
        pipe.finish()
        var received: [VoiceTutorLocalSpeechEvent] = []
        do {
            for try await event in pipe.stream { received.append(event) }
            XCTFail("Overflow must explicitly fail delivery, not silently drop a speech edge")
        } catch {
            XCTAssertEqual(error as? VoiceTutorLocalSpeechDeliveryError, .bufferOverflow)
        }
        XCTAssertEqual(received, [first, second], "The earliest paired edges must not be replaced by newer ones")
    }

    func testLocalVoiceActivityStreamCapacityRemainsBoundedForInvalidAndHugeValues() async {
        for (capacity, retainedCount) in [(Int.min, 1), (0, 1), (1, 1), (32, 32), (Int.max, 128)] {
            let pipe = VoiceTutorLocalSpeechEventStream(capacity: capacity)
            let sent = (0...retainedCount).map { index in
                VoiceTutorLocalSpeechEvent(
                    activity: index.isMultiple(of: 2) ? .started : .stopped,
                    sequence: index / 2 + 1
                )
            }
            for event in sent { pipe.yield(event) }
            pipe.finish()
            var received: [VoiceTutorLocalSpeechEvent] = []
            do {
                for try await event in pipe.stream { received.append(event) }
                XCTFail("Capacity \(capacity) must stop at its bounded limit")
            } catch {
                XCTAssertEqual(error as? VoiceTutorLocalSpeechDeliveryError, .bufferOverflow)
            }
            XCTAssertEqual(received, Array(sent.prefix(retainedCount)))
        }
    }

    func testLocalVoiceActivityFinishedStreamCannotDeliverLateEdgesIntoANewAttempt() async throws {
        let oldPipe = VoiceTutorLocalSpeechEventStream(capacity: 2)
        let newPipe = VoiceTutorLocalSpeechEventStream(capacity: 2)
        let started = VoiceTutorLocalSpeechEvent(activity: .started, sequence: 1)
        let stopped = VoiceTutorLocalSpeechEvent(activity: .stopped, sequence: 1)
        oldPipe.yield(started)
        oldPipe.finish()
        oldPipe.yield(stopped)
        oldPipe.yield(VoiceTutorLocalSpeechEvent(activity: .started, sequence: 2))
        newPipe.yield(started)
        newPipe.yield(stopped)
        newPipe.finish()
        var oldEvents: [VoiceTutorLocalSpeechEvent] = []
        for try await event in oldPipe.stream { oldEvents.append(event) }
        var newEvents: [VoiceTutorLocalSpeechEvent] = []
        for try await event in newPipe.stream { newEvents.append(event) }
        XCTAssertEqual(oldEvents, [started])
        XCTAssertEqual(newEvents, [started, stopped])
    }

    func testNativeVoiceModuleConstructorDelegateLossCannotPassProductionValidation() {
        let capture = VoiceTutorContractNativeAudioDelegate()
        let render = VoiceTutorContractNativeAudioDelegate()
        let module = LKRTCDefaultAudioProcessingModule(
            config: nil,
            capturePostProcessingDelegate: capture,
            renderPreProcessingDelegate: render
        )
        // This negative control exercises the pinned native SDK, not a mock.
        // 144.7559.14 discards constructor delegates. If that SDK defect is
        // fixed in a future upgrade, update this fixture deliberately.
        withExtendedLifetime((capture, render, module)) {
            XCTAssertNil(module.capturePostProcessingDelegate)
            XCTAssertNil(module.renderPreProcessingDelegate)
            assertNativeVoiceDelegateInstallationFails {
                try VoiceTutorAudioProcessingModuleFactory.validate(
                    module, captureDelegate: capture, renderDelegate: render
                )
            }
        }
    }

    func testNativeVoiceModuleFactoryInstallsExplicitCaptureAndRenderDelegateIdentities() throws {
        let capture = VoiceTutorContractNativeAudioDelegate()
        let render = VoiceTutorContractNativeAudioDelegate()
        let module = try VoiceTutorAudioProcessingModuleFactory.make(
            captureDelegate: capture, renderDelegate: render
        )
        try withExtendedLifetime((capture, render, module)) {
            XCTAssertTrue(module.capturePostProcessingDelegate === capture)
            XCTAssertTrue(module.renderPreProcessingDelegate === render)
            XCTAssertNoThrow(try VoiceTutorAudioProcessingModuleFactory.validate(
                module, captureDelegate: capture, renderDelegate: render
            ))
        }
    }

    func testNativeVoiceModuleWithoutRecordingStillInstallsTheProductionSpeechCaptureTap() throws {
        let tap = VoiceTutorLocalSpeechCaptureTap(recordingTap: nil, onActivity: { _ in })
        defer { tap.close() }
        let module = try VoiceTutorAudioProcessingModuleFactory.make(captureDelegate: tap)
        XCTAssertTrue(module.capturePostProcessingDelegate === tap,
                      "Speech detection must not depend on recording consent")
        XCTAssertNil(module.renderPreProcessingDelegate,
                     "No render recording delegate should be installed without consent")
        XCTAssertNoThrow(try VoiceTutorAudioProcessingModuleFactory.validate(module, captureDelegate: tap))
        XCTAssertFalse(tap.snapshot().gateEnabled)
        XCTAssertFalse(tap.snapshot().isClosed)
        withExtendedLifetime((tap, module)) {}
    }

    func testNativeVoiceModuleValidationRejectsMissingSubstitutedAndUnexpectedDelegates() throws {
        let capture = VoiceTutorContractNativeAudioDelegate()
        let render = VoiceTutorContractNativeAudioDelegate()
        let other = VoiceTutorContractNativeAudioDelegate()
        let module = try VoiceTutorAudioProcessingModuleFactory.make(
            captureDelegate: capture, renderDelegate: render
        )
        module.capturePostProcessingDelegate = nil
        assertNativeVoiceDelegateInstallationFails {
            try VoiceTutorAudioProcessingModuleFactory.validate(
                module, captureDelegate: capture, renderDelegate: render
            )
        }
        module.capturePostProcessingDelegate = other
        assertNativeVoiceDelegateInstallationFails {
            try VoiceTutorAudioProcessingModuleFactory.validate(
                module, captureDelegate: capture, renderDelegate: render
            )
        }
        module.capturePostProcessingDelegate = capture
        module.renderPreProcessingDelegate = nil
        assertNativeVoiceDelegateInstallationFails {
            try VoiceTutorAudioProcessingModuleFactory.validate(
                module, captureDelegate: capture, renderDelegate: render
            )
        }
        module.renderPreProcessingDelegate = other
        assertNativeVoiceDelegateInstallationFails {
            try VoiceTutorAudioProcessingModuleFactory.validate(
                module, captureDelegate: capture, renderDelegate: render
            )
        }
        module.renderPreProcessingDelegate = render
        assertNativeVoiceDelegateInstallationFails {
            try VoiceTutorAudioProcessingModuleFactory.validate(module, captureDelegate: capture)
        }
        XCTAssertNoThrow(try VoiceTutorAudioProcessingModuleFactory.validate(
            module, captureDelegate: capture, renderDelegate: render
        ))
        withExtendedLifetime((capture, render, other, module)) {}
    }

    func testNativeVoiceCaptureDiagnosticsAreBoundedAndClosedTapsCannotRevive() {
        let diagnostics = VoiceTutorContractCaptureDiagnostics()
        let tap = VoiceTutorLocalSpeechCaptureTap(
            recordingTap: nil,
            onActivity: { _ in diagnostics.observeActivity() },
            onDiagnostic: { kind, snapshot in diagnostics.observe(kind, snapshot: snapshot) }
        )
        tap.audioProcessingInitialize(sampleRate: 0, channels: 1)
        tap.audioProcessingInitialize(sampleRate: -1, channels: 1)
        XCTAssertEqual(diagnostics.snapshot().initializationEvents, 0)
        for _ in 0..<100 { tap.audioProcessingInitialize(sampleRate: 48_000, channels: 1) }
        let initialized = tap.snapshot()
        XCTAssertEqual(initialized.initializationCount, 102)
        XCTAssertEqual(initialized.sampleRate, 48_000)
        XCTAssertEqual(initialized.processedBufferCount, 0)
        XCTAssertEqual(initialized.validInputBufferCount, 0)
        XCTAssertEqual(diagnostics.snapshot().initializationEvents, 1)
        XCTAssertEqual(diagnostics.snapshot().firstInputEvents, 0)
        // The diagnostic preserves the native callback-time snapshot, before
        // later initialization calls or teardown can replace its metadata.
        XCTAssertEqual(diagnostics.snapshot().initialized?.initializationCount, 3)
        XCTAssertEqual(diagnostics.snapshot().initialized?.sampleRate, 48_000)
        tap.updateGate(mediaReady: true, muted: false)
        XCTAssertTrue(tap.snapshot().gateEnabled)
        tap.close()
        let closed = tap.snapshot()
        XCTAssertTrue(closed.isClosed)
        XCTAssertFalse(closed.gateEnabled)
        XCTAssertEqual(closed.sampleRate, 0)
        XCTAssertEqual(closed.initializationCount, initialized.initializationCount)
        for _ in 0..<10 {
            tap.audioProcessingInitialize(sampleRate: 44_100, channels: 2)
            tap.updateGate(mediaReady: true, muted: false)
        }
        tap.audioProcessingRelease()
        XCTAssertEqual(tap.snapshot(), closed)
        XCTAssertEqual(diagnostics.snapshot().initializationEvents, 1)
        XCTAssertEqual(diagnostics.snapshot().activityEvents, 0)
    }

    func testNativeVoiceRecordingSamplesNormalizeFloatS16WithoutMutatingTheNativeInput() throws {
        let native: [Float] = [-32_768, -16_384, -1, -0.5, 0, 0.5, 1, 16_384, 32_767, 32_768]
        let original = native
        let expected: [Float] = [-1, -0.5, -1 / 32_768, -0.5 / 32_768, 0,
                                 0.5 / 32_768, 1 / 32_768, 0.5, 32_767 / 32_768, 1]
        let copied = try XCTUnwrap(VoiceTutorAudioProcessingTap.normalizedRecordingSamples(native))
        XCTAssertEqual(copied.count, expected.count)
        for (sample, value) in zip(copied, expected) {
            XCTAssertEqual(sample, value, accuracy: 0.000_000_1)
        }
        XCTAssertEqual(native, original, "Recording conversion must never rewrite the RTP capture buffer")
        let pointerCopy = native.withUnsafeBufferPointer {
            VoiceTutorAudioProcessingTap.normalizedRecordingSamples($0)
        }
        XCTAssertEqual(pointerCopy, copied)
        XCTAssertLessThan(abs(copied[5]), 0.0001,
                          "A quiet native value below one is not already-normalized audio")
    }

    func testNativeVoiceRecordingSamplesClampFiniteOverflowToTheRecorderRange() throws {
        let native: [Float] = [-.greatestFiniteMagnitude, -65_536, 65_536, .greatestFiniteMagnitude]
        let copied = try XCTUnwrap(VoiceTutorAudioProcessingTap.normalizedRecordingSamples(native))
        XCTAssertEqual(copied, [-1, -1, 1, 1])
        XCTAssertTrue(copied.allSatisfy { $0.isFinite && (-1...1).contains($0) })
    }

    func testNativeVoiceRecordingSamplesRejectEmptyAndNonFiniteInput() {
        let invalid: [[Float]] = [[], [.nan], [.infinity], [-.infinity], [0, .nan, 1], [0, .infinity, -1]]
        for samples in invalid {
            XCTAssertNil(VoiceTutorAudioProcessingTap.normalizedRecordingSamples(samples))
            XCTAssertNil(samples.withUnsafeBufferPointer {
                VoiceTutorAudioProcessingTap.normalizedRecordingSamples($0)
            })
        }
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

    /// Deliberately outside the automatically selected non-microphone tests.
    /// Run this selector explicitly and opt in through the test-host environment.
    @MainActor
    func testOptInNativeVoiceCaptureDeliversFramesThroughTheProductionModuleFactory() async throws {
        guard ProcessInfo.processInfo.environment["BUDDYSTUDY_NATIVE_VOICE_CAPTURE_TEST"] == "1" else {
            throw XCTSkip("Native microphone probe is opt-in: set BUDDYSTUDY_NATIVE_VOICE_CAPTURE_TEST=1 and select this test explicitly.")
        }
        #if targetEnvironment(simulator)
        throw XCTSkip("Native microphone delivery must be verified on a physical iPhone.")
        #else
        var preflight = [nativeVoiceCapturePreflight(phase: "entry")]
        defer {
            // Preserve startup evidence even if permission, activation, peer
            // creation, or native recording fails before the frame attachment.
            XCTAssertLessThanOrEqual(preflight.count, 7)
            let attachment = XCTAttachment(string: preflight.joined(separator: "\n\n"))
            attachment.name = "native-voice-capture-preflight-metadata-only"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        guard AVAudioApplication.shared.recordPermission == .granted else {
            throw XCTSkip("Microphone permission is not already granted; this probe never requests permission or displays a prompt.")
        }
        let foregroundDeadline = ProcessInfo.processInfo.systemUptime + 2
        while UIApplication.shared.applicationState != .active
                && ProcessInfo.processInfo.systemUptime < foregroundDeadline {
            try await Task.sleep(for: .milliseconds(50))
        }
        guard UIApplication.shared.applicationState == .active else {
            throw XCTSkip("The test host did not become foreground-active within 2 seconds (applicationState=\(UIApplication.shared.applicationState.rawValue)); native microphone delivery was not verified.")
        }
        let audioSession = AVAudioSession.sharedInstance()
        let previousCategory = audioSession.category
        let previousMode = audioSession.mode
        let previousOptions = audioSession.categoryOptions
        let previousIODuration = audioSession.preferredIOBufferDuration
        defer {
            try? audioSession.setActive(false, options: [.notifyOthersOnDeactivation])
            try? audioSession.setCategory(previousCategory, mode: previousMode, options: previousOptions)
            try? audioSession.setPreferredIOBufferDuration(previousIODuration)
        }
        try audioSession.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetoothHFP])
        try audioSession.setPreferredIOBufferDuration(0.01)
        try audioSession.setActive(true)
        preflight.append(nativeVoiceCapturePreflight(phase: "session_activated"))
        guard audioSession.isInputAvailable else {
            throw XCTSkip("No microphone input route is available for the native capture probe.")
        }

        let diagnostics = VoiceTutorContractCaptureDiagnostics()
        let verifySilero = ProcessInfo.processInfo.environment["BUDDYSTUDY_SILERO_NATIVE_CAPTURE_TEST"] == "1"
        let speechScorer = verifySilero ? try await VoiceTutorSileroSpeechScorer.prepareBundled() : nil
        let tap = VoiceTutorLocalSpeechCaptureTap(
            recordingTap: nil,
            speechScorer: speechScorer,
            onActivity: { _ in diagnostics.observeActivity() },
            onDiagnostic: { kind, snapshot in diagnostics.observe(kind, snapshot: snapshot) }
        )
        let module = try VoiceTutorAudioProcessingModuleFactory.make(captureDelegate: tap)
        let factory = LKRTCPeerConnectionFactory(
            audioDeviceModuleType: .audioEngine,
            bypassVoiceProcessing: false,
            encoderFactory: nil,
            decoderFactory: nil,
            audioProcessingModule: module
        )
        let device = factory.audioDeviceModule
        preflight.append(nativeVoiceCapturePreflight(phase: "factory_created", device: device))
        let source = factory.audioSource(with: nil)
        let track = factory.audioTrack(with: source, trackId: "synthetic-native-capture-probe")
        var capturePeer: LKRTCPeerConnection?
        defer {
            tap.close()
            _ = device.stopRecording()
            capturePeer?.close()
            module.capturePostProcessingDelegate = nil
            module.renderPreProcessingDelegate = nil
            withExtendedLifetime((factory, source, track, capturePeer, module, tap)) {}
        }
        // PeerConnection.Initialize acquires the first MediaEngineReference;
        // WebRtcVoiceEngine.Init then registers ADM's audio-transport callback.
        // A factory/source/track alone leaves that callback unregistered, so
        // ADM can report a successful start while dropping every input frame.
        let configuration = LKRTCConfiguration()
        configuration.sdpSemantics = .unifiedPlan
        configuration.iceServers = []
        configuration.iceTransportPolicy = .none
        configuration.iceCandidatePoolSize = 0
        let constraints = LKRTCMediaConstraints(
            mandatoryConstraints: ["OfferToReceiveAudio": "false", "OfferToReceiveVideo": "false"],
            optionalConstraints: nil
        )
        capturePeer = factory.peerConnection(with: configuration, constraints: constraints, delegate: nil)
        let peer = try XCTUnwrap(capturePeer, "The native capture probe must initialize WebRTC's media engine")
        preflight.append(nativeVoiceCapturePreflight(phase: "peer_initialized", device: device))
        // The peer is retained only for native initialization: no sender, SDP,
        // ICE gathering, remote candidate, provider, recorder, or playout.
        preflight.append(nativeVoiceCapturePreflight(phase: "before_native_start", device: device))
        let startStatus = device.initAndStartRecording()
        preflight.append(nativeVoiceCapturePreflight(phase: "after_native_start", device: device, startStatus: startStatus))
        XCTAssertEqual(startStatus, 0, "The native microphone device failed to start")
        guard startStatus == 0 else { return }
        let deadline = ProcessInfo.processInfo.systemUptime + 3
        while tap.snapshot().validInputBufferCount < 5 && ProcessInfo.processInfo.systemUptime < deadline {
            try await Task.sleep(for: .milliseconds(50))
        }
        preflight.append(nativeVoiceCapturePreflight(phase: "capture_complete", device: device, startStatus: startStatus))
        let snapshot = tap.snapshot()
        let observed = diagnostics.snapshot()
        let attachment = XCTAttachment(string: """
        initializationCount=\(snapshot.initializationCount)
        processedBufferCount=\(snapshot.processedBufferCount)
        validInputBufferCount=\(snapshot.validInputBufferCount)
        validInputFrameCount=\(snapshot.validInputFrameCount)
        sampleRate=\(snapshot.sampleRate)
        gateEnabled=\(snapshot.gateEnabled)
        initializedDiagnosticCount=\(observed.initializationEvents)
        firstValidInputDiagnosticCount=\(observed.firstInputEvents)
        """)
        attachment.name = "native-voice-capture-metadata-only"
        attachment.lifetime = .keepAlways
        add(attachment)
        XCTAssertGreaterThan(snapshot.initializationCount, 0, "The actual native APM must initialize the installed capture delegate")
        XCTAssertGreaterThanOrEqual(snapshot.validInputBufferCount, 5,
                                    "A started device without native capture callbacks is a failure, not a passed probe")
        XCTAssertGreaterThan(snapshot.validInputFrameCount, 0)
        XCTAssertGreaterThan(snapshot.sampleRate, 0)
        XCTAssertFalse(snapshot.gateEnabled, "Native callback delivery must be observable before session-ready gating")
        XCTAssertEqual(observed.initializationEvents, 1)
        XCTAssertEqual(observed.firstInputEvents, 1)
        XCTAssertEqual(observed.activityEvents, 0)
        XCTAssertFalse(observed.firstInput?.gateEnabled ?? true)
        XCTAssertNil(peer.localDescription)
        XCTAssertNil(peer.remoteDescription)
        XCTAssertTrue(peer.senders.isEmpty)
        XCTAssertTrue(peer.receivers.isEmpty)
        XCTAssertEqual(peer.iceGatheringState, .new, "The microphone probe must never gather ICE candidates")
        if verifySilero {
            // Additional explicit opt-in: observe the actual native callback →
            // copy → resampler → bundled Core ML path. Activity is counted only;
            // there is still no sender, SDP, provider, playback or audio storage.
            tap.updateGate(mediaReady: true, muted: false)
            let acousticDeadline = ProcessInfo.processInfo.systemUptime + 3
            while tap.snapshot().speechInferenceCount < 5,
                  ProcessInfo.processInfo.systemUptime < acousticDeadline {
                try await Task.sleep(for: .milliseconds(50))
            }
            let acoustic = tap.snapshot()
            let acousticAttachment = XCTAttachment(string: """
            nativeSileroInferenceCount=\(acoustic.speechInferenceCount)
            nativeInputBuffers=\(acoustic.validInputBufferCount)
            sampleRate=\(acoustic.sampleRate)
            gateEnabled=\(acoustic.gateEnabled)
            providerConnection=false
            recording=false
            """)
            acousticAttachment.name = "native-silero-capture-metadata-only"
            acousticAttachment.lifetime = .keepAlways
            add(acousticAttachment)
            XCTAssertGreaterThanOrEqual(acoustic.speechInferenceCount, 5,
                                        "Native callbacks must reach the real bundled model, not just the gate-disabled tap")
            XCTAssertTrue(acoustic.gateEnabled)
            tap.updateGate(mediaReady: false, muted: false)
        }
        #endif
    }

    @MainActor
    private func nativeVoiceCapturePreflight(
        phase: String,
        device: LKRTCAudioDeviceModule? = nil,
        startStatus: Int? = nil
    ) -> String {
        let session = AVAudioSession.sharedInstance()
        var fields = [
            "phase=\(phase)",
            "applicationState=\(UIApplication.shared.applicationState.rawValue)",
            "recordPermission=\(AVAudioApplication.shared.recordPermission.rawValue)",
            "sampleRate=\(session.sampleRate)",
            "isInputAvailable=\(session.isInputAvailable)",
            "inputChannelCount=\(session.inputNumberOfChannels)",
            "outputChannelCount=\(session.outputNumberOfChannels)",
            "inputRouteCount=\(session.currentRoute.inputs.count)",
            "outputRouteCount=\(session.currentRoute.outputs.count)",
            "category=\(session.category.rawValue)",
            "mode=\(session.mode.rawValue)",
            "categoryOptions=\(session.categoryOptions.rawValue)",
            "ioBufferDuration=\(session.ioBufferDuration)"
        ]
        if let startStatus { fields.append("nativeStartStatus=\(startStatus)") }
        if let device {
            let state = device.engineState
            fields.append(contentsOf: [
                "nativeRecordingInitialized=\(device.isRecordingInitialized)",
                "nativeRecording=\(device.isRecording)",
                "nativeEngineRunning=\(device.isEngineRunning)",
                "nativeMicrophoneMuted=\(device.isMicrophoneMuted)",
                "engineOutputEnabled=\(state.outputEnabled)",
                "engineOutputRunning=\(state.outputRunning)",
                "engineInputEnabled=\(state.inputEnabled)",
                "engineInputRunning=\(state.inputRunning)",
                "engineInputMuted=\(state.inputMuted)",
                "engineMuteMode=\(state.muteMode.rawValue)"
            ])
        }
        return fields.joined(separator: "\n")
    }

    private func assertNativeVoiceDelegateInstallationFails(_ operation: () throws -> Void) {
        XCTAssertThrowsError(try operation()) { error in
            guard case VoiceTutorWebRTCError.audioProcessingDelegateInstallationFailed = error else {
                return XCTFail("Expected explicit native audio delegate installation failure")
            }
        }
    }

    private func localSpeechEvents(
        _ detector: inout VoiceTutorLocalSpeechDetector,
        rms: Double,
        frames: Int
    ) -> [VoiceTutorLocalSpeechEvent] {
        var events: [VoiceTutorLocalSpeechEvent] = []
        for _ in 0..<frames {
            if let event = detector.process(normalizedRMS: rms, duration: 0.01) {
                events.append(event)
            }
        }
        return events
    }

    private func makeCompactCallDetail(
        resultStatus: String? = nil,
        result: [String: Any]? = nil
    ) throws -> BackendVoiceTutorSessionDetail {
        var payload: [String: Any] = [
            "sessionId": "compact-call-synthetic-fixture",
            "state": "ENDED", "topic": "Redis", "durationSeconds": 60,
            "chargedSeconds": 60, "transcriptTurns": []
        ]
        if let resultStatus { payload["resultStatus"] = resultStatus }
        if let result { payload["result"] = result }
        return try decoder.decode(
            BackendVoiceTutorSessionDetail.self,
            from: JSONSerialization.data(withJSONObject: payload)
        )
    }

    @MainActor
    private func renderCompactCallSnapshot(_ fixture: VoiceTutorCompactCallSnapshot) async throws -> UIImage {
        let strings = AppStrings(language: fixture.language)
        let captions = fixture.language == .english
            ? [VoiceTutorCaption(speaker: .learner, text: "How does a cache expire?"),
               VoiceTutorCaption(speaker: .tutor, text: "A time to live lets Redis remove a value when its deadline passes.")]
            : [VoiceTutorCaption(speaker: .learner, text: "Redis에서 캐시 만료는 어떻게 정하나요?"),
               VoiceTutorCaption(speaker: .tutor, text: "데이터가 얼마나 자주 바뀌는지에 맞춰 만료 시간을 정하면 돼요.")]
        let root = NavigationStack {
            VoiceTutorCallScreen(
                topic: fixture.topic,
                presentation: VoiceTutorCallPresentation(
                    phase: fixture.phase,
                    isRecording: fixture.isRecording,
                    pauseState: fixture.pauseState,
                    sessionSecondsRemaining: fixture.seconds,
                    quotaRemainingSeconds: fixture.phase.isLive ? 0 : 3_540,
                    quotaReservedSeconds: fixture.phase.isLive ? 3_600 : 0,
                    quotaLimitSeconds: 3_600,
                    detail: fixture.detail
                ),
                strings: strings,
                captions: fixture.showsTranscript ? captions : [],
                errorMessage: fixture.phase == .failed ? strings.voiceTutorConnectionFailed : nil,
                showsTranscript: .constant(fixture.showsTranscript),
                showsSummary: .constant(false),
                onMute: { XCTFail("A visual fixture must never change the microphone") },
                onEnd: { XCTFail("A visual fixture must never end a real call") },
                onRetry: { XCTFail("A visual fixture must never start a real call") },
                onDismiss: { XCTFail("A visual fixture must never dismiss the real call screen") }
            )
            .navigationTitle(strings.voiceTutorCallTitle)
            .navigationBarTitleDisplayMode(.inline)
        }
        .id(fixture.name)
        .environment(\.colorScheme, .dark)
        .environment(\.locale, Locale(identifier: fixture.language == .english ? "en_US" : "ko_KR"))
        .dynamicTypeSize(fixture.dynamicType)
        let controller = UIHostingController(rootView: root)
        controller.overrideUserInterfaceStyle = UIUserInterfaceStyle.dark
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        let previousKeyWindow = scene?.windows.first { $0.isKeyWindow }
        let window: UIWindow
        if let scene {
            window = UIWindow(windowScene: scene)
        } else {
            window = UIWindow(frame: CGRect(origin: .zero, size: fixture.size))
        }
        window.frame = CGRect(origin: .zero, size: fixture.size)
        window.overrideUserInterfaceStyle = .dark
        window.rootViewController = controller
        // Consecutive non-key captures on iOS 26 omitted unchanged title and
        // control layers. Give this synthetic screen a fully presented window and
        // capture that entire window, then restore the original key window even
        // if the test is cancelled. Never replace the real window's controller.
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKey()
        }
        window.makeKeyAndVisible()
        controller.loadViewIfNeeded()
        let bounds = CGRect(origin: .zero, size: fixture.size)
        window.frame = bounds
        controller.view.frame = bounds
        UIView.performWithoutAnimation {
            window.setNeedsLayout()
            window.layoutIfNeeded()
            controller.view.setNeedsLayout()
            controller.view.layoutIfNeeded()
        }
        CATransaction.flush()
        await Task.yield()
        try await Task.sleep(for: .milliseconds(250))

        // Invalidate every presented UIView, not only the latest SwiftUI state
        // delta. A second committed frame includes shared title/control layers.
        @MainActor func invalidateDisplay(_ view: UIView) {
            view.setNeedsDisplay()
            for subview in view.subviews { invalidateDisplay(subview) }
        }
        invalidateDisplay(window)
        window.layoutIfNeeded()
        CATransaction.flush()
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertTrue(window.isKeyWindow, "The synthetic snapshot window must be fully presented")
        XCTAssertEqual(window.bounds.size, fixture.size, fixture.name)
        XCTAssertEqual(controller.view.bounds.size, fixture.size, fixture.name)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 2
        format.opaque = true
        let image = UIGraphicsImageRenderer(size: fixture.size, format: format).image { context in
            window.layer.render(in: context.cgContext)
        }
        return image
    }

    private func makeRenderBuffer(
        _ samples: [Int16],
        channels: AVAudioChannelCount = 1,
        interleaved: Bool = true
    ) throws -> AVAudioPCMBuffer {
        let format = try XCTUnwrap(AVAudioFormat(
            commonFormat: .pcmFormatInt16, sampleRate: 48_000, channels: channels, interleaved: interleaved
        ))
        let frames = samples.count / Int(channels)
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames)))
        buffer.frameLength = AVAudioFrameCount(frames)
        let data = try XCTUnwrap(buffer.int16ChannelData)
        for frame in 0..<frames {
            for channel in 0..<Int(channels) {
                let sample = frame * Int(channels) + channel
                if interleaved { data[0][sample] = samples[sample] }
                else { data[channel][frame] = samples[sample] }
            }
        }
        return buffer
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

private final class VoiceTutorContractNativeAudioDelegate: NSObject, LKRTCAudioCustomProcessingDelegate {
    func audioProcessingInitialize(sampleRate: Int, channels: Int) {}
    func audioProcessingProcess(audioBuffer: LKRTCAudioBuffer) {}
    func audioProcessingRelease() {}
}

private struct VoiceTutorContractCaptureDiagnosticCounts: Sendable {
    var initializationEvents = 0
    var firstInputEvents = 0
    var activityEvents = 0
    var initialized: VoiceTutorLocalSpeechCaptureSnapshot?
    var firstInput: VoiceTutorLocalSpeechCaptureSnapshot?
}

private final class VoiceTutorContractCaptureDiagnostics: @unchecked Sendable {
    private let lock = NSLock()
    private var counts = VoiceTutorContractCaptureDiagnosticCounts()

    func observe(_ kind: VoiceTutorLocalSpeechCaptureDiagnostic, snapshot: VoiceTutorLocalSpeechCaptureSnapshot) {
        lock.lock()
        defer { lock.unlock() }
        switch kind {
        case .initialized:
            counts.initializationEvents += 1
            counts.initialized = snapshot
        case .firstValidInputFrame:
            counts.firstInputEvents += 1
            counts.firstInput = snapshot
        }
    }

    func observeActivity() {
        lock.lock()
        counts.activityEvents += 1
        lock.unlock()
    }

    func snapshot() -> VoiceTutorContractCaptureDiagnosticCounts {
        lock.lock()
        defer { lock.unlock() }
        return counts
    }
}

private struct VoiceTutorCompactCallSnapshot {
    var name: String
    var phase: VoiceTutorSessionPhase
    var seconds: Int? = 1_852
    var isRecording = false
    var pauseState = VoiceTutorCallPauseState()
    var detail: BackendVoiceTutorSessionDetail?
    var showsTranscript = false
    var language: AppLanguage = .korean
    var topic = "Redis"
    var size = CGSize(width: 402, height: 874)
    var dynamicType = DynamicTypeSize.large
}

private struct VoiceTutorContractRenderFixture {
    var response = VoiceTutorWebRTCResponseState()
    var phase = VoiceTutorSessionPhase.listening
    private let renderer = VoiceTutorRemoteAudioRenderer()
    private let callbacks = VoiceTutorContractRenderCounter()

    var callbackCount: Int { callbacks.count }

    init() {
        let callbacks = self.callbacks
        renderer.onRenderedPCM = { _ in callbacks.increment() }
    }

    mutating func render(_ buffer: AVAudioPCMBuffer) {
        let previous = callbacks.count
        renderer.render(pcmBuffer: buffer)
        if callbacks.count > previous {
            phase = phase.afterRenderedTutorAudio(assistantResponseActive: response.mayIndicateSpeaking)
        }
    }
}

private final class VoiceTutorContractRenderCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var value = 0

    var count: Int {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func increment() {
        lock.lock()
        value += 1
        lock.unlock()
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
