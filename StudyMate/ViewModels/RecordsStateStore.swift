import Foundation

@MainActor
struct RecordsStateStore {
    private(set) var records: [StudyRecord]
    private(set) var totalCount = 0
    private(set) var loadedBackendCount = 0
    private(set) var isLoadingPage = false
    private(set) var hasLoadedPage = false
    private(set) var failedPageReset: Bool?

    init(records: [StudyRecord] = []) {
        self.records = records
    }

    var pendingRecords: [StudyRecord] {
        records.filter(\.isPendingQuestion)
    }

    mutating func replace(with records: [StudyRecord]) {
        self.records = records
    }

    var canLoadMore: Bool {
        loadedBackendCount < totalCount
    }

    mutating func beginPageLoad() -> Bool {
        guard !isLoadingPage else {
            return false
        }
        isLoadingPage = true
        failedPageReset = nil
        return true
    }

    mutating func applyPage(_ page: BackendRecordsPage, reset: Bool) {
        hasLoadedPage = true
        failedPageReset = nil
        totalCount = max(page.totalCount, reset ? page.records.count : totalCount)
        loadedBackendCount = reset
            ? page.records.count
            : max(loadedBackendCount, page.offset + page.records.count)
    }

    mutating func finishPageLoad() {
        isLoadingPage = false
    }

    mutating func failPageLoad(reset: Bool) {
        failedPageReset = reset
    }

    mutating func removeLoadedBackendRecord(_ record: StudyRecord) {
        guard record.isCompletedRecord else {
            return
        }
        totalCount = max(totalCount - 1, 0)
        loadedBackendCount = max(loadedBackendCount - 1, 0)
    }

    mutating func clear(loaded: Bool = false) {
        records = []
        totalCount = 0
        loadedBackendCount = 0
        isLoadingPage = false
        hasLoadedPage = loaded
        failedPageReset = nil
    }

    func record(
        matching question: QuestionItem?,
        matches: (StudyRecord, QuestionItem) -> Bool
    ) -> StudyRecord? {
        guard let question else {
            return nil
        }

        return records.last {
            $0.isQuestion && StudyRecordIdentityPolicy.sameQuestionInstance($0.question, question)
        } ?? records.last {
            $0.isQuestion && matches($0, question) &&
                StudyRecordIdentityPolicy.questionsMatch($0.question.question, question.question)
        }
    }

    func record(questionCreatedAt: TimeInterval?) -> StudyRecord? {
        guard let questionCreatedAt else {
            return nil
        }

        return records
            .filter(\.isQuestion)
            .map {
                (
                    record: $0,
                    distance: abs($0.question.createdAt.timeIntervalSince1970 - questionCreatedAt)
                )
            }
            .filter { $0.distance < 1 }
            .min { $0.distance < $1.distance }?
            .record
    }

    func pendingRecordsIncludingCurrent(
        currentQuestion: QuestionItem?,
        gradingResult: GradingResult?,
        fallbackTopic: String,
        fallbackDifficulty: Difficulty,
        matches: (StudyRecord, QuestionItem) -> Bool
    ) -> [StudyRecord] {
        var pending = pendingRecords

        if let currentQuestion,
           gradingResult == nil,
           !records.contains(where: { !$0.isPendingQuestion && matches($0, currentQuestion) }),
           !pending.contains(where: { matches($0, currentQuestion) }) {
            pending.append(
                StudyRecord(
                    question: currentQuestion,
                    topic: fallbackTopic,
                    difficulty: fallbackDifficulty
                )
            )
        }

        return pending
    }
}
