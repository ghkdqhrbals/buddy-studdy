#if os(iOS)
import AVFoundation
import Foundation
import LiveKitWebRTC

enum VoiceTutorWebRTCError: Error {
    case peerConnectionCreationFailed
    case localTrackCreationFailed
    case offerCreationFailed
    case invalidSDPResponse
    case sdpExchangeFailed(Int)
    case mediaConnectionFailed
    case mediaConnectionTimedOut
}

enum VoiceTutorWebRTCMediaReadiness: Equatable {
    case waiting
    case connected
    case failed
    case timedOut
}

private struct VoiceTutorUncheckedSendable<Value>: @unchecked Sendable {
    var value: Value
}

final class VoiceTutorAudioProcessingTap: NSObject, LKRTCAudioCustomProcessingDelegate,
    @unchecked Sendable {
    private let participant: VoiceTutorSessionRecorder.Participant
    private let recorder: VoiceTutorSessionRecorder
    private var sampleRate: Double = 48_000

    init(participant: VoiceTutorSessionRecorder.Participant, recorder: VoiceTutorSessionRecorder) {
        self.participant = participant
        self.recorder = recorder
    }

    func audioProcessingInitialize(sampleRate: Int, channels: Int) {
        self.sampleRate = Double(sampleRate)
    }

    func audioProcessingProcess(audioBuffer: LKRTCAudioBuffer) {
        let frameCount = Int(audioBuffer.frames)
        let channelCount = Int(audioBuffer.channels)
        guard frameCount > 0, channelCount > 0 else { return }
        var channels: [[Float]] = []
        channels.reserveCapacity(channelCount)
        for channel in 0..<channelCount {
            // LKRTCAudioBuffer is only valid during this callback. Copy every
            // sample before returning, then hand disk work to the recorder queue.
            let pointer = audioBuffer.rawBuffer(forChannel: channel)
            channels.append(Array(UnsafeBufferPointer(start: pointer, count: frameCount)))
        }
        recorder.append(
            VoiceTutorPCMFrame(
                sampleRate: sampleRate,
                channels: channels,
                frameCount: frameCount,
                capturedAtUptime: ProcessInfo.processInfo.systemUptime
            ),
            participant: participant
        )
    }

    func audioProcessingRelease() {}
}

final class VoiceTutorRemoteAudioRenderer: NSObject, LKRTCAudioRenderer, @unchecked Sendable {
    var onRenderedPCM: (@Sendable (TimeInterval) -> Void)?

    func render(pcmBuffer: AVAudioPCMBuffer) {
        guard pcmBuffer.frameLength > 0 else { return }
        onRenderedPCM?(ProcessInfo.processInfo.systemUptime)
    }
}

final class VoiceTutorWebRTCTransport: NSObject, @unchecked Sendable {
    var onRenderedPCM: (@Sendable (TimeInterval) -> Void)? {
        didSet { remoteRenderer.onRenderedPCM = onRenderedPCM }
    }
    var onInterruption: (@Sendable () -> Void)?
    var onConnectionFailure: (@Sendable () -> Void)?

    private let networkSession: URLSession
    private let audioSessionOwnerID = UUID()
    private let remoteRenderer = VoiceTutorRemoteAudioRenderer()
    private var captureTap: VoiceTutorAudioProcessingTap?
    private var renderTap: VoiceTutorAudioProcessingTap?
    private var audioProcessingModule: LKRTCDefaultAudioProcessingModule?
    private var factory: LKRTCPeerConnectionFactory?
    private var peerConnection: LKRTCPeerConnection?
    private var localAudioTrack: LKRTCAudioTrack?
    private var remoteAudioTrack: LKRTCAudioTrack?
    private var interruptionObserver: NSObjectProtocol?
    private let stateLock = NSLock()
    private var isClosed = false
    private var sessionMediaReady = false
    private static let audioSessionOwnershipLock = NSLock()
    private nonisolated(unsafe) static var activeAudioSessionOwnerID: UUID?

