import AVFoundation
import Foundation
import LiveKitWebRTC
import UIKit
import XCTest
@testable import StudyMate

/// Explicitly selected, opt-in integration test. A separate host fixture owns
/// one bounded provider call; this iPhone never creates an app session, local
/// audio track, microphone capture, recording, or client playout-drain ACK.
final class VoiceTutorNativeConversationTests: XCTestCase {
    @MainActor
    func testOptInNativeReceiveOnlyConversationContinuesAcrossThreeProviderTurns() async throws {
        guard ProcessInfo.processInfo.environment["BUDDYSTUDY_NATIVE_CONVERSATION_TEST"] == "1" else {
            throw XCTSkip("Select this test explicitly and set BUDDYSTUDY_NATIVE_CONVERSATION_TEST=1 after arming the bounded host fixture.")
        }
        #if targetEnvironment(simulator)
        throw XCTSkip("Native provider RTP must be verified on a physical iPhone.")
        #else
        let configuration = try NativeConversationConfiguration.environment()
        let client = NativeConversationHostClient(configuration: configuration)
        let observation = NativeConversationObservation()
        defer {
            client.invalidate()
            let attachment = XCTAttachment(string: observation.metadata())
            attachment.name = "native-three-turn-conversation-metadata-only"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        do {
            try await receiveConversation(client: client, observation: observation)
            await client.close()
        } catch {
            await client.close()
            throw error
        }
        #endif
    }

    @MainActor
    private func receiveConversation(
        client: NativeConversationHostClient,
        observation: NativeConversationObservation
    ) async throws {
        let foregroundDeadline = ProcessInfo.processInfo.systemUptime + 2
        while UIApplication.shared.applicationState != .active,
              ProcessInfo.processInfo.systemUptime < foregroundDeadline {
            try await Task.sleep(for: .milliseconds(50))
        }
        guard UIApplication.shared.applicationState == .active else {
            throw XCTSkip("The iPhone test host did not become foreground-active; native conversation was not verified.")
        }

        let audioSession = AVAudioSession.sharedInstance()
        let previousCategory = audioSession.category
        let previousMode = audioSession.mode
        let previousOptions = audioSession.categoryOptions
        let previousIO = audioSession.preferredIOBufferDuration
        defer {
            try? audioSession.setActive(false, options: [.notifyOthersOnDeactivation])
            try? audioSession.setCategory(previousCategory, mode: previousMode, options: previousOptions)
            try? audioSession.setPreferredIOBufferDuration(previousIO)
        }
        do {
            // Receive-only playback: never request microphone permission or
            // switch this synthetic fixture to playAndRecord/voiceChat.
            try audioSession.setCategory(.playback, mode: .default)
            try audioSession.setPreferredIOBufferDuration(0.01)
            try audioSession.setActive(true)
        } catch { throw NativeConversationFailure.audioSessionUnavailable }

        _ = LKRTCInitializeSSL()
        let counters = observation.renderCounters
        let forbiddenCapture = VoiceTutorLocalSpeechCaptureTap(recordingTap: nil, onActivity: { _ in
            counters.recordUnexpectedInputEvent()
        })
        let module = try VoiceTutorAudioProcessingModuleFactory.make(captureDelegate: forbiddenCapture)
        let factory = LKRTCPeerConnectionFactory(
            audioDeviceModuleType: .audioEngine, bypassVoiceProcessing: false,
            encoderFactory: nil, decoderFactory: nil, audioProcessingModule: module
        )
        let rtcConfiguration = LKRTCConfiguration()
        rtcConfiguration.sdpSemantics = .unifiedPlan
        rtcConfiguration.iceServers = []
        rtcConfiguration.iceCandidatePoolSize = 0
        let constraints = LKRTCMediaConstraints(
            mandatoryConstraints: ["OfferToReceiveAudio": "true", "OfferToReceiveVideo": "false"],
            optionalConstraints: nil
        )
        guard let peer = factory.peerConnection(with: rtcConfiguration, constraints: constraints, delegate: nil) else {
            throw NativeConversationFailure.peerCreationFailed
        }
        let device = factory.audioDeviceModule
        let renderer = VoiceTutorRemoteAudioRenderer()
        var remoteTrack: LKRTCAudioTrack?
        defer {
            forbiddenCapture.close()
            remoteTrack?.remove(renderer)
            peer.close()
            _ = device.stopPlayout()
            module.capturePostProcessingDelegate = nil
            module.renderPreProcessingDelegate = nil
            withExtendedLifetime((factory, peer, renderer, module, forbiddenCapture, remoteTrack)) {}
        }
        renderer.onRenderedBuffer = { frames, nonzero, _, _ in
            counters.rendered(frames: frames, nonzero: nonzero)
        }
        renderer.onRenderedPCM = { [weak observation] _ in
            Task { @MainActor [weak observation] in observation?.renderedAudio() }
        }
        let transceiverConfiguration = LKRTCRtpTransceiverInit()
        transceiverConfiguration.direction = .recvOnly
        guard peer.addTransceiver(of: .audio, init: transceiverConfiguration) != nil else {
            throw NativeConversationFailure.receiverCreationFailed
        }
        let offer = try await makeOffer(peer: peer, constraints: constraints)
        let checkedOffer = try VoiceTutorWebRTCTransport.validatedOfferSDP(offer.sdp)
        guard checkedOffer.contains("a=recvonly"),
              !checkedOffer.contains("a=sendrecv"), !checkedOffer.contains("a=sendonly") else {
            throw NativeConversationFailure.localAudioForbidden
        }
        try await setDescription(offer, peer: peer, local: true)
        let answer = try await client.offer(checkedOffer)
        try await setDescription(LKRTCSessionDescription(type: .answer, sdp: answer), peer: peer, local: false)
        guard let receiver = peer.receivers.first(where: { $0.track is LKRTCAudioTrack }),
              let track = receiver.track as? LKRTCAudioTrack else {
            throw NativeConversationFailure.receiverCreationFailed
        }
        remoteTrack = track
        track.add(renderer)
        observation.rememberReceiver(receiver, track: track)
        let connectedDeadline = ProcessInfo.processInfo.systemUptime + 12
        while peer.connectionState != .connected,
              ProcessInfo.processInfo.systemUptime < connectedDeadline {
            if peer.connectionState == .failed || peer.connectionState == .closed {
                throw NativeConversationFailure.mediaConnectionFailed
            }
            try await Task.sleep(for: .milliseconds(50))
        }
        guard peer.connectionState == .connected else { throw NativeConversationFailure.mediaConnectionFailed }
        guard !device.isRecording, peer.senders.allSatisfy({ $0.track == nil }) else {
            throw NativeConversationFailure.localAudioForbidden
        }
        observation.mediaConnected = true
        try await client.start()
        let callDeadline = ProcessInfo.processInfo.systemUptime + 52
        var lastLegacyStatsUptime = 0.0
        while !observation.hostCompleted, ProcessInfo.processInfo.systemUptime < callDeadline {
            try Task.checkCancellation()
            guard UIApplication.shared.applicationState == .active,
                  peer.connectionState == .connected else {
                throw NativeConversationFailure.mediaConnectionFailed
            }
            guard !device.isRecording, forbiddenCapture.snapshot().processedBufferCount == 0 else {
                throw NativeConversationFailure.localAudioForbidden
            }
            let events = try await client.poll(after: observation.cursor)
            for event in events { try observation.receive(event) }
            if let snapshot = await inboundStatistics(peer: peer, receiver: receiver) {
                observation.observe(snapshot)
            }
            observation.observeReceiver(receiver, track: track, peer: peer)
            let uptime = ProcessInfo.processInfo.systemUptime
            if uptime - lastLegacyStatsUptime >= 0.5 {
                lastLegacyStatsUptime = uptime
                observation.observe(await decodingStatistics(peer: peer, track: track))
            }
        }
        guard observation.hostCompleted else { throw NativeConversationFailure.conversationDeadline }
        observation.forbiddenCaptureBuffers = forbiddenCapture.snapshot().processedBufferCount
        observation.finalNativeRecording = device.isRecording
        observation.outputVolumeNonzero = audioSession.outputVolume > 0
        observation.finalNativePlaying = device.isPlaying

        XCTAssertEqual(observation.completedTurns, [1, 2, 3], "The production response state must complete every provider turn exactly once")
        XCTAssertEqual(observation.acceptedInputs, Set([1, 2]), "The host must accept both fixed synthetic learner messages")
        XCTAssertTrue(observation.renderCounters.snapshot().turnNonzeroBuffers.allSatisfy { $0 > 0 }, "Every teacher turn must reach the actual native renderer")
        XCTAssertTrue(observation.turnByteGrowth.allSatisfy { $0 > 0 }, "Each response must deliver new RTP rather than only old NetEq output")
        XCTAssertGreaterThan(observation.renderCounters.snapshot().postCompletionNonzeroBuffers, 0,
                             "Observe continuing nonzero native output instead of assuming exact digital silence")
        XCTAssertTrue(observation.sameReceiver && observation.sameTrack && observation.sameSSRC && observation.sameCodec && observation.sameTransport)
        XCTAssertGreaterThan(observation.statisticsSamples, 2)
        XCTAssertGreaterThan(observation.rtpForwardSteps, 2)
        XCTAssertEqual(observation.rtpBackwardSteps, 0, "RTP continuity must be compared modulo 2^32")
        XCTAssertTrue(observation.countersMonotonic)
        XCTAssertTrue(observation.opus48k)
        if observation.jitterFlushCounterAvailable {
            XCTAssertEqual(observation.jitterFlushesAfterFirstTurn, 0)
        }
        if observation.nativeCNGCounterAvailable {
            XCTAssertGreaterThan(observation.nativeCNGFrames, 0, "A supported native CNG counter must actually observe CNG in this fixture")
        }
        XCTAssertEqual(observation.providerClears, 0)
        XCTAssertEqual(observation.providerTruncations, 0)
        XCTAssertEqual(observation.providerErrors, 0)
        XCTAssertEqual(observation.forbiddenCaptureBuffers, 0)
        XCTAssertEqual(observation.renderCounters.snapshot().unexpectedInputEvents, 0)
        XCTAssertFalse(observation.finalNativeRecording)
        XCTAssertTrue(observation.finalNativePlaying)
        XCTAssertEqual(observation.phase, .listening, "Continuing post-response CNG must not leave the call stuck speaking")
    }

    @MainActor
    private func makeOffer(peer: LKRTCPeerConnection, constraints: LKRTCMediaConstraints) async throws -> LKRTCSessionDescription {
        let boxed: NativeConversationBox<LKRTCSessionDescription> = try await withCheckedThrowingContinuation { continuation in
            peer.offer(for: constraints) { description, error in
                guard error == nil, let description else {
                    continuation.resume(throwing: NativeConversationFailure.offerFailed)
                    return
                }
                continuation.resume(returning: NativeConversationBox(value: description))
            }
        }
        return boxed.value
    }

    @MainActor
    private func setDescription(_ description: LKRTCSessionDescription, peer: LKRTCPeerConnection, local: Bool) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let completion: @Sendable (Error?) -> Void = { error in
                if error != nil { continuation.resume(throwing: NativeConversationFailure.descriptionFailed) }
                else { continuation.resume() }
            }
            if local { peer.setLocalDescription(description, completionHandler: completion) }
            else { peer.setRemoteDescription(description, completionHandler: completion) }
        }
    }

    @MainActor
    private func inboundStatistics(peer: LKRTCPeerConnection, receiver: LKRTCRtpReceiver) async -> NativeConversationInbound? {
        await withCheckedContinuation { continuation in
            let gate = NativeConversationContinuation(continuation)
            DispatchQueue.global().asyncAfter(deadline: .now() + 0.8) { gate.resolve(nil) }
            peer.statistics(for: receiver) { report in
                gate.resolve(NativeConversationInbound(report: report))
            }
        }
    }

    @MainActor
    private func decodingStatistics(peer: LKRTCPeerConnection, track: LKRTCAudioTrack) async -> NativeConversationDecoding {
        await withCheckedContinuation { continuation in
            // Legacy stats can decline a track without invoking completion.
            // Unavailable evidence must not hang the bounded native probe.
            let gate = NativeConversationContinuation(continuation)
            DispatchQueue.global().asyncAfter(deadline: .now() + 0.8) { gate.resolve(NativeConversationDecoding()) }
            peer.stats(for: track, statsOutputLevel: .debug) { reports in
                var result = NativeConversationDecoding()
                for report in reports {
                    if let cng = report.values["googDecodingCNG"].flatMap({ UInt64($0) }),
                       let plcCNG = report.values["googDecodingPLCCNG"].flatMap({ UInt64($0) }) {
                        result.available = true
                        result.cngFrames = max(result.cngFrames, cng + plcCNG)
                    }
                    if let plc = report.values["googDecodingPLC"].flatMap({ UInt64($0) }) {
                        result.plcFrames = max(result.plcFrames, plc)
                    }
                }
                gate.resolve(result)
            }
        }
    }
}

