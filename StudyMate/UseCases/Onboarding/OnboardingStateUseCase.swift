import Foundation

struct OnboardingStateUseCase {
    private let repository: OnboardingStateRepository

    init(repository: OnboardingStateRepository) {
        self.repository = repository
    }

    func hasCompletedOnboarding() -> Bool {
        repository.loadHasCompletedOnboarding()
    }

    func setHasCompletedOnboarding(_ hasCompleted: Bool) {
        repository.saveHasCompletedOnboarding(hasCompleted)
    }
}
