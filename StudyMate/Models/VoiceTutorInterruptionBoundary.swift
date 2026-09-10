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

    static let maximumGraceSeconds: TimeInterval = 0.45
    static let minimumQuietSeconds: TimeInterval = 0.06
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
        if let lastObservedAt, now >= lastObservedAt,
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
#endif
