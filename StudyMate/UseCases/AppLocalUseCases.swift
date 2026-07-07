import Foundation

@MainActor
struct AppLocalUseCases {
    let appLog: AppLogUseCase
    let storedBackendIdentity: StoredBackendIdentityUseCase
    let communityProfileCache: CommunityProfileCacheUseCase
    let communitySession: CommunitySessionUseCase
    let onboardingState: OnboardingStateUseCase
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
