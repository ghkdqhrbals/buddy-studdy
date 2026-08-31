#if os(iOS)
import Foundation

/// A result object can be a PROCESSING placeholder. Only explicit result
/// status, not the call's state or the presence of text, makes it terminal.
enum VoiceTutorSummaryState: Equatable, Sendable {
    case unknown, pending, ready, empty, failed

    init(detail: BackendVoiceTutorSessionDetail?) {
        let statuses = [detail?.resultStatus, detail?.result?.status].compactMap {
            $0?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        }
        if statuses.contains("FAILED") {
            self = .failed
        } else if statuses.contains("PENDING") || statuses.contains("PROCESSING") {
            self = .pending
        } else if statuses.contains("COMPLETED") {
            if let result = detail?.result, Self.hasContent(result) {
                self = .ready
            } else {
                self = .empty
            }
        } else {
            self = .unknown
        }
    }

    var isTerminal: Bool {
        switch self {
        case .ready, .empty, .failed: return true
        case .unknown, .pending: return false
        }
    }

    static func nonemptyItems(_ items: [String]) -> [String] {
        items.filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }

    private static func hasContent(_ result: BackendVoiceTutorSessionResult) -> Bool {
        !result.summaryMarkdown.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || !nonemptyItems(result.strengths + result.improvements + result.nextSteps).isEmpty
            || result.explorations.contains(where: VoiceTutorExplorationPresentation.hasContent)
    }
}

enum VoiceTutorSummaryRefreshState: Equatable, Sendable {
    case idle, loading, deferred, unavailable
}

struct VoiceTutorSummaryPollOutcome: Equatable, Sendable {
    enum Reason: Equatable, Sendable {
        case terminal, exhausted, unavailable, invalidated, cancelled
    }

    var detail: BackendVoiceTutorSessionDetail?
    var reason: Reason

    var refreshState: VoiceTutorSummaryRefreshState {
        switch reason {
        case .terminal, .invalidated, .cancelled: return .idle
        case .exhausted: return .deferred
        case .unavailable: return .unavailable
        }
    }
}

/// The call and history screen share a finite, identity-fenced read policy.
/// Exhaustion is not a failed summary and retrying only reads the existing one.
enum VoiceTutorSummaryPolling {
    static let maximumRequests = 8

    static func clampedDelayMilliseconds(_ value: Int?) -> Int {
        max(250, min(value ?? 750, 5_000))
    }

    @MainActor
    static func poll(
        sessionID: String,
        initialDetail: BackendVoiceTutorSessionDetail?,
        initialDelayMilliseconds: Int? = nil,
        refreshCachedDetail: Bool = false,
        loader: VoiceTutorSessionDetailLoader,
        isCurrent: @MainActor () -> Bool = { true },
        onUpdate: @MainActor (BackendVoiceTutorSessionDetail) -> Void = { _ in },
        sleep: @MainActor (Int) async throws -> Void = { milliseconds in
            try await Task.sleep(nanoseconds: UInt64(milliseconds) * 1_000_000)
        }
    ) async -> VoiceTutorSummaryPollOutcome {
        var current = initialDetail?.sessionId == sessionID ? initialDetail : nil
        var delay = clampedDelayMilliseconds(initialDelayMilliseconds ?? current?.pollAfterMilliseconds)

        @MainActor func interrupted() -> VoiceTutorSummaryPollOutcome? {
            if Task.isCancelled { return .init(detail: nil, reason: .cancelled) }
            if !loader.isCurrent() || !isCurrent() { return .init(detail: nil, reason: .invalidated) }
            return nil
        }

        for attempt in 0..<maximumRequests {
            if let interruption = interrupted() { return interruption }
            if !(refreshCachedDetail && attempt == 0), VoiceTutorSummaryState(detail: current).isTerminal {
                return .init(detail: current, reason: .terminal)
            }
            if attempt > 0 || (current != nil && !refreshCachedDetail) {
                do {
                    try await sleep(delay)
                } catch {
                    if let interruption = interrupted() { return interruption }
                    return .init(detail: current, reason: .cancelled)
                }
            }
            if let interruption = interrupted() { return interruption }
            let loaded = await loader.load()
            if let interruption = interrupted() { return interruption }
            guard let loaded, loaded.sessionId == sessionID else {
                return .init(detail: current, reason: .unavailable)
            }
            current = loaded
            onUpdate(loaded)
            if let interruption = interrupted() { return interruption }
            if VoiceTutorSummaryState(detail: loaded).isTerminal {
                return .init(detail: loaded, reason: .terminal)
            }
            delay = clampedDelayMilliseconds(loaded.pollAfterMilliseconds ?? delay)
        }
        return .init(detail: current, reason: .exhausted)
    }
}
#endif
