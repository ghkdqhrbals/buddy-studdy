#if os(iOS)
import Foundation

struct VoiceTutorInterruptionAudioLevel: Equatable, Sendable {
    let rms: Double
    let peak: Double

    var isQuiet: Bool {
        rms.isFinite && peak.isFinite && rms >= 0 && peak >= 0 && rms <= peak + 1e-9
            && rms <= 0.0025 && peak <= 0.008
    }
}

struct VoiceTutorInterruptionBoundaryToken: Equatable, Sendable {
    let responseID: String
    let generation: UInt64
    let requestedAtUptime: TimeInterval
}

/// Finds a short acoustic gap before a user-requested interruption. The native
/// RTP renderer has no word timestamps, so this is a bounded opportunity to
/// finish a sound, not proof of a word/sentence boundary or response completion.
struct VoiceTutorInterruptionBoundaryState: Equatable {
    enum Decision: Equatable {
        case wait(TimeInterval)
        case quietGap
        case deadline
        case superseded
    }

    // A 450 ms cutoff could still fall inside the current word. Give a short
    // phrase room to finish, but never wait for an unbounded full response.
    static let maximumGraceSeconds: TimeInterval = 1.2
    static let minimumQuietSeconds: TimeInterval = 0.08
    static let maximumObservationGapSeconds: TimeInterval = 0.08
    static let maximumBufferDurationSeconds: TimeInterval = 0.25

    private(set) var responseID: String?
    private(set) var generation: UInt64 = 0
    private var lastObservedAt: TimeInterval?
    private var quietDuration: TimeInterval = 0

    mutating func responseStarted(_ responseID: String?) {
        guard let responseID, !responseID.isEmpty, self.responseID != responseID else { return }
        generation &+= 1
        self.responseID = responseID
        lastObservedAt = nil
        quietDuration = 0
    }

    mutating func observe(
        level: VoiceTutorInterruptionAudioLevel?,
        duration: TimeInterval,
        at uptime: TimeInterval
    ) {
        guard responseID != nil else { return }
        guard uptime.isFinite, uptime >= 0, duration.isFinite,
              duration > 0, duration <= Self.maximumBufferDurationSeconds,
              let level, level.rms.isFinite, level.peak.isFinite else {
            lastObservedAt = nil
            quietDuration = 0
            return
        }
        if let lastObservedAt, uptime <= lastObservedAt { return }
        let elapsed = lastObservedAt.map { uptime - $0 }
        let isContinuous = elapsed.map {
            $0 <= max(Self.maximumObservationGapSeconds, duration + 0.025)
        } ?? false
        if level.isQuiet {
            quietDuration = isContinuous
                ? quietDuration + min(duration, elapsed ?? duration)
                : duration
        } else {
            quietDuration = 0
        }
        lastObservedAt = uptime
    }

    func request(responseID: String, at uptime: TimeInterval) -> VoiceTutorInterruptionBoundaryToken? {
        guard self.responseID == responseID, !responseID.isEmpty,
              uptime.isFinite, uptime >= 0 else { return nil }
        return .init(responseID: responseID, generation: generation, requestedAtUptime: uptime)
    }

    func decision(for token: VoiceTutorInterruptionBoundaryToken, now: TimeInterval) -> Decision {
        guard responseID == token.responseID, generation == token.generation,
              now.isFinite, now >= token.requestedAtUptime else { return .superseded }
        let remaining = token.requestedAtUptime + Self.maximumGraceSeconds - now
        guard remaining > 0 else { return .deadline }
        if let lastObservedAt, lastObservedAt >= token.requestedAtUptime, now >= lastObservedAt,
           now - lastObservedAt <= Self.maximumObservationGapSeconds,
           quietDuration + 1e-9 >= Self.minimumQuietSeconds {
            return .quietGap
        }
        return .wait(remaining)
    }

    mutating func invalidate(responseID: String? = nil) {
        if let responseID, self.responseID != responseID { return }
        generation &+= 1
        self.responseID = nil
        lastObservedAt = nil
        quietDuration = 0
    }
}

struct VoiceTutorInterruptionFadeToken: Equatable, Sendable {
    let responseID: String
    let generation: UInt64
    let startedAtUptime: TimeInterval
}

/// A short gain ramp after the acoustic boundary, before the provider is told
/// to clear interrupted audio. It changes only output gain; it never freezes
/// the RTP queue or microphone. Every update is fenced to the original reply.
struct VoiceTutorInterruptionFadeState: Equatable {
    static let durationSeconds: TimeInterval = 0.08
    private(set) var responseID: String?
    private(set) var generation: UInt64 = 0
    private var startedAt: TimeInterval?

    mutating func responseStarted(_ responseID: String?) {
        guard let responseID, !responseID.isEmpty, self.responseID != responseID else { return }
        generation &+= 1
        self.responseID = responseID
        startedAt = nil
    }

    mutating func request(responseID: String, at uptime: TimeInterval) -> VoiceTutorInterruptionFadeToken? {
        guard self.responseID == responseID, uptime.isFinite, uptime >= 0 else { return nil }
        let start = startedAt ?? uptime
        startedAt = start
        return .init(responseID: responseID, generation: generation, startedAtUptime: start)
    }

    func gain(for token: VoiceTutorInterruptionFadeToken, at uptime: TimeInterval) -> Float? {
        guard responseID == token.responseID, generation == token.generation,
              startedAt == token.startedAtUptime, uptime.isFinite,
              uptime >= token.startedAtUptime else { return nil }
        let progress = min(1, (uptime - token.startedAtUptime) / Self.durationSeconds)
        // Zero slope at both ends avoids the instantaneous 1 -> 0 gain step
        // even when continuous speech reaches the acoustic hard deadline.
        return Float((1 + cos(.pi * progress)) / 2)
    }

    func currentGain(at uptime: TimeInterval) -> Float {
        guard let responseID, let startedAt else { return 1 }
        return gain(for: .init(responseID: responseID, generation: generation,
                              startedAtUptime: startedAt), at: uptime) ?? 1
    }

    mutating func invalidate() {
        generation &+= 1
        responseID = nil
        startedAt = nil
    }
}
#endif
