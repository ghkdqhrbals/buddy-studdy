import Foundation

enum CommunityFeedSort: String, CaseIterable, Identifiable {
    case recommended, latest, views, likes
    var id: String { rawValue }

    func title(strings: AppStrings) -> String {
        switch self {
        case .recommended: strings.feedRecommended
        case .latest: strings.feedLatest
        case .views: strings.feedMostViewed
        case .likes: strings.feedMostLiked
        }
    }
}

enum CommunityFeedScope: String, CaseIterable, Identifiable {
    case all, following
    var id: String { rawValue }

    func title(strings: AppStrings) -> String {
        self == .all ? strings.feedAllTopics : strings.feedFollowingTopics
    }
}

struct CommunityTopicSubscriptions: Codable, Equatable {
    var topics: [String]
}

enum CommunityTopicSubscriptionPolicy {
    static let maximumCount = 30
    static let maximumTopicLength = 120

    static func displayLabel(_ topic: String) -> String {
        topic.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }

    static func matchingKey(_ topic: String) -> String {
        displayLabel(topic).lowercased().filter { !$0.isWhitespace && $0 != "-" && $0 != "_" }
    }

    static func uniqueTopics(_ topics: [String]) -> [String] {
        var seen = Set<String>()
        return topics.map(displayLabel).filter { topic in
            !topic.isEmpty && !matchingKey(topic).isEmpty && seen.insert(matchingKey(topic)).inserted
        }
    }

    static func isValid(_ topics: [String]) -> Bool {
        topics.count <= maximumCount && topics.allSatisfy {
            !matchingKey($0).isEmpty &&
                displayLabel($0).utf16.count <= maximumTopicLength &&
                matchingKey($0).utf16.count <= maximumTopicLength &&
                !displayLabel($0).unicodeScalars.contains { $0.value < 0x20 || (0x7f...0x9f).contains($0.value) }
        }
    }
}

/// Account-owned interests only live in memory; the backend is the source of truth.
struct CommunityTopicSubscriptionStateStore {
    private(set) var topics: [String] = []
    private(set) var hasLoaded = false
    private(set) var isLoading = false
    private(set) var isSaving = false
    private(set) var errorMessage: String?
    private var requestID = UUID()

    mutating func reset() { self = Self() }

    mutating func showValidationError(_ message: String) { errorMessage = message }

    mutating func beginLoading() -> UUID? {
        guard !isLoading, !isSaving else { return nil }
        requestID = UUID()
        isLoading = true
        errorMessage = nil
        return requestID
    }

    mutating func beginSaving() -> UUID? {
        guard hasLoaded, !isLoading, !isSaving else { return nil }
        requestID = UUID()
        isSaving = true
        errorMessage = nil
        return requestID
    }

    func isCurrentRequest(_ candidate: UUID) -> Bool { requestID == candidate }

    mutating func apply(_ response: CommunityTopicSubscriptions, requestID candidate: UUID) {
        guard isCurrentRequest(candidate) else { return }
        topics = response.topics
        hasLoaded = true
        isLoading = false
        isSaving = false
        errorMessage = nil
    }

    mutating func fail(_ message: String, requestID candidate: UUID) {
        guard isCurrentRequest(candidate) else { return }
        isLoading = false
        isSaving = false
        errorMessage = message
    }
}

