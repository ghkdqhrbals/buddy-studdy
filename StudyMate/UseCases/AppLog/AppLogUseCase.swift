import Foundation

struct AppLogUseCase {
    private let repository: AppLogRepository

    init(repository: AppLogRepository) {
        self.repository = repository
    }

    func loadLogs(page: Int, pageSize: Int) -> AppLogPage {
        repository.loadAppLogs(page: page, pageSize: pageSize)
    }

    func appendLog(_ entry: AppLogEntry) {
        repository.appendAppLog(entry)
    }

    func clearLogs() {
        repository.clearAppLogs()
    }
}
