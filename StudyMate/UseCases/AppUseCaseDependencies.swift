import Foundation

@MainActor
struct AppUseCaseDependencies {
    let localUseCases: AppLocalUseCases
    let appUseCasesProvider: AppUseCasesProvider

    init(
        localUseCases: AppLocalUseCases,
        appUseCasesProvider: AppUseCasesProvider
    ) {
        self.localUseCases = localUseCases
        self.appUseCasesProvider = appUseCasesProvider
    }

    static func live(
        settingsStore: SettingsStore,
        remotePushBackendClient: RemotePushBackendClientProtocol?,
        appLogRepository: AppLogRepository?,
        appLogUseCase: AppLogUseCase?,
        remotePushRegistrationRepository: RemotePushRegistrationRepository?,
        storedBackendIdentityUseCase: StoredBackendIdentityUseCase?,
        communityProfileCacheRepository: CommunityProfileCacheRepository?,
        communityProfileCacheUseCase: CommunityProfileCacheUseCase?,
        communitySessionRepository: CommunitySessionRepository?,
        communitySessionUseCase: CommunitySessionUseCase?,
        onboardingStateRepository: OnboardingStateRepository?,
        onboardingStateUseCase: OnboardingStateUseCase?,
        pendingReferralRepository: PendingReferralRepository?,
        pendingReferralUseCase: PendingReferralUseCase?,
        developerSettingsRepository: DeveloperSettingsRepository?,
        developerSettingsUseCase: DeveloperSettingsUseCase?,
        currentStudySessionRepository: CurrentStudySessionRepository?,
        currentStudySessionUseCase: CurrentStudySessionUseCase?,
        localStudySettingsRepository: LocalStudySettingsRepository?,
        localStudySettingsUseCase: LocalStudySettingsUseCase?,
        cloudSyncStateRepository: CloudSyncStateRepository?,
        cloudSyncStateUseCase: CloudSyncStateUseCase?,
        localStudyRecordRepository: LocalStudyRecordRepository?,
        localStudyRecordUseCase: LocalStudyRecordUseCase?,
        appErrorHandlingUseCase: AppErrorHandlingUseCase
    ) -> AppUseCaseDependencies {
        AppUseCaseDependencies(
            localUseCases: AppLocalUseCases(
                settingsStore: settingsStore,
                appLogRepository: appLogRepository,
                appLogUseCase: appLogUseCase,
                remotePushRegistrationRepository: remotePushRegistrationRepository,
                storedBackendIdentityUseCase: storedBackendIdentityUseCase,
                communityProfileCacheRepository: communityProfileCacheRepository,
                communityProfileCacheUseCase: communityProfileCacheUseCase,
                communitySessionRepository: communitySessionRepository,
                communitySessionUseCase: communitySessionUseCase,
                onboardingStateRepository: onboardingStateRepository,
                onboardingStateUseCase: onboardingStateUseCase,
                pendingReferralRepository: pendingReferralRepository,
                pendingReferralUseCase: pendingReferralUseCase,
                developerSettingsRepository: developerSettingsRepository,
                developerSettingsUseCase: developerSettingsUseCase,
                currentStudySessionRepository: currentStudySessionRepository,
                currentStudySessionUseCase: currentStudySessionUseCase,
                localStudySettingsRepository: localStudySettingsRepository,
                localStudySettingsUseCase: localStudySettingsUseCase,
                cloudSyncStateRepository: cloudSyncStateRepository,
                cloudSyncStateUseCase: cloudSyncStateUseCase,
                localStudyRecordRepository: localStudyRecordRepository,
                localStudyRecordUseCase: localStudyRecordUseCase,
                appErrorHandlingUseCase: appErrorHandlingUseCase
            ),
            appUseCasesProvider: AppUseCasesProvider(backendClient: remotePushBackendClient)
        )
    }
}
