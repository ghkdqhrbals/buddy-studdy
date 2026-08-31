#if os(iOS)
import AVFoundation
import Foundation

enum VoiceTutorSessionPhase: Equatable {
    case idle
    case requestingPermission
    case connecting
    case listening
    case speaking
    case ending
    case ended
    case failed

    var isLive: Bool {
        switch self {
        case .requestingPermission, .connecting, .listening, .speaking:
            return true
        case .idle, .ending, .ended, .failed:
            return false
        }
    }

    static func completed(outcome: Self, serverState: String?, serverReason: String? = nil) -> Self {
        let reason = serverReason?.uppercased() ?? ""
        let failedReason = reason.hasSuffix("_ERROR") || reason.hasSuffix("_TIMEOUT")
            || ["AUTH_REVOKED", "CONTROL_BACKPRESSURE", "CLIENT_DISCONNECTED"].contains(reason)
        return outcome == .failed || serverState?.uppercased() == "FAILED" || failedReason ? .failed : .ended
    }

    func afterRenderedTutorAudio(assistantResponseActive: Bool) -> Self {
        // Do not interrupt startup's connecting guard or revive terminal UI.
        self == .listening && assistantResponseActive ? .speaking : self
    }
}

private enum VoiceTutorStopSource: String {
    case startupFailure, user, backgroundOrDismissal, audioInterruption, mediaFailure
    case identityInvalidated, controlReceiveFailure, providerError, pcmPlaybackFailure
    case outputBufferCleared, localSpeechDeliveryFailure
}

enum VoiceTutorDiagnosticError {
    static func fields(for error: Error) -> String {
        let value = error as NSError
        let safeDomains = [NSURLErrorDomain, NSCocoaErrorDomain, NSPOSIXErrorDomain, NSOSStatusErrorDomain, AVFoundationErrorDomain]
        let domain = safeDomains.contains(value.domain) ? value.domain : "other"
        let kind: String
        if let parseError = error as? VoiceTutorRealtimeEventParser.ParseError {
            kind = "parser.\(parseError)"
        } else {
            kind = String(describing: type(of: error))
        }
        // Never interpolate Error itself, userInfo, URLs, or descriptions.
        return "errorKind=\(kind) errorDomain=\(domain) errorCode=\(value.code)"
    }
}

struct VoiceTutorSessionQuotaState: Equatable {
    private(set) var remainingSeconds = 0
    private(set) var limitSeconds = 0
    private(set) var reservedSeconds = 0

    mutating func apply(_ quota: BackendVoiceTutorQuota) {
        remainingSeconds = quota.remainingSeconds
        limitSeconds = quota.limitSeconds
        reservedSeconds = quota.reservedSeconds
    }

    mutating func updateRemainingSeconds(_ seconds: Int) {
        remainingSeconds = max(0, seconds)
    }
}

struct VoiceTutorConnectionAttemptFence {
    private(set) var currentID = UUID()

    mutating func begin() -> UUID {
        currentID = UUID()
        return currentID
    }

    func isCurrent(_ candidate: UUID) -> Bool { candidate == currentID }
}

enum VoiceTutorAttemptDelivery {
    @MainActor
    static func deliver<Value: Sendable>(
        isCurrent: @MainActor () -> Bool,
        operation: @MainActor () async -> Value,
        apply: @MainActor (Value) async -> Void
    ) async {
        guard !Task.isCancelled, isCurrent() else { return }
        let value = await operation()
        guard !Task.isCancelled, isCurrent() else { return }
        await apply(value)
    }
}

struct VoiceTutorCaption: Identifiable, Equatable {
    enum Speaker: Equatable {
        case learner
        case tutor
    }

    let id = UUID()
    var speaker: Speaker
    var text: String
}

enum VoiceTutorLiveTextBounds {
    static let maximumDraftCharacters = 16_000
    static let maximumCaptionCharacters = 16_000
    static let maximumTotalCaptionCharacters = 32_000
    static let maximumCaptionCount = 80

    static func appending(delta: String, to draft: String) -> String {
        guard draft.count < maximumDraftCharacters else {
            return draft
        }
        return String((draft + delta).prefix(maximumDraftCharacters))
    }

    static func boundedCaption(_ text: String) -> String {
        String(text.prefix(maximumCaptionCharacters))
    }

    static func trim(_ captions: inout [VoiceTutorCaption]) {
        var totalCharacters = captions.reduce(0) { $0 + $1.text.count }
        while captions.count > maximumCaptionCount ||
            (totalCharacters > maximumTotalCaptionCharacters && captions.count > 1) {
            totalCharacters -= captions.removeFirst().text.count
        }
    }
}

struct VoiceTutorDuplexPlaybackState: Equatable {
    private(set) var isUserSpeaking = false
    private(set) var assistantResponseActive = false
    private(set) var activeResponseID: String?
    private(set) var tutorInterventionActive = false

    @discardableResult
    mutating func responseStarted(responseID: String?, isTutorIntervention: Bool) -> Bool {
        guard let responseID, !responseID.isEmpty else {
            return false
        }
        assistantResponseActive = true
        activeResponseID = responseID
        tutorInterventionActive = isTutorIntervention
        return true
    }

    func matchesActiveResponse(responseID: String?) -> Bool {
        guard assistantResponseActive,
              let activeResponseID,
              let responseID else {
            return false
        }
        return activeResponseID == responseID
    }

