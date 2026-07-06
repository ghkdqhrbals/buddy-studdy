import Foundation

struct SettingsStoreCloudSyncStateRepository: CloudSyncStateRepository {
    private let settingsStore: SettingsStore

    init(settingsStore: SettingsStore) {
        self.settingsStore = settingsStore
    }

    func loadCloudSyncState() -> CloudSyncStateSnapshot {
        CloudSyncStateSnapshot(
            isEnabled: settingsStore.loadIsCloudSyncEnabled(),
            stateUpdatedAt: settingsStore.loadCloudSyncStateUpdatedAt()
        )
    }

    func saveIsCloudSyncEnabled(_ isEnabled: Bool) {
        settingsStore.saveIsCloudSyncEnabled(isEnabled)
    }

    func saveCloudSyncStateUpdatedAt(_ date: Date?) {
        settingsStore.saveCloudSyncStateUpdatedAt(date)
    }
}