    override init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.waitsForConnectivity = true
        configuration.timeoutIntervalForRequest = 30
        networkSession = URLSession(configuration: configuration)
        super.init()
        remoteRenderer.onRenderedPCM = { [weak self] uptime in
            self?.onRenderedPCM?(uptime)
        }
    }

    deinit {
        stateLock.lock()
        let observer = interruptionObserver
        interruptionObserver = nil
        stateLock.unlock()
        if let observer {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    func connect(
        sdpRequest: URLRequest,
        recorder: VoiceTutorSessionRecorder?
    ) async throws {
        try configureAudioSession()
        try installInterruptionObserver()
        _ = LKRTCInitializeSSL()
        try ensureOpen()

        let nextCaptureTap = recorder.map {
            VoiceTutorAudioProcessingTap(participant: .learner, recorder: $0)
        }
        let nextRenderTap = recorder.map {
            VoiceTutorAudioProcessingTap(participant: .tutor, recorder: $0)
        }
        let processingModule = LKRTCDefaultAudioProcessingModule(
            config: nil,
            capturePostProcessingDelegate: nextCaptureTap,
            renderPreProcessingDelegate: nextRenderTap
        )
        let factory = LKRTCPeerConnectionFactory(
            audioDeviceModuleType: .audioEngine,
            bypassVoiceProcessing: false,
            encoderFactory: nil,
            decoderFactory: nil,
            audioProcessingModule: processingModule
        )
        try ensureOpen()

        let configuration = LKRTCConfiguration()
        configuration.sdpSemantics = .unifiedPlan
        let constraints = LKRTCMediaConstraints(
            mandatoryConstraints: ["OfferToReceiveAudio": "true"],
            optionalConstraints: nil
        )
        guard let peer = factory.peerConnection(
            with: configuration,
            constraints: constraints,
            delegate: self
        ) else {
            throw VoiceTutorWebRTCError.peerConnectionCreationFailed
        }
        var resourcesInstalled = false
        defer {
            if !resourcesInstalled {
                peer.delegate = nil
                peer.close()
            }
        }
        try ensureOpen()

        let source = factory.audioSource(with: nil)
        let localTrack = factory.audioTrack(with: source, trackId: "buddystudy-microphone")
        guard peer.add(localTrack, streamIds: ["buddystudy-voice"]) != nil else {
            throw VoiceTutorWebRTCError.localTrackCreationFailed
        }
        try installConnectionResources(
            captureTap: nextCaptureTap,
            renderTap: nextRenderTap,
            processingModule: processingModule,
            factory: factory,
            peerConnection: peer,
            localAudioTrack: localTrack
        )
        resourcesInstalled = true

        let offer = try await createOffer(peer: peer, constraints: constraints)
        try ensureOpen()
        // Keep a validated value snapshot before handing the description to
        // WebRTC. Teardown must never turn a later SDP read into an empty POST.
        let offerSDP = try Self.validatedOfferSDP(offer.sdp)
        try await setLocalDescription(offer, peer: peer)
        try ensureOpen()
        let answerSDP = try await exchangeSDP(offerSDP, request: sdpRequest)
        try ensureOpen()
        let answer = LKRTCSessionDescription(type: .answer, sdp: answerSDP)
        try await setRemoteDescription(answer, peer: peer)
        try ensureOpen()
        attachRemoteAudioTrackIfPresent(on: peer)
        // SDP installation is not media readiness. Open the backend control
        // socket only after ICE + DTLS are connected so its opening tutor turn
        // cannot run ahead of the iPhone's media path.
        try await waitForMediaConnection(peer: peer)
    }

    func setMuted(_ muted: Bool) {
        stateLock.lock()
        let peerFactory = factory
        stateLock.unlock()
        _ = peerFactory?.audioDeviceModule.setMicrophoneMuted(muted)
    }

    func setSessionMediaReady() {
        stateLock.lock()
        sessionMediaReady = true
        let track = localAudioTrack
        stateLock.unlock()
        track?.isEnabled = true
    }

    func close() {
        let remoteTrack: LKRTCAudioTrack?
        let peer: LKRTCPeerConnection?
        let localTrack: LKRTCAudioTrack?
        let peerFactory: LKRTCPeerConnectionFactory?
        let processingModule: LKRTCDefaultAudioProcessingModule?
        let nextCaptureTap: VoiceTutorAudioProcessingTap?
        let nextRenderTap: VoiceTutorAudioProcessingTap?
        let observer: NSObjectProtocol?
        let shouldDeactivateAudioSession: Bool

        Self.audioSessionOwnershipLock.lock()
        stateLock.lock()
        isClosed = true
        shouldDeactivateAudioSession = Self.activeAudioSessionOwnerID == audioSessionOwnerID
        if shouldDeactivateAudioSession {
            Self.activeAudioSessionOwnerID = nil
        }
        remoteTrack = remoteAudioTrack
        remoteAudioTrack = nil
        peer = peerConnection
        peerConnection = nil
        localTrack = localAudioTrack
        localAudioTrack = nil
        peerFactory = factory
        factory = nil
        processingModule = audioProcessingModule
        audioProcessingModule = nil
        nextCaptureTap = captureTap
        captureTap = nil
        nextRenderTap = renderTap
        renderTap = nil
        observer = interruptionObserver
        interruptionObserver = nil
        stateLock.unlock()
        if shouldDeactivateAudioSession {
            try? AVAudioSession.sharedInstance().setActive(
                false,
                options: [.notifyOthersOnDeactivation]
            )
        }
        Self.audioSessionOwnershipLock.unlock()

        withExtendedLifetime(
            (localTrack, peerFactory, processingModule, nextCaptureTap, nextRenderTap)
        ) {
            remoteTrack?.remove(remoteRenderer)
            peer?.delegate = nil
            peer?.close()
        }
        if let observer {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    private func configureAudioSession() throws {
        Self.audioSessionOwnershipLock.lock()
        defer { Self.audioSessionOwnershipLock.unlock() }
        stateLock.lock()
        defer { stateLock.unlock() }
        if isClosed {
            throw CancellationError()
        }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(
            .playAndRecord,
            mode: .voiceChat,
            options: [.defaultToSpeaker, .allowBluetoothHFP]
        )
        try session.setPreferredIOBufferDuration(0.01)
        try session.setActive(true)
        Self.activeAudioSessionOwnerID = audioSessionOwnerID
    }

    private func installInterruptionObserver() throws {
        let observer = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance(),
            queue: nil
        ) { [weak self] notification in
            guard let typeValue = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  AVAudioSession.InterruptionType(rawValue: typeValue) == .began else {
                return
            }
            self?.onInterruption?()
        }
        stateLock.lock()
        guard !isClosed else {
            stateLock.unlock()
            NotificationCenter.default.removeObserver(observer)
            throw CancellationError()
        }
        let previousObserver = interruptionObserver
        interruptionObserver = observer
        stateLock.unlock()
        if let previousObserver {
            NotificationCenter.default.removeObserver(previousObserver)
        }
    }

    private func installConnectionResources(
        captureTap: VoiceTutorAudioProcessingTap?,
        renderTap: VoiceTutorAudioProcessingTap?,
        processingModule: LKRTCDefaultAudioProcessingModule,
        factory: LKRTCPeerConnectionFactory,
        peerConnection: LKRTCPeerConnection,
        localAudioTrack: LKRTCAudioTrack
    ) throws {
        stateLock.lock()
        guard !isClosed, self.peerConnection == nil, self.factory == nil else {
            stateLock.unlock()
            throw CancellationError()
        }
        localAudioTrack.isEnabled = sessionMediaReady
        self.captureTap = captureTap
        self.renderTap = renderTap
        audioProcessingModule = processingModule
        self.factory = factory
        self.peerConnection = peerConnection
        self.localAudioTrack = localAudioTrack
        stateLock.unlock()
    }

    private func createOffer(
        peer: LKRTCPeerConnection,
        constraints: LKRTCMediaConstraints
    ) async throws -> LKRTCSessionDescription {
        let boxed: VoiceTutorUncheckedSendable<LKRTCSessionDescription> = try await
            withCheckedThrowingContinuation { continuation in
            peer.offer(for: constraints) { description, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let description {
                    continuation.resume(
                        returning: VoiceTutorUncheckedSendable(value: description)
                    )
                } else {
                    continuation.resume(
                        throwing: VoiceTutorWebRTCError.offerCreationFailed
                    )
                }
            }
        }
        return boxed.value
    }

    private func setLocalDescription(
        _ description: LKRTCSessionDescription,
        peer: LKRTCPeerConnection
    ) async throws {
        try await withCheckedThrowingContinuation {
            (continuation: CheckedContinuation<Void, Error>) in
            peer.setLocalDescription(description) { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume(returning: ()) }
            }
        }
    }

    private func setRemoteDescription(
        _ description: LKRTCSessionDescription,
        peer: LKRTCPeerConnection
    ) async throws {
        try await withCheckedThrowingContinuation {
            (continuation: CheckedContinuation<Void, Error>) in
            peer.setRemoteDescription(description) { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume(returning: ()) }
            }
        }
    }

    private func exchangeSDP(_ offer: String, request: URLRequest) async throws -> String {
        let request = try Self.sdpExchangeRequest(offer: offer, authenticatedRequest: request)
        try ensureOpen()
        let (data, response) = try await networkSession.data(for: request)
        guard let response = response as? HTTPURLResponse else {
            throw VoiceTutorWebRTCError.invalidSDPResponse
        }
        guard (200..<300).contains(response.statusCode) else {
            throw VoiceTutorWebRTCError.sdpExchangeFailed(response.statusCode)
        }
        guard let answer = String(data: data, encoding: .utf8),
              answer.contains("v=0"),
              answer.utf8.count <= 65_536 else {
            throw VoiceTutorWebRTCError.invalidSDPResponse
        }
        return answer
    }

    private func waitForMediaConnection(peer: LKRTCPeerConnection) async throws {
        let startedAt = ProcessInfo.processInfo.systemUptime
        while true {
            try ensureOpen()
            switch Self.mediaReadiness(
                state: peer.connectionState,
                elapsedSeconds: ProcessInfo.processInfo.systemUptime - startedAt
            ) {
            case .connected:
                try ensureOpen()
                return
            case .failed:
                throw VoiceTutorWebRTCError.mediaConnectionFailed
            case .timedOut:
                throw VoiceTutorWebRTCError.mediaConnectionTimedOut
            case .waiting:
                // Cancellation wakes immediately; close() is observed within
                // one short poll, without retaining delegate continuations.
                try await Task.sleep(nanoseconds: 50_000_000)
            }
        }
    }

    static func mediaReadiness(
        state: LKRTCPeerConnectionState,
        elapsedSeconds: TimeInterval,
        timeoutSeconds: TimeInterval = 15
    ) -> VoiceTutorWebRTCMediaReadiness {
        if state == .connected { return .connected }
        if state == .failed || state == .closed { return .failed }
        if elapsedSeconds >= timeoutSeconds { return .timedOut }
        return .waiting
    }

    static func validatedOfferSDP(_ offer: String) throws -> String {
        let snapshot = String(decoding: offer.utf8, as: UTF8.self)
        let lines = snapshot.split(whereSeparator: \.isNewline).map(String.init)
        let media = lines.filter { $0.hasPrefix("m=") }
        guard snapshot.utf8.count <= 65_536,
              lines.first == "v=0",
              media.count == 1,
              let audio = media.first,
              audio.hasPrefix("m=audio "),
              !lines.contains(where: { $0.hasPrefix("a=sctp-") || $0.hasPrefix("a=sctpmap:") }) else {
            throw VoiceTutorWebRTCError.offerCreationFailed
        }
        return snapshot
    }

    static func sdpExchangeRequest(offer: String, authenticatedRequest: URLRequest) throws -> URLRequest {
        let offer = try validatedOfferSDP(offer)
        var request = authenticatedRequest
        request.httpMethod = "POST"
        request.setValue("application/sdp", forHTTPHeaderField: "Content-Type")
        request.setValue("application/sdp", forHTTPHeaderField: "Accept")
        request.setValue(nil, forHTTPHeaderField: "Content-Length")
        request.httpBodyStream = nil
        request.httpBody = Data(offer.utf8)
        return request
    }

    private func attachRemoteAudioTrackIfPresent(on peer: LKRTCPeerConnection) {
        for receiver in peer.receivers {
            if let track = receiver.track as? LKRTCAudioTrack {
                attachRemoteAudioTrack(track)
                return
            }
        }
    }

    private func attachRemoteAudioTrack(_ track: LKRTCAudioTrack) {
        stateLock.lock()
        defer { stateLock.unlock() }
        guard !isClosed, remoteAudioTrack !== track else { return }
        remoteAudioTrack?.remove(remoteRenderer)
        remoteAudioTrack = track
        track.add(remoteRenderer)
    }

    private func ensureOpen() throws {
        try Task.checkCancellation()
        stateLock.lock()
        let closed = isClosed
        stateLock.unlock()
        if closed {
            throw CancellationError()
        }
    }

}