private enum NativeConversationFailure: Error {
    case invalidConfiguration, audioSessionUnavailable, peerCreationFailed, receiverCreationFailed
    case localAudioForbidden, offerFailed, descriptionFailed, signalingFailed, mediaConnectionFailed
    case invalidLifecycle, fixtureFailed, conversationDeadline
}

private struct NativeConversationBox<Value>: @unchecked Sendable { let value: Value }

private final class NativeConversationContinuation<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Value, Never>?

    init(_ continuation: CheckedContinuation<Value, Never>) { self.continuation = continuation }

    func resolve(_ value: Value) {
        lock.lock()
        let pending = continuation
        continuation = nil
        lock.unlock()
        pending?.resume(returning: value)
    }
}

private struct NativeConversationConfiguration {
    let url: URL
    let token: String

    static func environment() throws -> Self {
        let values = ProcessInfo.processInfo.environment
        guard let rawURL = values["BUDDYSTUDY_NATIVE_CONVERSATION_URL"],
              let url = URL(string: rawURL), let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let scheme = components.scheme, ["http", "https"].contains(scheme), components.host != nil,
              components.user == nil, components.password == nil, components.query == nil, components.fragment == nil,
              let token = values["BUDDYSTUDY_NATIVE_CONVERSATION_TOKEN"],
              token.range(of: "^[A-Za-z0-9_-]{32,128}$", options: .regularExpression) != nil else {
            throw NativeConversationFailure.invalidConfiguration
        }
        return Self(url: url, token: token)
    }
}

