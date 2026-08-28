import Foundation

protocol PendingReferralRepository {
    func loadPendingReferralAttribution() -> PendingReferralAttribution?
    func savePendingReferralAttribution(_ attribution: PendingReferralAttribution?)
}

struct SettingsStorePendingReferralRepository: PendingReferralRepository {
    private let settingsStore: SettingsStore

    init(settingsStore: SettingsStore) {
        self.settingsStore = settingsStore
    }

    func loadPendingReferralAttribution() -> PendingReferralAttribution? {
        settingsStore.loadPendingReferralAttribution()
    }

    func savePendingReferralAttribution(_ attribution: PendingReferralAttribution?) {
        settingsStore.savePendingReferralAttribution(attribution)
    }
}

struct PendingReferralUseCase {
    private let repository: PendingReferralRepository

    init(repository: PendingReferralRepository) {
        self.repository = repository
    }

    func pendingAttribution() -> PendingReferralAttribution? {
        guard let stored = repository.loadPendingReferralAttribution(),
              let code = ReferralLink.normalizedCode(stored.code) else {
            repository.savePendingReferralAttribution(nil)
            return nil
        }
        if code != stored.code {
            let normalized = PendingReferralAttribution(
                code: code,
                source: stored.source,
                capturedAt: stored.capturedAt,
                accountID: stored.accountID,
                state: stored.state
            )
            repository.savePendingReferralAttribution(normalized)
            return normalized
        }
        return stored
    }

    @discardableResult
    func capture(
        code: String,
        source: PendingReferralSource,
        accountID: Int? = nil,
        capturedAt: Date = Date()
    ) -> PendingReferralAttribution? {
        guard let code = ReferralLink.normalizedCode(code) else {
            return nil
        }
        if var existing = pendingAttribution() {
            if existing.state == .serverConfirmed {
                return existing
            }
            if existing.code == code {
                if let accountID {
                    guard existing.accountID == nil || existing.accountID == accountID else {
                        return existing
                    }
                    existing.accountID = accountID
                }
                if source == .requiredTerms {
                    existing.source = source
                }
                repository.savePendingReferralAttribution(existing)
                return existing
            }
            if existing.accountID != nil || accountID != nil {
                return existing
            }
        }
        let attribution = PendingReferralAttribution(
            code: code,
            source: source,
            capturedAt: capturedAt,
            accountID: accountID
        )
        repository.savePendingReferralAttribution(attribution)
        return attribution
    }

    @discardableResult
    func markServerConfirmed(code: String, accountID: Int) -> PendingReferralAttribution? {
        guard var attribution = pendingAttribution(),
              attribution.code == ReferralLink.normalizedCode(code),
              attribution.accountID == nil || attribution.accountID == accountID else {
            return nil
        }
        attribution.accountID = accountID
        attribution.state = .serverConfirmed
        repository.savePendingReferralAttribution(attribution)
        return attribution
    }

    @discardableResult
    func clear(ifMatching code: String? = nil) -> Bool {
        if let code,
           pendingAttribution()?.code != ReferralLink.normalizedCode(code) {
            return false
        }
        repository.savePendingReferralAttribution(nil)
        return true
    }
}

@MainActor
struct AppLocalUseCases {
    let appLog: AppLogUseCase
    let storedBackendIdentity: StoredBackendIdentityUseCase
    let communityProfileCache: CommunityProfileCacheUseCase
    let communitySession: CommunitySessionUseCase
    let onboardingState: OnboardingStateUseCase
    let pendingReferral: PendingReferralUseCase
    let developerSettings: DeveloperSettingsUseCase
    let currentStudySession: CurrentStudySessionUseCase
    let localStudySettings: LocalStudySettingsUseCase
    let cloudSyncState: CloudSyncStateUseCase
    let localStudyRecord: LocalStudyRecordUseCase
    let appErrorHandling: AppErrorHandlingUseCase