extension VoiceTutorWebRTCTransport: LKRTCPeerConnectionDelegate {
    func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didChange stateChanged: LKRTCSignalingState
    ) {}

    func peerConnection(_ peerConnection: LKRTCPeerConnection, didAdd stream: LKRTCMediaStream) {
        if let track = stream.audioTracks.first {
            attachRemoteAudioTrack(track)
        }
    }

    func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didRemove stream: LKRTCMediaStream
    ) {}

    func peerConnectionShouldNegotiate(_ peerConnection: LKRTCPeerConnection) {}

    func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didChange newState: LKRTCIceConnectionState
    ) {
        stateLock.lock()
        let closed = isClosed
        stateLock.unlock()
        if !closed, newState == .failed {
            onConnectionFailure?()
        }
    }

    func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didChange newState: LKRTCIceGatheringState
    ) {}

    func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didGenerate candidate: LKRTCIceCandidate
    ) {}

    func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didRemove candidates: [LKRTCIceCandidate]
    ) {}

    func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didOpen dataChannel: LKRTCDataChannel
    ) {
        // BuddyStudy uses an authenticated sideband control socket. Reject any
        // provider-opened data channel so the offer stays audio-only and no
        // second policy-control path can appear.
        dataChannel.close()
    }

    func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didAdd rtpReceiver: LKRTCRtpReceiver,
        streams mediaStreams: [LKRTCMediaStream]
    ) {
        if let track = rtpReceiver.track as? LKRTCAudioTrack {
            attachRemoteAudioTrack(track)
        }
    }
}
#endif
