import Foundation

@MainActor
struct CommunityFeedStateStore {
    var questions: [CommunityQuestion] = []
    var totalCount = 0
    var offset = 0
    var isLoading = false
    var errorMessage: String?
    var requestID = UUID()

    mutating func reset() {
        questions = []
        totalCount = 0
        offset = 0
        errorMessage = nil
        requestID = UUID()
    }

    mutating func beginLoading() -> UUID {
        let nextRequestID = UUID()
        requestID = nextRequestID
        isLoading = true
        errorMessage = nil
        return nextRequestID
    }

    func isCurrentRequest(_ candidate: UUID) -> Bool {
        requestID == candidate
    }

    mutating func finishLoading(_ candidate: UUID) {
        guard isCurrentRequest(candidate) else {
            return
        }
        isLoading = false
    }

    mutating func applyPage(_ response: CommunityQuestionsResponse, offset normalizedOffset: Int, reset: Bool) {
        if reset {
            questions = response.questions
        } else {
            let existing = Set(questions.map(\.id))
            questions.append(contentsOf: response.questions.filter { !existing.contains($0.id) })
        }
        totalCount = response.totalCount
        offset = normalizedOffset + response.questions.count
    }

    mutating func clearPage() {
        questions = []
        offset = 0
        totalCount = 0
    }

    mutating func removeQuestion(id: String) {
        guard questions.contains(where: { $0.id == id }) else {
            return
        }

        questions.removeAll { $0.id == id }
        totalCount = max(0, totalCount - 1)
        offset = max(0, offset - 1)
    }

    func canLoadMore(currentCount: Int) -> Bool {
        if currentCount <= 0 {
            return totalCount == 0 ? !questions.isEmpty : true
        }

        return currentCount < totalCount
    }
}