    mutating func assistantAudioBegan(responseID: String?) -> Bool {
        guard matchesActiveResponse(responseID: responseID) else {
            return false
        }
        assistantResponseActive = true
        return true
    }

    mutating func userSpeechStarted() {
        isUserSpeaking = true
    }

    mutating func userSpeechStopped() {
        isUserSpeaking = false
    }

    mutating func responseFinished(responseID: String) -> Bool {
        if let activeResponseID, activeResponseID != responseID {
            return false
        }
        assistantResponseActive = false
        activeResponseID = nil
        tutorInterventionActive = false
        return true
    }

    mutating func reset() {
        self = VoiceTutorDuplexPlaybackState()
    }
}

/// Control-stream state, not an acoustic playout acknowledgement. WebRTC keeps
/// one ordered RTP track across responses, including any locally buffered tail.
/// Its renderer also produces nonzero comfort/concealment noise indefinitely;
/// waiting for PCM silence must never be a prerequisite for another response.
struct VoiceTutorWebRTCResponseState: Equatable {
    private(set) var responseID: String?
    private(set) var responseDone = false
    private(set) var outputBufferStarted = false
    private(set) var outputBufferStopped = false

    var mayIndicateSpeaking: Bool {
        responseID != nil && outputBufferStarted && !outputBufferStopped
    }

    mutating func responseStarted(_ responseID: String?) {
        guard let responseID, !responseID.isEmpty, self.responseID != responseID else { return }
        self.responseID = responseID
        responseDone = false
        outputBufferStarted = false
        outputBufferStopped = false
    }

    mutating func markOutputBufferStarted(_ responseID: String?) {
        guard matches(responseID), !outputBufferStopped else { return }
        outputBufferStarted = true
    }

    mutating func markResponseDone(_ responseID: String?) -> String? {
        guard matches(responseID) else { return nil }
        responseDone = true
        return finishIfReady()
    }

    mutating func markOutputBufferStopped(_ responseID: String?) -> String? {
        guard matches(responseID) else { return nil }
        outputBufferStopped = true
        return finishIfReady()
    }

    private mutating func finishIfReady() -> String? {
        guard responseDone, outputBufferStopped, let responseID else { return nil }
        reset()
        return responseID
    }

    mutating func reset() {
        self = VoiceTutorWebRTCResponseState()
    }

    private func matches(_ candidate: String?) -> Bool {
        guard let responseID else { return false }
        return candidate == responseID
    }
}

@MainActor
final class VoiceTutorViewModel: ObservableObject {
    @Published private(set) var phase: VoiceTutorSessionPhase = .idle
    @Published private(set) var captions: [VoiceTutorCaption] = []
    @Published private(set) var assistantTranscriptDraft = ""
    @Published private(set) var sessionQuota = VoiceTutorSessionQuotaState()
    @Published private(set) var sessionSecondsRemaining: Int?
    @Published private(set) var detail: BackendVoiceTutorSessionDetail?
    @Published private(set) var errorMessage: String?
    @Published private(set) var isMuted = false
    @Published private(set) var isRecording = false

    var quotaRemainingSeconds: Int { sessionQuota.remainingSeconds }
    var quotaLimitSeconds: Int { sessionQuota.limitSeconds }
    var quotaReservedSeconds: Int { sessionQuota.reservedSeconds }

    let study: BackendStudyRoom

    private let appState: AppState
    private let audioEngine: VoiceTutorAudioEngine
    private let transport: VoiceTutorWebSocketTransport
    private let recordingConsent: Bool
    private var webRTCTransport: VoiceTutorWebRTCTransport?
    private var recorder: VoiceTutorSessionRecorder?
    private var usesWebRTC = false
    private var receiveTask: Task<Void, Never>?
    private var audioSendTask: Task<Void, Never>?
    private var audioSendContinuation: AsyncStream<Data>.Continuation?
    private var localSpeechEvents: VoiceTutorLocalSpeechEventStream?
    private var localSpeechSendTask: Task<Void, Never>?
    private var serverEndContinuation: AsyncStream<VoiceTutorRealtimeEnded>.Continuation?
    private var heartbeatTask: Task<Void, Never>?
    private var countdownTask: Task<Void, Never>?
    private var activeConnection: VoiceTutorLiveConnection?
    private var sessionID: String?
    private var hardEndsAt: Date?
    private var isFinalizing = false
    private var duplexPlaybackState = VoiceTutorDuplexPlaybackState()
    private var webRTCResponseState = VoiceTutorWebRTCResponseState()
    private var connectionAttemptFence = VoiceTutorConnectionAttemptFence()

    init(
        appState: AppState,
        study: BackendStudyRoom,
        recordingConsent: Bool = false,
        audioEngine: VoiceTutorAudioEngine = VoiceTutorAudioEngine(),
        transport: VoiceTutorWebSocketTransport = VoiceTutorWebSocketTransport()
    ) {
        self.appState = appState
        self.study = study
        self.recordingConsent = recordingConsent
        self.audioEngine = audioEngine
        self.transport = transport
        let quota = appState.voiceTutorStatus?.quota ?? appState.billingStatus?.voiceTutor?.quota
        if let quota { sessionQuota.apply(quota) }
    }

