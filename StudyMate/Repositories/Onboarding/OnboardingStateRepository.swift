protocol OnboardingStateRepository {
    func loadHasCompletedOnboarding() -> Bool
    func saveHasCompletedOnboarding(_ hasCompleted: Bool)
}
