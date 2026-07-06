import Foundation

@MainActor
protocol CloudSyncProviding {
    func canUseCloudSync() -> Bool
    func makeService() -> CloudSyncServiceProtocol?
}

@MainActor
struct DefaultCloudSyncProvider: CloudSyncProviding {
    func canUseCloudSync() -> Bool {
        CloudSyncService.canUseCloudKitContainer()
    }

    func makeService() -> CloudSyncServiceProtocol? {
        guard canUseCloudSync() else {
            return nil
        }

        return CloudSyncService()
    }
}