    func start() async {
        guard !isFinalizing, phase == .idle || phase == .failed else {
            return
        }
        let attemptID = connectionAttemptFence.begin()
        stopLocalSpeechEventPump()
        sessionID = nil
        clearSessionCountdown()
        isMuted = false
        errorMessage = nil
        detail = nil
        captions = []
        assistantTranscriptDraft = ""
        duplexPlaybackState.reset()
        webRTCResponseState.reset()
        usesWebRTC = false
        isRecording = false
        phase = .requestingPermission
        let requestedOwnerID = appState.communityProfile?.id
        let requestedLifecycleGeneration = VoiceTutorRecordingStore.lifecycleGeneration()

        let hasMicrophonePermission = await VoiceTutorAudioEngine.requestMicrophonePermission()
        guard connectionAttemptFence.isCurrent(attemptID), phase == .requestingPermission else {
            return
        }
        guard hasMicrophonePermission else {
            errorMessage = appState.strings.voiceTutorMicrophoneDenied
            phase = .failed
            return
        }
        guard phase == .requestingPermission else {
            return
        }
        guard appState.isCommunitySessionActive,
              appState.communityProfile?.id == requestedOwnerID,
              VoiceTutorRecordingStore.isCurrentLifecycleGeneration(requestedLifecycleGeneration) else {
            phase = .ended
            return
        }

        do {
            phase = .connecting
            let connection = try await appState.createVoiceTutorConnection(
                studyID: study.id,
                recordingConsent: recordingConsent
            )
            guard connectionAttemptFence.isCurrent(attemptID),
                  phase == .connecting, connection.isCurrent() else {
                _ = try? await connection.endSession()
                return
            }
            activeConnection = connection
            sessionID = connection.session.sessionId
            hardEndsAt = connection.session.hardEndsAt
            sessionQuota.apply(connection.session.quota)
            updateSessionCountdown()

            if recordingConsent, connection.session.recording?.enabled == true {
                recorder = try VoiceTutorSessionRecorder(
                    sessionID: connection.session.sessionId,
                    ownerUserID: connection.ownerUserID,
                    lifecycleGeneration: connection.recordingLifecycleGeneration
                )
                isRecording = true
            }

            usesWebRTC = connection.webRTC != nil
            if let webRTCConnection = connection.webRTC {
                let webRTCTransport = VoiceTutorWebRTCTransport()
                self.webRTCTransport = webRTCTransport
                let speechEvents = startLocalSpeechEventPump(attemptID: attemptID, connection: connection)
                webRTCTransport.onLocalSpeechActivity = { event in
                    // Called serially from the capture gate, with no actor hop
                    // per audio frame and no microphone bytes in the queue.
                    speechEvents.yield(event)
                }
                webRTCTransport.onRenderedPCM = { [weak self] uptime in
                    Task { @MainActor [weak self] in
                        guard self?.connectionAttemptFence.isCurrent(attemptID) == true else { return }
                        self?.handleWebRTCRenderedPCM(at: uptime)
                    }
                }
                webRTCTransport.onInterruption = { [weak self] in
                    Task { @MainActor [weak self] in
                        guard self?.connectionAttemptFence.isCurrent(attemptID) == true else { return }
                        await self?.handleAudioSessionInterruption()
                    }
                }
                webRTCTransport.onConnectionFailure = { [weak self] in
                    Task { @MainActor [weak self] in
                        guard self?.connectionAttemptFence.isCurrent(attemptID) == true else { return }
                        await self?.handleWebRTCConnectionFailure()
                    }
                }
                webRTCTransport.onDiagnostic = { [weak self] message in
                    Task { @MainActor [weak self] in
                        guard let self,
                              self.connectionAttemptFence.isCurrent(attemptID),
                              connection.isCurrent() else { return }
                        self.appState.logVoiceTutorEvent(
                            "sessionId=\(connection.session.sessionId) phase=\(self.phase) \(message)"
                        )
                    }
                }
                try await webRTCTransport.connect(
                    sdpRequest: webRTCConnection.sdpRequest,
                    recorder: recorder
                )
                guard connectionAttemptFence.isCurrent(attemptID) else {
                    webRTCTransport.close()
                    return
                }
                guard connection.isCurrent() else { throw CancellationError() }
                guard phase == .connecting else {
                    webRTCTransport.close()
                    return
                }
                // The backend can claim /control only after SDP has attached
                // the provider call and transitioned this session to ACTIVE.
                try await transport.connect(
                    request: VoiceTutorLocalSpeechProtocol.addingCapability(to: webRTCConnection.controlRequest),
                    localSpeechAttemptID: attemptID
                )
            } else {
                try await transport.connect(request: connection.request)
                guard connectionAttemptFence.isCurrent(attemptID), connection.isCurrent() else {
                    throw CancellationError()
                }
                guard phase == .connecting else {
                    await transport.disconnect(closeCode: .goingAway)
                    return
                }
                let audioSendContinuation = startAudioSendPump()
                let recorder = recorder
                let pcmSampleRate = VoiceTutorAudioEngine.sampleRate
                try await audioEngine.start(
                    onAudioChunk: { audio in
                        if let frame = VoiceTutorPCMFrame(
                            pcm16Mono: audio,
                            sampleRate: pcmSampleRate,
                            capturedAtUptime: ProcessInfo.processInfo.systemUptime
                        ) {
                            recorder?.append(frame, participant: .learner)
                        }
                        audioSendContinuation.yield(audio)
                    },
                    onPlaybackCompleted: { [weak self] responseID in
                        Task { @MainActor [weak self] in
                            guard self?.connectionAttemptFence.isCurrent(attemptID) == true else { return }
                            await self?.handleTutorPlaybackCompleted(responseID: responseID, attemptID: attemptID)
                        }
                    },
                    onInterruption: { [weak self] in
                        Task { @MainActor [weak self] in
                            guard self?.connectionAttemptFence.isCurrent(attemptID) == true else { return }
                            await self?.handleAudioSessionInterruption()
                        }
                    }
                )
            }
            guard connectionAttemptFence.isCurrent(attemptID), connection.isCurrent() else {
                throw CancellationError()
            }
            startReceivingEvents(attemptID: attemptID)
            startHeartbeat()
            guard phase == .connecting || phase == .listening || phase == .speaking else {
                audioEngine.stop()
                webRTCTransport?.close()
                webRTCTransport = nil
                stopAudioSendPump()
                stopLocalSpeechEventPump()
                stopHeartbeat()
                await transport.disconnect(closeCode: .goingAway)
                await finishRecordingIfNeeded()
                return
            }
            startCountdown()
        } catch is CancellationError where phase != .connecting {
            // A foreground-loss/user stop can close WebRTC while an awaited SDP
            // step is resuming. Re-run idempotent teardown without replacing the
            // terminal UI state chosen by the stop path.
            guard connectionAttemptFence.isCurrent(attemptID) else { return }
            webRTCTransport?.close()
            webRTCTransport = nil
            stopLocalSpeechEventPump()
            return
        } catch {
            guard connectionAttemptFence.isCurrent(attemptID), !isFinalizing,
                  phase.isLive else { return }
            errorMessage = localizedMessage(for: error)
            logDiagnostic("event=startup_failed \(VoiceTutorDiagnosticError.fields(for: error))", isWarning: true)
            await stop(shouldNotifyServerOverSocket: false, outcome: .failed, source: .startupFailure)
        }
    }

