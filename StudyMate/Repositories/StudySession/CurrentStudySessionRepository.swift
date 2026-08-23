struct CurrentStudySessionSnapshot {
    var question: QuestionItem?
    var lastAnswer: String
    var gradingResult: GradingResult?
    var isRunning: Bool
}

protocol CurrentStudySessionRepository {
    func loadCurrentStudySession() -> CurrentStudySessionSnapshot
    func saveQuestion(_ question: QuestionItem?)
    func saveLastAnswer(_ answer: String)
    func saveGradingResult(_ result: GradingResult?)
    func saveIsRunning(_ isRunning: Bool)
    func saveExplicitIsRunning(_ isRunning: Bool)
    func hasExplicitRunningPreference() -> Bool
    func loadPendingQuestionGenerationProcess() -> PendingQuestionGenerationProcess?
    func savePendingQuestionGenerationProcess(_ process: PendingQuestionGenerationProcess?)
}
