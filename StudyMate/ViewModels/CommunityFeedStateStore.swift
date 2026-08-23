import Foundation

@MainActor
struct CommunityFeedStateStore {
    var questions: [CommunityQuestion] = []
    var items: [CommunityFeedItem] = []
    var totalCount = 0
    var offset = 0
    var isLoading = false
    var errorMessage: String?
    var requestID = UUID()
    private var pageSize = 0
    private var hiddenQuestionIDs = Set<String>()
    private var hiddenAuthorIDs = Set<Int>()
    private var hiddenAdvertisementCampaignIDs = Set<String>()

    mutating func reset() {
        questions = []
        items = []
        totalCount = 0
        offset = 0
        errorMessage = nil
        requestID = UUID()
        pageSize = 0
        hiddenQuestionIDs = []
        hiddenAuthorIDs = []
        hiddenAdvertisementCampaignIDs = []
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
        if response.limit > 0 {
            pageSize = response.limit
        }
        let visibleQuestions = response.questions.filter {
            $0.status.caseInsensitiveCompare("graded") == .orderedSame &&
                !hiddenQuestionIDs.contains($0.id) &&
                !isAuthorHidden($0.author?.id)
        }
        let hiddenResponseCount = response.questions.count - visibleQuestions.count
        let visibleQuestionIDs = Set(visibleQuestions.map(\.id))
        let visibleItems = response.items.filter { item in
            switch item {
            case .publicQuestion(let question):
                return visibleQuestionIDs.contains(question.id)
            case .advertisement(let advertisement):
                return !hiddenAdvertisementCampaignIDs.contains(advertisement.campaignID)
            }
        }
        if reset {
            questions = visibleQuestions
            items = visibleItems
        } else {
            let existing = Set(questions.map(\.id))
            questions.append(contentsOf: visibleQuestions.filter { !existing.contains($0.id) })
            let existingItemIDs = Set(items.map(\.id))
            items.append(contentsOf: visibleItems.filter { !existingItemIDs.contains($0.id) })
        }
        totalCount = max(0, response.totalCount - hiddenResponseCount)
        offset = normalizedOffset + response.questions.count
    }

    mutating func clearPage() {
        questions = []
        items = []
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
        items.removeAll { item in
            if case .publicQuestion(let question) = item {
                return ids.contains(question.id)
            }
            return false
        }
        totalCount = max(0, totalCount - removedCount)
        offset = max(0, offset - removedCount)
    }

    mutating func restoreQuestion(id: String) {
        restoreQuestions(ids: [id])
    }

    mutating func restoreQuestions(ids: Set<String>) {
        hiddenQuestionIDs.subtract(ids)
    }

    mutating func hideAuthor(userID: Int) {
        hiddenAuthorIDs.insert(userID)
        requestID = UUID()
        isLoading = false
        let removedCount = questions.count { $0.author?.id == userID }
        questions.removeAll { $0.author?.id == userID }
        items.removeAll { item in
            if case .publicQuestion(let question) = item {
                return question.author?.id == userID
            }
            return false
        }
        totalCount = max(0, totalCount - removedCount)
        let adjustedOffset = max(0, offset - removedCount)
        if removedCount > 0, pageSize > 0 {
            // The backend converts offset to a page index with offset / limit.
            // Rewind to that page boundary so shifted rows are fetched without skipping them.
            offset = (adjustedOffset / pageSize) * pageSize
        } else {
            offset = adjustedOffset
        }
    }

    mutating func clearHiddenAuthors() {
        hiddenAuthorIDs.removeAll()
    }

    mutating func hideAdvertisement(campaignID: String) {
        hiddenAdvertisementCampaignIDs.insert(campaignID)
        items.removeAll { item in
            if case .advertisement(let advertisement) = item {
                return advertisement.campaignID == campaignID
            }
            return false
        }
    }

    mutating func clearHiddenAdvertisements() {
        hiddenAdvertisementCampaignIDs.removeAll()
    }

    func isAuthorHidden(_ userID: Int?) -> Bool {
        guard let userID else {
            return false
        }
        return hiddenAuthorIDs.contains(userID)
    }

    func canLoadMore(currentCount: Int) -> Bool {
        if currentCount <= 0 {
            return totalCount == 0 ? !questions.isEmpty : true
        }

        return currentCount < totalCount
    }
}