    func toggleMute() {
        guard phase.isLive, activeConnection?.isCurrent() == true else {
            return
        }
        isMuted.toggle()
        if usesWebRTC {
            webRTCTransport?.setMuted(isMuted)
        } else {
            audioEngine.setMuted(isMuted)
        }
    }

    func stopForUser() async {
        await stop(shouldNotifyServerOverSocket: true, source: .user)
    }

    func stopForBackground() async {
        guard phase.isLive || phase == .ending, !isFinalizing else {
            return
        }
        await stop(shouldNotifyServerOverSocket: true, source: .backgroundOrDismissal)
    }

    private func handleAudioSessionInterruption() async {
        guard phase.isLive, !isFinalizing else {
            return
        }
        errorMessage = appState.strings.voiceTutorAudioInterrupted
        await stop(shouldNotifyServerOverSocket: true, outcome: .failed, source: .audioInterruption)
    }

    private func handleWebRTCConnectionFailure() async {
        guard phase.isLive, !isFinalizing else { return }
        errorMessage = appState.strings.voiceTutorConnectionFailed
        await stop(shouldNotifyServerOverSocket: false, outcome: .failed, source: .mediaFailure)
    }

    private func logDiagnostic(_ message: String, isWarning: Bool = false) {
        appState.logVoiceTutorEvent(
            "sessionId=\(sessionID ?? "none") phase=\(phase) \(message)",
            isWarning: isWarning
        )
    }

    private func stop(
        shouldNotifyServerOverSocket: Bool,
        outcome: VoiceTutorSessionPhase = .ended,
        source: VoiceTutorStopSource
    ) async {
        guard !isFinalizing, phase.isLive || phase == .ending else {
            return
        }
        logDiagnostic("event=stop_requested source=\(source.rawValue) socketEnd=\(shouldNotifyServerOverSocket ? 1 : 0)", isWarning: outcome == .failed)
        recorder?.stopAcceptingFrames()
        isFinalizing = true
        phase = .ending
        let shouldNotifyServerOverSocket = shouldNotifyServerOverSocket
            && activeConnection?.isCurrent() == true
        if activeConnection?.isCurrent() == false {
            audioEngine.stop()
            webRTCTransport?.close()
        }
        if usesWebRTC {
            webRTCTransport?.setMuted(true)
        } else {
            audioEngine.stop()
        }
        stopLocalSpeechEventPump()
        await finishAudioSendPump()
        stopHeartbeat()
        clearSessionCountdown()

        let serverEndStream = shouldNotifyServerOverSocket ? makeServerEndStream() : nil
        var didSendSocketEnd = false
        if shouldNotifyServerOverSocket {
            do {
                try await transport.sendSessionEnd()
                didSendSocketEnd = true
            } catch {
                didSendSocketEnd = false
            }
        }

        let serverEnded: VoiceTutorRealtimeEnded?
        if didSendSocketEnd, let serverEndStream {
            serverEnded = await waitForServerEnd(from: serverEndStream)
        } else {
            serverEnded = nil
        }
        clearServerEndWaiter()

        var endedDetail: BackendVoiceTutorSessionDetail?
        if serverEnded == nil, let activeConnection {
            logDiagnostic("event=rest_end_requested")
            endedDetail = try? await activeConnection.endSession()
        }
        // A socket failure reaches this method from receiveTask itself. Closing
        // the socket ends the receive loop without cancelling REST settlement.
        receiveTask = nil
        webRTCTransport?.close()
        webRTCTransport = nil
        await transport.disconnect()
        await finishRecordingIfNeeded()
        await finishSession(
            initialDetail: endedDetail,
            pollAfterMilliseconds: serverEnded?.pollAfterMilliseconds
                ?? endedDetail?.pollAfterMilliseconds,
            outcome: .completed(outcome: outcome, serverState: nil, serverReason: serverEnded?.reason)
        )
    }

