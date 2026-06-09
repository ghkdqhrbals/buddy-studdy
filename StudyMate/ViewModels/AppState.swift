import Foundation
import Combine
#if os(macOS)
import AppKit
#elseif os(iOS)
import UIKit
import UniformTypeIdentifiers
#endif

private enum QuestionGenerationSkip: Error {
    case pendingLimit
    case duplicateQuestion
}

private enum ProtectedAppPage {
    case records
    case statistics
    case studyDetail

    func title(strings: AppStrings) -> String {
        switch self {
        case .records:
            return strings.tabRecords
        case .statistics:
            return strings.tabStatistics
        case .studyDetail:
            return strings.tabStudy
        }
    }
}

struct PageAccessPrompt: Identifiable, Equatable {
    let id = UUID()
    var title: String
    var message: String
}

enum EmailCommunitySignInResult: Equatable {
    case signedIn
    case verificationRequired
    case failed
}

#if os(iOS)
private final class BackgroundTaskExpiration: @unchecked Sendable {
    private let lock = NSLock()
    private var expired = false

    var isExpired: Bool {
        lock.lock()
        defer {
            lock.unlock()
        }

        return expired
    }

    func expire() {
        lock.lock()
        expired = true
        lock.unlock()
    }
}
#endif

@MainActor
final class AppState: ObservableObject {
    static let developerLogPageSize = 50
    static let maxPendingQuestionCount = 1
    static let communityQuestionPageSize = 20
    static let maxAPITrafficLogs = 120
    private static let clipboardQuickReadAttempts = 10
    private static let clipboardFallbackAttempts = 70
    private static let clipboardQuickReadIntervalMilliseconds: UInt64 = 8
    private static let clipboardSettingsReadAttempts = 120
    private static let clipboardSettingsReadIntervalMilliseconds: UInt64 = 16
    private static let clipboardStickyReadIntervalMilliseconds: UInt64 = 6
    private static let recentLocalSettingsMutationWindow: TimeInterval = 300

    @Published var settings: StudySettings
    @Published var draftSettings: StudySettings
    @Published var currentQuestion: QuestionItem?
    @Published var lastAnswer: String
    @Published var gradingResult: GradingResult?
    @Published var apiKey: String = ""
    @Published var draftAPIKey: String = ""
    @Published var isGeneratingQuestion = false
    @Published var isGradingAnswer = false
    @Published var isRunning: Bool
    @Published var studyRecords: [StudyRecord]
    @Published var backendStats: BackendStats?
    @Published var isBackendStatsLoading = false
    @Published var backendStatsErrorMessage: String?
    @Published var hasAPIKeyError = false
    @Published var isValidatingAPIKey = false
    @Published var appLogs: [AppLogEntry]
    @Published var appLogTotalCount: Int
    @Published var appLogPage: Int
    @Published var apiTrafficLogs: [APITrafficLogEntry] = []
    @Published var isAPIDebugPanelPresented = false
    @Published var isDebuggingEnabled: Bool
    @Published var debugBackendBaseURL: String
    @Published var draftDebugBackendBaseURL: String
    @Published var statusMessage: String?
    @Published var errorMessage: String?
    @Published var notificationLandingMessage: String?
    @Published var selectedTab: AppTab = .study
    @Published var homeStudyRoute: HomeStudyRoute?
    @Published var appRouteRequest: AppRouteRequest?
    @Published var focusedRecordRequest: FocusedRecordRequest?
    @Published var openAIModelOptions: [OpenAIModelOption] = OpenAIModelOption.all
    @Published var hasCompletedOnboarding: Bool
    @Published var isRefreshingVisibleData = false
    @Published var isCloudSyncEnabled: Bool
    @Published var isCloudSyncing = false
    @Published var isCommunitySignedIn: Bool

    var studyCategoriesForDisplay: [StudyCategory] {
        let synchronized = synchronizedTopicCategories(for: settings)
        return synchronized.studyCategories
    }

    var selectedStudyCategoryIDForDisplay: String? {
        let synchronized = synchronizedTopicCategories(for: settings)
        return synchronized.selectedStudyCategoryID
    }
    @Published var cloudSyncMessage: String?
    @Published var hasCloudSyncError = false
    @Published var cloudLastSyncedAt: Date?
    @Published var isBackendOpenAIKeyConfigured = false
    @Published var communityQuestions: [CommunityQuestion] = []
    @Published var communitySearchText = ""
    @Published var communityTotalCount = 0
    @Published var communityOffset = 0
    @Published var isLoadingCommunityQuestions = false
    @Published var communityErrorMessage: String?
    @Published var communityProfile: CommunityUserProfile?
    @Published var isUpdatingCommunityProfile = false
    @Published var isWithdrawingCommunityAccount = false
    @Published var pageAccessPrompt: PageAccessPrompt?
    @Published var profileAvatarSymbolName: String
    @Published var profileAvatarImageData: Data?
    @Published var profileAvatarColorSeed: String

    private let settingsStore: SettingsStore
    private var remotePushBackendClient: RemotePushBackendClientProtocol
    private let usesConfigurableRemotePushBackendClient: Bool
    private let notificationService: NotificationServicing
    private var cloudSyncService: CloudSyncServiceProtocol?
    private var timerTask: Task<Void, Never>?
    private var cloudSyncTask: Task<Void, Never>?
    private var visibleDataRefreshTask: Task<Void, Never>?
    private var lastBackgroundQuestionPreparationAt: Date?
    private var didStart = false
    private var savedSettings: StudySettings
    private var savedAPIKey: String
    private var savedDebugBackendBaseURL: String
    private var clipboardPasteRequestID = 0
    private var isEditingSettings = false
    private var didReceiveCloudStateWhileEditing = false
    private var backendStatsRequestID = UUID()
    private var communityQuestionLoadRequestID = UUID()
    private var apiTrafficLogCancellable: AnyCancellable?
    private var backendUnauthorizedCancellable: AnyCancellable?
    private var lastAPIKeyUpdatedAt: Date?
    private var lastLocalSettingsMutationAt: Date?

    var strings: AppStrings {
        AppStrings(language: settings.appLanguage)
    }

    var settingsEditorStrings: AppStrings {
        AppStrings(language: draftSettings.appLanguage)
    }

    var statusTitle: String {
        strings.statusTitle(isRunning: isRunning)
    }

    var hasUnsavedSettingsChanges: Bool {
        var comparableActiveSettings = normalizedSettings(activeSettingsForEditing)
        var comparableSavedSettings = normalizedSettings(savedSettings)
        if !isCommunitySignedIn {
            comparableActiveSettings = comparableActiveSettings.withQuestionPrivacy(false)
            comparableSavedSettings = comparableSavedSettings.withQuestionPrivacy(false)
        }

        return comparableActiveSettings != comparableSavedSettings ||
            activeAPIKeyForEditing.trimmingCharacters(in: .whitespacesAndNewlines) != savedAPIKey.trimmingCharacters(in: .whitespacesAndNewlines) ||
            Self.normalizedDebugBackendBaseURL(activeDebugBackendBaseURLForEditing) != Self.normalizedDebugBackendBaseURL(savedDebugBackendBaseURL)
    }

    var isDraftDebugBackendBaseURLValid: Bool {
        let normalizedURL = Self.normalizedDebugBackendBaseURL(draftDebugBackendBaseURL)
        return normalizedURL.isEmpty || Self.resolvedDebugBackendURL(from: normalizedURL) != nil
    }

    var mobileVisibleTab: AppTab {
        switch selectedTab {
        case .home, .records, .statistics, .settings:
            return selectedTab
        case .study:
            return .home
        }
    }

    func normalizeSelectedTabForMobile() {
        if selectedTab == .study {
            selectedTab = .home
        }
        homeStudyRoute = nil
    }

    func setSelectedTab(_ nextTab: AppTab) {
        if isEditingSettings && selectedTab == .settings && nextTab != .settings {
            cancelSettingsEditing()
        }

        if let protectedPage = protectedPage(for: nextTab),
           !canAccess(protectedPage) {
            redirectToPageAccessGuide(for: protectedPage)
            return
        }

        selectedTab = nextTab
        if nextTab == .home {
            homeStudyRoute = nil
        }
    }

    func openDeepLink(_ url: URL) {
        guard let route = AppRoute(url: url) else {
            log(.warning, "지원하지 않는 딥링크를 무시했습니다. url=\(url.absoluteString)")
            return
        }

        openRoute(route)
    }

    @discardableResult
    func openRoute(_ route: AppRoute) -> Bool {
        switch route {
        case .home:
            selectedTab = .home
            homeStudyRoute = nil
            return true
        case .studyList, .publicQuestions:
            selectedTab = .home
            homeStudyRoute = nil
            appRouteRequest = AppRouteRequest(route: route)
            return true
        case .studyRoom(let categoryID):
            showStudyScreen(categoryID: categoryID)
            return true
        case .records:
            setSelectedTab(.records)
            return mobileVisibleTab == .records
        case .recordDetail(let recordID):
            guard requirePageAccess(.records) else {
                return false
            }
            selectedTab = .records
            homeStudyRoute = nil
            focusedRecordRequest = FocusedRecordRequest(recordID: recordID)
            return true
        case .statistics:
            setSelectedTab(.statistics)
            return mobileVisibleTab == .statistics
        case .settings, .settingsOpenAI:
            setSelectedTab(.settings)
            appRouteRequest = AppRouteRequest(route: route)
            return true
        case .profile, .publicQuestion:
            selectedTab = .home
            homeStudyRoute = nil
            appRouteRequest = AppRouteRequest(route: route)
            return true
        }
    }

    private func protectedPage(for tab: AppTab) -> ProtectedAppPage? {
        switch tab {
        case .records:
            return .records
        case .statistics:
            return .statistics
        case .study:
            return .studyDetail
        case .home, .settings:
            return nil
        }
    }

    private func canAccess(_: ProtectedAppPage) -> Bool {
        isCommunitySignedIn
    }

    @discardableResult
    private func requirePageAccess(_ page: ProtectedAppPage) -> Bool {
        guard canAccess(page) else {
            redirectToPageAccessGuide(for: page)
            return false
        }

        return true
    }

    private func redirectToPageAccessGuide(for page: ProtectedAppPage) {
        selectedTab = .home
        homeStudyRoute = nil
        focusedRecordRequest = nil
        let message = strings.pageAccessDenied(page.title(strings: strings))
        pageAccessPrompt = PageAccessPrompt(
            title: strings.signInRequiredTitle,
            message: message
        )
    }

    func dismissPageAccessPrompt() {
        pageAccessPrompt = nil
    }

    @discardableResult
    private func handlePageAccessError(_ error: Error, page: ProtectedAppPage) -> Bool {
        guard let backendError = error as? RemotePushBackendError,
              backendError.isPageAccessDenied else {
            return false
        }

        redirectToPageAccessGuide(for: page)
        return true
    }

    var apiKeyValidationMessage: String? {
        guard hasAPIKeyError else {
            return nil
        }

        let strings = AppStrings(language: activeSettingsForEditing.appLanguage)
        if activeAPIKeyForEditing.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return strings.apiKeyEmpty
        }

