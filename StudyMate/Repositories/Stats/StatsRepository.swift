import Foundation

@MainActor
protocol StatsRepository {
    func fetchStats(
        registration: RemotePushRegistration,
        period: BackendStatsPeriod,
        startAt: Date?,
        endAt: Date?,
        sort: BackendStatsSort,
        limit: Int,
        offset: Int
    ) async throws -> BackendStats

    func fetchStatsActivity(
        registration: RemotePushRegistration,
        startAt: Date?,
        endAt: Date?
    ) async throws -> BackendStatsActivity
}
