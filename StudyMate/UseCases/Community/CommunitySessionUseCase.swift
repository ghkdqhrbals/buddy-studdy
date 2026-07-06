import Foundation

struct CommunitySessionUseCase {
    private let repository: CommunitySessionRepository

    init(repository: CommunitySessionRepository) {
        self.repository = repository
    }

    func isSignedIn() -> Bool {
        repository.loadIsCommunitySignedIn()
    }

    func setSignedIn(_ isSignedIn: Bool) {
        repository.saveIsCommunitySignedIn(isSignedIn)
    }
}
