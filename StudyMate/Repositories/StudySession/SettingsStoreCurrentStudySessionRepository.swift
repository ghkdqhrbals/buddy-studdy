struct SettingsStoreCurrentStudySessionRepository: CurrentStudySessionRepository {
    private let settingsStore: SettingsStore

    init(settingsStore: SettingsStore) {
        self.settingsStore = settingsStore
    }

    func loadCurrentStudySession() -> CurrentStudySessionSnapshot {
        CurrentStudySessionSnapshot(
            question: settingsStore.loadQuestion(),
            lastAnswer: settingsStore.loadLastAnswer(),
            gradingResult: settingsStore.loadGradingResult(),
            isRunning: settingsStore.loadIsRunning()
        )
    }

    func saveQuestion(_ question: QuestionItem?) {
        settingsStore.saveQuestion(question)
    }

    func saveLastAnswer(_ answer: String) {
        settingsStore.saveLastAnswer(answer)
    }

    func saveGradingResult(_ result: GradingResult?) {
        settingsStore.saveGradingResult(result)
    }

    func saveIsRunning(_ isRunning: Bool) {
        settingsStore.saveIsRunning(isRunning)
    }

    func saveExplicitIsRunning(_ isRunning: Bool) {
        settingsStore.saveExplicitIsRunning(isRunning)
    }

    func hasExplicitRunningPreference() -> Bool {
        settingsStore.hasExplicitRunningPreference()
    }
}