private struct NativeConversationEvent: Decodable, Sendable {
    let sequence: Int
    let kind: String
    let turn: Int
    let responses: Int?
    let acceptedInputs: Int?
    let providerClears: Int?
    let providerTruncations: Int?
    let providerErrors: Int?
    let clientDrainMessages: Int?
}

@MainActor
private final class NativeConversationHostClient {
    private let configuration: NativeConversationConfiguration
    private let session: URLSession

    init(configuration: NativeConversationConfiguration) {
        self.configuration = configuration
        let transport = URLSessionConfiguration.ephemeral
        transport.timeoutIntervalForRequest = 20
        transport.timeoutIntervalForResource = 25
        transport.urlCache = nil
        transport.httpCookieStorage = nil
        session = URLSession(configuration: transport)
    }

    func offer(_ sdp: String) async throws -> String {
        let data = try await request("offer", method: "POST", body: Data(sdp.utf8), contentType: "application/sdp")
        guard data.count <= 128 * 1024, let answer = String(data: data, encoding: .utf8) else {
            throw NativeConversationFailure.signalingFailed
        }
        return answer
    }

    func start() async throws { _ = try await request("start", method: "POST") }

    func poll(after cursor: Int) async throws -> [NativeConversationEvent] {
        struct Page: Decodable { let events: [NativeConversationEvent] }
        let data = try await request("events", cursor: cursor)
        guard data.count <= 64 * 1024,
              let page = try? JSONDecoder().decode(Page.self, from: data), page.events.count <= 96 else {
            throw NativeConversationFailure.signalingFailed
        }
        return page.events
    }

