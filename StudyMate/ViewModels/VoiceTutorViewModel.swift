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

struct VoiceTutorWebRTCPlayoutDrainState: Equatable {
    private(set) var responseID: String?
    private(set) var responseDone = false
    private(set) var outputBufferStopped = false
    private(set) var lastRenderUptime: TimeInterval?
    private(set) var stoppedUptime: TimeInterval?
    private(set) var latestObservedRenderUptime: TimeInterval?
    private(set) var acknowledgedRenderUptime: TimeInterval?
    private(set) var canSeedInitialUnassignedRender = true
    private(set) var generation = 0

    mutating func responseStarted(_ responseID: String?) {
        guard let responseID, !responseID.isEmpty else { return }
        self.responseID = responseID
        responseDone = false
        outputBufferStopped = false
        if canSeedInitialUnassignedRender,
           let latestObservedRenderUptime,
           acknowledgedRenderUptime.map({ latestObservedRenderUptime > $0 }) ?? true {
            // RTP media and the sideband control socket are independently
            // ordered. Preserve PCM that arrived before the session's first
            // response.created. Later unassigned PCM may belong to the response
            // that was just drained, so it must not seed a subsequent response.
            lastRenderUptime = latestObservedRenderUptime
        } else {
            lastRenderUptime = nil
        }
        canSeedInitialUnassignedRender = false
        stoppedUptime = nil
        generation += 1
    }

    mutating func rendered(at uptime: TimeInterval) {
        guard responseID != nil else {
            if canSeedInitialUnassignedRender {
                latestObservedRenderUptime = max(latestObservedRenderUptime ?? uptime, uptime)
            }
            return
        }
        latestObservedRenderUptime = max(latestObservedRenderUptime ?? uptime, uptime)
        lastRenderUptime = max(lastRenderUptime ?? uptime, uptime)
        generation += 1
    }

    mutating func markResponseDone(_ responseID: String?) {
        guard matches(responseID) else { return }
        responseDone = true
        generation += 1
    }

    mutating func markOutputBufferStopped(_ responseID: String?, at uptime: TimeInterval) {
        guard matches(responseID) else { return }
        outputBufferStopped = true
        stoppedUptime = uptime
        generation += 1
    }

    func drainDeadline(
        additionalLatency: TimeInterval
    ) -> (responseID: String, generation: Int, deadline: TimeInterval, renderedThrough: TimeInterval)? {
        guard responseDone,
              outputBufferStopped,
              let responseID,
              let stoppedUptime,
              let lastRenderUptime else {
            return nil
        }
        let lastPCM = max(lastRenderUptime, stoppedUptime)
        return (responseID, generation, lastPCM + max(0, additionalLatency), lastRenderUptime)
    }

    func isCurrent(responseID: String, generation: Int) -> Bool {
        self.responseID == responseID && self.generation == generation
            && responseDone && outputBufferStopped
    }

    mutating func markDrainDispatched(
        responseID: String,
        generation: Int,
        renderedThrough: TimeInterval
    ) -> Bool {
        guard isCurrent(responseID: responseID, generation: generation) else {
            return false
        }
        acknowledgedRenderUptime = max(
            acknowledgedRenderUptime ?? renderedThrough,
            renderedThrough
        )
        self.responseID = nil
        responseDone = false
        outputBufferStopped = false
        lastRenderUptime = nil
        stoppedUptime = nil
        self.generation += 1
        return true
    }

    mutating func reset() {
        self = VoiceTutorWebRTCPlayoutDrainState()
    }

