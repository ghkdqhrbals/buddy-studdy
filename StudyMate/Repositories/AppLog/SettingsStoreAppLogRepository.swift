import Foundation

struct SettingsStoreAppLogRepository: AppLogRepository {
    private let settingsStore: SettingsStore

    init(settingsStore: SettingsStore) {
        self.settingsStore = settingsStore
    }

    func loadAppLogs(page: Int, pageSize: Int) -> AppLogPage {
        settingsStore.loadAppLogs(page: page, pageSize: pageSize)
    }

    func appendAppLog(_ entry: AppLogEntry) {
        settingsStore.appendAppLog(entry)
    }

    func clearAppLogs() {
        settingsStore.clearAppLogs()
    }
}