struct CommunityNativeAdvertisementEligibilityPolicy {
    static func allowsServerSlot(
        isSignedIn: Bool,
        adFree: Bool?,
        query: String,
        offset: Int
    ) -> Bool {
        guard query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              offset == 0 else {
            return false
        }

        if !isSignedIn {
            return true
        }
        return adFree == false
    }
}

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
    private var pendingHiddenAdvertisements: [String: (index: Int, item: CommunityFeedItem)] = [:]

    mutating func reset() {
        questions = []
        items = []
        totalCount = 0
        offset = 0
        errorMessage = nil
        isLoading = false
        requestID = UUID()
        pageSize = 0
        hiddenQuestionIDs = []
        hiddenAuthorIDs = []
        hiddenAdvertisementCampaignIDs = []
        pendingHiddenAdvertisements = [:]
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

    mutating func applyPage(
        _ response: CommunityQuestionsResponse,
        offset normalizedOffset: Int,
        reset: Bool,
        allowsNativeAdSlots: Bool = true
    ) {
        if response.limit > 0 {
            pageSize = response.limit
        }
        let visibleQuestions = response.questions.filter {
            $0.canPublish &&
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
            case .nativeAdSlot:
                return allowsNativeAdSlots && normalizedOffset == 0
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

    mutating func invalidatePage() {
        requestID = UUID()
        isLoading = false
        errorMessage = nil
        clearPage()
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
        if pendingHiddenAdvertisements[campaignID] == nil,
           let index = items.firstIndex(where: { item in
               if case .advertisement(let advertisement) = item {
                   return advertisement.campaignID == campaignID
               }
               return false
           }) {
            pendingHiddenAdvertisements[campaignID] = (index, items[index])
        }
        hiddenAdvertisementCampaignIDs.insert(campaignID)
        items.removeAll { item in
            if case .advertisement(let advertisement) = item {
                return advertisement.campaignID == campaignID
            }
            return false
        }
    }

    mutating func confirmHiddenAdvertisement(campaignID: String) {
        pendingHiddenAdvertisements.removeValue(forKey: campaignID)
    }

    mutating func restoreAdvertisement(campaignID: String) {
        hiddenAdvertisementCampaignIDs.remove(campaignID)
        guard let pending = pendingHiddenAdvertisements.removeValue(forKey: campaignID),
              !items.contains(where: { $0.id == pending.item.id }) else {
            return
        }
        items.insert(pending.item, at: min(pending.index, items.count))
    }

    mutating func clearHiddenAdvertisements() {
        hiddenAdvertisementCampaignIDs.removeAll()
        pendingHiddenAdvertisements.removeAll()
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

@MainActor
struct LikedQuestionsStateStore {
    var questions: [CommunityQuestion] = []
    var totalCount = 0
    var offset = 0
    var isLoading = false
    var errorMessage: String?
    var requestID = UUID()
    var query = ""
    var hasLoadedInitialPage = false

    mutating func reset() {
        questions = []
        totalCount = 0
        offset = 0
        isLoading = false
        errorMessage = nil
        requestID = UUID()
        query = ""
        hasLoadedInitialPage = false
    }

    mutating func beginLoading(query: String) -> UUID {
        let nextRequestID = UUID()
        requestID = nextRequestID
        self.query = query
        isLoading = true
        errorMessage = nil
        return nextRequestID
    }

    func isCurrentRequest(_ candidate: UUID) -> Bool {
        requestID == candidate
    }

    mutating func finishLoading(_ candidate: UUID) {
        guard isCurrentRequest(candidate) else { return }
        isLoading = false
    }

    mutating func applyPage(_ response: CommunityQuestionsResponse, offset normalizedOffset: Int, reset: Bool) {
        let page = response.questions.filter(\.canPublish)
        if reset {
            questions = page
            hasLoadedInitialPage = true
        } else {
            let existingIDs = Set(questions.map(\.id))
            questions.append(contentsOf: page.filter { !existingIDs.contains($0.id) })
        }
        totalCount = max(0, response.totalCount)
        offset = normalizedOffset + response.questions.count
    }

    mutating func applyError(_ message: String, reset: Bool, preserveExisting: Bool) {
        errorMessage = message
        if reset {
            hasLoadedInitialPage = true
            if !preserveExisting {
                questions = []
                totalCount = 0
                offset = 0
            }
        }
    }

    mutating func updateQuestion(id: String, isLiked: Bool, likeCount: Int) {
        guard let index = questions.firstIndex(where: { $0.id == id }) else { return }
        questions[index].isLikedByMe = isLiked
        questions[index].likeCount = max(0, likeCount)
    }

    mutating func upsertLikedQuestion(_ question: CommunityQuestion, includeIfMissing: Bool) {
        if let index = questions.firstIndex(where: { $0.id == question.id }) {
            questions[index] = question
            return
        }
        guard includeIfMissing else { return }
        requestID = UUID()
        isLoading = false
        questions.insert(question, at: 0)
        totalCount += 1
        offset += 1
    }

    mutating func removeQuestion(id: String) {
        // A late page response must not reinsert a question after unlike succeeds.
        requestID = UUID()
        isLoading = false
        guard questions.contains(where: { $0.id == id }) else { return }
        questions.removeAll { $0.id == id }
        totalCount = max(0, totalCount - 1)
        offset = max(0, offset - 1)
    }

    func canLoadMore() -> Bool {
        offset < totalCount
    }
}

@MainActor
struct CommunityQuestionLikeRequestStore {
    private var requestIDsByQuestionID: [String: UUID] = [:]

    mutating func begin(questionID: String) -> UUID? {
        guard requestIDsByQuestionID[questionID] == nil else { return nil }
        let requestID = UUID()
        requestIDsByQuestionID[questionID] = requestID
        return requestID
    }

    func isCurrent(questionID: String, requestID: UUID) -> Bool {
        requestIDsByQuestionID[questionID] == requestID
    }

    func contains(questionID: String) -> Bool {
        requestIDsByQuestionID[questionID] != nil
    }

    mutating func finish(questionID: String, requestID: UUID) {
        guard isCurrent(questionID: questionID, requestID: requestID) else { return }
        requestIDsByQuestionID.removeValue(forKey: questionID)
    }

    mutating func reset() {
        requestIDsByQuestionID.removeAll()
    }
}