    private func matches(_ candidate: String?) -> Bool {
        guard let responseID else { return false }
        return candidate == nil || candidate == responseID
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
    @Published private(set) var isRecording = false

    let study: BackendStudyRoom

    private let appState: AppState
    private let audioEngine: VoiceTutorAudioEngine
    private let transport: VoiceTutorWebSocketTransport
    private let recordingConsent: Bool
    private var webRTCTransport: VoiceTutorWebRTCTransport?
    private var playoutDrainTask: Task<Void, Never>?
    private var recorder: VoiceTutorSessionRecorder?
    private var usesWebRTC = false
    private var receiveTask: Task<Void, Never>?
    private var audioSendTask: Task<Void, Never>?
    private var audioSendContinuation: AsyncStream<Data>.Continuation?
    private var serverEndContinuation: AsyncStream<VoiceTutorRealtimeEnded>.Continuation?
    private var heartbeatTask: Task<Void, Never>?
    private var countdownTask: Task<Void, Never>?
    private var activeConnection: VoiceTutorLiveConnection?
    private var sessionID: String?
    private var hardEndsAt: Date?
    private var isFinalizing = false
    private var duplexPlaybackState = VoiceTutorDuplexPlaybackState()
    private var webRTCDrainState = VoiceTutorWebRTCPlayoutDrainState()

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
        webRTCDrainState.reset()
        usesWebRTC = false
        isRecording = false
        phase = .requestingPermission
        let requestedOwnerID = appState.communityProfile?.id
        let requestedLifecycleGeneration = VoiceTutorRecordingStore.lifecycleGeneration()

        guard await VoiceTutorAudioEngine.requestMicrophonePermission() else {
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
            guard phase == .connecting, connection.isCurrent() else {
                _ = try? await connection.endSession()
                return
            }
            activeConnection = connection
            sessionID = connection.session.sessionId
            hardEndsAt = connection.session.hardEndsAt
            quotaRemainingSeconds = connection.session.quota.remainingSeconds
            quotaLimitSeconds = connection.session.quota.limitSeconds
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
                webRTCTransport.onRenderedPCM = { [weak self] uptime in
                    Task { @MainActor [weak self] in
                        self?.handleWebRTCRenderedPCM(at: uptime)
                    }
                }
                webRTCTransport.onInterruption = { [weak self] in
                    Task { @MainActor [weak self] in
                        await self?.handleAudioSessionInterruption()
                    }
                }
                webRTCTransport.onConnectionFailure = { [weak self] in
                    Task { @MainActor [weak self] in
                        await self?.handleWebRTCConnectionFailure()
                    }
                }
                try await webRTCTransport.connect(
                    sdpRequest: webRTCConnection.sdpRequest,
                    recorder: recorder
                )
                guard connection.isCurrent() else { throw CancellationError() }
                guard phase == .connecting else {
                    webRTCTransport.close()
                    return
                }
                // The backend can claim /control only after SDP has attached
                // the provider call and transitioned this session to ACTIVE.
                try await transport.connect(request: webRTCConnection.controlRequest)
            } else {
                try await transport.connect(request: connection.request)
                guard connection.isCurrent() else { throw CancellationError() }
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
                            await self?.handleTutorPlaybackCompleted(responseID: responseID)
                        }
                    },
                    onInterruption: { [weak self] in
                        Task { @MainActor [weak self] in
                            await self?.handleAudioSessionInterruption()
                        }
                    }
                )
            }
            guard connection.isCurrent() else { throw CancellationError() }
            startReceivingEvents()
            startHeartbeat()
            guard phase == .connecting || phase == .listening || phase == .speaking else {
                audioEngine.stop()
                webRTCTransport?.close()
                webRTCTransport = nil
                stopAudioSendPump()
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
            webRTCTransport?.close()
            webRTCTransport = nil
            return
        } catch {
            audioEngine.stop()
            webRTCTransport?.close()
            webRTCTransport = nil
            stopAudioSendPump()
            stopHeartbeat()
            await transport.disconnect(closeCode: .goingAway)
            await finishRecordingIfNeeded()
            await endCreatedSessionIfNeeded()
            errorMessage = localizedMessage(for: error)
            phase = .failed
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

    private func handleWebRTCConnectionFailure() async {
        guard phase.isLive, !isFinalizing else { return }
        errorMessage = appState.strings.voiceTutorConnectionFailed
        await stop(shouldNotifyServerOverSocket: false)
    }

    private func stop(shouldNotifyServerOverSocket: Bool) async {
        guard !isFinalizing, phase != .ended else {
            return
        }
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
        if serverEnded == nil, let activeConnection {
            endedDetail = try? await activeConnection.endSession()
        }
        receiveTask?.cancel()
        receiveTask = nil
        playoutDrainTask?.cancel()
        playoutDrainTask = nil
        webRTCTransport?.close()
        webRTCTransport = nil
        await transport.disconnect()
        await finishRecordingIfNeeded()
        await finishSession(
            initialDetail: endedDetail,
            pollAfterMilliseconds: serverEnded?.pollAfterMilliseconds
                ?? endedDetail?.pollAfterMilliseconds
        )
    }

    private func endCreatedSessionIfNeeded() async {
        guard let activeConnection else {
            return
        }
        _ = try? await activeConnection.endSession()
        self.sessionID = nil
        self.activeConnection = nil
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
        if isFinalizing {
            await transport.disconnect(closeCode: .goingAway)
            return
        }
        await stop(shouldNotifyServerOverSocket: false)
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
                guard activeConnection?.isCurrent() == true else {
                    await stopForInvalidatedContext()
                    return
                }
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
        guard activeConnection?.isCurrent() == true else {
            await stopForInvalidatedContext()
            return
        }
        switch event {
        case .sessionReady(let hardEndsAt, let remainingSeconds):
            recorder?.markSessionReady()
            if usesWebRTC {
                webRTCTransport?.setSessionMediaReady()
            }
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
                await stop(shouldNotifyServerOverSocket: false)
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
                    webRTCDrainState.responseStarted(responseID)
                }
            }
        case .responseFinished(let responseID):
            guard duplexPlaybackState.matchesActiveResponse(responseID: responseID) else {
                break
            }
            commitAssistantTranscript(nil)
            if usesWebRTC {
                webRTCDrainState.markResponseDone(responseID)
                scheduleWebRTCPlayoutDrainIfReady()
            } else if let responseID {
                audioEngine.finishResponseAudio(responseID: responseID)
            }
        case .outputAudioBufferStarted(let responseID):
            guard usesWebRTC,
                  duplexPlaybackState.matchesActiveResponse(responseID: responseID) else {
                break
            }
            phase = .speaking
        case .outputAudioBufferStopped(let responseID):
            guard usesWebRTC else { break }
            webRTCDrainState.markOutputBufferStopped(
                responseID,
                at: ProcessInfo.processInfo.systemUptime
            )
            scheduleWebRTCPlayoutDrainIfReady()
        case .outputAudioBufferCleared:
            guard usesWebRTC else { break }
            // A normal learner overlap must never clear tutor audio. Treat an
            // observed clear as a protocol violation instead of acknowledging
            // a sentence that was not played to completion.
            errorMessage = appState.strings.voiceTutorConnectionFailed
            await stop(shouldNotifyServerOverSocket: false)
        case .ignored:
            break
        }
    }

    private func handleTutorPlaybackCompleted(responseID: String) async {
        guard phase.isLive, activeConnection?.isCurrent() == true else {
            return
        }
        try? await transport.sendPlaybackCompleted(responseID: responseID)
        guard activeConnection?.isCurrent() == true,
              duplexPlaybackState.responseFinished(responseID: responseID) else {
            return
        }
        phase = .listening
    }

    private func handleWebRTCRenderedPCM(at uptime: TimeInterval) {
        guard usesWebRTC, phase.isLive else { return }
        webRTCDrainState.rendered(at: uptime)
        scheduleWebRTCPlayoutDrainIfReady()
    }

    private func scheduleWebRTCPlayoutDrainIfReady() {
        let audioSession = AVAudioSession.sharedInstance()
        let latency = audioSession.outputLatency + audioSession.ioBufferDuration + 0.02
        guard let drain = webRTCDrainState.drainDeadline(
            additionalLatency: latency
        ) else {
            return
        }
        playoutDrainTask?.cancel()
        playoutDrainTask = Task { [weak self] in
            let remaining = max(0, drain.deadline - ProcessInfo.processInfo.systemUptime)
            if remaining > 0 {
                try? await Task.sleep(nanoseconds: UInt64(remaining * 1_000_000_000))
            }
            guard let self,
                  !Task.isCancelled,
                  self.webRTCDrainState.markDrainDispatched(
                    responseID: drain.responseID,
                    generation: drain.generation,
                    renderedThrough: drain.renderedThrough
                  ) else {
                return
            }
            do {
                try await self.transport.sendPlayoutDrained(responseID: drain.responseID)
            } catch {
                self.errorMessage = self.appState.strings.voiceTutorConnectionFailed
                await self.stop(shouldNotifyServerOverSocket: false)
                return
            }
            guard self.duplexPlaybackState.responseFinished(responseID: drain.responseID) else {
                return
            }
            self.phase = .listening
        }
    }

    private func finishFromServer(_ ended: VoiceTutorRealtimeEnded) async {
        guard !isFinalizing else {
            return
        }
        recorder?.stopAcceptingFrames()
        isFinalizing = true
        phase = .ending
        audioEngine.stop()
        playoutDrainTask?.cancel()
        playoutDrainTask = nil
        webRTCTransport?.close()
        webRTCTransport = nil
        stopAudioSendPump()
        stopHeartbeat()
        countdownTask?.cancel()
        countdownTask = nil
        // This method runs inside receiveTask. Cancelling it here would also cancel
        // the result polling and leave the completed learning summary unloaded.
        receiveTask = nil
        await transport.disconnect()
        await finishRecordingIfNeeded()
        await finishSession(
            initialDetail: nil,
            pollAfterMilliseconds: ended.pollAfterMilliseconds
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
        pollAfterMilliseconds: Int?
    ) async {
        detail = activeConnection?.isCurrent() == true ? initialDetail : nil
        if activeConnection?.isCurrent() == true, let sessionID {
            detail = await pollForResult(
                sessionID: sessionID,
                initialDetail: initialDetail,
                initialDelayMilliseconds: pollAfterMilliseconds
            )
        }
        if activeConnection?.isCurrent() == true {
            await appState.refreshVoiceTutorStatus()
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
        webRTCDrainState.reset()
        usesWebRTC = false
        isFinalizing = false
        phase = .ended
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
            if current?.result != nil || current?.resultStatus?.uppercased() == "COMPLETED" {
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
        countdownTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard let self, !Task.isCancelled else {
                    return
                }
                guard self.activeConnection?.isCurrent() == true else {
                    await self.stopForInvalidatedContext()
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