        return errorMessage ?? strings.apiKeyCheck
    }

    private var activeSettingsForEditing: StudySettings {
        isEditingSettings ? draftSettings : settings
    }

    private var activeAPIKeyForEditing: String {
        (isEditingSettings ? draftAPIKey : apiKey).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var activeDebugBackendBaseURLForEditing: String {
        isEditingSettings ? draftDebugBackendBaseURL : debugBackendBaseURL
    }

    private static func normalizedDebugBackendBaseURL(_ value: String) -> String {
        BackendBaseURLConfiguration.normalizedDebugBackendBaseURL(value)
    }

    private static func makeRemotePushBackendClient(
        isDebuggingEnabled: Bool,
        debugBackendBaseURL: String
    ) -> RemotePushBackendClient {
        BackendBaseURLConfiguration(
            isDebuggingEnabled: isDebuggingEnabled,
            debugBackendBaseURL: debugBackendBaseURL
        ).makeClient()
    }

    private static func resolvedDebugBackendURL(from value: String) -> URL? {
        BackendBaseURLConfiguration.resolvedDebugBackendURL(from: value)
    }

    private var activeBackendBaseURLDescription: String {
        BackendBaseURLConfiguration(
            isDebuggingEnabled: isDebuggingEnabled,
            debugBackendBaseURL: debugBackendBaseURL
        ).displayBaseURL
    }

    private func refreshRemotePushBackendClient(reason: String) {
        guard usesConfigurableRemotePushBackendClient else {
            return
        }

        remotePushBackendClient = Self.makeRemotePushBackendClient(
            isDebuggingEnabled: isDebuggingEnabled,
            debugBackendBaseURL: debugBackendBaseURL
        )
        log(.info, "백엔드 API 경로를 갱신했습니다. reason=\(reason), baseURL=\(activeBackendBaseURLDescription)")
    }

    var pendingQuestionCount: Int {
        pendingRecordsIncludingCurrent.count
    }

    var hasReachedPendingQuestionLimit: Bool {
        pendingQuestionCount >= Self.maxPendingQuestionCount
    }

    func pendingQuestionCount(for category: StudyCategory) -> Int {
        let categoryKey = Self.normalizedCategoryText(for: category.title)
        return pendingRecordsIncludingCurrent.filter {
            Self.normalizedCategoryText(for: $0.topic) == categoryKey
        }.count
    }

    func hasReachedPendingQuestionLimit(for category: StudyCategory?) -> Bool {
        guard let category else {
            return hasReachedPendingQuestionLimit
        }

        return pendingQuestionCount(for: category) >= Self.maxPendingQuestionCount
    }

    var pendingStudyRecords: [StudyRecord] {
        pendingRecordsIncludingCurrent
            .sorted { $0.question.createdAt > $1.question.createdAt }
    }

    private var pendingRecordsIncludingCurrent: [StudyRecord] {
        var records = studyRecords.filter { $0.gradingResult == nil }

        if let currentQuestion,
           gradingResult == nil,
           !records.contains(where: { studyRecordMatches($0, question: currentQuestion) }) {
            records.append(
                StudyRecord(
                    question: currentQuestion,
                    answer: lastAnswer.isEmpty ? nil : lastAnswer,
                    topic: settings.topic,
                    difficulty: settings.difficulty
                )
            )
        }

        return records
    }

    var canSkipCurrentQuestion: Bool {
        guard let currentRecord = studyRecord(matching: currentQuestion) else {
            return currentQuestion != nil
        }

        return currentRecord.gradingResult == nil
    }

    var appLogPageCount: Int {
        max(1, (appLogTotalCount + Self.developerLogPageSize - 1) / Self.developerLogPageSize)
    }

    var appLogPageStart: Int {
        guard appLogTotalCount > 0 else {
            return 0
        }

        return appLogPage * Self.developerLogPageSize + 1
    }

    var appLogPageEnd: Int {
        guard appLogTotalCount > 0 else {
            return 0
        }

        return min(appLogPageStart + appLogs.count - 1, appLogTotalCount)
    }

    init(
        settingsStore: SettingsStore = SettingsStore(),
        remotePushBackendClient: RemotePushBackendClientProtocol? = nil,
        notificationService: NotificationServicing = NotificationService(),
        cloudSyncService: CloudSyncServiceProtocol? = nil
    ) {
        let loadedSettings = settingsStore.loadSettings()
        let synchronizedLoadedSettings = Self.synchronizedTopicCategories(
            for: loadedSettings,
            fallbackTopicResolver: Self.defaultFallbackTopic
        )
        let loadedIsCommunitySignedIn = settingsStore.loadIsCommunitySignedIn()
        let effectiveLoadedSettings = loadedIsCommunitySignedIn
            ? synchronizedLoadedSettings
            : synchronizedLoadedSettings.withQuestionPrivacy(false)
        if effectiveLoadedSettings != loadedSettings {
            settingsStore.saveSettings(effectiveLoadedSettings)
        }
        let loadedAPIKey = settingsStore.loadAPIKey().trimmingCharacters(in: .whitespacesAndNewlines)
        let loadedAPIKeyUpdatedAt = settingsStore.loadOpenAIAPIKeyUpdatedAt()
        let effectiveAPIKeyUpdatedAt = loadedAPIKeyUpdatedAt ?? (loadedAPIKey.isEmpty ? nil : Date())
        let loadedLogPage = settingsStore.loadAppLogs(page: 0, pageSize: Self.developerLogPageSize)
        let loadedHasCompletedOnboarding = settingsStore.loadHasCompletedOnboarding()
        let loadedCloudLastSyncedAt = settingsStore.loadCloudSyncStateUpdatedAt()
        let loadedLocalSettingsMutationAt = settingsStore.loadLocalSettingsMutationAt()
        let loadedIsDebuggingEnabled = settingsStore.loadIsDebuggingEnabled()
        let loadedDebugBackendBaseURL = Self.normalizedDebugBackendBaseURL(settingsStore.loadDebugBackendBaseURL())

        self.settingsStore = settingsStore
        self.settings = effectiveLoadedSettings
        self.draftSettings = effectiveLoadedSettings
        self.currentQuestion = settingsStore.loadQuestion()
        self.lastAnswer = settingsStore.loadLastAnswer()
        self.gradingResult = settingsStore.loadGradingResult()
        let loadedIsRunning = settingsStore.loadIsRunning()
        let shouldRecoverLegacyRunningState = loadedHasCompletedOnboarding
            && !loadedIsRunning
            && !settingsStore.hasExplicitRunningPreference()
            && !loadedAPIKey.isEmpty
        self.isRunning = shouldRecoverLegacyRunningState ? true : loadedIsRunning
        if shouldRecoverLegacyRunningState {
            settingsStore.saveIsRunning(true)
        }
        self.studyRecords = settingsStore.loadStudyRecords()
        self.backendStats = nil
        self.apiKey = loadedAPIKey
        self.draftAPIKey = loadedAPIKey
        self.lastAPIKeyUpdatedAt = effectiveAPIKeyUpdatedAt
        self.savedSettings = effectiveLoadedSettings
        self.savedAPIKey = loadedAPIKey
        self.savedDebugBackendBaseURL = loadedDebugBackendBaseURL
        self.appLogs = loadedLogPage.entries
        self.appLogTotalCount = loadedLogPage.totalCount
        self.appLogPage = loadedLogPage.page
        self.isDebuggingEnabled = loadedIsDebuggingEnabled
        self.debugBackendBaseURL = loadedDebugBackendBaseURL
        self.draftDebugBackendBaseURL = loadedDebugBackendBaseURL
        self.hasCompletedOnboarding = loadedHasCompletedOnboarding
        self.isCloudSyncEnabled = cloudSyncService == nil ? false : settingsStore.loadIsCloudSyncEnabled()
        if cloudSyncService == nil {
            settingsStore.saveIsCloudSyncEnabled(false)
        }
        self.isCommunitySignedIn = loadedIsCommunitySignedIn
        self.profileAvatarSymbolName = settingsStore.loadProfileAvatarSymbolName()
        self.profileAvatarImageData = settingsStore.loadProfileAvatarImageData()
        if let loadedAvatarColorSeed = settingsStore.loadProfileAvatarColorSeed(),
           !loadedAvatarColorSeed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            self.profileAvatarColorSeed = loadedAvatarColorSeed
        } else {
            let generatedSeed = UUID().uuidString
            self.profileAvatarColorSeed = generatedSeed
            settingsStore.saveProfileAvatarColorSeed(generatedSeed)
        }
        self.cloudLastSyncedAt = loadedCloudLastSyncedAt
        self.lastLocalSettingsMutationAt = loadedLocalSettingsMutationAt ?? loadedCloudLastSyncedAt
        self.notificationService = notificationService
        self.cloudSyncService = cloudSyncService
        self.usesConfigurableRemotePushBackendClient = remotePushBackendClient == nil
        self.remotePushBackendClient = remotePushBackendClient ?? Self.makeRemotePushBackendClient(
            isDebuggingEnabled: loadedIsDebuggingEnabled,
            debugBackendBaseURL: loadedDebugBackendBaseURL
        )
        self.hasAPIKeyError = apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        self.apiTrafficLogCancellable = NotificationCenter.default.publisher(
            for: APITrafficNotification.didReceiveLog,
            object: nil
        )
        .compactMap { notification -> APITrafficLogEntry? in
            (notification.userInfo?[APITrafficNotification.userInfoKey] as? APITrafficLogEntry)
        }
        .sink { [weak self] entry in
            self?.appendAPITrafficLog(entry)
        }
        self.backendUnauthorizedCancellable = NotificationCenter.default.publisher(
            for: BackendAuthorizationNotification.didReceiveUnauthorized,
            object: nil
        )
        .sink { [weak self] _ in
            self?.clearStoredBackendAccessToken()
        }

        if shouldRecoverLegacyRunningState {
            log(.info, "백엔드 schedule 상태로 인해 저장된 이전 일시정지 값을 실행 상태로 복구했습니다.")
        }

        if loadedAPIKeyUpdatedAt == nil, !loadedAPIKey.isEmpty {
            settingsStore.saveOpenAIAPIKeyUpdatedAt(effectiveAPIKeyUpdatedAt)
        }

        if !hasCompletedOnboarding {
            log(.info, "첫 실행 온보딩이 필요합니다.")
        } else if hasAPIKeyError {
            log(.warning, "OpenAI API 키가 비어 있습니다.")
        } else {
            log(.info, "앱 상태를 불러왔습니다.")
        }

        restartTimer()
    }

    deinit {
        MainActor.assumeIsolated {
            let timerTask = timerTask
            let cloudSyncTask = cloudSyncTask
            let apiTrafficLogCancellable = apiTrafficLogCancellable

            timerTask?.cancel()
            cloudSyncTask?.cancel()
            apiTrafficLogCancellable?.cancel()
        }
    }

    func start() async {
        guard !didStart else {
            return
        }

        didStart = true
        guard hasCompletedOnboarding else {
            log(.info, "온보딩 완료 전이라 시작 작업을 대기합니다.")
            return
        }

        if isCloudSyncEnabled {
            await syncCloudNow(updateVisibleQuestion: false)
            await ensureCloudQuestionPushSubscription()
        }

        await loadOpenAIModelOptions()
        await refreshBackendStudyIfPossible(updateVisibleQuestion: false)
        _ = await notificationService.requestAuthorizationIfNeeded(language: settings.appLanguage)
        await validateAPIKeyOnStartup()
        #if os(macOS)
        await generateDueQuestionIfNeeded(reason: "startup")
        #endif
        restartTimer()
    }

    func handleAppBecameActive() async {
        guard hasCompletedOnboarding else {
            return
        }

        reloadPersistedState()
        await loadOpenAIModelOptions()
        await refreshBackendStudyIfPossible(updateVisibleQuestion: false)
        if isCloudSyncEnabled {
            await syncCloudNow(updateVisibleQuestion: false)
            await ensureCloudQuestionPushSubscription()
        }
        #if os(macOS)
        await generateDueQuestionIfNeeded(reason: "foreground")
        #else
        log(.info, "iOS foreground 진입은 조용한 동기화만 수행합니다. 예약 질문은 서버/APNs와 타이머 경로가 담당합니다.")
        #endif
    }

    @discardableResult
    func handleBackgroundRefresh() async -> Bool {
        guard hasCompletedOnboarding else {
            return false
        }

        reloadPersistedState()
        await refreshBackendStudyIfPossible(updateVisibleQuestion: false)
        if isCloudSyncEnabled {
            await syncCloudNow(updateVisibleQuestion: false)
            await ensureCloudQuestionPushSubscription()
        }

        return await generateDueQuestionIfNeeded(reason: "background-refresh")
    }

    @discardableResult
    func prepareBackgroundQuestionNotifications() async -> Int {
        #if os(iOS)
        let expiration = BackgroundTaskExpiration()
        let taskIdentifier = UIApplication.shared.beginBackgroundTask(withName: "StudyMate.prepareQuestions") {
            expiration.expire()
        }
        defer {
            if taskIdentifier != .invalid {
                UIApplication.shared.endBackgroundTask(taskIdentifier)
            }
        }

        return await prepareScheduledQuestionsForLockedDevice {
            expiration.isExpired
        }
        #else
        return 0
        #endif
    }

    func backgroundRefreshEarliestBeginDate(now: Date = Date()) -> Date {
        refreshStudyProgressFromStore()
        return nextQuestionDueDate(now: now)
    }

    func refreshVisibleData() async {
        if let visibleDataRefreshTask {
            await visibleDataRefreshTask.value
            return
        }

        let task = Task { @MainActor in
            isRefreshingVisibleData = true
            defer {
                isRefreshingVisibleData = false
            }

            reloadPersistedState()
            let didRefreshBackend = await refreshBackendStudyIfPossible()
            if isCloudSyncEnabled {
                await syncCloudNow()
            } else if !didRefreshBackend {
                statusMessage = strings.refreshed
                log(.info, "화면 데이터를 새로고침했습니다.")
            }
        }

        visibleDataRefreshTask = task
        await task.value
        visibleDataRefreshTask = nil
    }

    private func loadOpenAIModelOptions() async {
        do {
            let fetchedOptions = try await remotePushBackendClient.fetchOpenAIModelOptions()
            let normalized: [OpenAIModelOption] = fetchedOptions.compactMap { option -> OpenAIModelOption? in
                let id = option.id.trimmingCharacters(in: .whitespacesAndNewlines)
                let displayName = option.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !id.isEmpty && !displayName.isEmpty else {
                    return nil
                }
                return OpenAIModelOption(
                    id: id,
                    displayName: displayName,
                    supportsTextVerbosity: option.supportsTextVerbosity
                )
            }

            let uniqueOptions = normalized.enumerated().reduce(into: [OpenAIModelOption]()) { result, current in
                if !result.contains(where: { $0.id == current.element.id }) {
                    result.append(current.element)
                }
            }

            if !uniqueOptions.isEmpty {
                openAIModelOptions = uniqueOptions
                let currentModel = settings.openAIModel.trimmingCharacters(in: .whitespacesAndNewlines)
                if !currentModel.isEmpty && !openAIModelOptions.contains(where: { $0.id == currentModel }) {
                    openAIModelOptions.append(
                        OpenAIModelOption(
                            id: currentModel,
                            displayName: currentModel,
                            supportsTextVerbosity: false
                        )
                    )
                } else if !openAIModelOptions.contains(where: { $0.id == currentModel }) {
                    settings = synchronizedTopicCategories(
                        for: StudySettings(
                            topic: settings.topic,
                            difficulty: settings.difficulty,
                            appLanguage: settings.appLanguage,
                            language: settings.appLanguage.studyLanguage,
                            openAIModel: uniqueOptions.first?.id ?? StudySettings.defaultOpenAIModel,
                            notificationSound: settings.notificationSound,
                            customPrompt: settings.customPrompt,
                            intervalMinutes: settings.sanitizedIntervalMinutes,
                            maxHistoryCount: settings.sanitizedMaxHistoryCount,
                            isQuestionPublic: settings.isQuestionPublic,
                            studyCategories: settings.studyCategories,
                            selectedStudyCategoryID: settings.selectedStudyCategoryID
                        )
                    )
                }
            }
            log(.info, "OpenAI 모델 목록을 업데이트했습니다. count=\(openAIModelOptions.count)")
        } catch {
            openAIModelOptions = OpenAIModelOption.all
            log(.warning, "OpenAI 모델 목록 갱신 실패: \(error.localizedDescription)")
        }
    }

    @discardableResult
    private func refreshBackendStudyIfPossible(
        updateVisibleQuestion: Bool = true,
        preserveLocalSettings: Bool = true
    ) async -> Bool {
        guard let storedRegistration = settingsStore.loadRemotePushRegistration(),
              let registration = await registrationWithAccessToken(storedRegistration, reason: "state") else {
            return false
        }

        do {
            let recordsPage = try await remotePushBackendClient.fetchRecords(
                registration: registration,
                limit: settings.sanitizedMaxHistoryCount,
                offset: 0
            )
            let studyPage = try await remotePushBackendClient.fetchStudy(
                registration: registration,
                limit: 100,
                offset: 0
            )
            applyBackendStudyPage(studyPage)
            let pendingRecords = studyPage.studies.compactMap(\.pendingQuestion)
            applyBackendRecordsPage(
                recordsPage,
                pendingRecords: pendingRecords,
                updateVisibleQuestion: updateVisibleQuestion,
                preserveLocalQuestionState: preserveLocalSettings
            )
            statusMessage = updateVisibleQuestion ? strings.refreshed : statusMessage
            log(.info, "백엔드 기록 데이터를 동기화했습니다. records=\(recordsPage.records.count), pending=\(pendingRecords.count)")
            return true
        } catch {
            log(.warning, "백엔드 기록 데이터 동기화 실패: \(error.localizedDescription)")
            return false
        }
    }

    private func applyBackendStudyPage(_ studyPage: BackendStudyPage) {
        guard !isEditingSettings, !studyPage.studies.isEmpty else {
            return
        }

        let existingCategoriesByTopic = settings.studyCategories.reduce(into: [String: StudyCategory]()) { result, category in
            let key = Self.normalizedCategoryText(for: category.title)
            if result[key] == nil {
                result[key] = category
            }
        }
        let selectedTopicKey = settings
            .category(for: settings.selectedStudyCategoryID)
            .map { Self.normalizedCategoryText(for: $0.title) }

        let categories = studyPage.studies.map { room in
            let topicKey = Self.normalizedCategoryText(for: room.topic)
            let existing = existingCategoriesByTopic[topicKey]
            return StudyCategory(
                id: existing?.id ?? String(room.id),
                title: room.topic,
                difficulty: Difficulty(level: room.difficultyLevel),
                customPrompt: room.customPrompt,
                openAIModel: room.openAIModel,
                createdAt: existing?.createdAt ?? room.createdAt
            )
        }

        let selectedCategoryID = selectedTopicKey.flatMap { key in
            categories.first { Self.normalizedCategoryText(for: $0.title) == key }?.id
        } ?? categories.first(where: { $0.id == settings.selectedStudyCategoryID })?.id ?? categories.first?.id
        let selectedCategory = categories.first { $0.id == selectedCategoryID } ?? categories.first

        let nextSettings = normalizedSettings(
            StudySettings(
                topic: selectedCategory?.title ?? settings.topic,
                difficulty: selectedCategory?.difficulty ?? settings.difficulty,
                appLanguage: settings.appLanguage,
                language: settings.appLanguage.studyLanguage,
                openAIModel: selectedCategory?.openAIModel ?? settings.sanitizedOpenAIModel,
                notificationSound: settings.notificationSound,
                customPrompt: selectedCategory?.customPrompt ?? settings.customPrompt,
                intervalMinutes: settings.sanitizedIntervalMinutes,
                maxHistoryCount: settings.sanitizedMaxHistoryCount,
                isQuestionPublic: settings.isQuestionPublic,
                studyCategories: categories,
                selectedStudyCategoryID: selectedCategoryID
            )
        )

        settings = nextSettings
        savedSettings = nextSettings
        draftSettings = nextSettings
        settingsStore.saveSettings(nextSettings)
    }

    func fetchBackendStats(
        period: BackendStatsPeriod = .all,
        search: String = "",
        sort: BackendStatsSort = .level,
        startAt: Date? = nil,
        endAt: Date? = nil,
        limit: Int = 8,
        offset: Int = 0
    ) async {
        let requestID = UUID()
        backendStatsRequestID = requestID

        backendStatsErrorMessage = nil
        isBackendStatsLoading = true
        defer {
            if backendStatsRequestID == requestID {
                isBackendStatsLoading = false
            }
        }

        let trimmedSearch = search.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedLimit = max(1, min(limit, 100))
        let normalizedOffset = max(0, offset)

        guard let registration = await backendRegistrationForOpenAIRequests(reason: "stats") else {
            backendStatsErrorMessage = "백엔드 등록이 필요합니다. 네트워크 또는 설정을 확인하세요."
            log(.warning, "통계 조회를 위한 백엔드 등록이 없어 요청을 중단했습니다.")
            if backendStatsRequestID == requestID {
                isBackendStatsLoading = false
            }
            return
        }

        do {
            let stats = try await remotePushBackendClient.fetchStats(
                registration: registration,
                period: period,
                startAt: startAt,
                endAt: endAt,
                search: trimmedSearch,
                sort: sort,
                limit: normalizedLimit,
                offset: normalizedOffset
            )

            guard requestID == backendStatsRequestID else {
                return
            }

            backendStats = stats
            log(.info, "통계 조회 완료. topics=\(stats.topics.count), totalTopics=\(stats.totalTopics), totalResponses=\(stats.totalResponses), offset=\(stats.offset)")
        } catch {
            guard requestID == backendStatsRequestID else {
                return
            }

            if handlePageAccessError(error, page: .statistics) {
                backendStatsErrorMessage = strings.pageAccessDenied(strings.tabStatistics)
                return
            }

            backendStatsErrorMessage = backendErrorDisplayMessage(error, fallback: "통계 조회 실패")
            log(.warning, "백엔드 통계 조회 실패: \(error.localizedDescription)")
        }
    }

    func loadCommunityQuestions(reset: Bool = true, userInitiated: Bool = false) async {
        let requestID = UUID()
        let trimmedTopic = communitySearchText.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedOffset = reset ? 0 : communityOffset
        let limit = Self.communityQuestionPageSize

        if normalizedOffset > 0 && !canLoadMoreCommunityQuestions(currentCount: normalizedOffset) {
            return
        }

        communityQuestionLoadRequestID = requestID
        isLoadingCommunityQuestions = true
        communityErrorMessage = nil
        defer {
            if communityQuestionLoadRequestID == requestID {
                isLoadingCommunityQuestions = false
            }
        }

        do {
            guard let registration = await backendRegistrationForOpenAIRequests(reason: "community-feed") else {
                if userInitiated {
                    communityErrorMessage = strings.communityRequestFailed
                }
                return
            }

            let response = try await remotePushBackendClient.fetchPublicQuestions(
                registration: registration,
                topic: trimmedTopic.isEmpty ? nil : trimmedTopic,
                limit: limit,
                offset: normalizedOffset,
                excludeDeviceID: nil
            )

            guard communityQuestionLoadRequestID == requestID else {
                return
            }

            if reset {
                communityQuestions = response.questions
            } else {
                let existing = Set(communityQuestions.map(\.id))
                communityQuestions.append(contentsOf: response.questions.filter { !existing.contains($0.id) })
            }
            communityTotalCount = response.totalCount
            communityOffset = normalizedOffset + response.questions.count
            log(.info, "공개 질문 목록을 로드했습니다. count=\(response.questions.count), total=\(response.totalCount), offset=\(communityOffset)")
        } catch {
            guard communityQuestionLoadRequestID == requestID else {
                return
            }
            if reset {
                communityQuestions = []
                communityOffset = 0
                communityTotalCount = 0
            }
            if userInitiated {
                communityErrorMessage = communityErrorMessage(for: error)
            } else {
                communityErrorMessage = nil
            }
            log(.warning, "공개 질문 로드 실패: \(error.localizedDescription)")
        }
    }

    func loadNextCommunityPage() async {
        guard canLoadMoreCommunityQuestions(currentCount: communityOffset) else {
            return
        }

        await loadCommunityQuestions(reset: false, userInitiated: true)
    }

    func refreshCommunityQuestions(userInitiated: Bool = true) {
        Task {
            await loadCommunityQuestions(reset: true, userInitiated: userInitiated)
        }
    }

    func shouldLoadNextCommunityQuestion(after recordID: String) {
        guard let last = communityQuestions.last,
              last.id == recordID else {
            return
        }
        Task {
            await loadNextCommunityPage()
        }
    }

    var canLoadCommunityQuestions: Bool {
        communityOffset < communityTotalCount
    }

    private func canLoadMoreCommunityQuestions(currentCount: Int) -> Bool {
        if currentCount <= 0 {
            return communityTotalCount == 0 ? !communityQuestions.isEmpty : true
        }

        return currentCount < communityTotalCount
    }

    private func applyBackendRecordsPage(
        _ recordsPage: BackendRecordsPage,
        pendingRecords: [StudyRecord] = [],
        updateVisibleQuestion: Bool,
        preserveLocalQuestionState: Bool = true
    ) {
        guard !isEditingSettings else {
            log(.info, "설정 편집 중이어서 백엔드 기록 페이지 적용을 건너뛰었습니다.")
            return
        }

        let localCurrentQuestion = currentQuestion
        let localLastAnswer = lastAnswer
        let localGradingResult = gradingResult

        let mergedRecords = pendingRecords.reduce(recordsPage.records) { records, pendingRecord in
            mergeBackendRecord(pendingRecord, into: records)
        }
        settingsStore.replaceStudyRecords(mergedRecords)
        studyRecords = settingsStore.loadStudyRecords()

        guard updateVisibleQuestion else {
            if preserveLocalQuestionState {
                currentQuestion = localCurrentQuestion
                lastAnswer = localLastAnswer
                gradingResult = localGradingResult
                settingsStore.saveQuestion(localCurrentQuestion)
                settingsStore.saveLastAnswer(localLastAnswer)
                settingsStore.saveGradingResult(localGradingResult)
            }
            restartTimer()
            return
        }

        let visibleRecord = localCurrentQuestion.flatMap { studyRecord(matching: $0) } ??
            studyRecords
                .filter { $0.gradingResult == nil }
                .sorted { $0.question.createdAt > $1.question.createdAt }
                .first

        currentQuestion = visibleRecord?.question
        lastAnswer = visibleRecord?.answer ?? ""
        gradingResult = visibleRecord?.gradingResult
        settingsStore.saveQuestion(currentQuestion)
        settingsStore.saveLastAnswer(lastAnswer)
        settingsStore.saveGradingResult(gradingResult)
        restartTimer()
    }

    private func reloadPersistedState(restartTimerAfterReload: Bool = true) {
        let loadedSettings = settingsStore.loadSettings()
        let synchronizedLoadedSettings = synchronizedTopicCategories(for: loadedSettings)
        let loadedAPIKey = settingsStore.loadAPIKey().trimmingCharacters(in: .whitespacesAndNewlines)
        let loadedAPIKeyUpdatedAt = settingsStore.loadOpenAIAPIKeyUpdatedAt()
        let effectiveAPIKeyUpdatedAt = loadedAPIKeyUpdatedAt ?? (loadedAPIKey.isEmpty ? nil : Date())

        settings = synchronizedLoadedSettings
        currentQuestion = settingsStore.loadQuestion()
        lastAnswer = settingsStore.loadLastAnswer()
        gradingResult = settingsStore.loadGradingResult()
        isRunning = settingsStore.loadIsRunning()
        studyRecords = settingsStore.loadStudyRecords()
        apiKey = loadedAPIKey
        savedSettings = synchronizedLoadedSettings
        savedAPIKey = loadedAPIKey.trimmingCharacters(in: .whitespacesAndNewlines)
        lastAPIKeyUpdatedAt = effectiveAPIKeyUpdatedAt
        hasCompletedOnboarding = settingsStore.loadHasCompletedOnboarding()
        isCloudSyncEnabled = settingsStore.loadIsCloudSyncEnabled()
        cloudLastSyncedAt = settingsStore.loadCloudSyncStateUpdatedAt()
        loadAppLogPage(appLogPage)

        if !isEditingSettings {
            draftSettings = synchronizedLoadedSettings
            draftAPIKey = loadedAPIKey
        }

        hasAPIKeyError = loadedAPIKey.isEmpty
        if restartTimerAfterReload {
            restartTimer()
        }

        if loadedAPIKeyUpdatedAt == nil, !loadedAPIKey.isEmpty {
            settingsStore.saveOpenAIAPIKeyUpdatedAt(effectiveAPIKeyUpdatedAt)
        }
    }

    private func refreshStudyProgressFromStore() {
        currentQuestion = settingsStore.loadQuestion()
        lastAnswer = settingsStore.loadLastAnswer()
        gradingResult = settingsStore.loadGradingResult()
        studyRecords = settingsStore.loadStudyRecords()
    }

    private func showPendingQuestionLimitStatus(reason: String) {
        statusMessage = strings.pendingQuestionLimitTitle
        errorMessage = strings.pendingQuestionLimitMessage
        log(.warning, "미채점 질문이 \(Self.maxPendingQuestionCount)개라 \(reason)을 건너뛰었습니다.")
    }

    private func validateAPIKeyOnStartup() async {
        let trimmedAPIKey = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "startup-api-validation") else {
            if trimmedAPIKey.isEmpty {
                hasAPIKeyError = true
                errorMessage = strings.apiKeyEmptyDetailed
            }
            log(.warning, "백엔드 등록이 없어 시작 시 API 키 검증을 완료하지 못했습니다.")
            return
        }

        guard !trimmedAPIKey.isEmpty || isBackendOpenAIKeyConfigured else {
            hasAPIKeyError = true
            errorMessage = strings.apiKeyEmptyDetailed
            log(.warning, "시작 시 API 키 검증을 건너뛰었습니다. API 키가 비어 있습니다.")
            return
        }

        isValidatingAPIKey = true
        log(.info, "시작 시 백엔드에서 OpenAI API 키를 검증합니다.")

        do {
            try await updateBackendSettings(
                registration: registration,
                reason: "startup-api-validation",
                includeAPIKey: !trimmedAPIKey.isEmpty && !isBackendOpenAIKeyConfigured
            )
            _ = try await remotePushBackendClient.validateAPIKey(registration: registration)
            hasAPIKeyError = false
            if errorMessage?.contains("API 키") == true {
                errorMessage = nil
            }
            isBackendOpenAIKeyConfigured = true
            log(.info, "시작 시 백엔드 OpenAI API 키 검증에 성공했습니다.")
        } catch {
            handleOpenAIError(error)
        }

        isValidatingAPIKey = false
    }

    func beginSettingsEditing() {
        guard !isEditingSettings else {
            return
        }

        let syncedSettings = synchronizedTopicCategories(for: settings)
        settings = syncedSettings
        draftSettings = syncedSettings
        draftAPIKey = apiKey
        draftDebugBackendBaseURL = debugBackendBaseURL
        didReceiveCloudStateWhileEditing = false
        isEditingSettings = true
    }

    func cancelSettingsEditing() {
        guard isEditingSettings else {
            return
        }

        let shouldSyncAfterCancel = didReceiveCloudStateWhileEditing && hasUnsavedSettingsChanges
        settings = savedSettings
        apiKey = savedAPIKey
        debugBackendBaseURL = savedDebugBackendBaseURL
        draftSettings = savedSettings
        draftAPIKey = savedAPIKey
        draftDebugBackendBaseURL = savedDebugBackendBaseURL
        isEditingSettings = false
        didReceiveCloudStateWhileEditing = false

        if shouldSyncAfterCancel {
            Task {
                await syncCloudNow(updateVisibleQuestion: false)
            }
        }

    }

    func updateDraftAppLanguage(_ language: AppLanguage) {
        draftSettings.appLanguage = language
        draftSettings.language = language.studyLanguage
    }

    func setDraftQuestionPublicity(_ isQuestionPublic: Bool) {
        draftSettings.isQuestionPublic = isCommunitySignedIn && isQuestionPublic
    }

    func signInToCommunity() {
        Task {
            #if os(iOS)
            do {
                communityErrorMessage = nil
                let idToken = try await GoogleOAuthService().signIn()
                await signInToCommunity(idToken: idToken)
            } catch GoogleOAuthError.cancelled {
                communityErrorMessage = nil
                log(.info, "Google Login이 사용자에 의해 취소되었습니다.")
            } catch GoogleOAuthError.notConfigured {
                statusMessage = strings.googleLoginSetupRequired
                log(.warning, "Google Login 설정이 없습니다.")
            } catch {
                communityErrorMessage = communityErrorMessage(for: error)
                log(.warning, "Google Login 실패: \(error.localizedDescription)")
            }
            #else
            statusMessage = strings.googleLoginSetupRequired
            #endif
        }
    }

    func signInToCommunity(idToken: String) async {
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "google-login") else {
            communityErrorMessage = strings.communityRequestFailed
            return
        }

        do {
            let result = try await remotePushBackendClient.loginWithGoogle(
                registration: registration,
                idToken: idToken
            )
            applyCommunityProfile(result.profile)
            settingsStore.saveRemotePushRegistration(result.registration)
            isCommunitySignedIn = true
            settingsStore.saveIsCommunitySignedIn(true)
            await refreshBackendStudyIfPossible(
                updateVisibleQuestion: true,
                preserveLocalSettings: false
            )
            await loadCommunityQuestions(reset: true, userInitiated: true)
        } catch {
            communityErrorMessage = communityErrorMessage(for: error)
            log(.warning, "Google 로그인 실패: \(error.localizedDescription)")
        }
    }

    func requestEmailVerificationCode(email: String) async -> Bool {
        let normalizedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "email-code") else {
            communityErrorMessage = strings.communityRequestFailed
            return false
        }

        do {
            communityErrorMessage = nil
            _ = try await remotePushBackendClient.requestEmailVerificationCode(
                registration: registration,
                email: normalizedEmail
            )
            return true
        } catch {
            communityErrorMessage = communityErrorMessage(for: error)
            log(.warning, "Email 인증코드 요청 실패: \(error.localizedDescription)")
            return false
        }
    }

    func signInToCommunity(email: String, password: String, verificationCode: String? = nil) async -> EmailCommunitySignInResult {
        let normalizedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "email-login") else {
            communityErrorMessage = strings.communityRequestFailed
            return .failed
        }

        do {
            communityErrorMessage = nil
            let result = try await remotePushBackendClient.loginWithEmail(
                registration: registration,
                email: normalizedEmail,
                password: password,
                verificationCode: verificationCode
            )
            applyCommunityProfile(result.profile)
            settingsStore.saveRemotePushRegistration(result.registration)
            isCommunitySignedIn = true
            settingsStore.saveIsCommunitySignedIn(true)
            await refreshBackendStudyIfPossible(
                updateVisibleQuestion: true,
                preserveLocalSettings: false
            )
            await loadCommunityQuestions(reset: true, userInitiated: true)
            return .signedIn
        } catch {
            if let backendError = error as? RemotePushBackendError,
               backendError.requiresEmailVerification {
                communityErrorMessage = strings.emailVerificationRequired
                log(.info, "Email 로그인에 인증코드가 필요합니다.")
                return .verificationRequired
            }
            communityErrorMessage = communityErrorMessage(for: error)
            log(.warning, "Email 로그인 실패: \(error.localizedDescription)")
            return .failed
        }
    }

    func signOutFromCommunity() {
        isCommunitySignedIn = false
        communityProfile = nil
        settingsStore.saveIsCommunitySignedIn(false)
        communityQuestions = []
        communityOffset = 0
        communityTotalCount = 0
        communityErrorMessage = nil
        if settings.isQuestionPublic || draftSettings.isQuestionPublic {
            settings = settings.withQuestionPrivacy(false)
            draftSettings = draftSettings.withQuestionPrivacy(false)
            settingsStore.saveSettings(settings)
            savedSettings = normalizedSettings(settings)
            Task {
                await syncRemotePushScheduleIfPossible(reason: "community-logout")
            }
        }
        statusMessage = strings.communitySignedOut
    }

    func updateProfileAvatarSymbolName(_ symbolName: String) {
        profileAvatarSymbolName = symbolName
        settingsStore.saveProfileAvatarSymbolName(symbolName)
    }

    func updateProfileAvatarColorSeed(_ seed: String) {
        profileAvatarColorSeed = seed
        settingsStore.saveProfileAvatarColorSeed(seed)
    }

    func updateProfileAvatarImageData(_ data: Data?) {
        profileAvatarImageData = data
        settingsStore.saveProfileAvatarImageData(data)
    }

    func updateCommunityProfileAvatar(symbolName: String? = nil, colorSeed: String? = nil) {
        if let symbolName {
            updateProfileAvatarSymbolName(symbolName)
        }
        if let colorSeed {
            updateProfileAvatarColorSeed(colorSeed)
        }
        guard isCommunitySignedIn else {
            return
        }

        let nextSymbolName = symbolName ?? profileAvatarSymbolName
        let nextColorSeed = colorSeed ?? profileAvatarColorSeed
        Task {
            await updateCommunityProfile(
                displayName: communityProfile?.displayName ?? "",
                bio: communityProfile?.bio ?? "",
                avatarSymbolName: nextSymbolName,
                avatarColorSeed: nextColorSeed,
                pageAccess: communityProfile?.pageAccess
            )
        }
    }

    func loadCommunityProfile() async {
        guard isCommunitySignedIn,
              let registration = await backendRegistrationForOpenAIRequests(reason: "community-profile") else {
            return
        }

        do {
            let profile = try await remotePushBackendClient.fetchMyProfile(registration: registration)
            applyCommunityProfile(profile)
        } catch {
            log(.warning, "커뮤니티 프로필 조회 실패: \(error.localizedDescription)")
        }
    }

    func updateCommunityProfile(
        displayName: String,
        bio: String = "",
        avatarSymbolName: String? = nil,
        avatarColorSeed: String? = nil,
        pageAccess: CommunityPageAccess? = nil
    ) async {
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "community-profile-update") else {
            return
        }
        isUpdatingCommunityProfile = true
        defer {
            isUpdatingCommunityProfile = false
        }

        do {
            let profile = try await remotePushBackendClient.updateMyProfile(
                registration: registration,
                displayName: displayName,
                bio: bio,
                avatarSymbolName: avatarSymbolName,
                avatarColorSeed: avatarColorSeed,
                pageAccess: pageAccess
            )
            settingsStore.saveCommunityProfileDisplayName(displayName)
            applyCommunityProfile(profile)
        } catch {
            communityErrorMessage = communityErrorMessage(for: error)
            log(.warning, "커뮤니티 프로필 저장 실패: \(error.localizedDescription)")
        }
    }

    private func applyCommunityProfile(_ profile: CommunityUserProfile) {
        let cachedDisplayName = settingsStore.loadCommunityProfileDisplayName()?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let incomingDisplayName = profile.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        let cachedProfileID = settingsStore.loadCommunityProfileID()
        let shouldPreserveCachedName = cachedProfileID == profile.id
            && !cachedDisplayName.isEmpty
            && cachedDisplayName != incomingDisplayName
        let resolvedProfile = shouldPreserveCachedName
            ? CommunityUserProfile(
                id: profile.id,
                displayName: cachedDisplayName,
                bio: profile.bio,
                avatarURL: profile.avatarURL,
                avatarSymbolName: profile.avatarSymbolName,
                avatarColorSeed: profile.avatarColorSeed,
                pageAccess: profile.pageAccess
            )
            : profile
        communityProfile = resolvedProfile
        settingsStore.saveCommunityProfileID(resolvedProfile.id)
        settingsStore.saveCommunityProfileDisplayName(resolvedProfile.displayName)
        updateProfileAvatarSymbolName(profile.avatarSymbolName)
        updateProfileAvatarColorSeed(profile.avatarColorSeed)
    }

    func withdrawCommunityAccount() async {
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "community-withdraw") else {
            communityErrorMessage = strings.communityRequestFailed
            return
        }

        isWithdrawingCommunityAccount = true
        defer {
            isWithdrawingCommunityAccount = false
        }

        do {
            let updatedRegistration = try await remotePushBackendClient.withdrawMyProfile(registration: registration)
            settingsStore.saveRemotePushRegistration(updatedRegistration)
            signOutFromCommunity()
            settingsStore.saveCommunityProfileID(nil)
            settingsStore.saveCommunityProfileDisplayName("")
            statusMessage = strings.accountDeleted
        } catch {
            communityErrorMessage = communityErrorMessage(for: error)
            log(.warning, "커뮤니티 탈퇴 실패: \(error.localizedDescription)")
        }
    }

    func reportCommunityQuestion(_ question: CommunityQuestion, reason: String, message: String = "") async {
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "community-report") else {
            communityErrorMessage = strings.communityRequestFailed
            return
        }

        do {
            try await remotePushBackendClient.reportCommunityQuestion(
                registration: registration,
                questionID: question.id,
                reason: reason,
                message: message
            )
            statusMessage = strings.reportSubmitted
        } catch {
            communityErrorMessage = communityErrorMessage(for: error)
            log(.warning, "공개 질문 신고 실패: \(error.localizedDescription)")
        }
    }

    func setCommunityQuestionLike(_ question: CommunityQuestion, isLiked: Bool) async {
        guard isCommunitySignedIn else {
            return
        }

        guard let registration = await backendRegistrationForOpenAIRequests(reason: "community-like") else {
            communityErrorMessage = strings.communityRequestFailed
            return
        }

        let previous = communityQuestions.first(where: { $0.id == question.id })
        updateCommunityQuestionLike(id: question.id, isLiked: isLiked, likeCount: max(0, question.likeCount + (isLiked ? 1 : -1)))

        do {
            let state = try await remotePushBackendClient.setCommunityQuestionLike(
                registration: registration,
                questionID: question.id,
                isLiked: isLiked
            )
            updateCommunityQuestionLike(id: question.id, isLiked: state.isLikedByMe, likeCount: state.likeCount)
        } catch {
            if let previous {
                updateCommunityQuestionLike(id: question.id, isLiked: previous.isLikedByMe, likeCount: previous.likeCount)
            }
            communityErrorMessage = communityErrorMessage(for: error)
            log(.warning, "공개 질문 좋아요 처리 실패: \(error.localizedDescription)")
        }
    }

    func loadCommunityQuestionComments(questionID: String, limit: Int = 30, offset: Int = 0) async -> CommunityCommentsResponse? {
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "community-comments") else {
            communityErrorMessage = strings.communityRequestFailed
            return nil
        }

        do {
            return try await remotePushBackendClient.fetchCommunityQuestionComments(
                registration: registration,
                questionID: questionID,
                limit: limit,
                offset: offset
            )
        } catch {
            communityErrorMessage = communityErrorMessage(for: error)
            log(.warning, "공개 질문 댓글 로드 실패: \(error.localizedDescription)")
            return nil
        }
    }

    func loadCommunityQuestionDetail(questionID: String) async -> CommunityQuestion? {
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "community-question-detail") else {
            communityErrorMessage = strings.communityRequestFailed
            return nil
        }

        do {
            let question = try await remotePushBackendClient.fetchPublicQuestion(
                registration: registration,
                questionID: questionID
            )
            if let index = communityQuestions.firstIndex(where: { $0.id == questionID }) {
                communityQuestions[index] = question
            }
            return question
        } catch {
            communityErrorMessage = communityErrorMessage(for: error)
            log(.warning, "공개 질문 상세 로드 실패: \(error.localizedDescription)")
            return nil
        }
    }

    func createCommunityQuestionComment(questionID: String, body: String) async -> CommunityQuestionComment? {
        guard isCommunitySignedIn else {
            return nil
        }

        guard let registration = await backendRegistrationForOpenAIRequests(reason: "community-comment-create") else {
            communityErrorMessage = strings.communityRequestFailed
            return nil
        }

        do {
            let comment = try await remotePushBackendClient.createCommunityQuestionComment(
                registration: registration,
                questionID: questionID,
                body: body
            )
            if let index = communityQuestions.firstIndex(where: { $0.id == questionID }) {
                communityQuestions[index].commentCount += 1
            }
            return comment
        } catch {
            communityErrorMessage = communityErrorMessage(for: error)
            log(.warning, "공개 질문 댓글 작성 실패: \(error.localizedDescription)")
            return nil
        }
    }

    private func updateCommunityQuestionLike(id: String, isLiked: Bool, likeCount: Int) {
        guard let index = communityQuestions.firstIndex(where: { $0.id == id }) else {
            return
        }

        communityQuestions[index].isLikedByMe = isLiked
        communityQuestions[index].likeCount = likeCount
    }

    func setDraftNotificationSound(_ sound: NotificationSoundOption, preview: Bool = true) {
        draftSettings.notificationSound = sound

        guard preview else {
            return
        }

        notificationService.playPreview(sound: sound)
        statusMessage = sound == .none
            ? "알림음을 없음으로 설정했습니다."
            : "\(sound.displayName(language: draftSettings.appLanguage)) 알림음을 재생했습니다."
    }

    func applyClipboardOpenAIAPIKey() {
        clipboardPasteRequestID += 1
        let requestID = clipboardPasteRequestID
        Task { [weak self] in
            guard let self else {
                return
            }

            let key = await readClipboardOpenAIAPIKeyForSettingsPaste()
            await MainActor.run {
                guard requestID == self.clipboardPasteRequestID else {
                    return
                }

                if let key {
                    self.setDraftAPIKey(key)
                } else {
                    self.statusMessage = strings.openAIAPIKeyMissing
                    self.errorMessage = strings.openAIAPIKeyMissing
                }
            }
        }
    }

    @discardableResult
    func applyPastedOpenAIAPIKeyCandidates(_ values: [String]) -> Bool {
        for value in values {
            if let key = Self.extractOpenAIAPIKey(from: value) {
                setDraftAPIKey(key)
                return true
            }
        }

        statusMessage = strings.openAIAPIKeyMissing
        errorMessage = strings.openAIAPIKeyMissing
        return false
    }

    func readClipboardOpenAIAPIKeyForSettingsPaste() async -> String? {
        statusMessage = strings.pasteboardChecking
        errorMessage = nil
        let preChangeCount = currentClipboardChangeCount()

        if let key = await fetchClipboardOpenAIAPIKeyWithRetry(
            maxAttempts: Self.clipboardQuickReadAttempts,
            intervalMilliseconds: Self.clipboardQuickReadIntervalMilliseconds,
            requiredChangeCount: preChangeCount
        ) {
            statusMessage = nil
            errorMessage = nil
            return key
        }

        if let key = readClipboardOpenAIAPIKeyImmediate() {
            statusMessage = nil
            errorMessage = nil
            return key
        }

        if let key = await readClipboardOpenAIAPIKeyForExternalPaste(
            maxAttempts: Self.clipboardFallbackAttempts,
            intervalMilliseconds: Self.clipboardSettingsReadIntervalMilliseconds,
            requiredChangeCount: preChangeCount
        ) {
            statusMessage = nil
            errorMessage = nil
            return key
        }

        statusMessage = strings.openAIAPIKeyMissing
        errorMessage = strings.openAIAPIKeyMissing
        return nil
    }

    func readClipboardOpenAIAPIKeyForExternalPaste(
        maxAttempts: Int = 80,
        intervalMilliseconds: UInt64 = 24,
        requiredChangeCount: Int? = nil
    ) async -> String? {
        statusMessage = strings.pasteboardChecking

        if let key = readClipboardOpenAIAPIKeyImmediate() {
            errorMessage = nil
            statusMessage = nil
            return key
        }

        let preChangeCount = currentClipboardChangeCount()
        if let key = await fetchClipboardOpenAIAPIKeyWithRetry(
            maxAttempts: 16,
            intervalMilliseconds: Self.clipboardStickyReadIntervalMilliseconds,
            requiredChangeCount: preChangeCount
        ) {
            errorMessage = nil
            statusMessage = nil
            return key
        }

        guard let key = await fetchClipboardOpenAIAPIKeyWithRetry(
            maxAttempts: maxAttempts,
            intervalMilliseconds: intervalMilliseconds,
            requiredChangeCount: requiredChangeCount
        ) else {
            statusMessage = strings.openAIAPIKeyMissing
            errorMessage = strings.openAIAPIKeyMissing
            return nil
        }

        errorMessage = nil
        statusMessage = nil
        return key
    }

    func readClipboardOpenAIAPIKeyForQuickPaste() async -> String? {
        return await readClipboardOpenAIAPIKeyForSettingsPaste()
    }

    func readClipboardOpenAIAPIKeyForLongPaste() async -> String? {
        statusMessage = strings.pasteboardChecking

        if let key = readClipboardOpenAIAPIKey() {
            errorMessage = nil
            statusMessage = nil
            return key
        }

        let preChangeCount = currentClipboardChangeCount()
        if let key = await fetchClipboardOpenAIAPIKeyWithRetry(
            maxAttempts: 16,
            intervalMilliseconds: Self.clipboardSettingsReadIntervalMilliseconds,
            requiredChangeCount: preChangeCount
        ) {
            statusMessage = nil
            errorMessage = nil
            return key
        }

        guard let key = await readClipboardOpenAIAPIKeyForExternalPaste() else {
            statusMessage = strings.openAIAPIKeyMissing
            errorMessage = strings.openAIAPIKeyMissing
            return nil
        }

        return key
    }

    /// 즉시 붙여넣기 동작에서 사용하는 편의 래퍼입니다.
    /// 일부 뷰에서 호출명이 누락된 경로를 방지합니다.
    func readClipboardOpenAIAPIKey() -> String? {
        readClipboardOpenAIAPIKeyImmediate()
    }

    private func setDraftAPIKey(_ key: String) {
        let trimmedKey = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedKey.isEmpty else {
            return
        }

        draftAPIKey = trimmedKey
        statusMessage = strings.openAIAPIKeyCopied
        errorMessage = nil
    }

    func readClipboardOpenAIAPIKeyImmediate() -> String? {
        if let key = fetchClipboardOpenAIAPIKey() {
            let trimmedKey = key.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmedKey.isEmpty {
                return trimmedKey
            }
        }

        return nil
    }

    func fetchClipboardOpenAIAPIKeyWithRetry(
        maxAttempts: Int = 80,
        intervalMilliseconds: UInt64 = 16,
        requiredChangeCount: Int? = nil
    ) async -> String? {
        guard maxAttempts > 0 else {
            return nil
        }

        for attempt in 0..<maxAttempts {
            if let key = fetchClipboardOpenAIAPIKey() {
                return key
            }

            let isLastAttempt = attempt == maxAttempts - 1
            guard !isLastAttempt else {
                return nil
            }

            if let requiredChangeCount {
                if currentClipboardChangeCount() == requiredChangeCount {
                    let baseInterval = max(Self.clipboardStickyReadIntervalMilliseconds, 3)
                    let shortInterval = min(
                        baseInterval * UInt64(attempt + 1),
                        40
                    )
                    try? await Task.sleep(nanoseconds: shortInterval * 1_000_000)
                    continue
                }
            }

            let pollingInterval = min(
                intervalMilliseconds * UInt64(attempt + 1),
                160
            )
            try? await Task.sleep(nanoseconds: max(pollingInterval, 6) * 1_000_000)
        }

        return nil
    }

    private func currentClipboardChangeCount() -> Int {
        #if os(macOS)
        return Int(NSPasteboard.general.changeCount)
        #elseif os(iOS)
        return UIPasteboard.general.changeCount
        #else
        return 0
        #endif
    }

    func fetchClipboardOpenAIAPIKey() -> String? {
        #if os(macOS)
        let candidates: [NSPasteboard.PasteboardType] = [
            .string,
            .init("public.utf8-plain-text"),
            .init("public.text"),
            .init("public.utf16-plain-text"),
            .init("public.utf16-external-plain-text"),
            .init("public.html"),
            .init("public.rtf")
        ]

        for type in candidates {
            if let value = NSPasteboard.general.string(forType: type) {
                if let extracted = Self.extractOpenAIAPIKey(from: value) {
                    return extracted
                }
            }
        }

        for item in NSPasteboard.general.pasteboardItems ?? [] {
            for type in item.types {
                if let value = Self.extractString(fromPasteboardItem: item, type: type),
                   let extracted = Self.extractOpenAIAPIKey(from: value) {
                    return extracted
                }
            }
        }

        let classes: [NSPasteboardReading.Type] = [NSString.self, NSAttributedString.self]
        if let objects = NSPasteboard.general.readObjects(forClasses: classes, options: nil),
           let extracted = objects
                .compactMap({ object -> String? in
                    if let string = object as? String {
                        return Self.extractOpenAIAPIKey(from: string)
                    }
                    if let attributed = object as? NSAttributedString {
                        return Self.extractOpenAIAPIKey(from: attributed.string)
                    }
                    return nil
                })
                .first(where: { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) {
            return extracted
        }

        return nil
        #elseif os(iOS)
        if let directString = UIPasteboard.general.string,
           let extracted = Self.extractOpenAIAPIKey(from: directString) {
            return extracted
        }

        let candidates: [String] = [
            UTType.text.identifier,
            UTType.plainText.identifier,
            UTType.html.identifier,
            UTType.utf8PlainText.identifier,
            "public.text",
            "public.utf16-plain-text",
            "public.utf16-external-plain-text",
            UTType.utf16PlainText.identifier,
            UTType.rtf.identifier,
            "public.url",
            "public.url-name"
        ]

        for item in UIPasteboard.general.items {
            for value in item.values {
                if let extracted = Self.extractOpenAIAPIKeyFromNestedClipboardValue(value) {
                    return extracted
                }
            }
        }

        for type in candidates {
            if let value = UIPasteboard.general.value(forPasteboardType: type) {
                if let extracted = Self.extractOpenAIAPIKeyFromNestedClipboardValue(value) {
                    return extracted
                }
            }

            if let data = UIPasteboard.general.data(forPasteboardType: type),
               let dataText = String(data: data, encoding: .utf8),
               let extracted = Self.extractOpenAIAPIKey(from: dataText) {
                return extracted
            }

            if let data = UIPasteboard.general.data(forPasteboardType: type),
               let extracted = Self.extractOpenAIAPIKeyFromNestedData(data) {
                return extracted
            }
        }

        return nil
        #else
        return nil
        #endif
    }

    #if os(iOS)
    private static func extractOpenAIAPIKey(fromClipboardValue value: Any) -> String? {
        if let valueString = value as? String,
           let extracted = extractOpenAIAPIKey(from: valueString) {
            return extracted
        }

        if let url = value as? URL,
           let extracted = extractOpenAIAPIKey(from: url.absoluteString) {
            return extracted
        }

        if let data = value as? Data {
            if let utf8Text = String(data: data, encoding: .utf8),
               let extracted = extractOpenAIAPIKey(from: utf8Text) {
                return extracted
            }

            if let utf16Text = String(data: data, encoding: .utf16LittleEndian),
               let extracted = extractOpenAIAPIKey(from: utf16Text) {
                return extracted
            }

            if let utf16Text = String(data: data, encoding: .utf16BigEndian),
               let extracted = extractOpenAIAPIKey(from: utf16Text) {
                return extracted
            }

            if let asciiText = String(data: data, encoding: .ascii),
               let extracted = extractOpenAIAPIKey(from: asciiText) {
                return extracted
            }
        }

        return nil
    }

    private static func extractOpenAIAPIKeyFromNestedClipboardValue(_ value: Any) -> String? {
        if let found = extractOpenAIAPIKey(fromClipboardValue: value) {
            return found
        }

        if let arrayValue = value as? [Any] {
            for element in arrayValue {
                if let found = extractOpenAIAPIKeyFromNestedClipboardValue(element) {
                    return found
                }
            }
        }

        if let dictValue = value as? [String: Any] {
            for element in dictValue.values {
                if let found = extractOpenAIAPIKeyFromNestedClipboardValue(element) {
                    return found
                }
            }
        }

        return nil
    }

    private static func extractOpenAIAPIKeyFromNestedData(_ data: Data) -> String? {
        let encodings: [String.Encoding] = [.utf8, .utf16LittleEndian, .utf16BigEndian, .ascii]

        for encoding in encodings {
            if let text = String(data: data, encoding: encoding),
               let extracted = extractOpenAIAPIKey(from: text) {
                return extracted
            }
        }

        return nil
    }
    #endif

    #if os(macOS)
    private static func extractString(fromPasteboardItem item: NSPasteboardItem, type: NSPasteboard.PasteboardType) -> String? {
        if let value = item.string(forType: type), !value.isEmpty {
            return value
        }

        guard let data = item.data(forType: type) else {
            return nil
        }

        if let text = String(data: data, encoding: .utf8), !text.isEmpty {
            return text
        }

        if let text = String(data: data, encoding: .utf16LittleEndian), !text.isEmpty {
            return text
        }

        if let text = String(data: data, encoding: .utf16BigEndian), !text.isEmpty {
            return text
        }

        return nil
    }
    #endif

    nonisolated static func extractOpenAIAPIKey(from text: String) -> String? {
        let normalized = text
            .replacingOccurrences(of: "`", with: " ")
            .replacingOccurrences(of: "\"", with: " ")
            .replacingOccurrences(of: "'", with: " ")
            .replacingOccurrences(of: "“", with: " ")
            .replacingOccurrences(of: "”", with: " ")
            .replacingOccurrences(of: "<", with: " ")
            .replacingOccurrences(of: ">", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\u{200b}", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)

        guard !normalized.isEmpty else {
            return nil
        }

        let candidateSeparators = CharacterSet(charactersIn: " \t\n\r.,:;()[]{}<>/\\\"'`~!@#$%^&*+=|?:;<>[]{}")
        let tokenCandidates = normalized
            .components(separatedBy: candidateSeparators)
            .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }

        for token in tokenCandidates {
            if (token.hasPrefix("sk-proj-") || token.hasPrefix("sk-")) && token.count >= 20 {
                return token
            }
        }

        let patterns = [
            "sk-(?:proj-)?[A-Za-z0-9_-]{20,}",
            "sk-proj-[A-Za-z0-9_-]{20,}",
            "sk-[A-Za-z0-9_-]{20,}"
        ]

        for pattern in patterns {
            guard let regex = try? NSRegularExpression(pattern: pattern, options: []),
                  let match = regex.firstMatch(
                      in: normalized,
                      options: [],
                      range: NSRange(location: 0, length: normalized.utf16.count)
                  ) else {
                continue
            }

            let start = String.Index(utf16Offset: match.range.location, in: normalized)
            let end = String.Index(utf16Offset: match.range.location + match.range.length, in: normalized)
            let extracted = String(normalized[start..<end]).trimmingCharacters(in: .whitespacesAndNewlines)

            if !extracted.isEmpty {
                return extracted
            }
        }

        if let tokenRegex = try? NSRegularExpression(pattern: "[A-Za-z0-9_-]+", options: []) {
            let tokenRange = NSRange(location: 0, length: normalized.utf16.count)
            let tokenMatches = tokenRegex.matches(in: normalized, options: [], range: tokenRange)
            for token in tokenMatches {
                let start = String.Index(utf16Offset: token.range.location, in: normalized)
                let end = String.Index(utf16Offset: token.range.location + token.range.length, in: normalized)
                let tokenText = String(normalized[start..<end])

                if (tokenText.hasPrefix("sk-proj-") || tokenText.hasPrefix("sk-")) && tokenText.count >= 20 {
                    return tokenText
                }
            }
        }

        let trimmed = normalized.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.hasPrefix("sk-") && trimmed.count >= 20 ? trimmed : nil
    }

    func saveSettings() {
        persistSettings(
            activeSettingsForEditing,
            apiKey: activeAPIKeyForEditing
        )
    }

    func addStudyCategory(
        _ title: String,
        difficulty: Difficulty? = nil,
        customPrompt: String? = nil,
        openAIModel: String? = nil
    ) {
        let raw = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else {
            return
        }

        let nextCategory = StudyCategory(
            title: raw,
            difficulty: difficulty ?? settings.difficulty,
            customPrompt: customPrompt ?? settings.customPrompt,
            openAIModel: openAIModel ?? settings.sanitizedOpenAIModel
        )
        let nextSettings = StudySettings(
            topic: settings.topic,
            difficulty: settings.difficulty,
            appLanguage: settings.appLanguage,
            language: settings.appLanguage.studyLanguage,
            openAIModel: settings.sanitizedOpenAIModel,
            notificationSound: settings.notificationSound,
            customPrompt: settings.customPrompt,
            intervalMinutes: settings.sanitizedIntervalMinutes,
            maxHistoryCount: settings.sanitizedMaxHistoryCount,
            isQuestionPublic: settings.isQuestionPublic,
            studyCategories: settings.studyCategories + [nextCategory],
            selectedStudyCategoryID: settings.selectedStudyCategoryID ?? nextCategory.id
        )

        persistSettings(nextSettings, apiKey: apiKey)
        if settings.selectedStudyCategoryID == nil {
            activateStudyContext(forTopic: nextSettings.topic)
        }
        statusMessage = nil
    }

    func updateStudyCategory(
        id: String,
        title: String,
        difficulty: Difficulty,
        customPrompt: String,
        openAIModel: String
    ) {
        let raw = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else {
            return
        }

        let categories = settings.studyCategories.map { category in
            guard category.id == id else {
                return category
            }

            return StudyCategory(
                id: category.id,
                title: raw,
                difficulty: difficulty,
                customPrompt: customPrompt,
                openAIModel: openAIModel,
                createdAt: category.createdAt
            )
        }

        let editedCategory = categories.first { $0.id == id }

        let nextSettings = StudySettings(
            topic: settings.selectedStudyCategoryID == id ? raw : settings.topic,
            difficulty: settings.selectedStudyCategoryID == id ? difficulty : settings.difficulty,
            appLanguage: settings.appLanguage,
            language: settings.appLanguage.studyLanguage,
            openAIModel: settings.selectedStudyCategoryID == id ? (editedCategory?.sanitizedOpenAIModel ?? openAIModel) : settings.sanitizedOpenAIModel,
            notificationSound: settings.notificationSound,
            customPrompt: settings.selectedStudyCategoryID == id ? customPrompt : settings.customPrompt,
            intervalMinutes: settings.sanitizedIntervalMinutes,
            maxHistoryCount: settings.sanitizedMaxHistoryCount,
            isQuestionPublic: settings.isQuestionPublic,
            studyCategories: categories,
            selectedStudyCategoryID: settings.selectedStudyCategoryID == id ? id : settings.selectedStudyCategoryID
        )

        persistSettings(nextSettings, apiKey: apiKey)
        if nextSettings.selectedStudyCategoryID == id {
            activateStudyContext(forTopic: nextSettings.topic)
        }
        statusMessage = nil
    }

    func activateStudyCategory(_ categoryID: String) {
        let categories = synchronizedTopicCategories(for: settings).studyCategories
        guard let targetCategory = categories.first(where: { $0.id == categoryID }) else {
            return
        }

        let nextSettings = settings.withSelectedCategoryID(targetCategory.id)
        persistSettings(nextSettings, apiKey: apiKey)
        activateStudyContext(forTopic: nextSettings.topic)
        statusMessage = nil
    }

    func openStudyCategory(_ categoryID: String) async {
        let categories = synchronizedTopicCategories(for: settings).studyCategories
        guard let targetCategory = categories.first(where: { $0.id == categoryID }) ?? categories.first else {
            return
        }

        await refreshBackendStudyIfPossible(updateVisibleQuestion: false)

        if settings.selectedStudyCategoryID != targetCategory.id {
            persistSettings(settings.withSelectedCategoryID(targetCategory.id), apiKey: apiKey)
        }

        if let record = preferredPendingRecord(for: targetCategory) {
            notificationLandingMessage = nil
            currentQuestion = record.question
            lastAnswer = record.answer ?? ""
            gradingResult = record.gradingResult
            settingsStore.saveQuestion(record.question)
            settingsStore.saveLastAnswer(record.answer ?? "")
            settingsStore.saveGradingResult(record.gradingResult)
        }

        showStudyScreen(categoryID: targetCategory.id)
    }

    func deleteStudyCategory(id: String) {
        guard let index = studyCategoriesForDisplay.firstIndex(where: { $0.id == id }) else {
            return
        }

        deleteStudyCategories(at: IndexSet(integer: index))
    }

    func deleteStudyCategories(at offsets: IndexSet) {
        let displayCategories = studyCategoriesForDisplay
        let idsToDelete = Set(offsets.compactMap { index in
            displayCategories.indices.contains(index) ? displayCategories[index].id : nil
        })
        guard !idsToDelete.isEmpty else {
            return
        }

        let currentSelectedID = settings.selectedStudyCategoryID
        let didDeleteActiveCategory = currentSelectedID.map { idsToDelete.contains($0) } ?? false
        let categories = settings.studyCategories.filter { !idsToDelete.contains($0.id) }
        let nextSelectedID: String?
        if didDeleteActiveCategory {
            nextSelectedID = categories.first?.id
        } else if let currentSelectedID,
                  categories.contains(where: { $0.id == currentSelectedID }) {
            nextSelectedID = currentSelectedID
        } else {
            nextSelectedID = categories.first?.id
        }

        let selectedCategory = nextSelectedID.flatMap { selectedID in
            categories.first { $0.id == selectedID }
        }

        let nextSettings = StudySettings(
            topic: selectedCategory?.normalizedTitle ?? settings.topic,
            difficulty: selectedCategory?.difficulty ?? settings.difficulty,
            appLanguage: settings.appLanguage,
            language: settings.appLanguage.studyLanguage,
            openAIModel: selectedCategory?.sanitizedOpenAIModel ?? settings.sanitizedOpenAIModel,
            notificationSound: settings.notificationSound,
            customPrompt: selectedCategory?.normalizedCustomPrompt ?? settings.customPrompt,
            intervalMinutes: settings.sanitizedIntervalMinutes,
            maxHistoryCount: settings.sanitizedMaxHistoryCount,
            isQuestionPublic: settings.isQuestionPublic,
            studyCategories: categories,
            selectedStudyCategoryID: nextSelectedID
        )

        persistSettings(nextSettings, apiKey: apiKey)
        if didDeleteActiveCategory {
            if let selectedCategory {
                activateStudyContext(forTopic: selectedCategory.title)
            } else {
                currentQuestion = nil
                lastAnswer = ""
                gradingResult = nil
                settingsStore.saveQuestion(nil)
                settingsStore.saveLastAnswer("")
                settingsStore.saveGradingResult(nil)
            }
        }
    }

    func moveStudyCategories(from source: IndexSet, to destination: Int) {
        var categories = settings.studyCategories
        categories.move(fromOffsets: source, toOffset: destination)

        let nextSettings = StudySettings(
            topic: settings.topic,
            difficulty: settings.difficulty,
            appLanguage: settings.appLanguage,
            language: settings.appLanguage.studyLanguage,
            openAIModel: settings.activeCategory?.sanitizedOpenAIModel ?? settings.sanitizedOpenAIModel,
            notificationSound: settings.notificationSound,
            customPrompt: settings.customPrompt,
            intervalMinutes: settings.sanitizedIntervalMinutes,
            maxHistoryCount: settings.sanitizedMaxHistoryCount,
            isQuestionPublic: settings.isQuestionPublic,
            studyCategories: categories,
            selectedStudyCategoryID: settings.selectedStudyCategoryID
        )

        persistSettings(nextSettings, apiKey: apiKey)
    }

    func selectStudyCategory(_ categoryID: String) {
        let categories = synchronizedTopicCategories(for: settings).studyCategories
        guard let targetCategoryID = categories.first(where: { $0.id == categoryID })?.id ?? categories.first?.id else {
            return
        }

        let nextSettings = settings.withSelectedCategoryID(targetCategoryID)
        persistSettings(nextSettings, apiKey: apiKey)
        activateStudyContext(forTopic: nextSettings.topic)
        showStudyScreen(categoryID: targetCategoryID)
    }

    func completeOnboarding(settings pendingSettings: StudySettings, apiKey pendingAPIKey: String) async {
        persistSettings(
            pendingSettings,
            apiKey: pendingAPIKey
        )
        settingsStore.saveHasCompletedOnboarding(true)
        hasCompletedOnboarding = true
        #if os(iOS)
        selectedTab = .home
        #else
        selectedTab = .study
        #endif
        markCloudDataChanged()

        let trimmedAPIKey = pendingAPIKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedAPIKey.isEmpty else {
            isRunning = false
            settingsStore.saveIsRunning(false)
            hasAPIKeyError = true
            errorMessage = strings.apiKeyEmptyDetailed
            statusMessage = strings.onboardingCompletedWithoutAPIKey
            log(.warning, "온보딩을 완료했지만 API 키가 비어 있어 타이머를 일시정지했습니다.")
            restartTimer()
            return
        }

        _ = await notificationService.requestAuthorizationIfNeeded(language: settings.appLanguage)
        isValidatingAPIKey = true
        statusMessage = strings.apiKeyCheckingAfterOnboarding
        errorMessage = nil

        do {
            guard let registration = await backendRegistrationForOpenAIRequests(reason: "onboarding-api-validation") else {
                throw RemotePushBackendError.invalidResponse
            }
            try await updateBackendSettings(
                registration: registration,
                reason: "onboarding-api-validation",
                includeAPIKey: true
            )
            _ = try await remotePushBackendClient.validateAPIKey(registration: registration)
            hasAPIKeyError = false
            statusMessage = strings.onboardingCompleted
            isBackendOpenAIKeyConfigured = true
            log(.info, "온보딩 완료 후 백엔드 OpenAI API 키 검증에 성공했습니다.")
        } catch {
            if handlePageAccessError(error, page: .studyDetail) {
                return
            }
            handleOpenAIError(error)
            statusMessage = nil
        }

        isValidatingAPIKey = false
        restartTimer()
    }

    func skipOnboarding() {
        settingsStore.saveHasCompletedOnboarding(true)
        hasCompletedOnboarding = true
        selectedTab = .settings

        if apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            isRunning = false
            settingsStore.saveIsRunning(false)
            hasAPIKeyError = true
            errorMessage = strings.apiKeyEmptyDetailed
        }

        statusMessage = strings.onboardingSkipped
        log(.info, "온보딩을 나중에 설정하도록 건너뛰었습니다.")
        markCloudDataChanged()
        restartTimer()
    }

    private func persistSettings(
        _ pendingSettings: StudySettings,
        apiKey pendingAPIKey: String
    ) {
        let profileSettings = settingsWithResolvedStudyProfile(from: pendingSettings)
        let synchronizedSettings = synchronizedTopicCategories(
            for: profileSettings,
            includeResolvedTopicCategory: false
        )
        var sanitizedSettings = normalizedSettings(synchronizedSettings)
        if !isCommunitySignedIn {
            sanitizedSettings = sanitizedSettings.withQuestionPrivacy(false)
        }
        let trimmedAPIKey = pendingAPIKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedDebugBackendBaseURL = Self.normalizedDebugBackendBaseURL(activeDebugBackendBaseURLForEditing)
        let now = Date()
        let didAPIKeyChange = trimmedAPIKey != savedAPIKey
        if didAPIKeyChange {
            lastAPIKeyUpdatedAt = now
            settingsStore.saveOpenAIAPIKeyUpdatedAt(now)
        } else if settingsStore.loadOpenAIAPIKeyUpdatedAt() == nil, !trimmedAPIKey.isEmpty {
            lastAPIKeyUpdatedAt = now
            settingsStore.saveOpenAIAPIKeyUpdatedAt(now)
        }
        lastLocalSettingsMutationAt = now
        settingsStore.saveLocalSettingsMutationAt(now)

        settings = sanitizedSettings
        apiKey = trimmedAPIKey
        debugBackendBaseURL = normalizedDebugBackendBaseURL
        draftSettings = sanitizedSettings
        draftAPIKey = trimmedAPIKey
        draftDebugBackendBaseURL = normalizedDebugBackendBaseURL
        didReceiveCloudStateWhileEditing = false

        settingsStore.saveSettings(sanitizedSettings)
        settingsStore.saveAPIKey(trimmedAPIKey)
        settingsStore.saveDebugBackendBaseURL(normalizedDebugBackendBaseURL)
        savedSettings = sanitizedSettings
        savedAPIKey = trimmedAPIKey
        savedDebugBackendBaseURL = normalizedDebugBackendBaseURL
        studyRecords = settingsStore.loadStudyRecords()
        if trimmedAPIKey.isEmpty {
            hasAPIKeyError = true
            errorMessage = strings.apiKeyEmptyDetailed
        } else if didAPIKeyChange {
            isBackendOpenAIKeyConfigured = false
            hasAPIKeyError = false
            errorMessage = nil
        } else if !hasAPIKeyError {
            isBackendOpenAIKeyConfigured = true
            errorMessage = nil
        }
        statusMessage = nil
        StudyNotificationDelegate.shared.register(language: sanitizedSettings.appLanguage)
        log(.info, "설정을 저장했습니다. interval=\(sanitizedSettings.sanitizedIntervalMinutes), maxHistory=\(sanitizedSettings.sanitizedMaxHistoryCount)")
        markCloudDataChanged()
        refreshRemotePushBackendClient(reason: "settings")

        restartTimer()

        Task {
            await ensureCloudQuestionPushSubscription()
            await syncRemotePushScheduleIfPossible(reason: "settings")
        }
    }

    private func settingsWithResolvedStudyProfile(from pendingSettings: StudySettings) -> StudySettings {
        let fallbackTitle = StudySettings.fallbackTopic(for: pendingSettings.appLanguage)
        let normalizedTopic = Self.normalizedCategoryLookup(for: pendingSettings.topic)
        let normalizedFallback = Self.normalizedCategoryLookup(for: fallbackTitle)
        let hasUserStudy = pendingSettings.studyCategories.contains {
            Self.normalizedCategoryLookup(for: $0.title) != normalizedFallback
        }

        guard !hasUserStudy, normalizedTopic != normalizedFallback else {
            return pendingSettings
        }

        let profile = StudyCategory(
            title: pendingSettings.topic,
            difficulty: pendingSettings.difficulty,
            customPrompt: pendingSettings.customPrompt,
            openAIModel: pendingSettings.sanitizedOpenAIModel
        )

        return StudySettings(
            topic: pendingSettings.topic,
            difficulty: pendingSettings.difficulty,
            appLanguage: pendingSettings.appLanguage,
            language: pendingSettings.appLanguage.studyLanguage,
            openAIModel: pendingSettings.sanitizedOpenAIModel,
            notificationSound: pendingSettings.notificationSound,
            customPrompt: pendingSettings.customPrompt,
            intervalMinutes: pendingSettings.sanitizedIntervalMinutes,
            maxHistoryCount: pendingSettings.sanitizedMaxHistoryCount,
            isQuestionPublic: pendingSettings.isQuestionPublic,
            studyCategories: [profile],
            selectedStudyCategoryID: profile.id
        )
    }

    func saveSettingsAndValidateAPIKey() async {
        let pendingSettings = activeSettingsForEditing
        let pendingAPIKey = activeAPIKeyForEditing
        let trimmedAPIKey = pendingAPIKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let didAPIKeyChange = trimmedAPIKey != savedAPIKey

        persistSettings(
            pendingSettings,
            apiKey: pendingAPIKey
        )

        guard didAPIKeyChange else {
            log(.info, "API 키 변경사항이 없어 저장 후 검증을 건너뛰었습니다.")
            return
        }

        guard !trimmedAPIKey.isEmpty else {
            hasAPIKeyError = true
            errorMessage = strings.apiKeyEmptyDetailed
            statusMessage = nil
            log(.warning, "API 키가 비어 있어 검증을 건너뛰었습니다.")
            return
        }

        isValidatingAPIKey = true
        statusMessage = nil
        errorMessage = nil

        do {
            guard let registration = await backendRegistrationForOpenAIRequests(reason: "settings-api-validation") else {
                throw RemotePushBackendError.invalidResponse
            }
            try await updateBackendSettings(
                registration: registration,
                reason: "settings-api-validation",
                includeAPIKey: true
            )
            _ = try await remotePushBackendClient.validateAPIKey(registration: registration)
            hasAPIKeyError = false
            statusMessage = nil
            isBackendOpenAIKeyConfigured = true
            log(.info, "백엔드 OpenAI API 키 검증에 성공했습니다.")
        } catch {
            if handlePageAccessError(error, page: .studyDetail) {
                return
            }
            handleOpenAIError(error)
            statusMessage = nil
        }

        isValidatingAPIKey = false
    }

    func setRunning(_ running: Bool) {
        isRunning = running
        settingsStore.saveExplicitIsRunning(running)
        statusMessage = running ? "질문 타이머가 실행 중입니다." : "질문 타이머를 일시정지했습니다."
        log(.info, running ? "질문 타이머를 실행했습니다." : "질문 타이머를 중지했습니다.")
        markCloudDataChanged()
        restartTimer()

        Task {
            await syncRemotePushScheduleIfPossible(reason: "running")
        }
    }

    func setTimerInterval(_ minutes: Int) {
        settings.intervalMinutes = min(max(minutes, 1), 240)
        settingsStore.saveSettings(settings)
        savedSettings = normalizedSettings(settings)
        studyRecords = settingsStore.loadStudyRecords()
        statusMessage = "질문 간격을 \(settings.intervalMinutes)분으로 설정했습니다."
        log(.info, "질문 간격을 \(settings.intervalMinutes)분으로 변경했습니다.")
        markCloudDataChanged()
        restartTimer()

        Task {
            await syncRemotePushScheduleIfPossible(reason: "timer")
        }
    }

    func updateAppLanguage(_ language: AppLanguage) {
        settings.appLanguage = language
        settings.language = language.studyLanguage
        StudyNotificationDelegate.shared.register(language: language)
    }

    func setNotificationSound(_ sound: NotificationSoundOption, preview: Bool = true) {
        settings.notificationSound = sound

        guard preview else {
            return
        }

        notificationService.playPreview(sound: sound)
        statusMessage = sound == .none
            ? "알림음을 없음으로 설정했습니다."
            : "\(sound.displayName(language: settings.appLanguage)) 알림음을 재생했습니다."
    }

    func openSystemNotificationSettings() {
        notificationService.openSystemNotificationSettings()
    }

    func setAppLanguage(_ language: AppLanguage) {
        updateAppLanguage(language)
        settingsStore.saveSettings(settings)
        savedSettings = normalizedSettings(settings)
        studyRecords = settingsStore.loadStudyRecords()
        StudyNotificationDelegate.shared.register(language: language)
        statusMessage = language == .korean ? "앱 언어를 한국어로 설정했습니다." : "App language set to English."
        log(.info, "앱 언어를 \(language.rawValue)로 변경했습니다.")
        markCloudDataChanged()

        Task {
            await syncRemotePushScheduleIfPossible(reason: "language")
        }
    }

    func generateQuestion(manual: Bool = true) async {
        notificationLandingMessage = nil

        if !manual && !isRunning {
            log(.info, "타이머가 중지되어 예약 질문 생성을 건너뛰었습니다.")
            return
        }

        guard !isGeneratingQuestion else {
            log(.info, "이미 질문 생성 중이라 새 요청을 무시했습니다.")
            return
        }

        isGeneratingQuestion = true
        defer {
            isGeneratingQuestion = false
        }

        guard let registration = await backendRegistrationForOpenAIRequests(reason: manual ? "manual-question" : "scheduled-question") else {
            statusMessage = nil
            errorMessage = "백엔드 등록이 없어 질문을 생성할 수 없습니다. 네트워크와 알림 권한을 확인한 뒤 다시 시도하세요."
            log(.warning, "백엔드 등록이 없어 질문 생성을 중단했습니다.")
            return
        }

        await generateBackendQuestion(registration: registration, manual: manual)
    }

    private func generateBackendQuestion(registration: RemotePushRegistration, manual: Bool) async {
        guard requirePageAccess(.studyDetail) else {
            return
        }

        guard await canCreateQuestionAfterGlobalPendingCheck(
            reason: "백엔드 새 질문 생성",
            updateVisibleQuestion: manual
        ) else {
            return
        }

        errorMessage = nil
        statusMessage = manual ? "질문을 생성 중입니다." : "예약된 질문을 확인 중입니다."
        log(.info, "백엔드 새 질문 생성 요청을 전송합니다.")

        do {
            try await updateBackendSettings(
                registration: registration,
                reason: manual ? "manual-question-before-create" : "scheduled-question-before-create"
            )
            let record = try await remotePushBackendClient.createQuestion(
                registration: registration,
                topic: settings.activeCategory?.normalizedTitle ?? settings.effectiveTopic
            )
            settingsStore.appendQuestionToHistory(record.question)
            settingsStore.replaceStudyRecords(mergeBackendRecord(record, into: studyRecords))
            studyRecords = settingsStore.loadStudyRecords()

            let shouldActivateQuestion = !hasActiveUngradedCurrentQuestion
            if shouldActivateQuestion {
                currentQuestion = record.question
                gradingResult = record.gradingResult
                lastAnswer = record.answer ?? ""
                settingsStore.saveQuestion(record.question)
                settingsStore.saveGradingResult(record.gradingResult)
                settingsStore.saveLastAnswer(record.answer ?? "")
            }

            hasAPIKeyError = false
            statusMessage = shouldActivateQuestion ? "새 질문이 준비됐습니다." : "새 질문이 준비됐지만 작성 중인 답변은 유지했습니다."
            log(.info, "백엔드 질문을 생성했습니다: \(record.question.question)")
            await syncRemotePushScheduleIfPossible(reason: "manual-question")
        } catch {
            if handlePageAccessError(error, page: .studyDetail) {
                return
            }
            handleOpenAIError(error)
            statusMessage = nil
            log(.error, "백엔드 질문 생성에 실패했습니다: \(error.localizedDescription)")
        }
    }

    @discardableResult
    private func generateDueQuestionIfNeeded(reason: String) async -> Bool {
        guard hasCompletedOnboarding, isRunning else {
            return false
        }

        guard let registration = await backendRegistrationForOpenAIRequests(reason: reason) else {
            log(.warning, "백엔드 등록이 없어 \(reason) 예약 질문 확인을 건너뛰었습니다.")
            return false
        }

        await syncRemotePushScheduleIfPossible(reason: reason)
        await refreshBackendStudyIfPossible(updateVisibleQuestion: false)
        log(.info, "백엔드 스케줄러가 예약 질문을 담당하므로 로컬 OpenAI 생성을 수행하지 않았습니다. reason=\(reason), deviceID=\(registration.deviceID)")
        return false
    }

    @discardableResult
    private func prepareScheduledQuestionsForLockedDevice(isExpired: () -> Bool) async -> Int {
        reloadPersistedState(restartTimerAfterReload: false)

        guard hasCompletedOnboarding, isRunning else {
            return 0
        }

        guard !isExpired() else {
            log(.warning, "iOS background 시간이 만료되어 백엔드 예약 확인을 중단했습니다.")
            return 0
        }

        guard let registration = await backendRegistrationForOpenAIRequests(reason: "background") else {
            log(.warning, "백엔드 등록이 없어 잠금화면 질문 준비를 건너뛰었습니다.")
            return 0
        }

        await syncRemotePushScheduleIfPossible(reason: "background")
        log(.info, "백엔드/APNs 스케줄러가 잠금화면 질문을 담당합니다. 로컬 OpenAI 질문 생성은 수행하지 않았습니다. deviceID=\(registration.deviceID)")
        return 0
    }

    private func canCreateQuestionAfterGlobalPendingCheck(
        reason: String,
        updateVisibleQuestion: Bool = true
    ) async -> Bool {
        await refreshGlobalStudyProgressFromStore(updateVisibleQuestion: updateVisibleQuestion)

        if isCloudSyncEnabled, hasCloudSyncError {
            let message = cloudSyncMessage ?? strings.syncUnavailable
            statusMessage = message
            errorMessage = message
            log(.warning, "iCloud 동기화 상태를 확인하지 못해 \(reason)을 건너뛰었습니다.")
            return false
        }

        guard !hasReachedPendingQuestionLimit(for: settings.category(for: settings.selectedStudyCategoryID)) else {
            showPendingQuestionLimitStatus(reason: reason)
            return false
        }

        return true
    }

    private func refreshGlobalStudyProgressFromStore(updateVisibleQuestion: Bool = true) async {
        refreshStudyProgressFromStore()

        guard isCloudSyncEnabled else {
            return
        }

        await waitForActiveCloudSyncIfNeeded()

        if !isCloudSyncing {
            await syncCloudNow(updateVisibleQuestion: updateVisibleQuestion)
        }

        await waitForActiveCloudSyncIfNeeded()
        refreshStudyProgressFromStore()
    }

    private func waitForActiveCloudSyncIfNeeded() async {
        guard isCloudSyncing else {
            return
        }

        for _ in 0..<20 {
            try? await Task.sleep(nanoseconds: 100_000_000)
            if !isCloudSyncing {
                return
            }
        }
    }

    func sendTestNotification() async {
        let question = QuestionItem(
            question: strings.testNotificationBody,
            expectedAnswerHint: nil,
            createdAt: Date()
        )

        let didSend = await notificationService.showQuestionNotification(
            question: question,
            title: strings.notificationTitle,
            subtitle: strings.notifications,
            sound: settings.notificationSound,
            language: settings.appLanguage,
            deliveryDate: nil
        )

        if didSend {
            statusMessage = strings.testNotificationSent
            log(.info, "테스트 알림을 보냈습니다.")
        } else {
            statusMessage = strings.testNotificationFailed
            log(.warning, "테스트 알림 전송에 실패했습니다. 알림 권한 또는 시스템 설정을 확인하세요.")
        }
    }

    private var notificationSubtitle: String {
        let topic = settings.topic.trimmingCharacters(in: .whitespacesAndNewlines)
        let difficulty = settings.difficulty.displayName(language: settings.appLanguage)

        guard !topic.isEmpty else {
            return difficulty
        }

        return "\(topic) · \(difficulty)"
    }

    private func isQuestionDue(now: Date) -> Bool {
        latestQuestionCreatedAt == nil || nextQuestionDueDate(now: now) <= now
    }

    private func nextQuestionDueDate(now: Date) -> Date {
        let interval = TimeInterval(settings.sanitizedIntervalMinutes * 60)
        guard let latestQuestionCreatedAt else {
            return now.addingTimeInterval(interval)
        }

        return latestQuestionCreatedAt.addingTimeInterval(interval)
    }

    private var latestQuestionCreatedAt: Date? {
        let recordDates = studyRecords.map(\.question.createdAt)
        return ([currentQuestion?.createdAt].compactMap { $0 } + recordDates).max()
    }

    private var hasActiveUngradedCurrentQuestion: Bool {
        guard let currentQuestion else {
            return false
        }

        if let record = studyRecord(matching: currentQuestion) {
            return record.gradingResult == nil
        }

        return gradingResult == nil
    }

    private func preferredPendingRecord(for category: StudyCategory) -> StudyRecord? {
        let categoryKey = Self.normalizedCategoryText(for: category.title)
        let records = pendingRecordsIncludingCurrent
            .filter { Self.normalizedCategoryText(for: $0.topic) == categoryKey }

        if let currentQuestion,
           let currentRecord = records.first(where: { studyRecordMatches($0, question: currentQuestion) }) {
            return currentRecord
        }

        return records.max { $0.question.createdAt < $1.question.createdAt }
    }

    func gradeCurrentAnswer(answer submittedAnswer: String? = nil) async {
        guard let currentQuestion else {
            errorMessage = "먼저 질문을 생성하세요."
            return
        }

        let answerToGrade = submittedAnswer ?? lastAnswer
        let trimmedAnswer = answerToGrade.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedAnswer.isEmpty else {
            errorMessage = "답변을 입력하세요."
            return
        }

        isGradingAnswer = true
        defer {
            isGradingAnswer = false
        }
        errorMessage = nil
        statusMessage = "답변을 채점 중입니다."
        lastAnswer = answerToGrade
        settingsStore.saveLastAnswer(answerToGrade)
        settingsStore.updateStudyRecordAnswer(question: currentQuestion, answer: answerToGrade, onlyIfUngraded: true)
        studyRecords = settingsStore.loadStudyRecords()
        log(.info, "현재 질문 답변 채점 요청을 전송합니다.")

        guard let registration = await backendRegistrationForOpenAIRequests(reason: "grade-current-answer") else {
            errorMessage = "백엔드 등록이 없어 채점할 수 없습니다."
            statusMessage = nil
            log(.warning, "백엔드 등록이 없어 현재 질문 채점을 중단했습니다.")
            return
        }

        guard let record = studyRecord(matching: currentQuestion) else {
            errorMessage = "이 질문은 백엔드 기록에 없어 채점할 수 없습니다. 새 질문을 다시 생성하세요."
            statusMessage = nil
            log(.warning, "현재 질문에 매칭되는 백엔드 기록이 없어 채점을 중단했습니다.")
            return
        }

        do {
            let updatedRecord = try await remotePushBackendClient.gradeRecord(
                registration: registration,
                recordID: record.id,
                answer: trimmedAnswer
            )
            applyGradedRecord(updatedRecord, answer: trimmedAnswer)
            await syncRemotePushScheduleIfPossible(reason: "grade")
        } catch {
            handleOpenAIError(error)
            statusMessage = nil
        }
    }

    private func applyGradedRecord(_ record: StudyRecord, answer: String) {
        currentQuestion = record.question
        lastAnswer = answer
        gradingResult = record.gradingResult
        settingsStore.saveQuestion(record.question)
        settingsStore.saveLastAnswer(answer)
        settingsStore.saveGradingResult(record.gradingResult)
        settingsStore.replaceStudyRecords(mergeBackendRecord(record, into: studyRecords))
        notificationService.cancelQuestionNotification(for: record.question)
        studyRecords = settingsStore.loadStudyRecords()
        hasAPIKeyError = false
        statusMessage = "채점이 완료됐습니다."
        log(.info, "백엔드에서 답변을 채점했습니다. score=\(record.gradingResult?.score ?? 0)")
    }

    func gradeRecord(_ record: StudyRecord, answer: String) async {
        let trimmedAnswer = answer.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedAnswer.isEmpty else {
            errorMessage = "답변을 입력하세요."
            return
        }

        isGradingAnswer = true
        defer {
            isGradingAnswer = false
        }
        errorMessage = nil
        statusMessage = "기록의 답변을 채점 중입니다."
        log(.info, "기록 답변 채점 요청을 전송합니다.")

        guard let registration = await backendRegistrationForOpenAIRequests(reason: "grade-record") else {
            errorMessage = "백엔드 등록이 없어 채점할 수 없습니다."
            statusMessage = nil
            log(.warning, "백엔드 등록이 없어 기록 채점을 중단했습니다.")
            return
        }

        do {
            let updatedRecord = try await remotePushBackendClient.gradeRecord(
                registration: registration,
                recordID: record.id,
                answer: trimmedAnswer
            )
            applyGradedRecord(updatedRecord, answer: trimmedAnswer)
            await syncRemotePushScheduleIfPossible(reason: "grade-record")
            markCloudDataChanged()
        } catch {
            handleOpenAIError(error)
            statusMessage = nil
        }
    }

    func skipCurrentQuestion() {
        guard let currentQuestion else {
            return
        }

        let skippedRecord = studyRecord(matching: currentQuestion) ?? StudyRecord(
            question: currentQuestion,
            answer: lastAnswer.isEmpty ? nil : lastAnswer,
            topic: settings.topic,
            difficulty: settings.difficulty
        )

        skipPendingQuestion(skippedRecord)
    }

    func skipPendingQuestion(_ record: StudyRecord) {
        guard record.gradingResult == nil else {
            return
        }

        notificationLandingMessage = nil

        let matchesCurrentQuestion = currentQuestion.map {
            Self.questionsMatch($0, record.question)
        } ?? false

        if matchesCurrentQuestion {
            notificationService.cancelQuestionNotification(for: record.question)
        }

        if let storedRecord = studyRecord(matching: record.question),
           storedRecord.gradingResult == nil {
            notificationService.cancelQuestionNotification(for: storedRecord.question)
            settingsStore.deleteStudyRecord(storedRecord)
        } else if !matchesCurrentQuestion {
            return
        }

        studyRecords = settingsStore.loadStudyRecords()

        if matchesCurrentQuestion {
            self.currentQuestion = nil
            lastAnswer = ""
            gradingResult = nil

            let remainingPendingRecords = studyRecords
                .filter { $0.gradingResult == nil }
                .sorted { $0.question.createdAt > $1.question.createdAt }

            if let nextRecord = remainingPendingRecords.first {
                self.currentQuestion = nextRecord.question
                lastAnswer = nextRecord.answer ?? ""
                gradingResult = nil
                settingsStore.saveQuestion(nextRecord.question)
                settingsStore.saveLastAnswer(nextRecord.answer ?? "")
                settingsStore.saveGradingResult(nil)
                statusMessage = "질문을 넘기고 다음 미제출 질문을 열었습니다."
            } else {
                settingsStore.saveQuestion(nil)
                settingsStore.saveLastAnswer("")
                settingsStore.saveGradingResult(nil)
                statusMessage = "질문을 넘겼습니다."
            }
        } else {
            statusMessage = "질문을 넘겼습니다."
        }

        errorMessage = nil
        log(.info, "미제출 질문을 넘겼습니다.")
        if let registration = settingsStore.loadRemotePushRegistration() {
            Task {
                do {
                    _ = try await remotePushBackendClient.skipRecord(registration: registration, recordID: record.id)
                    await refreshBackendStudyIfPossible(updateVisibleQuestion: false)
                    await syncRemotePushScheduleIfPossible(reason: "skip")
                } catch {
                    if self.handlePageAccessError(error, page: .studyDetail) {
                        return
                    }
                    log(.warning, "백엔드 미제출 질문 넘기기 실패: \(error.localizedDescription)")
                }
            }
        }
        markCloudDataChanged(syncDelaySeconds: 0)
    }

    func openOldestPendingQuestion() {
        guard let record = pendingStudyRecords.last else {
            return
        }

        notificationLandingMessage = nil
        selectStudyRecord(record)
        statusMessage = "가장 오래된 미제출 질문을 열었습니다."
        log(.info, "가장 오래된 미제출 질문을 열었습니다.")
    }

    func updateAnswer(_ answer: String) {
        lastAnswer = answer
        settingsStore.saveLastAnswer(answer)
        if let currentQuestion {
            settingsStore.updateStudyRecordAnswer(question: currentQuestion, answer: answer, onlyIfUngraded: true)
            studyRecords = settingsStore.loadStudyRecords()
            markCloudDataChanged(syncDelaySeconds: 4)
        }
    }

    func selectStudyRecord(_ record: StudyRecord) {
        guard requirePageAccess(.studyDetail) else {
            return
        }

        notificationLandingMessage = nil
        currentQuestion = record.question
        lastAnswer = record.answer ?? ""
        gradingResult = record.gradingResult
        settingsStore.saveQuestion(record.question)
        settingsStore.saveLastAnswer(record.answer ?? "")
        settingsStore.saveGradingResult(record.gradingResult)
        showStudyScreen(categoryID: categoryID(forTopic: record.topic))
        focusedRecordRequest = nil
        statusMessage = record.gradingResult == nil ? "미제출 질문을 열었습니다." : "학습 기록을 열었습니다."
        markCloudDataChanged(syncDelaySeconds: 4)
    }

    func prepareToOpenQuestionFromNotification() {
        guard requirePageAccess(.studyDetail) else {
            return
        }

        showStudyScreen(categoryID: nil)
        notificationLandingMessage = strings.openingNotificationQuestion
        statusMessage = strings.openingNotificationQuestion
        errorMessage = nil
    }

    @discardableResult
    func openRecordFromNotification(
        recordID: String?,
        questionCreatedAt: TimeInterval?,
        replyText: String? = nil
    ) -> Bool {
        if let recordID,
           settingsStore.loadRemotePushRegistration() != nil {
            guard requirePageAccess(.studyDetail) else {
                return false
            }

            showStudyScreen(categoryID: nil)
            notificationLandingMessage = strings.openingNotificationQuestion
            statusMessage = strings.openingNotificationQuestion
            Task {
                await handleBackendRecordPush(
                    recordID: recordID,
                    openStudy: true,
                    replyText: replyText
                )
            }
            return true
        }

        return openRecordFromNotification(questionCreatedAt: questionCreatedAt, replyText: replyText)
    }

    @discardableResult
    func openRecordFromNotification(questionCreatedAt: TimeInterval?, replyText: String? = nil) -> Bool {
        studyRecords = settingsStore.loadStudyRecords()

        let matchingRecord = recordMatching(questionCreatedAt: questionCreatedAt)
        let record = matchingRecord
        guard let record else {
            if let questionCreatedAt,
               let currentQuestion,
               abs(currentQuestion.createdAt.timeIntervalSince1970 - questionCreatedAt) < 1 {
                let trimmedReply = replyText?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                if !trimmedReply.isEmpty {
                    updateAnswer(trimmedReply)
                    statusMessage = "알림 답장을 기록에 저장했습니다."
                } else {
                    statusMessage = "알림에서 열린 질문입니다."
                }
                notificationLandingMessage = nil
                showStudyScreen(categoryID: nil)
                return true
            }

            showNotificationQuestionUnavailable(preserveCurrentQuestion: true)
            log(.warning, "알림에서 요청한 질문을 찾을 수 없습니다. 삭제되었거나 넘겨진 질문일 수 있습니다.")
            return false
        }

        let trimmedReply = replyText?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !trimmedReply.isEmpty {
            settingsStore.updateStudyRecordAnswer(question: record.question, answer: trimmedReply)
        }

        studyRecords = settingsStore.loadStudyRecords()
        let refreshedRecord = recordMatching(questionCreatedAt: questionCreatedAt) ??
            studyRecords.first { $0.id == record.id } ??
            record
        selectStudyRecord(refreshedRecord)
        notificationLandingMessage = nil
        statusMessage = trimmedReply.isEmpty ? "알림에서 열린 질문입니다." : "알림 답장을 기록에 저장했습니다."
        markCloudDataChanged()
        return true
    }

    @discardableResult
    func handleBackendRecordPush(recordID: String, openStudy: Bool, replyText: String? = nil) async -> Bool {
        guard let storedRegistration = settingsStore.loadRemotePushRegistration(),
              let registration = await registrationWithAccessToken(storedRegistration, reason: "backend-record-push") else {
            log(.warning, "백엔드 push record를 열 수 없습니다. 기기 등록 정보가 없습니다.")
            return false
        }

        do {
            var record = try await remotePushBackendClient.fetchRecord(
                registration: registration,
                recordID: recordID
            )

            let trimmedReply = replyText?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !trimmedReply.isEmpty, record.gradingResult == nil {
                record = try await remotePushBackendClient.saveRecordAnswer(
                    registration: registration,
                    recordID: recordID,
                    answer: trimmedReply
                )
            }

            settingsStore.replaceStudyRecords(mergeBackendRecord(record, into: studyRecords))
            studyRecords = settingsStore.loadStudyRecords()

            if currentQuestion.map({ Self.questionsMatch($0, record.question) }) == true {
                lastAnswer = record.answer ?? lastAnswer
                gradingResult = record.gradingResult
                settingsStore.saveLastAnswer(lastAnswer)
                settingsStore.saveGradingResult(gradingResult)
            }

            if openStudy {
                selectStudyRecord(record)
                notificationLandingMessage = nil
                statusMessage = trimmedReply.isEmpty ? "알림에서 열린 질문입니다." : "알림 답장을 기록에 저장했습니다."
            } else if !trimmedReply.isEmpty {
                statusMessage = "알림 답장을 기록에 저장했습니다."
            }

            log(.info, "백엔드 push record를 처리했습니다. recordID=\(recordID), openStudy=\(openStudy)")
            return true
        } catch {
            if handlePageAccessError(error, page: .studyDetail) {
                return false
            }
            if openStudy {
                showNotificationQuestionUnavailable(preserveCurrentQuestion: true)
            }
            log(.warning, "백엔드 push record 처리 실패: \(error.localizedDescription)")
            return false
        }
    }

    @discardableResult
    func saveNotificationReplyFromNotification(
        recordID: String? = nil,
        questionCreatedAt: TimeInterval?,
        replyText: String?
    ) -> Bool {
        if let recordID,
           settingsStore.loadRemotePushRegistration() != nil {
            Task {
                await handleBackendRecordPush(
                    recordID: recordID,
                    openStudy: false,
                    replyText: replyText
                )
            }
            return true
        }

        studyRecords = settingsStore.loadStudyRecords()

        let trimmedReply = replyText?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !trimmedReply.isEmpty else {
            return false
        }

        guard let record = recordMatching(questionCreatedAt: questionCreatedAt) else {
            log(.warning, "알림 답장을 저장할 질문을 찾을 수 없습니다. 삭제되었거나 넘겨진 질문일 수 있습니다.")
            return false
        }

        guard record.gradingResult == nil else {
            log(.info, "이미 채점된 질문이라 알림 답장을 덮어쓰지 않았습니다.")
            return false
        }

        settingsStore.updateStudyRecordAnswer(
            question: record.question,
            answer: trimmedReply,
            onlyIfUngraded: true
        )

        if currentQuestion.map({ Self.questionsMatch($0, record.question) }) == true {
            lastAnswer = trimmedReply
            settingsStore.saveLastAnswer(trimmedReply)
        }

        studyRecords = settingsStore.loadStudyRecords()
        markCloudDataChanged()
        return true
    }

    @discardableResult
    func handleCloudQuestionPush(recordName: String?, openStudy: Bool, replyText: String? = nil) async -> Bool {
        guard isCloudSyncEnabled else {
            log(.info, "iCloud 동기화가 꺼져 있어 CloudKit push를 무시했습니다.")
            return false
        }

        guard let cloudSyncService = resolvedCloudSyncService() else {
            log(.warning, "CloudKit push를 처리할 수 없습니다. iCloud 권한을 확인하세요.")
            return false
        }

        var fetchedPush: CloudQuestionPush?

        do {
            if let recordName,
               let push = try await cloudSyncService.fetchQuestionPush(recordName: recordName) {
                fetchedPush = push
            }
        } catch {
            log(.warning, "CloudKit push 질문 정보를 불러오지 못했습니다: \(error.localizedDescription)")
        }

        await syncCloudNow(updateVisibleQuestion: openStudy)

        guard let fetchedPush else {
            guard openStudy else {
                log(.info, "CloudKit push record가 없어 조용히 무시했습니다.")
                return false
            }

            showNotificationQuestionUnavailable(preserveCurrentQuestion: true)
            log(.warning, "CloudKit push record가 없어 알림 질문을 열 수 없습니다.")
            return false
        }

        var pushedQuestionCreatedAt: TimeInterval?
        let didAddRecord = ensureLocalRecordExists(for: fetchedPush, showStatus: openStudy)
        refreshStudyProgressFromStore()

        if studyRecord(matching: fetchedPush.question) != nil {
            pushedQuestionCreatedAt = fetchedPush.question.createdAt.timeIntervalSince1970
        }

        let didSaveReply = saveNotificationReplyIfNeeded(
            replyText,
            for: fetchedPush.question,
            showStatus: openStudy
        )

        if didAddRecord || didSaveReply {
            markCloudDataDirtyWithoutScheduling()
            await syncCloudNow(updateVisibleQuestion: openStudy)
        }

        guard openStudy else {
            log(.info, "CloudKit push로 iCloud 데이터를 갱신했습니다.")
            return true
        }

        if let pushedQuestionCreatedAt {
            openRecordFromNotification(questionCreatedAt: pushedQuestionCreatedAt, replyText: replyText)
        } else {
            showNotificationQuestionUnavailable(preserveCurrentQuestion: true)
        }

        return true
    }

    private func saveNotificationReplyIfNeeded(
        _ replyText: String?,
        for question: QuestionItem,
        showStatus: Bool
    ) -> Bool {
        let trimmedReply = replyText?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !trimmedReply.isEmpty else {
            return false
        }

        let existingRecord = studyRecord(matching: question)
        guard existingRecord?.gradingResult == nil else {
            log(.info, "이미 채점된 질문이라 알림 답장을 덮어쓰지 않았습니다.")
            return false
        }

        settingsStore.updateStudyRecordAnswer(
            question: question,
            answer: trimmedReply,
            onlyIfUngraded: true
        )

        if currentQuestion.map({ Self.questionsMatch($0, question) }) == true {
            lastAnswer = trimmedReply
            settingsStore.saveLastAnswer(trimmedReply)
        }

        studyRecords = settingsStore.loadStudyRecords()
        if showStatus {
            statusMessage = "알림 답장을 기록에 저장했습니다."
        }
        log(.info, "CloudKit push 알림 답장을 기록에 저장했습니다.")
        return true
    }

    private func showNotificationQuestionUnavailable(preserveCurrentQuestion: Bool) {
        showStudyScreen(categoryID: nil)
        errorMessage = nil

        if !preserveCurrentQuestion || currentQuestion == nil {
            currentQuestion = nil
            lastAnswer = ""
            gradingResult = nil
            settingsStore.saveQuestion(nil)
            settingsStore.saveLastAnswer("")
            settingsStore.saveGradingResult(nil)
        }

        notificationLandingMessage = strings.notificationQuestionUnavailable
        statusMessage = strings.notificationQuestionUnavailable
    }

    func clearStatus() {
        statusMessage = nil
        errorMessage = nil
        notificationLandingMessage = nil
    }

    func clearStudyRecords() {
        notificationService.cancelQuestionNotifications(for: studyRecords.map(\.question))
        settingsStore.clearStudyRecords()
        studyRecords = []
        notificationLandingMessage = nil
        statusMessage = "학습 기록을 삭제했습니다."
        log(.warning, "학습 기록을 모두 삭제했습니다.")
        if let registration = settingsStore.loadRemotePushRegistration() {
            Task {
                guard let tokenRegistration = await registrationWithAccessToken(registration, reason: "clear-records") else {
                    return
                }
                do {
                    try await remotePushBackendClient.clearRecords(registration: tokenRegistration)
                    await syncRemotePushScheduleIfPossible(reason: "clear-records")
                } catch {
                    log(.warning, "백엔드 학습 기록 전체삭제 실패: \(error.localizedDescription)")
                }
            }
        }
        markCloudDataChanged(syncDelaySeconds: 0)
    }

    func deleteStudyRecord(_ record: StudyRecord) {
        notificationService.cancelQuestionNotification(for: record.question)
        settingsStore.deleteStudyRecord(record)
        studyRecords = settingsStore.loadStudyRecords()
        notificationLandingMessage = nil

        if SettingsStore.normalizedQuestionText(currentQuestion?.question ?? "") ==
            SettingsStore.normalizedQuestionText(record.question.question) {
            currentQuestion = nil
            gradingResult = nil
            lastAnswer = ""
            settingsStore.saveQuestion(nil)
            settingsStore.saveGradingResult(nil)
            settingsStore.saveLastAnswer("")
        }

        statusMessage = "기록을 삭제했습니다."
        log(.info, "학습 기록을 1개 삭제했습니다.")
        if let registration = settingsStore.loadRemotePushRegistration() {
            Task {
                guard let tokenRegistration = await registrationWithAccessToken(registration, reason: "delete-record") else {
                    return
                }
                do {
                    try await remotePushBackendClient.deleteRecord(registration: tokenRegistration, recordID: record.id)
                    await refreshBackendStudyIfPossible(updateVisibleQuestion: false)
                    await syncRemotePushScheduleIfPossible(reason: "delete-record")
                } catch {
                    log(.warning, "백엔드 학습 기록 삭제 실패: \(error.localizedDescription)")
                }
            }
        }
        markCloudDataChanged(syncDelaySeconds: 0)
    }

    func updateStudyRecordPublicity(_ record: StudyRecord, isPublic: Bool) {
        let updatedRecord = StudyRecord(
            id: record.id,
            question: record.question,
            answer: record.answer,
            gradingResult: record.gradingResult,
            topic: record.topic,
            difficulty: record.difficulty,
            answeredAt: record.answeredAt,
            isPublic: isPublic
        )
        settingsStore.saveStudyRecord(updatedRecord)
        studyRecords = settingsStore.loadStudyRecords()
        markCloudDataChanged()

        guard let registration = settingsStore.loadRemotePushRegistration() else {
            return
        }

        Task {
            guard let tokenRegistration = await registrationWithAccessToken(registration, reason: "record-publicity") else {
                return
            }
            do {
                let backendRecord = try await remotePushBackendClient.updateRecordPublicity(
                    registration: tokenRegistration,
                    recordID: record.id,
                    isPublic: isPublic
                )
                settingsStore.saveStudyRecord(backendRecord)
                studyRecords = settingsStore.loadStudyRecords()
            } catch {
                log(.warning, "기록 공개 상태 변경 실패: \(error.localizedDescription)")
            }
        }
    }

    func clearAppLogs() {
        settingsStore.clearAppLogs()
        appLogs = []
        appLogTotalCount = 0
        appLogPage = 0
    }

    func appendAPITrafficLog(_ entry: APITrafficLogEntry) {
        apiTrafficLogs.insert(entry, at: 0)
        if apiTrafficLogs.count > Self.maxAPITrafficLogs {
            apiTrafficLogs.removeLast(apiTrafficLogs.count - Self.maxAPITrafficLogs)
        }
    }

    func clearAPITrafficLogs() {
        apiTrafficLogs = []
    }

    func showAPIDebugPanel() {
        isAPIDebugPanelPresented = true
    }

    func requestDebugPanelIfEnabledOrEnableOnDemand() {
        isAPIDebugPanelPresented = true
        log(.info, "API 디버그 패널을 열었습니다.")
    }

    func logRemoteNotificationEvent(_ message: String, isWarning: Bool = false) {
        log(isWarning ? .warning : .info, message)
    }

    func loadAppLogPage(_ page: Int) {
        let logPage = settingsStore.loadAppLogs(page: page, pageSize: Self.developerLogPageSize)
        appLogs = logPage.entries
        appLogTotalCount = logPage.totalCount
        appLogPage = logPage.page
    }

    func loadPreviousAppLogPage() {
        loadAppLogPage(appLogPage - 1)
    }

    func loadNextAppLogPage() {
        loadAppLogPage(appLogPage + 1)
    }

    func setDebuggingEnabled(_ isEnabled: Bool) {
        isDebuggingEnabled = isEnabled
        settingsStore.saveIsDebuggingEnabled(isEnabled)
        refreshRemotePushBackendClient(reason: isEnabled ? "debug-enabled" : "debug-disabled")
        log(.info, isEnabled ? "디버깅 모드를 켰습니다." : "디버깅 모드를 껐습니다.")
    }

    func setCloudSyncEnabled(_ isEnabled: Bool) {
        isCloudSyncEnabled = isEnabled
        settingsStore.saveIsCloudSyncEnabled(isEnabled)
        cloudSyncMessage = isEnabled ? strings.iCloudSyncOn : strings.iCloudSyncOff
        hasCloudSyncError = false

        guard isEnabled else {
            cloudSyncTask?.cancel()
            return
        }

        Task {
            await syncCloudNow()
            await ensureCloudQuestionPushSubscription()
        }
    }

    func syncCloudNow(updateVisibleQuestion: Bool = true) async {
        guard isCloudSyncEnabled else {
            return
        }

        guard !isCloudSyncing else {
            cloudSyncMessage = strings.syncAlreadyInProgress
            return
        }

        guard cloudSyncService != nil || CloudSyncService.canUseCloudKitContainer() else {
            cloudSyncMessage = strings.syncEntitlementMissing
            hasCloudSyncError = true
            log(.error, "이 앱 빌드에 iCloud CloudKit entitlement가 없어 동기화할 수 없습니다.")
            return
        }

        let cloudSyncService = resolvedCloudSyncService()
        guard let cloudSyncService else {
            cloudSyncMessage = strings.syncUnavailable
            hasCloudSyncError = true
            log(.warning, cloudSyncMessage ?? "iCloud 동기화를 사용할 수 없습니다.")
            return
        }

        isCloudSyncing = true
        defer {
            isCloudSyncing = false
        }

        do {
            let storedLocalUpdatedAt = settingsStore.loadCloudSyncStateUpdatedAt()
            let localUpdatedAt = storedLocalUpdatedAt ?? .distantPast
            let fetchedRemoteState = try await cloudSyncService.fetchState()

            if let fetchedRemoteState {
                let apiKeyMerge = remoteStateByFillingMissingAPIKey(fetchedRemoteState)
                let remoteState = apiKeyMerge.state
                if storedLocalUpdatedAt == nil {
                    let firstSync = firstSyncState(from: remoteState)
                    applyCloudState(firstSync.state, updateVisibleQuestion: updateVisibleQuestion)

                    if firstSync.shouldPushMergedState {
                        try await cloudSyncService.saveState(firstSync.state)
                        settingsStore.saveCloudSyncStateUpdatedAt(firstSync.state.updatedAt)
                        cloudLastSyncedAt = firstSync.state.updatedAt
                        cloudSyncMessage = strings.syncMergedRemote
                        log(.info, "iCloud 데이터를 불러오고 이 기기의 기록을 병합했습니다.")
                    } else {
                        cloudSyncMessage = strings.syncPulledRemote
                        log(.info, "첫 iCloud 동기화에서 원격 학습 데이터를 불러왔습니다.")
                    }
                } else if remoteState.updatedAt > localUpdatedAt {
                    var mergedRemoteState = incomingStateMergingLocalData(remoteState)
                    let shouldPushMergedRemote = apiKeyMerge.shouldPush ||
                        cloudStateContentDiffers(mergedRemoteState, remoteState)

                    if shouldPushMergedRemote {
                        mergedRemoteState.updatedAt = max(Date(), remoteState.updatedAt, localUpdatedAt)
                        try await cloudSyncService.saveState(mergedRemoteState)
                        applyCloudState(mergedRemoteState, updateVisibleQuestion: updateVisibleQuestion)
                        settingsStore.saveCloudSyncStateUpdatedAt(mergedRemoteState.updatedAt)
                        cloudLastSyncedAt = mergedRemoteState.updatedAt
                        cloudSyncMessage = strings.syncMergedRemote
                        log(.info, "iCloud 최신 데이터에 이 기기의 로컬 변경사항을 병합했습니다.")
                    } else {
                        applyCloudState(remoteState, updateVisibleQuestion: updateVisibleQuestion)
                        cloudSyncMessage = strings.syncPulledRemote
                        log(.info, "iCloud에서 최신 학습 데이터를 불러왔습니다.")
                    }
                } else {
                    let mergedState = outgoingStateMergingRemoteData(
                        makeCloudState(updatedAt: localUpdatedAt),
                        remoteState: remoteState
                    )
                    if cloudStateContentDiffers(mergedState, remoteState) {
                        var state = mergedState
                        state.updatedAt = max(localUpdatedAt, remoteState.updatedAt, Date())
                        try await cloudSyncService.saveState(state)
                        applyCloudState(state, updateVisibleQuestion: updateVisibleQuestion)
                        cloudSyncMessage = strings.syncPushedLocal
                        log(.info, "학습 데이터를 iCloud에 저장했습니다.")
                    } else {
                        applyCloudState(remoteState, updateVisibleQuestion: updateVisibleQuestion)
                        cloudSyncMessage = strings.syncAlreadyCurrent
                    }
                }
            } else {
                let updatedAt = max(localUpdatedAt, Date())
                let state = makeCloudState(updatedAt: updatedAt)
                try await cloudSyncService.saveState(state)
                applyCloudState(state, updateVisibleQuestion: updateVisibleQuestion)
                cloudSyncMessage = strings.syncPushedLocal
                log(.info, "학습 데이터를 iCloud에 저장했습니다.")
            }

            hasCloudSyncError = false
        } catch {
            cloudSyncMessage = cloudSyncFailureMessage(for: error)
            hasCloudSyncError = true
            settingsStore.saveIsCloudSyncEnabled(isCloudSyncEnabled)
            log(.warning, cloudSyncMessage ?? "iCloud 동기화에 실패했습니다.")
        }
    }

    func openOpenAIBillingPage() {
        openURLString("https://platform.openai.com/settings/organization/billing/overview")
    }

    func openOpenAIUsageDashboardPage() {
        openURLString("https://platform.openai.com/usage")
    }

    func openOpenAICreditGrantsPage() {
        openURLString("https://platform.openai.com/settings/organization/billing/credit-grants")
    }

    func uninstallApplication() {
        #if os(macOS)
        let appURL = Bundle.main.bundleURL

        do {
            try launchUninstaller(for: appURL)
            log(.warning, "앱 제거를 실행했습니다.")
            NSApp.terminate(nil)
        } catch {
            errorMessage = strings.uninstallFailed(error.localizedDescription)
            log(.error, "앱 제거 실패: \(error.localizedDescription)")
        }
        #else
        errorMessage = strings.uninstallFailed("iOS")
        #endif
    }

    #if os(macOS)
    private func launchUninstaller(for appURL: URL) throws {
        let scriptURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("studymate-uninstall-\(UUID().uuidString).sh")
        let script = Self.makeUninstallScript(appPath: appURL.path)

        try script.write(to: scriptURL, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: scriptURL.path)

        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/sh")
        process.arguments = [
            "-c",
            "nohup /bin/sh \(Self.shellEscaped(scriptURL.path)) >/dev/null 2>&1 &"
        ]
        try process.run()
        process.waitUntilExit()

        if process.terminationStatus != 0 {
            throw CocoaError(.executableLoad)
        }
    }
    #endif

    nonisolated static func makeUninstallScript(appPath: String) -> String {
        let escapedAppPath = shellEscaped(appPath)
        let escapedHomeApplicationsPath = shellEscaped("~/Applications/StudyMate.app")

        return """
        #!/bin/sh
        set +e

        APP_PATH=\(escapedAppPath)
        LOG_PATH="${TMPDIR:-/tmp}/studymate-uninstall.log"

        echo "StudyMate uninstall started at $(date)" > "${LOG_PATH}"

        /usr/bin/osascript -e 'tell application id "io.github.ghkdqhrbals.StudyMate" to quit' >> "${LOG_PATH}" 2>&1
        /usr/bin/osascript -e 'tell application "StudyMate" to quit' >> "${LOG_PATH}" 2>&1

        ATTEMPT=0
        while /usr/bin/pgrep -x "StudyMate" >/dev/null 2>&1 && [ "${ATTEMPT}" -lt 30 ]; do
          /bin/sleep 0.2
          ATTEMPT=$((ATTEMPT + 1))
        done

        /usr/bin/pkill -x "StudyMate" >> "${LOG_PATH}" 2>&1
        /bin/sleep 0.5

        remove_path() {
          TARGET_PATH="$1"
          EXPANDED_PATH="$(eval printf '%s' "${TARGET_PATH}")"

          [ -e "${EXPANDED_PATH}" ] || return 0
          echo "Removing ${EXPANDED_PATH}" >> "${LOG_PATH}"

          TRASH_TARGET="${HOME}/.Trash/$(basename "${EXPANDED_PATH}")-$(date +%Y%m%d%H%M%S)"
          /bin/mv "${EXPANDED_PATH}" "${TRASH_TARGET}" >> "${LOG_PATH}" 2>&1
          [ ! -e "${EXPANDED_PATH}" ] && return 0

          /bin/rm -rf "${EXPANDED_PATH}" >> "${LOG_PATH}" 2>&1
          [ ! -e "${EXPANDED_PATH}" ] && return 0

          ESCAPED_TARGET="$(printf "%s" "${EXPANDED_PATH}" | /usr/bin/sed "s/'/'\\\\''/g")"
          /usr/bin/osascript -e "do shell script \\"/bin/rm -rf '${ESCAPED_TARGET}'\\" with administrator privileges" >> "${LOG_PATH}" 2>&1
          [ ! -e "${EXPANDED_PATH}" ] && return 0

          echo "Failed to remove ${EXPANDED_PATH}" >> "${LOG_PATH}"
        }

        remove_path "${APP_PATH}"
        remove_path "/Applications/StudyMate.app"
        remove_path \(escapedHomeApplicationsPath)

        remove_data() {
          BUNDLE_ID="$1"
          /usr/bin/defaults delete "${BUNDLE_ID}" >> "${LOG_PATH}" 2>&1
          /bin/rm -f "${HOME}/Library/Preferences/${BUNDLE_ID}.plist"
          /bin/rm -rf "${HOME}/Library/Application Support/${BUNDLE_ID}"
          /bin/rm -rf "${HOME}/Library/Caches/${BUNDLE_ID}"
          /bin/rm -rf "${HOME}/Library/Caches/Sparkle/${BUNDLE_ID}"
          /bin/rm -rf "${HOME}/Library/Logs/${BUNDLE_ID}"
          /bin/rm -rf "${HOME}/Library/Saved Application State/${BUNDLE_ID}.savedState"
        }

        remove_data "io.github.ghkdqhrbals.StudyMate"
        remove_data "com.local.StudyMate"

        /usr/bin/osascript -e 'display dialog "사용해주셔서 감사합니다." with title "BuddyStuddy" buttons {"확인"} default button "확인" giving up after 8' >> "${LOG_PATH}" 2>&1
        echo "StudyMate uninstall finished at $(date)" >> "${LOG_PATH}"
        /bin/rm -f "$0"
        """
    }

    nonisolated private static func shellEscaped(_ value: String) -> String {
        "'\(value.replacingOccurrences(of: "'", with: "'\\''"))'"
    }

    nonisolated private static func appleScriptEscaped(_ value: String) -> String {
        value
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
    }

    private func restartTimer() {
        timerTask?.cancel()
        guard hasCompletedOnboarding, isRunning else {
            return
        }

        timerTask = Task { [weak self] in
            while !Task.isCancelled {
                let seconds = self?.timerPollIntervalSeconds() ?? 60
                try? await Task.sleep(nanoseconds: seconds * 1_000_000_000)

                guard !Task.isCancelled else {
                    return
                }

                await self?.handleScheduledQuestionTick()
            }
        }
    }

    private func timerPollIntervalSeconds() -> UInt64 {
        let intervalSeconds = max(settings.sanitizedIntervalMinutes * 60, 60)
        return UInt64(max(15, min(60, intervalSeconds / 4)))
    }

    private func handleScheduledQuestionTick() async {
        reloadPersistedState(restartTimerAfterReload: false)
        guard hasCompletedOnboarding, isRunning else {
            restartTimer()
            return
        }

        if isCloudSyncEnabled {
            await syncCloudNow(updateVisibleQuestion: false)
        }

        guard hasCompletedOnboarding, isRunning else {
            restartTimer()
            return
        }

        await generateDueQuestionIfNeeded(reason: "timer")
    }

    private func backendRegistrationForOpenAIRequests(reason: String) async -> RemotePushRegistration? {
        if let registration = settingsStore.loadRemotePushRegistration() {
            return await registrationWithAccessToken(registration, reason: reason)
        }

        do {
            return try await registerFreshBackendDevice(
                apnsToken: nil,
                reason: reason,
                includeAPIKey: true
            )
        } catch {
            log(.warning, "OpenAI 요청용 백엔드 기기 등록 실패: \(error.localizedDescription)")
            return nil
        }
    }

    private func registrationWithAccessToken(
        _ registration: RemotePushRegistration,
        reason: String
    ) async -> RemotePushRegistration? {
        guard !registration.hasAccessToken else {
            return registration
        }

        do {
            let updatedRegistration = try await remotePushBackendClient.bootstrapAccessToken(registration: registration)
            settingsStore.saveRemotePushRegistration(updatedRegistration)
            log(.info, "백엔드 access token을 갱신했습니다. reason=\(reason), deviceID=\(updatedRegistration.deviceID)")
            return updatedRegistration
        } catch {
            if Self.isBackendDeviceNotFound(error) {
                do {
                    log(.warning, "저장된 백엔드 기기를 찾을 수 없어 새 기기를 등록합니다. reason=\(reason), deviceID=\(registration.deviceID)")
                    return try await registerFreshBackendDevice(
                        apnsToken: registration.apnsToken.isEmpty ? nil : registration.apnsToken,
                        reason: "\(reason)-device-recovery",
                        includeAPIKey: true
                    )
                } catch {
                    log(.warning, "백엔드 기기 재등록 실패: \(error.localizedDescription)")
                    return nil
                }
            }
            log(.warning, "백엔드 access token 갱신 실패: \(error.localizedDescription)")
            return nil
        }
    }

    private func registerFreshBackendDevice(
        apnsToken: String?,
        reason: String,
        includeAPIKey: Bool
    ) async throws -> RemotePushRegistration {
        let registration = try await remotePushBackendClient.registerDevice(
            apnsToken: apnsToken,
            language: settings.appLanguage,
            timezone: TimeZone.current.identifier,
            apnsEnvironment: Self.backendAPNSEnvironment
        )
        settingsStore.saveRemotePushRegistration(registration)
        log(.info, "새 백엔드 기기를 등록했습니다. reason=\(reason), deviceID=\(registration.deviceID)")
        try await updateBackendSettings(
            registration: registration,
            reason: reason,
            includeAPIKey: includeAPIKey
        )
        return registration
    }

    private func clearStoredBackendAccessToken() {
        guard var registration = settingsStore.loadRemotePushRegistration(),
              registration.accessToken != nil || registration.accessTokenExpiresAt != nil else {
            return
        }

        registration.accessToken = nil
        registration.accessTokenExpiresAt = nil
        settingsStore.saveRemotePushRegistration(registration)
        log(.warning, "백엔드 401 응답으로 저장된 access token을 삭제했습니다. deviceID=\(registration.deviceID)")
    }

    private func updateBackendSettings(
        registration: RemotePushRegistration,
        reason: String,
        includeAPIKey: Bool = false
    ) async throws {
        let trimmedAPIKey = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let hasRemoteUsableKey = !trimmedAPIKey.isEmpty || isBackendOpenAIKeyConfigured
        let hasPushToken = !registration.apnsToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let shouldEnableRemotePush = isRunning && hasPushToken && hasRemoteUsableKey
        let shouldUploadAPIKey = !trimmedAPIKey.isEmpty && (includeAPIKey || !isBackendOpenAIKeyConfigured)
        let backendSettings = isCommunitySignedIn ? settings : settings.withQuestionPrivacy(false)
        try await remotePushBackendClient.updateSchedule(
            registration: registration,
            settings: backendSettings,
            apiKey: shouldUploadAPIKey ? trimmedAPIKey : nil,
            enabled: shouldEnableRemotePush
        )
        if shouldUploadAPIKey {
            isBackendOpenAIKeyConfigured = true
        }
        log(.info, "백엔드 학습 설정을 동기화했습니다. reason=\(reason), pushEnabled=\(shouldEnableRemotePush), apiKeyUploaded=\(shouldUploadAPIKey)")
    }

    private static var backendAPNSEnvironment: String {
        #if DEBUG
        return "sandbox"
        #else
        return "production"
        #endif
    }

    #if os(iOS)
    func registerRemotePushDeviceToken(_ deviceToken: Data) async {
        let token = Self.hexDeviceToken(deviceToken)
        do {
            let existingRegistration = settingsStore.loadRemotePushRegistration()
            let registration: RemotePushRegistration

            if let existingRegistration,
               existingRegistration.apnsToken == token {
                guard let tokenRegistration = await registrationWithAccessToken(existingRegistration, reason: "device-token-existing") else {
                    return
                }
                registration = tokenRegistration
            } else if let existingRegistration {
                guard let tokenRegistration = await registrationWithAccessToken(existingRegistration, reason: "device-token-update") else {
                    return
                }
                registration = try await remotePushBackendClient.updatePushToken(
                    registration: tokenRegistration,
                    apnsToken: token,
                    apnsEnvironment: Self.backendAPNSEnvironment
                )
                settingsStore.saveRemotePushRegistration(registration)
                log(.info, "서버 push 백엔드의 iPhone APNs 토큰을 갱신했습니다.")
            } else {
                registration = try await remotePushBackendClient.registerDevice(
                    apnsToken: token,
                    language: settings.appLanguage,
                    timezone: TimeZone.current.identifier,
                    apnsEnvironment: Self.backendAPNSEnvironment
                )
                settingsStore.saveRemotePushRegistration(registration)
                log(.info, "서버 push 백엔드에 iPhone 기기를 등록했습니다.")
            }

            try await updateBackendSettings(
                registration: registration,
                reason: "device-token"
            )
            await refreshBackendStudyIfPossible(updateVisibleQuestion: false)
        } catch {
            log(.warning, "서버 push 백엔드 등록 실패: \(error.localizedDescription)")
        }
    }

    private func syncRemotePushScheduleIfPossible(reason: String) async {
        guard let storedRegistration = settingsStore.loadRemotePushRegistration(),
              let registration = await registrationWithAccessToken(storedRegistration, reason: reason) else {
            return
        }

        do {
            try await updateBackendSettings(
                registration: registration,
                reason: reason
            )
        } catch {
            log(.warning, "서버 push 일정 동기화 실패: \(error.localizedDescription)")
        }
    }

    private static func hexDeviceToken(_ token: Data) -> String {
        token.map { String(format: "%02x", $0) }.joined()
    }
    #else
    private func syncRemotePushScheduleIfPossible(reason: String) async {}
    #endif

    private func ensureCloudQuestionPushSubscription() async {
        #if os(iOS)
        guard isCloudSyncEnabled else {
            return
        }

        guard let cloudSyncService = resolvedCloudSyncService() else {
            log(.warning, "CloudKit push 구독을 설정할 수 없습니다. iCloud 권한을 확인하세요.")
            return
        }

        do {
            try await cloudSyncService.ensureQuestionPushSubscription(
                language: settings.appLanguage,
                sound: settings.notificationSound
            )
            log(.info, "iPhone CloudKit 질문 push 구독을 설정했습니다.")
        } catch {
            log(.warning, "CloudKit push 구독 설정 실패: \(error.localizedDescription)")
        }
        #endif
    }

    private func saveQuestionPushIfNeeded(_ question: QuestionItem) async {
        #if os(iOS)
        return
        #else
        guard isCloudSyncEnabled else {
            return
        }

        guard let cloudSyncService = resolvedCloudSyncService() else {
            log(.warning, "CloudKit push 질문을 저장할 수 없습니다. iCloud 권한을 확인하세요.")
            return
        }

        do {
            try await cloudSyncService.saveQuestionPush(question: question, settings: settings)
            log(.info, "iPhone push용 CloudKit 질문 record를 저장했습니다.")
        } catch {
            log(.warning, "iPhone push용 CloudKit 질문 record 저장 실패: \(error.localizedDescription)")
        }
        #endif
    }

    @discardableResult
    private func ensureLocalRecordExists(for push: CloudQuestionPush, showStatus: Bool = true) -> Bool {
        refreshStudyProgressFromStore()
        guard studyRecord(matching: push.question) == nil else {
            return false
        }

        let pushRecord = StudyRecord(
            question: push.question,
            topic: push.topic.isEmpty ? settings.topic : push.topic,
            difficulty: push.difficulty
        )
        if settingsStore.loadDeletedStudyRecordMarkers().contains(where: { $0.matches(pushRecord) }) {
            if showStatus {
                showNotificationQuestionUnavailable(preserveCurrentQuestion: true)
            }
            log(.info, "이미 삭제되었거나 넘겨진 CloudKit push 질문을 다시 추가하지 않았습니다.")
            return false
        }

        let matchesCurrentQuestion = currentQuestion.map {
            Self.questionsMatch($0, push.question)
        } ?? false

        guard matchesCurrentQuestion || !hasReachedPendingQuestionLimit else {
            if showStatus {
                showPendingQuestionLimitStatus(reason: "CloudKit push 질문 추가")
            } else {
                log(.warning, "CloudKit push 질문 추가를 건너뛰었습니다. 미채점 질문이 \(pendingQuestionCount)개입니다.")
            }
            return false
        }

        let pushSettings = StudySettings(
            topic: push.topic.isEmpty ? settings.topic : push.topic,
            difficulty: push.difficulty,
            appLanguage: settings.appLanguage,
            language: settings.appLanguage.studyLanguage,
            openAIModel: settings.sanitizedOpenAIModel,
            notificationSound: settings.notificationSound,
            customPrompt: settings.customPrompt,
            intervalMinutes: settings.sanitizedIntervalMinutes,
                        maxHistoryCount: settings.sanitizedMaxHistoryCount,
            isQuestionPublic: settings.isQuestionPublic
        )

        settingsStore.appendQuestionToHistory(push.question)
        settingsStore.appendStudyRecord(question: push.question, settings: pushSettings)
        studyRecords = settingsStore.loadStudyRecords()
        return true
    }

    private func markCloudDataChanged(syncDelaySeconds: UInt64 = 2) {
        guard isCloudSyncEnabled else {
            return
        }

        markCloudDataDirtyWithoutScheduling()
        scheduleCloudSync(delaySeconds: syncDelaySeconds)
    }

    private func markCloudDataDirtyWithoutScheduling() {
        guard isCloudSyncEnabled else {
            return
        }

        let updatedAt = Date()
        settingsStore.saveCloudSyncStateUpdatedAt(updatedAt)
        cloudLastSyncedAt = updatedAt
    }

    private func scheduleCloudSync(delaySeconds: UInt64 = 2) {
        guard isCloudSyncEnabled else {
            return
        }

        cloudSyncTask?.cancel()
        cloudSyncTask = Task { [weak self] in
            if delaySeconds > 0 {
                try? await Task.sleep(nanoseconds: delaySeconds * 1_000_000_000)
                guard !Task.isCancelled else {
                    return
                }
            }

            await self?.waitForActiveCloudSyncIfNeeded()
            guard !Task.isCancelled else {
                return
            }
            await self?.syncCloudNow(updateVisibleQuestion: false)
        }
    }

    private func makeCloudState(updatedAt: Date) -> CloudSyncState {
        CloudSyncState(
            updatedAt: updatedAt,
            apiKey: Self.trimmedOptional(apiKey),
            apiKeyUpdatedAt: lastAPIKeyUpdatedAt,
            settings: normalizedSettings(settings),
            currentQuestion: currentQuestion,
            questionHistory: settingsStore.loadQuestionHistory(),
            lastAnswer: lastAnswer,
            gradingResult: gradingResult,
            isRunning: isRunning,
            hasCompletedOnboarding: hasCompletedOnboarding,
            studyRecords: studyRecords,
            deletedStudyRecordMarkers: settingsStore.loadDeletedStudyRecordMarkers(),
            studyRecordsClearedAt: settingsStore.loadStudyRecordsClearedAt()
        )
    }

    private func remoteStateByFillingMissingAPIKey(_ state: CloudSyncState) -> (state: CloudSyncState, shouldPush: Bool) {
        let resolvedAPIKey = resolvedAPIKeyForCloudSync(
            localAPIKey: apiKey,
            localAPIKeyUpdatedAt: lastAPIKeyUpdatedAt,
            remoteAPIKey: state.apiKey,
            remoteAPIKeyUpdatedAt: state.apiKeyUpdatedAt
        )

        let currentRemoteAPIKey = Self.trimmedOptional(state.apiKey ?? "")
        guard let selectedKey = resolvedAPIKey.key,
              selectedKey != currentRemoteAPIKey else {
            return (state, false)
        }

        var mergedState = state
        mergedState.apiKey = resolvedAPIKey.key
        mergedState.apiKeyUpdatedAt = resolvedAPIKey.updatedAt
        mergedState.updatedAt = max(state.updatedAt, Date())

        if resolvedAPIKey.updatedAt == nil {
            mergedState.apiKeyUpdatedAt = lastAPIKeyUpdatedAt
        }

        if resolvedAPIKey.updatedAt == nil,
           let localUpdatedAt = lastAPIKeyUpdatedAt {
            mergedState.apiKeyUpdatedAt = localUpdatedAt
        }

        return (mergedState, true)
    }

    private func incomingStateMergingLocalData(_ remoteState: CloudSyncState) -> CloudSyncState {
        var mergedState = remoteState
        let resolvedAPIKey = resolvedAPIKeyForCloudSync(
            localAPIKey: apiKey,
            localAPIKeyUpdatedAt: lastAPIKeyUpdatedAt,
            remoteAPIKey: remoteState.apiKey,
            remoteAPIKeyUpdatedAt: remoteState.apiKeyUpdatedAt
        )
        mergedState.apiKey = resolvedAPIKey.key
        mergedState.apiKeyUpdatedAt = resolvedAPIKey.updatedAt

        let maxHistoryCount = max(
            remoteState.settings.sanitizedMaxHistoryCount,
            settings.sanitizedMaxHistoryCount
        )
        let deletedMarkers = mergedDeletedStudyRecordMarkers(
            remote: remoteState.deletedStudyRecordMarkers,
            local: settingsStore.loadDeletedStudyRecordMarkers()
        )
        let recordsClearedAt = mergedStudyRecordsClearedAt(
            remote: remoteState.studyRecordsClearedAt,
            local: settingsStore.loadStudyRecordsClearedAt()
        )
        let mergedRecords = mergedStudyRecords(
            remote: remoteState.studyRecords,
            local: studyRecords,
            deletedMarkers: deletedMarkers,
            recordsClearedAt: recordsClearedAt,
            maxCount: maxHistoryCount
        )

        mergedState.deletedStudyRecordMarkers = deletedMarkers
        mergedState.studyRecordsClearedAt = recordsClearedAt
        mergedState.studyRecords = mergedRecords
        mergedState.questionHistory = mergedQuestionHistory(
            remote: remoteState.questionHistory,
            local: settingsStore.loadQuestionHistory()
        )

        if let currentQuestion = preferredCurrentQuestion(
            local: currentQuestion,
            remote: remoteState.currentQuestion,
            mergedRecords: mergedRecords
        ) {
            mergedState.currentQuestion = currentQuestion
            if let currentRecord = mergedRecords.last(where: {
                studyRecordMatches($0, question: currentQuestion)
            }) {
                mergedState.lastAnswer = currentRecord.answer ?? ""
                mergedState.gradingResult = currentRecord.gradingResult
            }
        } else {
            mergedState.currentQuestion = nil
            mergedState.lastAnswer = ""
            mergedState.gradingResult = nil
        }

        return mergedState
    }

    private func cloudStateContentDiffers(_ lhs: CloudSyncState, _ rhs: CloudSyncState) -> Bool {
        var normalizedLHS = lhs
        var normalizedRHS = rhs
        normalizedLHS.updatedAt = .distantPast
        normalizedRHS.updatedAt = .distantPast
        return normalizedLHS != normalizedRHS
    }

    private func outgoingStateMergingRemoteData(
        _ state: CloudSyncState,
        remoteState: CloudSyncState
    ) -> CloudSyncState {
        var mergedState = state
        let resolvedAPIKey = resolvedAPIKeyForCloudSync(
            localAPIKey: apiKey,
            localAPIKeyUpdatedAt: lastAPIKeyUpdatedAt,
            remoteAPIKey: remoteState.apiKey,
            remoteAPIKeyUpdatedAt: remoteState.apiKeyUpdatedAt
        )
        let previousAPIKey = mergedState.apiKey
        mergedState.apiKey = resolvedAPIKey.key
        mergedState.apiKeyUpdatedAt = resolvedAPIKey.updatedAt

        let maxHistoryCount = max(
            state.settings.sanitizedMaxHistoryCount,
            remoteState.settings.sanitizedMaxHistoryCount
        )

        if previousAPIKey != resolvedAPIKey.key {
            let trimmedResolved = resolvedAPIKey.key ?? ""
            if !isEditingSettings && !trimmedResolved.isEmpty {
                apiKey = trimmedResolved
                draftAPIKey = trimmedResolved
                savedAPIKey = trimmedResolved
                settingsStore.saveAPIKey(trimmedResolved)
                lastAPIKeyUpdatedAt = resolvedAPIKey.updatedAt
                if let updatedAt = resolvedAPIKey.updatedAt {
                    settingsStore.saveOpenAIAPIKeyUpdatedAt(updatedAt)
                } else {
                    settingsStore.saveOpenAIAPIKeyUpdatedAt(nil)
                }
            }

            if resolvedAPIKey.key == nil {
                settingsStore.saveAPIKey("")
                apiKey = ""
                draftAPIKey = ""
                savedAPIKey = ""
                lastAPIKeyUpdatedAt = nil
                settingsStore.saveOpenAIAPIKeyUpdatedAt(nil)
            }

            if errorMessage == strings.apiKeyEmptyDetailed || errorMessage == strings.apiKeyInvalidDetailed {
                errorMessage = nil
            }
            log(.info, "iCloud 원격 OpenAI API 키를 보존해 로컬 변경사항과 함께 저장합니다.")
            hasAPIKeyError = resolvedAPIKey.key == nil
        }

        mergedState.hasCompletedOnboarding = state.hasCompletedOnboarding || remoteState.hasCompletedOnboarding
        let deletedMarkers = mergedDeletedStudyRecordMarkers(
            remote: remoteState.deletedStudyRecordMarkers,
            local: state.deletedStudyRecordMarkers
        )
        let recordsClearedAt = mergedStudyRecordsClearedAt(
            remote: remoteState.studyRecordsClearedAt,
            local: state.studyRecordsClearedAt
        )
        let mergedRecords = mergedStudyRecords(
            remote: remoteState.studyRecords,
            local: state.studyRecords,
            deletedMarkers: deletedMarkers,
            recordsClearedAt: recordsClearedAt,
            maxCount: maxHistoryCount
        )
        let currentCandidate = preferredCurrentQuestion(
            local: state.currentQuestion,
            remote: remoteState.currentQuestion,
            mergedRecords: mergedRecords
        )
        mergedState.studyRecords = mergedRecords
        mergedState.deletedStudyRecordMarkers = deletedMarkers
        mergedState.studyRecordsClearedAt = recordsClearedAt
        mergedState.questionHistory = mergedQuestionHistory(
            remote: remoteState.questionHistory,
            local: state.questionHistory
        )

        if let preferredCurrentQuestion = currentCandidate,
           mergedState.studyRecords.contains(where: {
               studyRecordMatches($0, question: preferredCurrentQuestion)
           }) {
            mergedState.currentQuestion = preferredCurrentQuestion
            if let currentRecord = mergedState.studyRecords.last(where: {
                studyRecordMatches($0, question: preferredCurrentQuestion)
            }) {
                mergedState.lastAnswer = currentRecord.answer ?? ""
                mergedState.gradingResult = currentRecord.gradingResult
            }
        } else {
            mergedState.currentQuestion = nil
            mergedState.lastAnswer = ""
            mergedState.gradingResult = nil
        }

        return mergedState
    }

    private func shouldPreserveLocalAPIKeyDuringSync(
        localAPIKey: String?,
        localAPIKeyUpdatedAt: Date?,
        remoteAPIKey: String?,
        remoteAPIKeyUpdatedAt: Date?
    ) -> Bool {
        let trimmedLocal = Self.trimmedOptional(localAPIKey ?? "")
        let trimmedRemote = Self.trimmedOptional(remoteAPIKey ?? "")

        guard let trimmedLocal else {
            return false
        }

        guard trimmedRemote != trimmedLocal else {
            return false
        }

        guard let localMutationAt = lastLocalSettingsMutationAt else {
            return false
        }

        if trimmedRemote == nil {
            return true
        }

        if let localUpdatedAt = localAPIKeyUpdatedAt,
           let remoteUpdatedAt = remoteAPIKeyUpdatedAt,
           localUpdatedAt >= remoteUpdatedAt {
            return true
        }

        guard localAPIKeyUpdatedAt == nil else {
            return false
        }

        return Date().timeIntervalSince(localMutationAt) <= Self.recentLocalSettingsMutationWindow
    }

    private func resolvedAPIKeyForCloudSync(
        localAPIKey: String?,
        localAPIKeyUpdatedAt: Date?,
        remoteAPIKey: String?,
        remoteAPIKeyUpdatedAt: Date?
    ) -> (key: String?, updatedAt: Date?) {
        if shouldPreserveLocalAPIKeyDuringSync(
            localAPIKey: localAPIKey,
            localAPIKeyUpdatedAt: localAPIKeyUpdatedAt,
            remoteAPIKey: remoteAPIKey,
            remoteAPIKeyUpdatedAt: remoteAPIKeyUpdatedAt
        ) {
            return (Self.trimmedOptional(localAPIKey ?? ""), localAPIKeyUpdatedAt)
        }

        return resolvedAPIKey(
            localAPIKey: localAPIKey,
            localAPIKeyUpdatedAt: localAPIKeyUpdatedAt,
            remoteAPIKey: remoteAPIKey,
            remoteAPIKeyUpdatedAt: remoteAPIKeyUpdatedAt
        )
    }

    private func resolvedAPIKey(
        localAPIKey: String?,
        localAPIKeyUpdatedAt: Date?,
        remoteAPIKey: String?,
        remoteAPIKeyUpdatedAt: Date?
    ) -> (key: String?, updatedAt: Date?) {
        let trimmedLocal = Self.trimmedOptional(localAPIKey ?? "")
        let trimmedRemote = Self.trimmedOptional(remoteAPIKey ?? "")

        switch (trimmedLocal, trimmedRemote) {
        case let (local?, remote?):
            if let localUpdatedAt = localAPIKeyUpdatedAt, let remoteUpdatedAt = remoteAPIKeyUpdatedAt {
                return localUpdatedAt >= remoteUpdatedAt ? (local, localUpdatedAt) : (remote, remoteUpdatedAt)
            }

            if let localUpdatedAt = localAPIKeyUpdatedAt, remoteAPIKeyUpdatedAt == nil {
                return (local, localUpdatedAt)
            }

            if localAPIKeyUpdatedAt == nil, let remoteUpdatedAt = remoteAPIKeyUpdatedAt {
                return (remote, remoteUpdatedAt)
            }

            return (local, localAPIKeyUpdatedAt)
        case (let local?, nil):
            return (local, localAPIKeyUpdatedAt)
        case (nil, let remote?):
            return (remote, remoteAPIKeyUpdatedAt)
        case (nil, nil):
            return (nil, nil)
        }
    }

    private func preferredCurrentQuestion(
        local: QuestionItem?,
        remote: QuestionItem?,
        mergedRecords: [StudyRecord]
    ) -> QuestionItem? {
        let candidates = [local, remote].compactMap { $0 }
        guard !candidates.isEmpty else {
            return nil
        }

        let ungradedCandidates = candidates.filter { question in
            mergedRecords.contains {
                studyRecordMatches($0, question: question) && $0.gradingResult == nil
            }
        }

        return (ungradedCandidates.isEmpty ? candidates : ungradedCandidates)
            .max { $0.createdAt < $1.createdAt }
    }

    private func firstSyncState(from remoteState: CloudSyncState) -> (state: CloudSyncState, shouldPushMergedState: Bool) {
        guard hasMeaningfulLocalCloudData else {
            return (remoteState, false)
        }

        var mergedState = remoteState
        mergedState.updatedAt = Date()
        let resolvedAPIKey = resolvedAPIKeyForCloudSync(
            localAPIKey: apiKey,
            localAPIKeyUpdatedAt: lastAPIKeyUpdatedAt,
            remoteAPIKey: remoteState.apiKey,
            remoteAPIKeyUpdatedAt: remoteState.apiKeyUpdatedAt
        )

        if resolvedAPIKey.key != nil {
            mergedState.apiKey = resolvedAPIKey.key
            mergedState.apiKeyUpdatedAt = resolvedAPIKey.updatedAt
        }

        if mergedState.settings.studyCategories.isEmpty {
            mergedState.settings = synchronizedTopicCategories(
                for: mergedState.settings,
                includeResolvedTopicCategory: true
            )
        }

        if mergedState.settings.selectedStudyCategoryID == nil {
            mergedState.settings = synchronizedTopicCategories(
                for: mergedState.settings,
                includeResolvedTopicCategory: true
            )
        }
        mergedState.hasCompletedOnboarding = remoteState.hasCompletedOnboarding || hasCompletedOnboarding
        mergedState.deletedStudyRecordMarkers = mergedDeletedStudyRecordMarkers(
            remote: remoteState.deletedStudyRecordMarkers,
            local: settingsStore.loadDeletedStudyRecordMarkers()
        )
        mergedState.studyRecordsClearedAt = mergedStudyRecordsClearedAt(
            remote: remoteState.studyRecordsClearedAt,
            local: settingsStore.loadStudyRecordsClearedAt()
        )
        mergedState.studyRecords = mergedStudyRecords(
            remote: remoteState.studyRecords,
            local: studyRecords,
            deletedMarkers: mergedState.deletedStudyRecordMarkers,
            recordsClearedAt: mergedState.studyRecordsClearedAt,
            maxCount: max(
                remoteState.settings.sanitizedMaxHistoryCount,
                settings.sanitizedMaxHistoryCount
            )
        )
        mergedState.questionHistory = mergedQuestionHistory(
            remote: remoteState.questionHistory,
            local: settingsStore.loadQuestionHistory()
        )

        if mergedState.currentQuestion == nil {
            mergedState.currentQuestion = currentQuestion
        }
        if mergedState.lastAnswer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            mergedState.lastAnswer = lastAnswer
        }
        if mergedState.gradingResult == nil {
            mergedState.gradingResult = gradingResult
        }

        return (mergedState, true)
    }

    private var hasMeaningfulLocalCloudData: Bool {
        !studyRecords.isEmpty ||
            !settingsStore.loadQuestionHistory().isEmpty ||
            currentQuestion != nil ||
            !lastAnswer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            gradingResult != nil ||
            !apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            !settingsStore.loadDeletedStudyRecordMarkers().isEmpty ||
            settingsStore.loadStudyRecordsClearedAt() != nil ||
            normalizedSettings(settings) != .default
    }

    private func mergedStudyRecords(
        remote remoteRecords: [StudyRecord],
        local localRecords: [StudyRecord],
        deletedMarkers: [DeletedStudyRecordMarker],
        recordsClearedAt: Date?,
        maxCount: Int
    ) -> [StudyRecord] {
        var recordsByKey: [String: StudyRecord] = [:]

        for record in remoteRecords + localRecords {
            guard !isStudyRecordDeleted(
                record,
                markers: deletedMarkers,
                recordsClearedAt: recordsClearedAt
            ) else {
                continue
            }

            let key = studyRecordMergeKey(record)
            if let existingRecord = recordsByKey[key] {
                recordsByKey[key] = preferredStudyRecord(existingRecord, record)
            } else {
                recordsByKey[key] = record
            }
        }

        let sortedRecords = recordsByKey.values.sorted {
            studyRecordSortDate($0) < studyRecordSortDate($1)
        }
        return Array(sortedRecords.suffix(max(10, maxCount)))
    }

    private func mergedDeletedStudyRecordMarkers(
        remote remoteMarkers: [DeletedStudyRecordMarker],
        local localMarkers: [DeletedStudyRecordMarker]
    ) -> [DeletedStudyRecordMarker] {
        var markersByKey: [String: DeletedStudyRecordMarker] = [:]

        for marker in remoteMarkers + localMarkers {
            let key = [
                marker.recordID,
                marker.mergeKey,
                marker.normalizedQuestion
            ].joined(separator: "|")

            if let existingMarker = markersByKey[key] {
                markersByKey[key] = marker.deletedAt >= existingMarker.deletedAt ? marker : existingMarker
            } else {
                markersByKey[key] = marker
            }
        }

        return Array(
            markersByKey.values
                .sorted { $0.deletedAt < $1.deletedAt }
                .suffix(SettingsStore.maxDeletedStudyRecordMarkerCount)
        )
    }

    private func mergedStudyRecordsClearedAt(remote: Date?, local: Date?) -> Date? {
        switch (remote, local) {
        case (.some(let remote), .some(let local)):
            return max(remote, local)
        case (.some(let remote), .none):
            return remote
        case (.none, .some(let local)):
            return local
        case (.none, .none):
            return nil
        }
    }

    private func isStudyRecordDeleted(
        _ record: StudyRecord,
        markers: [DeletedStudyRecordMarker],
        recordsClearedAt: Date?
    ) -> Bool {
        let sortDate = studyRecordSortDate(record)
        if let recordsClearedAt,
           sortDate <= recordsClearedAt {
            return true
        }

        return markers.contains { marker in
            marker.deletedAt >= sortDate && marker.matches(record)
        }
    }

    private func mergedQuestionHistory(remote: [QuestionItem], local: [QuestionItem]) -> [QuestionItem] {
        var questionsByKey: [String: QuestionItem] = [:]

        for question in remote + local {
            let key = SettingsStore.normalizedQuestionText(question.question)
            if let existingQuestion = questionsByKey[key] {
                questionsByKey[key] = question.createdAt >= existingQuestion.createdAt ? question : existingQuestion
            } else {
                questionsByKey[key] = question
            }
        }

        let sortedQuestions = questionsByKey.values.sorted {
            $0.createdAt < $1.createdAt
        }
        return Array(sortedQuestions.suffix(20))
    }

    private func studyRecordMergeKey(_ record: StudyRecord) -> String {
        DeletedStudyRecordMarker.mergeKey(for: record)
    }

    private func studyRecordMatches(_ record: StudyRecord, question: QuestionItem) -> Bool {
        Self.questionsMatch(record.question, question)
    }

    nonisolated private static func questionsMatch(_ lhs: QuestionItem, _ rhs: QuestionItem) -> Bool {
        lhs.createdAt == rhs.createdAt ||
            SettingsStore.normalizedQuestionText(lhs.question) ==
            SettingsStore.normalizedQuestionText(rhs.question)
    }

    private func preferredStudyRecord(_ existingRecord: StudyRecord, _ candidateRecord: StudyRecord) -> StudyRecord {
        if existingRecord.gradingResult == nil && candidateRecord.gradingResult != nil {
            return candidateRecord
        }
        if (existingRecord.answer ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
           !(candidateRecord.answer ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return candidateRecord
        }

        return studyRecordSortDate(candidateRecord) >= studyRecordSortDate(existingRecord)
            ? candidateRecord
            : existingRecord
    }

    private func studyRecordSortDate(_ record: StudyRecord) -> Date {
        record.answeredAt ?? record.question.createdAt
    }

    private func mergeBackendRecord(_ record: StudyRecord, into records: [StudyRecord]) -> [StudyRecord] {
        var merged = records.filter { $0.id != record.id && !studyRecordMatches($0, question: record.question) }
        merged.append(record)
        return Array(
            merged
                .sorted { studyRecordSortDate($0) < studyRecordSortDate($1) }
                .suffix(settings.sanitizedMaxHistoryCount)
        )
    }

    private func cloudSyncFailureMessage(for error: Error) -> String {
        switch CloudSyncErrorClassifier.kind(for: error) {
        case .quotaExceeded:
            return strings.syncQuotaExceeded
        case .notAuthenticated:
            return strings.syncNotAuthenticated
        case .permissionDenied:
            return strings.syncPermissionDenied
        case .network:
            return strings.syncNetworkUnavailable
        case .serviceUnavailable, .unavailable:
            return strings.syncServiceUnavailable
        case .rateLimited:
            return strings.syncRateLimited
        case .limitExceeded:
            return strings.syncLimitExceeded
        case .conflict:
            return strings.syncConflictRetry
        case .unknown:
            return strings.syncFailed(error.localizedDescription)
        }
    }

    private func applyCloudState(_ state: CloudSyncState, updateVisibleQuestion: Bool = true) {
        let preservedCloudSyncEnabled = isCloudSyncEnabled

        if isEditingSettings {
            didReceiveCloudStateWhileEditing = true
            settingsStore.saveCloudSyncStateUpdatedAt(state.updatedAt)
            if cloudLastSyncedAt == nil || state.updatedAt > cloudLastSyncedAt! {
                cloudLastSyncedAt = state.updatedAt
            }
            log(.info, "설정 편집 중이어서 iCloud 상태 적용을 미뤘습니다.")
            return
        }

        let localSynchronizedSettings = synchronizedTopicCategories(for: settings)
        let sanitizedSettings = synchronizedTopicCategories(
            for: normalizedSettings(state.settings),
            includeResolvedTopicCategory: true
        )
        let effectiveSettings = shouldPreserveLocalSettings(
            local: localSynchronizedSettings,
            remote: sanitizedSettings,
            remoteUpdatedAt: state.updatedAt
        )
            ? localSynchronizedSettings
            : sanitizedSettings
        let mergedMaxHistoryCount = max(
            localSynchronizedSettings.sanitizedMaxHistoryCount,
            sanitizedSettings.sanitizedMaxHistoryCount
        )
        let mergedHasCompletedOnboarding = hasCompletedOnboarding || state.hasCompletedOnboarding
        let localCurrentQuestion = currentQuestion
        let localLastAnswer = lastAnswer
        let localGradingResult = gradingResult
        let localStudyRecords = studyRecords
        let localQuestionHistory = settingsStore.loadQuestionHistory()
        let resolvedAPIKey = resolvedAPIKeyForCloudSync(
            localAPIKey: apiKey,
            localAPIKeyUpdatedAt: lastAPIKeyUpdatedAt,
            remoteAPIKey: state.apiKey,
            remoteAPIKeyUpdatedAt: state.apiKeyUpdatedAt
        )
        let previousAPIKey = Self.trimmedOptional(apiKey)
        let shouldPreserveActiveQuestion = shouldPreserveActiveQuestion(whenApplying: state)
        let mergedDeletedMarkers = mergedDeletedStudyRecordMarkers(
            remote: state.deletedStudyRecordMarkers,
            local: settingsStore.loadDeletedStudyRecordMarkers()
        )
        let mergedRecordsClearedAt = mergedStudyRecordsClearedAt(
            remote: state.studyRecordsClearedAt,
            local: settingsStore.loadStudyRecordsClearedAt()
        )
        let mergedRecords = mergedStudyRecords(
            remote: state.studyRecords,
            local: localStudyRecords,
            deletedMarkers: mergedDeletedMarkers,
            recordsClearedAt: mergedRecordsClearedAt,
            maxCount: mergedMaxHistoryCount
        )
        let mergedHistory = mergedQuestionHistory(
            remote: state.questionHistory,
            local: localQuestionHistory
        )
        let appliedCurrentQuestion: QuestionItem?
        let appliedLastAnswer: String
        let appliedGradingResult: GradingResult?

        settings = effectiveSettings
        if !isEditingSettings {
            draftSettings = effectiveSettings
            savedSettings = effectiveSettings
            if effectiveSettings != sanitizedSettings {
                log(.info, "로컬 설정이 원격 설정보다 최신이라 iCloud 상태의 카테고리/주제 반영을 보류했습니다.")
            }
        }

        if !isEditingSettings {
            if let syncedAPIKey = resolvedAPIKey.key {
                if previousAPIKey != syncedAPIKey {
                    apiKey = syncedAPIKey
                    draftAPIKey = syncedAPIKey
                    savedAPIKey = syncedAPIKey
                    settingsStore.saveAPIKey(syncedAPIKey)
                    log(.info, "원격 OpenAI API 키 동기화를 반영해 앱 키를 갱신했습니다.")
                }

                lastAPIKeyUpdatedAt = resolvedAPIKey.updatedAt
                if let updatedAt = resolvedAPIKey.updatedAt {
                    settingsStore.saveOpenAIAPIKeyUpdatedAt(updatedAt)
                } else {
                    settingsStore.saveOpenAIAPIKeyUpdatedAt(nil)
                }

                hasAPIKeyError = false
            } else if previousAPIKey != nil {
                apiKey = ""
                draftAPIKey = ""
                savedAPIKey = ""
                lastAPIKeyUpdatedAt = nil
                settingsStore.saveAPIKey("")
                settingsStore.saveOpenAIAPIKeyUpdatedAt(nil)
                hasAPIKeyError = true
                log(.warning, "원격 OpenAI API 키가 없어 앱 키를 비웠습니다.")
            }

            if resolvedAPIKey.key == nil {
                hasAPIKeyError = true
            }

            if errorMessage == strings.apiKeyEmptyDetailed || errorMessage == strings.apiKeyInvalidDetailed {
                errorMessage = nil
            }

            if previousAPIKey != resolvedAPIKey.key {
                log(.info, resolvedAPIKey.key == nil
                    ? "iCloud에서 OpenAI API 키가 비어 있어 동기화에서 빈 값으로 반영했습니다."
                    : "iCloud에서 OpenAI API 키를 불러왔습니다.")
            }
        }

        if let syncedAPIKey = resolvedAPIKey.key,
           !isEditingSettings && previousAPIKey == syncedAPIKey,
           lastAPIKeyUpdatedAt == nil,
           resolvedAPIKey.updatedAt != nil {
            lastAPIKeyUpdatedAt = resolvedAPIKey.updatedAt
        }

        if !updateVisibleQuestion {
            appliedCurrentQuestion = localCurrentQuestion
            appliedLastAnswer = localLastAnswer
            appliedGradingResult = localGradingResult
            log(.info, "조용한 iCloud 동기화라 현재 학습 화면은 변경하지 않았습니다.")
        } else if shouldPreserveActiveQuestion, let localCurrentQuestion {
            let activeRecord = mergedRecords.last {
                studyRecordMatches($0, question: localCurrentQuestion)
            }
            appliedCurrentQuestion = localCurrentQuestion
            appliedLastAnswer = activeRecord?.answer ?? localLastAnswer
            appliedGradingResult = activeRecord?.gradingResult ?? localGradingResult
            log(.info, "iCloud 동기화 중 작성 중인 미제출 질문을 유지했습니다.")
        } else {
            let stateQuestion = state.currentQuestion
            if let stateQuestion,
               mergedRecords.contains(where: { studyRecordMatches($0, question: stateQuestion) }) {
                appliedCurrentQuestion = stateQuestion
                appliedLastAnswer = state.lastAnswer
                appliedGradingResult = state.gradingResult
            } else {
                appliedCurrentQuestion = nil
                appliedLastAnswer = ""
                appliedGradingResult = nil
            }
        }

        currentQuestion = appliedCurrentQuestion
        lastAnswer = appliedLastAnswer
        gradingResult = appliedGradingResult
        isRunning = state.isRunning
        hasCompletedOnboarding = mergedHasCompletedOnboarding
        isCloudSyncEnabled = preservedCloudSyncEnabled

        settingsStore.saveSettings(effectiveSettings)
        settingsStore.saveQuestion(appliedCurrentQuestion)
        settingsStore.saveQuestionHistory(mergedHistory)
        settingsStore.saveLastAnswer(appliedLastAnswer)
        settingsStore.saveGradingResult(appliedGradingResult)
        settingsStore.saveIsRunning(state.isRunning)
        settingsStore.saveHasCompletedOnboarding(mergedHasCompletedOnboarding)
        settingsStore.saveIsCloudSyncEnabled(preservedCloudSyncEnabled)
        settingsStore.saveDeletedStudyRecordMarkers(mergedDeletedMarkers)
        settingsStore.saveStudyRecordsClearedAt(mergedRecordsClearedAt)
        settingsStore.replaceStudyRecords(mergedRecords)
        let nextCloudSyncTimestamp = max(
            state.updatedAt,
            cloudLastSyncedAt ?? state.updatedAt,
            lastLocalSettingsMutationAt ?? .distantPast
        )
        settingsStore.saveCloudSyncStateUpdatedAt(nextCloudSyncTimestamp)

        studyRecords = settingsStore.loadStudyRecords()
        savedSettings = effectiveSettings
        cloudLastSyncedAt = nextCloudSyncTimestamp
        restartTimer()
    }

    private func shouldPreserveLocalSettings(
        local: StudySettings,
        remote: StudySettings,
        remoteUpdatedAt: Date
    ) -> Bool {
        guard local != remote else {
            return false
        }

        if hasUserDefinedCategory(in: local) && remoteSettingsAreFallbackOnly(remote, matchingLanguageOf: local) {
            return true
        }

        guard let lastLocalSettingsMutationAt else {
            return false
        }

        let now = Date()
        if now.timeIntervalSince(lastLocalSettingsMutationAt) <= Self.recentLocalSettingsMutationWindow {
            return true
        }

        guard remoteUpdatedAt <= lastLocalSettingsMutationAt else {
            return false
        }

        return true
    }

    private func hasUserDefinedCategory(in settings: StudySettings) -> Bool {
        let fallback = Self.normalizedCategoryLookup(for: StudySettings.fallbackTopic(for: settings.appLanguage))
        return settings.studyCategories.contains { category in
            Self.normalizedCategoryLookup(for: category.title) != fallback
        }
    }

    private func remoteSettingsAreFallbackOnly(_ settings: StudySettings, matchingLanguageOf local: StudySettings) -> Bool {
        let fallback = Self.normalizedCategoryLookup(for: StudySettings.fallbackTopic(for: local.appLanguage))
        return settings.studyCategories.allSatisfy { category in
            Self.normalizedCategoryLookup(for: category.title) == fallback
        }
    }

    private static func normalizedCategoryLookup(for title: String) -> String {
        title
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined()
    }

    private func shouldPreserveActiveQuestion(whenApplying state: CloudSyncState) -> Bool {
        guard let currentQuestion else {
            return false
        }

        if let remoteCurrentQuestion = state.currentQuestion,
           Self.questionsMatch(remoteCurrentQuestion, currentQuestion) {
            return false
        }

        return hasActiveUngradedCurrentQuestion
    }

    private func resolvedCloudSyncService() -> CloudSyncServiceProtocol? {
        if cloudSyncService == nil {
            guard CloudSyncService.canUseCloudKitContainer() else {
                return nil
            }

            cloudSyncService = CloudSyncService()
        }

        return cloudSyncService
    }

    nonisolated private static func isDuplicate(_ question: QuestionItem, in recentQuestions: [QuestionItem]) -> Bool {
        let normalizedQuestion = SettingsStore.normalizedQuestionText(question.question)
        return recentQuestions.contains {
            SettingsStore.normalizedQuestionText($0.question) == normalizedQuestion
        }
    }

    private func handleOpenAIError(_ error: Error) {
        if Self.isAPIKeyError(error) {
            hasAPIKeyError = true
            errorMessage = apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? strings.apiKeyEmptyDetailed
                : strings.apiKeyInvalidDetailed
            log(.error, errorMessage ?? "OpenAI API 키 오류가 발생했습니다.")
        } else {
            hasAPIKeyError = true
            errorMessage = error.localizedDescription
            log(.error, error.localizedDescription)
        }
    }

    private func log(_ level: LogLevel, _ message: String) {
        let entry = AppLogEntry(level: level, message: message)
        settingsStore.appendAppLog(entry)
        loadAppLogPage(appLogPage)
    }

    private func openURLString(_ urlString: String) {
        guard let url = URL(string: urlString) else {
            return
        }

        #if os(macOS)
        NSWorkspace.shared.open(url)
        #elseif os(iOS)
        UIApplication.shared.open(url)
        #endif
    }

    private func normalizedSettings(_ settings: StudySettings) -> StudySettings {
        StudySettings(
            topic: settings.topic,
            difficulty: settings.difficulty,
            appLanguage: settings.appLanguage,
            language: settings.appLanguage.studyLanguage,
            openAIModel: settings.sanitizedOpenAIModel,
            notificationSound: settings.notificationSound,
            customPrompt: settings.customPrompt,
            intervalMinutes: settings.sanitizedIntervalMinutes,
            maxHistoryCount: settings.sanitizedMaxHistoryCount,
            isQuestionPublic: settings.isQuestionPublic,
            studyCategories: settings.studyCategories,
            selectedStudyCategoryID: settings.selectedStudyCategoryID
        )
    }

    private static func defaultFallbackTopic(for appLanguage: AppLanguage) -> String {
        StudySettings.fallbackTopic(for: appLanguage)
    }

    private static func synchronizedTopicCategories(
        for settings: StudySettings,
        fallbackTopicResolver: (AppLanguage) -> String,
        includeResolvedTopicCategory: Bool = false
    ) -> StudySettings {
        let fallbackTopic = fallbackTopicResolver(settings.appLanguage)
        let normalizedTopic = settings.topic.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedReferenceTopic = normalizedTopic.isEmpty ? fallbackTopic : normalizedTopic
        var categories = StudySettings.normalizedCategories(
            categories: settings.studyCategories,
            fallbackTopic: fallbackTopic,
            fallbackTitle: fallbackTopic
        )

        if includeResolvedTopicCategory {
            categories = ensureCustomTopicCategory(
                in: categories,
                fallbackTopic: fallbackTopic,
                resolvedTopic: resolvedReferenceTopic
            )
        }

        if categories.isEmpty {
            categories = []
        }

        let matchedBySelection = categories.first {
            $0.id == settings.selectedStudyCategoryID
        }

        let matchedByName = categories.first {
            normalizedCategoryMatch(lhs: $0.title, rhs: resolvedReferenceTopic)
        }

        let selectedCategoryID: String?
        if let selected = matchedBySelection {
            selectedCategoryID = selected.id
        } else if includeResolvedTopicCategory, let matched = matchedByName {
            selectedCategoryID = matched.id
        } else {
            selectedCategoryID = nil
        }

        let effectiveTopic: String
        if let selectedCategoryID,
           let selectedCategoryTitle = categories.first(where: { $0.id == selectedCategoryID })?.normalizedTitle,
           !selectedCategoryTitle.isEmpty {
            effectiveTopic = selectedCategoryTitle
        } else {
            effectiveTopic = resolvedReferenceTopic
        }

        return StudySettings(
            topic: effectiveTopic,
            difficulty: settings.difficulty,
            appLanguage: settings.appLanguage,
            language: settings.appLanguage.studyLanguage,
            openAIModel: settings.sanitizedOpenAIModel,
            notificationSound: settings.notificationSound,
            customPrompt: settings.customPrompt,
            intervalMinutes: settings.sanitizedIntervalMinutes,
            maxHistoryCount: settings.sanitizedMaxHistoryCount,
            isQuestionPublic: settings.isQuestionPublic,
            studyCategories: categories,
            selectedStudyCategoryID: selectedCategoryID
        )
    }

    private func synchronizedTopicCategories(
        for settings: StudySettings,
        includeResolvedTopicCategory: Bool = false
    ) -> StudySettings {
        Self.synchronizedTopicCategories(
            for: settings,
            fallbackTopicResolver: Self.defaultFallbackTopic,
            includeResolvedTopicCategory: includeResolvedTopicCategory
        )
    }

    private static func normalizedTopicKey(
        for title: String,
        normalizedAgainst fallback: String
    ) -> Bool {
        normalizedCategoryText(for: title) == normalizedCategoryText(for: fallback)
    }

    private static func normalizedCategoryText(for topic: String) -> String {
        topic
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined()
    }

    private static func normalizedCategoryMatch(lhs: String, rhs: String) -> Bool {
        normalizedCategoryText(for: lhs) == normalizedCategoryText(for: rhs)
    }

    private static func ensureCustomTopicCategory(
        in categories: [StudyCategory],
        fallbackTopic: String,
        resolvedTopic: String,
    ) -> [StudyCategory] {
        let normalizedResolved = normalizedCategoryText(for: resolvedTopic)
        let normalizedFallback = normalizedCategoryText(for: fallbackTopic)

        guard !normalizedResolved.isEmpty,
              normalizedResolved != normalizedFallback else {
            return categories
        }

        guard !categories.contains(where: {
            normalizedCategoryMatch(lhs: $0.title, rhs: resolvedTopic)
        }) else {
            return categories
        }

        var merged = categories
        if let fallbackIndex = merged.firstIndex(where: { normalizedCategoryMatch(lhs: $0.title, rhs: fallbackTopic) }) {
            merged.insert(StudyCategory(title: resolvedTopic), at: min(fallbackIndex + 1, merged.count))
        } else {
            merged.append(StudyCategory(title: resolvedTopic))
        }

        return merged
    }

    private func normalizedTopicKey(for topic: String) -> String {
        topic
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined()
    }

    private func categoryID(forTopic topic: String) -> String? {
        settings.studyCategories.first {
            normalizedTopicKey(for: $0.title) == normalizedTopicKey(for: topic)
        }?.id
    }

    private func activateStudyContext(forTopic topic: String) {
        let matchingRecords = studyRecords
            .filter { normalizedTopicKey(for: $0.topic) == normalizedTopicKey(for: topic) }
            .sorted { lhs, rhs in
                let lhsDate = lhs.answeredAt ?? lhs.question.createdAt
                let rhsDate = rhs.answeredAt ?? rhs.question.createdAt
                return lhsDate > rhsDate
            }

        if let preferredRecord = matchingRecords.first(where: { $0.gradingResult == nil }) ?? matchingRecords.first {
            currentQuestion = preferredRecord.question
            lastAnswer = preferredRecord.answer ?? ""
            gradingResult = preferredRecord.gradingResult
            settingsStore.saveQuestion(preferredRecord.question)
            settingsStore.saveLastAnswer(preferredRecord.answer ?? "")
            settingsStore.saveGradingResult(preferredRecord.gradingResult)
        } else {
            currentQuestion = nil
            lastAnswer = ""
            gradingResult = nil
            settingsStore.saveQuestion(nil)
            settingsStore.saveLastAnswer("")
            settingsStore.saveGradingResult(nil)
        }
    }

    private func showStudyScreen(categoryID: String?) {
        guard requirePageAccess(.studyDetail) else {
            return
        }

        #if os(iOS)
        selectedTab = .home
        homeStudyRoute = HomeStudyRoute(categoryID: categoryID)
        #else
        selectedTab = .study
        #endif
    }

    private func communityErrorMessage(for error: Error) -> String {
        if let backendError = error as? RemotePushBackendError,
           case let .httpStatus(statusCode, _, _) = backendError,
           statusCode == 404 {
            return strings.communityUnavailable
        }

        return backendErrorDisplayMessage(error, fallback: strings.communityRequestFailed)
    }

    private func backendErrorDisplayMessage(_ error: Error, fallback: String) -> String {
        if let backendError = error as? RemotePushBackendError,
           let message = backendError.backendMessage,
           !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return message
        }

        let localized = error.localizedDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !localized.isEmpty else {
            return fallback
        }
        return localized
    }

    private func recordMatching(questionCreatedAt: TimeInterval?) -> StudyRecord? {
        guard let questionCreatedAt else {
            return nil
        }

        return studyRecords
            .map {
                (
                    record: $0,
                    distance: abs($0.question.createdAt.timeIntervalSince1970 - questionCreatedAt)
                )
            }
            .filter { $0.distance < 1 }
            .min { $0.distance < $1.distance }?
            .record
    }

    private func studyRecord(matching question: QuestionItem?) -> StudyRecord? {
        guard let question else {
            return nil
        }

        let normalizedQuestion = SettingsStore.normalizedQuestionText(question.question)
        return studyRecords.last {
            $0.question.createdAt == question.createdAt ||
                SettingsStore.normalizedQuestionText($0.question.question) == normalizedQuestion
        }
    }

    nonisolated private static func isAPIKeyError(_ error: Error) -> Bool {
        if let backendError = error as? RemotePushBackendError {
            switch backendError {
            case .httpStatus(let status, let body, let apiError):
                let lowercasedBody = (apiError?.message ?? body).lowercased()
                let code = apiError?.code ?? ""
                return status == 401 ||
                    status == 403 ||
                    code.contains("OPENAI_API_KEY") ||
                    lowercasedBody.contains("api key") ||
                    lowercasedBody.contains("unauthorized")
            case .invalidResponse:
                return false
            }
        }

        return false
    }

    nonisolated private static func isBackendDeviceNotFound(_ error: Error) -> Bool {
        guard let backendError = error as? RemotePushBackendError else {
            return false
        }

        return backendError.backendCode == "DEVICE_NOT_FOUND"
    }

    nonisolated private static func trimmedOptional(_ value: String) -> String? {
        let trimmedValue = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmedValue.isEmpty ? nil : trimmedValue
    }
}
