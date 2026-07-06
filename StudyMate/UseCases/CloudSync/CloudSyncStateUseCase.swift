import Foundation

struct CloudSyncStateUseCase {
    private let repository: CloudSyncStateRepository

    init(repository: CloudSyncStateRepository) {
        self.repository = repository
    }

    func loadState() -> CloudSyncStateSnapshot {
        repository.loadCloudSyncState()
    }

    func saveIsEnabled(_ isEnabled: Bool) {
        repository.saveIsCloudSyncEnabled(isEnabled)
    }

    func saveStateUpdatedAt(_ date: Date?) {
        repository.saveCloudSyncStateUpdatedAt(date)
    }
}
