import Foundation

@MainActor
struct CommunityFeedStateStore {
    var questions: [CommunityQuestion] = []
    var totalCount = 0
    var offset = 0
    var isLoading = false
    var errorMessage: String?
    var requestID = UUID()
    private var hiddenQuestionIDs = Set<String>()

    mutating func reset() {
        questions = []
        totalCount = 0
        offset = 0
        errorMessage = nil
        requestID = UUID()
        hiddenQuestionIDs = []
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
        let visibleQuestions = response.questions.filter { !hiddenQuestionIDs.contains($0.id) }
        let hiddenResponseCount = response.questions.count - visibleQuestions.count
        if reset {
            questions = visibleQuestions
        } else {
            let existing = Set(questions.map(\.id))
            questions.append(contentsOf: visibleQuestions.filter { !existing.contains($0.id) })
        }
        totalCount = max(0, response.totalCount - hiddenResponseCount)
        offset = normalizedOffset + response.questions.count
    }

    mutating func clearPage() {
        questions = []
        offset = 0
        totalCount = 0
    }

    mutating func removeQuestion(id: String) {
        hiddenQuestionIDs.insert(id)
        requestID = UUID()
        isLoading = false
        let containedQuestion = questions.contains { $0.id == id }
        questions.removeAll { $0.id == id }
        if containedQuestion {
            totalCount = max(0, totalCount - 1)
            offset = max(0, offset - 1)
        }
    }

    mutating func restoreQuestion(id: String) {
        hiddenQuestionIDs.remove(id)
    }

    func canLoadMore(currentCount: Int) -> Bool {
        if currentCount <= 0 {
            return totalCount == 0 ? !questions.isEmpty : true
        }

        return currentCount < totalCount
    }
}
