#if os(iOS)
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

@MainActor
final class VoiceTutorViewModel: ObservableObject {
    @Published private(set) var phase: VoiceTutorSessionPhase = .idle
    @Published private(set) var captions: [VoiceTutorCaption] = []
    @Published private(set) var assistantTranscriptDraft = ""
    @Published private(set) var quotaRemainingSeconds = 0
    @Published private(set) var quotaLimitSeconds = 0
    @Published private(set) var sessionSecondsRemaining: Int?
    @Published private(set) var detail: BackendVoiceTutorSessionDetail?
    @Published private(set) var errorMessage: String?
    @Published private(set) var isMuted = false

    let study: BackendStudyRoom

    private let appState: AppState
    private let audioEngine: VoiceTutorAudioEngine
    private let transport: VoiceTutorWebSocketTransport
    private var receiveTask: Task<Void, Never>?
    private var audioSendTask: Task<Void, Never>?
    private var audioSendContinuation: AsyncStream<Data>.Continuation?
    private var serverEndContinuation: AsyncStream<VoiceTutorRealtimeEnded>.Continuation?
    private var heartbeatTask: Task<Void, Never>?
    private var countdownTask: Task<Void, Never>?
    private var sessionID: String?
    private var hardEndsAt: Date?
    private var isFinalizing = false
    private var duplexPlaybackState = VoiceTutorDuplexPlaybackState()

    init(
        appState: AppState,
        study: BackendStudyRoom,
        audioEngine: VoiceTutorAudioEngine = VoiceTutorAudioEngine(),
        transport: VoiceTutorWebSocketTransport = VoiceTutorWebSocketTransport()
    ) {
        self.appState = appState
        self.study = study
        self.audioEngine = audioEngine
        self.transport = transport
        let quota = appState.voiceTutorStatus?.quota ?? appState.billingStatus?.voiceTutor?.quota
        quotaRemainingSeconds = quota?.remainingSeconds ?? 0
        quotaLimitSeconds = quota?.limitSeconds ?? 0
    }

    func start() async {
        guard phase == .idle || phase == .failed else {
            return
        }
        errorMessage = nil
        detail = nil
        captions = []
        assistantTranscriptDraft = ""
        duplexPlaybackState.reset()
        phase = .requestingPermission

        guard await VoiceTutorAudioEngine.requestMicrophonePermission() else {
            errorMessage = appState.strings.voiceTutorMicrophoneDenied
            phase = .failed
            return
        }
        guard phase == .requestingPermission else {
            return
        }

        do {
            phase = .connecting
            let connection = try await appState.createVoiceTutorConnection(studyID: study.id)
            guard phase == .connecting else {
                _ = try? await appState.endVoiceTutorSession(
                    sessionID: connection.session.sessionId
                )
                return
            }
            sessionID = connection.session.sessionId
            hardEndsAt = connection.session.hardEndsAt
            quotaRemainingSeconds = connection.session.quota.remainingSeconds
            quotaLimitSeconds = connection.session.quota.limitSeconds
            updateSessionCountdown()

            try await transport.connect(request: connection.request)
            guard phase == .connecting else {
                await transport.disconnect(closeCode: .goingAway)
                return
            }
            startReceivingEvents()
            startHeartbeat()
            let audioSendContinuation = startAudioSendPump()
            try await audioEngine.start(
                onAudioChunk: { audio in
                    audioSendContinuation.yield(audio)
                },
                onPlaybackCompleted: { [weak self] responseID in
                    Task { @MainActor [weak self] in
                        await self?.handleTutorPlaybackCompleted(responseID: responseID)
                    }
                },
                onInterruption: { [weak self] in
                    Task { @MainActor [weak self] in
                        await self?.handleAudioSessionInterruption()
                    }
                }
            )
            guard phase == .connecting || phase == .listening || phase == .speaking else {
                audioEngine.stop()
                stopAudioSendPump()
                stopHeartbeat()
                await transport.disconnect(closeCode: .goingAway)
                return
            }
            startCountdown()
        } catch {
            audioEngine.stop()
            stopAudioSendPump()
            stopHeartbeat()
            await transport.disconnect(closeCode: .goingAway)
            await endCreatedSessionIfNeeded()
            errorMessage = localizedMessage(for: error)
            phase = .failed
        }
    }

    func toggleMute() {
        guard phase.isLive else {
            return
        }
        isMuted.toggle()
        audioEngine.setMuted(isMuted)
    }

    func stopForUser() async {
        await stop(shouldNotifyServerOverSocket: true)
    }

    func stopForBackground() async {
        guard phase != .idle, phase != .ended, !isFinalizing else {
            return
        }
        await stop(shouldNotifyServerOverSocket: true)
    }

    private func handleAudioSessionInterruption() async {
        guard phase.isLive, !isFinalizing else {
            return
        }
        errorMessage = appState.strings.voiceTutorAudioInterrupted
        await stop(shouldNotifyServerOverSocket: true)
    }

