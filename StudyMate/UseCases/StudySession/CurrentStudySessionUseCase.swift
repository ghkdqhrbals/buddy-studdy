import Foundation

struct CurrentStudySessionUseCase {
    private let repository: CurrentStudySessionRepository

    init(repository: CurrentStudySessionRepository) {
        self.repository = repository
    }

    func loadSession() -> CurrentStudySessionSnapshot {
        repository.loadCurrentStudySession()
    }

    func saveCurrentQuestionState(
        question: QuestionItem?,
        lastAnswer: String,
        gradingResult: GradingResult?
    ) {
        repository.saveQuestion(question)
        repository.saveLastAnswer(lastAnswer)
        repository.saveGradingResult(gradingResult)
    }

    func saveQuestion(_ question: QuestionItem?) {
        repository.saveQuestion(question)
    }

    func saveLastAnswer(_ answer: String) {
        repository.saveLastAnswer(answer)
    }

    func saveGradingResult(_ result: GradingResult?) {
        repository.saveGradingResult(result)
    }

    func saveIsRunning(_ isRunning: Bool) {
        repository.saveIsRunning(isRunning)
    }

    func saveExplicitIsRunning(_ isRunning: Bool) {
        repository.saveExplicitIsRunning(isRunning)
    }

    func hasExplicitRunningPreference() -> Bool {
        repository.hasExplicitRunningPreference()
    }
}
