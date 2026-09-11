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

    @MainActor
    func testQuickCallEntryWaitsForFreshStatusAndStartsOnlyOncePerEntry() async throws {
        let status = try quickCallStatus()
        let admission = VoiceTutorCallEntryAdmission()
        let statusArrived = expectation(description: "The entry starts a fresh status read")
        let releaseStatus = VoiceTutorContractResponseGate()
        var finished = false
        let firstEntry = Task { @MainActor in
            let starts = await admission.refresh(
                startCallOnEntry: true,
                isCurrent: { true },
                loadStatus: {
                    statusArrived.fulfill()
                    await releaseStatus.wait()
                    return status
                }
            )
            finished = true
            return starts
        }
        defer {
            firstEntry.cancel()
            releaseStatus.open()
        }
        let arrived = await XCTWaiter.fulfillment(of: [statusArrived], timeout: 5)
        XCTAssertEqual(arrived, .completed)
        XCTAssertFalse(finished, "Cached eligibility cannot open the call while the fresh read is pending.")
        releaseStatus.open()
        let starts = await firstEntry.value
        XCTAssertTrue(starts)

        let returnFromCall = await admission.refresh(
            startCallOnEntry: true, isCurrent: { true }, loadStatus: { status }
        )
        XCTAssertFalse(returnFromCall, "Returning from a completed call must not start another call.")
        let newShortcutTap = await VoiceTutorCallEntryAdmission().refresh(
            startCallOnEntry: true, isCurrent: { true }, loadStatus: { status }
        )
        XCTAssertTrue(newShortcutTap, "A new navigation entry carries a new explicit user intent.")
    }

    @MainActor
    func testQuickCallEntryPreservesServerAdmissionAndExplicitEntryRequirements() async throws {
        let allowed = try quickCallStatus()
        let rejectedStatuses: [BackendVoiceTutorStatus?] = [
            nil,
            try quickCallStatus(eligible: false),
            try quickCallStatus(remaining: 0),
            try quickCallStatus(active: true)
        ]
        for rejected in rejectedStatuses {
            let admission = VoiceTutorCallEntryAdmission()
            let starts = await admission.refresh(
                startCallOnEntry: true, isCurrent: { true }, loadStatus: { rejected }
            )
            XCTAssertFalse(starts)
            let laterRefresh = await admission.refresh(
                startCallOnEntry: true, isCurrent: { true }, loadStatus: { allowed }
            )
            XCTAssertFalse(laterRefresh, "An initially blocked entry needs another explicit call action.")
        }

        let ordinaryEntry = await VoiceTutorCallEntryAdmission().refresh(
            startCallOnEntry: false, isCurrent: { true }, loadStatus: { allowed }
        )
        XCTAssertFalse(ordinaryEntry, "Opening the ordinary history/consent entry must not place a call.")
        let replacedAccount = await VoiceTutorCallEntryAdmission().refresh(
            startCallOnEntry: true, isCurrent: { false }, loadStatus: { allowed }
        )
        XCTAssertFalse(replacedAccount)
    }

    @MainActor
    func testQuickCallEntryCancellationConsumesTheOriginalStartIntent() async throws {
        let status = try quickCallStatus()
        let admission = VoiceTutorCallEntryAdmission()
        let statusArrived = expectation(description: "Status is pending before navigation cancellation")
        let releaseStatus = VoiceTutorContractResponseGate()
        let opening = Task { @MainActor in
            await admission.refresh(
                startCallOnEntry: true,
                isCurrent: { true },
                loadStatus: {
                    statusArrived.fulfill()
                    await releaseStatus.wait()
                    return status
                }
            )
        }
        defer {
            opening.cancel()
            releaseStatus.open()
        }
        let arrived = await XCTWaiter.fulfillment(of: [statusArrived], timeout: 5)
        XCTAssertEqual(arrived, .completed)
        opening.cancel()
        releaseStatus.open()
        let cancelledStart = await opening.value
        XCTAssertFalse(cancelledStart)
        let restartedPresentationTask = await admission.refresh(
            startCallOnEntry: true, isCurrent: { true }, loadStatus: { status }
        )
        XCTAssertFalse(restartedPresentationTask)
    }

    @MainActor
    func testQuickCallEntryCannotUseCachedEligibilityAfterStatusRefreshFails() async throws {
        let registration = try VoiceTutorContractAppFixture.registration(ownerUserID: 7, tokenID: "quick-call")
        var failStatus = false
        var paths: [String] = []
        let fixture = try VoiceTutorContractAppFixture(registration: registration) { request in
            paths.append(request.url?.path ?? "")
            XCTAssertEqual(request.url?.path, "/api/v1/voice-tutor/status")
            if failStatus { throw URLError(.notConnectedToInternet) }
            return VoiceTutorContractAppFixture.response(
                for: request,
                body: #"{"eligible":true,"quota":{"limitSeconds":3600,"usedSeconds":0,"reservedSeconds":0,"remainingSeconds":3600}}"#
            )
        }
        defer { fixture.close() }
        let fresh = await fixture.appState.refreshVoiceTutorStatus()
        XCTAssertTrue(VoiceTutorCallStartPolicy.canStart(status: fresh))
        failStatus = true

        let admission = VoiceTutorCallEntryAdmission()
        let starts = await admission.refresh(
            startCallOnEntry: true,
            isCurrent: { fixture.appState.isCommunitySessionActive },
            loadStatus: { await fixture.appState.refreshVoiceTutorStatus() }
        )
        XCTAssertFalse(starts)
        XCTAssertEqual(fixture.appState.voiceTutorStatus, fresh, "The existing usage display may keep its cache.")
        XCTAssertNotNil(fixture.appState.voiceTutorErrorMessage)
        failStatus = false
        let retryStarts = await admission.refresh(
            startCallOnEntry: true,
            isCurrent: { true },
            loadStatus: { await fixture.appState.refreshVoiceTutorStatus() }
        )
        XCTAssertFalse(retryStarts)
        XCTAssertTrue(paths.allSatisfy { $0 == "/api/v1/voice-tutor/status" },
            "Admission must not fetch history, retry recording uploads, or create a session on failure.")
    }

    @MainActor
    func testQuickCallColdEntryResolvesProfileThenFreshStatusWithoutLoadingOtherDestinations() async throws {
        let registration = try VoiceTutorContractAppFixture.registration(ownerUserID: 7, tokenID: "cold-entry")
        let profileArrived = expectation(description: "Cold entry resolves the restored account")
        let statusArrived = expectation(description: "Admission follows the verified profile")
        let releaseProfile = VoiceTutorContractResponseGate()
        let releaseStatus = VoiceTutorContractResponseGate()
        var paths: [String] = []
        let fixture = try VoiceTutorContractAppFixture(registration: registration, profileOwnerUserID: nil) { request in
            let path = request.url?.path ?? ""
            paths.append(path)
            if path == "/api/v1/profile" {
                profileArrived.fulfill()
                await releaseProfile.wait()
            } else if path == "/api/v1/voice-tutor/status" {
                statusArrived.fulfill()
                await releaseStatus.wait()
            }
            return try VoiceTutorContractAppFixture.quickCallEntryResponse(for: request)
        }
        defer {
            releaseProfile.open()
            releaseStatus.open()
            fixture.close()
        }
        XCTAssertTrue(fixture.appState.isCommunitySessionActive)
        XCTAssertNil(fixture.appState.communityProfile)
        let initialIdentity = fixture.appState.commonRecordsIdentity
        XCTAssertFalse(fixture.appState.isVoiceTutorEntryIdentityCurrent(initialIdentity))
        var finished = false
        let entry = Task { @MainActor in
            let starts = await VoiceTutorCallEntryAdmission().refresh(
                startCallOnEntry: true,
                isCurrent: { fixture.appState.isVoiceTutorEntryIdentityCurrent(initialIdentity) },
                loadStatus: { await fixture.appState.prepareVoiceTutorStatusForEntry() }
            )
            finished = true
            return starts
        }
        defer { entry.cancel() }
        let profileWait = await XCTWaiter.fulfillment(of: [profileArrived], timeout: 5)
        XCTAssertEqual(profileWait, .completed)
        XCTAssertEqual(paths, ["/api/v1/profile"])
        XCTAssertFalse(finished)
        releaseProfile.open()
        let statusWait = await XCTWaiter.fulfillment(of: [statusArrived], timeout: 5)
        XCTAssertEqual(statusWait, .completed)
        XCTAssertEqual(fixture.appState.communityProfile?.id, 7)
        XCTAssertTrue(fixture.appState.isVoiceTutorEntryIdentityCurrent(initialIdentity),
            "Resolving a previously unknown owner in the same session must preserve the explicit entry intent.")
        XCTAssertFalse(finished, "Profile readiness alone cannot authorize a call.")
        releaseStatus.open()
        let starts = await entry.value
        XCTAssertTrue(starts)
        XCTAssertTrue(VoiceTutorCallStartPolicy.canStart(status: fixture.appState.voiceTutorStatus))
        XCTAssertEqual(paths, ["/api/v1/profile", "/api/v1/voice-tutor/status"],
            "Home call admission must not load billing, catalogs, history, recordings, or create a session.")
    }

    @MainActor
    func testQuickCallColdEntryProfileFailureCannotAdmitAndNewEntryCanRetry() async throws {
        let registration = try VoiceTutorContractAppFixture.registration(ownerUserID: 7, tokenID: "cold-failure")
        var failProfile = true
        var paths: [String] = []
        let fixture = try VoiceTutorContractAppFixture(registration: registration, profileOwnerUserID: nil) { request in
            paths.append(request.url?.path ?? "")
            if failProfile {
                XCTAssertEqual(request.url?.path, "/api/v1/profile")
                throw URLError(.notConnectedToInternet)
            }
            return try VoiceTutorContractAppFixture.quickCallEntryResponse(for: request)
        }
        defer { fixture.close() }
        let initialIdentity = fixture.appState.commonRecordsIdentity
        let admission = VoiceTutorCallEntryAdmission()
        let failedEntry = await admission.refresh(
            startCallOnEntry: true,
            isCurrent: { fixture.appState.isVoiceTutorEntryIdentityCurrent(initialIdentity) },
            loadStatus: { await fixture.appState.prepareVoiceTutorStatusForEntry() }
        )
        XCTAssertFalse(failedEntry)
        XCTAssertNil(fixture.appState.communityProfile)
        XCTAssertNil(fixture.appState.voiceTutorStatus)
        XCTAssertEqual(paths, ["/api/v1/profile"])

        failProfile = false
        let refreshedEntry = await admission.refresh(
            startCallOnEntry: true,
            isCurrent: { fixture.appState.isVoiceTutorEntryIdentityCurrent(initialIdentity) },
            loadStatus: { await fixture.appState.prepareVoiceTutorStatusForEntry() }
        )
        XCTAssertFalse(refreshedEntry, "A retry can restore the page without reviving consumed automatic call intent.")
        XCTAssertTrue(VoiceTutorCallStartPolicy.canStart(status: fixture.appState.voiceTutorStatus))
        let newExplicitEntry = await VoiceTutorCallEntryAdmission().refresh(
            startCallOnEntry: true,
            isCurrent: { fixture.appState.isVoiceTutorEntryIdentityCurrent(initialIdentity) },
            loadStatus: { await fixture.appState.prepareVoiceTutorStatusForEntry() }
        )
        XCTAssertTrue(newExplicitEntry)
        XCTAssertEqual(paths, ["/api/v1/profile", "/api/v1/profile", "/api/v1/voice-tutor/status", "/api/v1/voice-tutor/status"])
    }

    @MainActor
    func testQuickCallColdEntryCancellationDuringProfileDoesNotFetchStatusOrAdmit() async throws {
        let registration = try VoiceTutorContractAppFixture.registration(ownerUserID: 7, tokenID: "cold-cancel")
        let profileArrived = expectation(description: "Profile is pending before entry cancellation")
        let releaseProfile = VoiceTutorContractResponseGate()
        var paths: [String] = []
        let fixture = try VoiceTutorContractAppFixture(registration: registration, profileOwnerUserID: nil) { request in
            paths.append(request.url?.path ?? "")
            XCTAssertEqual(request.url?.path, "/api/v1/profile")
            profileArrived.fulfill()
            await releaseProfile.wait()
            return try VoiceTutorContractAppFixture.quickCallEntryResponse(for: request)
        }
        defer {
            releaseProfile.open()
            fixture.close()
        }
        let initialIdentity = fixture.appState.commonRecordsIdentity
        let entry = Task { @MainActor in
            await VoiceTutorCallEntryAdmission().refresh(
                startCallOnEntry: true,
                isCurrent: { fixture.appState.isVoiceTutorEntryIdentityCurrent(initialIdentity) },
                loadStatus: { await fixture.appState.prepareVoiceTutorStatusForEntry() }
            )
        }
        defer { entry.cancel() }
        let profileWait = await XCTWaiter.fulfillment(of: [profileArrived], timeout: 5)
        XCTAssertEqual(profileWait, .completed)
        entry.cancel()
        releaseProfile.open()
        let starts = await entry.value
        XCTAssertFalse(starts)
        XCTAssertEqual(paths, ["/api/v1/profile"])
        XCTAssertNil(fixture.appState.voiceTutorStatus)
    }

    @MainActor
    func testQuickCallColdEntryAccountChangeDuringProfileOrStatusCannotAdmit() async throws {
        for pendingPath in ["/api/v1/profile", "/api/v1/voice-tutor/status"] {
            let registrationA = try VoiceTutorContractAppFixture.registration(ownerUserID: 7, tokenID: "cold-account-a")
            let registrationB = try VoiceTutorContractAppFixture.registration(ownerUserID: 8, tokenID: "cold-account-b")
            let requestArrived = expectation(description: "Account A is waiting for \(pendingPath)")
            let releaseResponse = VoiceTutorContractResponseGate()
            var paths: [String] = []
            let fixture = try VoiceTutorContractAppFixture(registration: registrationA, profileOwnerUserID: nil) { request in
                paths.append(request.url?.path ?? "")
                XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer \(registrationA.accessToken!)")
                if request.url?.path == pendingPath {
                    requestArrived.fulfill()
                    await releaseResponse.wait()
                }
                return try VoiceTutorContractAppFixture.quickCallEntryResponse(for: request)
            }
            defer {
                releaseResponse.open()
                fixture.close()
            }
            let initialIdentity = fixture.appState.commonRecordsIdentity
            let entry = Task { @MainActor in
                await VoiceTutorCallEntryAdmission().refresh(
                    startCallOnEntry: true,
                    isCurrent: { fixture.appState.isVoiceTutorEntryIdentityCurrent(initialIdentity) },
                    loadStatus: { await fixture.appState.prepareVoiceTutorStatusForEntry() }
                )
            }
            defer { entry.cancel() }
            let requestWait = await XCTWaiter.fulfillment(of: [requestArrived], timeout: 5)
            XCTAssertEqual(requestWait, .completed)
            fixture.replaceAccount(ownerUserID: 8, registration: registrationB)
            releaseResponse.open()
            let starts = await entry.value
            XCTAssertFalse(starts, "A resolved profile cannot transfer account A's pending call intent to account B.")
            XCTAssertEqual(fixture.appState.communityProfile?.id, 8)
            XCTAssertEqual(fixture.store.loadRemotePushRegistration(), registrationB)
            XCTAssertNil(fixture.appState.voiceTutorStatus)
            XCTAssertEqual(paths, pendingPath == "/api/v1/profile"
                ? ["/api/v1/profile"] : ["/api/v1/profile", "/api/v1/voice-tutor/status"])
        }
    }

    @MainActor
    func testQuickCallColdEntryLanguageChangeDuringProfileCannotAdmit() async throws {
        let registration = try VoiceTutorContractAppFixture.registration(ownerUserID: 7, tokenID: "cold-language")
        let profileArrived = expectation(description: "Profile is pending in the original language")
        let releaseProfile = VoiceTutorContractResponseGate()
        var paths: [String] = []
        let fixture = try VoiceTutorContractAppFixture(registration: registration, profileOwnerUserID: nil) { request in
            paths.append(request.url?.path ?? "")
            XCTAssertEqual(request.url?.path, "/api/v1/profile")
            profileArrived.fulfill()
            await releaseProfile.wait()
            return try VoiceTutorContractAppFixture.quickCallEntryResponse(for: request)
        }
        defer {
            releaseProfile.open()
            fixture.close()
        }
        let initialIdentity = fixture.appState.commonRecordsIdentity
        let entry = Task { @MainActor in
            await VoiceTutorCallEntryAdmission().refresh(
                startCallOnEntry: true,
                isCurrent: { fixture.appState.isVoiceTutorEntryIdentityCurrent(initialIdentity) },
                loadStatus: { await fixture.appState.prepareVoiceTutorStatusForEntry() }
            )
        }
        defer { entry.cancel() }
        let profileWait = await XCTWaiter.fulfillment(of: [profileArrived], timeout: 5)
        XCTAssertEqual(profileWait, .completed)
        fixture.appState.updateAppLanguage(fixture.appState.settings.appLanguage == .english ? .korean : .english)
        releaseProfile.open()
        let starts = await entry.value
        XCTAssertFalse(starts)
        XCTAssertNil(fixture.appState.communityProfile)
        XCTAssertNil(fixture.appState.voiceTutorStatus)
        XCTAssertEqual(paths, ["/api/v1/profile"])
        XCTAssertFalse(fixture.appState.isVoiceTutorEntryIdentityCurrent(initialIdentity))
    }

    @MainActor
    func testQuickCallEntryUnknownOwnerCannotBypassSessionOrBackendGeneration() throws {
        let registration = try VoiceTutorContractAppFixture.registration(ownerUserID: 7, tokenID: "entry-generation")
        let fixture = try VoiceTutorContractAppFixture(registration: registration) { request in
            XCTFail("Identity validation must not make a request: \(request.url?.path ?? "")")
            throw URLError(.badServerResponse)
        }
        defer { fixture.close() }
        var unresolvedIdentity = fixture.appState.commonRecordsIdentity
        unresolvedIdentity.userID = nil
        XCTAssertTrue(fixture.appState.isVoiceTutorEntryIdentityCurrent(unresolvedIdentity))

        var replacedSession = unresolvedIdentity
        replacedSession.sessionGeneration &+= 1
        XCTAssertFalse(fixture.appState.isVoiceTutorEntryIdentityCurrent(replacedSession))
        var replacedBackend = unresolvedIdentity
        replacedBackend.backendGeneration += 1
        XCTAssertFalse(fixture.appState.isVoiceTutorEntryIdentityCurrent(replacedBackend))
        var differentKnownOwner = unresolvedIdentity
        differentKnownOwner.userID = 8
        XCTAssertFalse(fixture.appState.isVoiceTutorEntryIdentityCurrent(differentKnownOwner))
    }

    private func quickCallStatus(
        eligible: Bool = true,
        remaining: Int = 3_600,
        active: Bool = false
    ) throws -> BackendVoiceTutorStatus {
        var json: [String: Any] = [
            "eligible": eligible,
            "quota": ["limitSeconds": 3_600, "usedSeconds": 0,
                      "reservedSeconds": 0, "remainingSeconds": remaining]
        ]
        if active {
            json["activeSession"] = ["sessionId": "quick-call-active", "state": "ACTIVE"]
        }
        return try decoder.decode(BackendVoiceTutorStatus.self,
            from: JSONSerialization.data(withJSONObject: json))
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
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Voice-Turn-Protocol"), "realtime-native-v1")
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

    func testSDPFailureDecodesOnlyBoundedStructuredBackendFields() throws {
        let failure = try XCTUnwrap(
            VoiceTutorWebRTCBackendFailure.decode(
                from: Data(
                    #"{"error":{"errorCode":"VOICE_TUTOR_PROVIDER_UNAVAILABLE","code":50302,"message":"Voice Tutor provider is temporarily unavailable.","requestId":"private-request"}}"#.utf8
                )
            )
        )

        XCTAssertEqual(failure.code, "VOICE_TUTOR_PROVIDER_UNAVAILABLE")
        XCTAssertEqual(failure.message, "Voice Tutor provider is temporarily unavailable.")
        XCTAssertNil(
            VoiceTutorWebRTCBackendFailure.decode(
                from: Data(#"{"error":{"code":"unsafe code","message":"never trusted"}}"#.utf8)
            )?.code
        )
        XCTAssertNil(
            VoiceTutorWebRTCBackendFailure.decode(
                from: Data(repeating: 0x41, count: 16_385)
            )
        )
    }

    func testSDPProviderStartupFailureUsesLocalizedServiceUnavailablePresentation() {
        let backendFailure = VoiceTutorWebRTCBackendFailure(
            code: "VOICE_TUTOR_PROVIDER_UNAVAILABLE",
            message: "This raw server text must not be shown."
        )

        for language in [AppLanguage.korean, .english, .japanese] {
            let strings = AppStrings(language: language)
            let coded = VoiceTutorStartupFailurePolicy.presentation(
                for: VoiceTutorWebRTCError.sdpExchangeFailed(
                    statusCode: 502,
                    backendFailure: backendFailure
                ),
                strings: strings
            )
            XCTAssertEqual(coded.cause, .providerUnavailable)
            XCTAssertEqual(coded.message, strings.serviceTemporarilyUnavailable)
            XCTAssertNotEqual(coded.message, backendFailure.message)

            let statusOnly = VoiceTutorStartupFailurePolicy.presentation(
                for: VoiceTutorWebRTCError.sdpExchangeFailed(
                    statusCode: 503,
                    backendFailure: nil
                ),
                strings: strings
            )
            XCTAssertEqual(statusOnly.cause, .providerUnavailable)
            XCTAssertEqual(statusOnly.message, strings.serviceTemporarilyUnavailable)
        }
    }

    func testPermanentSDPStartupFailuresDoNotOfferAnEndlessRetry() {
        let strings = AppStrings(language: .korean)
        let rejected = VoiceTutorStartupFailurePolicy.presentation(
            for: VoiceTutorWebRTCError.sdpExchangeFailed(
                statusCode: 422,
                backendFailure: VoiceTutorWebRTCBackendFailure(
                    code: "VALIDATION_ERROR",
                    message: "Invalid request."
                )
            ),
            strings: strings
        )
        XCTAssertEqual(rejected.cause, .requestRejected)
        XCTAssertEqual(rejected.message, strings.voiceTutorRequestRejected)
        XCTAssertEqual(
            VoiceTutorCallPresentation(phase: .failed, failureCause: rejected.cause).primaryAction,
            .dismiss
        )

        let update = VoiceTutorStartupFailurePolicy.presentation(
            for: VoiceTutorWebRTCError.sdpExchangeFailed(statusCode: 426, backendFailure: nil),
            strings: strings
        )
        XCTAssertEqual(update.cause, .updateRequired)
        XCTAssertEqual(update.message, strings.voiceTutorUpdateRequiredMessage)
        XCTAssertEqual(
            VoiceTutorCallPresentation(phase: .failed, failureCause: update.cause).primaryAction,
            .dismiss
        )
    }

    func testProviderCreditExhaustionKeepsItsOwnLocalizedFailureAndDoesNotOfferImmediateRetry() throws {
        let rawMessage = "private provider billing detail"
        let data = Data(#"{"error":{"errorCode":"VOICE_TUTOR_PROVIDER_QUOTA_EXHAUSTED","code":516,"message":"private provider billing detail"}}"#.utf8)
        let failure = try XCTUnwrap(VoiceTutorWebRTCBackendFailure.decode(from: data))
        for language in [AppLanguage.korean, .english, .japanese] {
            let strings = AppStrings(language: language)
            let mapped = VoiceTutorStartupFailurePolicy.presentation(
                for: VoiceTutorWebRTCError.sdpExchangeFailed(statusCode: 503, backendFailure: failure),
                strings: strings)
            XCTAssertEqual(mapped.cause, .providerQuotaUnavailable)
            XCTAssertEqual(mapped.message, strings.voiceTutorProviderQuotaUnavailableMessage)
            XCTAssertFalse(mapped.message.contains(rawMessage))
            let screen = VoiceTutorCallPresentation(phase: .failed, failureCause: mapped.cause)
            XCTAssertEqual(screen.statusText(strings), strings.voiceTutorProviderQuotaUnavailableTitle)
            XCTAssertEqual(screen.supplementaryError(strings, errorMessage: mapped.message), mapped.message)
            XCTAssertEqual(screen.primaryAction, .dismiss)
            XCTAssertFalse(screen.isMonthlyQuotaExhausted)
            XCTAssertFalse(screen.showsConnectionFailure(strings, errorMessage: mapped.message))

            // A generic 503, an unknown code, or message text cannot assert
            // provider credit exhaustion or remove ordinary connection retry.
            for other in [nil, VoiceTutorWebRTCBackendFailure(code: "UNRECOGNIZED", message: rawMessage),
                          VoiceTutorWebRTCBackendFailure(code: nil, message: "credit_balance_exhausted")] {
                let transient = VoiceTutorStartupFailurePolicy.presentation(
                    for: VoiceTutorWebRTCError.sdpExchangeFailed(statusCode: 503, backendFailure: other), strings: strings)
                XCTAssertEqual(transient.cause, .providerUnavailable)
                XCTAssertEqual(VoiceTutorCallPresentation(phase: .failed, failureCause: transient.cause).primaryAction, .retry)
            }
        }
    }

    func testSDPDiagnosticsKeepSafeStatusAndKnownCodeWithoutProviderBodyOrUnknownIdentifiers() {
        let known = VoiceTutorDiagnosticError.fields(for: VoiceTutorWebRTCError.sdpExchangeFailed(
            statusCode: 503, backendFailure: .init(code: "VOICE_TUTOR_PROVIDER_QUOTA_EXHAUSTED",
                message: "private provider body https://private.test/token")))
        XCTAssertTrue(known.contains("httpStatus=503"))
        XCTAssertTrue(known.contains("backendCode=VOICE_TUTOR_PROVIDER_QUOTA_EXHAUSTED"))
        XCTAssertFalse(known.contains("private"))
        let unknown = VoiceTutorDiagnosticError.fields(for: VoiceTutorWebRTCError.sdpExchangeFailed(
            statusCode: 700, backendFailure: .init(code: "PRIVATE_ACCOUNT_IDENTIFIER", message: "private")))
        XCTAssertTrue(unknown.contains("httpStatus=0"))
        XCTAssertTrue(unknown.contains("backendCode=unknown"))
        XCTAssertFalse(unknown.contains("PRIVATE_ACCOUNT_IDENTIFIER"))
        XCTAssertFalse(unknown.contains("private"))
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

    func testMonthlyQuotaEndReasonIsAnExactGracefulSpokenTerminalContract() {
        for reason in ["QUOTA_EXHAUSTED", " quota_exhausted \n"] {
            XCTAssertTrue(VoiceTutorServerEndReasonPolicy.isMonthlyQuotaExhausted(reason))
            XCTAssertTrue(VoiceTutorServerEndReasonPolicy.isGracefulServerCompletion(reason))
            XCTAssertTrue(VoiceTutorServerEndReasonPolicy.preservesFinalSpokenPlayout(reason))
            XCTAssertEqual(
                VoiceTutorSessionPhase.completed(
                    outcome: .ended,
                    serverState: nil,
                    serverReason: reason
                ),
                .ended
            )
        }

        for unrelated in ["TIME_LIMIT", "VOICE_TUTOR_QUOTA_EXCEEDED", "MONTHLY_QUOTA_EXHAUSTED", nil] {
            XCTAssertFalse(VoiceTutorServerEndReasonPolicy.isMonthlyQuotaExhausted(unrelated))
        }
        XCTAssertFalse(VoiceTutorServerEndReasonPolicy.preservesFinalSpokenPlayout("TIME_LIMIT"))
        XCTAssertFalse(VoiceTutorServerEndReasonPolicy.isGracefulServerCompletion("PROVIDER_ERROR"))
    }

    func testInputRetryIsRejectedOnceMonthlyQuotaTerminalOwnsSession() {
        for reason in ["QUOTA_EXHAUSTED", " quota_exhausted \n"] {
            XCTAssertFalse(
                VoiceTutorServerEndReasonPolicy.permitsInputRetry(
                    reason: reason,
                    isFinalizing: false
                )
            )
        }
    }

    func testInputRetryIsRejectedDuringFinalizationButAllowedForLiveNonQuotaSession() {
        XCTAssertFalse(
            VoiceTutorServerEndReasonPolicy.permitsInputRetry(
                reason: nil,
                isFinalizing: true
            )
        )
        XCTAssertFalse(
            VoiceTutorServerEndReasonPolicy.permitsInputRetry(
                reason: "SERVER_FINALIZED",
                isFinalizing: true
            )
        )

        for reason in [nil, "TIME_LIMIT", "MONTHLY_QUOTA_EXHAUSTED", "VOICE_TUTOR_QUOTA_EXCEEDED"] {
            XCTAssertTrue(
                VoiceTutorServerEndReasonPolicy.permitsInputRetry(
                    reason: reason,
                    isFinalizing: false
                )
            )
        }
    }

    func testMonthlyQuotaControlLossKeepsMediaForOnlyTheBoundedNoticeWindow() {
        let now = Date(timeIntervalSince1970: 10_000)
        XCTAssertEqual(
            VoiceTutorQuotaControlLossPolicy.mediaHoldSeconds(
                hardEndsAt: now.addingTimeInterval(8),
                now: now,
                hasSealedResponse: false
            ),
            28
        )
        XCTAssertEqual(
            VoiceTutorQuotaControlLossPolicy.mediaHoldSeconds(
                hardEndsAt: now.addingTimeInterval(-5),
                now: now,
                hasSealedResponse: false
            ),
            15
        )
        XCTAssertEqual(
            VoiceTutorQuotaControlLossPolicy.mediaHoldSeconds(
                hardEndsAt: now.addingTimeInterval(-21),
                now: now,
                hasSealedResponse: false
            ),
            0
        )
        XCTAssertEqual(
            VoiceTutorQuotaControlLossPolicy.mediaHoldSeconds(
                hardEndsAt: now.addingTimeInterval(300),
                now: now,
                hasSealedResponse: false
            ),
            VoiceTutorQuotaControlLossPolicy.maximumMediaHoldSeconds
        )
        XCTAssertEqual(
            VoiceTutorQuotaControlLossPolicy.mediaHoldSeconds(
                hardEndsAt: now.addingTimeInterval(8),
                now: now,
                hasSealedResponse: true
            ),
            0
        )
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
            .responseStarted(
                responseID: "response-1",
                isTutorIntervention: true,
                isQuotaExhaustionNotice: false
            )
        )

        let quotaNotice = try VoiceTutorRealtimeEventParser.parse(
            text: #"{"type":"response.created","response":{"id":"quota-response"},"buddystudyQuotaExhaustionNotice":true}"#
        )
        XCTAssertEqual(
            quotaNotice,
            .responseStarted(
                responseID: "quota-response",
                isTutorIntervention: false,
                isQuotaExhaustionNotice: true
            )
        )
        XCTAssertEqual(
            try VoiceTutorRealtimeEventParser.parse(
                text: #"{"type":"response.created","response":{"id":"not-a-marker"},"buddystudyQuotaExhaustionNotice":1}"#
            ),
            .responseStarted(
                responseID: "not-a-marker",
                isTutorIntervention: false,
                isQuotaExhaustionNotice: false
            )
        )
    }

    func testInputAssessmentRetryIsASeparateNonterminalEvent() throws {
        let event = try VoiceTutorRealtimeEventParser.parse(
            text: #"{"type":"buddystudy.voice.input.retry","reason":"ignored","message":"not UI content"}"#
        )
        XCTAssertEqual(event, .inputRetry)
        XCTAssertNotEqual(event, .serviceError(code: nil, message: "", retryable: true))
    }

    func testSilentInputSettlementRequiresAnExactNonnegativeAcousticSequence() throws {
        for sequence in [0, 1, 123] {
            XCTAssertEqual(
                try VoiceTutorRealtimeEventParser.parse(
                    text: #"{"type":"buddystudy.voice.input.settled","sequence":\#(sequence)}"#
                ),
                .inputSettled(sequence: sequence)
            )
        }
        for fields in ["", #", "sequence": -1"#, #", "sequence": 1.5"#,
                       #", "sequence": true"#, #", "sequence": "1""#,
                       #", "sequence": null"#, #", "sequence": 9223372036854775808"#] {
            XCTAssertEqual(
                try VoiceTutorRealtimeEventParser.parse(
                    text: #"{"type":"buddystudy.voice.input.settled"\#(fields)}"#
                ),
                .ignored(type: "buddystudy.voice.input.settled")
            )
        }
    }

    func testScopedInputRetryRequiresExactSequenceAndSafeOptionalResponseID() throws {
        for sequence in [0, 1, 123] {
            XCTAssertEqual(try VoiceTutorRealtimeEventParser.parse(
                text: #"{"type":"buddystudy.voice.input.retry","sequence":\#(sequence),"abandonedResponseId":"resp_failed-1"}"#
            ), .inputRetryScoped(sequence: sequence, abandonedResponseID: "resp_failed-1"))
            XCTAssertEqual(try VoiceTutorRealtimeEventParser.parse(
                text: #"{"type":"buddystudy.voice.input.retry","sequence":\#(sequence)}"#
            ), .inputRetryScoped(sequence: sequence, abandonedResponseID: nil))
        }
        for value in ["-1", "1.5", "true", #""1""#, "null", "9223372036854775808"] {
            XCTAssertEqual(try VoiceTutorRealtimeEventParser.parse(
                text: #"{"type":"buddystudy.voice.input.retry","sequence":\#(value),"abandonedResponseId":"resp_failed"}"#
            ), .ignored(type: "buddystudy.voice.input.retry"))
        }
        for value in [#""""#, #""unsafe response""#, #""unsafe/path""#, "null", "12", "true"] {
            XCTAssertEqual(try VoiceTutorRealtimeEventParser.parse(
                text: #"{"type":"buddystudy.voice.input.retry","sequence":7,"abandonedResponseId":\#(value)}"#
            ), .ignored(type: "buddystudy.voice.input.retry"))
        }
    }

    func testProviderTurnAbandonmentCarriesOnlyTheExactBoundedResponseID() throws {
        let abandoned = try VoiceTutorRealtimeEventParser.parse(
            text: #"{"type":"buddystudy.voice.input.retry","abandonedResponseId":"resp_1-a"}"#
        )
        XCTAssertEqual(abandoned, .providerTurnAbandoned(responseID: "resp_1-a"))

        let malformed = try VoiceTutorRealtimeEventParser.parse(
            text: #"{"type":"buddystudy.voice.input.retry","abandonedResponseId":"response id with spaces"}"#
        )
        XCTAssertEqual(malformed, .inputRetry, "Malformed metadata must not clear any active tutor response")

        let oversizedID = String(repeating: "r", count: 192)
        let oversized = try VoiceTutorRealtimeEventParser.parse(
            text: #"{"type":"buddystudy.voice.input.retry","abandonedResponseId":"\#(oversizedID)"}"#
        )
        XCTAssertEqual(oversized, .inputRetry)
    }

    func testInputRetryKeepsCompactCallLiveAndDoesNotOverrideTutorSpeech() {
        let strings = AppStrings(language: .korean)
        var call = VoiceTutorCallPresentation(
            phase: .listening,
            inputNeedsRepeat: true,
            sessionSecondsRemaining: 120
        )
        XCTAssertEqual(call.statusText(strings), strings.voiceTutorInputRepeat)
        XCTAssertEqual(call.primaryAction, .end)
        XCTAssertEqual(call.remainingTime, .call(120))
        XCTAssertFalse(call.showsConnectionFailure(strings, errorMessage: nil))
        call.phase = .speaking
        XCTAssertEqual(call.statusText(strings), strings.voiceTutorCallSpeaking)
        call.phase = .listening
        call.inputNeedsRepeat = false
        XCTAssertEqual(call.statusText(strings), strings.voiceTutorCallListening)
        XCTAssertEqual(AppStrings(language: .english).voiceTutorInputRepeat, "Please say that again")
    }

    func testLearnerOverlapImmediatelyInterruptsAndFencesCurrentTutorAnswer() {
        var state = VoiceTutorDuplexPlaybackState()

        state.responseStarted(responseID: "response-1", isTutorIntervention: false)
        XCTAssertTrue(state.assistantAudioBegan(responseID: "response-1"))
        XCTAssertEqual(state.userSpeechStarted(), "response-1")

        XCTAssertTrue(state.isUserSpeaking)
        XCTAssertFalse(state.assistantResponseActive)
        XCTAssertFalse(state.tutorInterventionActive)
        XCTAssertNil(state.activeResponseID)
        XCTAssertFalse(state.assistantAudioBegan(responseID: "response-1"))
        XCTAssertFalse(state.responseStarted(responseID: "response-1", isTutorIntervention: false))
        XCTAssertFalse(state.responseFinished(responseID: "response-1"))

        state.userSpeechStopped()
        XCTAssertFalse(state.assistantResponseActive)
        XCTAssertTrue(state.responseStarted(responseID: "response-2", isTutorIntervention: false))
        XCTAssertFalse(state.responseFinished(responseID: "response-1"))
        XCTAssertTrue(state.matchesActiveResponse(responseID: "response-2"))
        XCTAssertTrue(state.responseFinished(responseID: "response-2"))
        XCTAssertFalse(state.responseFinished(responseID: "response-2"))
    }

    func testInterruptionBeforeResponseCreatedRejectsDelayedOutputWithoutAffectingReplacement() {
        var state = VoiceTutorDuplexPlaybackState()
        state.interruptResponse(responseID: "response-delayed")
        XCTAssertFalse(state.responseStarted(responseID: "response-delayed", isTutorIntervention: false))
        XCTAssertFalse(state.matchesActiveResponse(responseID: "response-delayed"))
        XCTAssertTrue(state.responseStarted(responseID: "response-new", isTutorIntervention: false))
        state.interruptResponse(responseID: "response-delayed")
        XCTAssertTrue(state.matchesActiveResponse(responseID: "response-new"))
        XCTAssertFalse(state.responseFinished(responseID: "response-delayed"))
    }

    func testLegacyPCMOverlapRetainsItsExistingServerFloorPolicy() {
        var state = VoiceTutorDuplexPlaybackState()
        state.responseStarted(responseID: "response-pcm", isTutorIntervention: false)
        XCTAssertNil(state.userSpeechStarted(interruptsTutor: false))
        XCTAssertTrue(state.isUserSpeaking)
        XCTAssertTrue(state.matchesActiveResponse(responseID: "response-pcm"))
    }

    func testIntentionalInterruptionIsDistinctFromInputRetryAndRequiresExactResponseID() throws {
        XCTAssertEqual(try VoiceTutorRealtimeEventParser.parse(
            text: #"{"type":"buddystudy.voice.response.interrupted","responseId":"resp_123-a"}"#
        ), .responseInterrupted(responseID: "resp_123-a"))
        for value in ["", "response with spaces", String(repeating: "r", count: 192)] {
            let event = try JSONSerialization.data(withJSONObject: [
                "type": "buddystudy.voice.response.interrupted", "responseId": value
            ])
            XCTAssertEqual(try VoiceTutorRealtimeEventParser.parse(data: event),
                           .ignored(type: "buddystudy.voice.response.interrupted"))
        }
    }

    func testResponseRecoveringWireRequiresExactResponseAndAcousticSequence() throws {
        let type = "buddystudy.voice.response.recovering"
        for sequence in [0, 7] {
            XCTAssertEqual(try VoiceTutorRealtimeEventParser.parse(text:
                #"{"type":"buddystudy.voice.response.recovering","responseId":"r1_failed","sequence":\#(sequence)}"#),
                .responseRecovering(responseID: "r1_failed", sequence: sequence))
        }
        let invalidSequences: [Any] = [-1, true, 1.5, "7", NSNull()]
        for sequence in invalidSequences {
            XCTAssertEqual(try VoiceTutorRealtimeEventParser.parse(data: JSONSerialization.data(withJSONObject:
                ["type": type, "responseId": "r1_failed", "sequence": sequence])), .ignored(type: type))
        }
        for responseID in ["", "r1 failed", String(repeating: "r", count: 192)] {
            XCTAssertEqual(try VoiceTutorRealtimeEventParser.parse(data: JSONSerialization.data(withJSONObject:
                ["type": type, "responseId": responseID, "sequence": 7])), .ignored(type: type))
        }
        XCTAssertEqual(try VoiceTutorRealtimeEventParser.parse(text:
            #"{"type":"buddystudy.voice.response.recovering","responseId":"r1_failed"}"#), .ignored(type: type))
        XCTAssertEqual(try VoiceTutorRealtimeEventParser.parse(text:
            #"{"type":"buddystudy.voice.response.recovering","responseId":"r1_failed","sequence":7,"extra":true}"#), .ignored(type: type))
    }

    func testProviderAudioRecoveryRetainsPartialAndAllowsSilentReplacementSettlement() throws {
        for sequence in [0, 7] {
            var duplex = VoiceTutorDuplexPlaybackState()
            var response = VoiceTutorWebRTCResponseState()
            var transcript = VoiceTutorAssistantTranscriptState()
            let earlier = VoiceTutorCaption(speaker: .tutor, text: "이전에 끝난 대화", responseID: "earlier")
            var captions = [earlier]
            if sequence == 0 { duplex.awaitInitialResponse() }
            else {
                duplex.userSpeechStarted(sequence: sequence)
                duplex.userSpeechStopped(sequence: sequence)
            }
            XCTAssertTrue(duplex.responseStarted(responseID: "r1", isTutorIntervention: false))
            response.responseStarted("r1")
            response.markOutputBufferStarted("r1")
            XCTAssertTrue(duplex.assistantAudioBegan(responseID: "r1"))
            transcript.beginResponse("r1")
            transcript.append(delta: "끊기기 전에 보이던 내용")
            let event = try VoiceTutorRealtimeEventParser.parse(text:
                #"{"type":"buddystudy.voice.response.recovering","responseId":"r1","sequence":\#(sequence)}"#)
            guard case .responseRecovering(let responseID, let inputSequence) = event else { return XCTFail("Expected scoped recovery") }
            // Mirror the VM's synchronous handoff: retain visible text, fence
            // this playout, and restore only its already-consumed input wait.
            XCTAssertTrue(duplex.recoverResponse(responseID: responseID, sequence: inputSequence))
            XCTAssertTrue(transcript.retainInterruptedCaption(responseID: responseID, providerItemID: "r1_text", in: &captions))
            XCTAssertTrue(response.abandonResponse(responseID))
            XCTAssertFalse(duplex.assistantResponseActive)
            XCTAssertFalse(response.mayIndicateSpeaking)
            XCTAssertFalse(duplex.inputNeedsRepeat)
            XCTAssertTrue(duplex.isAwaitingTutorResponse)
            XCTAssertEqual(captions.first, earlier)
            let retained = try XCTUnwrap(captions.last)
            XCTAssertEqual(retained.text, "끊기기 전에 보이던 내용")
            XCTAssertTrue(retained.isInterrupted)
            XCTAssertEqual(retained.responseID, "r1")
            XCTAssertFalse(duplex.recoverResponse(responseID: "r1", sequence: sequence), "A duplicate cannot restart recovery")

            // r2 succeeds without audio, so it has no response.created event.
            let settled = try VoiceTutorRealtimeEventParser.parse(text:
                #"{"type":"buddystudy.voice.input.settled","sequence":\#(sequence)}"#)
            guard case .inputSettled(let settledSequence) = settled else { return XCTFail("Expected silent settlement") }
            XCTAssertTrue(duplex.inputSettled(sequence: settledSequence))
            XCTAssertFalse(duplex.isAwaitingTutorResponse)
            XCTAssertFalse(duplex.inputNeedsRepeat)
            duplex.awaitInitialResponse()
            XCTAssertFalse(duplex.isAwaitingTutorResponse, "A delayed ready event cannot reopen a settled recovery")
            XCTAssertFalse(duplex.inputSettled(sequence: settledSequence))
            XCTAssertFalse(duplex.responseStarted(responseID: "r1", isTutorIntervention: false))
            XCTAssertFalse(duplex.assistantAudioBegan(responseID: "r1"))
            XCTAssertFalse(duplex.responseFinished(responseID: "r1"))
            XCTAssertNil(response.markResponseDone("r1"))
            XCTAssertNil(response.markOutputBufferStopped("r1"))
            XCTAssertFalse(transcript.matchesResponse("r1"), "Late transcript frames cannot promote or replace the retained partial")
            XCTAssertEqual(captions, [earlier, retained])
            let presentation = VoiceTutorCallPresentation(phase: .listening, inputNeedsRepeat: duplex.inputNeedsRepeat,
                isAwaitingTutorResponse: duplex.isAwaitingTutorResponse)
            XCTAssertEqual(presentation.statusText(AppStrings(language: .korean)), AppStrings(language: .korean).voiceTutorCallListening)
        }
    }

    func testProviderRecoveryAllowsUnannouncedRetryExhaustionIncludingOpening() throws {
        for sequence in [0, 7] {
            var state = VoiceTutorDuplexPlaybackState()
            if sequence == 0 { state.awaitInitialResponse() }
            else {
                state.userSpeechStarted(sequence: sequence)
                state.userSpeechStopped(sequence: sequence)
            }
            XCTAssertTrue(state.responseStarted(responseID: "r1", isTutorIntervention: false))
            XCTAssertTrue(state.assistantAudioBegan(responseID: "r1"))
            XCTAssertTrue(state.recoverResponse(responseID: "r1", sequence: sequence))
            let event = try VoiceTutorRealtimeEventParser.parse(text:
                #"{"type":"buddystudy.voice.input.retry","sequence":\#(sequence),"abandonedResponseId":"r2_unannounced"}"#)
            guard case .inputRetryScoped(let inputSequence, let responseID) = event else { return XCTFail("Expected exhausted retry") }
            XCTAssertTrue(state.acceptInputRetry(sequence: inputSequence, responseID: responseID))
            XCTAssertTrue(state.inputNeedsRepeat)
            XCTAssertFalse(state.isAwaitingTutorResponse)
            XCTAssertFalse(state.assistantResponseActive)
            XCTAssertFalse(state.acceptInputRetry(sequence: inputSequence, responseID: responseID))
            state.learnerTranscriptReceived(usesWebRTC: true)
            XCTAssertTrue(state.inputNeedsRepeat)
            state.userSpeechStarted(sequence: sequence + 1)
            XCTAssertFalse(state.inputNeedsRepeat)
            state.userSpeechStopped(sequence: sequence + 1)
            XCTAssertTrue(state.isAwaitingTutorResponse)
            XCTAssertFalse(state.recoverResponse(responseID: "r1", sequence: sequence))
            XCTAssertFalse(state.inputSettled(sequence: sequence))
            XCTAssertTrue(state.isAwaitingTutorResponse)
        }
    }

    func testProviderRecoveryRejectsOldGenerationsDuplicateEventsAndNewSpeech() {
        var state = VoiceTutorDuplexPlaybackState()
        state.userSpeechStarted(sequence: 3)
        state.userSpeechStopped(sequence: 3)
        XCTAssertTrue(state.responseStarted(responseID: "r1", isTutorIntervention: false))
        let active = state
        XCTAssertFalse(state.recoverResponse(responseID: "r1", sequence: 0))
        XCTAssertFalse(state.recoverResponse(responseID: "r1", sequence: 2))
        XCTAssertFalse(state.recoverResponse(responseID: "r1", sequence: 4))
        XCTAssertFalse(state.recoverResponse(responseID: "unknown", sequence: 3))
        XCTAssertEqual(state, active)
        XCTAssertTrue(state.recoverResponse(responseID: "r1", sequence: 3))
        XCTAssertTrue(state.responseStarted(responseID: "r2", isTutorIntervention: false))
        let replacement = state
        XCTAssertFalse(state.recoverResponse(responseID: "r1", sequence: 3), "Same sequence cannot authorize an old generation")
        XCTAssertEqual(state, replacement)
        XCTAssertTrue(state.assistantAudioBegan(responseID: "r2"))
        XCTAssertTrue(state.responseFinished(responseID: "r2"))
        XCTAssertFalse(state.recoverResponse(responseID: "r2", sequence: 3))
        XCTAssertFalse(state.acceptInputRetry(sequence: 3, responseID: "r2"), "Successful playout cannot become an unannounced retry")
        state.userSpeechStarted(sequence: 4)
        let speaking = state
        XCTAssertFalse(state.recoverResponse(responseID: "r1", sequence: 3))
        XCTAssertEqual(state, speaking)
        state.userSpeechStopped(sequence: 4)
        let waiting = state
        XCTAssertFalse(state.recoverResponse(responseID: "r1", sequence: 3))
        XCTAssertEqual(state, waiting)
        XCTAssertTrue(state.isAwaitingTutorResponse)
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

    func testProviderTurnAbandonmentClearsOnlyTheExactResponseAndPreservesLearnerSpeech() {
        var state = VoiceTutorDuplexPlaybackState()
        state.userSpeechStarted()
        state.responseStarted(responseID: "response-current", isTutorIntervention: true)

        XCTAssertFalse(state.abandonResponse(responseID: "response-stale"))
        XCTAssertTrue(state.assistantResponseActive)
        XCTAssertTrue(state.tutorInterventionActive)
        XCTAssertTrue(state.isUserSpeaking)

        XCTAssertTrue(state.abandonResponse(responseID: "response-current"))
        XCTAssertFalse(state.assistantResponseActive)
        XCTAssertNil(state.activeResponseID)
        XCTAssertFalse(state.tutorInterventionActive)
        XCTAssertTrue(state.isUserSpeaking, "Abandoning a failed tutor turn must not discard live learner speech")
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

    func testTutorWaitingIndicationIncludesGreetingUntilMatchingRenderedAudio() {
        var state = VoiceTutorDuplexPlaybackState()
        state.awaitInitialResponse()
        XCTAssertTrue(state.isAwaitingTutorResponse, "A connected call has not yet spoken its greeting")

        state.responseStarted(responseID: "greeting", isTutorIntervention: false)
        XCTAssertTrue(state.isAwaitingTutorResponse, "Provider generation alone must not claim audible speech")
        XCTAssertFalse(state.assistantAudioBegan(responseID: "stale"))
        XCTAssertTrue(state.isAwaitingTutorResponse)
        XCTAssertTrue(state.assistantAudioBegan(responseID: "greeting"))
        XCTAssertFalse(state.isAwaitingTutorResponse)
        state.awaitInitialResponse()
        XCTAssertFalse(state.isAwaitingTutorResponse, "A late ready event cannot restart the greeting wait")
        XCTAssertTrue(state.responseFinished(responseID: "greeting"))
        XCTAssertFalse(state.isAwaitingTutorResponse)
        state.awaitInitialResponse()
        XCTAssertFalse(state.isAwaitingTutorResponse)
    }

    func testTutorWaitingIndicationFollowsPairedLearnerSpeechAfterInterruptingTutorPlayback() {
        var state = VoiceTutorDuplexPlaybackState()
        state.userSpeechStopped()
        XCTAssertFalse(state.isAwaitingTutorResponse, "An unpaired stop must not fabricate a learner turn")

        state.awaitInitialResponse()
        state.userSpeechStarted()
        XCTAssertFalse(state.isAwaitingTutorResponse, "The learner is still talking")
        state.userSpeechStopped()
        XCTAssertTrue(state.isAwaitingTutorResponse)
        state.responseStarted(responseID: "answer", isTutorIntervention: false)
        XCTAssertTrue(state.assistantAudioBegan(responseID: "answer"))
        state.userSpeechStopped()
        XCTAssertFalse(state.isAwaitingTutorResponse, "A duplicate stop must not reinstate the spinner")
        XCTAssertEqual(state.userSpeechStarted(), "answer")
        state.userSpeechStopped()
        XCTAssertFalse(state.assistantResponseActive)
        XCTAssertTrue(state.isAwaitingTutorResponse, "The completed interruption waits for the learner's new reply")
    }

    func testOverlappingLearnerTurnRemainsWaitingAfterCurrentTutorSentenceIsInterrupted() {
        var state = VoiceTutorDuplexPlaybackState()
        state.responseStarted(responseID: "current", isTutorIntervention: false)
        XCTAssertTrue(state.assistantAudioBegan(responseID: "current"))
        state.userSpeechStarted()
        state.userSpeechStopped()
        XCTAssertTrue(state.isAwaitingTutorResponse)
        XCTAssertFalse(state.assistantAudioBegan(responseID: "current"))
        XCTAssertTrue(state.isAwaitingTutorResponse, "More audio from the current sentence is not the next learner reply")
        XCTAssertFalse(state.responseFinished(responseID: "current"))
        XCTAssertTrue(state.isAwaitingTutorResponse, "The overlap still needs a tutor reply")

        // Native tool-only provider responses are not announced to the client.
        // The wait persists through that gap and only the audible continuation
        // consumes this pending learner turn.
        state.responseStarted(responseID: "continuation", isTutorIntervention: false)
        XCTAssertTrue(state.isAwaitingTutorResponse)
        XCTAssertFalse(state.assistantAudioBegan(responseID: "current"))
        XCTAssertTrue(state.assistantAudioBegan(responseID: "continuation"))
        XCTAssertFalse(state.isAwaitingTutorResponse)
        XCTAssertTrue(state.responseFinished(responseID: "continuation"))
        XCTAssertFalse(state.isAwaitingTutorResponse)
    }

    func testDuplicateResponseStartCannotConsumeLaterOverlappingLearnerTurn() {
        var state = VoiceTutorDuplexPlaybackState()
        state.responseStarted(responseID: "current", isTutorIntervention: false)
        XCTAssertTrue(state.assistantAudioBegan(responseID: "current"))
        state.userSpeechStarted()
        state.userSpeechStopped()
        XCTAssertFalse(state.responseStarted(responseID: "current", isTutorIntervention: false))
        XCTAssertFalse(state.isAwaitingActiveResponseAudio)
        XCTAssertFalse(state.responseFinished(responseID: "current"))
        XCTAssertTrue(state.isAwaitingTutorResponse)
    }

    func testTutorWaitingIndicationClearsForExactAbandonmentAndNewAttempt() {
        var state = VoiceTutorDuplexPlaybackState()
        state.responseStarted(responseID: "current", isTutorIntervention: false)
        XCTAssertFalse(state.abandonResponse(responseID: "old"))
        XCTAssertTrue(state.isAwaitingTutorResponse)
        XCTAssertTrue(state.abandonResponse(responseID: "current"))
        XCTAssertFalse(state.isAwaitingTutorResponse)
        state.userSpeechStarted()
        state.userSpeechStopped()
        XCTAssertTrue(state.isAwaitingTutorResponse)
        state.reset()
        XCTAssertFalse(state.isAwaitingTutorResponse)
    }

    func testSilentInputSettlementClearsWaitingWithoutInventingTutorPlayback() throws {
        var state = VoiceTutorDuplexPlaybackState()
        state.userSpeechStarted(sequence: 7)
        state.userSpeechStopped(sequence: 7)
        XCTAssertTrue(state.isAwaitingTutorResponse)
        let event = try VoiceTutorRealtimeEventParser.parse(
            text: #"{"type":"buddystudy.voice.input.settled","sequence":7}"#
        )
        guard case .inputSettled(let sequence) = event else { return XCTFail("Expected silent settlement") }
        XCTAssertTrue(state.inputSettled(sequence: sequence))
        XCTAssertFalse(state.isAwaitingTutorResponse)
        XCTAssertFalse(state.assistantResponseActive)
        XCTAssertFalse(state.isUserSpeaking)
        XCTAssertFalse(state.inputSettled(sequence: sequence), "The settlement is idempotent")
    }

    func testUnannouncedResponseRetrySettlesOnlyItsInputAndLateNativeTranscriptKeepsRetryHint() throws {
        var state = VoiceTutorDuplexPlaybackState()
        state.awaitInitialResponse()
        state.userSpeechStarted(sequence: 7)
        state.userSpeechStopped(sequence: 7)
        XCTAssertTrue(state.isAwaitingTutorResponse)
        let event = try VoiceTutorRealtimeEventParser.parse(
            text: #"{"type":"buddystudy.voice.input.retry","sequence":7,"abandonedResponseId":"never_announced"}"#)
        guard case .inputRetryScoped(let sequence, let responseID) = event else { return XCTFail("Expected scoped retry") }
        XCTAssertTrue(state.acceptInputRetry(sequence: sequence, responseID: responseID))
        XCTAssertFalse(state.assistantResponseActive)
        XCTAssertFalse(state.isAwaitingTutorResponse)
        XCTAssertTrue(state.inputNeedsRepeat)
        XCTAssertFalse(state.acceptInputRetry(sequence: sequence, responseID: responseID), "Duplicate retry must not revive an old input")
        state.learnerTranscriptReceived(usesWebRTC: true)
        state.awaitInitialResponse()
        XCTAssertTrue(state.inputNeedsRepeat, "The failed utterance's delayed ASR is not a new attempt")
        XCTAssertFalse(state.isAwaitingTutorResponse)
        let presentation = VoiceTutorCallPresentation(phase: .listening,
            inputNeedsRepeat: state.inputNeedsRepeat, isAwaitingTutorResponse: state.isAwaitingTutorResponse)
        XCTAssertEqual(presentation.statusText(AppStrings(language: .korean)), AppStrings(language: .korean).voiceTutorInputRepeat)
        state.userSpeechStarted(sequence: 8)
        XCTAssertFalse(state.inputNeedsRepeat)
        state.userSpeechStopped(sequence: 8)
        XCTAssertTrue(state.isAwaitingTutorResponse, "The next actual utterance starts its own response wait")
        XCTAssertFalse(state.inputSettled(sequence: 7))
        XCTAssertTrue(state.inputSettled(sequence: 8))
    }

    func testScopedRetryCannotConsumeNewerLiveOrStoppedSpeech() {
        var state = VoiceTutorDuplexPlaybackState()
        state.userSpeechStarted(sequence: 1)
        state.userSpeechStopped(sequence: 1)
        state.userSpeechStarted(sequence: 2)
        let speaking = state
        XCTAssertFalse(state.acceptInputRetry(sequence: 1, responseID: "failed_old"))
        XCTAssertFalse(state.acceptInputRetry(sequence: 2, responseID: "premature"))
        XCTAssertEqual(state, speaking)
        state.userSpeechStopped(sequence: 2)
        let waiting = state
        XCTAssertFalse(state.acceptInputRetry(sequence: 1, responseID: "failed_old"))
        XCTAssertFalse(state.acceptInputRetry(sequence: 3, responseID: "unseen"))
        XCTAssertEqual(state, waiting)
        XCTAssertTrue(state.isAwaitingTutorResponse)
        XCTAssertFalse(state.inputNeedsRepeat)
        XCTAssertTrue(state.acceptInputRetry(sequence: 2, responseID: "failed_current"))
    }

    func testScopedRetryAbandonsOnlyTheMatchingActiveResponseAndPreservesItsReplacement() {
        var state = VoiceTutorDuplexPlaybackState()
        var response = VoiceTutorWebRTCResponseState()
        state.userSpeechStarted(sequence: 7)
        state.userSpeechStopped(sequence: 7)
        state.responseStarted(responseID: "first", isTutorIntervention: false)
        response.responseStarted("first")
        state.responseStarted(responseID: "replacement", isTutorIntervention: false)
        response.responseStarted("replacement")
        let awaitingReplacement = state
        XCTAssertFalse(state.acceptInputRetry(sequence: 7, responseID: "first"))
        XCTAssertFalse(state.acceptInputRetry(sequence: 7, responseID: nil))
        XCTAssertEqual(state, awaitingReplacement)
        XCTAssertTrue(state.acceptInputRetry(sequence: 7, responseID: "replacement"))
        // Mirror the two model operations performed synchronously by the VM:
        // accepting input failure and abandoning only its local audio response.
        XCTAssertTrue(state.abandonResponse(responseID: "replacement"))
        XCTAssertTrue(response.abandonResponse("replacement"))
        XCTAssertTrue(state.inputNeedsRepeat)
        XCTAssertFalse(state.isAwaitingTutorResponse)
        XCTAssertFalse(state.assistantAudioBegan(responseID: "replacement"))
        XCTAssertNil(response.markResponseDone("replacement"))
        XCTAssertFalse(state.acceptInputRetry(sequence: 7, responseID: "replacement"))
        state.learnerTranscriptReceived(usesWebRTC: true)
        XCTAssertTrue(state.inputNeedsRepeat)
    }

    func testScopedOpeningRetryCannotConsumeLearnerInputOrAnotherAttempt() {
        var state = VoiceTutorDuplexPlaybackState()
        state.awaitInitialResponse()
        XCTAssertTrue(state.acceptInputRetry(sequence: 0, responseID: "unannounced_opening"))
        XCTAssertTrue(state.inputNeedsRepeat)
        state.userSpeechStarted(sequence: 1)
        state.userSpeechStopped(sequence: 1)
        let learnerWait = state
        XCTAssertFalse(state.acceptInputRetry(sequence: 0, responseID: "unannounced_opening"))
        XCTAssertEqual(state, learnerWait)
        XCTAssertTrue(state.isAwaitingTutorResponse)
        state.reset()
        state.awaitInitialResponse()
        let newAttempt = state
        XCTAssertFalse(state.acceptInputRetry(sequence: 1, responseID: "old_attempt"))
        XCTAssertEqual(state, newAttempt)
        state.responseStarted(responseID: "announced_opening", isTutorIntervention: false)
        XCTAssertTrue(state.acceptInputRetry(sequence: 0, responseID: "announced_opening"))
        XCTAssertTrue(state.abandonResponse(responseID: "announced_opening"))
        XCTAssertTrue(state.inputNeedsRepeat)
    }

    func testGenericNativeRetryAlsoSurvivesLateASRWhileLegacyPCMBehaviorIsPreserved() {
        var state = VoiceTutorDuplexPlaybackState()
        state.setInputNeedsRepeat(true)
        state.learnerTranscriptReceived(usesWebRTC: true)
        XCTAssertTrue(state.inputNeedsRepeat)
        state.learnerTranscriptReceived(usesWebRTC: false)
        XCTAssertFalse(state.inputNeedsRepeat)
        state.setInputNeedsRepeat(true)
        state.reset()
        XCTAssertFalse(state.inputNeedsRepeat)
    }

    func testAutomaticReplacementClearsRetryHintOnlyWhenItsExactAudioBegins() {
        var state = VoiceTutorDuplexPlaybackState()
        state.userSpeechStarted(sequence: 7)
        state.userSpeechStopped(sequence: 7)
        XCTAssertTrue(state.responseStarted(responseID: "failed", isTutorIntervention: false))
        XCTAssertTrue(state.abandonResponse(responseID: "failed"))
        state.setInputNeedsRepeat(true) // Compatibility path for a forwarded output clear.
        state.learnerTranscriptReceived(usesWebRTC: true)
        XCTAssertTrue(state.inputNeedsRepeat)
        XCTAssertTrue(state.responseStarted(responseID: "retry", isTutorIntervention: false))
        XCTAssertTrue(state.inputNeedsRepeat, "Generation intent alone cannot claim recovery")
        XCTAssertFalse(state.assistantAudioBegan(responseID: "failed"))
        XCTAssertTrue(state.inputNeedsRepeat)
        XCTAssertTrue(state.assistantAudioBegan(responseID: "retry"))
        XCTAssertFalse(state.inputNeedsRepeat)
        XCTAssertTrue(state.responseFinished(responseID: "retry"))
        XCTAssertFalse(state.inputNeedsRepeat, "A recovered answer must not leave a stale repeat label after playout")
    }

    func testRetryHintIsNotClearedByExistingOrRejectedResponseAudio() {
        var state = VoiceTutorDuplexPlaybackState()
        state.userSpeechStarted(sequence: 1)
        state.userSpeechStopped(sequence: 1)
        XCTAssertTrue(state.responseStarted(responseID: "existing", isTutorIntervention: false))
        state.setInputNeedsRepeat(true)
        XCTAssertTrue(state.assistantAudioBegan(responseID: "existing"))
        XCTAssertTrue(state.inputNeedsRepeat, "Audio already in progress before an unscoped retry is not a replacement")
        XCTAssertTrue(state.abandonResponse(responseID: "existing"))
        XCTAssertTrue(state.responseStarted(responseID: "existing", isTutorIntervention: false))
        XCTAssertTrue(state.assistantAudioBegan(responseID: "existing"))
        XCTAssertTrue(state.inputNeedsRepeat, "A failed generation's delayed frames cannot claim successful recovery")
        XCTAssertTrue(state.responseStarted(responseID: "new", isTutorIntervention: false))
        XCTAssertTrue(state.assistantAudioBegan(responseID: "new"))
        XCTAssertFalse(state.inputNeedsRepeat)
    }

    func testNewSpeechAndItsRetryCannotBeOverwrittenByOldReplacementAudio() {
        var state = VoiceTutorDuplexPlaybackState()
        state.userSpeechStarted(sequence: 4)
        state.userSpeechStopped(sequence: 4)
        XCTAssertTrue(state.acceptInputRetry(sequence: 4, responseID: "failed_old"))
        XCTAssertTrue(state.responseStarted(responseID: "old_replacement", isTutorIntervention: false))
        state.userSpeechStarted(sequence: 5)
        XCTAssertFalse(state.inputNeedsRepeat)
        state.userSpeechStopped(sequence: 5)
        XCTAssertTrue(state.isAwaitingTutorResponse)
        XCTAssertTrue(state.acceptInputRetry(sequence: 5, responseID: "failed_new"))
        XCTAssertFalse(state.responseStarted(responseID: "old_replacement", isTutorIntervention: false))
        XCTAssertFalse(state.assistantAudioBegan(responseID: "old_replacement"))
        XCTAssertTrue(state.inputNeedsRepeat)
        XCTAssertTrue(state.responseStarted(responseID: "current_replacement", isTutorIntervention: false))
        XCTAssertTrue(state.assistantAudioBegan(responseID: "current_replacement"))
        XCTAssertFalse(state.inputNeedsRepeat)
    }

    func testSilentInputSettlementCannotConsumeNewerSpeechOrAnActiveTutorResponse() {
        var state = VoiceTutorDuplexPlaybackState()
        state.userSpeechStarted(sequence: 1)
        state.userSpeechStopped(sequence: 1)
        state.userSpeechStarted(sequence: 2)
        XCTAssertTrue(state.inputSettled(sequence: 1))
        XCTAssertTrue(state.isUserSpeaking, "Settling old input never ends the new utterance")
        state.userSpeechStopped(sequence: 1)
        XCTAssertTrue(state.isUserSpeaking, "A stale stop cannot seal the new utterance")
        state.userSpeechStopped(sequence: 2)
        XCTAssertFalse(state.inputSettled(sequence: 1))
        XCTAssertTrue(state.isAwaitingTutorResponse)

        state.responseStarted(responseID: "new-reply", isTutorIntervention: false)
        XCTAssertFalse(state.inputSettled(sequence: 2))
        XCTAssertTrue(state.isAwaitingActiveResponseAudio)
        XCTAssertTrue(state.assistantResponseActive)
        XCTAssertEqual(state.userSpeechStarted(sequence: 3), "new-reply")
        state.userSpeechStopped(sequence: 3)
        XCTAssertFalse(state.inputSettled(sequence: 2))
        XCTAssertFalse(state.assistantAudioBegan(responseID: "new-reply"))
        XCTAssertFalse(state.responseFinished(responseID: "new-reply"))
        XCTAssertTrue(state.isAwaitingTutorResponse, "The newer overlap still needs a response")
        XCTAssertTrue(state.inputSettled(sequence: 3))
        XCTAssertFalse(state.isAwaitingTutorResponse)
    }

    func testSilentOpeningSettlementPreservesLearnerWaitAndCannotRestartOnLateReady() {
        var state = VoiceTutorDuplexPlaybackState()
        state.awaitInitialResponse()
        state.userSpeechStarted(sequence: 1)
        state.userSpeechStopped(sequence: 1)
        XCTAssertTrue(state.inputSettled(sequence: 0))
        XCTAssertTrue(state.isAwaitingTutorResponse, "Only the greeting was settled")
        XCTAssertTrue(state.inputSettled(sequence: 1))
        state.awaitInitialResponse()
        XCTAssertFalse(state.isAwaitingTutorResponse)
        XCTAssertFalse(state.inputSettled(sequence: 0))

        state.reset()
        state.responseStarted(responseID: "greeting", isTutorIntervention: false)
        XCTAssertFalse(state.inputSettled(sequence: 0))
        XCTAssertTrue(state.isAwaitingActiveResponseAudio)
        XCTAssertTrue(state.isAwaitingTutorResponse)
    }

    func testSilentLearnerSettlementConsumesUnannouncedOpeningOnlyForMatchingPendingTurn() {
        var state = VoiceTutorDuplexPlaybackState()
        state.awaitInitialResponse()
        state.userSpeechStarted(sequence: 1)
        state.userSpeechStopped(sequence: 1)
        XCTAssertTrue(state.isAwaitingTutorResponse)

        // The server yields or supersedes the opening before announcing any
        // tutor response, then the learner continues before a reply is ready.
        state.userSpeechStarted(sequence: 2)
        XCTAssertTrue(state.isUserSpeaking)
        XCTAssertFalse(state.isAwaitingTutorResponse)
        state.userSpeechStopped(sequence: 2)
        let awaitingLatestTurn = state
        XCTAssertFalse(state.inputSettled(sequence: 1), "An older input cannot settle the current learner turn")
        XCTAssertFalse(state.inputSettled(sequence: 3), "An unseen input cannot consume the opening wait")
        XCTAssertEqual(state, awaitingLatestTurn)
        XCTAssertTrue(state.isAwaitingTutorResponse)

        XCTAssertTrue(state.inputSettled(sequence: 2))
        XCTAssertFalse(state.isAwaitingTutorResponse, "The silent learner reply also replaces the unannounced opening")
        XCTAssertFalse(state.assistantResponseActive)
        XCTAssertFalse(state.isAwaitingActiveResponseAudio)
        XCTAssertNil(state.activeResponseID)
        XCTAssertFalse(state.isUserSpeaking)
        state.awaitInitialResponse()
        XCTAssertFalse(state.isAwaitingTutorResponse, "A late ready event cannot revive the superseded opening")
        XCTAssertFalse(state.inputSettled(sequence: 2))
        XCTAssertFalse(state.inputSettled(sequence: 0))
    }

    func testRejectedOpeningCannotLeaveGreetingWaitAfterRepeatedInputSettlesSilently() {
        var state = VoiceTutorDuplexPlaybackState()
        state.awaitInitialResponse()
        XCTAssertTrue(state.stopWaitingForInitialResponse())
        XCTAssertFalse(state.isAwaitingTutorResponse)
        state.userSpeechStarted(sequence: 1)
        state.userSpeechStopped(sequence: 1)
        XCTAssertFalse(state.stopWaitingForInitialResponse())
        XCTAssertTrue(state.isAwaitingTutorResponse, "A retry signal cannot discard newer learner input")
        XCTAssertTrue(state.inputSettled(sequence: 1))
        state.awaitInitialResponse()
        XCTAssertFalse(state.isAwaitingTutorResponse)
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

    func testProviderTurnAbandonmentInvalidatesOnlyExactWebRTCAttributionState() {
        var response = VoiceTutorWebRTCResponseState()
        response.responseStarted("response-current")
        response.markOutputBufferStarted("response-current")
        XCTAssertFalse(response.abandonResponse("response-stale"))
        XCTAssertTrue(response.mayIndicateSpeaking)
        XCTAssertTrue(response.abandonResponse("response-current"))
        XCTAssertNil(response.responseID)
        XCTAssertFalse(response.mayIndicateSpeaking)

        var playout = VoiceTutorLocalPlayoutTailState()
        playout.responseStarted("response-current")
        XCTAssertFalse(playout.abandonResponse("response-stale"))
        XCTAssertEqual(playout.activeResponseID, "response-current")
        XCTAssertTrue(playout.abandonResponse("response-current"))
        XCTAssertNil(playout.activeResponseID)
        XCTAssertNil(playout.sealedToken)
    }

    func testSpokenEndLocalPlayoutTailIsBoundToTheExactCompletedResponseGeneration() throws {
        var state = VoiceTutorLocalPlayoutTailState()
        state.responseStarted("response-1")
        XCTAssertNil(state.responseCompleted("response-stale", at: 10))
        state.rendered(at: 9.98, duration: 0.01)
        let profile = VoiceTutorLocalPlayoutDrainProfile(
            outputLatencySeconds: 0.08,
            outputIOBufferDurationSeconds: 0.02
        )
        let token = try XCTUnwrap(state.responseCompleted(
            "response-1",
            at: 10,
            drainProfile: profile
        ))

        XCTAssertEqual(token.responseID, "response-1")
        XCTAssertEqual(
            try XCTUnwrap(state.remainingWait(for: token, now: 10)),
            VoiceTutorLocalPlayoutTailState.spokenEndNetworkTailWindowSeconds + 0.10,
            accuracy: 0.000_001,
            "A pre-stop callback must not contribute a renderer quantum to the spoken-end drain"
        )
        state.rendered(at: 10.02, duration: 0.01)
        let postStopRenderWait: TimeInterval = try XCTUnwrap(
            state.remainingWait(for: token, now: 10)
        )
        XCTAssertEqual(
            postStopRenderWait,
            VoiceTutorLocalPlayoutTailState.spokenEndNetworkTailWindowSeconds + 0.11,
            accuracy: 0.000_001
        )
        XCTAssertNotNil(state.remainingWait(for: token, now: 11.359))
        XCTAssertNil(state.remainingWait(for: token, now: 11.36))

        state.responseStarted("response-2")
        XCTAssertNil(
            state.remainingWait(for: token, now: 10.1),
            "A newer response generation must invalidate an old spoken-end tail"
        )
    }

    func testSpokenEndLocalPlayoutTailKeepsProviderStopWindowDespiteImmediateContinuousComfortNoise() throws {
        var state = VoiceTutorLocalPlayoutTailState()
        state.responseStarted("response-1")
        let profile = VoiceTutorLocalPlayoutDrainProfile(
            outputLatencySeconds: 0.04,
            outputIOBufferDurationSeconds: 0.01
        )
        let token = try XCTUnwrap(state.responseCompleted(
            "response-1",
            at: 20,
            drainProfile: profile
        ))
        let boundedWait: TimeInterval = try XCTUnwrap(
            state.remainingWait(for: token, now: 20)
        )
        XCTAssertEqual(
            boundedWait,
            VoiceTutorLocalPlayoutTailState.spokenEndNetworkTailWindowSeconds + 0.05,
            accuracy: 0.000_001
        )

        // A continuous WebRTC track can immediately render comfort noise after
        // provider stop. That callback must not collapse the network-tail fence
        // to the old 450 ms grace. A plausible later tail inside the hard window
        // still contributes its measured render quantum to Core Audio drain.
        state.rendered(at: 20.01, duration: 0.01)
        XCTAssertNotNil(
            state.remainingWait(for: token, now: 20.8),
            "Immediate comfort noise must not shorten the provider-stop network-tail window"
        )
        state.rendered(at: 21.1, duration: 0.02)
        let expectedDeadline = 20
            + VoiceTutorLocalPlayoutTailState.spokenEndNetworkTailWindowSeconds
            + 0.07
        XCTAssertNotNil(state.remainingWait(for: token, now: expectedDeadline - 0.001))
        XCTAssertNil(state.remainingWait(for: token, now: expectedDeadline))

        state.rendered(at: 21.3, duration: 0.25)
        XCTAssertNil(
            state.remainingWait(for: token, now: expectedDeadline),
            "Callbacks outside the hard network window cannot extend the exact generation"
        )
    }

    func testSpokenEndLocalPlayoutTailDrainsMeasuredQueueAfterLateRendererEvidence() throws {
        var state = VoiceTutorLocalPlayoutTailState()
        state.responseStarted("response-1")
        let profile = VoiceTutorLocalPlayoutDrainProfile(
            outputLatencySeconds: 0.12,
            outputIOBufferDurationSeconds: 0.02
        )
        let token = try XCTUnwrap(state.responseCompleted(
            "response-1",
            at: 30,
            drainProfile: profile
        ))
        state.rendered(at: 31.24, duration: 0.02)

        XCTAssertNotNil(
            state.remainingWait(for: token, now: 31.25),
            "The old 1.25-second cap could close immediately after a late final buffer reached Core Audio"
        )
        let deadline = 30
            + VoiceTutorLocalPlayoutTailState.spokenEndNetworkTailWindowSeconds
            + 0.16
        XCTAssertNotNil(state.remainingWait(for: token, now: deadline - 0.001))
        XCTAssertNil(state.remainingWait(for: token, now: deadline))
    }

    func testLocalPlayoutDrainProfileUsesMeasuredValuesWithDefensiveBounds() {
        let measured = VoiceTutorLocalPlayoutDrainProfile(
            outputLatencySeconds: 0.12,
            outputIOBufferDurationSeconds: 0.02
        )
        XCTAssertEqual(measured.queueDrainSeconds(renderQuantumSeconds: 0.01), 0.15, accuracy: 0.000_001)

        let invalid = VoiceTutorLocalPlayoutDrainProfile(
            outputLatencySeconds: .nan,
            outputIOBufferDurationSeconds: -1
        )
        XCTAssertEqual(invalid.queueDrainSeconds(renderQuantumSeconds: .infinity), 0)

        let bounded = VoiceTutorLocalPlayoutDrainProfile(
            outputLatencySeconds: 100,
            outputIOBufferDurationSeconds: 100
        )
        XCTAssertEqual(
            bounded.queueDrainSeconds(renderQuantumSeconds: 100),
            VoiceTutorLocalPlayoutDrainProfile.maximumOutputLatencySeconds
                + VoiceTutorLocalPlayoutDrainProfile.maximumIOBufferDurationSeconds
                + VoiceTutorLocalPlayoutDrainProfile.maximumRenderQuantumSeconds,
            accuracy: 0.000_001
        )
    }

    func testSpokenEndLocalPlayoutTailIsBoundedAndAbsentWithoutAnActiveResponse() throws {
        var state = VoiceTutorLocalPlayoutTailState()
        XCTAssertNil(state.responseCompleted("response-1", at: 30))
        state.responseStarted(nil)
        XCTAssertNil(state.responseCompleted("response-1", at: 30))

        state.responseStarted("response-1")
        let token = try XCTUnwrap(state.responseCompleted("response-1", at: 30))
        XCTAssertNotNil(
            state.remainingWait(
                for: token,
                now: 30
                    + VoiceTutorLocalPlayoutTailState.spokenEndNetworkTailWindowSeconds
                    - 0.001
            )
        )
        XCTAssertNil(
            state.remainingWait(
                for: token,
                now: 30 + VoiceTutorLocalPlayoutTailState.spokenEndNetworkTailWindowSeconds
            )
        )
        state.rendered(
            at: 30 + VoiceTutorLocalPlayoutTailState.spokenEndNetworkTailWindowSeconds + 0.001,
            duration: VoiceTutorLocalPlayoutDrainProfile.maximumRenderQuantumSeconds
        )
        XCTAssertNil(
            state.remainingWait(
                for: token,
                now: 30 + VoiceTutorLocalPlayoutTailState.spokenEndNetworkTailWindowSeconds + 0.001
            ),
            "A late render callback cannot resurrect an elapsed bounded drain"
        )

        XCTAssertEqual(
            VoiceTutorServerEndPlayoutPolicy.acceptedQuotaNoticeResponseID(
                reason: "QUOTA_EXHAUSTED",
                phase: .ending,
                responseID: "response-1",
                isQuotaExhaustionNotice: true
            ),
            "response-1"
        )
        XCTAssertNil(VoiceTutorServerEndPlayoutPolicy.acceptedQuotaNoticeResponseID(
            reason: "QUOTA_EXHAUSTED",
            phase: .speaking,
            responseID: "response-1",
            isQuotaExhaustionNotice: true
        ))
        XCTAssertNil(VoiceTutorServerEndPlayoutPolicy.acceptedQuotaNoticeResponseID(
            reason: "QUOTA_EXHAUSTED",
            phase: .ending,
            responseID: "response-1",
            isQuotaExhaustionNotice: false
        ))
        XCTAssertNil(VoiceTutorServerEndPlayoutPolicy.acceptedQuotaNoticeResponseID(
            reason: "TIME_LIMIT",
            phase: .ending,
            responseID: "response-1",
            isQuotaExhaustionNotice: true
        ))
        XCTAssertTrue(VoiceTutorServerEndPlayoutPolicy.isExactAbandonedQuotaNotice(
            reason: "QUOTA_EXHAUSTED",
            phase: .ending,
            responseID: "response-1",
            quotaNoticeResponseID: "response-1"
        ))
        XCTAssertFalse(VoiceTutorServerEndPlayoutPolicy.isExactAbandonedQuotaNotice(
            reason: "QUOTA_EXHAUSTED",
            phase: .ending,
            responseID: "ordinary-response",
            quotaNoticeResponseID: "response-1"
        ))
        XCTAssertFalse(VoiceTutorServerEndPlayoutPolicy.isExactAbandonedQuotaNotice(
            reason: "QUOTA_EXHAUSTED",
            phase: .speaking,
            responseID: "response-1",
            quotaNoticeResponseID: "response-1"
        ))

        XCTAssertEqual(
            VoiceTutorServerEndPlayoutPolicy.spokenEndToken(
                reason: "user_ended", usesWebRTC: true, pending: token
            ),
            token
        )
        XCTAssertEqual(
            VoiceTutorServerEndPlayoutPolicy.spokenEndToken(
                reason: "QUOTA_EXHAUSTED",
                usesWebRTC: true,
                pending: token,
                quotaNoticeResponseID: "response-1"
            ),
            token
        )
        XCTAssertNil(VoiceTutorServerEndPlayoutPolicy.spokenEndToken(
            reason: "QUOTA_EXHAUSTED",
            usesWebRTC: true,
            pending: token,
            quotaNoticeResponseID: "ordinary-response"
        ))
        XCTAssertNil(VoiceTutorServerEndPlayoutPolicy.spokenEndToken(
            reason: "QUOTA_EXHAUSTED", usesWebRTC: true, pending: token
        ))
        XCTAssertNil(VoiceTutorServerEndPlayoutPolicy.spokenEndToken(
            reason: "TIME_LIMIT", usesWebRTC: true, pending: token
        ))
        XCTAssertNil(VoiceTutorServerEndPlayoutPolicy.spokenEndToken(
            reason: "USER_ENDED", usesWebRTC: false, pending: token
        ))
        XCTAssertNil(VoiceTutorServerEndPlayoutPolicy.spokenEndToken(
            reason: "USER_ENDED", usesWebRTC: true, pending: nil
        ))
        XCTAssertEqual(
            VoiceTutorServerEndPlayoutPolicy.serverVerifiedFallbackResponseID(
                reason: "USER_ENDED",
                usesWebRTC: true,
                pending: nil,
                activeResponseID: "response-1"
            ),
            "response-1"
        )
        XCTAssertNil(VoiceTutorServerEndPlayoutPolicy.serverVerifiedFallbackResponseID(
            reason: "QUOTA_EXHAUSTED",
            usesWebRTC: true,
            pending: nil,
            activeResponseID: "response-1",
            quotaNoticeResponseID: "response-1"
        ))
        XCTAssertNil(VoiceTutorServerEndPlayoutPolicy.serverVerifiedFallbackResponseID(
            reason: "QUOTA_EXHAUSTED",
            usesWebRTC: true,
            pending: nil,
            activeResponseID: "ordinary-response",
            quotaNoticeResponseID: "quota-response"
        ))
        XCTAssertNil(VoiceTutorServerEndPlayoutPolicy.serverVerifiedFallbackResponseID(
            reason: "USER_ENDED",
            usesWebRTC: true,
            pending: token,
            activeResponseID: "response-1"
        ))
        XCTAssertNil(VoiceTutorServerEndPlayoutPolicy.serverVerifiedFallbackResponseID(
            reason: "TIME_LIMIT",
            usesWebRTC: true,
            pending: nil,
            activeResponseID: "response-1"
        ))

        XCTAssertEqual(
            VoiceTutorServerEndPlayoutPolicy.playoutDrainedResponseID(
                reason: "QUOTA_EXHAUSTED",
                phase: .ending,
                usesWebRTC: true,
                pending: token,
                quotaNoticeResponseID: "response-1"
            ),
            "response-1"
        )
        XCTAssertNil(VoiceTutorServerEndPlayoutPolicy.playoutDrainedResponseID(
            reason: "QUOTA_EXHAUSTED",
            phase: .ending,
            usesWebRTC: true,
            pending: token,
            quotaNoticeResponseID: "ordinary-response"
        ))
        XCTAssertNil(VoiceTutorServerEndPlayoutPolicy.playoutDrainedResponseID(
            reason: "TIME_LIMIT",
            phase: .ending,
            usesWebRTC: true,
            pending: token
        ))
        XCTAssertNil(VoiceTutorServerEndPlayoutPolicy.playoutDrainedResponseID(
                reason: "QUOTA_EXHAUSTED",
                phase: .speaking,
                usesWebRTC: true,
                pending: token,
                quotaNoticeResponseID: "response-1"
            ))
        XCTAssertNil(VoiceTutorServerEndPlayoutPolicy.playoutDrainedResponseID(
                reason: "QUOTA_EXHAUSTED",
                phase: .ending,
                usesWebRTC: false,
                pending: token,
                quotaNoticeResponseID: "response-1"
            ))
    }

    func testOrdinaryTutorPlayoutAcknowledgesOnlyTheCompletedLiveResponse() throws {
        var playout = VoiceTutorLocalPlayoutTailState()
        playout.responseStarted("confirmation-question")
        let token = try XCTUnwrap(playout.responseCompleted("confirmation-question", at: 0))
        for phase in [VoiceTutorSessionPhase.listening, .speaking] {
            XCTAssertEqual(VoiceTutorServerEndPlayoutPolicy.playoutDrainedResponseID(
                reason: nil, phase: phase, usesWebRTC: true, pending: token
            ), "confirmation-question")
        }
        for phase in [VoiceTutorSessionPhase.idle, .connecting, .ending, .ended, .failed] {
            XCTAssertNil(VoiceTutorServerEndPlayoutPolicy.playoutDrainedResponseID(
                reason: nil, phase: phase, usesWebRTC: true, pending: token
            ))
        }
        XCTAssertNil(VoiceTutorServerEndPlayoutPolicy.playoutDrainedResponseID(
            reason: nil, phase: .listening, usesWebRTC: true, pending: nil
        ))
        XCTAssertNil(VoiceTutorServerEndPlayoutPolicy.playoutDrainedResponseID(
            reason: nil, phase: .listening, usesWebRTC: false, pending: token
        ))
        XCTAssertNil(VoiceTutorServerEndPlayoutPolicy.playoutDrainedResponseID(
            reason: "QUOTA_EXHAUSTED", phase: .ending, usesWebRTC: true,
            pending: token, quotaNoticeResponseID: "different-quota-notice"
        ), "Ordinary confirmation receipt cannot release the quota notice")
    }

    func testQuotaPlayoutAcknowledgementFollowsTheExactBoundedLocalTail() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let sourceURL = root.appendingPathComponent("StudyMate/ViewModels/VoiceTutorViewModel.swift")
        #if !targetEnvironment(simulator)
        guard FileManager.default.fileExists(atPath: sourceURL.path) else {
            throw XCTSkip("Source-contract check requires the local repository; behavior tests run on iPhone.")
        }
        #endif
        let source = try String(
            contentsOf: sourceURL,
            encoding: .utf8
        )
        let start = try XCTUnwrap(source.range(of: "private func scheduleTerminalPlayoutDrainIfReady"))
        let end = try XCTUnwrap(
            source.range(of: "private func cancelTerminalPlayoutDrain", range: start.upperBound..<source.endIndex)
        )
        let method = String(source[start.lowerBound..<end.lowerBound])
        let localTail = try XCTUnwrap(method.range(of: "waitForLocalPlayoutTail(token)"))
        let acknowledgement = try XCTUnwrap(method.range(of: "sendPlayoutDrained(responseID: responseID)"))

        XCTAssertLessThan(localTail.lowerBound, acknowledgement.lowerBound)
        XCTAssertTrue(method.contains("VoiceTutorServerEndPlayoutPolicy.playoutDrainedResponseID"))
        XCTAssertTrue(method.contains("connectionAttemptFence.isCurrent(attemptID)"))
        XCTAssertFalse(method.contains("renderedPCM"), "PCM silence must not authorize a terminal ACK")
    }

    func testServerEndingClosesLearnerInputFailClosedWithoutClosingTutorAudio() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let transportURL = root.appendingPathComponent("StudyMate/Services/VoiceTutorWebRTCTransport.swift")
        let viewModelURL = root.appendingPathComponent("StudyMate/ViewModels/VoiceTutorViewModel.swift")
        #if !targetEnvironment(simulator)
        guard [transportURL, viewModelURL].allSatisfy({ FileManager.default.fileExists(atPath: $0.path) }) else {
            throw XCTSkip("Source-contract check requires the local repository; behavior tests run on iPhone.")
        }
        #endif
        let transportSource = try String(
            contentsOf: transportURL,
            encoding: .utf8
        )
        let closeStart = try XCTUnwrap(transportSource.range(of: "func closeMicrophoneInput()"))
        let closeEnd = try XCTUnwrap(
            transportSource.range(of: "func setSessionMediaReady()", range: closeStart.upperBound..<transportSource.endIndex)
        )
        let closeMethod = String(transportSource[closeStart.lowerBound..<closeEnd.lowerBound])
        XCTAssertTrue(closeMethod.contains("captureTap?.updateGate(mediaReady: false, muted: true)"))
        XCTAssertTrue(closeMethod.contains("track?.isEnabled = false"))
        XCTAssertFalse(closeMethod.contains("remoteAudioTrack"), "The final tutor sentence must remain audible")

        let viewModelSource = try String(
            contentsOf: viewModelURL,
            encoding: .utf8
        )
        let endingStart = try XCTUnwrap(viewModelSource.range(of: "case .sessionEnding(let reason"))
        let endingEnd = try XCTUnwrap(
            viewModelSource.range(of: "case .sessionEnded", range: endingStart.upperBound..<viewModelSource.endIndex)
        )
        let endingHandler = String(viewModelSource[endingStart.lowerBound..<endingEnd.lowerBound])
        XCTAssertTrue(endingHandler.contains("webRTCTransport?.closeMicrophoneInput()"))
        XCTAssertFalse(endingHandler.contains("webRTCTransport?.setMuted(true)"))
    }

    func testServerEndingCanStillSealTheExactResponseButExplicitStopCannot() {
        XCTAssertTrue(VoiceTutorSessionPhase.listening.maySealServerResponse(isFinalizing: false))
        XCTAssertTrue(
            VoiceTutorSessionPhase.ending.maySealServerResponse(isFinalizing: false),
            "session.ending may arrive before response.done/output_audio_buffer.stopped"
        )
        XCTAssertFalse(
            VoiceTutorSessionPhase.ending.maySealServerResponse(isFinalizing: true),
            "The red-button stop is already finalizing and must not enter spoken-end playout waiting"
        )
        XCTAssertFalse(VoiceTutorSessionPhase.ended.maySealServerResponse(isFinalizing: false))
        XCTAssertFalse(VoiceTutorSessionPhase.failed.maySealServerResponse(isFinalizing: false))
        XCTAssertTrue(
            VoiceTutorSessionPhase.ending
                .shouldCloseFinalizingMediaForDismissal(isFinalizing: true),
            "Explicit dismissal must abort a pending local playout tail without waiting"
        )
        XCTAssertFalse(
            VoiceTutorSessionPhase.ending
                .shouldCloseFinalizingMediaForDismissal(isFinalizing: false)
        )
        XCTAssertFalse(
            VoiceTutorSessionPhase.listening
                .shouldCloseFinalizingMediaForDismissal(isFinalizing: true)
        )
    }

    func testSpokenEndTailSealsWhetherSessionEndingArrivesBeforeOrAfterProviderCompletion() throws {
        for sessionEndingFirst in [false, true] {
            var phase = VoiceTutorSessionPhase.speaking
            var response = VoiceTutorWebRTCResponseState()
            var playout = VoiceTutorLocalPlayoutTailState()
            response.responseStarted("response-1")
            playout.responseStarted("response-1")

            if sessionEndingFirst { phase = .ending }
            XCTAssertNil(response.markResponseDone("response-1"))
            let completedID = try XCTUnwrap(response.markOutputBufferStopped("response-1"))
            XCTAssertTrue(phase.maySealServerResponse(isFinalizing: false))
            let token = try XCTUnwrap(playout.responseCompleted(completedID, at: 40))
            if !sessionEndingFirst { phase = .ending }

            XCTAssertEqual(token.responseID, "response-1")
            XCTAssertEqual(phase, .ending)
            XCTAssertEqual(
                VoiceTutorServerEndPlayoutPolicy.spokenEndToken(
                    reason: "USER_ENDED", usesWebRTC: true, pending: token
                ),
                token
            )
        }
    }

    func testSpokenEndTailCanSealFromServerAttestationWhenSecondProviderBoundaryIsNotRelayed() throws {
        var response = VoiceTutorWebRTCResponseState()
        var playout = VoiceTutorLocalPlayoutTailState()
        response.responseStarted("response-1")
        playout.responseStarted("response-1")
        XCTAssertNil(response.markResponseDone("response-1"))

        // The backend's USER_ENDED lifecycle is emitted only after it has seen
        // both exact boundaries, but terminal ownership can suppress forwarding
        // the second raw provider event to iOS. Preserve that exact active ID.
        let fallbackID = try XCTUnwrap(
            VoiceTutorServerEndPlayoutPolicy.serverVerifiedFallbackResponseID(
                reason: "USER_ENDED",
                usesWebRTC: true,
                pending: nil,
                activeResponseID: response.responseID
            )
        )
        let token = try XCTUnwrap(playout.responseCompleted(fallbackID, at: 50))

        XCTAssertEqual(token.responseID, "response-1")
        XCTAssertEqual(
            try XCTUnwrap(playout.remainingWait(for: token, now: 50)),
            VoiceTutorLocalPlayoutTailState.spokenEndNetworkTailWindowSeconds,
            accuracy: 0.000_001
        )
        playout.rendered(at: 50.01, duration: 0.01)
        XCTAssertEqual(
            try XCTUnwrap(playout.remainingWait(for: token, now: 50.01)),
            VoiceTutorLocalPlayoutTailState.spokenEndNetworkTailWindowSeconds,
            accuracy: 0.000_001
        )
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

    func testNativeBargeInKeepsBufferedRenderCallbacksFromRevivingInterruptedAnswer() throws {
        var fixture = VoiceTutorContractRenderFixture()
        var duplex = VoiceTutorDuplexPlaybackState()
        var gain = VoiceTutorLocalPlayoutInterruptionState()
        var transcript = VoiceTutorAssistantTranscriptState()
        let bufferedAudio = try makeRenderBuffer(Array(repeating: Int16(100), count: 480))

        XCTAssertTrue(duplex.responseStarted(responseID: "wrong-answer", isTutorIntervention: false))
        XCTAssertTrue(gain.responseStarted("wrong-answer"))
        fixture.response.responseStarted("wrong-answer")
        fixture.response.markOutputBufferStarted("wrong-answer")
        transcript.stageCompletedTranscript("중단되어야 하는 긴 답변")
        fixture.render(bufferedAudio)
        XCTAssertEqual(fixture.phase, .speaking)
        // Generation may already be done while the long output is still queued.
        XCTAssertNil(fixture.response.markResponseDone("wrong-answer"))

        let interruptedID = try XCTUnwrap(duplex.userSpeechStarted())
        XCTAssertTrue(gain.interruptResponse(interruptedID))
        XCTAssertTrue(fixture.response.abandonResponse(interruptedID))
        transcript.discard()
        fixture.phase = .listening
        for _ in 1...50 { fixture.render(bufferedAudio) }
        XCTAssertEqual(fixture.callbackCount, 51, "The renderer must continue consuming buffered audio")
        XCTAssertEqual(fixture.phase, .listening)
        XCTAssertTrue(gain.isMuted)
        XCTAssertNil(fixture.response.markOutputBufferStopped("wrong-answer"))
        XCTAssertNil(fixture.response.markResponseDone("wrong-answer"))
        XCTAssertFalse(duplex.responseFinished(responseID: "wrong-answer"))
        XCTAssertFalse(duplex.responseStarted(responseID: "wrong-answer", isTutorIntervention: false))
        XCTAssertFalse(gain.responseStarted("wrong-answer"))
        XCTAssertNil(transcript.commit())

        duplex.userSpeechStopped()
        XCTAssertTrue(duplex.responseStarted(responseID: "corrected-answer", isTutorIntervention: false))
        XCTAssertTrue(gain.responseStarted("corrected-answer"))
        fixture.response.responseStarted("corrected-answer")
        fixture.response.markOutputBufferStarted("corrected-answer")
        transcript.stageCompletedTranscript("새 질문에 맞춘 답변")
        fixture.render(bufferedAudio)
        XCTAssertEqual(fixture.phase, .speaking)
        XCTAssertFalse(gain.isMuted)
        XCTAssertNil(fixture.response.markOutputBufferStopped("wrong-answer"))
        XCTAssertNil(fixture.response.markResponseDone("wrong-answer"))
        XCTAssertTrue(duplex.matchesActiveResponse(responseID: "corrected-answer"))
        XCTAssertNil(fixture.response.markResponseDone("corrected-answer"))
        let completedID = try XCTUnwrap(fixture.response.markOutputBufferStopped("corrected-answer"))
        XCTAssertTrue(duplex.responseFinished(responseID: completedID))
        XCTAssertEqual(transcript.commit(), "새 질문에 맞춘 답변")
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

    func testWebRTCRendererReportsTheActualPCMQuantumDuration() throws {
        let renderer = VoiceTutorRemoteAudioRenderer()
        let observation = VoiceTutorContractRenderDurationObservation()
        renderer.onRenderedBuffer = { frames, _, duration, _ in
            observation.record(frames: frames, duration: duration)
        }

        renderer.render(pcmBuffer: try makeRenderBuffer(Array(repeating: Int16(0), count: 480)))

        XCTAssertEqual(observation.frames, 480)
        XCTAssertEqual(try XCTUnwrap(observation.duration), 0.01, accuracy: 0.000_001)
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

    func testLearnerInterruptionKeepsProviderCancellationUnderBackendControl() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let sourceURLs = [
            "StudyMate/Services/VoiceTutorAudioEngine.swift",
            "StudyMate/Services/VoiceTutorRealtimeClient.swift",
            "StudyMate/Services/VoiceTutorWebRTCTransport.swift",
            "StudyMate/ViewModels/VoiceTutorViewModel.swift"
        ].map { root.appendingPathComponent($0) }
        #if !targetEnvironment(simulator)
        guard sourceURLs.allSatisfy({ FileManager.default.fileExists(atPath: $0.path) }) else {
            throw XCTSkip("Source-contract check requires the local repository; behavior tests run on iPhone.")
        }
        #endif
        let sources = try sourceURLs.map {
            try String(contentsOf: $0, encoding: .utf8)
        }.joined(separator: "\n")

        XCTAssertFalse(sources.contains("interruptPlayback"))
        XCTAssertTrue(sources.contains("interruptLocalPlayoutResponse"))
        XCTAssertTrue(sources.contains("buddystudy.voice.response.interrupted"))
        XCTAssertFalse(sources.contains("sendBargeIn"))
        XCTAssertFalse(sources.contains("buddystudy.voice.barge-in"))
        XCTAssertFalse(sources.contains("conversation.item.truncate"))
        XCTAssertFalse(sources.contains("response.cancel"))
        XCTAssertFalse(sources.contains("session.update"))
        XCTAssertFalse(sources.contains("dataChannel(forLabel:"))
        XCTAssertFalse(sources.contains(#""type":"output_audio_buffer.clear""#))
        XCTAssertTrue(sources.contains("completionCallbackType: .dataPlayedBack"))
        XCTAssertTrue(sources.contains("sendPlaybackCompleted"))
        XCTAssertTrue(sources.contains("sendPlayoutDrained"))
        XCTAssertTrue(sources.contains("buddystudy.voice.playout.drained"))
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

    func testCompactVoiceCallActionsAndStatusFollowTheConnectionPhase() {
        for language in [AppLanguage.korean, .english, .japanese] {
            let strings = AppStrings(language: language)
            let cases: [(VoiceTutorSessionPhase, VoiceTutorCallPresentation.PrimaryAction, String)] = [
                (.idle, .wait, strings.voiceTutorCallConnecting),
                (.requestingPermission, .end, strings.voiceTutorCallConnecting),
                (.connecting, .end, strings.voiceTutorCallConnecting),
                (.listening, .end, strings.voiceTutorCallListening),
                (.speaking, .end, strings.voiceTutorCallSpeaking),
                (.ending, .wait, strings.voiceTutorCallEnding),
                (.ended, .dismiss, strings.voiceTutorCallEnded),
                (.failed, .retry, strings.voiceTutorCallFailed)
            ]
            for (phase, action, text) in cases {
                let presentation = VoiceTutorCallPresentation(phase: phase)
                XCTAssertEqual(presentation.primaryAction, action, "\(language): \(phase)")
                XCTAssertEqual(presentation.statusText(strings), text, "\(language): \(phase)")
            }
        }
    }

    func testBackendLessonPhasesHaveDistinctLocalizedStatuses() {
        for language in [AppLanguage.korean, .english, .japanese] {
            let strings = AppStrings(language: language)
            let cases: [(VoiceTutorSessionStateEvent.Phase, String)] = [
                (.questionLoading, strings.voiceTutorQuestionLoading),
                (.questionGenerating, strings.voiceTutorQuestionGenerating),
                (.questionReady, strings.voiceTutorQuestionReady),
                (.questionReading, strings.voiceTutorQuestionReading),
                (.answering, strings.voiceTutorAnswerListening),
                (.answerFinalizing, strings.voiceTutorAnswerFinalizing),
                (.answerReview, strings.voiceTutorAnswerReview),
                (.answerSubmitting, strings.voiceTutorAnswerSubmitting),
                (.grading, strings.voiceTutorAnswerGrading),
                (.graded, strings.voiceTutorAnswerGraded),
                (.questionFailed, strings.voiceTutorQuestionFailed),
                (.gradingFailed, strings.voiceTutorGradingFailed),
                (.answerFailed, strings.voiceTutorAnswerFailed),
                (.ending, strings.voiceTutorCallEnding),
                (.ended, strings.voiceTutorCallEnded),
                (.failed, strings.voiceTutorCallFailed)
            ]
            for (phase, label) in cases {
                let presentation = VoiceTutorCallPresentation(
                    phase: .listening, sessionState: makeLessonPresentationState(phase), hasCanonicalAnswerQuestion: true
                )
                XCTAssertEqual(presentation.statusText(strings), label, "\(language) / \(phase)")
                XCTAssertTrue(presentation.needsVisibleStatus(strings, errorMessage: nil))
                if phase == .questionReady {
                    XCTAssertFalse(presentation.orbAnimates, "A ready question must stop the generation pulse")
                    XCTAssertNotEqual(presentation.orbState, .thinking)
                }
            }
            XCTAssertEqual(Set(cases.map(\.1)).count, cases.count, "Lesson phases must remain distinguishable")
        }
    }

    func testBackendLessonProgressNeverMasksLocalConnectionOrPauseTransitions() throws {
        let strings = AppStrings(language: .korean)
        for transport in [VoiceTutorSessionPhase.connecting, .ending, .ended, .failed] {
            let plain = VoiceTutorCallPresentation(phase: transport)
            let withSnapshot = VoiceTutorCallPresentation(
                phase: transport, sessionState: makeLessonPresentationState(.grading)
            )
            XCTAssertEqual(withSnapshot.statusText(strings), plain.statusText(strings))
            XCTAssertEqual(withSnapshot.orbState, plain.orbState)
            XCTAssertFalse(withSnapshot.canDisplayActiveAnswer)
        }
        var pause = VoiceTutorCallPauseState()
        pause.isSupported = true
        let command = try XCTUnwrap(pause.requestPause())
        var presentation = VoiceTutorCallPresentation(
            phase: .listening, pauseState: pause,
            sessionState: makeLessonPresentationState(.answering, paused: true)
        )
        XCTAssertEqual(presentation.statusText(strings), strings.voiceTutorPausing)
        XCTAssertFalse(presentation.canDisplayActiveAnswer)
        _ = pause.acknowledge(sequence: command.sequence, paused: true)
        presentation.pauseState = pause
        XCTAssertEqual(presentation.statusText(strings), strings.voiceTutorPaused)
        _ = try XCTUnwrap(pause.requestResume())
        presentation.pauseState = pause
        XCTAssertEqual(presentation.statusText(strings), strings.voiceTutorResuming)
        XCTAssertEqual(presentation.orbState, .resuming)
        XCTAssertFalse(presentation.canDisplayActiveAnswer)

        let serverPaused = VoiceTutorCallPresentation(
            phase: .listening, sessionState: makeLessonPresentationState(.grading, paused: true)
        )
        XCTAssertEqual(serverPaused.statusText(strings), strings.voiceTutorPaused)
        XCTAssertEqual(serverPaused.orbState, .paused)
        XCTAssertFalse(serverPaused.orbAnimates)
        XCTAssertFalse(serverPaused.canDisplayActiveAnswer)
    }

    func testLocalManualAnswerProgressRemainsVisibleUntilServerAcknowledgesIt() throws {
        let strings = AppStrings(language: .english)
        var draft = VoiceTutorAnswerDraftState()
        let answerID = "d2a27b1e-8924-4c5f-a668-0405b4f50d00"
        XCTAssertTrue(draft.apply(.init(answerID: answerID, studyID: 42, recordID: "101", revision: 1,
                                        phase: .listening, text: nil, code: nil, question: "합성 저장 질문을 설명하세요."), existingDraft: "B, because"))
        var presentation = VoiceTutorCallPresentation(
            phase: .listening, sessionState: makeLessonPresentationState(.questionReading)
        )
        XCTAssertEqual(presentation.statusText(strings, answerDraftState: draft), strings.voiceTutorAnswerListening)
        _ = try XCTUnwrap(draft.requestFinish())
        presentation.sessionState = makeLessonPresentationState(.answering)
        XCTAssertEqual(presentation.statusText(strings, answerDraftState: draft), strings.voiceTutorAnswerFinalizing)
        XCTAssertTrue(draft.apply(.init(answerID: answerID, studyID: 42, recordID: "101", revision: 1,
                                        phase: .review, text: nil, code: nil)))
        XCTAssertEqual(presentation.statusText(strings, answerDraftState: draft), strings.voiceTutorAnswerReview)
        _ = try XCTUnwrap(draft.requestSubmit())
        XCTAssertEqual(presentation.statusText(strings, answerDraftState: draft), strings.voiceTutorAnswerSubmitting)
        XCTAssertTrue(draft.apply(.init(answerID: answerID, studyID: 42, recordID: "101", revision: 1,
                                        phase: .submitted, text: nil, code: nil)))
        presentation.sessionState = makeLessonPresentationState(.grading)
        XCTAssertEqual(presentation.statusText(strings, answerDraftState: draft), strings.voiceTutorAnswerGrading)
        XCTAssertEqual(draft.text, "B, because", "Display snapshots never replace the learner's draft")
    }

    func testGeneralConversationRetainsAudioSpeakingAndListeningWithoutInventingALesson() {
        let strings = AppStrings(language: .english)
        for phase in [VoiceTutorSessionPhase.listening, .speaking] {
            let plain = VoiceTutorCallPresentation(phase: phase)
            let conversation = VoiceTutorCallPresentation(
                phase: phase, sessionState: makeLessonPresentationState(.conversation)
            )
            XCTAssertEqual(conversation.statusText(strings), plain.statusText(strings))
            XCTAssertEqual(conversation.orbState, plain.orbState)
        }
    }

    func testExactInputRetryOverridesStaleQuestionReadingWithoutCompletingTheLesson() {
        var duplex = VoiceTutorDuplexPlaybackState()
        duplex.userSpeechStarted(sequence: 7)
        duplex.userSpeechStopped(sequence: 7)
        XCTAssertTrue(duplex.acceptInputRetry(sequence: 7, responseID: "unannounced_failed"))
        duplex.learnerTranscriptReceived(usesWebRTC: true)
        let snapshot = makeLessonPresentationState(.questionReading)
        for language in [AppLanguage.korean, .english, .japanese] {
            let strings = AppStrings(language: language)
            var presentation = VoiceTutorCallPresentation(phase: .listening,
                inputNeedsRepeat: duplex.inputNeedsRepeat, isAwaitingTutorResponse: duplex.isAwaitingTutorResponse,
                sessionState: snapshot)
            XCTAssertEqual(presentation.statusText(strings), strings.voiceTutorInputRepeat)
            XCTAssertEqual(presentation.orbState, .listening)
            XCTAssertEqual(presentation.lessonPhase, .questionReading)
            XCTAssertEqual(presentation.sessionState, snapshot, "Presentation cannot complete or discard the server's question")

            presentation.phase = .speaking
            XCTAssertEqual(presentation.orbState, .speaking, "Actual playback retains priority over the retry hint")
            presentation.phase = .listening
            presentation.inputNeedsRepeat = false
            XCTAssertEqual(presentation.statusText(strings), strings.voiceTutorQuestionReading)
            XCTAssertEqual(presentation.orbState, .speaking, "Without an explicit retry, the original server snapshot is unchanged")
        }
    }

    func testQuestionReadRetryPreservesPauseAnswerAndTerminalPresentationPriority() throws {
        let strings = AppStrings(language: .korean)
        var pause = VoiceTutorCallPauseState()
        pause.isSupported = true
        let pauseCommand = try XCTUnwrap(pause.requestPause())
        var presentation = VoiceTutorCallPresentation(phase: .listening, inputNeedsRepeat: true,
            pauseState: pause, sessionState: makeLessonPresentationState(.questionReading))
        XCTAssertEqual(presentation.statusText(strings), strings.voiceTutorPausing)
        XCTAssertEqual(presentation.orbState, .pausing)
        XCTAssertTrue(pause.acknowledge(sequence: pauseCommand.sequence, paused: true))
        presentation.pauseState = pause
        XCTAssertEqual(presentation.statusText(strings), strings.voiceTutorPaused)
        XCTAssertEqual(presentation.orbState, .paused)
        XCTAssertFalse(presentation.canDisplayActiveAnswer)
        _ = try XCTUnwrap(pause.requestResume())
        presentation.pauseState = pause
        XCTAssertEqual(presentation.statusText(strings), strings.voiceTutorResuming)
        XCTAssertEqual(presentation.orbState, .resuming)

        let serverPaused = VoiceTutorCallPresentation(phase: .listening, inputNeedsRepeat: true,
            sessionState: makeLessonPresentationState(.questionReading, paused: true))
        XCTAssertEqual(serverPaused.statusText(strings), strings.voiceTutorPaused)
        XCTAssertEqual(serverPaused.orbState, .paused)

        for phase in [VoiceTutorSessionStateEvent.Phase.answering, .answerFinalizing, .answerReview,
                      .answerSubmitting, .grading, .graded, .answerFailed, .gradingFailed, .ending, .ended, .failed] {
            let baseline = VoiceTutorCallPresentation(phase: .listening, sessionState: makeLessonPresentationState(phase))
            var retry = baseline
            retry.inputNeedsRepeat = true
            XCTAssertEqual(retry.statusText(strings), baseline.statusText(strings), "\(phase)")
            XCTAssertEqual(retry.orbState, baseline.orbState, "\(phase)")
        }
        for phase in [VoiceTutorSessionPhase.ending, .ended, .failed] {
            let baseline = VoiceTutorCallPresentation(phase: phase, sessionState: makeLessonPresentationState(.questionReading))
            var retry = baseline
            retry.inputNeedsRepeat = true
            XCTAssertEqual(retry.statusText(strings), baseline.statusText(strings))
            XCTAssertEqual(retry.orbState, baseline.orbState)
        }
    }

    func testLocalAnswerDraftRemainsVisibleOverQuestionReadRetryHint() throws {
        let strings = AppStrings(language: .korean)
        var draft = VoiceTutorAnswerDraftState()
        let answerID = "d2a27b1e-8924-4c5f-a668-0405b4f50d00"
        XCTAssertTrue(draft.apply(.init(answerID: answerID, studyID: 42, recordID: "101", revision: 1,
            phase: .listening, text: nil, code: nil, question: "합성 저장 질문을 설명하세요."), existingDraft: "지켜야 하는 답변 초안"))
        let presentation = VoiceTutorCallPresentation(phase: .listening, inputNeedsRepeat: true,
            sessionState: makeLessonPresentationState(.questionReading))
        XCTAssertEqual(presentation.statusText(strings, answerDraftState: draft), strings.voiceTutorAnswerListening)
        _ = try XCTUnwrap(draft.requestFinish())
        XCTAssertTrue(draft.holdsMicrophone)
        XCTAssertEqual(presentation.statusText(strings, answerDraftState: draft), strings.voiceTutorAnswerFinalizing)
        XCTAssertTrue(draft.apply(.init(answerID: answerID, studyID: 42, recordID: "101", revision: 1,
            phase: .review, text: nil, code: nil)))
        XCTAssertEqual(presentation.statusText(strings, answerDraftState: draft), strings.voiceTutorAnswerReview)
        _ = try XCTUnwrap(draft.requestSubmit())
        XCTAssertEqual(presentation.statusText(strings, answerDraftState: draft), strings.voiceTutorAnswerSubmitting)
        XCTAssertEqual(draft.text, "지켜야 하는 답변 초안")
        XCTAssertEqual(draft.answerID, answerID)
        XCTAssertEqual(presentation.lessonPhase, .questionReading)
    }

    func testAnswerCardFinishRequiresRealDraftAndAdvancesThroughReviewBeforeSubmit() throws {
        let presentation = VoiceTutorCallPresentation(phase: .listening,
            sessionState: makeLessonPresentationState(.questionReading))
        let noInput = VoiceTutorUserInputState()
        var draft = VoiceTutorAnswerDraftState()
        XCTAssertFalse(presentation.canFinishAnswer(draft, userInputState: noInput),
                       "A question-reading snapshot alone cannot invent an answer ID or Finish action")
        let answerID = "d2a27b1e-8924-4c5f-a668-0405b4f50d00"
        XCTAssertTrue(draft.apply(.init(answerID: answerID, studyID: 42, recordID: "101", revision: 1,
            phase: .listening, text: nil, code: nil, question: "합성 저장 질문을 설명하세요."), existingDraft: "직접 작성한 답변"))
        XCTAssertTrue(presentation.canFinishAnswer(draft, userInputState: noInput))
        let finish = try XCTUnwrap(draft.requestFinish())
        XCTAssertEqual(finish.kind, .finish)
        XCTAssertEqual(finish.answerID, answerID)
        XCTAssertEqual(finish.recordID, "101")
        XCTAssertFalse(presentation.canFinishAnswer(draft, userInputState: noInput))
        XCTAssertNil(draft.requestFinish(), "Repeated card taps cannot send another finish while finalizing")
        XCTAssertTrue(draft.holdsMicrophone)
        XCTAssertEqual(presentation.statusText(AppStrings(language: .korean), answerDraftState: draft),
                       AppStrings(language: .korean).voiceTutorAnswerFinalizing)
        XCTAssertTrue(draft.apply(.init(answerID: answerID, studyID: 42, recordID: "101", revision: 1,
            phase: .review, text: nil, code: nil)))
        XCTAssertFalse(presentation.canFinishAnswer(draft, userInputState: noInput))
        XCTAssertTrue(draft.canSubmit)
        XCTAssertEqual(draft.text, "직접 작성한 답변")
        let submit = try XCTUnwrap(draft.requestSubmit())
        XCTAssertEqual(submit.kind, .submit)
        XCTAssertEqual(submit.answerID, answerID)
        XCTAssertEqual(submit.text, "직접 작성한 답변")
        XCTAssertFalse(presentation.canFinishAnswer(draft, userInputState: noInput))
    }

    func testAnswerCardFinishPreservesPauseAndStructuredInputAcknowledgementHolds() throws {
        var draft = VoiceTutorAnswerDraftState()
        XCTAssertTrue(draft.apply(.init(answerID: "d2a27b1e-8924-4c5f-a668-0405b4f50d00", studyID: 42,
            recordID: "101", revision: 1, phase: .listening, text: nil, code: nil, question: "합성 저장 질문을 설명하세요.")))
        var presentation = VoiceTutorCallPresentation(phase: .listening,
            sessionState: makeLessonPresentationState(.questionReading))
        var userInput = VoiceTutorUserInputState()
        presentation.pauseState.isSupported = true
        let pause = try XCTUnwrap(presentation.pauseState.requestPause())
        XCTAssertFalse(presentation.canFinishAnswer(draft, userInputState: userInput))
        XCTAssertTrue(presentation.pauseState.acknowledge(sequence: pause.sequence, paused: true))
        XCTAssertFalse(presentation.canFinishAnswer(draft, userInputState: userInput))
        let resume = try XCTUnwrap(presentation.pauseState.requestResume())
        XCTAssertFalse(presentation.canFinishAnswer(draft, userInputState: userInput))
        XCTAssertTrue(presentation.pauseState.acknowledge(sequence: resume.sequence, paused: false))
        XCTAssertTrue(presentation.canFinishAnswer(draft, userInputState: userInput))

        let request = VoiceTutorUserInputRequest(requestId: "11111111-1111-4111-a111-111111111111",
            sessionId: "22222222-2222-4222-a222-222222222222", attemptId: "33333333-3333-4333-a333-333333333333",
            sequence: 1, title: "선택 확인", questions: [.init(id: "next", prompt: "어떻게 이어갈까요?",
                selectionMode: .text, options: [], allowFreeText: true)])
        XCTAssertTrue(userInput.apply(request, sessionID: request.sessionId))
        XCTAssertFalse(presentation.canFinishAnswer(draft, userInputState: userInput))
        userInput.update(requestID: request.id, answer: .init(questionId: "next", text: "답변을 이어갈게요"))
        _ = try XCTUnwrap(userInput.submit(requestID: request.id, cancel: false))
        XCTAssertFalse(presentation.canFinishAnswer(draft, userInputState: userInput), "Sending the selection must not release its ACK hold")
        XCTAssertTrue(userInput.apply(.init(requestId: request.id, sessionId: request.sessionId,
            attemptId: request.attemptId, sequence: 2, phase: .submitted, errorCode: nil)))
        XCTAssertTrue(presentation.canFinishAnswer(draft, userInputState: userInput))
        presentation.sessionState = makeLessonPresentationState(.questionReading, paused: true)
        XCTAssertFalse(presentation.canFinishAnswer(draft, userInputState: userInput))
        for phase in [VoiceTutorSessionPhase.ending, .ended, .failed, .connecting] {
            presentation.phase = phase
            XCTAssertFalse(presentation.canFinishAnswer(draft, userInputState: userInput), "\(phase)")
        }
        XCTAssertEqual(draft.phase, .listening)
    }

    func testAnswerCardSourceWiresFinishAndKeepsReviewSubmitAndFinalizationStatus() throws {
        let sourceURL = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("StudyMate/Views/VoiceTutorView.swift")
        guard FileManager.default.fileExists(atPath: sourceURL.path) else {
            throw XCTSkip("Source-contract check requires the local repository; answer model/render tests run on iPhone.")
        }
        let source = try String(contentsOf: sourceURL, encoding: .utf8)
        let start = try XCTUnwrap(source.range(of: "private var answerDraftCard:"))
        let end = try XCTUnwrap(source.range(of: "private var supplementaryErrorNotice:", range: start.upperBound..<source.endIndex))
        let card = String(source[start.lowerBound..<end.lowerBound])
        let finish = try XCTUnwrap(card.range(of: "voiceCall.answerFinish"))
        let submit = try XCTUnwrap(card.range(of: "voiceCall.answerSubmit"))
        let busy = try XCTUnwrap(card.range(of: "else if answerDraftIsBusy"))
        XCTAssertLessThan(finish.lowerBound, submit.lowerBound)
        XCTAssertLessThan(submit.lowerBound, busy.lowerBound)
        XCTAssertTrue(card.contains("if answerDraftState.phase == .listening"))
        XCTAssertTrue(card.contains("guard presentation.canFinishAnswer(answerDraftState, userInputState: userInputState)"))
        XCTAssertTrue(card.contains(".disabled(!presentation.canFinishAnswer(answerDraftState, userInputState: userInputState) || didRequestEnd)"))
        XCTAssertTrue(card.contains("onFinishAnswer()"))
        XCTAssertTrue(card.contains("Text(strings.voiceTutorAnswerFinish)"))
        XCTAssertTrue(card.contains("else if answerDraftState.phase == .review || answerDraftState.phase == .failed"))
        XCTAssertTrue(card.contains("Text(strings.voiceTutorAnswerSubmit)"))
        XCTAssertTrue(card.contains("Text(answerDraftStatus)"))
        XCTAssertFalse(card.contains("UUID()"), "A displayed button must never fabricate a server-owned answer identity")
    }

    private func makeLessonPresentationState(
        _ phase: VoiceTutorSessionStateEvent.Phase, paused: Bool = false
    ) -> VoiceTutorSessionState {
        var state = VoiceTutorSessionState()
        XCTAssertTrue(state.apply(.init(
            sequence: 1, phase: phase, paused: paused, revision: 1,
            studyID: 42, recordID: "101", answerID: "d2a27b1e-8924-4c5f-a668-0405b4f50d00"
        )))
        return state
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

        let speaking = VoiceTutorCallPresentation(phase: .speaking)
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
    }

    func testCompactVoiceCallShowsDisconnectionWhileFailureSettlementIsStillPending() {
        for language in [AppLanguage.korean, .english, .japanese] {
            let strings = AppStrings(language: language)
            let presentation = VoiceTutorCallPresentation(phase: .ending)
            let failure = " \n\(strings.voiceTutorConnectionFailed) \n"
            XCTAssertTrue(presentation.showsConnectionFailure(strings, errorMessage: failure))
            XCTAssertEqual(presentation.statusText(strings, errorMessage: failure), strings.voiceTutorCallFailed)
            XCTAssertNil(presentation.supplementaryError(strings, errorMessage: failure))
            XCTAssertEqual(presentation.primaryAction, .wait, "Retry must wait for the original call to settle")
            XCTAssertFalse(presentation.showsConnectionFailure(strings, errorMessage: nil))
            XCTAssertEqual(presentation.statusText(strings), strings.voiceTutorCallEnding)
        }
    }

    func testCompactVoiceCallShowsQuotaExhaustionAsPlannedTerminalState() {
        for language in [AppLanguage.korean, .english, .japanese] {
            let strings = AppStrings(language: language)
            var prearmed = VoiceTutorCallPresentation(
                phase: .ending,
                failureCause: .connection,
                serverEndReason: "QUOTA_EXHAUSTED",
                sessionSecondsRemaining: 8
            )
            XCTAssertEqual(prearmed.statusText(strings), strings.voiceTutorCallEnding)
            XCTAssertEqual(prearmed.remainingTime, .call(8))
            prearmed.sessionSecondsRemaining = 0
            XCTAssertEqual(prearmed.statusText(strings), strings.voiceTutorCallQuotaEnded)
            XCTAssertEqual(prearmed.remainingTime, .call(0))

            let ending = VoiceTutorCallPresentation(
                phase: .ending,
                failureCause: .connection,
                serverEndReason: "QUOTA_EXHAUSTED"
            )
            XCTAssertFalse(
                ending.showsConnectionFailure(
                    strings,
                    errorMessage: strings.voiceTutorConnectionFailed
                )
            )
            XCTAssertEqual(ending.statusText(strings), strings.voiceTutorCallQuotaEnded)
            XCTAssertNil(
                ending.supplementaryError(
                    strings,
                    errorMessage: strings.voiceTutorConnectionFailed
                )
            )
            XCTAssertEqual(ending.primaryAction, .wait)

            let ended = VoiceTutorCallPresentation(
                phase: .ended,
                serverEndReason: "QUOTA_EXHAUSTED",
                quotaRemainingSeconds: 0,
                quotaReservedSeconds: 0,
                quotaLimitSeconds: 3_600
            )
            XCTAssertEqual(ended.statusText(strings), strings.voiceTutorCallQuotaEnded)
            XCTAssertEqual(ended.primaryAction, .dismiss)
            XCTAssertEqual(ended.remainingTime, .monthly(0))

            let defensiveFailure = VoiceTutorCallPresentation(
                phase: .failed,
                failureCause: .connection,
                serverEndReason: "QUOTA_EXHAUSTED"
            )
            XCTAssertEqual(defensiveFailure.orbState, .ended)
            XCTAssertEqual(defensiveFailure.primaryAction, .dismiss)
            XCTAssertEqual(defensiveFailure.statusText(strings), strings.voiceTutorCallQuotaEnded)
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

    func testCompactVoiceCallDistinguishesProviderFailureFromNetworkDisconnection() {
        for language in [AppLanguage.korean, .english, .japanese] {
            let strings = AppStrings(language: language)
            let provider = VoiceTutorCallPresentation(phase: .failed, failureCause: .provider)
            XCTAssertFalse(provider.showsConnectionFailure(strings, errorMessage: strings.serviceTemporarilyUnavailable))
            XCTAssertEqual(provider.statusText(strings), strings.voiceTutorProviderCallFailed)
            XCTAssertEqual(
                provider.supplementaryError(strings, errorMessage: strings.serviceTemporarilyUnavailable),
                strings.serviceTemporarilyUnavailable
            )

            let unavailable = VoiceTutorCallPresentation(phase: .failed, failureCause: .providerUnavailable)
            XCTAssertFalse(unavailable.showsConnectionFailure(strings, errorMessage: strings.serviceTemporarilyUnavailable))
            XCTAssertEqual(unavailable.statusText(strings), strings.voiceTutorCallUnavailable)

            let connection = VoiceTutorCallPresentation(phase: .failed, failureCause: .connection)
            XCTAssertTrue(connection.showsConnectionFailure(strings, errorMessage: strings.voiceTutorConnectionFailed))
            XCTAssertEqual(connection.statusText(strings), strings.voiceTutorCallFailed)
            XCTAssertNil(connection.supplementaryError(strings, errorMessage: strings.voiceTutorConnectionFailed))

            for cause in [
                VoiceTutorFailureCause.microphone, .audio, .localControl, .service, .unknown
            ] {
                let stopped = VoiceTutorCallPresentation(phase: .failed, failureCause: cause)
                XCTAssertFalse(stopped.showsConnectionFailure(strings, errorMessage: nil))
                XCTAssertEqual(stopped.statusText(strings), strings.voiceTutorCallEnded)
            }

            let update = VoiceTutorCallPresentation(phase: .failed, failureCause: .updateRequired)
            XCTAssertEqual(update.statusText(strings), strings.updateRequired)
            XCTAssertEqual(update.primaryAction, .dismiss)

            let rejected = VoiceTutorCallPresentation(phase: .failed, failureCause: .requestRejected)
            XCTAssertEqual(rejected.statusText(strings), strings.voiceTutorCallUnavailable)
            XCTAssertEqual(rejected.primaryAction, .dismiss)
        }
    }

    func testProviderFailureLabelIsLocalized() {
        XCTAssertEqual(AppStrings(language: .korean).voiceTutorProviderCallFailed, "AI 응답 중단됨")
        XCTAssertEqual(AppStrings(language: .english).voiceTutorProviderCallFailed, "AI response stopped")
        XCTAssertEqual(AppStrings(language: .japanese).voiceTutorProviderCallFailed, "AIの応答が中断されました")
        XCTAssertEqual(AppStrings(language: .korean).voiceTutorCallUnavailable, "연결 실패")
        XCTAssertEqual(AppStrings(language: .english).voiceTutorCallUnavailable, "Couldn't connect")
        XCTAssertEqual(AppStrings(language: .japanese).voiceTutorCallUnavailable, "接続できません")
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

    func testEffectivelyUnlimitedVoiceQuotaUsesOnlyTheExactOverrideValue() {
        let unlimited = VoiceTutorQuotaPresentation.unlimitedLimitSeconds
        XCTAssertEqual(unlimited, 31_536_000)
        XCTAssertTrue(VoiceTutorQuotaPresentation.isUnlimited(limitSeconds: unlimited))
        XCTAssertFalse(VoiceTutorQuotaPresentation.isUnlimited(limitSeconds: unlimited - 1))
        XCTAssertFalse(VoiceTutorQuotaPresentation.isUnlimited(limitSeconds: unlimited + 1))

        let expected: [(AppLanguage, String, String)] = [
            (.korean, "무제한", "매월 음성 무제한"),
            (.english, "Unlimited", "Unlimited voice each month"),
            (.japanese, "無制限", "毎月の音声時間は無制限")
        ]
        for (language, remaining, allowance) in expected {
            let strings = AppStrings(language: language)
            XCTAssertEqual(
                strings.voiceTutorRemainingTime(
                    unlimited - 120,
                    limitSeconds: unlimited
                ),
                remaining
            )
            XCTAssertEqual(strings.voiceTutorMonthlyAllowance(unlimited), allowance)
        }

        let ordinary = AppStrings(language: .korean)
        XCTAssertEqual(
            ordinary.voiceTutorRemainingTime(3_480, limitSeconds: 3_600),
            "58분 남음"
        )
        XCTAssertEqual(ordinary.voiceTutorMonthlyAllowance(3_600), "매월 음성 60분")
        XCTAssertEqual(
            VoiceTutorCallPresentation(
                phase: .ended,
                quotaRemainingSeconds: unlimited - 120,
                quotaReservedSeconds: 0,
                quotaLimitSeconds: unlimited
            ).remainingTime,
            .monthlyUnlimited
        )
    }

    func testNewVoiceCallAttemptResetsTranscriptAndSummaryDisclosure() {
        var disclosure = VoiceTutorCallDisclosureState(
            showsTranscript: true,
            showsSummary: true
        )
        disclosure.resetForNewAttempt()
        XCTAssertFalse(disclosure.showsTranscript)
        XCTAssertFalse(disclosure.showsSummary)
    }

    func testVoiceCallRetryResetsDisclosureBeforeStartingTheAttempt() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let sourceURL = root.appendingPathComponent("StudyMate/Views/VoiceTutorView.swift")
        guard FileManager.default.fileExists(atPath: sourceURL.path) else {
            throw XCTSkip("Source-contract check requires the local repository.")
        }
        let source = try String(contentsOf: sourceURL, encoding: .utf8)
        let retryStart = try XCTUnwrap(source.range(of: "onRetry: {"))
        let dismissStart = try XCTUnwrap(
            source.range(of: "onDismiss:", range: retryStart.upperBound..<source.endIndex)
        )
        let retry = String(source[retryStart.lowerBound..<dismissStart.lowerBound])
        let reset = try XCTUnwrap(retry.range(of: "disclosureState.resetForNewAttempt()"))
        let start = try XCTUnwrap(retry.range(of: "viewModel.start()"))
        XCTAssertLessThan(reset.lowerBound, start.lowerBound)
    }

    func testVoiceCallInitialAttemptResetsDisclosureBeforeStarting() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let sourceURL = root.appendingPathComponent("StudyMate/Views/VoiceTutorView.swift")
        guard FileManager.default.fileExists(atPath: sourceURL.path) else {
            throw XCTSkip("Source-contract check requires the local repository.")
        }
        let source = try String(contentsOf: sourceURL, encoding: .utf8)
        let sessionStart = try XCTUnwrap(source.range(of: "struct VoiceTutorSessionView: View"))
        let sessionEnd = try XCTUnwrap(
            source.range(of: "struct VoiceTutorCallAdaptiveLayout", range: sessionStart.upperBound..<source.endIndex)
        )
        let sessionSource = String(source[sessionStart.lowerBound..<sessionEnd.lowerBound])
        let taskStart = try XCTUnwrap(sessionSource.range(of: ".task {"))
        let reset = try XCTUnwrap(
            sessionSource.range(
                of: "disclosureState.resetForNewAttempt()",
                range: taskStart.upperBound..<sessionSource.endIndex
            )
        )
        let start = try XCTUnwrap(
            sessionSource.range(
                of: "await viewModel.start()",
                range: taskStart.upperBound..<sessionSource.endIndex
            )
        )
        XCTAssertLessThan(reset.lowerBound, start.lowerBound)
    }

    func testOrbHoldShowsPreparationThenWarningBeforeEndingExactlyOnce() {
        var gesture = VoiceTutorOrbInteractionState()
        gesture.begin(at: 0)
        for (time, stage) in [(0.349, VoiceTutorOrbInteractionState.HoldStage.idle),
                              (0.35, .anticipating), (0.899, .anticipating),
                              (0.9, .warning), (2.199, .warning)] {
            XCTAssertFalse(gesture.advance(to: time, canEnd: true))
            XCTAssertEqual(gesture.stage, stage)
        }
        XCTAssertLessThan(gesture.progress, 1)
        XCTAssertTrue(gesture.advance(to: 2.2, canEnd: true))
        XCTAssertEqual(gesture.stage, .committed)
        XCTAssertEqual(gesture.progress, 1)
        XCTAssertFalse(gesture.advance(to: 5, canEnd: true), "A held finger cannot send a second hangup")
        XCTAssertFalse(gesture.release(at: 5), "Releasing after hangup must not pause the call")
    }

    func testOrbQuickTapAllowsSmallJitterButHoldingThenReleasingNeverPauses() {
        var tap = VoiceTutorOrbInteractionState()
        tap.begin(at: 0)
        tap.move(translation: CGSize(width: 3, height: 4))
        XCTAssertTrue(tap.release(at: 0.349))
        for release in [0.35, 0.7, 0.9, 1.8, 2.199] {
            var hold = VoiceTutorOrbInteractionState()
            hold.begin(at: 0)
            XCTAssertFalse(hold.advance(to: release, canEnd: true))
            XCTAssertFalse(hold.release(at: release), "Cancelled hold at \(release) must not become a pause")
            XCTAssertFalse(hold.isActive)
            XCTAssertEqual(hold.progress, 0)
        }
    }

    func testOrbSwipeCancelsHoldEvenIfTheFingerReturnsToItsStartingPoint() {
        for translation in [CGSize(width: 12, height: 0), CGSize(width: 0, height: -12),
                            CGSize(width: 9, height: 9), CGSize(width: 0, height: 100)] {
            var gesture = VoiceTutorOrbInteractionState()
            gesture.begin(at: 0)
            gesture.advance(to: 1, canEnd: true)
            gesture.move(translation: translation)
            gesture.move(translation: .zero)
            XCTAssertTrue(gesture.hasMoved)
            XCTAssertEqual(gesture.stage, .idle)
            XCTAssertEqual(gesture.progress, 0)
            XCTAssertFalse(gesture.advance(to: 10, canEnd: true))
            XCTAssertFalse(gesture.release(at: 10))
        }
    }

    func testOrbSystemCancellationAndUnavailableHangupCannotEndTheCall() {
        var cancelled = VoiceTutorOrbInteractionState()
        cancelled.begin(at: 0)
        cancelled.advance(to: 1, canEnd: true)
        cancelled.cancel()
        XCTAssertFalse(cancelled.advance(to: 10, canEnd: true))
        XCTAssertFalse(cancelled.release(at: 10))

        var unavailable = VoiceTutorOrbInteractionState()
        unavailable.begin(at: 0)
        XCTAssertFalse(unavailable.advance(to: 10, canEnd: false))
        XCTAssertFalse(unavailable.didCommitEnd)
        unavailable.cancel()
        XCTAssertFalse(unavailable.advance(to: 11, canEnd: true))
    }

    func testOrbNewTouchCannotReusePreviousHoldProgressOrRestartAnActiveHold() {
        var gesture = VoiceTutorOrbInteractionState()
        gesture.begin(at: 0)
        gesture.advance(to: 1, canEnd: true)
        gesture.begin(at: 1)
        XCTAssertEqual(gesture.startedAt, 0)
        XCTAssertFalse(gesture.release(at: 1.2))
        gesture.begin(at: 10)
        XCTAssertEqual(gesture.stage, .idle)
        XCTAssertEqual(gesture.progress, 0)
        XCTAssertFalse(gesture.advance(to: 10.1, canEnd: true))
        XCTAssertTrue(gesture.release(at: 10.1))
    }

    func testOrbMalformedClockAndMovementCannotAuthorizeAnEndOrTap() {
        var gesture = VoiceTutorOrbInteractionState()
        gesture.begin(at: .nan)
        XCTAssertFalse(gesture.isActive)
        gesture.begin(at: 10)
        XCTAssertFalse(gesture.advance(to: .infinity, canEnd: true))
        XCTAssertFalse(gesture.advance(to: 9, canEnd: true))
        gesture.move(translation: CGSize(width: CGFloat.nan, height: 0))
        XCTAssertFalse(gesture.advance(to: 20, canEnd: true))
        XCTAssertFalse(gesture.release(at: 10.1))
    }

    func testVoiceCallOrbSwipeRoutingPreservesTapAndHorizontalMovement() {
        typealias Routing = VoiceTutorOrbGestureRouting

        XCTAssertEqual(
            Routing.transcriptAction(for: CGSize(width: 4, height: -80), showsTranscript: false),
            .reveal
        )
        XCTAssertEqual(
            Routing.transcriptAction(for: CGSize(width: 3, height: 72), showsTranscript: true),
            .hide
        )
        XCTAssertNil(
            Routing.transcriptAction(for: .zero, showsTranscript: false),
            "A tap must remain available to pause or resume the call"
        )
        XCTAssertNil(
            Routing.transcriptAction(for: CGSize(width: 2, height: -20), showsTranscript: false),
            "Small finger movement during a tap must not reveal the transcript"
        )
        XCTAssertNil(
            Routing.transcriptAction(for: CGSize(width: 90, height: -50), showsTranscript: false),
            "Horizontal movement must not be interpreted as transcript navigation"
        )
        XCTAssertNil(
            Routing.transcriptAction(for: CGSize(width: 0, height: 80), showsTranscript: false),
            "Only an upward swipe reveals a hidden transcript"
        )
        XCTAssertNil(
            Routing.transcriptAction(for: CGSize(width: 0, height: -80), showsTranscript: true),
            "Only a downward swipe hides a visible transcript"
        )
    }

    func testVoiceCallDisclosureTracksTheFingerInBothDirections() {
        typealias Routing = VoiceTutorOrbGestureRouting
        XCTAssertEqual(Routing.expansion(
            for: CGSize(width: 2, height: -75), startsExpanded: false, travel: 300
        ), 0.25, accuracy: 0.001)
        XCTAssertEqual(Routing.expansion(
            for: CGSize(width: 2, height: 75), startsExpanded: true, travel: 300
        ), 0.75, accuracy: 0.001)
        XCTAssertEqual(Routing.expansion(
            for: CGSize(width: 0, height: -900), startsExpanded: false, travel: 300
        ), 1)
        XCTAssertEqual(Routing.expansion(
            for: CGSize(width: 0, height: 900), startsExpanded: true, travel: 300
        ), 0)
        XCTAssertEqual(Routing.expansion(
            for: CGSize(width: 90, height: -50), startsExpanded: false, travel: 300
        ), 0, "Horizontal movement must not reveal chat")
        XCTAssertEqual(Routing.expansion(
            for: CGSize(width: 0, height: -50), startsExpanded: false, travel: 0
        ), 0)
    }

    func testOrbCoalescedSwipeUsesItsReleaseLocationWithoutAnIntermediateMove() {
        typealias Routing = VoiceTutorOrbGestureRouting
        XCTAssertEqual(Routing.releaseAction(
            for: CGSize(width: 0, height: -297),
            predictedTranslation: CGSize(width: 0, height: -297), showsTranscript: false
        ), .reveal)
        XCTAssertEqual(Routing.releaseAction(
            for: CGSize(width: 0, height: 240),
            predictedTranslation: CGSize(width: 0, height: 240), showsTranscript: true
        ), .hide)
        XCTAssertNil(Routing.releaseAction(for: .zero, predictedTranslation: .zero, showsTranscript: false))
        XCTAssertNil(Routing.releaseAction(
            for: CGSize(width: 90, height: -30),
            predictedTranslation: CGSize(width: 100, height: -100), showsTranscript: false
        ))
        XCTAssertNil(Routing.releaseAction(
            for: CGSize(width: CGFloat.nan, height: -90),
            predictedTranslation: CGSize(width: 0, height: -100), showsTranscript: false
        ))
    }

    func testVoiceCallShortFlickFinishesButTapAndReversedMovementDoNot() {
        typealias Routing = VoiceTutorOrbGestureRouting
        XCTAssertTrue(Routing.settlesExpanded(
            translation: CGSize(width: 1, height: -22),
            predictedTranslation: CGSize(width: 2, height: -90), startsExpanded: false
        ))
        XCTAssertFalse(Routing.settlesExpanded(
            translation: CGSize(width: 1, height: 22),
            predictedTranslation: CGSize(width: 2, height: 90), startsExpanded: true
        ))
        XCTAssertFalse(Routing.settlesExpanded(
            translation: CGSize(width: 0, height: -8),
            predictedTranslation: CGSize(width: 0, height: -100), startsExpanded: false
        ), "A tap cannot become a disclosure flick from prediction alone")
        XCTAssertFalse(Routing.settlesExpanded(
            translation: CGSize(width: 0, height: -22),
            predictedTranslation: CGSize(width: 0, height: 90), startsExpanded: false
        ), "Reversing before release settles back to the current mode")
    }

    func testVoiceCallUsesOneOrbAlongAContinuousLayoutPath() {
        let compact = CGRect(x: 100, y: 280, width: 152, height: 152)
        let transcript = CGRect(x: 310, y: 12, width: 48, height: 48)
        XCTAssertEqual(VoiceTutorOrbGestureRouting.orbFrame(
            from: compact, to: transcript, expansion: 0
        ), compact)
        XCTAssertEqual(VoiceTutorOrbGestureRouting.orbFrame(
            from: compact, to: transcript, expansion: 1
        ), transcript)
        XCTAssertEqual(VoiceTutorOrbGestureRouting.orbFrame(
            from: compact, to: transcript, expansion: 0.5
        ), CGRect(x: 205, y: 146, width: 100, height: 100))
    }

    func testTranscriptFollowingIgnoresContentGrowthButPausesDuringUserInteraction() {
        var state = VoiceTutorTranscriptFollowState()
        let bottom = CGRect(x: 0, y: -250, width: 360, height: 610)
        let appendedWithoutOffsetMovement = CGRect(x: 0, y: -250, width: 360, height: 690)

        state.observeLayout(contentFrame: bottom, viewportHeight: 360)
        XCTAssertTrue(state.followsLatest)
        XCTAssertTrue(state.shouldAutoScrollForContentChange)

        state.beginUserInteraction()
        state.observeLayout(contentFrame: appendedWithoutOffsetMovement, viewportHeight: 360)
        XCTAssertTrue(state.followsLatest, "Appending a draft changes height, not the learner-owned offset")
        XCTAssertFalse(state.shouldAutoScrollForContentChange, "New deltas must not fight an active scroll")
        XCTAssertTrue(state.endUserInteraction())
        XCTAssertTrue(state.shouldAutoScrollForContentChange)
    }

    func testTranscriptContentAutoFollowWaitsForLayoutAndRechecksLearnerIntent() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let sourceURL = root.appendingPathComponent("StudyMate/Views/VoiceTutorView.swift")
        guard FileManager.default.fileExists(atPath: sourceURL.path) else {
            throw XCTSkip("Source-contract check requires the local repository.")
        }
        let source = try String(contentsOf: sourceURL, encoding: .utf8)
        let methodStart = try XCTUnwrap(source.range(of: "private func scheduleTranscriptAutoScroll(using proxy:"))
        let methodEnd = try XCTUnwrap(
            source.range(
                of: "private func scheduleTranscriptScrollSettlement(using proxy:",
                range: methodStart.upperBound..<source.endIndex
            )
        )
        let method = String(source[methodStart.lowerBound..<methodEnd.lowerBound])

        XCTAssertTrue(method.contains("await Task.yield()"), "The latest anchor must exist before scrolling")
        XCTAssertGreaterThanOrEqual(
            method.components(separatedBy: "transcriptFollowState.shouldAutoScrollForContentChange").count - 1,
            2,
            "Auto-follow eligibility must be checked both before scheduling and after layout"
        )
        XCTAssertTrue(method.contains("animated && showsTranscript"), "Hidden appends need no animation")
        XCTAssertFalse(method.contains("guard showsTranscript"), "The mounted transcript must already be at the latest edge before a reveal drag begins")
        XCTAssertTrue(method.contains("guard !Task.isCancelled"))
        XCTAssertTrue(source.contains(".onChange(of: showsTranscript) { _, isShowingTranscript in"))

        var state = VoiceTutorTranscriptFollowState()
        XCTAssertTrue(state.shouldAutoScrollForContentChange)
        state.beginUserInteraction()
        XCTAssertFalse(
            state.shouldAutoScrollForContentChange,
            "A user who starts reading history while a scroll is queued must win the re-check"
        )
    }

    func testTranscriptFollowingCannotSettleWhileTheFingerIsStillDown() {
        var state = VoiceTutorTranscriptFollowState()
        state.beginUserInteraction()

        XCTAssertFalse(
            state.shouldScheduleSettlement(isGestureActive: true),
            "A stationary finger is still an active drag and must keep caption auto-scroll suspended"
        )
        XCTAssertTrue(
            state.shouldScheduleSettlement(isGestureActive: false),
            "Only the gesture ending may start the deceleration settlement debounce"
        )
    }

    func testTranscriptFollowingStopsForOlderHistoryAndResumesOnlyAtLatestEdge() {
        var state = VoiceTutorTranscriptFollowState()
        let bottom = CGRect(x: 0, y: -250, width: 360, height: 610)
        let olderHistory = CGRect(x: 0, y: -190, width: 360, height: 610)

        state.observeLayout(contentFrame: bottom, viewportHeight: 360)
        state.beginUserInteraction()
        state.observeLayout(contentFrame: olderHistory, viewportHeight: 360)
        XCTAssertFalse(state.followsLatest)
        XCTAssertFalse(state.endUserInteraction())
        XCTAssertFalse(state.shouldAutoScrollForContentChange)

        state.beginUserInteraction()
        state.observeLayout(contentFrame: CGRect(x: 0, y: -220, width: 360, height: 610), viewportHeight: 360)
        XCTAssertFalse(state.followsLatest, "Approaching the end is not the same as reaching it")
        state.observeLayout(contentFrame: bottom, viewportHeight: 360)
        XCTAssertTrue(state.followsLatest)
        XCTAssertTrue(state.endUserInteraction())
        XCTAssertTrue(state.shouldAutoScrollForContentChange)
    }

    func testTranscriptFollowingRecognizesVoiceOverOffsetChangesWithoutADragGesture() {
        var state = VoiceTutorTranscriptFollowState()
        let bottom = CGRect(x: 0, y: -250, width: 360, height: 610)
        let olderHistory = CGRect(x: 0, y: -205, width: 360, height: 610)

        state.observeLayout(contentFrame: bottom, viewportHeight: 360)
        state.observeLayout(contentFrame: olderHistory, viewportHeight: 360)
        XCTAssertFalse(state.followsLatest)
        XCTAssertFalse(state.shouldAutoScrollForContentChange)
        state.observeLayout(contentFrame: bottom, viewportHeight: 360)
        XCTAssertTrue(state.followsLatest)
        XCTAssertTrue(state.shouldAutoScrollForContentChange)
    }

    func testTranscriptNavigationLabelsDescribeFullScreenActions() {
        let expected = [
            (AppLanguage.korean, "전체 대화 보기", "대화로 돌아가기", "최근 대화"),
            (AppLanguage.english, "View full conversation", "Return to conversation", "Latest conversation"),
            (AppLanguage.japanese, "会話を全画面で見る", "対話に戻る", "最近の会話")
        ]
        for (language, reveal, collapse, latest) in expected {
            let strings = AppStrings(language: language)
            XCTAssertEqual(strings.voiceTutorCallRevealConversation, reveal)
            XCTAssertEqual(strings.voiceTutorCallCollapseConversation, collapse)
            XCTAssertEqual(strings.voiceTutorCallLatestConversation, latest)
            XCTAssertFalse(strings.voiceTutorCallRevealConversation.lowercased().contains("swipe"))
        }

        let gestureHints = [
            (AppLanguage.korean, "위로 쓸어 전체 대화를 봅니다.", "아래로 쓸어 대화 화면으로 돌아갑니다."),
            (AppLanguage.english, "Swipe up to view the full conversation.", "Swipe down to return to the conversation."),
            (AppLanguage.japanese, "上にスワイプすると会話全体を表示します。", "下にスワイプすると対話画面に戻ります。")
        ]
        for (language, reveal, hide) in gestureHints {
            let strings = AppStrings(language: language)
            XCTAssertEqual(strings.voiceTutorOrbRevealConversationHint, reveal)
            XCTAssertEqual(strings.voiceTutorOrbHideConversationHint, hide)
        }
    }

    func testTranscriptLatestEdgeDetectionIsBoundedAndFailsClosed() {
        typealias Interaction = VoiceTutorTranscriptInteraction
        XCTAssertTrue(Interaction.transcriptIsAtLatest(
            contentFrame: CGRect(x: 0, y: -250, width: 360, height: 610),
            viewportHeight: 360
        ))
        XCTAssertTrue(Interaction.transcriptIsAtLatest(
            contentFrame: CGRect(x: 0, y: 0, width: 360, height: 180),
            viewportHeight: 360
        ), "Short transcripts are already at their latest edge")
        XCTAssertTrue(Interaction.transcriptIsAtLatest(
            contentFrame: CGRect(x: 0, y: -242, width: 360, height: 610),
            viewportHeight: 360
        ), "Small bounce/layout differences stay inside the fixed edge tolerance")
        XCTAssertFalse(Interaction.transcriptIsAtLatest(
            contentFrame: CGRect(x: 0, y: -200, width: 360, height: 610),
            viewportHeight: 360
        ), "A learner who has scrolled up must retain normal transcript scrolling")
        XCTAssertFalse(Interaction.transcriptIsAtLatest(
            contentFrame: .null,
            viewportHeight: 360
        ))
        XCTAssertFalse(Interaction.transcriptIsAtLatest(
            contentFrame: CGRect(x: 0, y: 0, width: 360, height: 180),
            viewportHeight: 0
        ))
    }

    func testCallUsesAnIntegratedFullScreenTranscriptAndNoUserMuteControl() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let sourceURL = root.appendingPathComponent("StudyMate/Views/VoiceTutorView.swift")
        guard FileManager.default.fileExists(atPath: sourceURL.path) else {
            throw XCTSkip("Source-contract check requires the local repository; render tests run on iPhone.")
        }
        let source = try String(contentsOf: sourceURL, encoding: .utf8)
        let compactStart = try XCTUnwrap(source.range(of: "private func compactCall(in geometry:"))
        let transcriptStart = try XCTUnwrap(source.range(of: "private var fullScreenTranscript:"))
        let orbStart = try XCTUnwrap(source.range(of: "private func callOrb(diameter:"))
        let compact = String(source[compactStart.lowerBound..<transcriptStart.lowerBound])
        let transcript = String(source[transcriptStart.lowerBound..<orbStart.lowerBound])

        XCTAssertTrue(compact.contains("answerOrbPlaceholder(.call, diameter:"))
        XCTAssertTrue(compact.contains("Text(displayTopic)"))
        XCTAssertTrue(compact.contains("callTime"))
        XCTAssertFalse(compact.contains("transcriptPanel"))
        XCTAssertFalse(compact.contains("voiceCall.liveTranscript"))
        XCTAssertFalse(compact.contains("integratedConversationPreview"))

        XCTAssertTrue(transcript.contains("transcriptPanel"))
        XCTAssertTrue(transcript.contains("transcriptHeader"))
        XCTAssertTrue(transcript.contains("maxHeight: .infinity"))
        XCTAssertTrue(transcript.contains("voiceCall.fullTranscript"))
        XCTAssertFalse(transcript.contains("Color(uiColor: .systemBackground)"), "One stable screen background must remain behind both fading layouts")
        XCTAssertFalse(transcript.contains("UnevenRoundedRectangle"))
        XCTAssertFalse(transcript.contains("shadow("))
        XCTAssertTrue(transcript.contains("answerOrbPlaceholder(.transcript, diameter: hasLessonOrbContent ? (usesAccessibilityChrome ? 96 : 72) : (usesAccessibilityChrome ? 56 : 48))"))
        let placeholderStart = try XCTUnwrap(source.range(of: "private func answerOrbPlaceholder("))
        let placeholderEnd = try XCTUnwrap(source.range(of: "private var showsAnswerPauseControl", range: placeholderStart.upperBound..<source.endIndex))
        let placeholder = String(source[placeholderStart.lowerBound..<placeholderEnd.lowerBound])
        XCTAssertTrue(placeholder.contains("orbPlaceholder(placement, diameter: diameter)"),
                      "Both layouts must still publish their orb anchor through the shared answer controls helper")
        XCTAssertTrue(placeholder.contains("placement == .call ? .callPause : .transcriptPause"),
                      "The optional answer-pause companion must move with the same two layout anchors")
        XCTAssertTrue(source.contains("interactionDock"))
        XCTAssertTrue(source.contains("stableCallControls"))
        XCTAssertFalse(source.contains("Button(action: onPause)"), "The circle owns taps, swipes and holds through one recognizer")
        XCTAssertTrue(source.contains("DragGesture(minimumDistance: 0, coordinateSpace: .global)"))
        XCTAssertTrue(source.contains("orbInteraction.release(at:"))
        XCTAssertTrue(source.contains("case .end, .wait: EmptyView()"), "The stable dock must not duplicate the live orb and answer-pause controls")
        XCTAssertTrue(source.contains("VoiceTutorOrbGestureRouting.expansion"))
        XCTAssertTrue(source.contains("VoiceTutorOrbGestureRouting.settlesExpanded"))
        XCTAssertTrue(source.contains(".highPriorityGesture(orbTranscriptGesture("))
        XCTAssertFalse(source.contains("voiceCall.openTranscript"), "A separate live transcript button is unnecessary")
        XCTAssertTrue(source.contains("voiceCall.collapseTranscript"))
        XCTAssertTrue(source.contains(".offset(y: reduceMotion ? 0 : min(48, geometry.size.height * 0.08) * (1 - expansion))"))
        XCTAssertTrue(source.contains(".interactiveSpring(response: 0.34, dampingFraction: 1)"))
        XCTAssertTrue(source.contains("coordinateSpace: .global"), "The moving orb must not move its gesture origin")
        XCTAssertTrue(source.contains("reduceMotion"))
        XCTAssertFalse(source.contains("voiceCall.mute"))
        XCTAssertFalse(source.contains("onMute"))
        XCTAssertFalse(source.contains("transcriptSheet"))
        XCTAssertFalse(source.contains("transcriptSheetGesture"))
        XCTAssertTrue(source.contains("LazyVStack(alignment: .leading"))
        XCTAssertTrue(source.contains(".onChange(of: captions.last?.id)"))
        XCTAssertTrue(source.contains(".onChange(of: assistantTranscriptDraft)"))
        XCTAssertTrue(source.contains("scheduleTranscriptAutoScroll(using: proxy)"))
        XCTAssertTrue(source.contains(".simultaneousGesture(transcriptFollowGesture"))

        let presentationStart = try XCTUnwrap(source.range(of: "struct VoiceTutorCallPresentation"))
        let presentationEnd = try XCTUnwrap(
            source.range(of: "struct VoiceTutorTranscriptInteraction", range: presentationStart.upperBound..<source.endIndex)
        )
        let presentationSource = String(source[presentationStart.lowerBound..<presentationEnd.lowerBound])
        XCTAssertFalse(presentationSource.contains("isMuted"))
        XCTAssertFalse(presentationSource.contains("canMute"))
        XCTAssertFalse(presentationSource.contains("voiceTutorCallMuted"))

        let callScreenStart = try XCTUnwrap(source.range(of: "struct VoiceTutorCallScreen"))
        let callScreenEnd = try XCTUnwrap(
            source.range(of: "private struct VoiceTutorCaptionBubble", range: callScreenStart.upperBound..<source.endIndex)
        )
        let callScreenSource = String(source[callScreenStart.lowerBound..<callScreenEnd.lowerBound])
        // Pause icons, hints and the optional companion may crossfade. Only the
        // disclosure surfaces must remain mounted to preserve transcript scroll.
        XCTAssertFalse(transcript.contains(".transition("), "The transcript surface must not be replaced during disclosure")
        let surfaceStart = try XCTUnwrap(callScreenSource.range(of: "ZStack {"))
        let surfaceEnd = try XCTUnwrap(callScreenSource.range(of: ".overlayPreferenceValue(", range: surfaceStart.upperBound..<callScreenSource.endIndex))
        let surfaces = String(callScreenSource[surfaceStart.lowerBound..<surfaceEnd.lowerBound])
        XCTAssertTrue(surfaces.contains("compactCall(in: geometry)"))
        XCTAssertTrue(surfaces.contains("fullScreenTranscript"))
        XCTAssertFalse(surfaces.contains("if showsTranscript"), "Disclosure must keep the transcript mounted in the same ZStack slot")
        XCTAssertFalse(surfaces.contains("if !showsTranscript"), "Both disclosure surfaces must remain mounted")
        XCTAssertFalse(surfaces.contains(".transition("))
        XCTAssertFalse(surfaces.contains(".id("), "Disclosure must not reset a surface's SwiftUI identity")
        XCTAssertEqual(callScreenSource.components(separatedBy: "callOrb(diameter:").count - 1, 2,
                       "One orb call site plus its definition; compact and transcript layouts only provide anchors")
        let disclosureStart = try XCTUnwrap(callScreenSource.range(of: "private func setTranscriptExpanded"))
        let disclosureEnd = try XCTUnwrap(callScreenSource.range(of: "private var disclosureAnimation"))
        let disclosure = String(callScreenSource[disclosureStart.lowerBound..<disclosureEnd.lowerBound])
        XCTAssertFalse(disclosure.contains("resetTranscriptInteraction"), "Hiding/revealing must preserve reading position")
        XCTAssertFalse(
            callScreenSource.contains(".dynamicTypeSize("),
            "The call screen must respect AX2-AX5 instead of forcing a smaller text category"
        )
        XCTAssertTrue(callScreenSource.contains(".accessibilityAction(named: Text(orbActionLabel))"))
    }

    func testCallChromeStacksAtEveryAccessibilityTextCategory() {
        let accessibilitySizes: [DynamicTypeSize] = [
            .accessibility1, .accessibility2, .accessibility3, .accessibility4, .accessibility5
        ]
        for size in accessibilitySizes {
            XCTAssertTrue(
                VoiceTutorCallAdaptiveLayout.usesAccessibilityChrome(for: size),
                "Essential call controls must use the reachable vertical layout at \(size)"
            )
        }

        let standardSizes: [DynamicTypeSize] = [
            .xSmall, .small, .medium, .large, .xLarge, .xxLarge, .xxxLarge
        ]
        for size in standardSizes {
            XCTAssertFalse(VoiceTutorCallAdaptiveLayout.usesAccessibilityChrome(for: size))
        }
    }

    func testProviderResponseRecoveryDiscardsOnlyPartialTutorStateWithoutStoppingTheCall() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let sourceURL = root.appendingPathComponent("StudyMate/ViewModels/VoiceTutorViewModel.swift")
        #if !targetEnvironment(simulator)
        guard FileManager.default.fileExists(atPath: sourceURL.path) else {
            throw XCTSkip("Source-contract check requires the local repository; behavior tests run on iPhone.")
        }
        #endif
        let source = try String(
            contentsOf: sourceURL,
            encoding: .utf8
        )
        let retryStart = try XCTUnwrap(source.range(of: "case .responseStarted("))
        let retryEnd = try XCTUnwrap(source.range(of: "case .responseFinished", range: retryStart.upperBound..<source.endIndex))
        let retry = String(source[retryStart.lowerBound..<retryEnd.lowerBound])
        XCTAssertTrue(retry.contains("duplexPlaybackState.assistantResponseActive"))
        XCTAssertTrue(retry.contains("assistantTranscriptState.discard()"))

        let transcriptDoneStart = try XCTUnwrap(source.range(of: "case .assistantTranscriptDone"))
        let transcriptDoneEnd = try XCTUnwrap(source.range(of: "case .userTranscript", range: transcriptDoneStart.upperBound..<source.endIndex))
        let transcriptDone = String(source[transcriptDoneStart.lowerBound..<transcriptDoneEnd.lowerBound])
        XCTAssertTrue(transcriptDone.contains("assistantTranscriptState.stageCompletedTranscript"))
        XCTAssertFalse(transcriptDone.contains("commitAssistantTranscript"))

        let responseFinishedStart = try XCTUnwrap(source.range(of: "case .responseFinished(let responseID)"))
        let responseFinishedEnd = try XCTUnwrap(source.range(of: "case .outputAudioBufferStarted", range: responseFinishedStart.upperBound..<source.endIndex))
        let responseFinished = String(source[responseFinishedStart.lowerBound..<responseFinishedEnd.lowerBound])
        XCTAssertTrue(responseFinished.contains("if usesWebRTC"))
        XCTAssertTrue(responseFinished.contains("finishWebRTCResponseIfReady"))
        XCTAssertTrue(responseFinished.contains("else if let responseID"))
        XCTAssertTrue(responseFinished.contains("commitAssistantTranscript(responseID: responseID)"))

        let webRTCFinishStart = try XCTUnwrap(source.range(of: "private func finishWebRTCResponseIfReady"))
        let webRTCFinishEnd = try XCTUnwrap(source.range(of: "private func abandonProviderTurn", range: webRTCFinishStart.upperBound..<source.endIndex))
        let webRTCFinish = String(source[webRTCFinishStart.lowerBound..<webRTCFinishEnd.lowerBound])
        XCTAssertTrue(webRTCFinish.contains("duplexPlaybackState.responseFinished"))
        XCTAssertTrue(webRTCFinish.contains("commitAssistantTranscript(responseID: responseID)"))

        let clearStart = try XCTUnwrap(source.range(of: "case .outputAudioBufferCleared(let responseID)"))
        let clearEnd = try XCTUnwrap(source.range(of: "case .ignored", range: clearStart.upperBound..<source.endIndex))
        let clear = String(source[clearStart.lowerBound..<clearEnd.lowerBound])
        XCTAssertTrue(clear.contains("abandonProviderTurn(responseID: responseID)"))
        XCTAssertFalse(clear.contains("await stop"))

        let abandonStart = try XCTUnwrap(source.range(of: "private func abandonProviderTurn(responseID:"))
        let abandonEnd = try XCTUnwrap(source.range(of: "private func finishFromServer", range: abandonStart.upperBound..<source.endIndex))
        let abandon = String(source[abandonStart.lowerBound..<abandonEnd.lowerBound])
        XCTAssertTrue(abandon.contains("duplexPlaybackState.abandonResponse"))
        XCTAssertTrue(abandon.contains("webRTCResponseState.abandonResponse"))
        XCTAssertTrue(abandon.contains("abandonLocalPlayoutResponse"))
        XCTAssertTrue(abandon.contains("phase = .listening"))
        XCTAssertFalse(abandon.contains("await stop"))
    }

    func testFailedTutorTranscriptIsDiscardedBeforeReplacementResponseCommits() {
        var transcript = VoiceTutorAssistantTranscriptState()

        transcript.append(delta: "실패한 일부")
        transcript.stageCompletedTranscript("실패한 일부 문장")
        XCTAssertEqual(transcript.draft, "실패한 일부 문장")

        // A failed response never calls commit. The retry boundary discards its
        // provisional transcript before any replacement deltas arrive.
        transcript.discard()
        transcript.append(delta: "정상 대체")
        transcript.stageCompletedTranscript("정상 대체 문장")

        XCTAssertEqual(transcript.commit(), "정상 대체 문장")
        XCTAssertEqual(transcript.draft, "")
        XCTAssertNil(transcript.commit())
    }

    func testIntentionalInterruptionRetainsVisibleTextPriorMessagesAndExactOperationAnchor() throws {
        let learner = VoiceTutorCaption(speaker: .learner, text: "먼저 한 요청", providerItemID: "learner_first")
        let completed = VoiceTutorCaption(speaker: .tutor, text: "이미 끝난 설명", responseID: "response_completed")
        var captions = [learner, completed]
        var transcript = VoiceTutorAssistantTranscriptState()
        transcript.beginResponse("response_interrupted")
        transcript.append(delta: "말하는 도중 화면에 보이던 부분")
        var operations = VoiceTutorOperationState()
        XCTAssertTrue(operations.applyContext(.init(operationID: "read_origin", responseID: "response_interrupted")))
        XCTAssertTrue(operations.apply(.init(sequence: 1, operationID: "read_origin", name: "list_studies",
            phase: .completed, elapsedMilliseconds: 100), at: 1, afterCaptionID: completed.id))
        XCTAssertTrue(transcript.retainInterruptedCaption(responseID: "response_interrupted",
            providerItemID: "tutor_partial", in: &captions))
        XCTAssertEqual(Array(captions.prefix(2)), [learner, completed], "Interruption cannot remove or rewrite earlier dialogue")
        let retained = try XCTUnwrap(captions.last)
        XCTAssertEqual(retained.text, "말하는 도중 화면에 보이던 부분")
        XCTAssertEqual(retained.responseID, "response_interrupted")
        XCTAssertEqual(retained.providerItemID, "tutor_partial")
        XCTAssertTrue(retained.isInterrupted)
        XCTAssertFalse(completed.isInterrupted)
        XCTAssertEqual(transcript.draft, "")
        XCTAssertNil(transcript.responseID)
        let layout = VoiceTutorOperationTranscriptLayout(entries: operations.visibleEntries(at: 1), captions: captions,
            assistantResponseID: nil, hasAssistantDraft: false)
        XCTAssertEqual(layout.byCaptionID[retained.id]?.map(\.id), ["read_origin"])
        XCTAssertNil(layout.byCaptionID[completed.id])
    }

    func testLocalThenServerInterruptionAndLateCompletionCannotDuplicateOrUpgradeRetainedText() throws {
        var transcript = VoiceTutorAssistantTranscriptState()
        var duplex = VoiceTutorDuplexPlaybackState()
        var response = VoiceTutorWebRTCResponseState()
        var captions: [VoiceTutorCaption] = []
        transcript.beginResponse("partial")
        transcript.append(delta: "중단 전에 표시된 내용")
        XCTAssertTrue(duplex.responseStarted(responseID: "partial", isTutorIntervention: false))
        response.responseStarted("partial")
        XCTAssertTrue(transcript.retainInterruptedCaption(responseID: "partial", providerItemID: nil, in: &captions))
        duplex.interruptResponse(responseID: "partial")
        XCTAssertTrue(response.abandonResponse("partial"))
        let retained = try XCTUnwrap(captions.first)

        duplex.interruptResponse(responseID: "partial")
        XCTAssertFalse(transcript.retainInterruptedCaption(responseID: "partial", providerItemID: nil, in: &captions))
        XCTAssertFalse(duplex.responseStarted(responseID: "partial", isTutorIntervention: false))
        XCTAssertFalse(duplex.matchesActiveResponse(responseID: "partial"))
        XCTAssertFalse(transcript.matchesResponse("partial"), "Late delta/done cannot reacquire the consumed draft")
        XCTAssertFalse(duplex.responseFinished(responseID: "partial"))
        XCTAssertNil(response.markResponseDone("partial"))
        XCTAssertNil(response.markOutputBufferStopped("partial"))
        XCTAssertNil(transcript.commit())
        XCTAssertEqual(captions, [retained])
        XCTAssertTrue(retained.isInterrupted)
    }

    func testOldInterruptionCannotTakeReplacementTextOrMixItWithTheRetainedMessage() throws {
        var transcript = VoiceTutorAssistantTranscriptState()
        var captions: [VoiceTutorCaption] = []
        transcript.beginResponse("old")
        transcript.append(delta: "이전 일부 설명")
        XCTAssertTrue(transcript.retainInterruptedCaption(responseID: "old", providerItemID: nil, in: &captions))
        let retained = try XCTUnwrap(captions.first)
        transcript.beginResponse("replacement")
        transcript.append(delta: "새 응답의 별도 설명")
        XCTAssertFalse(transcript.retainInterruptedCaption(responseID: "old", providerItemID: nil, in: &captions))
        XCTAssertTrue(transcript.matchesResponse("replacement"))
        XCTAssertEqual(transcript.draft, "새 응답의 별도 설명")
        let replacement = VoiceTutorCaption(speaker: .tutor, text: try XCTUnwrap(transcript.commit()), responseID: "replacement")
        captions.append(replacement)
        XCTAssertEqual(captions, [retained, replacement])
        XCTAssertTrue(retained.isInterrupted)
        XCTAssertFalse(replacement.isInterrupted)
        XCTAssertEqual(replacement.text, "새 응답의 별도 설명")
    }

    func testEndRetentionKeepsCompletedCaptionImmutableAndStoresOnlyTheCurrentPartialOnce() throws {
        var transcript = VoiceTutorAssistantTranscriptState()
        transcript.beginResponse("completed")
        transcript.stageCompletedTranscript("끝까지 완료된 메시지")
        let completed = VoiceTutorCaption(speaker: .tutor, text: try XCTUnwrap(transcript.commit()), responseID: "completed")
        var captions = [completed]
        XCTAssertFalse(transcript.retainInterruptedCaption(responseID: "completed", providerItemID: nil, in: &captions))
        // Even an accidentally staged duplicate cannot downgrade a completed
        // caption when explicit stop reaches the same retention path again.
        transcript.beginResponse("completed")
        transcript.append(delta: "중복된 늦은 텍스트")
        XCTAssertFalse(transcript.retainInterruptedCaption(responseID: "completed", providerItemID: nil, in: &captions))
        XCTAssertEqual(captions, [completed])
        XCTAssertFalse(captions[0].isInterrupted)
        transcript.beginResponse("current_partial")
        transcript.append(delta: "종료 직전 보이던 설명")
        XCTAssertTrue(transcript.retainInterruptedCaption(responseID: "current_partial", providerItemID: nil, in: &captions))
        XCTAssertFalse(transcript.retainInterruptedCaption(responseID: "current_partial", providerItemID: nil, in: &captions))
        XCTAssertEqual(captions.count, 2)
        XCTAssertEqual(captions[0], completed)
        XCTAssertEqual(captions[1].text, "종료 직전 보이던 설명")
        XCTAssertTrue(captions[1].isInterrupted)
    }

    @MainActor
    func testSpokenUnsubmittedDraftSurvivesCancellationInCanonicalStoreAndVisibleCaption() throws {
        let registration = try VoiceTutorContractAppFixture.registration(ownerUserID: 7, tokenID: "draft-retention")
        let fixture = try VoiceTutorContractAppFixture(registration: registration) { _ in
            XCTFail("Retaining an unfinished answer must not submit or publish it")
            throw URLError(.unsupportedURL)
        }
        defer { fixture.close() }
        fixture.store.saveAnswerDraft("다른 질문의 초안", recordID: "102")
        var draft = VoiceTutorAnswerDraftState()
        let answerID = "11111111-2222-3333-4444-555555555555"
        XCTAssertTrue(draft.apply(.init(answerID: answerID, studyID: 42, recordID: "101", revision: 1,
                                        phase: .listening, text: nil, code: nil, question: "합성 저장 질문을 설명하세요.")))
        XCTAssertTrue(draft.append(.init(answerID: answerID, recordID: "101", itemID: "spoken_part", sequence: 1,
                                        text: "말로만 남긴 답변\n생각 중인 내용")))
        XCTAssertFalse(draft.hasUserEdited, "This is the previously unsaved speech-only teardown path")
        let snapshot = try XCTUnwrap(VoiceTutorUnsubmittedAnswerSnapshot(draft))
        let prior = VoiceTutorCaption(speaker: .tutor, text: "기존 질문", responseID: "question")
        let raw = VoiceTutorCaption(speaker: .learner, text: draft.recognizedText, providerItemID: "spoken_part")
        var captions = [prior, raw]
        fixture.appState.saveVoiceTutorAnswerDraft(snapshot.text, for: snapshot.change, validity: { true })
        XCTAssertTrue(snapshot.retainCaption(in: &captions))
        draft.endLocally()
        XCTAssertEqual(draft.phase, .cancelled)
        XCTAssertFalse(draft.canSubmit)
        XCTAssertNil(VoiceTutorUnsubmittedAnswerSnapshot(draft))
        XCTAssertFalse(snapshot.retainCaption(in: &captions), "Repeated end cannot append the same answer twice")
        XCTAssertFalse(draft.append(.init(answerID: answerID, recordID: "101", itemID: "late_part", sequence: 2,
                                         text: "취소 뒤 늦은 인식")))
        let retained = try XCTUnwrap(captions.last)
        XCTAssertEqual(retained.text, snapshot.text)
        XCTAssertEqual(retained.answerID, answerID)
        XCTAssertEqual(retained.providerItemIDs, ["spoken_part"])
        XCTAssertTrue(retained.isUnsubmittedAnswer)
        XCTAssertFalse(retained.isInterrupted)
        XCTAssertEqual(captions.filter { $0.id != raw.id }, [prior, retained],
                       "Keeping raw ASR hidden still leaves the authoritative visible draft")
        XCTAssertEqual(fixture.appState.voiceTutorAnswerDraft(for: snapshot.change, validity: { true }), snapshot.text)
        XCTAssertEqual(fixture.store.loadAnswerDraft(recordID: "101"), snapshot.text)
        XCTAssertEqual(fixture.store.loadAnswerDraft(recordID: "102"), "다른 질문의 초안")
        fixture.appState.saveVoiceTutorAnswerDraft("무효 계정의 텍스트", for: snapshot.change, validity: { false })
        XCTAssertEqual(fixture.store.loadAnswerDraft(recordID: "101"), snapshot.text)
    }

    func testCancelledAnswerRetentionKeepsEditedTextAndCannotBorrowReplacementIdentity() throws {
        var draft = VoiceTutorAnswerDraftState()
        let oldID = "11111111-2222-3333-4444-555555555555"
        let newID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        XCTAssertTrue(draft.apply(.init(answerID: oldID, studyID: 42, recordID: "101", revision: 1,
                                        phase: .listening, text: nil, code: nil, question: "합성 저장 질문을 설명하세요."), existingDraft: "저장돼 있던 초안"))
        XCTAssertTrue(draft.append(.init(answerID: oldID, recordID: "101", itemID: "old_part", sequence: 1, text: "음성 원문")))
        XCTAssertTrue(draft.edit("사용자가 최종 수정한 답변"))
        let snapshot = try XCTUnwrap(VoiceTutorUnsubmittedAnswerSnapshot(draft))
        XCTAssertTrue(draft.apply(.init(answerID: oldID, studyID: 42, recordID: "101", revision: 1,
                                        phase: .cancelled, text: nil, code: nil)))
        var captions: [VoiceTutorCaption] = []
        XCTAssertTrue(snapshot.retainCaption(in: &captions))
        let retained = try XCTUnwrap(captions.first)
        XCTAssertEqual(retained.text, "사용자가 최종 수정한 답변")
        XCTAssertFalse(retained.text.contains("음성 원문"))
        XCTAssertEqual(retained.answerID, oldID)
        XCTAssertTrue(retained.containsProviderItemID("old_part"))
        XCTAssertTrue(draft.apply(.init(answerID: newID, studyID: 42, recordID: "102", revision: 2,
                                        phase: .listening, text: nil, code: nil, question: "합성 저장 질문을 설명하세요.")))
        XCTAssertTrue(draft.edit("다음 질문의 별도 초안"))
        XCTAssertFalse(draft.apply(.init(answerID: oldID, studyID: 42, recordID: "101", revision: 1,
                                         phase: .cancelled, text: nil, code: nil)))
        XCTAssertFalse(snapshot.retainCaption(in: &captions))
        XCTAssertEqual(draft.answerID, newID)
        XCTAssertEqual(draft.text, "다음 질문의 별도 초안")
        XCTAssertEqual(captions, [retained])
        var operations = VoiceTutorOperationState()
        XCTAssertTrue(operations.applyContext(.init(operationID: "answer_action", answerID: oldID)))
        XCTAssertTrue(operations.apply(.init(sequence: 1, operationID: "answer_action", name: "list_studies",
            phase: .started, elapsedMilliseconds: 0), at: 0))
        let layout = VoiceTutorOperationTranscriptLayout(entries: operations.visibleEntries(at: 0), captions: captions,
            assistantResponseID: nil, hasAssistantDraft: false, answerDraftID: newID)
        XCTAssertEqual(layout.byCaptionID[retained.id]?.map(\.id), ["answer_action"])
        XCTAssertTrue(layout.afterAnswerDraft.isEmpty)
    }

    func testSubmissionInFlightRetainsTextWithoutClaimingItWasUnsubmittedOrResubmitting() throws {
        var draft = VoiceTutorAnswerDraftState()
        let answerID = "11111111-2222-3333-4444-555555555555"
        XCTAssertNil(VoiceTutorUnsubmittedAnswerSnapshot(draft))
        XCTAssertTrue(draft.apply(.init(answerID: answerID, studyID: 42, recordID: "101", revision: 1,
                                        phase: .listening, text: nil, code: nil, question: "합성 저장 질문을 설명하세요.")))
        XCTAssertNil(VoiceTutorUnsubmittedAnswerSnapshot(draft), "An empty capture must not create a blank chat row")
        XCTAssertTrue(draft.edit("제출 결과를 아직 확인하지 못한 답변"))
        XCTAssertNotNil(draft.requestFinish())
        XCTAssertTrue(draft.apply(.init(answerID: answerID, studyID: 42, recordID: "101", revision: 1,
                                        phase: .review, text: nil, code: nil)))
        XCTAssertNotNil(draft.requestSubmit())
        let pending = try XCTUnwrap(VoiceTutorUnsubmittedAnswerSnapshot(draft))
        XCTAssertTrue(pending.isSubmissionUnconfirmed)
        var captions: [VoiceTutorCaption] = []
        XCTAssertTrue(pending.retainCaption(in: &captions))
        XCTAssertFalse(try XCTUnwrap(captions.first).isUnsubmittedAnswer,
                       "The server may have accepted a submission whose ACK was lost")
        XCTAssertNil(draft.requestSubmit(), "Retention cannot cause automatic re-submission")
        XCTAssertTrue(draft.apply(.init(answerID: answerID, studyID: 42, recordID: "101", revision: 1,
                                        phase: .submitted, text: nil, code: nil)))
        XCTAssertNil(VoiceTutorUnsubmittedAnswerSnapshot(draft))
        XCTAssertFalse(draft.apply(.init(answerID: answerID, studyID: 42, recordID: "101", revision: 1,
                                         phase: .cancelled, text: nil, code: nil)))
        let confirmed = VoiceTutorCaption(speaker: .learner, text: draft.text, answerID: answerID)
        captions = [confirmed]
        XCTAssertFalse(pending.retainCaption(in: &captions))
        XCTAssertEqual(captions, [confirmed], "A retained snapshot cannot downgrade an accepted answer")
        draft = VoiceTutorAnswerDraftState()
        XCTAssertNil(VoiceTutorUnsubmittedAnswerSnapshot(draft), "Identity reset removes any retainable previous draft")
    }

    func testUnsubmittedAnswerTeardownUsesCanonicalDraftStorageBeforeCancellation() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let sourceURL = root.appendingPathComponent("StudyMate/ViewModels/VoiceTutorViewModel.swift")
        #if !targetEnvironment(simulator)
        guard FileManager.default.fileExists(atPath: sourceURL.path) else {
            throw XCTSkip("Source wiring requires the repository; draft behavior also runs on iPhone.")
        }
        #endif
        let source = try String(contentsOf: sourceURL, encoding: .utf8)
        for start in ["    private func stop(\n", "    private func finishFromServer(\n"] {
            let body = try XCTUnwrap(source.range(of: start)).upperBound
            let cancelled = try XCTUnwrap(source.range(of: "answerDraftState.endLocally()", range: body..<source.endIndex))
            let beforeCancellation = String(source[body..<cancelled.lowerBound])
            XCTAssertTrue(beforeCancellation.contains("preserveUnsubmittedAnswerDraft(VoiceTutorUnsubmittedAnswerSnapshot(answerDraftState))"))
        }
        let saveStart = try XCTUnwrap(source.range(of: "    private func preserveUnsubmittedAnswerDraft(")).lowerBound
        let saveEnd = try XCTUnwrap(source.range(of: "    private func failAnswerControl", range: saveStart..<source.endIndex)).lowerBound
        let save = String(source[saveStart..<saveEnd])
        XCTAssertTrue(save.contains("connection.isCurrent()"))
        XCTAssertTrue(save.contains("appState.saveVoiceTutorAnswerDraft(snapshot.text, for: snapshot.change"))
        XCTAssertFalse(save.contains("transport.send"), "Local preservation must not invoke answer submission")
        XCTAssertFalse(save.contains("controls.yield"))
        let privacyStart = try XCTUnwrap(source.range(of: "    private func stopForInvalidatedContext()")).lowerBound
        let privacyEnd = try XCTUnwrap(source.range(of: "    private func ", range: source.index(after: privacyStart)..<source.endIndex)).lowerBound
        let privacy = String(source[privacyStart..<privacyEnd])
        XCTAssertTrue(privacy.contains("answerDraftState = VoiceTutorAnswerDraftState()"))
        XCTAssertTrue(privacy.contains("captions = []"))
        XCTAssertFalse(privacy.contains("preserveUnsubmittedAnswerDraft"))
        let view = try String(contentsOf: root.appendingPathComponent("StudyMate/Views/VoiceTutorView.swift"), encoding: .utf8)
        XCTAssertTrue(view.contains("if caption.isUnsubmittedAnswer {"))
        XCTAssertTrue(view.contains("Text(strings.voiceTutorUnsubmittedAnswer)"))
    }

    func testProviderRetryAndIdentityDiscardDoNotArchivePartialOrCreateEmptyInterruptedMessages() {
        let previous = VoiceTutorCaption(speaker: .learner, text: "유지할 기존 요청")
        var captions = [previous]
        var transcript = VoiceTutorAssistantTranscriptState()
        for responseID in ["provider_failed", "identity_invalidated"] {
            transcript.beginResponse(responseID)
            transcript.append(delta: "완료 기록으로 남기지 않을 부분")
            transcript.discard()
            XCTAssertFalse(transcript.retainInterruptedCaption(responseID: responseID, providerItemID: nil, in: &captions))
            XCTAssertNil(transcript.commit())
        }
        transcript.beginResponse("empty")
        transcript.append(delta: " \n ")
        XCTAssertFalse(transcript.retainInterruptedCaption(responseID: "empty", providerItemID: nil, in: &captions))
        XCTAssertNil(transcript.responseID)
        XCTAssertEqual(captions, [previous])
    }

    func testAuthoritativeEmptyTutorTranscriptClearsEarlierPartialDeltas() {
        var transcript = VoiceTutorAssistantTranscriptState()
        transcript.append(delta: "확정되지 않은 일부")
        transcript.stageCompletedTranscript("  \n ")

        XCTAssertEqual(transcript.draft, "")
        XCTAssertNil(transcript.commit())
    }

    func testServerVerifiedSpokenEndCommitsLastTutorTranscriptWhenLifecycleWinsRace() throws {
        var transcript = VoiceTutorAssistantTranscriptState()
        transcript.stageCompletedTranscript("정상적으로 끝난 마지막 문장")

        let exactResponseID = try XCTUnwrap(
            VoiceTutorServerEndPlayoutPolicy.serverVerifiedFallbackResponseID(
                reason: "USER_ENDED",
                usesWebRTC: true,
                pending: nil,
                activeResponseID: "response-final"
            )
        )
        XCTAssertEqual(exactResponseID, "response-final")
        XCTAssertEqual(transcript.commit(), "정상적으로 끝난 마지막 문장")

        var failedTranscript = VoiceTutorAssistantTranscriptState()
        failedTranscript.stageCompletedTranscript("실패한 중간 문장")
        XCTAssertNil(VoiceTutorServerEndPlayoutPolicy.serverVerifiedFallbackResponseID(
            reason: "PROVIDER_ERROR",
            usesWebRTC: true,
            pending: nil,
            activeResponseID: "response-failed"
        ))
        failedTranscript.discard()
        XCTAssertNil(failedTranscript.commit())
    }

    func testTutorTranscriptCommitsOnlyAfterBothExactWebRTCCompletionBoundaries() throws {
        for responseDoneFirst in [true, false] {
            var transcript = VoiceTutorAssistantTranscriptState()
            var response = VoiceTutorWebRTCResponseState()
            var duplex = VoiceTutorDuplexPlaybackState()
            response.responseStarted("response-complete")
            XCTAssertTrue(duplex.responseStarted(
                responseID: "response-complete",
                isTutorIntervention: false
            ))
            transcript.stageCompletedTranscript("완료된 선생님 문장")

            let firstReadyID = responseDoneFirst
                ? response.markResponseDone("response-complete")
                : response.markOutputBufferStopped("response-complete")
            XCTAssertNil(firstReadyID)
            XCTAssertEqual(transcript.draft, "완료된 선생님 문장")

            let readyID = try XCTUnwrap(responseDoneFirst
                ? response.markOutputBufferStopped("response-complete")
                : response.markResponseDone("response-complete"))
            XCTAssertTrue(duplex.responseFinished(responseID: readyID))
            XCTAssertEqual(transcript.commit(), "완료된 선생님 문장")
            XCTAssertNil(transcript.commit(), "The same response cannot publish twice")
        }
    }

    func testResponseDoneThenProviderClearDiscardsTutorTranscriptBeforeRetry() {
        var transcript = VoiceTutorAssistantTranscriptState()
        var response = VoiceTutorWebRTCResponseState()
        var duplex = VoiceTutorDuplexPlaybackState()
        response.responseStarted("response-failed")
        XCTAssertTrue(duplex.responseStarted(
            responseID: "response-failed",
            isTutorIntervention: false
        ))
        transcript.stageCompletedTranscript("전송되지 않아야 할 문장")

        XCTAssertNil(response.markResponseDone("response-failed"))
        XCTAssertTrue(duplex.abandonResponse(responseID: "response-failed"))
        XCTAssertTrue(response.abandonResponse("response-failed"))
        transcript.discard()

        XCTAssertNil(transcript.commit())
        XCTAssertFalse(duplex.assistantResponseActive)
        XCTAssertNil(response.responseID)
    }

    @MainActor
    func testCompletedCallPopsRenderedNavigationWhileEndingAndFailureStayPresented() async throws {
        let fixture = VoiceTutorCompletionNavigationFixture()
        defer { fixture.close() }
        let rootReady = try await waitForCompletionNavigation { fixture.probe.isRootReady }
        XCTAssertTrue(rootReady)
        fixture.probe.path = [1]
        let callReady = try await waitForCompletionNavigation { fixture.probe.destinationAppearCount == 1 }
        XCTAssertTrue(callReady)

        // The failure screen remains available for retry. A later successful
        // attempt returns only after its finalization reaches ended.
        for phase in [VoiceTutorSessionPhase.ending, .failed, .listening, .ending] {
            fixture.probe.phase = phase
            let rendered = try await waitForCompletionNavigation {
                fixture.probe.renderedState == .init(phase: phase, scenePhase: .active)
            }
            XCTAssertTrue(rendered)
            try await Task.sleep(for: .milliseconds(150))
            XCTAssertEqual(fixture.probe.path, [1], "\(phase) must preserve the call destination.")
        }

        fixture.probe.phase = .ended
        let popped = try await waitForCompletionNavigation { fixture.probe.path.isEmpty }
        XCTAssertTrue(popped, "The production modifier must dismiss through NavigationStack's actual path binding.")
        XCTAssertEqual(fixture.probe.destinationAppearCount, 1, "Completing a call must not present another call.")
    }

    @MainActor
    func testCallCompletedOutsideActiveScenePopsRenderedNavigationOnlyAfterForegroundReturn() async throws {
        for suspendedScene in [ScenePhase.background, .inactive] {
            let fixture = VoiceTutorCompletionNavigationFixture()
            defer { fixture.close() }
            let rootReady = try await waitForCompletionNavigation { fixture.probe.isRootReady }
            XCTAssertTrue(rootReady)
            fixture.probe.path = [1]
            let callReady = try await waitForCompletionNavigation { fixture.probe.destinationAppearCount == 1 }
            XCTAssertTrue(callReady)

            fixture.probe.scenePhase = suspendedScene
            let suspended = try await waitForCompletionNavigation {
                fixture.probe.renderedState == .init(phase: .listening, scenePhase: suspendedScene)
            }
            XCTAssertTrue(suspended)
            fixture.probe.phase = .ended
            let completionRendered = try await waitForCompletionNavigation {
                fixture.probe.renderedState == .init(phase: .ended, scenePhase: suspendedScene)
            }
            XCTAssertTrue(completionRendered)
            try await Task.sleep(for: .milliseconds(150))
            XCTAssertEqual(fixture.probe.path, [1], "\(suspendedScene) must defer navigation until the app is active.")

            fixture.probe.scenePhase = .active
            let popped = try await waitForCompletionNavigation { fixture.probe.path.isEmpty }
            XCTAssertTrue(popped, "An ended call must return on foreground entry without another phase update.")
            XCTAssertEqual(fixture.probe.destinationAppearCount, 1)
        }
    }

    @MainActor
    private func waitForCompletionNavigation(_ condition: () -> Bool) async throws -> Bool {
        let deadline = ProcessInfo.processInfo.systemUptime + 3
        while !condition(), ProcessInfo.processInfo.systemUptime < deadline {
            try await Task.sleep(for: .milliseconds(20))
        }
        return condition()
    }

    @MainActor
    func testVoiceOrbTapAndConversationActionsOnPresentedSyntheticCall() async throws {
        guard ProcessInfo.processInfo.environment["BUDDYSTUDY_VOICE_INTERACTION_SMOKE"] == "1" else {
            throw XCTSkip("Opt-in computer-use smoke test; the fixture has no microphone or network session.")
        }
        let probe = VoiceTutorOrbInteractionProbe()
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }.first { $0.activationState == .foregroundActive })
        let previousKeyWindow = scene.windows.first { $0.isKeyWindow }
        let window = UIWindow(windowScene: scene)
        window.rootViewController = UIHostingController(rootView: VoiceTutorInteractiveCallFixture(probe: probe))
        window.overrideUserInterfaceStyle = .dark
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKey()
        }
        print("VOICE_INTERACTION_READY")
        let deadline = ProcessInfo.processInfo.systemUptime + 180
        while !probe.completed && ProcessInfo.processInfo.systemUptime < deadline {
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertEqual(probe.pauseCount, 2, "Tap once to pause and once to continue")
        XCTAssertTrue(probe.revealed, "Reveal the conversation through the circle's gesture or accessibility action")
        XCTAssertTrue(probe.collapsed, "Return to the call through the circle's gesture or accessibility action")
        XCTAssertEqual(probe.endCount, 0, "Taps and disclosure swipes must never end the call")
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
            .init(
                name: "02-listening-with-preview", phase: .listening,
                seconds: 3_596, showsPreview: true
            ),
            .init(name: "03-speaking-recording", phase: .speaking, isRecording: true),
            .init(
                name: "04-paused-static", phase: .listening,
                pauseState: pausedState
            ),
            .init(
                name: "05-provider-failed-with-saved-summary", phase: .failed,
                failureCause: .provider, detail: completed
            ),
            .init(name: "06-ended-summary-pending", phase: .ended, detail: pending),
            .init(name: "07-expanded-conversation", phase: .speaking, showsTranscript: true),
            .init(
                name: "08-monthly-quota-ended", phase: .ended,
                serverEndReason: "QUOTA_EXHAUSTED",
                quotaRemainingSeconds: 0,
                quotaLimitSeconds: 3_600
            ),
            .init(
                name: "09-narrow-accessibility-english", phase: .listening,
                showsTranscript: true, language: .english,
                topic: "Redis caching and concurrent updates",
                size: CGSize(width: 320, height: 696), dynamicType: .accessibility3
            ),
            .init(
                name: "10-first-response-waiting", phase: .listening,
                isAwaitingTutorResponse: true, topic: ""
            ),
            .init(
                name: "11-no-tutor-response-failed", phase: .failed,
                failureCause: .provider, showsTranscript: true,
                learnerOnly: true, topic: ""
            ),
            .init(
                name: "12-light-conversation-waiting", phase: .listening,
                isAwaitingTutorResponse: true, showsTranscript: true,
                learnerOnly: true, colorScheme: .light
            )
        ]
        // A single-fixture process helps isolate layers that iOS can omit in
        // consecutive hierarchy captures, without creating any real call.
        let requestedFixture = ProcessInfo.processInfo.environment["BUDDYSTUDY_VOICE_SNAPSHOT"]
        let selectedFixtures = requestedFixture.map { name in fixtures.filter { $0.name == name } } ?? fixtures
        XCTAssertFalse(selectedFixtures.isEmpty, "The requested visual fixture must exist")
        var capturedPNGs = Set<Data>()
        for fixture in selectedFixtures {
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
        XCTAssertEqual(capturedPNGs.count, selectedFixtures.count, "Each state must produce a distinct rendered screen")
    }

    @MainActor
    func testBackendLessonProgressScreensRenderWithoutStartingAnAudioSession() async throws {
        let fixtures: [VoiceTutorCompactCallSnapshot] = [
            .init(name: "lesson-question-loading", phase: .listening, topic: "스프링",
                  sessionState: makeLessonPresentationState(.questionLoading)),
            .init(name: "lesson-question-generating", phase: .listening, topic: "스프링",
                  sessionState: makeLessonPresentationState(.questionGenerating)),
            .init(name: "lesson-question-ready", phase: .listening, topic: "스프링",
                  sessionState: makeLessonPresentationState(.questionReady)),
            .init(name: "lesson-question-reading", phase: .speaking, topic: "스프링",
                  sessionState: makeLessonPresentationState(.questionReading)),
            .init(name: "lesson-grading", phase: .listening, topic: "스프링",
                  sessionState: makeLessonPresentationState(.grading)),
            .init(name: "lesson-graded", phase: .listening, topic: "스프링",
                  sessionState: makeLessonPresentationState(.graded)),
            .init(name: "lesson-grading-failed-light", phase: .listening, topic: "스프링", colorScheme: .light,
                  sessionState: makeLessonPresentationState(.gradingFailed)),
            .init(name: "lesson-grading-transcript-accessibility", phase: .listening, showsTranscript: true,
                  language: .english, topic: "Spring", size: CGSize(width: 320, height: 696), dynamicType: .accessibility3,
                  sessionState: makeLessonPresentationState(.grading))
        ]
        var captures = Set<Data>()
        for fixture in fixtures {
            let image = try await renderCompactCallSnapshot(fixture)
            let png = try XCTUnwrap(image.pngData(), fixture.name)
            XCTAssertEqual(image.size, fixture.size)
            XCTAssertGreaterThan(png.count, 2_000, fixture.name)
            captures.insert(png)
            let attachment = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
            attachment.name = fixture.name
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        XCTAssertEqual(captures.count, fixtures.count, "Server lesson stages must be visually distinguishable")
    }

    @MainActor
    func testManualAnswerScreensRenderWithoutSubmittingOrOpeningAMicrophone() async throws {
        let answerID = UUID().uuidString
        func makeState(_ phase: VoiceTutorAnswerDraftState.Phase, text: String) -> VoiceTutorAnswerDraftState {
            var state = VoiceTutorAnswerDraftState()
            state.apply(.init(answerID: answerID, studyID: 42, recordID: "101", revision: 1,
                              phase: .listening, text: nil, code: nil, question: "합성 저장 질문을 설명하세요."), existingDraft: text)
            if phase != .listening {
                _ = state.requestFinish()
                state.apply(.init(answerID: answerID, studyID: 42, recordID: "101", revision: 1,
                                  phase: .review, text: nil, code: nil))
                if phase == .submitting { _ = state.requestSubmit() }
                if phase == .failed {
                    state.apply(.init(answerID: answerID, studyID: 42, recordID: "101", revision: 1,
                                      phase: .failed, text: nil, code: "ANSWER_SUBMISSION_FAILED"))
                }
            }
            return state
        }
        let text = "B입니다. 스프링 프레임워크는 핵심 기반을 제공하고, 스프링 부트는 설정을 단순화합니다."
        var paused = VoiceTutorCallPauseState()
        paused.isSupported = true
        let pauseRequest = try XCTUnwrap(paused.requestPause())
        _ = paused.acknowledge(sequence: pauseRequest.sequence, paused: true)
        var cancelling = makeState(.review, text: text)
        XCTAssertNotNil(cancelling.requestCancel())
        let fixtures: [VoiceTutorCompactCallSnapshot] = [
            .init(name: "answer-started-empty", phase: .listening, topic: "스프링",
                  answerDraftState: makeState(.listening, text: "")),
            .init(name: "answer-capture-light", phase: .listening, topic: "스프링", colorScheme: .light,
                  answerDraftState: makeState(.listening, text: text)),
            .init(name: "answer-capture-paused", phase: .listening, pauseState: paused, topic: "스프링",
                  answerDraftState: makeState(.listening, text: text)),
            .init(name: "answer-capture-accessibility", phase: .listening, language: .english,
                  topic: "Spring", size: CGSize(width: 320, height: 696), dynamicType: .accessibility3,
                  answerDraftState: makeState(.listening, text: "I would choose B.")),
            .init(name: "answer-listening-orb", phase: .listening, topic: "스프링",
                  answerDraftState: makeState(.listening, text: text)),
            .init(name: "answer-listening-editor", phase: .listening, showsTranscript: true, topic: "스프링",
                  answerDraftState: makeState(.listening, text: text)),
            .init(name: "answer-review-corrected", phase: .listening, showsTranscript: true, topic: "스프링",
                  answerDraftState: makeState(.review, text: text + " 스프링 데이터는 데이터 접근을 돕습니다.")),
            .init(name: "answer-review-empty", phase: .listening, showsTranscript: true, topic: "스프링",
                  answerDraftState: makeState(.review, text: "")),
            .init(name: "answer-submitting", phase: .listening, showsTranscript: true, topic: "스프링",
                  answerDraftState: makeState(.submitting, text: text)),
            .init(name: "answer-cancelling", phase: .listening, showsTranscript: true, topic: "스프링",
                  answerDraftState: cancelling),
            .init(name: "answer-retry-accessibility", phase: .listening, showsTranscript: true,
                  language: .english, topic: "Spring", size: CGSize(width: 320, height: 696), dynamicType: .accessibility3,
                  answerDraftState: makeState(.failed, text: "B. Spring supplies the core framework. Spring Boot simplifies configuration.")),
        ]
        var captures = Set<Data>()
        for fixture in fixtures {
            let image = try await renderCompactCallSnapshot(fixture)
            let png = try XCTUnwrap(image.pngData(), fixture.name)
            XCTAssertEqual(image.size, fixture.size)
            XCTAssertGreaterThan(png.count, 2_000, fixture.name)
            captures.insert(png)
            let attachment = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
            attachment.name = fixture.name
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        XCTAssertEqual(captures.count, fixtures.count, "Each manual answer state must render distinctly")
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

    func testLocalVoiceActivityStartsOnceAndStopsAfterOneThousandTwoHundredEightyMillisecondsOfQuiet() {
        var detector = VoiceTutorLocalSpeechDetector()
        _ = detector.updateGate(mediaReady: true, muted: false)
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0.03, frames: 7).isEmpty)
        XCTAssertFalse(detector.isSpeaking)
        XCTAssertEqual(detector.process(normalizedRMS: 0.03, duration: 0.01),
                       VoiceTutorLocalSpeechEvent(activity: .started, sequence: 1))
        XCTAssertTrue(detector.isSpeaking)
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0.03, frames: 200).isEmpty,
                      "A sustained utterance starts only once")
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0, frames: 127).isEmpty)
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
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0, frames: 40).isEmpty)
        XCTAssertNil(detector.process(normalizedRMS: 0.004, duration: 0.01))
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0, frames: 127).isEmpty,
                      "Continuing speech restarts the complete 1,280 ms silence hold")
        XCTAssertEqual(detector.process(normalizedRMS: 0, duration: 0.01),
                       VoiceTutorLocalSpeechEvent(activity: .stopped, sequence: 1))
    }

    func testLocalVoiceActivityNormalizedAndNativeWebRTCSamplesProduceIdenticalEvents() throws {
        var normalizedDetector = VoiceTutorLocalSpeechDetector()
        var nativeDetector = VoiceTutorLocalSpeechDetector()
        _ = normalizedDetector.updateGate(mediaReady: true, muted: false)
        _ = nativeDetector.updateGate(mediaReady: true, muted: false)
        let segments: [(Float, Int)] = [(0, 30), (0.012, 16), (0.004, 40), (0, 160), (0.02, 12), (0, 160)]
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
        XCTAssertTrue(localSpeechEvents(&detector, rms: 0, frames: 127).isEmpty)
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
        XCTAssertEqual(localSpeechEvents(&detector, rms: 0, frames: 160), [
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

    func testLocalVoiceActivityInterruptsTutorWithoutClosingLearnerCapture() {
        var detector = VoiceTutorLocalSpeechDetector()
        var playback = VoiceTutorDuplexPlaybackState()
        XCTAssertTrue(playback.responseStarted(responseID: "synthetic-tutor-sentence", isTutorIntervention: false))
        XCTAssertTrue(playback.assistantAudioBegan(responseID: "synthetic-tutor-sentence"))
        _ = detector.updateGate(mediaReady: true, muted: false)
        XCTAssertEqual(localSpeechEvents(&detector, rms: 0.03, frames: 8), [
            VoiceTutorLocalSpeechEvent(activity: .started, sequence: 1)
        ])
        XCTAssertEqual(playback.userSpeechStarted(), "synthetic-tutor-sentence")
        XCTAssertTrue(playback.isUserSpeaking)
        XCTAssertFalse(playback.assistantResponseActive)
        XCTAssertNil(playback.activeResponseID)
        XCTAssertEqual(localSpeechEvents(&detector, rms: 0, frames: 160), [
            VoiceTutorLocalSpeechEvent(activity: .stopped, sequence: 1)
        ])
        playback.userSpeechStopped()
        XCTAssertFalse(playback.isUserSpeaking)
        XCTAssertFalse(playback.assistantResponseActive)
        XCTAssertFalse(playback.responseStarted(responseID: "synthetic-tutor-sentence", isTutorIntervention: false))
        XCTAssertTrue(detector.isEnabled, "Interrupting tutor output must leave the microphone active")
    }

    func testLocalVoiceActivityMessagesPairEdgesWithPositiveIncreasingUtteranceSequences() {
        var detector = VoiceTutorLocalSpeechDetector()
        _ = detector.updateGate(mediaReady: true, muted: false)
        var events: [VoiceTutorLocalSpeechEvent] = []
        for _ in 0..<6 {
            events += localSpeechEvents(&detector, rms: 0.03, frames: 8)
            events += localSpeechEvents(&detector, rms: 0, frames: 160)
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

    func testRealtimeNativeSpeechBoundariesCarryOnlyThePairedAcousticSequence() throws {
        for activity in [VoiceTutorLocalSpeechActivity.started, .stopped] {
            for sequence in [1, 2, Int.max] {
                let event = VoiceTutorLocalSpeechEvent(activity: activity, sequence: sequence)
                let payload = try VoiceTutorTurnProtocol.payload(for: .speech(event))
                XCTAssertEqual(Set(payload.keys), ["type", "sequence"])
                XCTAssertEqual(payload["type"] as? String, event.messageType)
                XCTAssertEqual(payload["sequence"] as? Int, sequence)
                let serialized = try JSONSerialization.data(withJSONObject: payload)
                XCTAssertLessThan(serialized.count, 128,
                                  "Speech boundaries carry no microphone audio or direct provider commands")
                let decoded = try XCTUnwrap(JSONSerialization.jsonObject(with: serialized) as? [String: Any])
                XCTAssertEqual(decoded["sequence"] as? Int, sequence)
                XCTAssertEqual(decoded["type"] as? String, event.messageType)
            }
            for sequence in [Int.min, -1, 0] {
                XCTAssertThrowsError(try VoiceTutorTurnProtocol.payload(for:
                    .speech(VoiceTutorLocalSpeechEvent(activity: activity, sequence: sequence))
                )) { error in
                    XCTAssertEqual(error as? VoiceTutorLocalSpeechDeliveryError, .invalidSequence)
                }
            }
        }
    }

    func testRealtimeNativeControlPreservesPauseFencesAndRejectsInvalidSequences() throws {
        for kind in [VoiceTutorPauseControl.Kind.pause, .inputQuiesced, .resume] {
            let payload = try VoiceTutorTurnProtocol.payload(for:
                .pause(VoiceTutorPauseControl(kind: kind, sequence: 7))
            )
            XCTAssertEqual(Set(payload.keys), ["type", "sequence"])
            XCTAssertEqual(payload["type"] as? String, kind.rawValue)
            XCTAssertEqual(payload["sequence"] as? Int64, 7)
            for sequence in [Int64.min, -1, 0] {
                XCTAssertThrowsError(try VoiceTutorTurnProtocol.payload(for:
                    .pause(VoiceTutorPauseControl(kind: kind, sequence: sequence))
                )) { error in
                    XCTAssertEqual(error as? VoiceTutorLocalSpeechDeliveryError, .invalidSequence)
                }
            }
        }
    }

    func testRealtimeNativeCapabilityPreservesAuthenticatedControlRequests() throws {
        var original = URLRequest(url: try XCTUnwrap(URL(
            string: "wss://voice-tutor.test/api/v1/voice-tutor/sessions/synthetic/control"
        )))
        original.httpMethod = "GET"
        original.timeoutInterval = 17
        original.setValue("Bearer fixture-token", forHTTPHeaderField: "Authorization")
        original.setValue("fixture-device", forHTTPHeaderField: "X-Device-Id")
        original.setValue("fixture-secret", forHTTPHeaderField: "X-Client-Secret")
        original.setValue("buddystudy.voice.control.v2", forHTTPHeaderField: "Sec-WebSocket-Protocol")
        let prepared = VoiceTutorTurnProtocol.addingCapability(to: original)
        XCTAssertEqual(VoiceTutorTurnProtocol.capabilityHeader, "X-Voice-Turn-Protocol")
        XCTAssertEqual(VoiceTutorTurnProtocol.capabilityValue, "realtime-native-v1")
        XCTAssertEqual(prepared.value(forHTTPHeaderField: "X-Voice-Turn-Protocol"), "realtime-native-v1")
        XCTAssertEqual(prepared.value(forHTTPHeaderField: "X-Voice-User-Input-Protocol"), "user-input-v1")
        XCTAssertNil(original.value(forHTTPHeaderField: "X-Voice-User-Input-Protocol"))
        XCTAssertNil(original.value(forHTTPHeaderField: "X-Voice-Turn-Protocol"))
        XCTAssertEqual(prepared.url, original.url)
        XCTAssertEqual(prepared.httpMethod, original.httpMethod)
        XCTAssertEqual(prepared.timeoutInterval, original.timeoutInterval)
        for name in ["Authorization", "X-Device-Id", "X-Client-Secret", "Sec-WebSocket-Protocol"] {
            XCTAssertEqual(prepared.value(forHTTPHeaderField: name), original.value(forHTTPHeaderField: name))
        }
        XCTAssertEqual(VoiceTutorTurnProtocol.addingCapability(to: prepared), prepared)
        var staleCapability = prepared
        staleCapability.setValue("old-capability", forHTTPHeaderField: "X-Voice-Turn-Protocol")
        XCTAssertEqual(VoiceTutorTurnProtocol.addingCapability(to: staleCapability), prepared)
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

    func testVoiceEchoCancellationReadinessRequiresSoftwareAECWithoutAParallelPlatformPath() {
        let permits = VoiceTutorEchoCancellationPolicy.canOpenMicrophone
        XCTAssertFalse(permits(true, true, false, true, false, false))
        XCTAssertFalse(permits(true, true, true, false, false, false), "Requested/resolved AEC alone is not active processing")
        XCTAssertFalse(permits(true, true, true, false, true, true), "An automatic return to platform AEC does not satisfy the chosen processing path")
        XCTAssertFalse(permits(true, true, true, true, true, true), "Do not stack two echo cancellers")
        XCTAssertFalse(permits(true, true, true, true, false, true), "Remove the VPIO graph rather than accepting a bypassed platform path")
        XCTAssertFalse(permits(false, true, true, true, false, false), "Processing flags cannot stand in for a running engine")
        XCTAssertFalse(permits(true, false, true, true, false, false), "An idle capture device cannot admit learner input")
        XCTAssertTrue(permits(true, true, true, true, false, false))
    }

    func testVoiceCommunicationProcessingOptionsKeepLocalTrackEnabledAndUseSoftwareAEC() throws {
        let options = VoiceTutorEchoCancellationPolicy.communicationOptions()
        XCTAssertTrue(options.echoCancellation)
        XCTAssertTrue(options.noiseSuppression)
        XCTAssertTrue(options.autoGainControl)
        XCTAssertTrue(options.highPassFilter)
        XCTAssertEqual(options.echoCancellationMode, .software)
        XCTAssertEqual(options.noiseSuppressionMode, .software, "The coupled AEC/NS platform path must move together")
        XCTAssertEqual(options.autoGainControlMode, .software)
        XCTAssertEqual(options.highPassFilterMode, .software)
        let capture = VoiceTutorContractNativeAudioDelegate()
        let module = try VoiceTutorAudioProcessingModuleFactory.make(captureDelegate: capture)
        let factory = LKRTCPeerConnectionFactory(audioDeviceModuleType: .audioEngine,
            bypassVoiceProcessing: false, encoderFactory: nil, decoderFactory: nil,
            audioProcessingModule: module)
        try VoiceTutorEchoCancellationPolicy.prepareDevice(factory.audioDeviceModule)
        XCTAssertFalse(factory.audioDeviceModule.isPlatformVoiceProcessingAllowed)
        let source = factory.audioSource(with: nil)
        let track = factory.audioTrack(with: source, trackId: "synthetic-echo-policy")
        XCTAssertTrue(track.isEnabled)
        let result = try VoiceTutorEchoCancellationPolicy.configure(track)
        XCTAssertTrue(result.isSuccess)
        XCTAssertEqual(result.code, .stored, "No sender or audio I/O is started by this policy test")
        XCTAssertTrue(track.isEnabled, "Echo control cannot remove the learner's ability to interrupt the tutor")
        XCTAssertFalse(factory.audioDeviceModule.isRecording)
        withExtendedLifetime((capture, module, factory, source, track)) {}
    }

    func testVoiceSoftwareAECIsResolvedByMediaEngineBeforeMutedInputOrSDP() throws {
        let capture = VoiceTutorContractNativeAudioDelegate()
        let module = try VoiceTutorAudioProcessingModuleFactory.make(captureDelegate: capture)
        let factory = LKRTCPeerConnectionFactory(audioDeviceModuleType: .audioEngine,
            bypassVoiceProcessing: false, encoderFactory: nil, decoderFactory: nil,
            audioProcessingModule: module)
        try VoiceTutorEchoCancellationPolicy.prepareDevice(factory.audioDeviceModule)
        let track = factory.audioTrack(with: factory.audioSource(with: nil), trackId: "synthetic-software-aec")
        try VoiceTutorEchoCancellationPolicy.configure(track)
        track.isEnabled = false
        let configuration = LKRTCConfiguration()
        configuration.sdpSemantics = .unifiedPlan
        configuration.iceServers = []
        configuration.iceTransportPolicy = .none
        let constraints = LKRTCMediaConstraints(
            mandatoryConstraints: ["OfferToReceiveAudio": "false", "OfferToReceiveVideo": "false"],
            optionalConstraints: nil)
        let peer = try XCTUnwrap(factory.peerConnection(with: configuration, constraints: constraints, delegate: nil))
        defer {
            peer.close()
            withExtendedLifetime((capture, module, factory, track, peer)) {}
        }
        // Media-engine default options must already resolve against our device
        // policy. This is the same pre-SDP ordering used by a real muted call;
        // it cannot rely on enabling the microphone to apply stored track options.
        let processing = factory.audioProcessingState
        XCTAssertTrue(processing.echoCancellation.isSoftwareResolved)
        XCTAssertTrue(processing.echoCancellation.isSoftwareActive)
        XCTAssertFalse(module.config.isEchoCancellationMobileMode, "The render-reference path must use AEC3, not legacy AECM")
        XCTAssertTrue(processing.noiseSuppression.isSoftwareActive)
        XCTAssertFalse(processing.echoCancellation.isPlatformActive)
        XCTAssertFalse(factory.audioDeviceModule.platformAudioProcessingState.isVoiceProcessingEnabledActive)
        XCTAssertFalse(factory.audioDeviceModule.isRecording)
        XCTAssertFalse(track.isEnabled)
        XCTAssertFalse(VoiceTutorEchoCancellationPolicy.isActive(factory: factory), "Configuring AEC is not media readiness")
        XCTAssertNil(peer.localDescription)
        XCTAssertNil(peer.remoteDescription)
        XCTAssertEqual(peer.iceGatheringState, .new)
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

    func testActiveVoiceCallDeclaresBackgroundAudio() throws {
        let modes = try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String])
        XCTAssertTrue(modes.contains("audio"), "A live tutor call must continue when switching apps or locking the iPhone")
        XCTAssertTrue(modes.contains("remote-notification"))
    }

    @MainActor
    func testRecordingFinalizationWaitsForActualProtectedDataAvailability() async throws {
        let center = NotificationCenter()
        let checked = expectation(description: "Protected recording is waiting for unlock")
        let rechecked = expectation(description: "Availability rechecked after notification")
        var isAvailable = false
        var checks = 0
        var didContinue = false
        let finalization = Task {
            try await VoiceTutorProtectedRecordingAccess.waitUntilAvailable(notificationCenter: center) {
                checks += 1
                if checks == 1 { checked.fulfill() }
                if checks == 2 { rechecked.fulfill() }
                return isAvailable
            }
            didContinue = true
        }
        defer { finalization.cancel() }
        let initialCheck = await XCTWaiter.fulfillment(of: [checked], timeout: 2)
        XCTAssertEqual(initialCheck, .completed)
        XCTAssertFalse(didContinue)
        center.post(name: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil)
        let secondCheck = await XCTWaiter.fulfillment(of: [rechecked], timeout: 2)
        XCTAssertEqual(secondCheck, .completed)
        XCTAssertFalse(didContinue, "A notification alone must not reopen protected audio")
        isAvailable = true
        center.post(name: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil)
        try await finalization.value
        XCTAssertTrue(didContinue)
    }

    @MainActor
    func testProtectedRecordingWaitCanBeCancelledWithoutUnlocking() async {
        let checked = expectation(description: "Protected data remains locked")
        let finalization = Task {
            try await VoiceTutorProtectedRecordingAccess.waitUntilAvailable(notificationCenter: NotificationCenter()) {
                checked.fulfill()
                return false
            }
        }
        let ready = await XCTWaiter.fulfillment(of: [checked], timeout: 2)
        XCTAssertEqual(ready, .completed)
        finalization.cancel()
        do {
            try await finalization.value
            XCTFail("Cancellation must not begin export while audio is protected")
        } catch {
            XCTAssertTrue(error is CancellationError)
        }
    }

    @MainActor
    func testUnlockedRecordingFinalizationDoesNotWaitForAnUnlockNotification() async throws {
        try await VoiceTutorProtectedRecordingAccess.waitUntilAvailable(notificationCenter: NotificationCenter()) { true }
    }

    @MainActor
    func testRecordingFinalizationRetainsSourcesAndRetriesAfterRelockDuringExportHashOrSave() async throws {
        let failures: [NSError] = [
            NSError(domain: AVFoundationErrorDomain, code: -11800, userInfo: [
                NSUnderlyingErrorKey: NSError(domain: NSOSStatusErrorDomain, code: -54)
            ]),
            NSError(domain: NSCocoaErrorDomain, code: NSFileReadNoPermissionError),
            NSError(domain: NSCocoaErrorDomain, code: NSFileWriteNoPermissionError)
        ]
        for failure in failures {
            let source = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            let audio = Data("retained learner and tutor audio".utf8)
            try audio.write(to: source)
            defer { try? FileManager.default.removeItem(at: source) }
            let center = NotificationCenter()
            let locked = expectation(description: "Finalization encountered a protected-file access error")
            var available = true
            var attempts = 0
            let finalization = Task {
                try await VoiceTutorProtectedRecordingAccess.finalize(
                    notificationCenter: center,
                    isAvailable: { available },
                    isCurrent: { true },
                    operation: { @MainActor in
                        attempts += 1
                        if attempts == 1 {
                            available = false
                            center.post(name: UIApplication.protectedDataWillBecomeUnavailableNotification, object: nil)
                            locked.fulfill()
                            throw failure
                        }
                        return try Data(contentsOf: source)
                    }
                )
            }
            defer { finalization.cancel() }
            let ready = await XCTWaiter.fulfillment(of: [locked], timeout: 2)
            guard ready == .completed else { XCTFail("Finalization did not start"); return }
            XCTAssertEqual(attempts, 1)
            XCTAssertEqual(try Data(contentsOf: source), audio, "A temporary protected-file error must retain the originals")
            available = true
            center.post(name: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil)
            let recoveredAudio = try await finalization.value
            XCTAssertEqual(recoveredAudio, audio)
            XCTAssertEqual(attempts, 2)
        }
    }

    @MainActor
    func testRecordingFinalizationRetriesAnAccessErrorEvenWhenUnlockPrecedesItsDelivery() async throws {
        let center = NotificationCenter()
        var attempts = 0
        let result = try await VoiceTutorProtectedRecordingAccess.finalize(
            notificationCenter: center,
            isAvailable: { true },
            isCurrent: { true },
            operation: { @MainActor in
                attempts += 1
                if attempts == 1 {
                    center.post(name: UIApplication.protectedDataWillBecomeUnavailableNotification, object: nil)
                    center.post(name: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil)
                    throw NSError(domain: NSPOSIXErrorDomain, code: Int(EACCES))
                }
                return "saved"
            }
        )
        XCTAssertEqual(result, "saved")
        XCTAssertEqual(attempts, 2)
    }

    @MainActor
    func testRecordingFinalizationDoesNotRetryUnrelatedOrUnexplainedAccessFailures() async {
        for failure in [
            NSError(domain: NSCocoaErrorDomain, code: NSFileReadNoSuchFileError),
            NSError(domain: NSCocoaErrorDomain, code: NSFileReadCorruptFileError),
            NSError(domain: NSCocoaErrorDomain, code: NSFileReadNoPermissionError)
        ] {
            var attempts = 0
            do {
                let _: Bool = try await VoiceTutorProtectedRecordingAccess.finalize(
                    notificationCenter: NotificationCenter(), isAvailable: { true }, isCurrent: { true },
                    operation: { @MainActor in attempts += 1; throw failure }
                )
                XCTFail("A permanent failure must reach existing cleanup")
            } catch {
                XCTAssertEqual((error as NSError).domain, failure.domain)
                XCTAssertEqual((error as NSError).code, failure.code)
                XCTAssertEqual(attempts, 1)
            }
        }
    }

    @MainActor
    func testRecordingFinalizationDoesNotRetryAfterAccountInvalidation() async {
        var isCurrent = true
        var attempts = 0
        let center = NotificationCenter()
        do {
            let _: Bool = try await VoiceTutorProtectedRecordingAccess.finalize(
                notificationCenter: center, isAvailable: { true }, isCurrent: { isCurrent },
                operation: { @MainActor in
                    attempts += 1
                    center.post(name: UIApplication.protectedDataWillBecomeUnavailableNotification, object: nil)
                    isCurrent = false
                    throw NSError(domain: NSCocoaErrorDomain, code: NSFileReadNoPermissionError)
                }
            )
            XCTFail("The invalidated owner must reach existing purge cleanup")
        } catch {
            guard case VoiceTutorRecordingError.lifecycleInvalidated = error else {
                XCTFail("Expected lifecycle invalidation, got \(error)")
                return
            }
            XCTAssertEqual(attempts, 1)
        }
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

    func testLearnerFinalTranscriptDeduplicatesItemsWithoutDeduplicatingSpeechText() {
        let attempt = UUID()
        var state = VoiceTutorLearnerTranscriptState()
        state.beginAttempt(attempt)

        XCTAssertTrue(state.accept(transcript: "다시 설명해 줘", itemID: "item-1", attemptID: attempt, requiresItemID: true))
        XCTAssertFalse(state.accept(transcript: "다시 설명해 줘", itemID: "item-1", attemptID: attempt, requiresItemID: true))
        XCTAssertFalse(state.accept(transcript: "변경된 중복 결과", itemID: "item-1", attemptID: attempt, requiresItemID: true))
        XCTAssertTrue(state.accept(transcript: "다시 설명해 줘", itemID: "item-2", attemptID: attempt, requiresItemID: true),
                      "A new utterance may deliberately repeat the same words")
    }

    func testLearnerFinalTranscriptIdentityEndsWithItsConnectionAttempt() {
        let oldAttempt = UUID()
        let newAttempt = UUID()
        var state = VoiceTutorLearnerTranscriptState()
        state.beginAttempt(oldAttempt)
        XCTAssertTrue(state.accept(transcript: "첫 통화", itemID: "item-1", attemptID: oldAttempt, requiresItemID: true))
        state.beginAttempt(newAttempt)
        XCTAssertFalse(state.accept(transcript: "늦게 도착한 이전 통화", itemID: "item-2", attemptID: oldAttempt, requiresItemID: true))
        XCTAssertTrue(state.accept(transcript: "새 통화", itemID: "item-1", attemptID: newAttempt, requiresItemID: true))
        state.endLocally()
        XCTAssertFalse(state.accept(transcript: "종료 이후", itemID: "item-3", attemptID: newAttempt, requiresItemID: true))
        XCTAssertFalse(state.accept(transcript: "이전 계정", itemID: nil, attemptID: oldAttempt, requiresItemID: false))
    }

    func testLearnerFinalTranscriptRequiresNativeItemIdentityAndPreservesLegacyFrames() {
        let attempt = UUID()
        var state = VoiceTutorLearnerTranscriptState()
        state.beginAttempt(attempt)
        XCTAssertFalse(state.accept(transcript: "텍스트", itemID: nil, attemptID: attempt, requiresItemID: true))
        XCTAssertFalse(state.accept(transcript: "텍스트", itemID: "", attemptID: attempt, requiresItemID: true))
        XCTAssertFalse(state.accept(transcript: " \n ", itemID: "item-1", attemptID: attempt, requiresItemID: true))
        XCTAssertTrue(state.accept(transcript: "완성된 문장", itemID: "item-1", attemptID: attempt, requiresItemID: true),
                      "An empty frame must not consume a later visible item's identity")
        XCTAssertTrue(state.accept(transcript: "기존 PCM", itemID: nil, attemptID: attempt, requiresItemID: false))
        XCTAssertTrue(state.accept(transcript: "기존 PCM", itemID: nil, attemptID: attempt, requiresItemID: false))
    }

    func testLearnerFinalTranscriptDeduplicationOutlivesVisibleCaptionTrimmingAndIsBounded() {
        let attempt = UUID()
        var state = VoiceTutorLearnerTranscriptState()
        state.beginAttempt(attempt)
        var captions: [VoiceTutorCaption] = []
        for index in 0..<VoiceTutorLearnerTranscriptState.maximumItemCount {
            let text = "발화 \(index)"
            if state.accept(transcript: text, itemID: "item-\(index)", attemptID: attempt, requiresItemID: true) {
                captions.append(VoiceTutorCaption(speaker: .learner, text: text))
                VoiceTutorLiveTextBounds.trim(&captions)
            } else {
                XCTFail("Every distinct input inside the call's bound must be accepted")
            }
        }
        XCTAssertEqual(captions.count, VoiceTutorLiveTextBounds.maximumCaptionCount)
        XCTAssertFalse(captions.contains { $0.text == "발화 0" })
        XCTAssertFalse(state.accept(transcript: "발화 0", itemID: "item-0", attemptID: attempt, requiresItemID: true),
                      "Trimming an old caption must not make its duplicate a new utterance")
        XCTAssertFalse(state.accept(transcript: "용량 초과", itemID: "overflow", attemptID: attempt, requiresItemID: true),
                      "Do not evict old identities and reopen them when the bounded call limit is reached")
    }

    func testLearnerFinalTranscriptParserRejectsMalformedIdentityAndText() throws {
        let type = "conversation.item.input_audio_transcription.completed"
        let frames: [[String: Any]] = [
            ["type": type, "transcript": "텍스트", "item_id": ""],
            ["type": type, "transcript": "텍스트", "item_id": "invalid id"],
            ["type": type, "transcript": "텍스트", "item_id": 7],
            ["type": type, "transcript": "텍스트", "item_id": NSNull()],
            ["type": type, "transcript": "텍스트", "item_id": String(repeating: "x", count: 192)],
            ["type": type, "transcript": 7, "item_id": "item-1"],
            ["type": type, "item_id": "item-1"]
        ]
        for frame in frames {
            let data = try JSONSerialization.data(withJSONObject: frame)
            XCTAssertEqual(try VoiceTutorRealtimeEventParser.parse(data: data), .ignored(type: type))
        }
        XCTAssertEqual(
            try VoiceTutorRealtimeEventParser.parse(text: #"{"type":"conversation.item.input_audio_transcription.completed","item_id":"item-1","transcript":"제가 말한 그대로"}"#),
            .userTranscript("제가 말한 그대로", itemID: "item-1")
        )
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
        let sourceURL = root.appendingPathComponent("StudyMate/Views/VoiceTutorView.swift")
        #if !targetEnvironment(simulator)
        guard FileManager.default.fileExists(atPath: sourceURL.path) else {
            throw XCTSkip("Source-contract check requires the local repository; behavior tests run on iPhone.")
        }
        #endif
        let source = try String(
            contentsOf: sourceURL,
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
                XCTFail("A stale bootstrap must not proceed to an authenticated voice request")
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
        // Preview and session creation share prepareVoiceTutorRegistration and
        // its identity fence. Exercise that bootstrap boundary without touching
        // the process-wide recording lifecycle or the user's recording files.
        let bootstrapRequest = Task {
            try await fixture.appState.loadVoiceTutorVoicePreview(voice: .alloy, language: .english)
        }
        defer { bootstrapRequest.cancel() }
        let bootstrapWait = await XCTWaiter.fulfillment(of: [bootstrapArrived], timeout: 5)
        guard bootstrapWait == .completed else {
            return XCTFail("Token bootstrap did not reach the fixture; account-replacement assertions require an in-flight request")
        }

        fixture.replaceAccount(ownerUserID: 8, registration: registrationB)
        releaseResponse.open()
        do {
            _ = try await bootstrapRequest.value
            XCTFail("A token bootstrap response must not authorize a voice request after account replacement")
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
        let staleStatus = await status.value
        let staleSummary = await summary.value

        XCTAssertNil(staleStatus, "An old account's response cannot authorize quick call admission.")
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
            XCTAssertLessThanOrEqual(preflight.count, 8)
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
        try VoiceTutorEchoCancellationPolicy.configureAudioSession(audioSession)
        XCTAssertEqual(audioSession.category, .playAndRecord)
        XCTAssertEqual(audioSession.mode, .default,
                       "Software AEC must use the media gain instead of the chat mode's VPIO-dependent loudness")
        XCTAssertTrue(audioSession.categoryOptions.contains(.defaultToSpeaker))
        XCTAssertTrue(audioSession.categoryOptions.contains(.allowBluetoothHFP))
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
        let outputDiagnostics = VoiceTutorOutputDiagnostics()
        device.observer = outputDiagnostics
        defer {
            outputDiagnostics.close()
            device.observer = nil
            withExtendedLifetime(outputDiagnostics) {}
        }
        try VoiceTutorEchoCancellationPolicy.prepareDevice(device)
        preflight.append(nativeVoiceCapturePreflight(phase: "factory_created", device: device))
        let source = factory.audioSource(with: nil)
        let track = factory.audioTrack(with: source, trackId: "synthetic-native-capture-probe")
        XCTAssertTrue(try VoiceTutorEchoCancellationPolicy.configure(track).isSuccess)
        // Reproduce connect(): provider input remains disabled until native
        // capture, ICE/DTLS and server readiness have all been established.
        // The former probe left this track enabled and started ADM directly,
        // so it could pass while production waited on an idle capture engine.
        track.isEnabled = false
        tap.updateGate(mediaReady: false, muted: false)
        var capturePeer: LKRTCPeerConnection?
        defer {
            tap.close()
            _ = device.stopRecording()
            _ = device.stopPlayout()
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
        let sender = try XCTUnwrap(peer.add(track, streamIds: ["synthetic-native-capture-probe"]),
                                   "Readiness must be checked with production's disabled local sender installed")
        XCTAssertEqual(sender.track?.trackId, track.trackId)
        XCTAssertEqual(sender.track?.isEnabled, false)
        preflight.append(nativeVoiceCapturePreflight(phase: "peer_initialized", device: device))
        XCTAssertTrue(factory.audioProcessingState.echoCancellation.isSoftwareActive)
        XCTAssertFalse(device.isRecording)
        XCTAssertFalse(VoiceTutorEchoCancellationPolicy.isActive(factory: factory),
                       "AEC configuration without recording must not satisfy connect readiness")
        XCTAssertEqual(tap.snapshot().validInputBufferCount, 0)
        XCTAssertFalse(track.isEnabled)
        XCTAssertFalse(tap.snapshot().gateEnabled)
        XCTAssertFalse(device.isPlaying)
        // Start input before SDP can initialize output. Initializing an output-
        // only graph first caused the real call's later input activation to
        // fail with SDK -4010 (recording device format unavailable).
        preflight.append(nativeVoiceCapturePreflight(phase: "before_native_start", device: device))
        try VoiceTutorEchoCancellationPolicy.startCaptureForReadiness(factory: factory, track: track)
        preflight.append(nativeVoiceCapturePreflight(phase: "after_native_start", device: device))
        XCTAssertTrue(device.isRecording)
        XCTAssertFalse(device.isPlaying, "Production prepares local input before negotiation can start output")
        XCTAssertFalse(track.isEnabled, "Starting local capture must not publish learner audio before session readiness")
        XCTAssertFalse(tap.snapshot().gateEnabled)
        let inputBuffersBeforePlayout = tap.snapshot().validInputBufferCount
        let playoutInitStatus = device.initPlayout()
        let playoutStartStatus = playoutInitStatus == 0 ? device.startPlayout() : nil
        preflight.append(nativeVoiceCapturePreflight(phase: "playout_started_after_capture", device: device)
            + "\nnativePlayoutInitStatus=\(playoutInitStatus)"
            + "\nnativePlayoutStartStatus=\(playoutStartStatus.map { String($0) } ?? "not_attempted")"
            + "\ninputBuffersBeforePlayout=\(inputBuffersBeforePlayout)")
        XCTAssertEqual(playoutInitStatus, 0)
        XCTAssertEqual(playoutStartStatus, 0)
        guard playoutInitStatus == 0, playoutStartStatus == 0 else { return }
        XCTAssertTrue(device.isPlaying)
        XCTAssertTrue(device.isRecording, "Adding output must retain the already prepared input path")
        let deadline = ProcessInfo.processInfo.systemUptime + 3
        while (tap.snapshot().validInputBufferCount < inputBuffersBeforePlayout + 5
                || !VoiceTutorEchoCancellationPolicy.isActive(factory: factory)
                || outputDiagnostics.snapshot().buffers == 0)
                && ProcessInfo.processInfo.systemUptime < deadline {
            try await Task.sleep(for: .milliseconds(50))
        }
        preflight.append(nativeVoiceCapturePreflight(phase: "capture_complete", device: device))
        let snapshot = tap.snapshot()
        let observed = diagnostics.snapshot()
        let output = outputDiagnostics.snapshot()
        let processing = factory.audioProcessingState
        let platform = device.platformAudioProcessingState
        let attachment = XCTAttachment(string: """
        initializationCount=\(snapshot.initializationCount)
        processedBufferCount=\(snapshot.processedBufferCount)
        validInputBufferCount=\(snapshot.validInputBufferCount)
        validInputFrameCount=\(snapshot.validInputFrameCount)
        sampleRate=\(snapshot.sampleRate)
        gateEnabled=\(snapshot.gateEnabled)
        trackEnabled=\(track.isEnabled)
        engineRunning=\(device.isEngineRunning)
        nativeRecording=\(device.isRecording)
        nativePlaying=\(device.isPlaying)
        mixerTapInstalled=\(output.tapInstalled)
        mixerOutputConnected=\(output.outputConnected)
        mixerBuffers=\(output.buffers)
        mixerFrames=\(output.frames)
        mixerMaxRMS=\(output.maxRMS)
        mixerMaxPeak=\(output.maxPeak)
        mixerVolume=\(output.mixerVolume.map { String($0) } ?? "unknown")
        outputHardwareSampleRate=\(output.outputSampleRate)
        outputHardwareChannels=\(output.outputChannelCount)
        inputBuffersBeforePlayout=\(inputBuffersBeforePlayout)
        speechInferenceCount=\(snapshot.speechInferenceCount)
        initializedDiagnosticCount=\(observed.initializationEvents)
        firstValidInputDiagnosticCount=\(observed.firstInputEvents)
        aecRequested=\(processing.echoCancellation.requested?.isEnabled == true)
        aecSoftwareResolved=\(processing.echoCancellation.isSoftwareResolved)
        aecSoftwareActive=\(processing.echoCancellation.isSoftwareActive)
        aecPlatformResolved=\(processing.echoCancellation.isPlatformResolved)
        aecPlatformActive=\(processing.echoCancellation.isPlatformActive)
        platformAECRequested=\(platform.echoCancellation.isRequested)
        platformAECActive=\(platform.echoCancellation.isActive)
        voiceProcessingEnabledRequested=\(platform.isVoiceProcessingEnabledRequested)
        voiceProcessingBypassedRequested=\(platform.isVoiceProcessingBypassedRequested)
        voiceProcessingEnabledActive=\(platform.isVoiceProcessingEnabledActive)
        voiceProcessingBypassedActive=\(platform.isVoiceProcessingBypassedActive)
        """)
        attachment.name = "native-voice-capture-metadata-only"
        attachment.lifetime = .keepAlways
        add(attachment)
        XCTAssertGreaterThan(snapshot.initializationCount, 0, "The actual native APM must initialize the installed capture delegate")
        XCTAssertGreaterThanOrEqual(snapshot.validInputBufferCount, 5,
                                    "A started device without native capture callbacks is a failure, not a passed probe")
        XCTAssertGreaterThanOrEqual(snapshot.validInputBufferCount - inputBuffersBeforePlayout, 5,
                                    "Real input must continue arriving after output starts, not just before the graph transition")
        XCTAssertGreaterThan(snapshot.validInputFrameCount, 0)
        XCTAssertGreaterThan(snapshot.sampleRate, 0)
        XCTAssertFalse(snapshot.gateEnabled, "Native callback delivery must be observable before session-ready gating")
        XCTAssertFalse(track.isEnabled, "Readiness checks must leave provider input disabled")
        XCTAssertTrue(device.isEngineRunning)
        XCTAssertTrue(device.isRecording)
        XCTAssertTrue(device.isPlaying)
        XCTAssertTrue(output.tapInstalled)
        XCTAssertTrue(output.outputConnected, "The observer must preserve the native mixer-to-output connection")
        XCTAssertGreaterThan(output.outputSampleRate, 0)
        XCTAssertGreaterThan(output.outputChannelCount, 0)
        XCTAssertGreaterThan(try XCTUnwrap(output.mixerVolume), 0)
        XCTAssertGreaterThan(output.buffers, 0, "The final native mixer must deliver actual output callbacks")
        XCTAssertGreaterThan(output.frames, 0)
        // This fixture supplies no remote signal. Silence is valid; RMS/peak
        // are recorded as diagnostics without claiming audible speaker output.
        XCTAssertEqual(audioSession.category, .playAndRecord)
        XCTAssertEqual(audioSession.mode, .default,
                       "Starting native duplex I/O must preserve the production software-AEC session mode")
        XCTAssertTrue(audioSession.categoryOptions.contains(.defaultToSpeaker),
                      "Default mode must retain the speaker preference after output starts")
        XCTAssertTrue(audioSession.categoryOptions.contains(.allowBluetoothHFP))
        XCTAssertEqual(snapshot.speechInferenceCount, 0, "Closed readiness gating must not process learner speech")
        XCTAssertEqual(observed.initializationEvents, 1)
        XCTAssertEqual(observed.firstInputEvents, 1)
        XCTAssertEqual(observed.activityEvents, 0)
        XCTAssertFalse(observed.firstInput?.gateEnabled ?? true)
        XCTAssertTrue(VoiceTutorEchoCancellationPolicy.isActive(factory: factory),
                      "Real processed input frames must have an active echo canceller before media-ready can open capture")
        XCTAssertFalse(device.isMicrophoneMuted, "AEC must not rely on muting the native microphone")
        // The disabled sender and input-first/output-second graph exercise the
        // production readiness ordering without SDP, ICE or a provider. This
        // verifies live duplex I/O, not residual echo from real remote speech.
        XCTAssertFalse(device.isPlatformVoiceProcessingAllowed)
        XCTAssertFalse(platform.echoCancellation.isRequested)
        XCTAssertFalse(platform.echoCancellation.isActive)
        XCTAssertFalse(platform.isVoiceProcessingEnabledRequested)
        XCTAssertFalse(platform.isVoiceProcessingEnabledActive)
        XCTAssertFalse(processing.echoCancellation.isPlatformActive)
        XCTAssertTrue(processing.echoCancellation.isSoftwareResolved)
        XCTAssertTrue(processing.echoCancellation.isSoftwareActive,
                      "Actual captured frames must pass through the selected render-reference software echo canceller")
        XCTAssertFalse(module.config.isEchoCancellationMobileMode)
        XCTAssertNil(peer.localDescription)
        XCTAssertNil(peer.remoteDescription)
        XCTAssertEqual(peer.senders.count, 1)
        XCTAssertTrue(peer.senders.allSatisfy { $0.track?.isEnabled == false })
        XCTAssertEqual(peer.iceGatheringState, .new, "The microphone probe must never gather ICE candidates")
        if verifySilero {
            // Additional explicit opt-in: observe the actual native callback →
            // copy → resampler → bundled Core ML path. Activity is counted only;
            // no SDP, provider, transmitted audio or audio storage is involved.
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
            trackEnabled=\(track.isEnabled)
            providerConnection=false
            recording=false
            """)
            acousticAttachment.name = "native-silero-capture-metadata-only"
            acousticAttachment.lifetime = .keepAlways
            add(acousticAttachment)
            XCTAssertGreaterThanOrEqual(acoustic.speechInferenceCount, 5,
                                        "Native callbacks must reach the real bundled model, not just the gate-disabled tap")
            XCTAssertTrue(acoustic.gateEnabled)
            XCTAssertFalse(track.isEnabled, "Local Silero verification must not enable provider audio publication")
            tap.updateGate(mediaReady: false, muted: false)
        }
        tap.close()
        XCTAssertEqual(device.stopRecording(), 0)
        XCTAssertFalse(device.isRecording, "Teardown must stop the explicitly started readiness capture")
        XCTAssertEqual(device.stopPlayout(), 0)
        XCTAssertFalse(device.isPlaying)
        XCTAssertFalse(track.isEnabled)
        XCTAssertFalse(tap.snapshot().gateEnabled)
        #endif
    }

    /// Emits only locally generated speech through the real speaker. Run with
    /// nobody speaking near the iPhone; a detected onset is reported as a test
    /// failure, not silently discarded as "environment noise". No provider,
    /// SDP, learner recording, transcript or question submission is involved.
    @MainActor
    func testOptInNativeEchoDoesNotCreateLearnerTurnsAfterInputHold() async throws {
        guard ProcessInfo.processInfo.environment["BUDDYSTUDY_NATIVE_ECHO_TEST"] == "1" else {
            throw XCTSkip("Physical speaker echo probe requires BUDDYSTUDY_NATIVE_ECHO_TEST=1 and explicit selection.")
        }
        #if targetEnvironment(simulator)
        throw XCTSkip("Speaker-to-microphone echo must be measured on a physical iPhone.")
        #else
        guard AVAudioApplication.shared.recordPermission == .granted else {
            throw XCTSkip("Microphone permission is not granted; the echo probe never requests permission.")
        }
        let foregroundDeadline = ProcessInfo.processInfo.systemUptime + 2
        while UIApplication.shared.applicationState != .active,
              ProcessInfo.processInfo.systemUptime < foregroundDeadline {
            try await Task.sleep(for: .milliseconds(50))
        }
        guard UIApplication.shared.applicationState == .active else {
            throw XCTSkip("The echo probe needs the foreground test host.")
        }
        // write() synthesizes a known fixture in memory; it does not speak via
        // a second AVAudioEngine that would bypass the AEC render reference.
        let syntheticSpeech = try await VoiceTutorEchoSyntheticSpeech.make()
        let scorer = try await VoiceTutorSileroSpeechScorer.prepareBundled()
        let session = AVAudioSession.sharedInstance()
        let previousCategory = session.category
        let previousMode = session.mode
        let previousOptions = session.categoryOptions
        let previousIODuration = session.preferredIOBufferDuration
        defer {
            try? session.setActive(false, options: [.notifyOthersOnDeactivation])
            try? session.setCategory(previousCategory, mode: previousMode, options: previousOptions)
            try? session.setPreferredIOBufferDuration(previousIODuration)
        }
        try VoiceTutorEchoCancellationPolicy.configureAudioSession(session)
        guard session.isInputAvailable,
              session.currentRoute.outputs.contains(where: { $0.portType == .builtInSpeaker }),
              session.outputVolume > 0 else {
            throw XCTSkip("The physical echo probe needs an available microphone and a nonzero built-in speaker route; it never changes volume or forces a route.")
        }

        let measurements = VoiceTutorEchoProbeMeasurements()
        let productionTap = VoiceTutorLocalSpeechCaptureTap(
            recordingTap: nil,
            speechScorer: scorer,
            onActivity: { measurements.observeActivity($0) },
            onFailure: { measurements.observeFailure($0.rawValue) }
        )
        let capture = VoiceTutorEchoProbeCaptureDelegate(tap: productionTap, measurements: measurements)
        let render = VoiceTutorEchoProbeRenderDelegate(speech: syntheticSpeech)
        let module = try VoiceTutorAudioProcessingModuleFactory.make(
            captureDelegate: capture, renderDelegate: render
        )
        let factory = LKRTCPeerConnectionFactory(
            audioDeviceModuleType: .audioEngine, bypassVoiceProcessing: false,
            encoderFactory: nil, decoderFactory: nil, audioProcessingModule: module
        )
        let device = factory.audioDeviceModule
        let output = VoiceTutorOutputDiagnostics()
        device.observer = output
        var peer: LKRTCPeerConnection?
        defer {
            render.stop()
            productionTap.close()
            _ = device.stopRecording()
            _ = device.stopPlayout()
            peer?.close()
            output.close()
            device.observer = nil
            module.capturePostProcessingDelegate = nil
            module.renderPreProcessingDelegate = nil
            withExtendedLifetime((factory, module, capture, render, productionTap, output, peer)) {}
        }
        try VoiceTutorEchoCancellationPolicy.prepareDevice(device)
        let track = factory.audioTrack(with: factory.audioSource(with: nil), trackId: "synthetic-echo-probe")
        try VoiceTutorEchoCancellationPolicy.configure(track)
        track.isEnabled = false
        productionTap.updateGate(mediaReady: true, muted: true)
        let configuration = LKRTCConfiguration()
        configuration.sdpSemantics = .unifiedPlan
        configuration.iceServers = []
        configuration.iceTransportPolicy = .none
        configuration.iceCandidatePoolSize = 0
        let constraints = LKRTCMediaConstraints(
            mandatoryConstraints: ["OfferToReceiveAudio": "false", "OfferToReceiveVideo": "false"],
            optionalConstraints: nil
        )
        peer = factory.peerConnection(with: configuration, constraints: constraints, delegate: nil)
        let initializedPeer = try XCTUnwrap(peer)
        _ = try XCTUnwrap(initializedPeer.add(track, streamIds: ["synthetic-echo-probe"]))
        try VoiceTutorEchoCancellationPolicy.startCaptureForReadiness(factory: factory, track: track)
        XCTAssertEqual(device.initPlayout(), 0)
        XCTAssertEqual(device.startPlayout(), 0)
        let readinessDeadline = ProcessInfo.processInfo.systemUptime + 3
        while (productionTap.snapshot().validInputBufferCount < 5 || render.snapshot().callbacks < 5),
              ProcessInfo.processInfo.systemUptime < readinessDeadline {
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertTrue(VoiceTutorEchoCancellationPolicy.isActive(factory: factory))
        XCTAssertGreaterThanOrEqual(productionTap.snapshot().validInputBufferCount, 5)
        XCTAssertGreaterThanOrEqual(render.snapshot().callbacks, 5)
        guard session.currentRoute.outputs.contains(where: { $0.portType == .builtInSpeaker }),
              session.outputVolume > 0 else {
            throw XCTSkip("The speaker route became unavailable during native startup; no echo result is claimed.")
        }

        // Verify the actual LKRTCAudioBuffer path with deterministic nonzero
        // samples, not just the microphone's possibly silent ambient input.
        // The sender remains disabled. Reset VAD after this synthetic input so
        // it contributes no evidence to the subsequent acoustic measurement.
        capture.setSyntheticGateProbe(.held)
        try await Task.sleep(for: .milliseconds(160))
        capture.setSyntheticGateProbe(nil)
        productionTap.updateGate(mediaReady: true, muted: false)
        capture.setSyntheticGateProbe(.open)
        try await Task.sleep(for: .milliseconds(160))
        capture.setSyntheticGateProbe(nil)
        productionTap.updateGate(mediaReady: true, muted: true)

        // Positive control: the same synthesized voice must produce speech
        // through this native callback and the real scorer when placed directly
        // at capture. It is never sent or played and is not a double-talk test.
        measurements.beginPhase("speech_pipeline_control")
        capture.setSyntheticSpeechProbe(syntheticSpeech)
        productionTap.updateGate(mediaReady: true, muted: false)
        try await Task.sleep(for: .seconds(3))
        productionTap.updateGate(mediaReady: true, muted: true)
        capture.setSyntheticSpeechProbe(nil)

        // Nine seconds of acoustic observation: the first three exercise a
        // form hold, the next four reopen while tutor speech keeps playing,
        // and the last two expose a delayed physical echo tail. The renderer
        // uses the same sample stream throughout the hold/resume transition.
        measurements.beginPhase("held")
        let renderBeforeHold = render.snapshot()
        let outputBeforeHold = output.snapshot()
        render.start()
        try await Task.sleep(for: .seconds(3))
        let renderAfterHold = render.snapshot()
        let outputAfterHold = output.snapshot()
        measurements.beginPhase("resumed")
        productionTap.updateGate(mediaReady: true, muted: false)
        try await Task.sleep(for: .seconds(4))
        render.stop()
        let renderAfterResume = render.snapshot()
        let outputAfterResume = output.snapshot()
        measurements.beginPhase("tail")
        try await Task.sleep(for: .seconds(2))
        productionTap.updateGate(mediaReady: true, muted: true)

        let phases = measurements.snapshot()
        let renderSnapshot = render.snapshot()
        let outputSnapshot = output.snapshot()
        let gateProbe = capture.gateProbeSnapshot()
        let payload: [String: Any] = [
            "phases": phases.mapValues { $0.json },
            "syntheticGateProbe": gateProbe,
            "failures": measurements.failures(),
            "renderCallbacks": renderSnapshot.callbacks,
            "renderedSpeechFrames": renderSnapshot.speechFrames,
            "renderSampleRate": renderSnapshot.sampleRate,
            "renderPeak": renderSnapshot.peak,
            "mixerFrames": outputSnapshot.frames,
            "mixerPeak": outputSnapshot.maxPeak,
            "mixerRMS": outputSnapshot.maxRMS,
            "heldRenderedSpeechFrames": renderAfterHold.speechFrames - renderBeforeHold.speechFrames,
            "resumedRenderedSpeechFrames": renderAfterResume.speechFrames - renderAfterHold.speechFrames,
            "heldMixerFrames": outputAfterHold.frames - outputBeforeHold.frames,
            "resumedMixerFrames": outputAfterResume.frames - outputAfterHold.frames,
            "systemOutputVolume": session.outputVolume,
            "finalBuiltInSpeaker": session.currentRoute.outputs.contains(where: { $0.portType == .builtInSpeaker }),
            "softwareAECActive": factory.audioProcessingState.echoCancellation.isSoftwareActive,
            "platformAECActive": factory.audioProcessingState.echoCancellation.isPlatformActive,
            "speechInferences": productionTap.snapshot().speechInferenceCount,
            "providerConnection": false,
            "learnerAudioStored": false,
            "syntheticSpeechStored": false
        ]
        let attachment = XCTAttachment(data: try JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted, .sortedKeys]),
                                       uniformTypeIdentifier: "public.json")
        attachment.name = "native-speaker-echo-hold-resume-metadata-only"
        attachment.lifetime = .keepAlways
        add(attachment)
        XCTAssertTrue(measurements.failures().isEmpty, "Native capture/Silero errors must not be mistaken for echo-free silence")
        XCTAssertGreaterThan(gateProbe["heldSamples"] ?? 0, 480)
        XCTAssertGreaterThan(gateProbe["openSamples"] ?? 0, 480)
        XCTAssertEqual(gateProbe["heldMismatches"], 0, "Every held sample must be zero after the production tap")
        XCTAssertEqual(gateProbe["openMismatches"], 0, "The open production tap must preserve every injected PCM sample")
        XCTAssertGreaterThan(renderSnapshot.speechFrames, 48_000)
        XCTAssertGreaterThan(renderSnapshot.peak, 0.01)
        XCTAssertGreaterThan(outputSnapshot.maxPeak, 0.001, "Synthesized speech must reach the final native mixer")
        XCTAssertGreaterThan(renderAfterHold.speechFrames - renderBeforeHold.speechFrames, 16_000)
        XCTAssertGreaterThan(renderAfterResume.speechFrames - renderAfterHold.speechFrames, 16_000)
        XCTAssertGreaterThan(outputAfterHold.frames - outputBeforeHold.frames, 16_000)
        XCTAssertGreaterThan(outputAfterResume.frames - outputAfterHold.frames, 16_000)
        XCTAssertTrue(session.currentRoute.outputs.contains(where: { $0.portType == .builtInSpeaker }))
        XCTAssertGreaterThan(session.outputVolume, 0)
        XCTAssertTrue(device.isPlaying)
        XCTAssertTrue(device.isRecording)
        XCTAssertTrue(device.isEngineRunning)
        XCTAssertTrue(VoiceTutorEchoCancellationPolicy.isActive(factory: factory))
        XCTAssertGreaterThan(productionTap.snapshot().speechInferenceCount, 10)
        let control = try XCTUnwrap(phases["speech_pipeline_control"])
        XCTAssertGreaterThan(control.speechStarts, 0, "The same synthetic voice must be recognized by the actual capture/scorer path without acoustic cancellation")
        let held = try XCTUnwrap(phases["held"])
        let resumed = try XCTUnwrap(phases["resumed"])
        let tail = try XCTUnwrap(phases["tail"])
        XCTAssertGreaterThan(held.frames, 5_000)
        XCTAssertEqual(held.postGatePeak, 0, "A form hold must zero actual outgoing PCM after AEC, not only set a mute flag")
        XCTAssertEqual(held.speechStarts, 0, "A held microphone must not publish learner speech")
        XCTAssertGreaterThan(resumed.frames, 5_000)
        XCTAssertEqual(resumed.speechStarts, 0, "Tutor-only speaker playback must not become a learner turn after reopening the gate; environmental speech also fails this controlled probe")
        XCTAssertGreaterThan(tail.frames, 5_000)
        XCTAssertEqual(tail.speechStarts, 0, "A tutor playback tail must not become a new learner turn")
        XCTAssertFalse(track.isEnabled)
        XCTAssertNil(initializedPeer.localDescription)
        XCTAssertNil(initializedPeer.remoteDescription)
        XCTAssertEqual(initializedPeer.iceGatheringState, .new)
        XCTAssertTrue(initializedPeer.senders.allSatisfy { $0.track?.isEnabled == false })
        #endif
    }

    @MainActor
    func testOptInLegacyVoiceProcessingConfiguresAnUnbypassedDuplexGraph() throws {
        guard ProcessInfo.processInfo.environment["BUDDYSTUDY_NATIVE_VOICE_CAPTURE_TEST"] == "1" else {
            throw XCTSkip("Native voice-processing probe requires BUDDYSTUDY_NATIVE_VOICE_CAPTURE_TEST=1.")
        }
        #if targetEnvironment(simulator)
        throw XCTSkip("Apple Voice Processing I/O must be verified on a physical iPhone.")
        #else
        guard AVAudioApplication.shared.recordPermission == .granted,
              UIApplication.shared.applicationState == .active else {
            throw XCTSkip("Voice-processing probe needs an active test host with microphone permission already granted.")
        }
        let session = AVAudioSession.sharedInstance()
        let previousCategory = session.category
        let previousMode = session.mode
        let previousOptions = session.categoryOptions
        let engine = AVAudioEngine()
        defer {
            engine.stop()
            try? engine.inputNode.setVoiceProcessingEnabled(false)
            try? session.setActive(false, options: [.notifyOthersOnDeactivation])
            try? session.setCategory(previousCategory, mode: previousMode, options: previousOptions)
        }
        try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetoothHFP])
        try session.setActive(true)
        guard session.isInputAvailable else {
            throw XCTSkip("No microphone route is available for Voice Processing I/O.")
        }

        try VoiceTutorPCMVoiceProcessing.configure(engine)
        XCTAssertTrue(engine.inputNode.isVoiceProcessingEnabled)
        XCTAssertTrue(engine.outputNode.isVoiceProcessingEnabled,
                      "Input capture and tutor playout must use the same native duplex processing graph")
        XCTAssertFalse(engine.inputNode.isVoiceProcessingBypassed)
        XCTAssertTrue(engine.inputNode.isVoiceProcessingAGCEnabled)
        engine.inputNode.isVoiceProcessingBypassed = true
        try VoiceTutorPCMVoiceProcessing.configure(engine)
        XCTAssertFalse(engine.inputNode.isVoiceProcessingBypassed,
                       "A reused graph must not retain a previous bypass state")
        XCTAssertFalse(engine.isRunning, "This configuration probe never captures or plays audio")
        let attachment = XCTAttachment(string: """
        \(nativeVoiceCapturePreflight(phase: "legacy_vpio_configured"))
        inputVoiceProcessing=\(engine.inputNode.isVoiceProcessingEnabled)
        outputVoiceProcessing=\(engine.outputNode.isVoiceProcessingEnabled)
        voiceProcessingBypassed=\(engine.inputNode.isVoiceProcessingBypassed)
        voiceProcessingAGC=\(engine.inputNode.isVoiceProcessingAGCEnabled)
        engineRunning=\(engine.isRunning)
        """)
        attachment.name = "legacy-voice-processing-configuration-metadata-only"
        attachment.lifetime = .keepAlways
        add(attachment)
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
            "inputPorts=\(session.currentRoute.inputs.map { $0.portType.rawValue }.joined(separator: ","))",
            "outputPorts=\(session.currentRoute.outputs.map { $0.portType.rawValue }.joined(separator: ","))",
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
        let conversation = fixture.language == .english
            ? [VoiceTutorCaption(speaker: .learner, text: "How does a cache expire?"),
               VoiceTutorCaption(speaker: .tutor, text: "A time to live lets Redis remove a value when its deadline passes.")]
            : [VoiceTutorCaption(speaker: .learner, text: "Redis에서 캐시 만료는 어떻게 정하나요?"),
               VoiceTutorCaption(speaker: .tutor, text: "데이터가 얼마나 자주 바뀌는지에 맞춰 만료 시간을 정하면 돼요.")]
        let captions = fixture.learnerOnly
            ? [VoiceTutorCaption(speaker: .learner, text: fixture.language == .english ? "Can you hear me?" : "어, 들려?"),
               VoiceTutorCaption(speaker: .learner, text: fixture.language == .english ? "Are you there?" : "들리냐고?")]
            : conversation
        let root = NavigationStack {
            VoiceTutorCallScreen(
                topic: fixture.topic,
                presentation: VoiceTutorCallPresentation(
                    phase: fixture.phase,
                    failureCause: fixture.failureCause,
                    serverEndReason: fixture.serverEndReason,
                    isRecording: fixture.isRecording,
                    isAwaitingTutorResponse: fixture.isAwaitingTutorResponse,
                    pauseState: fixture.pauseState,
                    sessionState: fixture.sessionState,
                    sessionSecondsRemaining: fixture.seconds,
                    quotaRemainingSeconds: fixture.quotaRemainingSeconds
                        ?? (fixture.phase.isLive ? 0 : 3_540),
                    quotaReservedSeconds: fixture.phase.isLive ? 3_600 : 0,
                    quotaLimitSeconds: fixture.quotaLimitSeconds,
                    detail: fixture.detail
                ),
                strings: strings,
                captions: fixture.answerDraftState.isActive
                    ? [VoiceTutorCaption(speaker: .tutor, text: fixture.language == .english
                        ? "How do Spring Framework, Spring Boot, and Spring Data differ?"
                        : "스프링 프레임워크, 스프링 부트, 스프링 데이터의 차이를 설명해 주세요.")]
                    : (fixture.showsTranscript || fixture.showsPreview ? captions : []),
                errorMessage: fixture.phase == .failed
                    ? (fixture.failureCause == .provider
                        ? strings.serviceTemporarilyUnavailable
                        : strings.voiceTutorConnectionFailed)
                    : nil,
                showsTranscript: .constant(fixture.showsTranscript),
                showsSummary: .constant(false),
                onEnd: { XCTFail("A visual fixture must never end a real call") },
                onRetry: { XCTFail("A visual fixture must never start a real call") },
                onDismiss: { XCTFail("A visual fixture must never dismiss the real call screen") },
                answerDraftState: fixture.answerDraftState,
                answerDraftText: .constant(fixture.answerDraftState.text),
                canSubmitAnswer: fixture.answerDraftState.canSubmit,
                onFinishAnswer: { XCTFail("A visual fixture must never finish microphone capture") },
                onSubmitAnswer: { XCTFail("A visual fixture must never submit an answer") },
                canSkipAnswer: fixture.answerDraftState.canSkip,
                onSkipAnswer: { XCTFail("A visual fixture must never skip a saved question") },
                canCancelLearning: fixture.answerDraftState.canCancel,
                onCancelLearning: { XCTFail("A visual fixture must never cancel a saved question") }
            )
            .navigationTitle(strings.voiceTutorCallTitle)
            .navigationBarTitleDisplayMode(.inline)
        }
        .id(fixture.name)
        .environment(\.colorScheme, fixture.colorScheme)
        .environment(\.locale, Locale(identifier: fixture.language == .english ? "en_US" : "ko_KR"))
        .dynamicTypeSize(fixture.dynamicType)
        let controller = UIHostingController(rootView: root)
        controller.overrideUserInterfaceStyle = fixture.colorScheme == .dark ? .dark : .light
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
        window.overrideUserInterfaceStyle = fixture.colorScheme == .dark ? .dark : .light
        window.rootViewController = controller
        // Give this synthetic screen a fully presented window, then restore the
        // original key window even
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

        // Request a fresh committed frame before capturing the synthetic window.
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
        // Opt-in time for an external simctl screenshot of the OS composite.
        // Hosted unit tests cannot use XCUIScreen; drawHierarchy can omit
        // independently composited SwiftUI layers on iOS 26.
        if ProcessInfo.processInfo.environment["BUDDYSTUDY_VOICE_CAPTURE_COMPOSITE"] == "1" {
            print("VOICE_SNAPSHOT_READY:\(fixture.name)")
            try await Task.sleep(for: .seconds(5))
        }
        return UIGraphicsImageRenderer(size: fixture.size).image { _ in
            window.drawHierarchy(in: bounds, afterScreenUpdates: true)
        }
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
            _ = try await fixture.appState.createVoiceTutorConnection()
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

private struct VoiceTutorEchoSyntheticSpeech: Sendable {
    let samples: [Float]
    let sampleRate: Double

    @MainActor
    static func make() async throws -> Self {
        guard let voice = AVSpeechSynthesisVoice.speechVoices().first(where: { $0.language == "ko-KR" }) else {
            throw XCTSkip("The offline echo fixture needs an installed Korean system voice.")
        }
        let collector = VoiceTutorEchoSynthesisCollector()
        let synthesizer = AVSpeechSynthesizer()
        let utterance = AVSpeechUtterance(string: "취소했어요. 편하게 이야기해 주세요. 다음 주제로 계속 공부할게요.")
        utterance.voice = voice
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate
        defer { synthesizer.stopSpeaking(at: .immediate) }
        synthesizer.write(utterance) { collector.append($0) }
        let deadline = ProcessInfo.processInfo.systemUptime + 6
        while !collector.isComplete, ProcessInfo.processInfo.systemUptime < deadline {
            try await Task.sleep(for: .milliseconds(20))
        }
        return try collector.result()
    }
}

/// Holds generated fixture audio only. Live microphone data never enters this
/// collector, an attachment, a log or a file.
private final class VoiceTutorEchoSynthesisCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var samples: [Float] = []
    private var sampleRate = 0.0
    private var complete = false
    private var failure: String?

    var isComplete: Bool {
        lock.lock()
        defer { lock.unlock() }
        return complete
    }

    func append(_ buffer: AVAudioBuffer) {
        lock.lock()
        defer { lock.unlock() }
        guard !complete else { return }
        guard let pcm = buffer as? AVAudioPCMBuffer else {
            failure = "System speech synthesis returned a non-PCM buffer"
            complete = true
            return
        }
        let frames = Int(pcm.frameLength)
        guard frames > 0 else { complete = true; return }
        let rate = pcm.format.sampleRate
        let channels = Int(pcm.format.channelCount)
        guard rate.isFinite, (8_000...96_000).contains(rate), channels > 0, channels <= 8,
              sampleRate == 0 || sampleRate == rate,
              samples.count + frames <= Int(rate * 12) else {
            failure = "System speech synthesis exceeded the bounded fixture format or duration"
            complete = true
            return
        }
        let interleaved = pcm.format.isInterleaved
        for frame in 0..<frames {
            var mono = 0.0
            for channel in 0..<channels {
                let index = interleaved ? frame * channels + channel : frame
                let channelIndex = interleaved ? 0 : channel
                if let pointer = pcm.floatChannelData {
                    mono += Double(pointer[channelIndex][index])
                } else if let pointer = pcm.int16ChannelData {
                    mono += Double(pointer[channelIndex][index]) / 32_768
                } else {
                    failure = "System speech synthesis returned an unsupported PCM format"
                    complete = true
                    return
                }
            }
            guard mono.isFinite else {
                failure = "System speech synthesis returned non-finite samples"
                complete = true
                return
            }
            samples.append(Float(max(-1, min(1, mono / Double(channels)))))
        }
        sampleRate = rate
    }

    func result() throws -> VoiceTutorEchoSyntheticSpeech {
        lock.lock()
        defer { lock.unlock() }
        guard complete, failure == nil, sampleRate > 0,
              samples.count >= Int(sampleRate / 2),
              samples.contains(where: { abs($0) > 0.01 }) else {
            throw NSError(domain: "VoiceTutorEchoProbe", code: 1, userInfo: [
                NSLocalizedDescriptionKey: failure ?? "System speech synthesis did not produce bounded nonzero speech within six seconds"
            ])
        }
        return VoiceTutorEchoSyntheticSpeech(samples: samples, sampleRate: sampleRate)
    }
}

private struct VoiceTutorEchoProbePhase {
    var frames = 0
    var buffers = 0
    var preGatePeak = 0.0
    var postGatePeak = 0.0
    var preGateRMS = 0.0
    var postGateRMS = 0.0
    var speechStarts = 0
    var speechStops = 0

    var json: [String: Any] {
        ["frames": frames, "buffers": buffers, "preGatePeak": preGatePeak,
         "postGatePeak": postGatePeak, "maxPreGateRMS": preGateRMS,
         "maxPostGateRMS": postGateRMS, "speechStarts": speechStarts,
         "speechStops": speechStops]
    }
}

private final class VoiceTutorEchoProbeMeasurements: @unchecked Sendable {
    private let lock = NSLock()
    private var phase = "preflight"
    private var phases: [String: VoiceTutorEchoProbePhase] = [:]
    private var errors: [String] = []

    func beginPhase(_ name: String) {
        lock.lock()
        phase = name
        phases[name] = VoiceTutorEchoProbePhase()
        lock.unlock()
    }

    func observeActivity(_ activity: VoiceTutorLocalSpeechEvent) {
        lock.lock()
        defer { lock.unlock() }
        var value = phases[phase, default: VoiceTutorEchoProbePhase()]
        if activity.activity == .started { value.speechStarts += 1 }
        else { value.speechStops += 1 }
        phases[phase] = value
    }

    func observeFailure(_ code: String) {
        lock.lock()
        if errors.count < 4 { errors.append(code) }
        lock.unlock()
    }

    func observe(frames: Int, before: (Double, Double), after: (Double, Double)) {
        lock.lock()
        defer { lock.unlock() }
        var value = phases[phase, default: VoiceTutorEchoProbePhase()]
        value.buffers += 1
        value.frames += frames
        value.preGatePeak = max(value.preGatePeak, before.0)
        value.postGatePeak = max(value.postGatePeak, after.0)
        value.preGateRMS = max(value.preGateRMS, before.1)
        value.postGateRMS = max(value.postGateRMS, after.1)
        phases[phase] = value
    }

    func snapshot() -> [String: VoiceTutorEchoProbePhase] {
        lock.lock()
        defer { lock.unlock() }
        return phases
    }

    func failures() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return errors
    }
}

/// Measures only aggregate levels around the production post-AEC gate. The
/// underlying LKRTCAudioBuffer is never retained and raw microphone samples
/// are never copied. The short synthetic probe is separate from acoustic VAD.
private final class VoiceTutorEchoProbeCaptureDelegate: NSObject, LKRTCAudioCustomProcessingDelegate, @unchecked Sendable {
    enum SyntheticGateProbe: Equatable { case held, open }
    private let lock = NSLock()
    private let tap: VoiceTutorLocalSpeechCaptureTap
    private let measurements: VoiceTutorEchoProbeMeasurements
    private var probe: SyntheticGateProbe?
    private var syntheticSpeech: VoiceTutorEchoSyntheticSpeech?
    private var speechPosition = 0.0
    private var sampleRate = 0.0
    private var counts = ["heldSamples": 0, "openSamples": 0, "heldMismatches": 0, "openMismatches": 0]

    init(tap: VoiceTutorLocalSpeechCaptureTap, measurements: VoiceTutorEchoProbeMeasurements) {
        self.tap = tap
        self.measurements = measurements
        super.init()
    }

    func setSyntheticGateProbe(_ value: SyntheticGateProbe?) {
        lock.lock()
        probe = value
        lock.unlock()
    }

    func setSyntheticSpeechProbe(_ value: VoiceTutorEchoSyntheticSpeech?) {
        lock.lock()
        syntheticSpeech = value
        speechPosition = 0
        lock.unlock()
    }

    func gateProbeSnapshot() -> [String: Int] {
        lock.lock()
        defer { lock.unlock() }
        return counts
    }

    func audioProcessingInitialize(sampleRate: Int, channels: Int) {
        lock.lock()
        self.sampleRate = Double(sampleRate)
        lock.unlock()
        tap.audioProcessingInitialize(sampleRate: sampleRate, channels: channels)
    }

    func audioProcessingProcess(audioBuffer: LKRTCAudioBuffer) {
        lock.lock()
        defer { lock.unlock() }
        let frames = Int(audioBuffer.frames)
        let channels = Int(audioBuffer.channels)
        guard frames > 0, frames <= 48_000, channels > 0, channels <= 32 else {
            measurements.observeFailure("invalid_native_capture_buffer")
            tap.audioProcessingProcess(audioBuffer: audioBuffer)
            return
        }
        if probe != nil {
            for channel in 0..<channels {
                audioBuffer.rawBuffer(forChannel: channel).update(repeating: 4_096, count: frames)
            }
        }
        if let speech = syntheticSpeech, sampleRate > 0 {
            let step = speech.sampleRate / sampleRate
            for frame in 0..<frames {
                let first = Int(speechPosition) % speech.samples.count
                let second = (first + 1) % speech.samples.count
                let fraction = Float(speechPosition - floor(speechPosition))
                let value = speech.samples[first] * (1 - fraction) + speech.samples[second] * fraction
                for channel in 0..<channels {
                    audioBuffer.rawBuffer(forChannel: channel)[frame] = value * 32_768
                }
                speechPosition += step
                if speechPosition >= Double(speech.samples.count) { speechPosition -= Double(speech.samples.count) }
            }
        }
        let before = Self.level(audioBuffer, frames: frames, channels: channels)
        tap.audioProcessingProcess(audioBuffer: audioBuffer)
        let after = Self.level(audioBuffer, frames: frames, channels: channels)
        measurements.observe(frames: frames, before: before, after: after)
        if let probe {
            let prefix = probe == .held ? "held" : "open"
            let expected: Float = probe == .held ? 0 : 4_096
            counts[prefix + "Samples", default: 0] += frames * channels
            for channel in 0..<channels {
                let samples = audioBuffer.rawBuffer(forChannel: channel)
                for frame in 0..<frames where samples[frame] != expected {
                    counts[prefix + "Mismatches", default: 0] += 1
                }
            }
        }
    }

    private static func level(_ audio: LKRTCAudioBuffer, frames: Int, channels: Int) -> (Double, Double) {
        var peak = 0.0
        var squared = 0.0
        for channel in 0..<channels {
            let samples = audio.rawBuffer(forChannel: channel)
            for frame in 0..<frames {
                let value = Double(samples[frame]) / 32_768
                guard value.isFinite else { return (.infinity, .infinity) }
                peak = max(peak, abs(value))
                squared += value * value
            }
        }
        return (peak, (squared / Double(frames * channels)).squareRoot())
    }

    func audioProcessingRelease() { tap.audioProcessingRelease() }
}

/// SDK m144 audio_processing_impl.cc invokes render_pre_processor before
/// QueueNonbandedRenderAudio/AnalyzeRender. Injecting here supplies the same
/// known waveform to software AEC and the physical output. AVAudioPlayer or a
/// separate synthesis speaker would omit that reference and invalidate this
/// measurement. This is test-only; the production renderer stays untouched.
private final class VoiceTutorEchoProbeRenderDelegate: NSObject, LKRTCAudioCustomProcessingDelegate, @unchecked Sendable {
    struct Snapshot {
        var callbacks = 0
        var speechFrames = 0
        var sampleRate = 0.0
        var peak = 0.0
    }
    private let lock = NSLock()
    private let speech: VoiceTutorEchoSyntheticSpeech
    private var state = Snapshot()
    private var position = 0.0
    private var playing = false

    init(speech: VoiceTutorEchoSyntheticSpeech) {
        self.speech = speech
        super.init()
    }

    func start() {
        lock.lock()
        playing = true
        lock.unlock()
    }

    func stop() {
        lock.lock()
        playing = false
        lock.unlock()
    }

    func snapshot() -> Snapshot {
        lock.lock()
        defer { lock.unlock() }
        return state
    }

    func audioProcessingInitialize(sampleRate: Int, channels: Int) {
        lock.lock()
        state.sampleRate = Double(sampleRate)
        lock.unlock()
    }

    func audioProcessingProcess(audioBuffer: LKRTCAudioBuffer) {
        lock.lock()
        defer { lock.unlock() }
        state.callbacks += 1
        let frames = Int(audioBuffer.frames)
        let channels = Int(audioBuffer.channels)
        guard frames > 0, frames <= 48_000, channels > 0, channels <= 32 else { return }
        guard playing, state.sampleRate > 0, !speech.samples.isEmpty else {
            for channel in 0..<channels {
                audioBuffer.rawBuffer(forChannel: channel).update(repeating: 0, count: frames)
            }
            return
        }
        let step = speech.sampleRate / state.sampleRate
        for frame in 0..<frames {
            let first = Int(position) % speech.samples.count
            let second = (first + 1) % speech.samples.count
            let fraction = Float(position - floor(position))
            let sample = speech.samples[first] * (1 - fraction) + speech.samples[second] * fraction
            for channel in 0..<channels {
                audioBuffer.rawBuffer(forChannel: channel)[frame] = sample * 32_768
            }
            state.peak = max(state.peak, Double(abs(sample)))
            position += step
            if position >= Double(speech.samples.count) { position -= Double(speech.samples.count) }
        }
        state.speechFrames += frames
    }

    func audioProcessingRelease() {}
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

private struct VoiceTutorCompletionNavigationRenderState: Equatable {
    let phase: VoiceTutorSessionPhase
    let scenePhase: ScenePhase
}

@MainActor
private final class VoiceTutorCompletionNavigationProbe: ObservableObject {
    @Published var path: [Int] = []
    @Published var phase: VoiceTutorSessionPhase = .listening
    @Published var scenePhase: ScenePhase = .active
    var isRootReady = false
    var destinationAppearCount = 0
    var renderedState: VoiceTutorCompletionNavigationRenderState?
}

@MainActor
private struct VoiceTutorCompletionNavigationRoot: View {
    @ObservedObject var probe: VoiceTutorCompletionNavigationProbe

    var body: some View {
        NavigationStack(path: $probe.path) {
            Text("Synthetic call entry")
                .onAppear { probe.isRootReady = true }
                .navigationDestination(for: Int.self) { _ in
                    Text("Synthetic call")
                        .modifier(VoiceTutorCallCompletionNavigation(phase: probe.phase))
                        .onAppear { probe.destinationAppearCount += 1 }
                        .onChange(of: VoiceTutorCompletionNavigationRenderState(
                            phase: probe.phase, scenePhase: probe.scenePhase
                        ), initial: true) { _, state in
                            probe.renderedState = state
                        }
                }
        }
        // Inject lifecycle transitions without backgrounding the XCTest runner.
        // Dismissal itself remains SwiftUI's real navigation environment action.
        .environment(\.scenePhase, probe.scenePhase)
        .transaction { $0.disablesAnimations = true }
    }
}

@MainActor
private final class VoiceTutorCompletionNavigationFixture {
    let probe = VoiceTutorCompletionNavigationProbe()
    private let window: UIWindow
    private let previousKeyWindow: UIWindow?

    init() {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        previousKeyWindow = scene?.windows.first { $0.isKeyWindow }
        if let scene {
            window = UIWindow(windowScene: scene)
        } else {
            window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        }
        let controller = UIHostingController(rootView: VoiceTutorCompletionNavigationRoot(probe: probe))
        window.rootViewController = controller
        window.makeKeyAndVisible()
        controller.loadViewIfNeeded()
        window.layoutIfNeeded()
    }

    func close() {
        window.isHidden = true
        window.rootViewController = nil
        previousKeyWindow?.makeKey()
    }
}

@MainActor
private final class VoiceTutorOrbInteractionProbe {
    var pauseCount = 0
    var endCount = 0
    var revealed = false
    var collapsed = false
    var completed: Bool { pauseCount >= 2 && revealed && collapsed }
}

@MainActor
private struct VoiceTutorInteractiveCallFixture: View {
    let probe: VoiceTutorOrbInteractionProbe
    @State private var pauseState: VoiceTutorCallPauseState = {
        var state = VoiceTutorCallPauseState()
        state.isSupported = true
        return state
    }()
    @State private var showsTranscript = false
    @State private var showsSummary = false

    var body: some View {
        VoiceTutorCallScreen(
            topic: "Redis",
            presentation: VoiceTutorCallPresentation(
                phase: .listening, pauseState: pauseState,
                sessionSecondsRemaining: 1_852, quotaRemainingSeconds: 0,
                quotaReservedSeconds: 3_600, quotaLimitSeconds: 3_600
            ),
            strings: AppStrings(language: .korean),
            captions: [
                VoiceTutorCaption(speaker: .learner, text: "캐시가 언제 만료되나요?"),
                VoiceTutorCaption(speaker: .tutor, text: "설정한 만료 시간이 지나면 사라져요.")
            ],
            errorMessage: nil,
            showsTranscript: $showsTranscript,
            showsSummary: $showsSummary,
            onPause: {
                let paused = pauseState.mode == .active
                let command = paused ? pauseState.requestPause() : pauseState.requestResume()
                if let command, pauseState.acknowledge(sequence: command.sequence, paused: paused) {
                    probe.pauseCount += 1
                    print("VOICE_INTERACTION_PAUSE:\(probe.pauseCount)")
                }
            },
            onEnd: { probe.endCount += 1 }
        )
        .environment(\.colorScheme, .dark)
        // This UIHostingController is outside the app's SwiftUI Scene. Supply
        // the active scene value that the real call receives from WindowGroup.
        .environment(\.scenePhase, .active)
        .onChange(of: showsTranscript) { _, expanded in
            if expanded { probe.revealed = true }
            else if probe.revealed { probe.collapsed = true }
            print("VOICE_INTERACTION_TRANSCRIPT:\(expanded)")
        }
    }
}

private struct VoiceTutorCompactCallSnapshot {
    var name: String
    var phase: VoiceTutorSessionPhase
    var seconds: Int? = 1_852
    var isRecording = false
    var isAwaitingTutorResponse = false
    var pauseState: VoiceTutorCallPauseState = {
        var state = VoiceTutorCallPauseState()
        state.isSupported = true
        return state
    }()
    var failureCause: VoiceTutorFailureCause? = nil
    var serverEndReason: String? = nil
    var quotaRemainingSeconds: Int? = nil
    var quotaLimitSeconds = 3_600
    var detail: BackendVoiceTutorSessionDetail?
    var showsTranscript = false
    var showsPreview = false
    var learnerOnly = false
    var language: AppLanguage = .korean
    var topic = "Redis"
    var size = CGSize(width: 402, height: 874)
    var dynamicType = DynamicTypeSize.large
    var colorScheme = ColorScheme.dark
    var answerDraftState = VoiceTutorAnswerDraftState()
    var sessionState = VoiceTutorSessionState()
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

private final class VoiceTutorContractRenderDurationObservation: @unchecked Sendable {
    private let lock = NSLock()
    private var storedFrames: Int?
    private var storedDuration: TimeInterval?

    var frames: Int? {
        lock.lock()
        defer { lock.unlock() }
        return storedFrames
    }

    var duration: TimeInterval? {
        lock.lock()
        defer { lock.unlock() }
        return storedDuration
    }

    func record(frames: Int, duration: TimeInterval) {
        lock.lock()
        storedFrames = frames
        storedDuration = duration
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
        profileOwnerUserID: Int? = 7,
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
        if let profileOwnerUserID {
            appState.communityProfile = Self.profile(ownerUserID: profileOwnerUserID)
        }
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

    static func quickCallEntryResponse(for request: URLRequest) throws -> (HTTPURLResponse, Data) {
        switch request.url?.path {
        case "/api/v1/profile":
            return response(for: request, body: #"{"id":7,"displayName":"Fixture-7","status":"ACTIVE","provider":"GOOGLE"}"#)
        case "/api/v1/voice-tutor/status":
            return response(for: request, body: #"{"eligible":true,"quota":{"limitSeconds":3600,"usedSeconds":0,"reservedSeconds":0,"remainingSeconds":3600}}"#)
        default:
            XCTFail("Unexpected call entry request: \(request.url?.path ?? "")")
            throw URLError(.badServerResponse)
        }
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
