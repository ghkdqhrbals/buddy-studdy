import Foundation

struct SettingsStoreLocalStudyRecordRepository: LocalStudyRecordRepository {
    private let settingsStore: SettingsStore

    init(settingsStore: SettingsStore) {
        self.settingsStore = settingsStore
    }

    func loadStudyRecords() -> [StudyRecord] {
        settingsStore.loadStudyRecords()
    }

    func appendStudyRecord(question: QuestionItem, settings: StudySettings) {
        settingsStore.appendStudyRecord(question: question, settings: settings)
    }

    func updateStudyRecordAnswer(question: QuestionItem, answer: String, onlyIfUngraded: Bool) {
        settingsStore.updateStudyRecordAnswer(
            question: question,
            answer: answer,
            onlyIfUngraded: onlyIfUngraded
        )
    }

    func saveStudyRecord(_ record: StudyRecord) {
        settingsStore.saveStudyRecord(record)
    }

    func deleteStudyRecord(_ record: StudyRecord) {
        settingsStore.deleteStudyRecord(record)
    }

    func clearStudyRecords() {
        settingsStore.clearStudyRecords()
    }

    func replaceStudyRecords(_ records: [StudyRecord]) {
        settingsStore.replaceStudyRecords(records)
    }

    func replaceBackendStudyRecords(_ records: [StudyRecord]) {
        settingsStore.replaceBackendStudyRecords(records)
    }

    func loadAnswerDraft(recordID: String) -> String {
        settingsStore.loadAnswerDraft(recordID: recordID)
    }

    func saveAnswerDraft(_ answer: String, recordID: String) {
        settingsStore.saveAnswerDraft(answer, recordID: recordID)
    }

    func deleteAnswerDraft(recordID: String) {
        settingsStore.deleteAnswerDraft(recordID: recordID)
    }

    func loadQuestionHistory() -> [QuestionItem] {
        settingsStore.loadQuestionHistory()
    }

    func appendQuestionToHistory(_ question: QuestionItem) {
        settingsStore.appendQuestionToHistory(question)
    }

    func saveQuestionHistory(_ questions: [QuestionItem]) {
        settingsStore.saveQuestionHistory(questions)
    }

    func loadDeletedStudyRecordMarkers() -> [DeletedStudyRecordMarker] {
        settingsStore.loadDeletedStudyRecordMarkers()
    }

    func saveDeletedStudyRecordMarkers(_ markers: [DeletedStudyRecordMarker]) {
        settingsStore.saveDeletedStudyRecordMarkers(markers)
    }

    func loadStudyRecordsClearedAt() -> Date? {
        settingsStore.loadStudyRecordsClearedAt()
    }

    func saveStudyRecordsClearedAt(_ date: Date?) {
        settingsStore.saveStudyRecordsClearedAt(date)
    }
}
