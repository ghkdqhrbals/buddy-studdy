import XCTest
@testable import StudyMate

final class ArchitecturePolicyTests: XCTestCase {
    func testAnalyticsConfigurationRequiresMatchingFirebaseApp() {
        let configured: [String: Any] = [
            "BUNDLE_ID": "io.github.ghkdqhrbals.StudyMate",
            "GOOGLE_APP_ID": "1:1234567890:ios:abcdef",
            "API_KEY": "configured-api-key",
            "IS_ANALYTICS_ENABLED": false
        ]

        XCTAssertTrue(
            AppAnalyticsConfiguration.isUsable(
                dictionary: configured,
                bundleIdentifier: "io.github.ghkdqhrbals.StudyMate"
            )
        )
        XCTAssertFalse(
            AppAnalyticsConfiguration.isUsable(
                dictionary: configured,
                bundleIdentifier: "io.github.ghkdqhrbals.Other"
            )
        )

        var placeholder = configured
        placeholder["API_KEY"] = "NOT_CONFIGURED"
        XCTAssertFalse(
            AppAnalyticsConfiguration.isUsable(
                dictionary: placeholder,
                bundleIdentifier: "io.github.ghkdqhrbals.StudyMate"
            )
        )
    }

    func testAnalyticsConfigurationRejectsRepositoryPlaceholder() throws {
        let root = try repositoryRoot()
        let configurationURL = root.appendingPathComponent("StudyMate/GoogleService-Info.plist")
        let dictionary = try XCTUnwrap(
            NSDictionary(contentsOf: configurationURL) as? [String: Any]
        )

        XCTAssertFalse(
            AppAnalyticsConfiguration.isUsable(
                dictionary: dictionary,
                bundleIdentifier: "io.github.ghkdqhrbals.StudyMate"
            )
        )
    }

    func testEmbeddedDeveloperCodeAcceptsOnlyConfiguredFourPartCode() {
        XCTAssertTrue(
            DeveloperPromotionCodeVerifier.isDeveloperCode("QAQA-QAQA-QAQA-QAQA")
        )
        XCTAssertFalse(
            DeveloperPromotionCodeVerifier.isDeveloperCode("NOPE-NOPE-NOPE-NOPE")
        )
        XCTAssertEqual(
            DeveloperPromotionCodeVerifier.formattedInput("qaqaqaqaqaqaqaqa"),
            "QAQA-QAQA-QAQA-QAQA"
        )
    }

    func testMaintenanceScreenProvidesHiddenDeveloperBypass() throws {
        let root = try repositoryRoot()
        let appFile = root.appendingPathComponent("StudyMate/StudyMateiOSApp.swift")
        let stateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let appContent = try String(contentsOf: appFile, encoding: .utf8)
        let stateContent = try String(contentsOf: stateFile, encoding: .utf8)

        XCTAssertTrue(appContent.contains("now.timeIntervalSince(startedAt) <= 2"))
        XCTAssertTrue(appContent.contains("guard hiddenTapCount >= 5"))
        XCTAssertTrue(appContent.contains("MaintenanceDeveloperAccessSheet()"))
        XCTAssertTrue(appContent.contains("await appState.bypassMaintenanceForDeveloper()"))
        XCTAssertTrue(
            stateContent.contains(
                "isServiceUnderMaintenance && !isMaintenanceBypassedForDeveloper"
            )
        )
    }

    func testTestFlightKeepsDeveloperDebugPopupBehindPromotionCodeGate() throws {
        let root = try repositoryRoot()
        let appContent = try String(
            contentsOf: root.appendingPathComponent("StudyMate/StudyMateiOSApp.swift"),
            encoding: .utf8
        )
        let mobileRootContent = try String(
            contentsOf: root.appendingPathComponent("StudyMate/Views/MobileRootView.swift"),
            encoding: .utf8
        )
        let debugControlsContent = try String(
            contentsOf: root.appendingPathComponent("StudyMate/Debug/AppDebugControls.swift"),
            encoding: .utf8
        )

        XCTAssertTrue(appContent.contains("FloatingDebugLogOverlay()"))
        XCTAssertFalse(appContent.contains("#if DEBUG\nprivate enum DebugLogTab"))
        XCTAssertTrue(
            mobileRootContent.contains(
                "appState.requestDebugPanelIfEnabledOrEnableOnDemand()"
            )
        )
        XCTAssertFalse(debugControlsContent.contains("#if DEBUG"))
        XCTAssertTrue(debugControlsContent.hasPrefix("#if os(iOS)"))
    }

    func testBackendMaintenanceErrorDoesNotReplaceMonitoringServiceStatus() {
        let error = RemotePushBackendError.httpStatus(
            503,
            "",
            BackendAPIError(
                code: "SERVICE_UNDER_MAINTENANCE",
                numericCode: 903,
                message: "Maintenance in progress"
            )
        )

        let resolution = AppErrorHandlingUseCase().resolve(
            error,
            fallback: "Request failed"
        )

        XCTAssertNil(resolution.serviceAvailability)
    }

    func testTimeoutAndGenericServiceFailureDoNotResolveToMaintenance() {
        let timeoutResolution = AppErrorHandlingUseCase().resolve(
            URLError(.timedOut),
            fallback: "Request timed out"
        )
        let genericFailureResolution = AppErrorHandlingUseCase().resolve(
            RemotePushBackendError.httpStatus(
                503,
                "",
                BackendAPIError(
                    code: "INTERNAL_SERVER_ERROR",
                    numericCode: 900,
                    message: "Service unavailable"
                )
            ),
            fallback: "Request failed"
        )

        XCTAssertNil(timeoutResolution.serviceAvailability)
        XCTAssertNil(genericFailureResolution.serviceAvailability)
    }

