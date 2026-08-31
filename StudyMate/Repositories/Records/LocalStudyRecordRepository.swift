import Foundation

protocol LocalStudyRecordRepository {
    #if os(iOS)
    func loadLearningRecordsPage(for key: StudyLearningRecordsCacheKey) -> BackendStudyLearningRecordsPage?
    func saveLearningRecordsPage(_ page: BackendStudyLearningRecordsPage, for key: StudyLearningRecordsCacheKey)
    func clearLearningRecordsPages()
    #endif
    func loadStudyRecords() -> [StudyRecord]
    func appendStudyRecord(question: QuestionItem, settings: StudySettings)
    func saveSubmittedAnswer(question: QuestionItem, answer: String, onlyIfUngraded: Bool)
    func saveStudyRecord(_ record: StudyRecord)
    func deleteStudyRecord(_ record: StudyRecord)
    func clearStudyRecords()
    func replaceStudyRecords(_ records: [StudyRecord])
    func replaceBackendStudyRecords(_ records: [StudyRecord])

    func loadAnswerDraft(recordID: String) -> String
    func saveAnswerDraft(_ answer: String, recordID: String)
    func deleteAnswerDraft(recordID: String)

    func loadQuestionHistory() -> [QuestionItem]
    func appendQuestionToHistory(_ question: QuestionItem)
    func saveQuestionHistory(_ questions: [QuestionItem])

    func loadDeletedStudyRecordMarkers() -> [DeletedStudyRecordMarker]
    func saveDeletedStudyRecordMarkers(_ markers: [DeletedStudyRecordMarker])
    func loadStudyRecordsClearedAt() -> Date?
    func saveStudyRecordsClearedAt(_ date: Date?)
}

#if os(iOS)
extension LocalStudyRecordRepository {
    // Isolated repositories without a cache remain valid. The production
    // SettingsStore adapter implements these using its existing record store.
    func loadLearningRecordsPage(for key: StudyLearningRecordsCacheKey) -> BackendStudyLearningRecordsPage? { nil }
    func saveLearningRecordsPage(_ page: BackendStudyLearningRecordsPage, for key: StudyLearningRecordsCacheKey) {}
    func clearLearningRecordsPages() {}
}
#endif
