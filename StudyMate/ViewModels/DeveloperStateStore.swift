import Foundation

@MainActor
struct DeveloperStateStore {
    var appLogs: [AppLogEntry]
    var appLogTotalCount: Int
    var appLogPage: Int
    var apiTrafficLogs: [APITrafficLogEntry] = []
    var isAPIDebugPanelPresented = false
    var isDebuggingEnabled: Bool
    var debugBackendBaseURL: String
    var draftDebugBackendBaseURL: String

    init(
        appLogs: [AppLogEntry] = [],
        appLogTotalCount: Int = 0,
        appLogPage: Int = 0,
        apiTrafficLogs: [APITrafficLogEntry] = [],
        isAPIDebugPanelPresented: Bool = false,
        isDebuggingEnabled: Bool = false,
        debugBackendBaseURL: String = "",
        draftDebugBackendBaseURL: String = ""
    ) {
        self.appLogs = appLogs
        self.appLogTotalCount = appLogTotalCount
        self.appLogPage = appLogPage
        self.apiTrafficLogs = apiTrafficLogs
        self.isAPIDebugPanelPresented = isAPIDebugPanelPresented
        self.isDebuggingEnabled = isDebuggingEnabled
        self.debugBackendBaseURL = debugBackendBaseURL
        self.draftDebugBackendBaseURL = draftDebugBackendBaseURL
    }

    mutating func applyLogPage(_ page: AppLogPage) {
        appLogs = page.entries
        appLogTotalCount = page.totalCount
        appLogPage = page.page
    }

    mutating func clearAppLogs() {
        appLogs = []
        appLogTotalCount = 0
        appLogPage = 0
    }

    mutating func appendAPITrafficLog(_ entry: APITrafficLogEntry, limit: Int) {
        apiTrafficLogs.insert(entry, at: 0)
        if apiTrafficLogs.count > limit {
            apiTrafficLogs.removeLast(apiTrafficLogs.count - limit)
        }
    }

    mutating func clearAPITrafficLogs() {
        apiTrafficLogs = []
    }
}
