import XCTest
@testable import StudyMate

final class ArchitecturePolicyTests: XCTestCase {
    func testViewModelsDoNotReadBackendErrorPresentationExtensionsDirectly() throws {
        let root = try repositoryRoot()
        let viewModels = root.appendingPathComponent("StudyMate/ViewModels", isDirectory: true)
        let forbiddenPatterns = [
            "RemotePushBackendError",
            "backendError.requiresLogin",
            "backendError.isPageAccessDenied",
            "backendError.requiresEmailVerification",
            "backendError.shouldShowPopup",
            "backendError.shouldShowInlineError",
            "backendError.userFacingMessage(",
            "backendError.presentation(",
        ]

        let violations = try swiftFiles(in: viewModels).flatMap { file -> [String] in
            let content = try String(contentsOf: file, encoding: .utf8)
            return forbiddenPatterns
                .filter { content.contains($0) }
                .map { "\(file.lastPathComponent): \($0)" }
        }

        XCTAssertTrue(violations.isEmpty, "ViewModels must use AppErrorHandlingPolicy instead of RemotePushBackendError UI extensions: \(violations)")
    }

    func testRemotePushBackendErrorDoesNotExposeUIPresentationExtensions() throws {
        let root = try repositoryRoot()
        let policyFile = root.appendingPathComponent("StudyMate/Core/ErrorHandling/BackendErrorPresentationPolicy.swift")
        let content = try String(contentsOf: policyFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("extension RemotePushBackendError"),
            "Backend error UI decisions must stay behind AppErrorHandlingPolicy/BackendErrorPresentationPolicy, not RemotePushBackendError extensions."
        )
    }