    init(
        settingsStore: SettingsStore,
        appLogRepository: AppLogRepository? = nil,
        appLogUseCase: AppLogUseCase? = nil,
        remotePushRegistrationRepository: RemotePushRegistrationRepository? = nil,
        storedBackendIdentityUseCase: StoredBackendIdentityUseCase? = nil,
        communityProfileCacheRepository: CommunityProfileCacheRepository? = nil,
        communityProfileCacheUseCase: CommunityProfileCacheUseCase? = nil,
        communitySessionRepository: CommunitySessionRepository? = nil,
        communitySessionUseCase: CommunitySessionUseCase? = nil,
        onboardingStateRepository: OnboardingStateRepository? = nil,
        onboardingStateUseCase: OnboardingStateUseCase? = nil,
        pendingReferralRepository: PendingReferralRepository? = nil,
        pendingReferralUseCase: PendingReferralUseCase? = nil,
        developerSettingsRepository: DeveloperSettingsRepository? = nil,
        developerSettingsUseCase: DeveloperSettingsUseCase? = nil,
        currentStudySessionRepository: CurrentStudySessionRepository? = nil,
        currentStudySessionUseCase: CurrentStudySessionUseCase? = nil,
        localStudySettingsRepository: LocalStudySettingsRepository? = nil,
        localStudySettingsUseCase: LocalStudySettingsUseCase? = nil,
        cloudSyncStateRepository: CloudSyncStateRepository? = nil,
        cloudSyncStateUseCase: CloudSyncStateUseCase? = nil,
        localStudyRecordRepository: LocalStudyRecordRepository? = nil,
        localStudyRecordUseCase: LocalStudyRecordUseCase? = nil,
        appErrorHandlingUseCase: AppErrorHandlingUseCase = AppErrorHandlingUseCase()
    ) {
        let resolvedAppLogRepository = appLogRepository ?? SettingsStoreAppLogRepository(settingsStore: settingsStore)
        let resolvedRemotePushRegistrationRepository = remotePushRegistrationRepository
            ?? SettingsStoreRemotePushRegistrationRepository(settingsStore: settingsStore)
        let resolvedCommunityProfileCacheRepository = communityProfileCacheRepository
            ?? SettingsStoreCommunityProfileCacheRepository(settingsStore: settingsStore)
        let resolvedCommunitySessionRepository = communitySessionRepository
            ?? SettingsStoreCommunitySessionRepository(settingsStore: settingsStore)
        let resolvedOnboardingStateRepository = onboardingStateRepository
            ?? SettingsStoreOnboardingStateRepository(settingsStore: settingsStore)
        let resolvedPendingReferralRepository = pendingReferralRepository
            ?? SettingsStorePendingReferralRepository(settingsStore: settingsStore)
        let resolvedDeveloperSettingsRepository = developerSettingsRepository
            ?? SettingsStoreDeveloperSettingsRepository(settingsStore: settingsStore)
        let resolvedCurrentStudySessionRepository = currentStudySessionRepository
            ?? SettingsStoreCurrentStudySessionRepository(settingsStore: settingsStore)
        let resolvedLocalStudySettingsRepository = localStudySettingsRepository
            ?? SettingsStoreLocalStudySettingsRepository(settingsStore: settingsStore)
        let resolvedCloudSyncStateRepository = cloudSyncStateRepository
            ?? SettingsStoreCloudSyncStateRepository(settingsStore: settingsStore)
        let resolvedLocalStudyRecordRepository = localStudyRecordRepository
            ?? SettingsStoreLocalStudyRecordRepository(settingsStore: settingsStore)

        self.appLog = appLogUseCase ?? AppLogUseCase(repository: resolvedAppLogRepository)
        self.storedBackendIdentity = storedBackendIdentityUseCase
            ?? StoredBackendIdentityUseCase(repository: resolvedRemotePushRegistrationRepository)
        self.communityProfileCache = communityProfileCacheUseCase
            ?? CommunityProfileCacheUseCase(repository: resolvedCommunityProfileCacheRepository)
        self.communitySession = communitySessionUseCase
            ?? CommunitySessionUseCase(repository: resolvedCommunitySessionRepository)
        self.onboardingState = onboardingStateUseCase
            ?? OnboardingStateUseCase(repository: resolvedOnboardingStateRepository)
        self.pendingReferral = pendingReferralUseCase
            ?? PendingReferralUseCase(repository: resolvedPendingReferralRepository)
        self.developerSettings = developerSettingsUseCase
            ?? DeveloperSettingsUseCase(repository: resolvedDeveloperSettingsRepository)
        self.currentStudySession = currentStudySessionUseCase
            ?? CurrentStudySessionUseCase(repository: resolvedCurrentStudySessionRepository)
        self.localStudySettings = localStudySettingsUseCase
            ?? LocalStudySettingsUseCase(repository: resolvedLocalStudySettingsRepository)
        self.cloudSyncState = cloudSyncStateUseCase
            ?? CloudSyncStateUseCase(repository: resolvedCloudSyncStateRepository)
        self.localStudyRecord = localStudyRecordUseCase
            ?? LocalStudyRecordUseCase(repository: resolvedLocalStudyRecordRepository)
        self.appErrorHandling = appErrorHandlingUseCase
    }
}
