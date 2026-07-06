import Foundation

@MainActor
struct StatsUseCase {
    private let backendClient: RemotePushBackendClientProtocol

    init(backendClient: RemotePushBackendClientProtocol) {
        self.backendClient = backendClient
    }

    func fetchStats(
        registration: RemotePushRegistration,
        period: BackendStatsPeriod,
        startAt: Date?,
        endAt: Date?,
        sort: BackendStatsSort,
        limit: Int,
        offset: Int
    ) async throws -> BackendStats {
        try await backendClient.fetchStats(
            registration: registration,
            period: period,
            startAt: startAt,
            endAt: endAt,
            sort: sort,
            limit: limit,
            offset: offset
        )
    }

    func fetchStatsActivity(
        registration: RemotePushRegistration,
        startAt: Date?,
        endAt: Date?
    ) async throws -> BackendStatsActivity {
        try await backendClient.fetchStatsActivity(
            registration: registration,
            startAt: startAt,
            endAt: endAt
        )
    }
}
