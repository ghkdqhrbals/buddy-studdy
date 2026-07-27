import Foundation

@MainActor
struct RecordsStateStore {
    private(set) var records: [StudyRecord]
    private(set) var totalCount = 0
    private(set) var loadedBackendCount = 0
    private(set) var isLoadingPage = false

    init(records: [StudyRecord] = []) {
        self.records = records
    }

    var pendingRecords: [StudyRecord] {
        records.filter { $0.gradingResult == nil }
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
        return true
    }

    mutating func applyPage(_ page: BackendRecordsPage, reset: Bool) {
        totalCount = max(page.totalCount, reset ? page.records.count : totalCount)
        loadedBackendCount = reset
            ? page.records.count
            : max(loadedBackendCount, page.offset + page.records.count)
    }

    mutating func finishPageLoad() {
        isLoadingPage = false
    }

    mutating func removeLoadedBackendRecord(_ record: StudyRecord) {
        guard record.gradingResult != nil else {
            return
        }
        totalCount = max(totalCount - 1, 0)
        loadedBackendCount = max(loadedBackendCount - 1, 0)
    }

    mutating func clear() {
        records = []
        totalCount = 0
        loadedBackendCount = 0
        isLoadingPage = false
    }

    mutating func updateAnswer(
        for question: QuestionItem,
        answer: String,
        matches: (StudyRecord, QuestionItem) -> Bool
    ) {
        guard let index = records.lastIndex(where: { matches($0, question) }),
              records[index].gradingResult == nil else {
            return
        }

        records[index].answer = answer
    }

    func record(
        matching question: QuestionItem?,
        matches: (StudyRecord, QuestionItem) -> Bool
    ) -> StudyRecord? {
        guard let question else {
            return nil
        }

        return records.last { matches($0, question) }
    }

    func record(questionCreatedAt: TimeInterval?) -> StudyRecord? {
        guard let questionCreatedAt else {
            return nil
        }

        return records
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
        lastAnswer: String,
        fallbackTopic: String,
        fallbackDifficulty: Difficulty,
        matches: (StudyRecord, QuestionItem) -> Bool
    ) -> [StudyRecord] {
        var pending = pendingRecords

        if let currentQuestion,
           gradingResult == nil,
           !pending.contains(where: { matches($0, currentQuestion) }) {
            pending.append(
                StudyRecord(
                    question: currentQuestion,
                    answer: lastAnswer.isEmpty ? nil : lastAnswer,
                    topic: fallbackTopic,
                    difficulty: fallbackDifficulty
                )
            )
        }

        return pending
    }
}