    func close() async { _ = try? await request("close", method: "POST") }
    func invalidate() { session.invalidateAndCancel() }

    private func request(_ path: String, method: String = "GET", body: Data? = nil,
                         contentType: String? = nil, cursor: Int? = nil) async throws -> Data {
        var components = URLComponents(url: configuration.url.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if let cursor { components.queryItems = [URLQueryItem(name: "after", value: String(cursor))] }
        guard let url = components.url else { throw NativeConversationFailure.invalidConfiguration }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        request.setValue("Bearer " + configuration.token, forHTTPHeaderField: "Authorization")
        if let contentType { request.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        do {
            let (data, response) = try await session.data(for: request)
            guard let response = response as? HTTPURLResponse, response.statusCode == 200 else {
                throw NativeConversationFailure.signalingFailed
            }
            return data
        } catch { throw NativeConversationFailure.signalingFailed }
    }
}

private struct NativeConversationRenderCounts: Sendable {
    var buffers = 0
    var frames = 0
    var nonzeroBuffers = 0
    var postCompletionNonzeroBuffers = 0
    var turnNonzeroBuffers = [0, 0, 0]
    var unexpectedInputEvents = 0
}

private final class NativeConversationRenderCounters: @unchecked Sendable {
    private let lock = NSLock()
    private var counts = NativeConversationRenderCounts()
    private var turn = 0
    private var completedAtLeastOne = false
    private var responseActive = false

    func state(turn: Int, active: Bool, completedAtLeastOne: Bool) {
        lock.lock()
        defer { lock.unlock() }
        self.turn = turn
        responseActive = active
        self.completedAtLeastOne = completedAtLeastOne
    }

    func rendered(frames: Int, nonzero: Bool) {
        lock.lock()
        defer { lock.unlock() }
        counts.buffers += 1
        counts.frames += frames
        if nonzero {
            counts.nonzeroBuffers += 1
            if 1...3 ~= turn, responseActive { counts.turnNonzeroBuffers[turn - 1] += 1 }
            if completedAtLeastOne && !responseActive { counts.postCompletionNonzeroBuffers += 1 }
        }
    }

    func recordUnexpectedInputEvent() {
        lock.lock()
        defer { lock.unlock() }
        counts.unexpectedInputEvents += 1
    }

    func snapshot() -> NativeConversationRenderCounts {
        lock.lock()
        defer { lock.unlock() }
        return counts
    }
}

private struct NativeConversationInbound: Sendable {
    let ssrc: UInt64
    let codecID: String
    let transportID: String
    let codecIsOpus48k: Bool
    let packets: UInt64
    let bytes: UInt64
    let emittedSamples: UInt64?
    let flushes: UInt64?

    init?(report: LKRTCStatisticsReport) {
        let inbound = report.statistics.values.filter {
            $0.type == "inbound-rtp" && (($0.values["kind"] as? String) == "audio" || ($0.values["mediaType"] as? String) == "audio")
        }
        guard inbound.count == 1, let values = inbound.first?.values,
              let ssrc = values["ssrc"] as? NSNumber,
              let codecID = values["codecId"] as? String,
              let transportID = values["transportId"] as? String,
              let packets = values["packetsReceived"] as? NSNumber, packets.uint64Value > 0,
              let bytes = values["bytesReceived"] as? NSNumber else { return nil }
        self.ssrc = ssrc.uint64Value
        self.codecID = codecID
        self.transportID = transportID
        self.packets = packets.uint64Value
        self.bytes = bytes.uint64Value
        emittedSamples = (values["jitterBufferEmittedCount"] as? NSNumber)?.uint64Value
        flushes = (values["jitterBufferFlushes"] as? NSNumber)?.uint64Value
        let codec = report.statistics[codecID]?.values
        codecIsOpus48k = (codec?["mimeType"] as? String)?.lowercased() == "audio/opus"
            && (codec?["clockRate"] as? NSNumber)?.intValue == 48_000
    }
}

private struct NativeConversationDecoding: Sendable {
    var available = false
    var cngFrames: UInt64 = 0
    var plcFrames: UInt64 = 0
}

@MainActor
private final class NativeConversationObservation {
    let renderCounters = NativeConversationRenderCounters()
    private var response = VoiceTutorWebRTCResponseState()
    private var activeOrdinal = 0
    private var receiverID: String?
    private var trackID: String?
    private var previousInbound: NativeConversationInbound?
    private var previousRTPTimestamp: UInt32?
    private var previousSourceSSRC: UInt32?
    private var firstTurnFlushes: UInt64?
    private var firstCNGFrames: UInt64?
    private var turnStartBytes: UInt64 = 0
    private(set) var cursor = 0
    private(set) var completedTurns: [Int] = []
    private(set) var acceptedInputs = Set<Int>()
    private(set) var hostCompleted = false
    private(set) var phase = VoiceTutorSessionPhase.listening
    private(set) var statisticsSamples = 0
    private(set) var rtpForwardSteps = 0
    private(set) var rtpBackwardSteps = 0
    private(set) var countersMonotonic = true
    private(set) var sameReceiver = true
    private(set) var sameTrack = true
    private(set) var sameSSRC = true
    private(set) var sameCodec = true
    private(set) var sameTransport = true
    private(set) var opus48k = true
    private(set) var jitterFlushCounterAvailable = false
    private(set) var jitterFlushesAfterFirstTurn: UInt64 = 0
    private(set) var nativeCNGCounterAvailable = false
    private(set) var nativeCNGFrames: UInt64 = 0
    private(set) var nativePLCFrames: UInt64 = 0
    private(set) var providerClears = 0
    private(set) var providerTruncations = 0
    private(set) var providerErrors = 0
    private(set) var turnByteGrowth: [UInt64] = [0, 0, 0]
    var mediaConnected = false
    var forbiddenCaptureBuffers = 0
    var finalNativeRecording = false
    var finalNativePlaying = false
    var outputVolumeNonzero = false

    func receive(_ event: NativeConversationEvent) throws {
        guard event.sequence == cursor + 1, event.sequence <= 96 else { throw NativeConversationFailure.invalidLifecycle }
        cursor = event.sequence
        let id = "synthetic-response-\(event.turn)"
        var completion: String?
        switch event.kind {
        case "offer_answered", "manual_session_ready", "synthetic_input_sent": break
        case "response_started":
            guard event.turn == completedTurns.count + 1, (1...3).contains(event.turn) else {
                throw NativeConversationFailure.invalidLifecycle
            }
            activeOrdinal = event.turn
            turnStartBytes = previousInbound?.bytes ?? 0
            response.responseStarted(id)
        case "output_started": response.markOutputBufferStarted(id)
        case "response_done": completion = response.markResponseDone(id)
        case "output_stopped": completion = response.markOutputBufferStopped(id)
        case "synthetic_input_accepted":
            guard (1...2).contains(event.turn) else { throw NativeConversationFailure.invalidLifecycle }
            acceptedInputs.insert(event.turn)
        case "probe_complete":
            guard event.responses == 3, event.acceptedInputs == 2, event.clientDrainMessages == 0 else {
                throw NativeConversationFailure.invalidLifecycle
            }
            providerClears = event.providerClears ?? -1
            providerTruncations = event.providerTruncations ?? -1
            providerErrors = event.providerErrors ?? -1
            hostCompleted = true
        case "probe_failed": throw NativeConversationFailure.fixtureFailed
        default: throw NativeConversationFailure.invalidLifecycle
        }
        if let completion {
            guard completion == id, event.turn == activeOrdinal, !completedTurns.contains(event.turn) else {
                throw NativeConversationFailure.invalidLifecycle
            }
            completedTurns.append(event.turn)
            phase = .listening
        }
        renderCounters.state(turn: activeOrdinal, active: response.mayIndicateSpeaking,
                             completedAtLeastOne: !completedTurns.isEmpty)
    }

    func renderedAudio() {
        // This is the production indication transition and response state.
        // Native nonzero CNG has no completion/generation/deadline side effect.
        phase = phase.afterRenderedTutorAudio(assistantResponseActive: response.mayIndicateSpeaking)
    }

    func rememberReceiver(_ receiver: LKRTCRtpReceiver, track: LKRTCAudioTrack) {
        receiverID = receiver.receiverId
        trackID = track.trackId
    }

    func observeReceiver(_ receiver: LKRTCRtpReceiver, track: LKRTCAudioTrack, peer: LKRTCPeerConnection) {
        let currentReceivers = peer.receivers.filter { $0.track is LKRTCAudioTrack }
        sameReceiver = sameReceiver && currentReceivers.count == 1
            && currentReceivers.first?.receiverId == receiverID
        sameReceiver = sameReceiver && receiverID == receiver.receiverId
        sameTrack = sameTrack && trackID == track.trackId && receiver.track?.trackId == trackID
        for source in receiver.sources where source.sourceType.rawValue == 0 {
            if let previousSourceSSRC { sameSSRC = sameSSRC && source.sourceId == previousSourceSSRC }
            previousSourceSSRC = source.sourceId
            if let previousRTPTimestamp {
                let step = source.rtpTimestamp &- previousRTPTimestamp
                if step > 0 && step < 0x8000_0000 { rtpForwardSteps += 1 }
                else if step >= 0x8000_0000 { rtpBackwardSteps += 1 }
            }
            previousRTPTimestamp = source.rtpTimestamp
        }
    }

    func observe(_ current: NativeConversationInbound) {
        statisticsSamples += 1
        opus48k = opus48k && current.codecIsOpus48k
        if let previous = previousInbound {
            sameSSRC = sameSSRC && current.ssrc == previous.ssrc
            sameCodec = sameCodec && current.codecID == previous.codecID
            sameTransport = sameTransport && current.transportID == previous.transportID
            countersMonotonic = countersMonotonic && current.packets >= previous.packets && current.bytes >= previous.bytes
            if let emitted = current.emittedSamples, let previousEmitted = previous.emittedSamples {
                countersMonotonic = countersMonotonic && emitted >= previousEmitted
            }
        }
        previousInbound = current
        if (1...3).contains(activeOrdinal), current.bytes >= turnStartBytes {
            turnByteGrowth[activeOrdinal - 1] = current.bytes - turnStartBytes
        }
        if let flushes = current.flushes, !completedTurns.isEmpty {
            jitterFlushCounterAvailable = true
            if firstTurnFlushes == nil { firstTurnFlushes = flushes }
            if let firstTurnFlushes, flushes >= firstTurnFlushes {
                jitterFlushesAfterFirstTurn = flushes - firstTurnFlushes
            } else { countersMonotonic = false }
        }
    }

    func observe(_ decoding: NativeConversationDecoding) {
        nativeCNGCounterAvailable = nativeCNGCounterAvailable || decoding.available
        if decoding.available {
            if firstCNGFrames == nil { firstCNGFrames = decoding.cngFrames }
            nativeCNGFrames = max(nativeCNGFrames, decoding.cngFrames - min(firstCNGFrames ?? 0, decoding.cngFrames))
        }
        nativePLCFrames = max(nativePLCFrames, decoding.plcFrames)
    }

    func metadata() -> String {
        let rendered = renderCounters.snapshot()
        return """
        providerResponsesCompleted=\(completedTurns.count)
        syntheticLearnerInputsAccepted=\(acceptedInputs.count)
        hostCompleted=\(hostCompleted)
        mediaConnected=\(mediaConnected)
        renderedBuffers=\(rendered.buffers)
        renderedFrames=\(rendered.frames)
        nonzeroBuffers=\(rendered.nonzeroBuffers)
        turnOneNonzeroBuffers=\(rendered.turnNonzeroBuffers[0])
        turnTwoNonzeroBuffers=\(rendered.turnNonzeroBuffers[1])
        turnThreeNonzeroBuffers=\(rendered.turnNonzeroBuffers[2])
        turnOneRTPBytes=\(turnByteGrowth[0])
        turnTwoRTPBytes=\(turnByteGrowth[1])
        turnThreeRTPBytes=\(turnByteGrowth[2])
        postCompletionNonzeroBuffers=\(rendered.postCompletionNonzeroBuffers)
        sameReceiver=\(sameReceiver)
        sameTrack=\(sameTrack)
        sameSSRC=\(sameSSRC)
        sameCodec=\(sameCodec)
        sameTransport=\(sameTransport)
        opus48k=\(opus48k)
        inboundStatisticsSamples=\(statisticsSamples)
        rtpForwardSteps=\(rtpForwardSteps)
        rtpBackwardSteps=\(rtpBackwardSteps)
        countersMonotonic=\(countersMonotonic)
        jitterFlushCounterAvailable=\(jitterFlushCounterAvailable)
        jitterFlushesAfterFirstTurn=\(jitterFlushesAfterFirstTurn)
        nativeCNGCounterAvailable=\(nativeCNGCounterAvailable)
        nativeCNGFrames=\(nativeCNGFrames)
        nativePLCFrames=\(nativePLCFrames)
        providerClears=\(providerClears)
        providerTruncations=\(providerTruncations)
        providerErrors=\(providerErrors)
        forbiddenCaptureBuffers=\(forbiddenCaptureBuffers)
        unexpectedInputEvents=\(rendered.unexpectedInputEvents)
        nativeRecording=\(finalNativeRecording)
        nativePlaying=\(finalNativePlaying)
        outputVolumeNonzero=\(outputVolumeNonzero)
        clientDrainAcknowledgements=0
        learnerInputIsSyntheticText=true
        microphoneAndASRVerification=false
        """
    }
}