    func testEveryAppStringProvidesJapaneseCopy() throws {
        let root = try repositoryRoot()
        let source = try String(
            contentsOf: root.appendingPathComponent("StudyMate/Models/StudyModels.swift"),
            encoding: .utf8
        )
        let expression = try NSRegularExpression(
            pattern: #"text\(\s*"(?:\\.|[^"])*"\s*,\s*"((?:\\.|[^"])*)"(?:\s*,\s*"((?:\\.|[^"])*)")?"#,
            options: [.dotMatchesLineSeparators]
        )
        let range = NSRange(source.startIndex..., in: source)
        var missing: [String] = []

        for match in expression.matches(in: source, range: range) {
            guard let englishRange = Range(match.range(at: 1), in: source) else {
                continue
            }
            let hasInlineJapanese = match.range(at: 2).location != NSNotFound
            if hasInlineJapanese {
                continue
            }
            let encodedEnglish = "\"\(source[englishRange])\""
            let english = try JSONDecoder().decode(String.self, from: Data(encodedEnglish.utf8))
            if !JapaneseAppStrings.hasTranslation(for: english) {
                missing.append(english)
            }
        }

        XCTAssertTrue(
            missing.isEmpty,
            "Every AppStrings entry must provide Japanese copy inline or in JapaneseAppStrings: \(missing)"
        )
    }

    func testMarkdownContentRendersAndFallsBackToPlainText() {
        let source = """
        **핵심**은 `WHERE` 절입니다.

        - 첫 번째 조건
        - 두 번째 조건
        """

        let rendered = MarkdownContent.attributedString(source)
        let plainText = MarkdownContent.plainText(source)

        XCTAssertFalse(rendered.runs.isEmpty)
        XCTAssertTrue(plainText.contains("핵심은 WHERE 절입니다."))
        XCTAssertTrue(plainText.contains("첫 번째 조건"))
        XCTAssertTrue(plainText.contains("두 번째 조건"))
        XCTAssertFalse(plainText.contains("**"))
        XCTAssertFalse(plainText.contains("`"))
        XCTAssertEqual(MarkdownContent.plainText("일반 문장입니다."), "일반 문장입니다.")
    }

    func testMarkdownContentBlocksUnsafeLinks() {
        let rendered = MarkdownContent.attributedString(
            "[안전](https://example.com) [차단](javascript:alert(1))"
        )
        let links = rendered.runs.compactMap(\.link)

        XCTAssertEqual(links.count, 1)
        XCTAssertEqual(links.first?.scheme, "https")
    }

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

    func testAppStateDoesNotReadBackendBaseURLConfigurationDirectly() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("BackendBaseURLConfiguration"),
            "AppState must use AppUseCasesProvider for backend URL normalization and display instead of reading BackendBaseURLConfiguration directly."
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