    private func stopForInvalidatedContext() async {
        // Account replacement is terminal, not learner overlap. Close both
        // microphone and playback immediately; the captured old registration
        // owns best-effort REST cleanup and must never update the new account.
        detail = nil
        captions = []
        assistantTranscriptDraft = ""
        audioEngine.stop()
        webRTCTransport?.close()
        stopAudioSendPump()
        stopLocalSpeechEventPump()
        if isFinalizing {
            await transport.disconnect(closeCode: .goingAway)
            return
        }
        await stop(shouldNotifyServerOverSocket: false, source: .identityInvalidated)
    }

    private func startReceivingEvents(attemptID: UUID) {
        receiveTask?.cancel()
        receiveTask = Task { [weak self] in
            guard let self else {
                return
            }
            await self.receiveEvents(attemptID: attemptID)
        }
    }

    private func startLocalSpeechEventPump(
        attemptID: UUID,
        connection: VoiceTutorLiveConnection
    ) -> VoiceTutorLocalSpeechEventStream {
        stopLocalSpeechEventPump()
        let events = VoiceTutorLocalSpeechEventStream()
        localSpeechEvents = events
        localSpeechSendTask = Task { [weak self] in
            do {
                for try await event in events.stream {
                    guard let self, !Task.isCancelled,
                          self.connectionAttemptFence.isCurrent(attemptID), connection.isCurrent(),
                          self.phase.isLive, !self.isFinalizing else { return }
                    switch event.activity {
                    case .started:
                        self.duplexPlaybackState.userSpeechStarted()
                        if !self.duplexPlaybackState.assistantResponseActive {
                            self.phase = .listening
                        }
                    case .stopped:
                        self.duplexPlaybackState.userSpeechStopped()
                    }
                    // One consumer awaits each send before taking the next edge.
                    // Backend alone commits input and creates the next response.
                    try await self.transport.sendInputSpeechActivity(event, attemptID: attemptID)
                    guard !Task.isCancelled, self.connectionAttemptFence.isCurrent(attemptID),
                          connection.isCurrent(), self.phase.isLive, !self.isFinalizing else { return }
                    self.logDiagnostic("event=local_speech_\(event.activity.rawValue) sequence=\(event.sequence)")
                }
            } catch is CancellationError {
                return
            } catch {
                guard let self, !Task.isCancelled,
                      self.connectionAttemptFence.isCurrent(attemptID), connection.isCurrent(),
                      self.phase.isLive, !self.isFinalizing else { return }
                self.logDiagnostic(
                    "event=local_speech_delivery_failed \(VoiceTutorDiagnosticError.fields(for: error))",
                    isWarning: true
                )
                // Preserve this task's settlement work when stop() cancels peers.
                self.localSpeechSendTask = nil
                self.localSpeechEvents?.finish()
                self.localSpeechEvents = nil
                self.errorMessage = self.appState.strings.voiceTutorConnectionFailed
                await self.stop(
                    shouldNotifyServerOverSocket: false,
                    outcome: .failed,
                    source: .localSpeechDeliveryFailure
                )
            }
        }
        return events
    }

    private func stopLocalSpeechEventPump() {
        localSpeechEvents?.finish()
        localSpeechEvents = nil
        localSpeechSendTask?.cancel()
        localSpeechSendTask = nil
    }

    private func startAudioSendPump() -> AsyncStream<Data>.Continuation {
        stopAudioSendPump()
        let (stream, continuation) = AsyncStream.makeStream(
            of: Data.self,
            bufferingPolicy: .bufferingNewest(8)
        )
        audioSendContinuation = continuation
        let transport = transport
        audioSendTask = Task {
            for await audio in stream {
                guard !Task.isCancelled else {
                    return
                }
                do {
                    try await transport.sendAudio(audio)
                } catch {
                    return
                }
            }
        }
        return continuation
    }

    private func stopAudioSendPump() {
        audioSendContinuation?.finish()
        audioSendContinuation = nil
        audioSendTask?.cancel()
        audioSendTask = nil
    }

