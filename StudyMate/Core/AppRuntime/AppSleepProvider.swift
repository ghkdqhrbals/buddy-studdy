import Foundation

protocol AppSleepProviding: Sendable {
    func sleep(nanoseconds: UInt64) async throws
}

struct TaskAppSleepProvider: AppSleepProviding {
    func sleep(nanoseconds: UInt64) async throws {
        try await Task.sleep(nanoseconds: nanoseconds)
    }
}