    func testAppStateRoutesBackendErrorPresentationThroughUseCase() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "AppErrorHandlingPolicy.",
            "BackendErrorPresentationPolicy.",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must depend on AppErrorHandlingUseCase for common backend error handling instead of static policies directly: \(violations)"
        )
    }

    func testAppStateDoesNotCallBackendIdentityTransportDirectly() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "remotePushBackendClient.bootstrapAccessToken",
            "remotePushBackendClient.registerDevice",
            "remotePushBackendClient.updatePushToken",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use BackendIdentityUseCase for backend identity transport calls: \(violations)"
        )
    }

    func testAppStateDoesNotOwnBackendTransportComposition() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "private var remotePushBackendClient",
            "usesConfigurableRemotePushBackendClient",
            "makeRemotePushBackendClient(",
            "AppUseCases(backendClient:",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must delegate backend client composition to an app-use-case provider: \(violations)"
        )
    }

    func testAppStateDoesNotInstantiateOAuthServicesDirectly() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "GoogleOAuthService(",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use an auth use case instead of constructing OAuth services directly: \(violations)"
        )
    }

    func testAppStateDoesNotInstantiateCloudSyncInfrastructureDirectly() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "CloudSyncService.canUseCloudKitContainer",
            "cloudSyncService = CloudSyncService()",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use a cloud-sync provider boundary instead of constructing CloudKit infrastructure directly: \(violations)"
        )
    }

    func testAppStateDoesNotCallPlatformEffectsDirectly() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "UIApplication.shared",
            "UNUserNotificationCenter.current()",
            "NSWorkspace.shared",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use a platform effects provider for app lifecycle effects instead of calling platform APIs directly: \(violations)"
        )
    }

    func testAppStateUsesClockProviderForCurrentTime() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("Date()"),
            "AppState must use AppClockProviding for current time instead of constructing Date() directly."
        )
    }

    func testAppStateUsesIdentifierProviderForGeneratedIdentifiers() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("UUID()"),
            "AppState must use AppIdentifierProviding for generated identifiers instead of constructing UUID values directly."
        )
    }

    func testAppStateUsesTimeZoneProviderForRuntimeTimeZone() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("TimeZone.current"),
            "AppState must use AppTimeZoneProviding for runtime timezone values instead of reading TimeZone.current directly."
        )
    }

    func testAppStateDoesNotOwnApplicationUninstallInfrastructure() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "Bundle.main.bundleURL",
            "FileManager.default.temporaryDirectory",
            "Process()",
            "launchUninstaller(",
            "makeUninstallScript(",
            "shellEscaped(",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must delegate platform uninstall infrastructure to AppPlatformEffectsProvider: \(violations)"
        )
    }

    func testAppStateDoesNotSubscribeToNotificationCenterDirectly() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "NotificationCenter.default.publisher",
            "APITrafficNotification.userInfoKey",
            "BackendAuthorizationNotification.didReceiveUnauthorized",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must consume app event streams through AppNotificationEventProvider instead of subscribing to NotificationCenter directly: \(violations)"
        )
    }

    func testAppStateDoesNotReadClipboardPlatformAPIsDirectly() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "UIPasteboard.general",
            "NSPasteboard.general",
            "NSPasteboard.PasteboardType",
            "NSPasteboardReading",
            "NSPasteboardItem",
            "UTType.",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use a clipboard provider boundary instead of reading pasteboard platform APIs directly: \(violations)"
        )
    }

    func testAppStateUsesAppLogRepositoryForPersistedLogs() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "settingsStore.loadAppLogs",
            "settingsStore.appendAppLog",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use AppLogRepository for persisted logs instead of SettingsStore directly: \(violations)"
        )
    }

    func testAppStateRoutesAppLogsThroughUseCase() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "appLogRepository.loadAppLogs",
            "appLogRepository.appendAppLog",
            "appLogRepository.clearAppLogs",
            "resolvedAppLogRepository.loadAppLogs",
            "resolvedAppLogRepository.appendAppLog",
            "resolvedAppLogRepository.clearAppLogs",
            "private let appLogRepository",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use AppLogUseCase for persisted logs instead of the repository directly: \(violations)"
        )
    }

    func testAppStateUsesRemotePushRegistrationRepositoryForStoredBackendIdentity() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "settingsStore.loadRemotePushRegistration",
            "settingsStore.saveRemotePushRegistration",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use RemotePushRegistrationRepository for stored backend identity instead of SettingsStore directly: \(violations)"
        )
    }

    func testAppStateRoutesStoredBackendIdentityThroughUseCase() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "remotePushRegistrationRepository.loadRemotePushRegistration",
            "remotePushRegistrationRepository.saveRemotePushRegistration",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use StoredBackendIdentityUseCase for stored backend identity reads/writes instead of the repository directly: \(violations)"
        )
    }

    func testAppStateUsesCommunityProfileCacheRepositoryForStoredProfileState() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "settingsStore.loadProfileAvatarSymbolName",
            "settingsStore.saveProfileAvatarSymbolName",
            "settingsStore.loadProfileAvatarImageData",
            "settingsStore.saveProfileAvatarImageData",
            "settingsStore.loadProfileAvatarColorSeed",
            "settingsStore.saveProfileAvatarColorSeed",
            "settingsStore.loadCommunityProfileDisplayName",
            "settingsStore.saveCommunityProfileDisplayName",
            "settingsStore.loadCommunityProfileID",
            "settingsStore.saveCommunityProfileID",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use CommunityProfileCacheRepository for stored profile state instead of SettingsStore directly: \(violations)"
        )
    }

    func testAppStateRoutesCommunityProfileCacheThroughUseCase() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "communityProfileCacheRepository.loadProfileAvatarSymbolName",
            "communityProfileCacheRepository.saveProfileAvatarSymbolName",
            "communityProfileCacheRepository.loadProfileAvatarImageData",
            "communityProfileCacheRepository.saveProfileAvatarImageData",
            "communityProfileCacheRepository.loadProfileAvatarColorSeed",
            "communityProfileCacheRepository.saveProfileAvatarColorSeed",
            "communityProfileCacheRepository.loadCommunityProfileDisplayName",
            "communityProfileCacheRepository.saveCommunityProfileDisplayName",
            "communityProfileCacheRepository.loadCommunityProfileID",
            "communityProfileCacheRepository.saveCommunityProfileID",
            "resolvedCommunityProfileCacheRepository.load",
            "resolvedCommunityProfileCacheRepository.save",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use CommunityProfileCacheUseCase for cached profile identity and avatar state instead of the repository directly: \(violations)"
        )
    }

    func testAppStateUsesCommunitySessionRepositoryForStoredSignInState() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "settingsStore.loadIsCommunitySignedIn",
            "settingsStore.saveIsCommunitySignedIn",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use CommunitySessionRepository for stored community sign-in state instead of SettingsStore directly: \(violations)"
        )
    }

    func testAppStateRoutesCommunitySessionThroughUseCase() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "communitySessionRepository.loadIsCommunitySignedIn",
            "communitySessionRepository.saveIsCommunitySignedIn",
            "resolvedCommunitySessionRepository.loadIsCommunitySignedIn",
            "resolvedCommunitySessionRepository.saveIsCommunitySignedIn",
            "private let communitySessionRepository",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use CommunitySessionUseCase for cached community sign-in state instead of the repository directly: \(violations)"
        )
    }

    func testAppStateUsesOnboardingStateRepositoryForStoredOnboardingState() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "settingsStore.loadHasCompletedOnboarding",
            "settingsStore.saveHasCompletedOnboarding",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use OnboardingStateRepository for stored onboarding state instead of SettingsStore directly: \(violations)"
        )
    }

    func testAppStateRoutesOnboardingStateThroughUseCase() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "onboardingStateRepository.loadHasCompletedOnboarding",
            "onboardingStateRepository.saveHasCompletedOnboarding",
            "resolvedOnboardingStateRepository.loadHasCompletedOnboarding",
            "resolvedOnboardingStateRepository.saveHasCompletedOnboarding",
            "private let onboardingStateRepository",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use OnboardingStateUseCase for onboarding completion state instead of the repository directly: \(violations)"
        )
    }

    func testAppStateUsesDeveloperSettingsRepositoryForStoredDebugSettings() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "settingsStore.loadIsDebuggingEnabled",
            "settingsStore.saveIsDebuggingEnabled",
            "settingsStore.loadDebugBackendBaseURL",
            "settingsStore.saveDebugBackendBaseURL",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use DeveloperSettingsRepository for stored debug settings instead of SettingsStore directly: \(violations)"
        )
    }

    func testAppStateRoutesDeveloperSettingsThroughUseCase() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "developerSettingsRepository.loadDeveloperSettings",
            "developerSettingsRepository.saveDebugBackendBaseURL",
            "developerSettingsRepository.saveIsDebuggingEnabled",
            "resolvedDeveloperSettingsRepository.loadDeveloperSettings",
            "resolvedDeveloperSettingsRepository.saveDebugBackendBaseURL",
            "resolvedDeveloperSettingsRepository.saveIsDebuggingEnabled",
            "private let developerSettingsRepository",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use DeveloperSettingsUseCase for developer/debug settings instead of the repository directly: \(violations)"
        )
    }

    func testAppStateUsesCurrentStudySessionRepositoryForStoredSessionState() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "settingsStore.loadQuestion()",
            "settingsStore.saveQuestion(",
            "settingsStore.loadLastAnswer()",
            "settingsStore.saveLastAnswer(",
            "settingsStore.loadGradingResult()",
            "settingsStore.saveGradingResult(",
            "settingsStore.loadIsRunning()",
            "settingsStore.saveIsRunning(",
            "settingsStore.saveExplicitIsRunning(",
            "settingsStore.hasExplicitRunningPreference",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use CurrentStudySessionRepository for stored study session state instead of SettingsStore directly: \(violations)"
        )
    }

    func testAppStateRoutesCurrentStudySessionThroughUseCase() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "currentStudySessionRepository.loadCurrentStudySession",
            "currentStudySessionRepository.saveQuestion",
            "currentStudySessionRepository.saveLastAnswer",
            "currentStudySessionRepository.saveGradingResult",
            "currentStudySessionRepository.saveIsRunning",
            "currentStudySessionRepository.saveExplicitIsRunning",
            "currentStudySessionRepository.hasExplicitRunningPreference",
            "resolvedCurrentStudySessionRepository.loadCurrentStudySession",
            "resolvedCurrentStudySessionRepository.saveQuestion",
            "resolvedCurrentStudySessionRepository.saveLastAnswer",
            "resolvedCurrentStudySessionRepository.saveGradingResult",
            "resolvedCurrentStudySessionRepository.saveIsRunning",
            "resolvedCurrentStudySessionRepository.saveExplicitIsRunning",
            "resolvedCurrentStudySessionRepository.hasExplicitRunningPreference",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use CurrentStudySessionUseCase for current question, answer, grading, and running state instead of the repository directly: \(violations)"
        )
    }

    func testAppStateUsesLocalStudySettingsRepositoryForStoredSettingsState() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "settingsStore.loadSettings()",
            "settingsStore.saveSettings(",
            "settingsStore.loadAPIKey()",
            "settingsStore.saveAPIKey(",
            "settingsStore.loadOpenAIAPIKeyUpdatedAt()",
            "settingsStore.saveOpenAIAPIKeyUpdatedAt(",
            "settingsStore.loadLocalSettingsMutationAt()",
            "settingsStore.saveLocalSettingsMutationAt(",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use LocalStudySettingsRepository for stored settings and API key state instead of SettingsStore directly: \(violations)"
        )
    }

    func testAppStateRoutesLocalStudySettingsThroughUseCase() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "localStudySettingsRepository.loadLocalStudySettings",
            "localStudySettingsRepository.saveSettings",
            "localStudySettingsRepository.saveAPIKey",
            "localStudySettingsRepository.saveOpenAIAPIKeyUpdatedAt",
            "localStudySettingsRepository.saveLocalSettingsMutationAt",
            "resolvedLocalStudySettingsRepository.loadLocalStudySettings",
            "resolvedLocalStudySettingsRepository.saveSettings",
            "resolvedLocalStudySettingsRepository.saveAPIKey",
            "resolvedLocalStudySettingsRepository.saveOpenAIAPIKeyUpdatedAt",
            "resolvedLocalStudySettingsRepository.saveLocalSettingsMutationAt",
            "private let localStudySettingsRepository",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use LocalStudySettingsUseCase for stored settings and API key state instead of the repository directly: \(violations)"
        )
    }

    func testAppStateUsesCloudSyncStateRepositoryForStoredCloudSyncState() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "settingsStore.loadIsCloudSyncEnabled()",
            "settingsStore.saveIsCloudSyncEnabled(",
            "settingsStore.loadCloudSyncStateUpdatedAt()",
            "settingsStore.saveCloudSyncStateUpdatedAt(",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use CloudSyncStateRepository for stored cloud sync state instead of SettingsStore directly: \(violations)"
        )
    }

    func testAppStateRoutesCloudSyncStateThroughUseCase() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "cloudSyncStateRepository.loadCloudSyncState",
            "cloudSyncStateRepository.saveIsCloudSyncEnabled",
            "cloudSyncStateRepository.saveCloudSyncStateUpdatedAt",
            "resolvedCloudSyncStateRepository.loadCloudSyncState",
            "resolvedCloudSyncStateRepository.saveIsCloudSyncEnabled",
            "resolvedCloudSyncStateRepository.saveCloudSyncStateUpdatedAt",
            "private let cloudSyncStateRepository",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use CloudSyncStateUseCase for stored cloud-sync state instead of the repository directly: \(violations)"
        )
    }

    func testAppStateUsesLocalStudyRecordRepositoryForStoredRecordState() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "settingsStore.loadStudyRecords(",
            "settingsStore.replaceBackendStudyRecords(",
            "settingsStore.replaceStudyRecords(",
            "settingsStore.updateStudyRecordAnswer(",
            "settingsStore.deleteStudyRecord(",
            "settingsStore.saveStudyRecord(",
            "settingsStore.appendStudyRecord(",
            "settingsStore.clearStudyRecords(",
            "settingsStore.loadAnswerDraft(",
            "settingsStore.saveAnswerDraft(",
            "settingsStore.deleteAnswerDraft(",
            "settingsStore.appendQuestionToHistory(",
            "settingsStore.loadQuestionHistory(",
            "settingsStore.saveQuestionHistory(",
            "settingsStore.loadDeletedStudyRecordMarkers(",
            "settingsStore.saveDeletedStudyRecordMarkers(",
            "settingsStore.loadStudyRecordsClearedAt(",
            "settingsStore.saveStudyRecordsClearedAt(",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use LocalStudyRecordRepository for stored study records, drafts, history, and delete markers instead of SettingsStore directly: \(violations)"
        )
    }

    func testAppStateRoutesLocalStudyRecordsThroughUseCase() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "localStudyRecordRepository.loadStudyRecords",
            "localStudyRecordRepository.appendStudyRecord",
            "localStudyRecordRepository.updateStudyRecordAnswer",
            "localStudyRecordRepository.saveStudyRecord",
            "localStudyRecordRepository.deleteStudyRecord",
            "localStudyRecordRepository.clearStudyRecords",
            "localStudyRecordRepository.replaceStudyRecords",
            "localStudyRecordRepository.replaceBackendStudyRecords",
            "localStudyRecordRepository.loadAnswerDraft",
            "localStudyRecordRepository.saveAnswerDraft",
            "localStudyRecordRepository.deleteAnswerDraft",
            "localStudyRecordRepository.loadQuestionHistory",
            "localStudyRecordRepository.appendQuestionToHistory",
            "localStudyRecordRepository.saveQuestionHistory",
            "localStudyRecordRepository.loadDeletedStudyRecordMarkers",
            "localStudyRecordRepository.saveDeletedStudyRecordMarkers",
            "localStudyRecordRepository.loadStudyRecordsClearedAt",
            "localStudyRecordRepository.saveStudyRecordsClearedAt",
            "resolvedLocalStudyRecordRepository.loadStudyRecords",
            "resolvedLocalStudyRecordRepository.appendStudyRecord",
            "resolvedLocalStudyRecordRepository.updateStudyRecordAnswer",
            "resolvedLocalStudyRecordRepository.saveStudyRecord",
            "resolvedLocalStudyRecordRepository.deleteStudyRecord",
            "resolvedLocalStudyRecordRepository.clearStudyRecords",
            "resolvedLocalStudyRecordRepository.replaceStudyRecords",
            "resolvedLocalStudyRecordRepository.replaceBackendStudyRecords",
            "private let localStudyRecordRepository",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must use LocalStudyRecordUseCase for stored records, drafts, history, and delete markers instead of the repository directly: \(violations)"
        )
    }

    func testViewsDoNotDependOnSettingsStore() throws {
        let root = try repositoryRoot()
        let views = root.appendingPathComponent("StudyMate/Views", isDirectory: true)
        let violations = try swiftFiles(in: views).compactMap { file -> String? in
            let content = try String(contentsOf: file, encoding: .utf8)
            return content.contains("SettingsStore") ? file.lastPathComponent : nil
        }

        XCTAssertTrue(
            violations.isEmpty,
            "Views must render state and use Core policies instead of depending on SettingsStore: \(violations)"
        )
    }

    func testHomePullToRefreshDoesNotHoldSystemRefreshControlForNetworkLoad() throws {
        let root = try repositoryRoot()
        let file = root.appendingPathComponent("StudyMate/Views/MobileRootView.swift")
        let content = try String(contentsOf: file, encoding: .utf8)

        XCTAssertFalse(
            content.contains("await homeRefreshTask?.value"),
            "Home pull-to-refresh must launch a background refresh and return promptly so the system refresh control does not pin the list while network requests run."
        )
    }

    func testHomeCommunityRefreshIndicatorStaysInsideEmptyContentSlot() throws {
        let root = try repositoryRoot()
        let file = root.appendingPathComponent("StudyMate/Views/MobileRootView.swift")
        let content = try String(contentsOf: file, encoding: .utf8)

        XCTAssertTrue(
            content.contains("if appState.communityQuestions.isEmpty {\n                if isRefreshingCommunityContent {\n                    MobileHomeRefreshIndicator()"),
            "When public questions are empty, the refresh indicator should render in the public-question content slot instead of shifting the fixed title or tab area."
        )
    }

    func testGoogleSignInUseCaseDependsOnAuthRepositoryBoundary() throws {
        let root = try repositoryRoot()
        let useCaseFile = root.appendingPathComponent("StudyMate/UseCases/Auth/GoogleSignInUseCase.swift")
        let content = try String(contentsOf: useCaseFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("GoogleOAuthService"),
            "GoogleSignInUseCase must depend on an auth repository boundary instead of the OAuth provider service."
        )
        XCTAssertTrue(
            content.contains("GoogleSignInRepository"),
            "GoogleSignInUseCase should keep OAuth provider details behind a repository boundary."
        )
    }

    func testCommunityUseCaseDependsOnRepositoryBoundary() throws {
        let root = try repositoryRoot()
        let useCaseFile = root.appendingPathComponent("StudyMate/UseCases/Community/CommunityUseCase.swift")
        let content = try String(contentsOf: useCaseFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("RemotePushBackendClientProtocol"),
            "CommunityUseCase must depend on CommunityRepository instead of the backend transport service."
        )
        XCTAssertTrue(
            content.contains("CommunityRepository"),
            "CommunityUseCase should keep backend transport behind a repository boundary."
        )
    }

    func testStudyRoomUseCaseDependsOnRepositoryBoundary() throws {
        let root = try repositoryRoot()
        let useCaseFile = root.appendingPathComponent("StudyMate/UseCases/StudyRoom/StudyRoomUseCase.swift")
        let content = try String(contentsOf: useCaseFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("RemotePushBackendClientProtocol"),
            "StudyRoomUseCase must depend on StudyRoomRepository instead of the backend transport service."
        )
        XCTAssertTrue(
            content.contains("StudyRoomRepository"),
            "StudyRoomUseCase should keep backend transport behind a repository boundary."
        )
    }

    func testRecordsUseCaseDependsOnRepositoryBoundary() throws {
        let root = try repositoryRoot()
        let useCaseFile = root.appendingPathComponent("StudyMate/UseCases/Records/RecordsUseCase.swift")
        let content = try String(contentsOf: useCaseFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("RemotePushBackendClientProtocol"),
            "RecordsUseCase must depend on RecordsRepository instead of the backend transport service."
        )
        XCTAssertTrue(
            content.contains("RecordsRepository"),
            "RecordsUseCase should keep backend transport behind a repository boundary."
        )
    }

    func testStatsUseCaseDependsOnRepositoryBoundary() throws {
        let root = try repositoryRoot()
        let useCaseFile = root.appendingPathComponent("StudyMate/UseCases/Stats/StatsUseCase.swift")
        let content = try String(contentsOf: useCaseFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("RemotePushBackendClientProtocol"),
            "StatsUseCase must depend on StatsRepository instead of the backend transport service."
        )
        XCTAssertTrue(
            content.contains("StatsRepository"),
            "StatsUseCase should keep backend transport behind a repository boundary."
        )
    }

    func testNotificationsUseCaseDependsOnRepositoryBoundary() throws {
        let root = try repositoryRoot()
        let useCaseFile = root.appendingPathComponent("StudyMate/UseCases/Notifications/NotificationsUseCase.swift")
        let content = try String(contentsOf: useCaseFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("RemotePushBackendClientProtocol"),
            "NotificationsUseCase must depend on NotificationsRepository instead of the backend transport service."
        )
        XCTAssertTrue(
            content.contains("NotificationsRepository"),
            "NotificationsUseCase should keep backend transport behind a repository boundary."
        )
    }

    func testSettingsUseCaseDependsOnRepositoryBoundary() throws {
        let root = try repositoryRoot()
        let useCaseFile = root.appendingPathComponent("StudyMate/UseCases/Settings/SettingsUseCase.swift")
        let content = try String(contentsOf: useCaseFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("RemotePushBackendClientProtocol"),
            "SettingsUseCase must depend on SettingsRepository instead of the backend transport service."
        )
        XCTAssertTrue(
            content.contains("SettingsRepository"),
            "SettingsUseCase should keep backend transport behind a repository boundary."
        )
    }

    func testBackendIdentityUseCaseDependsOnRepositoryBoundary() throws {
        let root = try repositoryRoot()
        let useCaseFile = root.appendingPathComponent("StudyMate/UseCases/Identity/BackendIdentityUseCase.swift")
        let content = try String(contentsOf: useCaseFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("RemotePushBackendClientProtocol"),
            "BackendIdentityUseCase must depend on IdentityRepository instead of the backend transport service."
        )
        XCTAssertTrue(
            content.contains("IdentityRepository"),
            "BackendIdentityUseCase should keep backend transport behind a repository boundary."
        )
    }

    func testPageAccessUseCaseDependsOnRepositoryBoundary() throws {
        let root = try repositoryRoot()
        let useCaseFile = root.appendingPathComponent("StudyMate/UseCases/PageAccess/RefreshPageAccessUseCase.swift")
        let content = try String(contentsOf: useCaseFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("RemotePushBackendClientProtocol"),
            "RefreshPageAccessUseCase must depend on PageAccessRepository instead of the backend transport service."
        )
        XCTAssertTrue(
            content.contains("PageAccessRepository"),
            "RefreshPageAccessUseCase should keep backend transport behind a repository boundary."
        )
    }

    func testAppStateDoesNotAssignRawLocalizedDescriptionToPrimaryErrorMessage() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("errorMessage = error.localizedDescription"),
            "AppState must route raw errors through AppErrorHandlingPolicy before writing user-visible errorMessage."
        )
    }

    func testAuthRangeNumericBackendErrorsRequireLoginWithoutPopup() {
        let apiError = BackendAPIError(
            code: "101",
            numericCode: 101,
            message: "다시 로그인해 주세요.",
            status: 401
        )
        let error = RemotePushBackendError.httpStatus(401, "", apiError)

        let resolution = AppErrorHandlingPolicy.resolve(error, fallback: "fallback")

        XCTAssertNil(resolution.featureMessage)
        XCTAssertFalse(resolution.shouldShowPopup)
        XCTAssertTrue(resolution.requiresLogin)
        XCTAssertTrue(resolution.isPageAccessDenied)
        XCTAssertTrue(resolution.shouldResetBackendIdentity)
        XCTAssertTrue(resolution.shouldClearFeatureMessage)
    }

    func testAuthRangeNumericBackendErrorsRequireLoginWithoutPopupEvenWhenStatusIsForbidden() {
        let apiError = BackendAPIError(
            code: "101",
            numericCode: 101,
            message: "다시 로그인해 주세요.",
            status: 403
        )
        let error = RemotePushBackendError.httpStatus(403, "", apiError)

        let resolution = AppErrorHandlingPolicy.resolve(error, fallback: "fallback")

        XCTAssertNil(resolution.featureMessage)
        XCTAssertFalse(resolution.shouldShowPopup)
        XCTAssertTrue(resolution.requiresLogin)
        XCTAssertTrue(resolution.isPageAccessDenied)
        XCTAssertTrue(resolution.shouldResetBackendIdentity)
        XCTAssertTrue(resolution.shouldClearFeatureMessage)
    }

    func testEmptyBackendStudyPageDecodesAsEmptyList() throws {
        let data = #"{}"#.data(using: .utf8)!

        let page = try JSONDecoder().decode(BackendStudyPage.self, from: data)

        XCTAssertEqual(page.studies, [])
        XCTAssertEqual(page.totalCount, 0)
        XCTAssertEqual(page.limit, 0)
        XCTAssertEqual(page.offset, 0)
    }

    func testValidationBackendErrorsUseServerMessageInline() {
        let apiError = BackendAPIError(
            code: "RECORD_NOT_FOUND",
            message: "기록을 찾을 수 없습니다.",
            status: 404
        )
        let error = RemotePushBackendError.httpStatus(404, "", apiError)

        let resolution = AppErrorHandlingPolicy.resolve(error, fallback: "fallback")

        XCTAssertEqual(resolution.featureMessage, "기록을 찾을 수 없습니다.")
        XCTAssertFalse(resolution.shouldShowPopup)
        XCTAssertFalse(resolution.requiresLogin)
        XCTAssertFalse(resolution.isPageAccessDenied)
        XCTAssertFalse(resolution.shouldResetBackendIdentity)
        XCTAssertFalse(resolution.shouldClearFeatureMessage)
    }

    func testEmailVerificationRequirementStaysInVerificationFlow() {
        let apiError = BackendAPIError(
            code: "AUTH_GOOGLE_REQUIRED",
            message: "Verification code is required.",
            status: 403
        )
        let error = RemotePushBackendError.httpStatus(403, "", apiError)

        let resolution = AppErrorHandlingPolicy.resolve(error, fallback: "fallback")

        XCTAssertEqual(resolution.featureMessage, "Verification code is required.")
        XCTAssertFalse(resolution.shouldShowPopup)
        XCTAssertFalse(resolution.requiresLogin)
        XCTAssertFalse(resolution.isPageAccessDenied)
        XCTAssertTrue(resolution.requiresEmailVerification)
        XCTAssertFalse(resolution.shouldResetBackendIdentity)
        XCTAssertFalse(resolution.shouldClearFeatureMessage)
    }

    func testCancellationClearsFeatureMessageWithoutUserFacingNoise() {
        let resolution = AppErrorHandlingPolicy.resolve(CancellationError(), fallback: "fallback")

        XCTAssertNil(resolution.featureMessage)
        XCTAssertFalse(resolution.shouldShowPopup)
        XCTAssertFalse(resolution.requiresLogin)
        XCTAssertFalse(resolution.shouldResetBackendIdentity)
        XCTAssertTrue(resolution.shouldClearFeatureMessage)
    }

    func testDecodingErrorsUseFriendlyInlineMessageWithoutPopup() {
        let context = DecodingError.Context(
            codingPath: [],
            debugDescription: "No value associated with key CodingKeys(stringValue: \"questions\", intValue: nil)."
        )
        let error = DecodingError.keyNotFound(TestCodingKey(stringValue: "questions"), context)

        let resolution = AppErrorHandlingPolicy.resolve(error, fallback: "fallback")

        XCTAssertEqual(resolution.featureMessage, "응답 데이터를 읽을 수 없습니다. 잠시 후 다시 시도하세요.")
        XCTAssertFalse(resolution.shouldShowPopup)
        XCTAssertFalse(resolution.requiresLogin)
        XCTAssertFalse(resolution.isPageAccessDenied)
        XCTAssertFalse(resolution.shouldResetBackendIdentity)
        XCTAssertFalse(resolution.shouldClearFeatureMessage)
    }

    private func repositoryRoot() throws -> URL {
        var current = URL(fileURLWithPath: #filePath)
        while current.path != "/" {
            let project = current.appendingPathComponent("StudyMate.xcodeproj")
            if FileManager.default.fileExists(atPath: project.path) {
                return current
            }
            current.deleteLastPathComponent()
        }

        throw XCTSkip("Repository root could not be resolved from \(#filePath)")
    }

    private func swiftFiles(in directory: URL) throws -> [URL] {
        guard let enumerator = FileManager.default.enumerator(
            at: directory,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        ) else {
            return []
        }

        return try enumerator.compactMap { item in
            guard let url = item as? URL, url.pathExtension == "swift" else {
                return nil
            }

            let values = try url.resourceValues(forKeys: [.isRegularFileKey])
            return values.isRegularFile == true ? url : nil
        }
    }
}

private struct TestCodingKey: CodingKey {
    var stringValue: String
    var intValue: Int?

    init(stringValue: String) {
        self.stringValue = stringValue
    }

    init(intValue: Int) {
        stringValue = "\(intValue)"
        self.intValue = intValue
    }
}
