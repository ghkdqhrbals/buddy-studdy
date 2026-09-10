#if os(iOS)
import Foundation

struct VoiceTutorPauseControl: Equatable, Sendable {
    enum Kind: String, Sendable {
        case pause = "buddystudy.voice.pause.request"
        case inputQuiesced = "buddystudy.voice.pause.input.quiesced"
        case resume = "buddystudy.voice.resume.request"
    }

    let kind: Kind
    let sequence: Int64
}

/// Display/capture intent only. The server acknowledges the actual held state;
/// this model has no authority over elapsed time, quota, or the call lifetime.
struct VoiceTutorCallPauseState: Equatable, Sendable {
    enum Mode: Equatable, Sendable { case active, pausing, paused, resuming }

    static let supportedProtocol = "pause-v1"
    var isSupported = false
    private(set) var mode: Mode = .active
    private(set) var sequence: Int64 = 0

    var holdsMicrophone: Bool { mode != .active }
    var isAwaitingAcknowledgement: Bool { mode == .pausing || mode == .resuming }

    // Includes the server's configured response timeout (hard-capped at 120s)
    // plus its clear-ACK grace. Resume has no tutor response to drain.
    var acknowledgementTimeoutSeconds: UInt64 { mode == .pausing ? 140 : 15 }

    mutating func requestPause() -> VoiceTutorPauseControl? {
        guard isSupported, mode == .active, sequence < .max else { return nil }
        sequence += 1
        mode = .pausing
        return VoiceTutorPauseControl(kind: .pause, sequence: sequence)
    }

    mutating func requestResume() -> VoiceTutorPauseControl? {
        guard isSupported, mode == .paused, sequence < .max else { return nil }
        sequence += 1
        mode = .resuming
        return VoiceTutorPauseControl(kind: .resume, sequence: sequence)
    }

    /// A stale, unsolicited, wrong-direction or duplicate ACK cannot unmute.
    mutating func acknowledge(sequence: Int64, paused: Bool) -> Bool {
        guard isSupported, sequence > 0, sequence == self.sequence else { return false }
        switch (mode, paused) {
        case (.pausing, true): mode = .paused
        case (.resuming, false): mode = .active
        default: return false
        }
        return true
    }

    func effectiveMicrophoneMuted(userMuted: Bool) -> Bool { userMuted || holdsMicrophone }
}

enum VoiceTutorCallControlEvent: Equatable, Sendable {
    case speech(VoiceTutorLocalSpeechEvent)
    case pause(VoiceTutorPauseControl)
    case answer(VoiceTutorAnswerControl)
}

/// One bounded sender for speech edges AND hold fences. The capture gate emits
/// its final stop synchronously between pause-request and input-quiesced, so an
/// asynchronous socket send cannot let a clear overtake the learner's tail.
struct VoiceTutorCallControlEventStream: Sendable {
    let stream: AsyncThrowingStream<VoiceTutorCallControlEvent, Error>
    private let continuation: AsyncThrowingStream<VoiceTutorCallControlEvent, Error>.Continuation

    init(capacity: Int = 32) {
        let pair = AsyncThrowingStream<VoiceTutorCallControlEvent, Error>.makeStream(
            bufferingPolicy: .bufferingOldest(max(1, min(capacity, 128)))
        )
        stream = pair.stream
        continuation = pair.continuation
    }

    func yield(_ event: VoiceTutorLocalSpeechEvent) { yield(.speech(event)) }
    func yield(_ control: VoiceTutorPauseControl) { yield(.pause(control)) }
    func yield(_ control: VoiceTutorAnswerControl) { yield(.answer(control)) }

    private func yield(_ event: VoiceTutorCallControlEvent) {
        switch continuation.yield(event) {
        case .enqueued, .terminated: break
        case .dropped:
            continuation.finish(throwing: VoiceTutorLocalSpeechDeliveryError.bufferOverflow)
        @unknown default:
            continuation.finish(throwing: VoiceTutorLocalSpeechDeliveryError.bufferOverflow)
        }
    }

    func finish() { continuation.finish() }
}
#endif
