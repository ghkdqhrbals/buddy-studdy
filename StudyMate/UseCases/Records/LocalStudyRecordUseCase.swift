import Foundation

struct LocalStudyRecordUseCase {
    private let repository: LocalStudyRecordRepository

    init(repository: LocalStudyRecordRepository) {
        self.repository = repository
    }

    func loadRecords() -> [StudyRecord] {
        repository.loadStudyRecords()
    }

    func appendRecord(question: QuestionItem, settings: StudySettings) {
        repository.appendStudyRecord(question: question, settings: settings)
    }

    func updateAnswer(question: QuestionItem, answer: String, onlyIfUngraded: Bool) {
        repository.updateStudyRecordAnswer(question: question, answer: answer, onlyIfUngraded: onlyIfUngraded)
    }

    func saveRecord(_ record: StudyRecord) {
        repository.saveStudyRecord(record)
    }

    func deleteRecord(_ record: StudyRecord) {
        repository.deleteStudyRecord(record)
    }

    func clearRecords() {
        repository.clearStudyRecords()
    }

    func replaceRecords(_ records: [StudyRecord]) {
        repository.replaceStudyRecords(records)
    }

    func replaceBackendRecords(_ records: [StudyRecord]) {
        repository.replaceBackendStudyRecords(records)
    }

    func loadAnswerDraft(recordID: String) -> String {
        repository.loadAnswerDraft(recordID: recordID)
    }

    func saveAnswerDraft(_ answer: String, recordID: String) {
        repository.saveAnswerDraft(answer, recordID: recordID)
    }

    func deleteAnswerDraft(recordID: String) {
        repository.deleteAnswerDraft(recordID: recordID)
    }

    func loadQuestionHistory() -> [QuestionItem] {
        repository.loadQuestionHistory()
    }

    func appendQuestionToHistory(_ question: QuestionItem) {
        repository.appendQuestionToHistory(question)
    }

    func saveQuestionHistory(_ questions: [QuestionItem]) {
        repository.saveQuestionHistory(questions)
    }

    func loadDeletedRecordMarkers() -> [DeletedStudyRecordMarker] {
        repository.loadDeletedStudyRecordMarkers()
    }

    func saveDeletedRecordMarkers(_ markers: [DeletedStudyRecordMarker]) {
        repository.saveDeletedStudyRecordMarkers(markers)
    }

    func limitedDeletedRecordMarkers(_ markers: [DeletedStudyRecordMarker]) -> [DeletedStudyRecordMarker] {
        Array(markers.suffix(SettingsStore.maxDeletedStudyRecordMarkerCount))
    }

    func loadRecordsClearedAt() -> Date? {
        repository.loadStudyRecordsClearedAt()
    }

    func saveRecordsClearedAt(_ date: Date?) {
        repository.saveStudyRecordsClearedAt(date)
    }
}