    private func stop(shouldNotifyServerOverSocket: Bool) async {
        guard !isFinalizing, phase != .ended else {
            return
        }
        isFinalizing = true
        phase = .ending
        audioEngine.stop()
        await finishAudioSendPump()
        stopHeartbeat()
        countdownTask?.cancel()
        countdownTask = nil

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
        if serverEnded == nil, let sessionID {
            endedDetail = try? await appState.endVoiceTutorSession(sessionID: sessionID)
        }
        receiveTask?.cancel()
        receiveTask = nil
        await transport.disconnect()
        await finishSession(
            initialDetail: endedDetail,
            pollAfterMilliseconds: serverEnded?.pollAfterMilliseconds
                ?? endedDetail?.pollAfterMilliseconds
        )
    }

    private func endCreatedSessionIfNeeded() async {
        guard let sessionID else {
            return
        }
        _ = try? await appState.endVoiceTutorSession(sessionID: sessionID)
        self.sessionID = nil
    }

    private func startReceivingEvents() {
        receiveTask?.cancel()
        receiveTask = Task { [weak self] in
            guard let self else {
                return
            }
            await self.receiveEvents()
        }
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

    private func receiveEvents() async {
        do {
            while !Task.isCancelled {
                let event = try await transport.receive()
                await handle(event)
            }
        } catch is CancellationError {
            return
        } catch {
            guard !isFinalizing, phase != .ended else {
                return
            }
            audioEngine.stop()
            errorMessage = appState.strings.voiceTutorConnectionFailed
            phase = .failed
            await stop(shouldNotifyServerOverSocket: false)
        }
    }

    private func handle(_ event: VoiceTutorRealtimeEvent) async {
        switch event {
        case .sessionReady(let hardEndsAt, let remainingSeconds):
            self.hardEndsAt = hardEndsAt ?? self.hardEndsAt
            if let remainingSeconds {
                quotaRemainingSeconds = max(0, remainingSeconds)
            }
            updateSessionCountdown()
            phase = .listening
        case .quotaUpdated(let quota):
            quotaLimitSeconds = quota.limitSeconds
            quotaRemainingSeconds = quota.remainingSeconds
            let existingQuota = appState.voiceTutorStatus?.quota
            appState.applyVoiceTutorQuota(
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
            audioEngine.setMuted(true)
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
            if let sessionID {
                detail = await appState.loadVoiceTutorSessionDetail(sessionID: sessionID)
            }
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
            phase = .failed
            await stop(shouldNotifyServerOverSocket: false)
        case .audioDelta(let delta):
            guard duplexPlaybackState.assistantAudioBegan(responseID: delta.responseID) else {
                break
            }
            try? audioEngine.playPCM24(delta)
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
            duplexPlaybackState.responseStarted(
                responseID: responseID,
                isTutorIntervention: isTutorIntervention
            )
        case .responseFinished(let responseID):
            guard duplexPlaybackState.matchesActiveResponse(responseID: responseID) else {
                break
            }
            commitAssistantTranscript(nil)
            if let responseID {
                audioEngine.finishResponseAudio(responseID: responseID)
            }
        case .ignored:
            break
        }
    }

    private func handleTutorPlaybackCompleted(responseID: String) async {
        guard phase.isLive else {
            return
        }
        try? await transport.sendPlaybackCompleted(responseID: responseID)
        guard duplexPlaybackState.responseFinished(responseID: responseID) else {
            return
        }
        phase = .listening
    }

    private func finishFromServer(_ ended: VoiceTutorRealtimeEnded) async {
        guard !isFinalizing else {
            return
        }
        isFinalizing = true
        phase = .ending
        audioEngine.stop()
        stopAudioSendPump()
        stopHeartbeat()
        countdownTask?.cancel()
        countdownTask = nil
        // This method runs inside receiveTask. Cancelling it here would also cancel
        // the result polling and leave the completed learning summary unloaded.
        receiveTask = nil
        await transport.disconnect()
        await finishSession(
            initialDetail: nil,
            pollAfterMilliseconds: ended.pollAfterMilliseconds
        )
    }

    private func finishSession(
        initialDetail: BackendVoiceTutorSessionDetail?,
        pollAfterMilliseconds: Int?
    ) async {
        detail = initialDetail
        if let sessionID {
            detail = await pollForResult(
                sessionID: sessionID,
                initialDetail: initialDetail,
                initialDelayMilliseconds: pollAfterMilliseconds
            )
        }
        await appState.refreshVoiceTutorStatus()
        await appState.loadVoiceTutorSessions(reset: true)
        duplexPlaybackState.reset()
        isFinalizing = false
        phase = .ended
    }

    private func pollForResult(
        sessionID: String,
        initialDetail: BackendVoiceTutorSessionDetail?,
        initialDelayMilliseconds: Int?
    ) async -> BackendVoiceTutorSessionDetail? {
        var current = initialDetail
        var delayMilliseconds = max(250, min(initialDelayMilliseconds ?? 750, 5_000))
        for attempt in 0..<8 {
            if current?.result != nil || current?.resultStatus?.uppercased() == "COMPLETED" {
                return current
            }
            if attempt > 0 || initialDetail != nil {
                try? await Task.sleep(nanoseconds: UInt64(delayMilliseconds) * 1_000_000)
            }
            guard !Task.isCancelled else {
                return current
            }
            if let loaded = await appState.loadVoiceTutorSessionDetail(sessionID: sessionID) {
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
        countdownTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard let self, !Task.isCancelled else {
                    return
                }
                self.updateSessionCountdown()
            }
        }
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
