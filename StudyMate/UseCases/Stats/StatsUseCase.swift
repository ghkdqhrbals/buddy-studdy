import Foundation

@MainActor
struct StatsUseCase {
    private let repository: StatsRepository

    init(repository: StatsRepository) {
        self.repository = repository
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
        try await repository.fetchStats(
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
        try await repository.fetchStatsActivity(
            registration: registration,
            startAt: startAt,
            endAt: endAt
        )
    }

    func fetchStudyGrowth(
        registration: RemotePushRegistration,
        startAt: Date?,
        endAt: Date?
    ) async throws -> BackendStudyGrowth {
        try await repository.fetchStudyGrowth(
            registration: registration,
            startAt: startAt,
            endAt: endAt
        )
    }
}
