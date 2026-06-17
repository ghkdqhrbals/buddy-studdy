import Foundation

@MainActor
struct StatsStateStore {
    private(set) var stats: BackendStats?
    private(set) var isLoading = false
    private(set) var errorMessage: String?
    private(set) var requestID = UUID()

    mutating func reset() {
        stats = nil
        isLoading = false
        errorMessage = nil
        requestID = UUID()
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
}