    private func finishAudioSendPump() async {
        audioSendContinuation?.finish()
        audioSendContinuation = nil
        guard let task = audioSendTask else {
            return
        }
        audioSendTask = nil
        await withTaskGroup(of: Bool.self) { group in
            group.addTask {
                await task.value
                return true
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: 500_000_000)
                return false
            }
            _ = await group.next()
            task.cancel()
            group.cancelAll()
        }
    }

    private func startHeartbeat() {
        stopHeartbeat()
        let transport = transport
        heartbeatTask = Task {
            while !Task.isCancelled {
                do {
                    try await Task.sleep(nanoseconds: 15_000_000_000)
                    guard !Task.isCancelled else {
                        return
                    }
                    try await transport.sendHeartbeat()
                } catch {
                    return
                }
            }
        }
    }

    private func stopHeartbeat() {
        heartbeatTask?.cancel()
        heartbeatTask = nil
    }

    private func makeServerEndStream() -> AsyncStream<VoiceTutorRealtimeEnded> {
        clearServerEndWaiter()
        let (stream, continuation) = AsyncStream.makeStream(
            of: VoiceTutorRealtimeEnded.self,
            bufferingPolicy: .bufferingNewest(1)
        )
        serverEndContinuation = continuation
        return stream
    }

    private func clearServerEndWaiter() {
        serverEndContinuation?.finish()
        serverEndContinuation = nil
    }

    private func waitForServerEnd(
        from stream: AsyncStream<VoiceTutorRealtimeEnded>
    ) async -> VoiceTutorRealtimeEnded? {
        await withTaskGroup(of: VoiceTutorRealtimeEnded?.self) { group in
            group.addTask {
                var iterator = stream.makeAsyncIterator()
                return await iterator.next()
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }

    private func receiveEvents(attemptID: UUID) async {
        do {
            while !Task.isCancelled {
                guard connectionAttemptFence.isCurrent(attemptID) else { return }
                let event = try await transport.receive()
                guard connectionAttemptFence.isCurrent(attemptID) else { return }
                guard activeConnection?.isCurrent() == true else {
                    await stopForInvalidatedContext()
                    return
                }
                await handle(event, attemptID: attemptID)
                guard connectionAttemptFence.isCurrent(attemptID),
                      phase.isLive || phase == .ending else { return }
            }
        } catch is CancellationError {
            return
        } catch {
            guard connectionAttemptFence.isCurrent(attemptID),
                  !isFinalizing, phase.isLive || phase == .ending else {
                return
            }
            let closeCode = await transport.diagnosticCloseCode()
            guard connectionAttemptFence.isCurrent(attemptID), !isFinalizing,
                  phase.isLive || phase == .ending else { return }
            logDiagnostic(
                "event=control_receive_failed \(VoiceTutorDiagnosticError.fields(for: error)) wsCloseCode=\(closeCode.map(String.init) ?? "unknown")",
                isWarning: true
            )
            audioEngine.stop()
            errorMessage = appState.strings.voiceTutorConnectionFailed
            await stop(shouldNotifyServerOverSocket: false, outcome: .failed, source: .controlReceiveFailure)
        }
    }

    private func handle(_ event: VoiceTutorRealtimeEvent, attemptID: UUID) async {
        guard connectionAttemptFence.isCurrent(attemptID) else { return }
        guard let connection = activeConnection, connection.isCurrent() else {
            await stopForInvalidatedContext()
            return
        }
        if isFinalizing {
            switch event {
            case .sessionEnded, .quotaUpdated:
                break
            default:
                return
            }
        }
        switch event {
        case .sessionReady(let hardEndsAt, let remainingSeconds):
            recorder?.markSessionReady()
            if usesWebRTC {
                webRTCTransport?.setSessionMediaReady()
            }
            self.hardEndsAt = hardEndsAt ?? self.hardEndsAt
            if let remainingSeconds {
                sessionQuota.updateRemainingSeconds(remainingSeconds)
            }
            updateSessionCountdown()
            phase = .listening
        case .quotaUpdated(let quota):
            let existingQuota = appState.voiceTutorStatus?.quota
            applyServerQuota(
                BackendVoiceTutorQuota(
                    periodStartedAt: existingQuota?.periodStartedAt,
                    resetAt: existingQuota?.resetAt,
                    limitSeconds: quota.limitSeconds,
                    usedSeconds: quota.usedSeconds,
                    reservedSeconds: quota.reservedSeconds,
                    remainingSeconds: quota.remainingSeconds
                )
            )
        case .sessionEnding(_, let hardEndsAt):
            self.hardEndsAt = hardEndsAt ?? self.hardEndsAt
            if usesWebRTC {
                webRTCTransport?.setMuted(true)
            } else {
                audioEngine.setMuted(true)
            }
            phase = .ending
        case .sessionEnded(let ended):
            if isFinalizing {
                serverEndContinuation?.yield(ended)
                serverEndContinuation?.finish()
                serverEndContinuation = nil
            } else {
                await finishFromServer(ended)
            }
        case .resultReady:
            let completedSessionID = connection.session.sessionId
            await VoiceTutorAttemptDelivery.deliver(
                isCurrent: { self.connectionAttemptFence.isCurrent(attemptID) && connection.isCurrent() },
                operation: { await self.appState.loadVoiceTutorSessionDetail(sessionID: completedSessionID) },
                apply: { self.detail = $0 }
            )
        case .heartbeatAcknowledged:
            break
        case .serviceError(let code, _, _):
            switch code?.uppercased() {
            case "VOICE_TUTOR_PRO_REQUIRED":
                errorMessage = appState.strings.voiceTutorProRequiredMessage
            case "VOICE_TUTOR_QUOTA_EXCEEDED":
                errorMessage = appState.strings.voiceTutorQuotaReached
            case "VOICE_TUTOR_PROVIDER_UNAVAILABLE":
                errorMessage = appState.strings.serviceTemporarilyUnavailable
            default:
                errorMessage = appState.strings.voiceTutorConnectionFailed
            }
            await stop(shouldNotifyServerOverSocket: false, outcome: .failed, source: .providerError)
        case .audioDelta(let delta):
            guard !usesWebRTC else { break }
            guard duplexPlaybackState.assistantAudioBegan(responseID: delta.responseID) else {
                break
            }
            if let frame = VoiceTutorPCMFrame(
                pcm16Mono: delta.audio,
                sampleRate: VoiceTutorAudioEngine.sampleRate,
                capturedAtUptime: ProcessInfo.processInfo.systemUptime
            ) {
                recorder?.append(frame, participant: .tutor)
            }
            do {
                try audioEngine.playPCM24(delta)
            } catch {
                errorMessage = appState.strings.voiceTutorConnectionFailed
                await stop(shouldNotifyServerOverSocket: false, outcome: .failed, source: .pcmPlaybackFailure)
                break
            }
            phase = .speaking
        case .assistantTranscriptDelta(let responseID, let delta):
            guard duplexPlaybackState.matchesActiveResponse(responseID: responseID) else {
                break
            }
            assistantTranscriptDraft = VoiceTutorLiveTextBounds.appending(
                delta: delta,
                to: assistantTranscriptDraft
            )
        case .assistantTranscriptDone(let responseID, let transcript):
            guard duplexPlaybackState.matchesActiveResponse(responseID: responseID) else {
                break
            }
            commitAssistantTranscript(transcript)
        case .userTranscript(let transcript):
            appendCaption(speaker: .learner, text: transcript)
        case .userSpeechStarted:
            duplexPlaybackState.userSpeechStarted()
            if !duplexPlaybackState.assistantResponseActive {
                phase = .listening
            }
        case .userSpeechStopped:
            duplexPlaybackState.userSpeechStopped()
        case .responseStarted(let responseID, let isTutorIntervention):
            if !duplexPlaybackState.matchesActiveResponse(responseID: responseID) {
                duplexPlaybackState.responseStarted(
                    responseID: responseID,
                    isTutorIntervention: isTutorIntervention
                )
                if usesWebRTC {
                    webRTCResponseState.responseStarted(responseID)
                }
            }
        case .responseFinished(let responseID):
            guard duplexPlaybackState.matchesActiveResponse(responseID: responseID) else {
                break
            }
            commitAssistantTranscript(nil)
            if usesWebRTC {
                logDiagnostic("event=response_done")
                finishWebRTCResponseIfReady(webRTCResponseState.markResponseDone(responseID))
            } else if let responseID {
                audioEngine.finishResponseAudio(responseID: responseID)
            }
        case .outputAudioBufferStarted(let responseID):
            guard usesWebRTC,
                  duplexPlaybackState.matchesActiveResponse(responseID: responseID) else {
                break
            }
            // Provider generation is not proof that the iPhone rendered audio.
            // The first nonzero local render changes the speaking indication.
            webRTCResponseState.markOutputBufferStarted(responseID)
            logDiagnostic("event=provider_output_started")
        case .outputAudioBufferStopped(let responseID):
            guard usesWebRTC else { break }
            logDiagnostic("event=provider_output_stopped")
            finishWebRTCResponseIfReady(webRTCResponseState.markOutputBufferStopped(responseID))
        case .outputAudioBufferCleared:
            guard usesWebRTC else { break }
            // A normal learner overlap must never clear tutor audio. Treat an
            // observed clear as a protocol violation instead of acknowledging
            // a sentence that was not played to completion.
            errorMessage = appState.strings.voiceTutorConnectionFailed
            await stop(shouldNotifyServerOverSocket: false, outcome: .failed, source: .outputBufferCleared)
        case .ignored:
            break
        }
    }

    private func handleTutorPlaybackCompleted(responseID: String, attemptID: UUID) async {
        guard connectionAttemptFence.isCurrent(attemptID), phase.isLive,
              let connection = activeConnection, connection.isCurrent() else {
            return
        }
        try? await transport.sendPlaybackCompleted(responseID: responseID)
        guard !Task.isCancelled, connectionAttemptFence.isCurrent(attemptID),
              phase.isLive, connection.isCurrent(),
              duplexPlaybackState.responseFinished(responseID: responseID) else {
            return
        }
        phase = .listening
    }

    private func handleWebRTCRenderedPCM(at uptime: TimeInterval) {
        guard usesWebRTC, phase.isLive else { return }
        phase = phase.afterRenderedTutorAudio(
            assistantResponseActive: webRTCResponseState.mayIndicateSpeaking
        )
    }

    private func finishWebRTCResponseIfReady(_ responseID: String?) {
        guard let responseID, phase.isLive,
              duplexPlaybackState.responseFinished(responseID: responseID) else { return }
        // This ends only the UI's server-streaming state. Never stop/mute/clear
        // the remote track: its remaining RTP samples play before the next
        // response on the same continuous stream, even after these controls.
        logDiagnostic("event=provider_response_stream_finished")
        phase = .listening
    }

    private func finishFromServer(_ ended: VoiceTutorRealtimeEnded) async {
        guard !isFinalizing else {
            return
        }
        logDiagnostic("event=server_ended")
        recorder?.stopAcceptingFrames()
        isFinalizing = true
        phase = .ending
        audioEngine.stop()
        webRTCTransport?.close()
        webRTCTransport = nil
        stopAudioSendPump()
        stopLocalSpeechEventPump()
        stopHeartbeat()
        clearSessionCountdown()
        // This method runs inside receiveTask. Cancelling it here would also cancel
        // the result polling and leave the completed learning summary unloaded.
        receiveTask = nil
        await transport.disconnect()
        await finishRecordingIfNeeded()
        await finishSession(
            initialDetail: nil,
            pollAfterMilliseconds: ended.pollAfterMilliseconds,
            outcome: .completed(outcome: .ended, serverState: nil, serverReason: ended.reason)
        )
    }

    private func finishRecordingIfNeeded() async {
        guard let recorder else {
            isRecording = false
            return
        }
        self.recorder = nil
        isRecording = false
        let pending: VoiceTutorPendingRecording
        do {
            pending = try await recorder.finish()
        } catch {
            errorMessage = appState.strings.voiceTutorRecordingUnavailable
            return
        }
        do {
            try await appState.uploadVoiceTutorRecording(pending)
        } catch {
            // Upload failures retain a protected manifest for foreground retry.
            // Unlike a local finalize failure, no recording data was lost.
        }
    }

    private func finishSession(
        initialDetail: BackendVoiceTutorSessionDetail?,
        pollAfterMilliseconds: Int?,
        outcome: VoiceTutorSessionPhase = .ended
    ) async {
        detail = activeConnection?.isCurrent() == true ? initialDetail : nil
        if let quota = detail?.quota { applyServerQuota(quota) }
        if activeConnection?.isCurrent() == true, let sessionID {
            detail = await pollForResult(
                sessionID: sessionID,
                initialDetail: initialDetail,
                initialDelayMilliseconds: pollAfterMilliseconds
            )
        }
        if activeConnection?.isCurrent() == true, let quota = detail?.quota {
            applyServerQuota(quota)
        }
        if activeConnection?.isCurrent() == true {
            await appState.refreshVoiceTutorStatus()
        }
        if activeConnection?.isCurrent() == true, let quota = appState.voiceTutorStatus?.quota {
            sessionQuota.apply(quota)
        }
        if activeConnection?.isCurrent() == true {
            await appState.loadVoiceTutorSessions(reset: true)
        }
        if activeConnection?.isCurrent() != true {
            detail = nil
            captions = []
            assistantTranscriptDraft = ""
        }
        duplexPlaybackState.reset()
        webRTCResponseState.reset()
        usesWebRTC = false
        isFinalizing = false
        clearSessionCountdown()
        phase = .completed(outcome: outcome, serverState: detail?.state)
        if phase == .failed, errorMessage == nil {
            errorMessage = appState.strings.voiceTutorConnectionFailed
        }
        activeConnection = nil
    }

    private func pollForResult(
        sessionID: String,
        initialDetail: BackendVoiceTutorSessionDetail?,
        initialDelayMilliseconds: Int?
    ) async -> BackendVoiceTutorSessionDetail? {
        var current = initialDetail
        var delayMilliseconds = max(250, min(initialDelayMilliseconds ?? 750, 5_000))
        for attempt in 0..<8 {
            guard activeConnection?.isCurrent() == true else { return nil }
            if current?.result != nil || ["COMPLETED", "FAILED"].contains(current?.resultStatus?.uppercased() ?? "") {
                return current
            }
            if attempt > 0 || initialDetail != nil {
                try? await Task.sleep(nanoseconds: UInt64(delayMilliseconds) * 1_000_000)
            }
            guard activeConnection?.isCurrent() == true else { return nil }
            guard !Task.isCancelled else {
                return current
            }
            if let loaded = await appState.loadVoiceTutorSessionDetail(sessionID: sessionID) {
                guard activeConnection?.isCurrent() == true else { return nil }
                current = loaded
                delayMilliseconds = max(
                    250,
                    min(loaded.pollAfterMilliseconds ?? delayMilliseconds, 5_000)
                )
            }
        }
        return current
    }

    private func commitAssistantTranscript(_ completedTranscript: String?) {
        let text = (completedTranscript ?? assistantTranscriptDraft)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        assistantTranscriptDraft = ""
        appendCaption(speaker: .tutor, text: text)
    }

    private func appendCaption(speaker: VoiceTutorCaption.Speaker, text: String) {
        let normalized = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else {
            return
        }
        captions.append(
            VoiceTutorCaption(
                speaker: speaker,
                text: VoiceTutorLiveTextBounds.boundedCaption(normalized)
            )
        )
        VoiceTutorLiveTextBounds.trim(&captions)
    }

    private func startCountdown() {
        countdownTask?.cancel()
        let attemptID = connectionAttemptFence.currentID
        let connection = activeConnection
        countdownTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard let self, !Task.isCancelled,
                      self.connectionAttemptFence.isCurrent(attemptID) else {
                    return
                }
                guard connection?.isCurrent() == true else {
                    // Context cleanup originates here; do not self-cancel it
                    // when stop() clears the displayed countdown.
                    self.countdownTask = nil
                    await self.stopForInvalidatedContext()
                    return
                }
                self.updateSessionCountdown()
            }
        }
    }

    private func clearSessionCountdown() {
        countdownTask?.cancel()
        countdownTask = nil
        hardEndsAt = nil
        sessionSecondsRemaining = nil
    }

    private func applyServerQuota(_ quota: BackendVoiceTutorQuota) {
        sessionQuota.apply(quota)
        appState.applyVoiceTutorQuota(quota)
    }

    private func updateSessionCountdown() {
        guard let hardEndsAt else {
            sessionSecondsRemaining = nil
            return
        }
        sessionSecondsRemaining = max(0, Int(hardEndsAt.timeIntervalSinceNow.rounded(.up)))
    }

    private func localizedMessage(for error: Error) -> String {
        if let preparationError = error as? VoiceTutorPreparationError {
            switch preparationError {
            case .signInRequired:
                return appState.strings.voiceTutorSignInRequired
            case .missingRegistration:
                return appState.strings.voiceTutorAccountNotReady
            case .invalidWebSocketURL:
                return appState.strings.voiceTutorInvalidConnection
            }
        }
        if let audioError = error as? VoiceTutorAudioEngine.AudioError {
            switch audioError {
            case .microphonePermissionDenied:
                return appState.strings.voiceTutorMicrophoneDenied
            case .unsupportedInputFormat, .invalidOutputAudio:
                return appState.strings.voiceTutorConnectionFailed
            }
        }
        return appState.strings.voiceTutorConnectionFailed
    }
}
#endif
