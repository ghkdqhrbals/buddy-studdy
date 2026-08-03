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
        let visibleQuestions = response.questions.filter {
            $0.status.caseInsensitiveCompare("graded") == .orderedSame &&
                !hiddenQuestionIDs.contains($0.id)
        }
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
        removeQuestions(ids: [id])
    }

    mutating func removeQuestions(ids: Set<String>) {
        guard !ids.isEmpty else {
            return
        }
        hiddenQuestionIDs.formUnion(ids)
        requestID = UUID()
        isLoading = false
        let removedCount = questions.count { ids.contains($0.id) }
        questions.removeAll { ids.contains($0.id) }
        totalCount = max(0, totalCount - removedCount)
        offset = max(0, offset - removedCount)
    }

    mutating func restoreQuestion(id: String) {
        restoreQuestions(ids: [id])
    }

    mutating func restoreQuestions(ids: Set<String>) {
        hiddenQuestionIDs.subtract(ids)
    }

    func canLoadMore(currentCount: Int) -> Bool {
        if currentCount <= 0 {
            return totalCount == 0 ? !questions.isEmpty : true
        }

        return currentCount < totalCount
    }
}