    func testAppStateDoesNotConstructDefaultRuntimeImplementationsDirectly() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "NotificationService()",
            "DefaultCloudSyncProvider()",
            "DefaultAppPlatformEffectsProvider()",
            "DefaultClipboardProvider()",
            "DefaultAppNotificationEventProvider()",
            "SystemAppClockProvider()",
            "UUIDAppIdentifierProvider()",
            "SystemAppTimeZoneProvider()",
            "TaskAppSleepProvider()",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must receive default runtime implementations from AppRuntimeDependencies instead of constructing them directly: \(violations)"
        )
    }

    func testAppStateDoesNotConstructUseCaseCompositionDirectly() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "AppLocalUseCases(",
            "AppUseCasesProvider(",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must receive composed use-case dependencies instead of constructing use-case composition directly: \(violations)"
        )
    }

    func testProtectedTabsDoNotUsePageAccessPreflight() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("refreshPageAccessThenOpen"),
            "Protected tab selection must not preflight backend page access before showing the destination."
        )
        XCTAssertFalse(
            content.contains("shouldRefreshPageAccessBeforeDenying"),
            "Protected tab access should be resolved from actual API errors, not a separate page-access branch."
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

    func testAppStateUsesSleepProviderForRuntimeDelays() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("Task.sleep"),
            "AppState must use AppSleepProviding for runtime delays instead of sleeping tasks directly."
        )
    }

    func testAppStateDoesNotOwnApplicationUninstallInfrastructure() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "Bundle.main.bundleURL",
            "FileManager.default.temporaryDirectory",
            "= Process()",
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

    func testAppStateDoesNotRetainSettingsStoreImplementation() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "private let settingsStore",
            "self.settingsStore",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must not retain SettingsStore directly after composing repositories/use cases: \(violations)"
        )
    }

    func testAppStateDoesNotComposeSettingsStoreRepositoriesDirectly() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)
        let forbiddenPatterns = [
            "SettingsStoreAppLogRepository(",
            "SettingsStoreRemotePushRegistrationRepository(",
            "SettingsStoreCommunityProfileCacheRepository(",
            "SettingsStoreCommunitySessionRepository(",
            "SettingsStoreOnboardingStateRepository(",
            "SettingsStoreDeveloperSettingsRepository(",
            "SettingsStoreCurrentStudySessionRepository(",
            "SettingsStoreLocalStudySettingsRepository(",
            "SettingsStoreCloudSyncStateRepository(",
            "SettingsStoreLocalStudyRecordRepository(",
        ]

        let violations = forbiddenPatterns.filter { content.contains($0) }

        XCTAssertTrue(
            violations.isEmpty,
            "AppState must delegate SettingsStore repository composition to a local use-case composition boundary: \(violations)"
        )
    }

    func testAppStateDoesNotReadSettingsStoreStorageConstantsDirectly() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("SettingsStore.maxDeletedStudyRecordMarkerCount"),
            "AppState must ask LocalStudyRecordUseCase to enforce study-record storage limits instead of reading SettingsStore constants directly."
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

    func testEveryStudyContextMenuCanOpenTheFullTree() throws {
        let root = try repositoryRoot()
        let file = root.appendingPathComponent("StudyMate/Views/MobileRootView.swift")
        let content = try String(contentsOf: file, encoding: .utf8)
        let fullTreeActionCount = content.components(
            separatedBy: "strings.viewFullStudyTree"
        ).count - 1

        XCTAssertGreaterThanOrEqual(
            fullTreeActionCount,
            2,
            "Both tree-backed and childless fallback study cards must expose View Full Tree so the user can add a first child topic."
        )
        XCTAssertFalse(
            content.contains("if !snapshot.children(of: snapshot.root.id).isEmpty"),
            "View Full Tree must not disappear when a root has no child topics."
        )
    }

    func testMyStudyListMenusKeepDeletionInsideEditors() throws {
        let root = try repositoryRoot()
        let file = root.appendingPathComponent("StudyMate/Views/MobileRootView.swift")
        let content = try String(contentsOf: file, encoding: .utf8)

        let topicActions = try XCTUnwrap(
            content.range(
                of: "private func topicActions(for room: BackendStudyRoom) -> some View {"
            )
        )
        let rootActions = try XCTUnwrap(
            content.range(
                of: "private var rootActions: some View {",
                range: topicActions.upperBound..<content.endIndex
            )
        )
        let navigationRow = try XCTUnwrap(
            content.range(
                of: "private func studyNavigationRow(",
                range: rootActions.upperBound..<content.endIndex
            )
        )
        let topicMenuSource = String(content[topicActions.lowerBound..<rootActions.lowerBound])
        let rootMenuSource = String(content[rootActions.lowerBound..<navigationRow.lowerBound])

        for menuSource in [topicMenuSource, rootMenuSource] {
            XCTAssertTrue(
                menuSource.contains("Label(strings.editStudyCategory, systemImage: \"pencil\")")
            )
            XCTAssertTrue(menuSource.contains("strings.viewFullStudyTree"))
            XCTAssertFalse(
                menuSource.contains("strings.deleteStudy"),
                "My Studies list menus should expose only Edit Study and View Full Tree."
            )
        }

        XCTAssertFalse(
            content.contains("@State private var deletionStudyCategory"),
            "The My Studies list should not stage direct deletion outside an editor."
        )
        XCTAssertTrue(
            content.contains("if onDelete != nil {\n                    Section {\n                        Button(role: .destructive)"),
            "Root-study deletion should remain available inside StudyCategoryEditorSheet."
        )
        XCTAssertTrue(
            content.contains("struct StudyTopicLevelSheet: View")
                && content.contains("Button(strings.deleteStudy, role: .destructive)"),
            "Child-topic deletion should remain available inside StudyTopicLevelSheet."
        )
    }

    func testSelectedStudyToolbarOffersOnlyEditAndTreeActions() throws {
        let root = try repositoryRoot()
        let file = root.appendingPathComponent("StudyMate/Views/StudyView.swift")
        let content = try String(contentsOf: file, encoding: .utf8)

        XCTAssertTrue(
            content.contains("toolbarNewQuestionButton(strings: strings)\n            studyOptionsMenu(strings: strings)"),
            "The selected study toolbar should keep New Question and place a separate More menu to its right."
        )
        XCTAssertTrue(
            content.contains("Label(strings.editStudyCategory, systemImage: \"pencil\")"),
            "The selected study More menu should expose study editing."
        )
        XCTAssertTrue(
            content.contains("selectedTreeRootID = appState.rootStudyRoom(for: room.id)?.id ?? room.id"),
            "View Full Tree should resolve a nested topic back to its containing root study."
        )
        XCTAssertTrue(
            content.contains("strings.viewFullStudyTree,"),
            "The selected study More menu should expose View Full Tree."
        )
        XCTAssertFalse(
            content.contains("@State private var deletionCandidate: BackendStudyRoom?"),
            "The selected study screen must not offer deletion directly from its More menu."
        )
        XCTAssertTrue(
            content.contains("StudyTopicLevelSheet(")
                && content.contains("onDelete: {\n                    deleteStudyRoom(room)"),
            "Topic deletion should remain available inside the study editor."
        )
    }

    func testChildTopicRecommendationsSupportOrderedBatchSelection() throws {
        let root = try repositoryRoot()
        let viewFile = root.appendingPathComponent("StudyMate/Views/MobileRootView.swift")
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let viewContent = try String(contentsOf: viewFile, encoding: .utf8)
        let appStateContent = try String(contentsOf: appStateFile, encoding: .utf8)

        XCTAssertTrue(
            viewContent.contains("@State private var selectedSuggestions = Set<String>()"),
            "Recommended child topics should keep a multi-selection set instead of one selected suggestion."
        )
        XCTAssertTrue(
            viewContent.contains("return suggestions.filter(selectedSuggestions.contains)"),
            "Selected recommendations should preserve the server-provided display order when submitted."
        )
        XCTAssertTrue(
            appStateContent.contains("func addChildStudyCategories("),
            "AppState should expose one batch-oriented child-topic action for the recommendation sheet."
        )
        XCTAssertTrue(
            appStateContent.contains("refreshAfterCreation: false"),
            "A recommendation batch should defer per-topic tree refreshes until all selected topics have been attempted."
        )
    }

    func testStudyGrowthDetailUsesOneAlwaysExpandedScoreTree() throws {
        let root = try repositoryRoot()
        let file = root.appendingPathComponent("StudyMate/Views/StatisticsView.swift")
        let content = try String(contentsOf: file, encoding: .utf8)
        let detailStart = try XCTUnwrap(
            content.range(of: "private struct StudyGrowthDetailView: View")
        )
        let detailEnd = try XCTUnwrap(
            content.range(
                of: "private struct StudyGrowthTreeItem",
                range: detailStart.upperBound..<content.endIndex
            )
        )
        let detail = String(content[detailStart.lowerBound..<detailEnd.lowerBound])

        XCTAssertTrue(
            detail.contains("StudyGrowthTreeCard("),
            "Study growth detail should present the combined root and individual descendants in one score tree."
        )
        XCTAssertTrue(
            content.contains("StudyTreeLayoutSnapshot(")
                && content.contains("StudyGrowthScoreTreeNode("),
            "The statistics tree should reuse the circular My Studies tree layout instead of rendering a depth-indented list."
        )
        XCTAssertTrue(
            detail.contains(".padding(.horizontal, 16)"),
            "The pushed growth detail should preserve the same horizontal screen padding as the statistics root."
        )
        XCTAssertFalse(
            detail.contains("StudyGrowthAttentionCard("),
            "Review candidates should be visible in the tree instead of a separate priority card."
        )
        XCTAssertFalse(
            detail.contains("isShowingAllStudies"),
            "The complete score tree should be visible without another disclosure control."
        )
        XCTAssertTrue(
            content.contains("label: strings.totalLearningShort")
                && content.contains("label: strings.totalTopicsShort")
                && content.contains("label: strings.measuredTopicsShort"),
            "Growth summaries should prioritize total learning and topic counts."
        )
        XCTAssertFalse(
            content.contains("growthCompletionValue(root.profile?.completion)")
                || content.contains("label: strings.completion"),
            "Question workflow completion should not be shown as a learning-growth statistic."
        )
    }

    func testStudyGrowthNodeDetailPaginatesTopicRecords() throws {
        let root = try repositoryRoot()
        let file = root.appendingPathComponent("StudyMate/Views/StatisticsView.swift")
        let content = try String(contentsOf: file, encoding: .utf8)
        let detailStart = try XCTUnwrap(
            content.range(of: "private struct StudyGrowthNodeDetailView: View")
        )
        let detailEnd = try XCTUnwrap(
            content.range(
                of: "private struct StudyGrowthDeltaLabel",
                range: detailStart.upperBound..<content.endIndex
            )
        )
        let detail = String(content[detailStart.lowerBound..<detailEnd.lowerBound])

        XCTAssertTrue(
            detail.contains("LazyVStack")
                && detail.contains("appState.fetchBackendRecords(")
                && detail.contains("loadNextPageIfNeeded"),
            "Selecting a statistics node should show its details and lazily page that node's records."
        )
        XCTAssertTrue(
            detail.contains("HistoryRow(")
                && detail.contains("selectedRecord = record"),
            "Topic records should reuse the existing paginated record row."
        )
        XCTAssertTrue(
            content.contains("record.asQuestionBrowseQuestion(author: author)"),
            "Statistics should project a record into the question-browse presentation model."
        )
        XCTAssertTrue(
            content.contains("CommunityQuestionDetailView("),
            "Statistics should navigate to the shared question-browse detail."
        )
        XCTAssertTrue(
            content.contains("contentSource: .record(isPublic: record.isPublic)"),
            "The question-browse detail should retain record privacy behavior."
        )
    }

    func testProtectedMobileTabsNavigateToDedicatedLoginPage() throws {
        let root = try repositoryRoot()
        let file = root.appendingPathComponent("StudyMate/Views/MobileRootView.swift")
        let content = try String(contentsOf: file, encoding: .utf8)

        XCTAssertFalse(
            content.contains("if appState.shouldShowRecordsLoginPage {\n                            MobileLoginPage("),
            "Records tab must show a lightweight gate and navigate to a dedicated login page instead of rendering the login page inline."
        )
        XCTAssertFalse(
            content.contains("if appState.shouldShowStatisticsLoginPage {\n                            MobileLoginPage("),
            "Statistics tab must show a lightweight gate and navigate to a dedicated login page instead of rendering the login page inline."
        )
        XCTAssertTrue(
            content.contains("MobileProtectedLoginGate"),
            "Protected mobile tabs should use a reusable gate view before pushing the login page."
        )
        XCTAssertTrue(
            content.contains("MobileProtectedLoginGate(\n                                    page: .records"),
            "Records login gate should keep the same root title position as the records screen."
        )
        XCTAssertTrue(
            content.contains("MobileProtectedLoginGate(\n                                    page: .statistics"),
            "Statistics login gate should keep the same root title position as the statistics screen."
        )
        XCTAssertTrue(
            content.contains("MobileRootLargeTitle(page.title(strings: strings))"),
            "Protected mobile gates should render the tab title instead of replacing the screen chrome with a login prompt."
        )
        XCTAssertTrue(
            content.contains("MobileProtectedLoginPreview"),
            "Protected mobile gates should preview the value of records and statistics before presenting the login action."
        )
        XCTAssertTrue(
            content.contains("MobileProtectedLoginFooter"),
            "Protected mobile gates should keep the login invitation in a dedicated bottom action region."
        )
        XCTAssertTrue(
            content.contains("MobilePrimaryLoginButtonLabel(title: page.loginActionTitle(strings: strings))"),
            "Protected mobile gates should use a clear primary login button without outcome-gated wording."
        )
        XCTAssertTrue(
            content.contains(".navigationDestination(isPresented: $isRecordsLoginPagePresented)"),
            "Records login action should push a dedicated login page in the tab navigation stack."
        )
        XCTAssertTrue(
            content.contains(".navigationDestination(isPresented: $isStatisticsLoginPagePresented)"),
            "Statistics login action should push a dedicated login page in the tab navigation stack."
        )
        XCTAssertFalse(
            content.contains("strings.pageAccessRequiresLogin"),
            "Protected mobile access surfaces should not render a redundant sign-in-required title."
        )
        XCTAssertFalse(
            content.contains("strings.protectedPageLoginHelp"),
            "Protected mobile access surfaces should not render a redundant after-sign-in explanation."
        )
        XCTAssertFalse(
            content.contains("prompt?.title"),
            "The dedicated login page should not echo page-access prompt text such as sign-in-required."
        )
        XCTAssertTrue(
            content.contains("MobileProtectedLoginPrompt(\n                page: .myStudy"),
            "The My Study login action should use the shared plain login prompt so list rows do not show a navigation chevron."
        )
        XCTAssertTrue(
            content.contains("onLogin: { isHomeLoginPagePresented = true }"),
            "The My Study login prompt should open the dedicated login page."
        )
        XCTAssertFalse(
            content.contains("NavigationLink {\n                    MobileLoginPage()"),
            "The My Study login action should not use an inline NavigationLink row accessory."
        )
    }

    func testMobileLoginPageIncludesLegalAgreementLinks() throws {
        let root = try repositoryRoot()
        let viewFile = root.appendingPathComponent("StudyMate/Views/MobileRootView.swift")
        let stringsFile = root.appendingPathComponent("StudyMate/Models/StudyModels.swift")
        let viewContent = try String(contentsOf: viewFile, encoding: .utf8)
        let stringsContent = try String(contentsOf: stringsFile, encoding: .utf8)

        XCTAssertTrue(
            viewContent.contains("Link(strings.termsOfService"),
            "Login page must link to the Terms of Service."
        )
        XCTAssertTrue(
            viewContent.contains("Link(strings.privacyPolicy"),
            "Login page must link to the Privacy Policy."
        )
        XCTAssertTrue(
            viewContent.contains("AppLegalLinks.termsOfServiceURL"),
            "Login page should use the shared legal URL constants."
        )
        XCTAssertTrue(
            viewContent.contains("AppLegalLinks.privacyPolicyURL"),
            "Login page should use the shared legal URL constants."
        )
        XCTAssertTrue(
            viewContent.contains(".tint(.accentColor)"),
            "Legal links must keep visible link styling instead of inheriting secondary body text color."
        )
        XCTAssertTrue(
            stringsContent.contains("loginAgreementPrefix"),
            "Legal agreement copy must be localized through AppStrings."
        )
    }

    func testRequiredTermsGatePrioritizesAllAgreementsAndKeepsRequiredOnlyChoiceSecondary() throws {
        let root = try repositoryRoot()
        let viewFile = root.appendingPathComponent("StudyMate/Views/MobileRootView.swift")
        let stringsFile = root.appendingPathComponent("StudyMate/Models/StudyModels.swift")
        let viewContent = try String(contentsOf: viewFile, encoding: .utf8)
        let stringsContent = try String(contentsOf: stringsFile, encoding: .utf8)

        XCTAssertTrue(
            viewContent.contains("await agreeTerms(includeMarketing: marketingTerms != nil)"),
            "The primary terms action must include active marketing consent."
        )
        XCTAssertTrue(
            viewContent.contains("Text(marketingTerms == nil ? strings.agreeAndStart : strings.agreeAllAndStart)"),
            "The primary terms action must clearly say that it agrees to all available terms."
        )
        XCTAssertTrue(
            viewContent.contains("await agreeTerms(includeMarketing: false)"),
            "Users must retain a required-terms-only path."
        )
        XCTAssertTrue(
            viewContent.contains("Text(strings.agreeRequiredOnlyAndStart)\n                        .font(.footnote)\n                        .foregroundStyle(.secondary)"),
            "The required-only path should remain available as a visually secondary action."
        )
        XCTAssertFalse(
            viewContent.contains("Button(strings.nextTime)"),
            "The required terms gate should start the app through an explicit consent choice instead of a generic later action."
        )
        XCTAssertTrue(
            stringsContent.contains("\"필수 약관만 동의하고 시작하기\""),
            "The secondary required-only action must use explicit localized consent copy."
        )
        XCTAssertTrue(
            stringsContent.contains("마케팅 정보 수신 동의는 선택입니다."),
            "The marketing nudge must still state that marketing consent is optional."
        )
    }

    func testMobileLoginSurfacesUseAppLogo() throws {
        let root = try repositoryRoot()
        let viewFile = root.appendingPathComponent("StudyMate/Views/MobileRootView.swift")
        let viewContent = try String(contentsOf: viewFile, encoding: .utf8)

        XCTAssertTrue(
            viewContent.contains("private struct MobileLoginLogo"),
            "Login UI should keep the app logo in a reusable component."
        )
        XCTAssertTrue(
            viewContent.contains("Image(\"BuddyStudyLoginLogo\")"),
            "Login UI should use the shared BuddyStudy login logo asset."
        )
        XCTAssertFalse(
            viewContent.contains("MobileLoginLogo(size: 72)"),
            "Protected login gates should stay visually quiet and leave the prominent app logo to the dedicated login page."
        )
        XCTAssertTrue(
            viewContent.contains("MobileLoginLogo(size: 96)"),
            "The dedicated login page should show a prominent centered app logo above sign-in actions."
        )
    }

    func testLegalDocsUseSingleBuddyStudyIconAsset() throws {
        let root = try repositoryRoot()
        let docsRoot = root.appendingPathComponent("docs", isDirectory: true)
        let documentPaths: [(root: URL, path: String, iconPath: String)] = [
            (root, "terms.html", "docs/assets/buddystudy-icon.png"),
            (root, "privacy.html", "docs/assets/buddystudy-icon.png"),
            (docsRoot, "index.html", "assets/buddystudy-icon.png"),
            (docsRoot, "terms.html", "assets/buddystudy-icon.png"),
            (docsRoot, "privacy.html", "assets/buddystudy-icon.png"),
            (docsRoot, "en/index.html", "../assets/buddystudy-icon.png"),
            (docsRoot, "en/privacy.html", "../assets/buddystudy-icon.png"),
        ]

        let violations = try documentPaths.flatMap { document -> [String] in
            let content = try String(
                contentsOf: document.root.appendingPathComponent(document.path),
                encoding: .utf8
            )
            var fileViolations: [String] = []

            if !content.contains(#"<link rel="icon" href="\#(document.iconPath)" type="image/png">"#) {
                fileViolations.append("\(document.path): favicon must use \(document.iconPath)")
            }
            if !content.contains(#"<link rel="apple-touch-icon" href="\#(document.iconPath)">"#) {
                fileViolations.append("\(document.path): apple-touch-icon must use \(document.iconPath)")
            }
            if content.contains("favicon-16.png")
                || content.contains("favicon-32.png")
                || content.contains("apple-touch-icon.png")
                || content.contains("studymate-icon") {
                fileViolations.append("\(document.path): legal/site docs must not reference alternate BuddyStudy icon assets")
            }

            return fileViolations
        }

        XCTAssertTrue(
            violations.isEmpty,
            "BuddyStudy site and legal documents should render one shared icon asset: \(violations)"
        )
    }

    func testMobileLoginButtonsUseRoundedRectangleShape() throws {
        let root = try repositoryRoot()
        let viewFile = root.appendingPathComponent("StudyMate/Views/MobileRootView.swift")
        let viewContent = try String(contentsOf: viewFile, encoding: .utf8)

        XCTAssertTrue(
            viewContent.contains("let buttonShape = RoundedRectangle(cornerRadius: 24, style: .continuous)"),
            "Login buttons should use an open rounded rectangle shape."
        )
        XCTAssertTrue(
            viewContent.contains(".frame(minHeight: 58)"),
            "Login buttons should have enough vertical space to avoid feeling cramped."
        )
        XCTAssertTrue(
            viewContent.contains(".background {\n                buttonShape"),
            "Login buttons should fill the rounded shape directly."
        )
        XCTAssertTrue(
            viewContent.contains(".contentShape(buttonShape)"),
            "Login button hit testing should follow the rounded shape."
        )
    }

    func testGoogleLoginUsesSystemWebAuthenticationSheet() throws {
        let root = try repositoryRoot()
        let file = root.appendingPathComponent("StudyMate/Services/GoogleOAuthService.swift")
        let content = try String(contentsOf: file, encoding: .utf8)

        XCTAssertTrue(
            content.contains("ASWebAuthenticationSession"),
            "Google Login must use the iOS system web authentication sheet instead of embedding OAuth in an arbitrary WebView."
        )
        XCTAssertTrue(
            content.contains("presentationContextProvider"),
            "The OAuth sheet needs an iOS presentation anchor so it appears as the system authentication sheet."
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

    func testBackendDoesNotExposePageAccessPreflightEndpoint() throws {
        let root = try repositoryRoot()
        let backendClientFile = root.appendingPathComponent("StudyMate/Services/RemotePushBackendClient.swift")
        let content = try String(contentsOf: backendClientFile, encoding: .utf8)

        XCTAssertFalse(
            content.contains("\"access\""),
            "The app must not call /api/v1/me/access as a page-access preflight."
        )
        XCTAssertFalse(
            FileManager.default.fileExists(atPath: root.appendingPathComponent("StudyMate/UseCases/PageAccess/RefreshPageAccessUseCase.swift").path),
            "Page access preflight use cases should be removed; normal API errors drive auth and permission UI."
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

    func testQuotaErrorDecodesResetTimeAndPresentsItInAppLanguage() throws {
        let payload = try XCTUnwrap(
            """
            {
              "error": {
                "errorCode": "QUOTA_EXCEEDED",
                "code": 305,
                "message": "월간 질문 한도에 도달했습니다.",
                "status": 403,
                "metadata": {
                  "quotaPeriod": "MONTHLY",
                  "quotaResetAt": "2026-08-01T00:00:00Z",
                  "quotaTimeZone": "Z",
                  "remaining": 0,
                  "required": 1
                }
              }
            }
            """.data(using: .utf8)
        )

        let response = try JSONDecoder().decode(BackendAPIErrorResponse.self, from: payload)
        let resetAt = try XCTUnwrap(response.error.metadata?.quotaResetDate)
        let expectedResetAt = try XCTUnwrap(
            ISO8601DateFormatter().date(from: "2026-08-01T00:00:00Z")
        )
        let error = RemotePushBackendError.httpStatus(403, "", response.error)
        let resolution = AppErrorHandlingPolicy.resolve(
            error,
            fallback: "fallback",
            language: .korean
        )

        XCTAssertEqual(resetAt, expectedResetAt)
        XCTAssertEqual(response.error.metadata?.quotaPeriod, "MONTHLY")
        XCTAssertEqual(response.error.metadata?.remaining, 0)
        XCTAssertTrue(resolution.featureMessage?.contains("이번 달 질문 한도에 도달했습니다.") == true)
        XCTAssertTrue(resolution.featureMessage?.contains("다시 사용할 수 있습니다.") == true)
    }

    func testQuotaErrorIgnoresKoreanServerMessageWhenAppLanguageIsEnglish() throws {
        let metadata = try JSONDecoder().decode(
            BackendAPIErrorMetadata.self,
            from: Data(#"{"quotaResetAt":"2026-08-01T00:00:00Z"}"#.utf8)
        )
        let apiError = BackendAPIError(
            code: "QUOTA_EXCEEDED",
            numericCode: 305,
            message: "월간 질문 한도에 도달했습니다.",
            status: 403,
            metadata: metadata
        )
        let resolution = AppErrorHandlingPolicy.resolve(
            RemotePushBackendError.httpStatus(403, "", apiError),
            fallback: "fallback",
            language: .english
        )

        XCTAssertTrue(
            resolution.featureMessage?.hasPrefix("You have reached this month's question limit.") == true
        )
        XCTAssertTrue(resolution.featureMessage?.contains("You can create questions again on") == true)
        XCTAssertFalse(resolution.featureMessage?.contains("월간 질문") ?? true)
    }

    func testQuotaErrorWithoutResetTimeUsesAppLanguage() {
        let apiError = BackendAPIError(
            code: "QUOTA_EXCEEDED",
            numericCode: 305,
            message: "월간 질문 한도에 도달했습니다.",
            status: 403
        )
        let resolution = AppErrorHandlingPolicy.resolve(
            RemotePushBackendError.httpStatus(403, "", apiError),
            fallback: "fallback",
            language: .english
        )

        XCTAssertEqual(
            resolution.featureMessage,
            "You have reached this month's question limit."
        )
    }

    func testQuestionGenerationKeepsBusyStateUntilPermanentFailureHandlingCompletes() throws {
        let root = try repositoryRoot()
        let appStateFile = root.appendingPathComponent("StudyMate/ViewModels/AppState.swift")
        let content = try String(contentsOf: appStateFile, encoding: .utf8)

        XCTAssertTrue(
            content.contains("guard !isGeneratingQuestion, questionGenerationPollingTask == nil else"),
            "A previous polling task must block a rapid second question-generation request."
        )
        let marker = "if appErrorHandlingUseCase.isPermanentBackendOperationError(error) {"
        var searchStart = content.startIndex
        for _ in 0..<2 {
            let markerRange = try XCTUnwrap(content.range(of: marker, range: searchStart..<content.endIndex))
            let blockEnd = content.index(
                markerRange.upperBound,
                offsetBy: 500,
                limitedBy: content.endIndex
            ) ?? content.endIndex
            let block = content[markerRange.lowerBound..<blockEnd]
            let handlerRange = try XCTUnwrap(block.range(of: "await handleQuestionGenerationRequestFailure("))
            let finishRange = try XCTUnwrap(block.range(of: "finishQuestionGenerationProcess()"))

            XCTAssertLessThan(
                handlerRange.lowerBound,
                finishRange.lowerBound,
                "Permanent failures must be presented before the generation lifecycle is released."
            )
            searchStart = markerRange.upperBound
        }
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
        XCTAssertFalse(resolution.shouldResetBackendIdentity)
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
        XCTAssertFalse(resolution.shouldResetBackendIdentity)
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

    func testTransientBackendFailuresDoNotExposeHTTPStatusOrServerDetails() {
        let gatewayError = RemotePushBackendError.httpStatus(502, "", nil)
        let detailedServiceError = RemotePushBackendError.httpStatus(
            503,
            "",
            BackendAPIError(
                code: "UPSTREAM_FAILURE",
                message: "upstream failed with HTTP 503",
                status: 503
            )
        )

        let gatewayResolution = AppErrorHandlingPolicy.resolve(
            gatewayError,
            fallback: "잠시 후 다시 시도해 주세요.",
            language: .korean
        )
        let detailedResolution = AppErrorHandlingPolicy.resolve(
            detailedServiceError,
            fallback: "잠시 후 다시 시도해 주세요.",
            language: .korean
        )

        XCTAssertEqual(gatewayResolution.featureMessage, "잠시 후 다시 시도해 주세요.")
        XCTAssertEqual(detailedResolution.featureMessage, "잠시 후 다시 시도해 주세요.")
        XCTAssertFalse(gatewayResolution.featureMessage?.contains("502") ?? true)
        XCTAssertFalse(detailedResolution.featureMessage?.contains("503") ?? true)
    }

    func testInvalidBackendResponseUsesSafeLocalizedFallback() {
        let koreanResolution = AppErrorHandlingPolicy.resolve(
            RemotePushBackendError.invalidResponse,
            fallback: "",
            language: .korean
        )
        let englishResolution = AppErrorHandlingPolicy.resolve(
            RemotePushBackendError.invalidResponse,
            fallback: "",
            language: .english
        )

        XCTAssertEqual(
            koreanResolution.featureMessage,
            "서버 응답을 확인할 수 없습니다. 잠시 후 다시 시도하세요."
        )
        XCTAssertEqual(
            englishResolution.featureMessage,
            "The server response could not be read. Please try again shortly."
        )
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

    func testCommunityPageDecodesBackendBooleanFieldNames() throws {
        let payload = Data(
            """
            {
              "questions": [
                {
                  "id": "25",
                  "question": "메시지 중복 처리를 설명하세요.",
                  "answer": "소비자에서 멱등하게 처리합니다.",
                  "gradingResult": {
                    "score": 88,
                    "correct": true,
                    "feedback": "좋아요.",
                    "explanation": "핵심을 설명했습니다."
                  },
                  "topic": "메시지큐",
                  "difficultyLevel": 7,
                  "status": "graded",
                  "source": "scheduled",
                  "createdAt": "2026-07-20T08:00:00Z",
                  "answeredAt": "2026-07-20T08:01:00Z",
                  "likeCount": 2,
                  "commentCount": 1,
                  "viewCount": 12,
                  "likedByMe": true
                }
              ],
              "totalCount": 1,
              "limit": 20,
              "offset": 0
            }
            """.utf8
        )
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601

        let page = try decoder.decode(CommunityQuestionsResponse.self, from: payload)

        XCTAssertEqual(page.questions.count, 1)
        XCTAssertEqual(page.questions.first?.gradingResult?.isCorrect, true)
        XCTAssertEqual(page.questions.first?.isLikedByMe, true)
    }

    func testCommunityLikeStateDecodesBackendLikedByMeFieldName() throws {
        let payload = Data(#"{"questionId":"25","likeCount":3,"likedByMe":true}"#.utf8)

        let state = try JSONDecoder().decode(CommunityLikeState.self, from: payload)

        XCTAssertEqual(state.questionID, "25")
        XCTAssertEqual(state.likeCount, 3)
        XCTAssertTrue(state.isLikedByMe)
    }

    func testNotificationPageDecodesBackendReadFieldName() throws {
        let payload = Data(
            """
            {
              "notifications": [
                {
                  "id": "17",
                  "type": "QUESTION_ANSWERED",
                  "title": "답변이 등록되었습니다",
                  "body": "질문 답변을 확인하세요.",
                  "threadType": "question",
                  "threadId": "25",
                  "deepLink": "buddystudy://questions/25",
                  "read": false,
                  "createdAt": "2026-07-20T08:00:00.123Z",
                  "readAt": null
                }
              ],
              "unreadCount": 2,
              "totalCount": 2,
              "limit": 30,
              "offset": 0
            }
            """.utf8
        )

        let page = try RemotePushBackendClient.makeDecoder().decode(BackendNotificationsPage.self, from: payload)

        XCTAssertEqual(page.notifications.count, 1)
        XCTAssertEqual(page.notifications.first?.id, "17")
        XCTAssertEqual(page.notifications.first?.isRead, false)
        XCTAssertEqual(page.unreadCount, 2)
        XCTAssertEqual(page.totalCount, 2)
    }

    func testNotificationPageStillDecodesLegacyIsReadFieldName() throws {
        let payload = Data(
            """
            {
              "notifications": [
                {
                  "id": "18",
                  "type": "COMMENT",
                  "title": "댓글이 등록되었습니다",
                  "body": "새 댓글을 확인하세요.",
                  "isRead": true,
                  "createdAt": "2026-07-20T08:00:00Z",
                  "readAt": "2026-07-20T08:01:00Z"
                }
              ],
              "unreadCount": 0,
              "totalCount": 1,
              "limit": 30,
              "offset": 0
            }
            """.utf8
        )

        let page = try RemotePushBackendClient.makeDecoder().decode(BackendNotificationsPage.self, from: payload)

        XCTAssertEqual(page.notifications.first?.isRead, true)
        XCTAssertNotNil(page.notifications.first?.readAt)
    }

    func testNotificationPageRejectsMissingCollectionWhenCountsArePositive() {
        let payload = Data(#"{"unreadCount":2,"totalCount":2,"limit":30,"offset":0}"#.utf8)

        XCTAssertThrowsError(
            try RemotePushBackendClient.makeDecoder().decode(BackendNotificationsPage.self, from: payload)
        ) { error in
            let diagnostic = AppErrorHandlingUseCase().diagnosticDescription(for: error)
            XCTAssertTrue(diagnostic.contains("path=$.notifications"))
        }
    }

    func testNotificationRouteResolverUsesStudyQuestionRecord() {
        let notification = BackendAppNotification(
            id: "1",
            type: "STUDY_QUESTION",
            title: "BuddyStudy",
            body: "새 질문",
            threadType: "study_question",
            threadId: "81",
            isRead: false,
            createdAt: Date()
        )

        XCTAssertEqual(
            NotificationRouteResolver.route(for: notification),
            .recordDetail(recordID: "81")
        )
    }

    func testNotificationRouteResolverUsesCommunityQuestionThread() {
        let notification = BackendAppNotification(
            id: "2",
            type: "THREAD_ACTIVITY",
            title: "새 댓글",
            body: "질문에 댓글이 등록되었습니다.",
            threadType: "question",
            threadId: "100",
            isRead: false,
            createdAt: Date()
        )

        XCTAssertEqual(
            NotificationRouteResolver.route(for: notification),
            .publicQuestion(id: "100")
        )
    }

    func testNotificationRouteResolverPrefersValidDeepLink() {
        let notification = BackendAppNotification(
            id: "3",
            type: "THREAD_ACTIVITY",
            title: "새 댓글",
            body: "질문에 댓글이 등록되었습니다.",
            threadType: "question",
            threadId: "100",
            deepLink: "buddystudy://public/questions/200",
            isRead: false,
            createdAt: Date()
        )

        XCTAssertEqual(
            NotificationRouteResolver.route(for: notification),
            .publicQuestion(id: "200")
        )
    }

    func testNotificationRouteResolverFallsBackWhenDeepLinkIsInvalid() {
        let notification = BackendAppNotification(
            id: "4",
            type: "THREAD_ACTIVITY",
            title: "새 좋아요",
            body: "질문에 좋아요가 등록되었습니다.",
            threadType: "like",
            threadId: "300",
            deepLink: "https://unsupported.example/questions/300",
            isRead: false,
            createdAt: Date()
        )

        XCTAssertEqual(
            NotificationRouteResolver.route(for: notification),
            .publicQuestion(id: "300")
        )
    }

    func testNotificationRouteResolverUsesRelevantListWithoutThreadID() {
        XCTAssertEqual(
            NotificationRouteResolver.route(
                deepLink: nil,
                threadType: nil,
                threadID: nil,
                notificationType: "STUDY_QUESTION"
            ),
            .studyList
        )
        XCTAssertEqual(
            NotificationRouteResolver.route(
                deepLink: nil,
                threadType: nil,
                threadID: nil,
                notificationType: "THREAD_ACTIVITY"
            ),
            .publicQuestions
        )
    }

    func testNotificationPayloadRoutesThreadMetadataWithoutDeepLink() {
        let payload: [AnyHashable: Any] = [
            "type": "THREAD_ACTIVITY",
            "threadType": "question",
            "threadId": "400"
        ]

        XCTAssertEqual(
            StudyNotificationPayload.appRoute(from: payload),
            .publicQuestion(id: "400")
        )
    }

    func testNotificationPayloadDoesNotConsumeUnknownTypeAsHomeRoute() {
        let payload: [AnyHashable: Any] = [
            "type": "cloudkit-query",
            StudyNotificationAction.questionCreatedAt: NSNumber(value: 100.25)
        ]

        XCTAssertNil(StudyNotificationPayload.appRoute(from: payload))
        XCTAssertEqual(StudyNotificationPayload.questionCreatedAt(from: payload), 100.25)
    }

    func testExplicitNotificationTapIsNavigationIntentRegardlessOfActivationTiming() {
        XCTAssertTrue(
            StudyNotificationRouting.shouldOpenStudyImmediately(
                actionIdentifier: UNNotificationDefaultActionIdentifier
            )
        )
        XCTAssertFalse(
            StudyNotificationRouting.shouldOpenStudyImmediately(
                actionIdentifier: StudyNotificationAction.reply
            )
        )
        XCTAssertFalse(
            StudyNotificationRouting.shouldOpenStudyImmediately(
                actionIdentifier: UNNotificationDismissActionIdentifier
            )
        )
    }

    func testNotificationDelegateIsInstalledDuringApplicationLaunch() throws {
        let root = try repositoryRoot()
        let appContent = try String(
            contentsOf: root.appendingPathComponent("StudyMate/StudyMateiOSApp.swift"),
            encoding: .utf8
        )
        let launchMethod = try XCTUnwrap(
            appContent.range(
                of: "didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil"
            )
        )
        let launchBody = appContent[launchMethod.lowerBound...]
        let delegateRegistration = try XCTUnwrap(
            launchBody.range(of: "StudyNotificationDelegate.shared.register()")
        )
        let launchReturn = try XCTUnwrap(launchBody.range(of: "return true"))

        XCTAssertLessThan(delegateRegistration.lowerBound, launchReturn.lowerBound)
    }

    func testAPIValidationDecodesBackendValidFieldName() throws {
        let payload = Data(#"{"openaiKeyConfigured":true,"valid":true,"openaiModel":"gpt-5.4"}"#.utf8)

        let response = try JSONDecoder().decode(BackendAPIValidation.self, from: payload)

        XCTAssertTrue(response.openAIKeyConfigured)
        XCTAssertTrue(response.isValid)
        XCTAssertEqual(response.openAIModel, "gpt-5.4")
    }

    func testStudyRecordDecodesBackendPublicFieldName() throws {
        let payload = Data(
            """
            {
              "id": "25",
              "question": {
                "question": "메시지 중복 처리를 설명하세요.",
                "expectedAnswerHint": null,
                "createdAt": "2026-07-20T08:00:00Z"
              },
              "answer": "소비자에서 멱등하게 처리합니다.",
              "gradingResult": {
                "score": 88,
                "correct": true,
                "feedback": "좋아요.",
                "explanation": "핵심을 설명했습니다."
              },
              "topic": "메시지큐",
              "difficulty": 7,
              "answeredAt": "2026-07-20T08:01:00Z",
              "public": false,
              "likeCount": 2,
              "commentCount": 1,
              "viewCount": 12
            }
            """.utf8
        )

        let record = try RemotePushBackendClient.makeDecoder().decode(StudyRecord.self, from: payload)

        XCTAssertFalse(record.isPublic)
        XCTAssertEqual(record.gradingResult?.isCorrect, true)
    }

    func testDecodingDiagnosticIncludesMissingKeyAndCodingPath() {
        struct RequiredBodyPayload: Decodable {
            let body: String
        }

        do {
            _ = try JSONDecoder().decode(RequiredBodyPayload.self, from: Data("{}".utf8))
            XCTFail("Expected decoding to fail.")
        } catch {
            let diagnostic = AppErrorHandlingUseCase().diagnosticDescription(for: error)

            XCTAssertTrue(diagnostic.contains("kind=keyNotFound"))
            XCTAssertTrue(diagnostic.contains("path=$.body"))
        }
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
