import Foundation

@MainActor
struct StatsStateStore {
    private(set) var stats: BackendStats?
    private(set) var activity: BackendStatsActivity?
    private(set) var isLoading = false
    private(set) var isActivityLoading = false
    private(set) var errorMessage: String?
    private(set) var activityErrorMessage: String?
    private(set) var requestID = UUID()
    private(set) var activityRequestID = UUID()

    mutating func reset() {
        stats = nil
        activity = nil
        isLoading = false
        isActivityLoading = false
        errorMessage = nil
        activityErrorMessage = nil
        requestID = UUID()
        activityRequestID = UUID()
    }

    mutating func beginRequest() -> UUID {
        let nextRequestID = UUID()
        requestID = nextRequestID
        errorMessage = nil
        isLoading = true
        return nextRequestID
    }

    func isCurrentRequest(_ candidate: UUID) -> Bool {
        requestID == candidate
    }

    mutating func finishRequest(_ candidate: UUID) {
        guard isCurrentRequest(candidate) else {
            return
        }
        isLoading = false
    }

    mutating func applyStats(_ stats: BackendStats, requestID candidate: UUID) {
        guard isCurrentRequest(candidate) else {
            return
        }
        self.stats = stats
    }

    mutating func applyError(_ message: String, requestID candidate: UUID) {
        guard isCurrentRequest(candidate) else {
            return
        }
        errorMessage = message
    }

    mutating func beginActivityRequest() -> UUID {
        let nextRequestID = UUID()
        activityRequestID = nextRequestID
        activityErrorMessage = nil
        isActivityLoading = true
        return nextRequestID
    }

    func isCurrentActivityRequest(_ candidate: UUID) -> Bool {
        activityRequestID == candidate
    }

    mutating func finishActivityRequest(_ candidate: UUID) {
        guard isCurrentActivityRequest(candidate) else {
            return
        }
        isActivityLoading = false
    }

    mutating func applyActivity(_ activity: BackendStatsActivity, requestID candidate: UUID) {
        guard isCurrentActivityRequest(candidate) else {
            return
        }
        self.activity = activity
    }

    mutating func applyActivityError(_ message: String, requestID candidate: UUID) {
        guard isCurrentActivityRequest(candidate) else {
            return
        }
        activityErrorMessage = message
    }
}
