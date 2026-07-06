import Foundation

protocol AppLogRepository {
    func loadAppLogs(page: Int, pageSize: Int) -> AppLogPage
    func appendAppLog(_ entry: AppLogEntry)
    func clearAppLogs()
}
