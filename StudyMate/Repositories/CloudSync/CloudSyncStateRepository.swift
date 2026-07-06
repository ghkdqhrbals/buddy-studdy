import Foundation

struct CloudSyncStateSnapshot {
    var isEnabled: Bool
    var stateUpdatedAt: Date?
}

protocol CloudSyncStateRepository {
    func loadCloudSyncState() -> CloudSyncStateSnapshot
    func saveIsCloudSyncEnabled(_ isEnabled: Bool)
    func saveCloudSyncStateUpdatedAt(_ date: Date?)
}
