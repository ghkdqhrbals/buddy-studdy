import Foundation
import Combine
import OSLog
#if os(iOS)
import StoreKit
#endif

private let appStateLogger = Logger(subsystem: "io.github.ghkdqhrbals.StudyMate", category: "app")
private let appAuthLogger = Logger(subsystem: "io.github.ghkdqhrbals.StudyMate", category: "auth")

private enum QuestionGenerationSkip: Error {
    case pendingLimit
    case duplicateQuestion
}

private enum AnswerGradingProcessError: LocalizedError {
    case failed(String)

    var errorDescription: String? {
        switch self {
        case .failed(let message):
            return message
        }
    }
}

private enum AppStateError: LocalizedError {
    case backendStudyMissing
    case missingRemotePushRegistration

    var errorDescription: String? {
        switch self {
        case .backendStudyMissing:
            return "학습 정보를 동기화한 뒤 다시 시도하세요."
        case .missingRemotePushRegistration:
            return "기기 등록 정보를 동기화한 뒤 다시 시도하세요."
        }
    }
}

private enum AppErrorMessageTarget: Equatable {
    case none
    case community
    case notification
}

enum EmailCommunitySignInResult: Equatable {
    case signedIn
    case verificationRequired
    case failed
}

enum BackendStudyLoadState: Equatable {
    case idle
    case loading
    case loaded
    case failed
}

@MainActor
final class AppState: ObservableObject {
    static let developerLogPageSize = 50
    static let maxPendingQuestionCount = 1
    static let communityQuestionPageSize = 20
    static let recordPageSize = 30
    static let maxAPITrafficLogs = 120
    private static let answerGradingPollIntervalMilliseconds = 3_000
    private static let clipboardQuickReadAttempts = 10
    private static let clipboardFallbackAttempts = 70
    private static let clipboardQuickReadIntervalMilliseconds: UInt64 = 8
    private static let clipboardSettingsReadAttempts = 120
    private static let clipboardSettingsReadIntervalMilliseconds: UInt64 = 16
    private static let clipboardStickyReadIntervalMilliseconds: UInt64 = 6
    private static let recentLocalSettingsMutationWindow: TimeInterval = 300
    #if os(iOS)
    private static let appleBillingRecoveryAttempts = 3
    private static let appleBillingRecoveryDelayNanoseconds: [UInt64] = [3_000_000_000, 9_000_000_000]
    #endif

    @Published var settings: StudySettings
    @Published var draftSettings: StudySettings
    @Published var currentQuestion: QuestionItem?
    @Published var lastAnswer: String
    @Published var gradingResult: GradingResult?
    @Published var apiKey: String = ""
    @Published var draftAPIKey: String = ""
    @Published var isGeneratingQuestion = false
    @Published private(set) var generatingQuestionCategoryID: String?
    @Published var isGradingAnswer = false
    @Published private(set) var answerGradingStatusMessage: String?
    @Published var isRunning: Bool
    @Published private var recordsState = RecordsStateStore()
    @Published private var studyRoomState = StudyRoomStateStore()
    @Published private var statsState = StatsStateStore()
    @Published var hasAPIKeyError = false
    @Published var isValidatingAPIKey = false
    @Published private var developerState = DeveloperStateStore()
    @Published var statusMessage: String?
    @Published var errorMessage: String?
    @Published var notificationLandingMessage: String?
    @Published var homeAnnouncement: HomeAnnouncement?
    @Published var selectedTab: AppTab = .study
    @Published var homeStudyRoute: HomeStudyRoute?
    @Published var appRouteRequest: AppRouteRequest?
    @Published var focusedRecordRequest: FocusedRecordRequest?
    @Published var openAIModelOptions: [OpenAIModelOption] = OpenAIModelOption.all
    @Published var hasCompletedOnboarding: Bool
    @Published var isRefreshingVisibleData = false
    @Published private(set) var backendStudyLoadState: BackendStudyLoadState = .idle
    @Published var isCloudSyncEnabled: Bool
    @Published var isCloudSyncing = false
    @Published var activeTerms: [BackendTerms] = []
    @Published var notificationPreferences: [BackendNotificationPreference] = []
    @Published var isLoadingTermsAndPreferences = false
    @Published var isRequiredTermsGatePresented = false
    @Published private(set) var questionQuota: BackendQuestionQuota?
    @Published private(set) var questionQuotaNotice: String?
    @Published private(set) var billingCatalog: BackendBillingCatalog?
    @Published private(set) var billingStatus: BackendBillingStatus?
    @Published private(set) var billingInvoices: [BackendBillingInvoice] = []
    @Published private(set) var isLoadingBilling = false
    @Published private(set) var billingErrorMessage: String?
    @Published private(set) var serviceAvailability = BackendServiceAvailability.operational
    @Published private(set) var isCheckingAppControl = false
    @Published private(set) var appUpdateDecision: BackendAppUpdateDecision?
    @Published private(set) var isCheckingAppUpdate = false
    @Published private(set) var isMaintenanceBypassedForDeveloper = false
    @Published private(set) var pendingQuestionLimitCategoryID: String?
    @Published private var backendRuntimeState = BackendRuntimeStateStore()
    @Published private var communitySessionState: CommunitySessionStateStore
    @Published private var searchState = SearchStateStore()
    private var communityCommentsCache: [String: CommunityCommentsResponse] = [:]

    var appLogs: [AppLogEntry] {
        get {
            developerState.appLogs
        }
        set {
            var nextState = developerState
            nextState.appLogs = newValue
            developerState = nextState
        }
    }

    var appLogTotalCount: Int {
        get {
            developerState.appLogTotalCount
        }
        set {
            var nextState = developerState
            nextState.appLogTotalCount = newValue
            developerState = nextState
        }
    }

    var appLogPage: Int {
        get {
            developerState.appLogPage
        }
        set {
            var nextState = developerState
            nextState.appLogPage = newValue
            developerState = nextState
        }
    }

    var apiTrafficLogs: [APITrafficLogEntry] {
        get {
            developerState.apiTrafficLogs
        }
        set {
            var nextState = developerState
            nextState.apiTrafficLogs = newValue
            developerState = nextState
        }
    }

    var isAPIDebugPanelPresented: Bool {
        get {
            developerFeatureAccess.debugPopupAllowed
                && developerState.isAPIDebugPanelPresented
        }
        set {
            var nextState = developerState
            nextState.isAPIDebugPanelPresented = newValue
                && developerFeatureAccess.debugPopupAllowed
            developerState = nextState
        }
    }

    var isDebuggingEnabled: Bool {
        get {
            developerState.isDebuggingEnabled
        }
        set {
            var nextState = developerState
            nextState.isDebuggingEnabled = newValue
            developerState = nextState
        }
    }

    var debugBackendBaseURL: String {
        get {
            developerState.debugBackendBaseURL
        }
        set {
            var nextState = developerState
            nextState.debugBackendBaseURL = newValue
            developerState = nextState
        }
    }

    var draftDebugBackendBaseURL: String {
        get {
            developerState.draftDebugBackendBaseURL
        }
        set {
            var nextState = developerState
            nextState.draftDebugBackendBaseURL = newValue
            developerState = nextState
        }
    }

    var backendStudyRooms: [BackendStudyRoom] {
        studyRoomState.rooms
    }

    var studyRecords: [StudyRecord] {
        recordsState.records
    }

    var homeStudySearchResults: [StudyCategory]? {
        searchState.homeStudyResults
    }

    var recordSearchResults: [StudyRecord]? {
        searchState.recordResults
    }

    var recordTotalCount: Int {
        max(recordsState.totalCount, studyRecords.filter { $0.gradingResult != nil }.count)
    }

    var isLoadingRecordPage: Bool {
        recordsState.isLoadingPage
    }

    var canLoadMoreRecords: Bool {
        recordsState.canLoadMore
    }

    var recordSearchTotalCount: Int {
        max(searchState.recordTotalCount, recordSearchResults?.count ?? 0)
    }

    var isLoadingRecordSearchPage: Bool {
        searchState.isLoadingRecordPage
    }

    var canLoadMoreRecordSearchResults: Bool {
        searchState.canLoadMoreRecordResults
    }

    var backendStats: BackendStats? {
        statsState.stats
    }

    var backendStatsActivity: BackendStatsActivity? {
        statsState.activity
    }

    var backendStudyGrowth: BackendStudyGrowth? {
        statsState.studyGrowth
    }

    var isBackendStatsLoading: Bool {
        statsState.isLoading
    }

    var isBackendStatsActivityLoading: Bool {
        statsState.isActivityLoading
    }

    var isBackendStudyGrowthLoading: Bool {
        statsState.isStudyGrowthLoading
    }

    var backendStatsErrorMessage: String? {
        statsState.errorMessage
    }

    var backendStatsActivityErrorMessage: String? {
        statsState.activityErrorMessage
    }

    var backendStudyGrowthErrorMessage: String? {
        statsState.studyGrowthErrorMessage
    }

    var backendAccessState: BackendAccessState {
        get {
            backendRuntimeState.accessState
        }
        set {
            var nextState = backendRuntimeState
            nextState.accessState = newValue
            backendRuntimeState = nextState
        }
    }

    var isLoadingBackendSettingsForEditing: Bool {
        get {
            backendRuntimeState.isLoadingSettingsForEditing
        }
        set {
            var nextState = backendRuntimeState
            nextState.isLoadingSettingsForEditing = newValue
            backendRuntimeState = nextState
        }
    }

    var isBackendOpenAIKeyConfigured: Bool {
        get {
            backendRuntimeState.isOpenAIKeyConfigured
        }
        set {
            var nextState = backendRuntimeState
            nextState.isOpenAIKeyConfigured = newValue
            backendRuntimeState = nextState
        }
    }

    var communitySearchText: String {
        get {
            searchState.communityQuery
        }
        set {
            var nextState = searchState
            nextState.communityQuery = newValue
            searchState = nextState
        }
    }

    var communityQuestions: [CommunityQuestion] {
        get {
            communityFeedState.questions
        }
        set {
            var nextState = communityFeedState
            nextState.questions = newValue
            communityFeedState = nextState
        }
    }

    var communityTotalCount: Int {
        get {
            communityFeedState.totalCount
        }
        set {
            var nextState = communityFeedState
            nextState.totalCount = newValue
            communityFeedState = nextState
        }
    }

    var communityOffset: Int {
        get {
            communityFeedState.offset
        }
        set {
            var nextState = communityFeedState
            nextState.offset = newValue
            communityFeedState = nextState
        }
    }

    var isLoadingCommunityQuestions: Bool {
        get {
            communityFeedState.isLoading
        }
        set {
            var nextState = communityFeedState
            nextState.isLoading = newValue
            communityFeedState = nextState
        }
    }

    var communityErrorMessage: String? {
        get {
            communityFeedState.errorMessage
        }
        set {
            var nextState = communityFeedState
            nextState.errorMessage = newValue
            communityFeedState = nextState
        }
    }

    var notifications: [BackendAppNotification] {
        get {
            notificationState.notifications
        }
        set {
            var nextState = notificationState
            nextState.notifications = newValue
            notificationState = nextState
        }
    }

    var notificationUnreadCount: Int {
        get {
            notificationState.unreadCount
        }
        set {
            updateNotificationState { state in
                state.unreadCount = max(0, newValue)
            }
        }
    }

    var notificationTotalCount: Int {
        get {
            notificationState.totalCount
        }
        set {
            var nextState = notificationState
            nextState.totalCount = max(0, newValue)
            notificationState = nextState
        }
    }

    var isLoadingNotifications: Bool {
        get {
            notificationState.isLoading
        }
        set {
            var nextState = notificationState
            nextState.isLoading = newValue
            notificationState = nextState
        }
    }

    var notificationErrorMessage: String? {
        get {
            notificationState.errorMessage
        }
        set {
            var nextState = notificationState
            nextState.errorMessage = newValue
            notificationState = nextState
        }
    }

    var communityProfile: CommunityUserProfile? {
        get {
            communityProfileState.profile
        }
        set {
            var nextState = communityProfileState
            nextState.profile = newValue
            communityProfileState = nextState
        }
    }

    var isUpdatingCommunityProfile: Bool {
        get {
            communityProfileState.isUpdating
        }
        set {
            var nextState = communityProfileState
            nextState.isUpdating = newValue
            communityProfileState = nextState
        }
    }

    var isWithdrawingCommunityAccount: Bool {
        get {
            communityProfileState.isWithdrawing
        }
        set {
            var nextState = communityProfileState
            nextState.isWithdrawing = newValue
            communityProfileState = nextState
        }
    }

    var profileAvatarSymbolName: String {
        get {
            communityProfileState.avatarSymbolName
        }
        set {
            var nextState = communityProfileState
            nextState.avatarSymbolName = newValue
            communityProfileState = nextState
        }
    }

    var profileAvatarImageData: Data? {
        get {
            communityProfileState.avatarImageData
        }
        set {
            var nextState = communityProfileState
            nextState.avatarImageData = newValue
            communityProfileState = nextState
        }
    }

    var profileAvatarColorSeed: String {
        get {
            communityProfileState.avatarColorSeed
        }
        set {
            var nextState = communityProfileState
            nextState.avatarColorSeed = newValue
            communityProfileState = nextState
        }
    }

    var profileAvatarConfig: [String: String]? {
        get {
            communityProfileState.avatarConfig
        }
        set {
            var nextState = communityProfileState
            nextState.avatarConfig = newValue
            communityProfileState = nextState
        }
    }

    var studyCategoriesForDisplay: [StudyCategory] {
        let synchronized = synchronizedTopicCategories(for: settings)
        return synchronized.studyCategories
    }

    var rootStudyCategoriesForDisplay: [StudyCategory] {
        StudyRoomDisplayPolicy.rootCategories(
            from: studyCategoriesForDisplay,
            rooms: backendStudyRooms
        )
    }

    var selectedStudyCategoryIDForDisplay: String? {
        let synchronized = synchronizedTopicCategories(for: settings)
        return synchronized.selectedStudyCategoryID
    }
    @Published var cloudSyncMessage: String?
    @Published var hasCloudSyncError = false
    @Published var cloudLastSyncedAt: Date?
    @Published private var communityFeedState = CommunityFeedStateStore()
    @Published private var communityProfileState = CommunityProfileStateStore()
    @Published private(set) var avatarCatalog: AvatarCatalogResponse?
    @Published private(set) var isLoadingAvatarCatalog = false
    @Published private var notificationState = NotificationStateStore()
    @Published var pageAccessPrompt: PageAccessPrompt?
    @Published private(set) var backendPermissionEvaluations = BackendPermissionEvaluations(permissions: [])
    @Published private(set) var developerFeatureAccess: DeveloperFeatureAccess = .restricted

    private let appLogUseCase: AppLogUseCase
    private let storedBackendIdentityUseCase: StoredBackendIdentityUseCase
    private let communityProfileCacheUseCase: CommunityProfileCacheUseCase
    private let communitySessionUseCase: CommunitySessionUseCase
    private let onboardingStateUseCase: OnboardingStateUseCase
    private let developerSettingsUseCase: DeveloperSettingsUseCase
    private let currentStudySessionUseCase: CurrentStudySessionUseCase
    private let localStudySettingsUseCase: LocalStudySettingsUseCase
    private let cloudSyncStateUseCase: CloudSyncStateUseCase
    private let localStudyRecordUseCase: LocalStudyRecordUseCase
    private let appErrorHandlingUseCase: AppErrorHandlingUseCase
    private let appUseCasesProvider: AppUseCasesProvider
    private var appUseCases: AppUseCases
    private var backendIdentityUseCase: BackendIdentityUseCase { appUseCases.backendIdentity }
    private var appUpdateUseCase: AppUpdateUseCase { appUseCases.appUpdate }
    private var googleSignInUseCase: GoogleSignInUseCase { appUseCases.googleSignIn }
    private var studyRoomUseCase: StudyRoomUseCase { appUseCases.studyRoom }
    private var recordsUseCase: RecordsUseCase { appUseCases.records }
    private var notificationsUseCase: NotificationsUseCase { appUseCases.notifications }
    private var statsUseCase: StatsUseCase { appUseCases.stats }
    private var settingsUseCase: SettingsUseCase { appUseCases.settings }
    private var termsUseCase: TermsUseCase { appUseCases.terms }
    private var communityUseCase: CommunityUseCase { appUseCases.community }
    private var billingUseCase: BillingUseCase { appUseCases.billing }
    private let actionRunner = AppActionRunner()
    private let notificationService: NotificationServicing
    private let cloudSyncProvider: CloudSyncProviding
    private let platformEffectsProvider: AppPlatformEffectsProviding
    private let clipboardProvider: ClipboardProviding
    private let appNotificationEventProvider: AppNotificationEventProviding
    private let appClock: AppClockProviding
    private let appIdentifierProvider: AppIdentifierProviding
    private let appTimeZoneProvider: AppTimeZoneProviding
    private let appSleepProvider: AppSleepProviding
    private let appDistributionContext: AppDistributionContext
    private let appControlProvider: AppControlProviding
    private let appControlSettingsStore: SettingsStore
    private var cloudSyncService: CloudSyncServiceProtocol?
    private var timerTask: Task<Void, Never>?
    private var cloudSyncTask: Task<Void, Never>?
    private var visibleDataRefreshTask: Task<Void, Never>?
    private var backendRecordRefreshTask: Task<Void, Never>?
    private var answerDraftSaveTask: Task<Void, Never>?
    private var protectedPageAccessRefreshTask: Task<Void, Never>?
    private var questionGenerationPollingTask: Task<Void, Never>?
    private var answerGradingPollingTask: Task<StudyRecord, Error>?
    private var answerGradingPollingID: String?
    private var answerGradingOwnerID: String?
    private var answerSubmissionRecordIDs: Set<String> = []
    private var appControlBoundaryTask: Task<Void, Never>?
    private var appControlPolicy: AppControlRemotePolicy?
    #if os(iOS)
    private var appleBillingUpdatesTask: Task<Void, Never>?
    private var appleBillingRecoveryTask: Task<Void, Never>?
    private var recoveringAppleTransactionIDs = Set<UInt64>()
    private var synchronizedAppleTransactionIDs = Set<UInt64>()
    #endif
    private var membershipRefreshOrder = MembershipRefreshOrder()
    private var backendClientGeneration = 0
    private var configuredBackendBaseURLDescription = ""
    private var billingRefreshTask: Task<Void, Never>?
    private var billingRefreshRequestID = 0
    private var billingRefreshInFlightCount = 0
    private var appControlResolution = AppControlResolution.normal
    private var didStartAppControlListener = false
    private var appControlRefreshTask: Task<Bool, Never>?
    private var lastAppControlPresentationKey: String?
    private var pendingTermsRequirementRetry: (() async -> Void)?
    private var pendingAnswerDraft: PendingAnswerDraft?
    private var lastBackgroundQuestionPreparationAt: Date?
    private var didStart = false
    private var didCompleteStartupTasks = false
    private var isCompletingStartupTasks = false
    private var communitySignInRefreshTask: Task<Void, Never>?
    private var savedSettings: StudySettings
    private var savedAPIKey: String
    private var savedDebugBackendBaseURL: String
    private var clipboardPasteRequestID = 0
    private var isEditingSettings = false
    private var didReceiveCloudStateWhileEditing = false
    private var locallyDeletedStudyIDs = Set<Int>()
    private var locallyDeletedStudyTopicKeys = Set<String>()

    private struct PendingAnswerDraft {
        var question: QuestionItem?
        var recordID: String? = nil
        var answer: String
    }
    private var appNotificationEventCancellables: [AnyCancellable] = []
    private var lastAuthTraceMessages: [String: String] = [:]
    private var lastAPIKeyUpdatedAt: Date?
    private var lastLocalSettingsMutationAt: Date?
    lazy var notificationLandingCoordinator = NotificationLandingCoordinator(appState: self)

    var strings: AppStrings {
        #if DEBUG
        if isAppStoreScreenshotFixtureEnabled {
            switch ProcessInfo.processInfo.environment["BUDDYSTUDY_SCREENSHOT_LANGUAGE"]?
                .lowercased() {
            case "ja", "jp", "japanese":
                return AppStrings(language: .japanese)
            case "en", "english":
                return AppStrings(language: .english)
            default:
                return AppStrings(language: .korean)
            }
        }
        #endif
        return AppStrings(language: settings.appLanguage)
    }

    #if DEBUG
    private var isAppStoreScreenshotFixtureEnabled: Bool {
        ProcessInfo.processInfo.environment["BUDDYSTUDY_SCREENSHOT_FIXTURE"] != nil
    }
    #endif

    var settingsEditorStrings: AppStrings {
        AppStrings(language: draftSettings.appLanguage)
    }

    var isCommunitySessionActive: Bool {
        communitySessionState.isSignedIn
    }

    var isCommunitySignedIn: Bool {
        communitySessionState.isSignedIn
    }

    var statusTitle: String {
        strings.statusTitle(isRunning: isRunning)
    }

    var hasUnsavedSettingsChanges: Bool {
        var comparableActiveSettings = normalizedSettings(activeSettingsForEditing)
        var comparableSavedSettings = normalizedSettings(savedSettings)
        if !isCommunitySessionActive {
            comparableActiveSettings = comparableActiveSettings.withQuestionPrivacy(false)
            comparableSavedSettings = comparableSavedSettings.withQuestionPrivacy(false)
        }

        return comparableActiveSettings != comparableSavedSettings ||
            normalizedDebugBackendBaseURL(activeDebugBackendBaseURLForEditing) != normalizedDebugBackendBaseURL(savedDebugBackendBaseURL)
    }

    var isDraftDebugBackendBaseURLValid: Bool {
        let normalizedURL = normalizedDebugBackendBaseURL(draftDebugBackendBaseURL)
        return normalizedURL.isEmpty || resolvedDebugBackendURL(from: normalizedURL) != nil
    }

    var mobileVisibleTab: AppTab {
        switch selectedTab {
        case .home, .records, .statistics, .notifications:
            return selectedTab
        case .study, .settings:
            return .home
        }
    }

    var shouldShowRecordsLoginPage: Bool {
        let shouldShow = !isCommunitySessionActive
        logAuthTrace(
            "mobile_login_gate",
            page: .records,
            reason: "computed-property",
            extra: ["showLoginGate=\(shouldShow)"]
        )
        return shouldShow
    }

    var shouldShowStatisticsLoginPage: Bool {
        let shouldShow = !isCommunitySessionActive
        logAuthTrace(
            "mobile_login_gate",
            page: .statistics,
            reason: "computed-property",
            extra: ["showLoginGate=\(shouldShow)"]
        )
        return shouldShow
    }

    func normalizeSelectedTabForMobile() {
        logAuthTrace("mobile_normalize_tab_start", reason: "normalizeSelectedTabForMobile")
        #if DEBUG
        if ProcessInfo.processInfo.environment["BUDDYSTUDY_SCREENSHOT_FIXTURE"]?
            .lowercased() == "study-tree" {
            selectedTab = .home
            homeStudyRoute = HomeStudyRoute(categoryID: "101", showsTree: true)
            return
        }
        if selectedTab == .study,
           let debugInitialTab = ProcessInfo.processInfo.environment["BUDDYSTUDY_DEBUG_INITIAL_TAB"] {
            switch debugInitialTab.lowercased() {
            case "records":
                selectedTab = .records
            case "statistics", "stats":
                selectedTab = .statistics
            case "notifications":
                selectedTab = .notifications
            default:
                selectedTab = .home
            }
            homeStudyRoute = nil
            logAuthTrace(
                "mobile_normalize_tab_debug_override",
                reason: "normalizeSelectedTabForMobile",
                extra: ["debugInitialTab=\(debugInitialTab)"]
            )
            return
        }
        #endif
        if selectedTab == .study {
            selectedTab = .home
        }
        homeStudyRoute = nil
        logAuthTrace("mobile_normalize_tab_end", reason: "normalizeSelectedTabForMobile")
    }

    func setSelectedTab(_ nextTab: AppTab) {
        logAuthTrace(
            "mobile_tab_request",
            page: protectedPage(for: nextTab),
            reason: "setSelectedTab",
            extra: ["nextTab=\(String(describing: nextTab))"]
        )
        if isEditingSettings && selectedTab == .settings && nextTab != .settings {
            cancelSettingsEditing()
        }

        logAuthTrace(
            "mobile_tab_allowed",
            page: protectedPage(for: nextTab),
            reason: "setSelectedTab",
            extra: ["nextTab=\(String(describing: nextTab))"]
        )
        applySelectedTab(nextTab)
    }

    private func applySelectedTab(_ nextTab: AppTab) {
        selectedTab = nextTab
        if nextTab == .home {
            homeStudyRoute = nil
        }
        logAuthTrace(
            "mobile_tab_applied",
            page: protectedPage(for: nextTab),
            reason: "applySelectedTab",
            extra: ["nextTab=\(String(describing: nextTab))"]
        )
    }

    func openDeepLink(_ url: URL) {
        guard let route = AppRoute(url: url) else {
            log(.warning, "지원하지 않는 딥링크를 무시했습니다. url=\(url.absoluteString)")
            return
        }

        openRoute(route)
    }

    func presentHomeAnnouncement(_ announcement: HomeAnnouncement) {
        selectedTab = .home
        homeStudyRoute = nil
        homeAnnouncement = announcement
    }

    func dismissHomeAnnouncement() {
        homeAnnouncement = nil
    }

    @discardableResult
    func openRouteFromNotification(_ route: AppRoute) -> Bool {
        log(
            .info,
            "push_route_applying route=\(route), selectedTabBefore=\(selectedTab)"
        )
        if route == .home || route == .studyList {
            let opened = openRoute(route)
            log(
                .info,
                "push_route_applied route=\(route), selectedTab=home, presentation=direct"
            )
            return opened
        }
        selectedTab = .notifications
        homeStudyRoute = nil
        appRouteRequest = AppRouteRequest(route: route, presentation: .notificationInbox)
        log(
            .info,
            "push_route_applied route=\(route), selectedTab=notifications, presentation=notificationInbox"
        )
        return true
    }

    @discardableResult
    func openRoute(_ route: AppRoute) -> Bool {
        switch route {
        case .home:
            selectedTab = .home
            homeStudyRoute = nil
            appRouteRequest = nil
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
            selectedTab = .home
            homeStudyRoute = nil
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
        PageAccessPolicy.protectedPage(for: tab)
    }

    private func canAccess(_ page: ProtectedAppPage) -> Bool {
        isCommunitySessionActive || page == .studyDetail
    }

    private func shouldShowLoginGate(for page: ProtectedAppPage) -> Bool {
        let shouldShow = PageAccessPolicy.shouldShowLoginGate(
            for: page,
            isSignedIn: isCommunitySessionActive
        )
        logAuthTrace(
            "page_login_gate_decision",
            page: page,
            reason: "shouldShowLoginGate",
            extra: ["showLoginGate=\(shouldShow)"]
        )
        return shouldShow
    }

    @discardableResult
    private func requirePageAccess(_ page: ProtectedAppPage) -> Bool {
        logAuthTrace(
            "page_access_required_bypassed_to_api_policy",
            page: page,
            reason: "requirePageAccess",
            extra: ["sessionActive=\(isCommunitySessionActive)"]
        )
        return true
    }

    private func redirectToPageAccessGuide(for page: ProtectedAppPage) {
        homeStudyRoute = nil
        focusedRecordRequest = nil
        pageAccessPrompt = PageAccessPolicy.prompt(for: page, strings: strings)
        logAuthTrace("page_access_guide_redirect", page: page, reason: "redirectToPageAccessGuide")
    }

    func dismissPageAccessPrompt() {
        pageAccessPrompt = nil
        logAuthTrace("page_access_prompt_dismiss", reason: "dismissPageAccessPrompt")
    }

    func logMobileAuthView(
        _ event: String,
        page: ProtectedAppPage? = nil,
        reason: String,
        extra: [String] = []
    ) {
        logAuthTrace(event, page: page, reason: reason, extra: extra)
    }

    func isCurrentCommunityUser(id userID: Int) -> Bool {
        if let profile = communityProfile,
           profile.id == userID {
            return true
        }

        return backendAccessState.user.id == Int64(userID)
    }

    func refreshPageAccess(reason: String = "manual") async {
        logAuthTrace("page_access_refresh_skipped", reason: reason, deduplicate: false)
    }

    private func reconcileVisiblePageAccessAfterRefresh() {
        logAuthTrace("page_access_reconcile_skipped", page: currentVisibleProtectedPage(), reason: "reconcileVisiblePageAccessAfterRefresh")
    }

    private func currentVisibleProtectedPage() -> ProtectedAppPage? {
        if homeStudyRoute != nil {
            return .studyDetail
        }

        switch selectedTab {
        case .records:
            return .records
        case .statistics:
            return .statistics
        case .study:
            #if os(macOS)
            return .studyDetail
            #else
            return nil
            #endif
        case .home, .settings, .notifications:
            return nil
        }
    }

    func refreshPermissionEvaluations(reason: String = "manual") async {
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "permissions-\(reason)") else {
            return
        }

        do {
            let evaluations = try await performWithBackendIdentityRecovery(
                registration: registration,
                reason: "permissions-\(reason)",
                operation: { recoveredRegistration in
                    try await self.termsUseCase.fetchPermissionEvaluations(registration: recoveredRegistration)
                }
            )
            backendPermissionEvaluations = evaluations
        } catch {
            log(.warning, "권한 평가 상태 조회 실패: \(error.localizedDescription), reason=\(reason)")
        }
    }

    @discardableResult
    private func handleAppError(
        _ error: Error,
        fallback: String,
        target: AppErrorMessageTarget,
        protectedPage: ProtectedAppPage? = nil,
        termsRetry: (() async -> Void)? = nil
    ) -> Bool {
        let resolution = appErrorResolution(error, fallback: fallback)
        logAuthTrace(
            "app_error_resolution",
            page: protectedPage ?? currentVisibleProtectedPage(),
            reason: "handleAppError",
            extra: [
                "requiresLogin=\(resolution.requiresLogin)",
                "pageAccessDenied=\(resolution.isPageAccessDenied)",
                "resetIdentity=\(resolution.shouldResetBackendIdentity)",
                "requiresTerms=\(resolution.requiresTermsAgreement)",
                "error=\(error.localizedDescription)"
            ],
            deduplicate: false
        )

        if resolution.shouldResetBackendIdentity {
            logAuthTrace("app_error_reset_backend_identity", reason: "handleAppError", deduplicate: false)
            clearStoredBackendAccessToken()
            resetCommunitySignInState()
        }

        if resolution.requiresTermsAgreement {
            clearErrorMessage(target)
            pageAccessPrompt = nil
            pendingTermsRequirementRetry = termsRetry
            presentRequiredTermsGate()
            Task { [weak self] in
                await self?.refreshPermissionEvaluations(reason: "terms-required")
            }
            return true
        }

        if resolution.isPageAccessDenied || resolution.requiresLogin {
            if let page = protectedPage ?? currentVisibleProtectedPage() {
                logAuthTrace("app_error_redirect_login_gate", page: page, reason: "handleAppError", deduplicate: false)
                redirectToPageAccessGuide(for: page)
            }
            clearErrorMessage(target)
            return true
        }

        if let message = resolution.featureMessage {
            applyErrorMessage(message, target: target)
        } else if resolution.shouldClearFeatureMessage {
            clearErrorMessage(target)
        }

        return false
    }

    private func presentRequiredTermsGate() {
        guard isRequiredTermsGatePresented else {
            isRequiredTermsGatePresented = true
            return
        }

        isRequiredTermsGatePresented = false
        Task { [weak self] in
            await Task.yield()
            self?.isRequiredTermsGatePresented = true
        }
    }

    private func applyErrorMessage(_ message: String, target: AppErrorMessageTarget) {
        switch target {
        case .none:
            break
        case .community:
            communityErrorMessage = message
        case .notification:
            updateNotificationState { state in
                state.applyError(message)
            }
        }
    }

    private func clearErrorMessage(_ target: AppErrorMessageTarget) {
        switch target {
        case .none:
            break
        case .community:
            communityErrorMessage = nil
        case .notification:
            updateNotificationState { state in
                state.applyError(nil)
            }
        }
    }

    @discardableResult
    private func handleCommunityError(_ error: Error, fallback: String? = nil) -> Bool {
        handleAppError(error, fallback: fallback ?? strings.communityRequestFailed, target: .community)
    }

    private func clearCommunityErrorForMissingRegistration(reason: String) {
        communityErrorMessage = nil
        log(.warning, "백엔드 등록을 확보하지 못해 커뮤니티 요청을 중단했습니다. reason=\(reason)")
    }

    @discardableResult
    private func handlePageAccessError(_ error: Error, page: ProtectedAppPage) -> Bool {
        handleAppError(error, fallback: "", target: .none, protectedPage: page)
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

    var canAccessDeveloperOptions: Bool {
        developerFeatureAccess.developerOptionsAllowed
    }

    var canShowDebugPopup: Bool {
        developerFeatureAccess.debugPopupAllowed
    }

    private func normalizedDebugBackendBaseURL(_ value: String) -> String {
        appUseCasesProvider.normalizedDebugBackendBaseURL(value)
    }

    private func resolvedDebugBackendURL(from value: String) -> URL? {
        appUseCasesProvider.resolvedDebugBackendURL(from: value)
    }

    private var activeBackendBaseURLDescription: String {
        appUseCasesProvider.displayBaseURL(
            isDebuggingEnabled: isDebuggingEnabled,
            debugBackendBaseURL: debugBackendBaseURL
        )
    }

    private func refreshRemotePushBackendClient(reason: String) {
        guard appUseCasesProvider.usesConfigurableBackendClient else {
            return
        }

        let nextBaseURLDescription = activeBackendBaseURLDescription
        let didChangeBackend = configuredBackendBaseURLDescription != nextBaseURLDescription
        appUseCases = appUseCasesProvider.makeUseCases(
            isDebuggingEnabled: isDebuggingEnabled,
            debugBackendBaseURL: debugBackendBaseURL
        )
        configuredBackendBaseURLDescription = nextBaseURLDescription
        backendClientGeneration += 1
        membershipRefreshOrder.invalidatePendingRequests()
        billingRefreshTask?.cancel()
        billingRefreshTask = nil
        billingRefreshRequestID += 1
        if didChangeBackend {
            billingCatalog = nil
            billingStatus = nil
            billingInvoices = []
            questionQuota = nil
            billingErrorMessage = nil
        }
        log(.info, "백엔드 API 경로를 갱신했습니다. reason=\(reason), baseURL=\(activeBackendBaseURLDescription)")
    }

    var pendingQuestionCount: Int {
        if studyRoomState.hasRooms {
            return studyRoomState.pendingQuestionCount
        }

        return pendingRecordsIncludingCurrent.count
    }

    var hasReachedPendingQuestionLimit: Bool {
        pendingQuestionCount >= Self.maxPendingQuestionCount
    }

    func pendingQuestionCount(for category: StudyCategory) -> Int {
        if let studyID = Int(category.id) {
            let localCount = pendingRecordsIncludingCurrent.filter { $0.studyID == studyID }.count
            if let backendCount = studyRoomState.pendingQuestionCount(for: category) {
                return max(backendCount, localCount)
            }
            return localCount
        }

        if let backendCount = studyRoomState.pendingQuestionCount(for: category) {
            return backendCount
        }

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

    func pendingQuestionCount(categoryID: String?) -> Int {
        guard let categoryID else {
            return pendingQuestionCount
        }

        if let category = settings.category(for: categoryID) {
            return pendingQuestionCount(for: category)
        }

        if let studyID = Int(categoryID) {
            return pendingRecordsIncludingCurrent.filter { $0.studyID == studyID }.count
        }

        return 0
    }

    func hasReachedPendingQuestionLimit(categoryID: String?) -> Bool {
        pendingQuestionCount(categoryID: categoryID) >= Self.maxPendingQuestionCount
    }

    var pendingStudyRecords: [StudyRecord] {
        pendingRecordsIncludingCurrent
            .sorted { $0.question.createdAt > $1.question.createdAt }
    }

    private var pendingRecordsIncludingCurrent: [StudyRecord] {
        recordsState.pendingRecordsIncludingCurrent(
            currentQuestion: currentQuestion,
            gradingResult: gradingResult,
            fallbackTopic: settings.topic,
            fallbackDifficulty: settings.difficulty,
            matches: studyRecordMatches
        )
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
        runtimeDependencies: AppRuntimeDependencies = .live,
        useCaseDependencies: AppUseCaseDependencies? = nil,
        notificationService: NotificationServicing? = nil,
        cloudSyncProvider: CloudSyncProviding? = nil,
        platformEffectsProvider: AppPlatformEffectsProviding? = nil,
        clipboardProvider: ClipboardProviding? = nil,
        appNotificationEventProvider: AppNotificationEventProviding? = nil,
        appClock: AppClockProviding? = nil,
        appIdentifierProvider: AppIdentifierProviding? = nil,
        appTimeZoneProvider: AppTimeZoneProviding? = nil,
        appSleepProvider: AppSleepProviding? = nil,
        appDistributionContext: AppDistributionContext? = nil,
        appControlProvider: AppControlProviding? = nil,
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
        appErrorHandlingUseCase: AppErrorHandlingUseCase = AppErrorHandlingUseCase(),
        cloudSyncService: CloudSyncServiceProtocol? = nil
    ) {
        let notificationService = notificationService ?? runtimeDependencies.notificationService
        let cloudSyncProvider = cloudSyncProvider ?? runtimeDependencies.cloudSyncProvider
        let platformEffectsProvider = platformEffectsProvider ?? runtimeDependencies.platformEffectsProvider
        let clipboardProvider = clipboardProvider ?? runtimeDependencies.clipboardProvider
        let appNotificationEventProvider = appNotificationEventProvider ?? runtimeDependencies.appNotificationEventProvider
        let appClock = appClock ?? runtimeDependencies.appClock
        let appIdentifierProvider = appIdentifierProvider ?? runtimeDependencies.appIdentifierProvider
        let appTimeZoneProvider = appTimeZoneProvider ?? runtimeDependencies.appTimeZoneProvider
        let appSleepProvider = appSleepProvider ?? runtimeDependencies.appSleepProvider
        let appDistributionContext = appDistributionContext
            ?? runtimeDependencies.appDistributionContext
        let useCaseDependencies = useCaseDependencies ?? AppUseCaseDependencies.live(
            settingsStore: settingsStore,
            remotePushBackendClient: remotePushBackendClient,
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
            appErrorHandlingUseCase: appErrorHandlingUseCase,
            appDistributionContext: appDistributionContext
        )
        let localUseCases = useCaseDependencies.localUseCases
        let appUseCasesProvider = useCaseDependencies.appUseCasesProvider
        let loadedLocalStudySettings = localUseCases.localStudySettings.loadSettings()
        let loadedCloudSyncState = localUseCases.cloudSyncState.loadState()
        let loadedSettings = loadedLocalStudySettings.settings
        let loadedHasCompletedOnboarding = localUseCases.onboardingState.hasCompletedOnboarding()
        let synchronizedLoadedSettings = Self.synchronizedTopicCategories(
            for: loadedSettings,
            fallbackTopicResolver: Self.defaultFallbackTopic
        )
        let loadedIsCommunitySignedIn = localUseCases.communitySession.isSignedIn()
        let effectiveLoadedSettings = loadedIsCommunitySignedIn
            ? synchronizedLoadedSettings
            : synchronizedLoadedSettings.withQuestionPrivacy(false)
        if loadedHasCompletedOnboarding, effectiveLoadedSettings != loadedSettings {
            localUseCases.localStudySettings.saveSettings(effectiveLoadedSettings)
        }
        let loadedAPIKey = loadedLocalStudySettings.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let loadedAPIKeyUpdatedAt = loadedLocalStudySettings.openAIAPIKeyUpdatedAt
        let effectiveAPIKeyUpdatedAt = loadedAPIKeyUpdatedAt ?? (loadedAPIKey.isEmpty ? nil : appClock.now)
        let loadedLogPage = localUseCases.appLog.loadLogs(page: 0, pageSize: Self.developerLogPageSize)
        let loadedCloudLastSyncedAt = loadedCloudSyncState.stateUpdatedAt
        let loadedLocalSettingsMutationAt = loadedLocalStudySettings.localSettingsMutationAt
        let loadedDeveloperSettings = localUseCases.developerSettings.prepareForLaunch(
            distribution: appDistributionContext
        )
        let shouldRestoreDeveloperAccess = Self.shouldRestoreDeveloperAccess(
            settings: loadedDeveloperSettings,
            distribution: appDistributionContext
        )
        if loadedDeveloperSettings.isDeveloperAccessUnlocked && !shouldRestoreDeveloperAccess {
            localUseCases.developerSettings.saveDeveloperAccessUnlocked(false)
            localUseCases.developerSettings.saveIsDebuggingEnabled(false)
        }
        let loadedDeveloperFeatureAccess: DeveloperFeatureAccess =
            shouldRestoreDeveloperAccess ? .fullyAllowed : .restricted
        let loadedIsDebuggingEnabled =
            shouldRestoreDeveloperAccess && loadedDeveloperSettings.isDebuggingEnabled
        let loadedDebugBackendBaseURL = appUseCasesProvider.normalizedDebugBackendBaseURL(loadedDeveloperSettings.debugBackendBaseURL)

        self.appLogUseCase = localUseCases.appLog
        self.storedBackendIdentityUseCase = localUseCases.storedBackendIdentity
        self.communityProfileCacheUseCase = localUseCases.communityProfileCache
        self.communitySessionUseCase = localUseCases.communitySession
        self.onboardingStateUseCase = localUseCases.onboardingState
        self.developerSettingsUseCase = localUseCases.developerSettings
        self.currentStudySessionUseCase = localUseCases.currentStudySession
        self.localStudySettingsUseCase = localUseCases.localStudySettings
        self.cloudSyncStateUseCase = localUseCases.cloudSyncState
        self.localStudyRecordUseCase = localUseCases.localStudyRecord
        self.appErrorHandlingUseCase = localUseCases.appErrorHandling
        self.appClock = appClock
        self.appIdentifierProvider = appIdentifierProvider
        self.appTimeZoneProvider = appTimeZoneProvider
        self.appSleepProvider = appSleepProvider
        self.appDistributionContext = appDistributionContext
        self.appControlProvider = appControlProvider ?? FirebaseAppControlProvider()
        self.appControlSettingsStore = settingsStore
        self.settings = effectiveLoadedSettings
        self.draftSettings = effectiveLoadedSettings
        let loadedCurrentStudySession = localUseCases.currentStudySession.loadSession()
        let loadedPendingQuestionGeneration = localUseCases.currentStudySession.loadPendingQuestionGenerationProcess()
        self.currentQuestion = loadedCurrentStudySession.question
        self.lastAnswer = loadedCurrentStudySession.lastAnswer
        self.gradingResult = loadedCurrentStudySession.gradingResult
        self.isGeneratingQuestion = loadedPendingQuestionGeneration != nil
        self.generatingQuestionCategoryID = loadedPendingQuestionGeneration?.studyCategoryID
        let loadedIsRunning = loadedCurrentStudySession.isRunning
        let shouldRecoverLegacyRunningState = loadedHasCompletedOnboarding
            && !loadedIsRunning
            && !localUseCases.currentStudySession.hasExplicitRunningPreference()
            && !loadedAPIKey.isEmpty
        self.isRunning = shouldRecoverLegacyRunningState ? true : loadedIsRunning
        if shouldRecoverLegacyRunningState {
            localUseCases.currentStudySession.saveIsRunning(true)
        }
        self.recordsState = RecordsStateStore(records: localUseCases.localStudyRecord.loadRecords())
        self.statsState = StatsStateStore()
        self.apiKey = loadedAPIKey
        self.draftAPIKey = loadedAPIKey
        self.lastAPIKeyUpdatedAt = effectiveAPIKeyUpdatedAt
        self.savedSettings = effectiveLoadedSettings
        self.savedAPIKey = loadedAPIKey
        self.savedDebugBackendBaseURL = loadedDebugBackendBaseURL
        self.developerState = DeveloperStateStore(
            appLogs: loadedLogPage.entries,
            appLogTotalCount: loadedLogPage.totalCount,
            appLogPage: loadedLogPage.page,
            isDebuggingEnabled: loadedIsDebuggingEnabled,
            debugBackendBaseURL: loadedDebugBackendBaseURL,
            draftDebugBackendBaseURL: loadedDebugBackendBaseURL
        )
        self.developerFeatureAccess = loadedDeveloperFeatureAccess
        self.hasCompletedOnboarding = loadedHasCompletedOnboarding
        self.isCloudSyncEnabled = cloudSyncService == nil ? false : loadedCloudSyncState.isEnabled
        if cloudSyncService == nil {
            localUseCases.cloudSyncState.saveIsEnabled(false)
        }
        self.communitySessionState = CommunitySessionStateStore(isSignedIn: loadedIsCommunitySignedIn)
        let loadedAvatarCache = localUseCases.communityProfileCache.loadAvatarCache {
            appIdentifierProvider.makeIdentifier()
        }
        self.communityProfileState = CommunityProfileStateStore(
            avatarSymbolName: loadedAvatarCache.symbolName,
            avatarImageData: loadedAvatarCache.imageData,
            avatarColorSeed: loadedAvatarCache.colorSeed,
            avatarConfig: loadedAvatarCache.config
        )
        self.cloudLastSyncedAt = loadedCloudLastSyncedAt
        self.lastLocalSettingsMutationAt = loadedLocalSettingsMutationAt ?? loadedCloudLastSyncedAt
        self.notificationService = notificationService
        self.cloudSyncProvider = cloudSyncProvider
        self.platformEffectsProvider = platformEffectsProvider
        self.clipboardProvider = clipboardProvider
        self.appNotificationEventProvider = appNotificationEventProvider
        self.cloudSyncService = cloudSyncService
        self.appUseCasesProvider = appUseCasesProvider
        self.appUseCases = appUseCasesProvider.makeUseCases(
            isDebuggingEnabled: loadedIsDebuggingEnabled,
            debugBackendBaseURL: loadedDebugBackendBaseURL
        )
        self.configuredBackendBaseURLDescription = appUseCasesProvider.displayBaseURL(
            isDebuggingEnabled: loadedIsDebuggingEnabled,
            debugBackendBaseURL: loadedDebugBackendBaseURL
        )
        self.hasAPIKeyError = apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        self.appNotificationEventCancellables = [
            appNotificationEventProvider.observeAPITrafficLogs { [weak self] entry in
                self?.appendAPITrafficLog(entry)
            },
            appNotificationEventProvider.observeBackendUnauthorized { [weak self] in
                self?.clearStoredBackendAccessToken()
            },
        ]

        if shouldRecoverLegacyRunningState {
            log(.info, "백엔드 schedule 상태로 인해 저장된 이전 일시정지 값을 실행 상태로 복구했습니다.")
        }

        if loadedAPIKeyUpdatedAt == nil, !loadedAPIKey.isEmpty {
            localUseCases.localStudySettings.saveAPIKeyUpdatedAt(effectiveAPIKeyUpdatedAt)
        }

        if !hasCompletedOnboarding {
            log(.info, "첫 실행 온보딩이 필요합니다.")
        } else {
            log(.info, "앱 상태를 불러왔습니다.")
        }

        #if DEBUG
        configureAppStoreScreenshotFixtureIfNeeded()
        #endif
        restartTimer()
    }

    #if DEBUG
    private func configureAppStoreScreenshotFixtureIfNeeded() {
        guard let fixture = ProcessInfo.processInfo.environment["BUDDYSTUDY_SCREENSHOT_FIXTURE"]?
            .lowercased() else {
            return
        }

        let screenshotLanguage = ProcessInfo.processInfo
            .environment["BUDDYSTUDY_SCREENSHOT_LANGUAGE"]?
            .lowercased() ?? "ko"
        let language: AppLanguage
        switch screenshotLanguage {
        case "ja", "jp", "japanese":
            language = .japanese
        case "en", "english":
            language = .english
        default:
            language = .korean
        }
        let isKorean = language == .korean
        let isJapanese = language == .japanese
        let now = appClock.now
        let rootTitles: [String]
        switch language {
        case .korean:
            rootTitles = ["SwiftUI 앱 개발", "자료구조와 알고리즘", "영어 회화"]
        case .english:
            rootTitles = ["SwiftUI App Development", "Data Structures & Algorithms", "English Conversation"]
        case .japanese:
            rootTitles = ["SwiftUIアプリ開発", "データ構造とアルゴリズム", "英会話"]
        }
        let categories = [
            StudyCategory(id: "101", title: rootTitles[0], difficulty: Difficulty(level: 6)),
            StudyCategory(id: "201", title: rootTitles[1], difficulty: Difficulty(level: 5)),
            StudyCategory(id: "301", title: rootTitles[2], difficulty: Difficulty(level: 4)),
        ]
        let fixtureSettings = StudySettings(
            topic: rootTitles[0],
            difficulty: Difficulty(level: 6),
            appLanguage: language,
            language: language.studyLanguage,
            customPrompt: StudySettings.defaultCustomPrompt,
            intervalMinutes: 180,
            isQuestionPublic: true,
            studyCategories: categories,
            selectedStudyCategoryID: "101"
        )
        settings = fixtureSettings
        draftSettings = fixtureSettings
        savedSettings = fixtureSettings
        hasCompletedOnboarding = true
        isCloudSyncEnabled = false
        communitySessionState = CommunitySessionStateStore(isSignedIn: true)

        func room(
            _ id: Int,
            _ topic: String,
            parent: Int? = nil,
            order: Int = 0,
            level: Int = 5
        ) -> BackendStudyRoom {
            BackendStudyRoom(
                id: id,
                topic: topic,
                parentStudyId: parent,
                sortOrder: order,
                difficultyLevel: level,
                intervalMinutes: 180,
                enabled: true,
                activeForQuestions: true,
                notificationSound: "default",
                customPrompt: StudySettings.defaultCustomPrompt,
                openAIModel: StudySettings.defaultOpenAIModel,
                maxHistoryCount: 100,
                nextDueAt: now.addingTimeInterval(7_200),
                lastSentAt: now.addingTimeInterval(-3_600),
                lastError: nil,
                pendingQuestion: nil,
                createdAt: now.addingTimeInterval(-2_592_000),
                updatedAt: now
            )
        }

        let topics: [String]
        switch language {
        case .korean:
            topics = [
                "상태 관리", "내비게이션", "비동기 처리", "Observation", "화면 구성", "애니메이션",
                "배열과 해시", "트리 탐색", "시간 복잡도", "일상 대화", "여행 영어",
            ]
        case .english:
            topics = [
                "State Management", "Navigation", "Async Programming", "Observation", "Layout", "Animation",
                "Arrays & Hashing", "Tree Traversal", "Time Complexity", "Daily Conversation", "Travel English",
            ]
        case .japanese:
            topics = [
                "状態管理", "ナビゲーション", "非同期処理", "Observation", "画面レイアウト", "アニメーション",
                "配列とハッシュ", "木構造の探索", "時間計算量", "日常会話", "旅行英語",
            ]
        }
        let rooms = [
            room(101, rootTitles[0], order: 0, level: 6),
            room(102, topics[0], parent: 101, order: 0, level: 6),
            room(103, topics[1], parent: 101, order: 1, level: 5),
            room(104, topics[2], parent: 101, order: 2, level: 7),
            room(105, topics[3], parent: 102, order: 0, level: 6),
            room(106, topics[4], parent: 102, order: 1, level: 5),
            room(107, topics[5], parent: 103, order: 0, level: 7),
            room(201, rootTitles[1], order: 1, level: 5),
            room(202, topics[6], parent: 201, order: 0, level: 5),
            room(203, topics[7], parent: 201, order: 1, level: 6),
            room(204, topics[8], parent: 201, order: 2, level: 5),
            room(301, rootTitles[2], order: 2, level: 4),
            room(302, topics[9], parent: 301, order: 0, level: 4),
            room(303, topics[10], parent: 301, order: 1, level: 5),
        ]
        studyRoomState.replace(with: rooms)

        let recordTopics = [topics[0], topics[2], topics[3], topics[6], topics[7], topics[9]]
        let studyIDs = [102, 104, 105, 202, 203, 302]
        let questionsKO = [
            "@State와 @Binding의 역할 차이를 설명해 보세요.",
            "async/await에서 구조적 동시성이 중요한 이유는 무엇인가요?",
            "Observation 프레임워크가 화면 갱신 범위를 줄이는 방법은?",
            "해시 테이블의 평균 검색 시간 복잡도는 무엇인가요?",
            "깊이 우선 탐색과 너비 우선 탐색은 언제 각각 유용한가요?",
            "처음 만난 사람에게 취미를 자연스럽게 묻는 표현은?",
        ]
        let questionsEN = [
            "How do @State and @Binding differ in SwiftUI?",
            "Why does structured concurrency matter with async/await?",
            "How does Observation reduce unnecessary view updates?",
            "What is the average lookup complexity of a hash table?",
            "When would you choose DFS over BFS, and vice versa?",
            "How can you naturally ask someone about their hobbies?",
        ]
        let questionsJA = [
            "SwiftUIにおける@Stateと@Bindingの違いを説明してください。",
            "async/awaitで構造化並行性が重要な理由は何ですか？",
            "Observationは不要な画面更新をどのように減らしますか？",
            "ハッシュテーブルの平均検索時間計算量は何ですか？",
            "深さ優先探索と幅優先探索はどのように使い分けますか？",
            "初対面の人に趣味を自然に尋ねる英語表現は？",
        ]
        let questionTexts: [String]
        let answers: [String]
        switch language {
        case .korean:
            questionTexts = questionsKO
            answers = [
                "@State는 뷰가 소유하는 값이고 @Binding은 다른 소유자의 값을 양방향으로 연결합니다.",
                "자식 작업의 생명주기와 취소가 부모 작업에 묶여 안전하게 관리되기 때문입니다.",
                "실제로 읽은 속성의 변경만 추적해 관련 뷰를 다시 계산합니다.",
                "충돌이 적절히 관리되면 평균 O(1)입니다.",
                "DFS는 깊은 경로 탐색에, BFS는 최단 단계 탐색에 적합합니다.",
                "What do you like to do in your free time?",
            ]
        case .english:
            questionTexts = questionsEN
            answers = [
                "@State owns local view data, while @Binding provides two-way access to data owned elsewhere.",
                "It ties child-task lifetime and cancellation to a well-defined parent scope.",
                "It tracks accessed properties so only dependent views are invalidated.",
                "Average lookup is O(1) with a well-distributed hash function.",
                "DFS suits deep exploration; BFS is useful for shortest paths by level.",
                "What do you like to do in your free time?",
            ]
        case .japanese:
            questionTexts = questionsJA
            answers = [
                "@Stateはビューが所有する値で、@Bindingは別の所有者の値へ双方向にアクセスします。",
                "子タスクの生存期間とキャンセルを親タスクのスコープで安全に管理できるためです。",
                "実際に参照したプロパティを追跡し、依存するビューだけを更新します。",
                "適切なハッシュ分散であれば平均O(1)です。",
                "DFSは深い経路の探索に、BFSは階層ごとの最短経路探索に向いています。",
                "What do you like to do in your free time?",
            ]
        }
        let gradingFeedback = isKorean
            ? "핵심 개념을 정확하게 설명했어요."
            : (isJapanese ? "重要な概念を正確に説明できています。" : "You explained the core concept clearly.")
        let gradingExplanation = isKorean
            ? "개념과 사용 시점이 잘 연결되어 있습니다."
            : (isJapanese ? "概念と利用場面が明確に結び付いています。" : "The concept and its use case are connected well.")
        let scores = [94, 88, 91, 86, 82, 97, 90, 84, 93, 89, 95, 87]
        let records = (0..<18).map { index -> StudyRecord in
            let item = index % questionTexts.count
            let createdAt = now.addingTimeInterval(TimeInterval(-(index + 1) * 43_200))
            return StudyRecord(
                id: "screenshot-record-\(index)",
                studyID: studyIDs[item],
                question: QuestionItem(
                    question: questionTexts[item],
                    expectedAnswerHint: nil,
                    createdAt: createdAt
                ),
                answer: answers[item],
                gradingResult: GradingResult(
                    score: scores[index % scores.count],
                    isCorrect: scores[index % scores.count] >= 80,
                    feedback: gradingFeedback,
                    explanation: gradingExplanation
                ),
                topic: recordTopics[item],
                difficulty: Difficulty(level: 4 + (index % 4)),
                answeredAt: createdAt.addingTimeInterval(420),
                isPublic: index % 3 != 0,
                likeCount: 4 + index,
                commentCount: index % 5,
                viewCount: 28 + index * 7
            )
        }
        recordsState.replace(with: records)

        let authorNames = isKorean
            ? ["꾸준한개발자", "알고리즘메이트", "영어한스푼"]
            : (isJapanese ? ["毎日デベロッパー", "アルゴリズム仲間", "英語ひとさじ"] : ["Daily Builder", "Algorithm Mate", "English Spoon"])
        let authorBios = isKorean
            ? ["매일 한 개념씩 공부해요", "함께 성장하는 학습자", "오늘의 표현을 나눠요"]
            : (isJapanese ? ["毎日一つずつ学んでいます", "一緒に成長する学習者", "今日の表現を共有します"] : ["Learning one concept every day", "Growing together", "Sharing today's phrase"])
        let authors = [
            CommunityUserProfile(
                id: 701,
                displayName: authorNames[0],
                bio: authorBios[0],
                avatarURL: nil,
                avatarSymbolName: "pixel-fox",
                avatarColorSeed: "avatar-color-mint"
            ),
            CommunityUserProfile(
                id: 702,
                displayName: authorNames[1],
                bio: authorBios[1],
                avatarURL: nil,
                avatarSymbolName: "pixel-owl",
                avatarColorSeed: "avatar-color-blue"
            ),
            CommunityUserProfile(
                id: 703,
                displayName: authorNames[2],
                bio: authorBios[2],
                avatarURL: nil,
                avatarSymbolName: "pixel-cat",
                avatarColorSeed: "avatar-color-pink"
            ),
        ]
        let publicQuestions = (0..<6).map { index -> CommunityQuestion in
            let item = index % questionTexts.count
            return CommunityQuestion(
                id: "screenshot-community-\(index)",
                question: questionTexts[item],
                answer: answers[item],
                gradingResult: records[index].gradingResult,
                topic: recordTopics[item],
                difficultyLevel: 4 + (index % 4),
                status: "ANSWERED",
                source: "STUDY",
                createdAt: now.addingTimeInterval(TimeInterval(-(index + 1) * 5_400)),
                answeredAt: now.addingTimeInterval(TimeInterval(-(index + 1) * 5_100)),
                author: authors[index % authors.count],
                likeCount: [18, 12, 27, 9, 21, 15][index],
                commentCount: [5, 3, 8, 2, 6, 4][index],
                viewCount: [142, 96, 211, 73, 168, 121][index],
                isLikedByMe: index == 0
            )
        }
        communityFeedState.applyPage(
            CommunityQuestionsResponse(
                questions: publicQuestions,
                totalCount: 48,
                limit: 20,
                offset: 0
            ),
            offset: 0,
            reset: true
        )

        let averages = [91, 88, 90, 85, 82, 94]
        let bestScores = [98, 96, 99, 94, 93, 100]
        let correctRates = [92, 86, 90, 83, 80, 95]
        var topicStats: [BackendTopicStats] = []
        for index in recordTopics.indices {
            let sampleCount = 12 + index * 3
            let centerLevel = 0.52 + Double(index) * 0.045
            let lowerBound = 0.43 + Double(index) * 0.04
            let upperBound = 0.61 + Double(index) * 0.04
            let matchingRecords = records.filter { $0.topic == recordTopics[index] }
            let stats = BackendTopicStats(
                topicKey: "fixture-topic-\(index)",
                topic: recordTopics[index],
                topicAliases: [],
                count: sampleCount,
                average: averages[index],
                best: bestScores[index],
                correctRate: correctRates[index],
                levelRange: BackendTopicLevelRange(
                    level: 5 + (index % 3),
                    average: averages[index],
                    sampleCount: sampleCount,
                    centerLevel: centerLevel,
                    lowerBound: lowerBound,
                    upperBound: upperBound
                ),
                latestAt: now.addingTimeInterval(TimeInterval(-index * 3_600)),
                records: matchingRecords
            )
            topicStats.append(stats)
        }
        var nextStatsState = statsState
        let statsRequestID = nextStatsState.beginRequest()
        nextStatsState.applyStats(
            BackendStats(
                totalResponses: 126,
                totalTopics: 11,
                topics: topicStats,
                limit: 8,
                offset: 0,
                generatedAt: now
            ),
            requestID: statsRequestID
        )
        nextStatsState.finishRequest(statsRequestID)

        let activityDays = (0..<24).compactMap { index -> BackendStatsActivityDay? in
            guard index % 4 != 3,
                  let date = Calendar.current.date(byAdding: .day, value: -index, to: now) else {
                return nil
            }
            return BackendStatsActivityDay(
                date: date,
                answerCount: 2 + (index % 5),
                topicCount: 1 + (index % 3),
                topics: Array(recordTopics.prefix(1 + (index % 3))),
                bestLevel: 5.2 + Double(index % 4) * 0.45
            )
        }
        let activityRequestID = nextStatsState.beginActivityRequest()
        nextStatsState.applyActivity(
            BackendStatsActivity(
                days: activityDays,
                streakDays: 12,
                monthAnswerCount: 74,
                generatedAt: now
            ),
            requestID: activityRequestID
        )
        nextStatsState.finishActivityRequest(activityRequestID)

        let growthNodes = rooms.map { studyRoom -> BackendStudyGrowthNode in
            let rootID: Int
            if studyRoom.id >= 300 {
                rootID = 301
            } else if studyRoom.id >= 200 {
                rootID = 201
            } else {
                rootID = 101
            }
            let level = Double(studyRoom.difficultyLevel) + Double(studyRoom.id % 3) * 0.2
            return BackendStudyGrowthNode(
                studyId: studyRoom.id,
                parentStudyId: studyRoom.parentStudyId,
                rootStudyId: rootID,
                topic: studyRoom.topic,
                sortOrder: studyRoom.sortOrder,
                depth: studyRoom.parentStudyId == nil ? 0 : 1,
                childCount: rooms.filter { $0.parentStudyId == studyRoom.id }.count,
                activeForQuestions: true,
                currentLevel: level,
                previousLevel: level - 0.6,
                growth: 0.6,
                answerCount: 8 + (studyRoom.id % 7),
                measuredTopicCount: studyRoom.parentStudyId == nil ? 3 : 1,
                totalTopicCount: studyRoom.parentStudyId == nil ? 5 : 1,
                latestAt: now.addingTimeInterval(-3_600),
                trend: [level - 1.1, level - 0.8, level - 0.5, level - 0.2, level]
            )
        }
        let roots = [101, 201, 301].compactMap { id -> BackendStudyGrowthRoot? in
            guard let studyRoom = rooms.first(where: { $0.id == id }),
                  let node = growthNodes.first(where: { $0.studyId == id }) else {
                return nil
            }
            return BackendStudyGrowthRoot(
                studyId: id,
                topic: studyRoom.topic,
                activeForQuestions: true,
                currentLevel: node.currentLevel,
                previousLevel: node.previousLevel,
                growth: node.growth,
                answerCount: id == 101 ? 68 : (id == 201 ? 37 : 21),
                measuredTopicCount: id == 101 ? 6 : (id == 201 ? 3 : 2),
                totalTopicCount: id == 101 ? 7 : (id == 201 ? 4 : 3),
                trend: node.trend,
                profile: BackendStudyGrowthProfile(
                    achievement: id == 101 ? 0.91 : 0.86,
                    challenge: id == 101 ? 0.72 : 0.61,
                    completion: id == 101 ? 0.84 : 0.78,
                    breadth: id == 101 ? 0.88 : 0.75,
                    depth: id == 101 ? 0.79 : 0.70
                )
            )
        }
        let growthRequestID = nextStatsState.beginStudyGrowthRequest()
        nextStatsState.applyStudyGrowth(
            BackendStudyGrowth(
                roots: roots,
                nodes: growthNodes,
                startAt: now.addingTimeInterval(-7_776_000),
                endAt: now,
                generatedAt: now
            ),
            requestID: growthRequestID
        )
        nextStatsState.finishStudyGrowthRequest(growthRequestID)
        statsState = nextStatsState

        homeStudyRoute = nil
        switch fixture {
        case "study-tree", "tree":
            selectedTab = .home
            homeStudyRoute = HomeStudyRoute(categoryID: "101", showsTree: true)
        case "study-list", "studies":
            selectedTab = .home
            appRouteRequest = AppRouteRequest(route: .studyList)
        case "statistics", "stats":
            selectedTab = .statistics
        case "records":
            selectedTab = .records
        default:
            selectedTab = .home
            appRouteRequest = AppRouteRequest(route: .publicQuestions)
        }
    }
    #endif

    deinit {
        timerTask?.cancel()
        cloudSyncTask?.cancel()
        answerDraftSaveTask?.cancel()
        backendRecordRefreshTask?.cancel()
        protectedPageAccessRefreshTask?.cancel()
        questionGenerationPollingTask?.cancel()
        answerGradingPollingTask?.cancel()
        appControlBoundaryTask?.cancel()
        appControlRefreshTask?.cancel()
        #if os(iOS)
        appleBillingUpdatesTask?.cancel()
        appleBillingRecoveryTask?.cancel()
        #endif
        appControlProvider.stopListening()
    }

    func start() async {
        guard !didStart else {
            return
        }

        didStart = true
        #if os(iOS)
        startAppleBillingTransactionListener()
        #endif
        #if DEBUG
        if isAppStoreScreenshotFixtureEnabled {
            return
        }
        #endif
        let usesRemoteAppControl = await refreshAppControlPolicy()
        guard !isMaintenanceAccessBlocked else {
            return
        }
        if !usesRemoteAppControl {
            await refreshAppUpdate()
        }
        guard hasCompletedOnboarding else {
            log(.info, "온보딩 완료 전이라 시작 작업을 대기합니다.")
            return
        }

        await completeStartupTasksIfNeeded()
    }

    private func completeStartupTasksIfNeeded() async {
        guard !didCompleteStartupTasks,
              !isCompletingStartupTasks,
              hasCompletedOnboarding,
              !isMaintenanceAccessBlocked else {
            return
        }
        isCompletingStartupTasks = true
        defer {
            isCompletingStartupTasks = false
            didCompleteStartupTasks = true
        }

        if isCloudSyncEnabled {
            await syncCloudNow(updateVisibleQuestion: false)
            await ensureCloudQuestionPushSubscription()
        }

        await loadOpenAIModelOptions()
        await refreshBackendSettingsFromServer(reason: "startup")
        await refreshPermissionEvaluations(reason: "startup")
        await refreshNotificationUnreadCount()
        await refreshBackendStudyIfPossible(updateVisibleQuestion: false)
        await resumePendingQuestionGenerationIfNeeded(reason: "startup")
        #if os(iOS)
        await recoverAppleBillingTransactions(reason: "startup")
        #endif
        #if os(iOS)
        if isCommunitySessionActive {
            _ = await notificationService.requestAuthorizationIfNeeded(language: settings.appLanguage)
        } else {
            notificationService.deactivateRemoteNotificationsForLogout()
        }
        #else
        _ = await notificationService.requestAuthorizationIfNeeded(language: settings.appLanguage)
        #endif
        await validateAPIKeyOnStartup()
        #if os(macOS)
        await generateDueQuestionIfNeeded(reason: "startup")
        #endif
        restartTimer()
    }

    func handleAppBecameActive() async {
        #if DEBUG
        if isAppStoreScreenshotFixtureEnabled {
            return
        }
        #endif
        let usesRemoteAppControl = await refreshAppControlPolicy()
        guard !isMaintenanceAccessBlocked else {
            return
        }
        if !usesRemoteAppControl {
            await refreshAppUpdate()
        }
        guard hasCompletedOnboarding else {
            return
        }

        // SwiftUI can report the initial active scene while start() is still awaiting its
        // startup refresh. That lifecycle notification must join the startup pass instead of
        // launching the same settings, permissions, and study requests a second time.
        guard !isCompletingStartupTasks else {
            return
        }

        if !didCompleteStartupTasks {
            await completeStartupTasksIfNeeded()
            return
        }

        reloadPersistedState()
        await loadOpenAIModelOptions()
        await refreshBackendSettingsFromServer(reason: "foreground")
        await refreshPermissionEvaluations(reason: "foreground")
        await refreshBackendStudyIfPossible(updateVisibleQuestion: false)
        await resumePendingQuestionGenerationIfNeeded(reason: "foreground")
        #if os(iOS)
        await recoverAppleBillingTransactions(reason: "foreground")
        #endif
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

    var isServiceUnderMaintenance: Bool {
        serviceAvailability.isUnderMaintenance
    }

    var isCheckingAvailabilityControl: Bool {
        isCheckingAppControl
    }

    @discardableResult
    func refreshAppControlPolicy() async -> Bool {
        if let appControlRefreshTask {
            return await appControlRefreshTask.value
        }
        let task = Task { [weak self] in
            guard let self else { return false }
            return await self.performAppControlPolicyRefresh()
        }
        appControlRefreshTask = task
        isCheckingAppControl = true
        let result = await task.value
        appControlRefreshTask = nil
        isCheckingAppControl = false
        return result
    }

    private func performAppControlPolicyRefresh() async -> Bool {
        startAppControlListenerIfNeeded()
        if let fetched = await appControlProvider.fetchAndActivate(),
           isUsableAppControlPolicy(fetched) {
            appControlPolicy = fetched
        }
        guard let policy = appControlPolicy, isUsableAppControlPolicy(policy) else {
            appControlPolicy = nil
            appControlResolution = .normal
            serviceAvailability = .operational
            isMaintenanceBypassedForDeveloper = false
            await recordAppControlEvent(.versionObserved, resolution: .normal)
            return false
        }
        await applyAppControlPolicy(policy, source: "remote-config-fetch")
        return true
    }

    func refreshAvailabilityControl() async {
        _ = await refreshAppControlPolicy()
    }

    private func startAppControlListenerIfNeeded() {
        guard !didStartAppControlListener else { return }
        didStartAppControlListener = true
        appControlProvider.startListening { [weak self] policy in
            guard let self, self.isUsableAppControlPolicy(policy) else { return }
            self.appControlPolicy = policy
            Task {
                await self.applyAppControlPolicy(policy, source: "remote-config-listener")
            }
        }
    }

    private func isUsableAppControlPolicy(_ policy: AppControlRemotePolicy) -> Bool {
        policy.schemaVersion == 1
            && policy.policyID != "bundled-default"
            && policy.publishedAt <= appClock.now.addingTimeInterval(5 * 60)
            && policy.validUntil > appClock.now
    }

    private func applyAppControlPolicy(
        _ policy: AppControlRemotePolicy,
        source: String
    ) async {
        let previous = appControlResolution
        let resolution = AppControlPolicyResolver.resolve(
            policy: policy,
            language: settings.appLanguage,
            channel: appDistributionContext.appControlChannel,
            currentVersion: appDistributionContext.appVersion,
            currentBuild: appDistributionContext.appBuild,
            dismissedOptionalCampaignID: appControlSettingsStore
                .loadDismissedOptionalAppControlCampaignID(),
            now: appClock.now
        )
        appControlResolution = resolution
        if let maintenance = resolution.maintenance {
            serviceAvailability = maintenance
            appUpdateDecision = nil
        } else {
            serviceAvailability = .operational
            isMaintenanceBypassedForDeveloper = false
            appUpdateDecision = resolution.update?.shouldPresent == true
                ? resolution.update
                : nil
        }
        scheduleAppControlBoundary(resolution.nextEvaluationAt)
        log(
            .info,
            "앱 제어 정책을 반영했습니다. policy=\(policy.policyID), revision=\(policy.revision), action=\(resolution.action), source=\(source)"
        )
        await recordAppControlEvent(.policyEvaluated, resolution: resolution)
        if resolution.action == "UP_TO_DATE", resolution.campaignID != nil {
            await recordAppControlEvent(.updated, resolution: resolution)
        }

        let presentationKey = "\(policy.policyID):\(resolution.action)"
        if presentationKey != lastAppControlPresentationKey {
            if resolution.maintenance != nil, previous.maintenance == nil {
                await recordAppControlEvent(.maintenanceShown, resolution: resolution)
            } else if resolution.update?.shouldPresent == true {
                await recordAppControlEvent(.promptShown, resolution: resolution)
            }
            lastAppControlPresentationKey = presentationKey
        }
    }

    private func scheduleAppControlBoundary(_ date: Date?) {
        appControlBoundaryTask?.cancel()
        guard let date else {
            appControlBoundaryTask = nil
            return
        }
        let delay = max(0, date.timeIntervalSince(appClock.now))
        let maximumDelay = Double(UInt64.max) / 1_000_000_000
        let delayNanoseconds = UInt64(min(delay, maximumDelay) * 1_000_000_000)
        let sleepProvider = appSleepProvider
        appControlBoundaryTask = Task { [weak self] in
            try? await sleepProvider.sleep(nanoseconds: delayNanoseconds)
            guard !Task.isCancelled, let self, let policy = self.appControlPolicy else {
                return
            }
            await self.applyAppControlPolicy(policy, source: "policy-time-boundary")
        }
    }

    func refreshAppUpdate() async {
        guard !isCheckingAppUpdate else {
            return
        }
        isCheckingAppUpdate = true
        defer { isCheckingAppUpdate = false }

        guard let registration = await backendRegistrationForOpenAIRequests(reason: "app-update-check") else {
            return
        }
        do {
            let decision = try await appUpdateUseCase.check(
                registration: registration,
                language: settings.appLanguage
            )
            guard decision.updateAvailable, decision.shouldPresent, decision.campaignID != nil else {
                appUpdateDecision = nil
                return
            }
            let isNewPresentation = appUpdateDecision?.campaignID != decision.campaignID
            appUpdateDecision = decision
            if isNewPresentation {
                await recordAppUpdateEvent(.shown, decision: decision)
            }
        } catch {
            log(.warning, "앱 업데이트 정책 확인 실패: \(error.localizedDescription)")
        }
    }

    func dismissOptionalAppUpdate() {
        guard let decision = appUpdateDecision, !decision.isForced else {
            return
        }
        appUpdateDecision = nil
        if appControlPolicy != nil {
            appControlSettingsStore.saveDismissedOptionalAppControlCampaignID(decision.campaignID)
            Task {
                await recordAppControlEvent(.dismissed, resolution: appControlResolution)
            }
            return
        }
        Task {
            await recordAppUpdateEvent(.dismissed, decision: decision)
        }
    }

    func recordAppStoreOpened() {
        guard let decision = appUpdateDecision else {
            return
        }
        if appControlPolicy != nil {
            Task {
                await recordAppControlEvent(.storeOpened, resolution: appControlResolution)
            }
            return
        }
        Task {
            await recordAppUpdateEvent(.appStoreOpened, decision: decision)
        }
    }

    private func recordAppUpdateEvent(
        _ event: BackendAppUpdateEvent,
        decision: BackendAppUpdateDecision
    ) async {
        guard let campaignID = decision.campaignID,
              let registration = storedBackendIdentityUseCase.loadRegistration() else {
            return
        }
        do {
            try await appUpdateUseCase.record(
                registration: registration,
                campaignID: campaignID,
                event: event
            )
        } catch {
            log(.warning, "앱 업데이트 이벤트 기록 실패: event=\(event.rawValue), error=\(error.localizedDescription)")
        }
    }

    private func recordAppControlEvent(
        _ event: BackendAppControlEventType,
        resolution: AppControlResolution
    ) async {
        guard let registration = await backendRegistrationForOpenAIRequests(
            reason: "app-control-\(event.rawValue.lowercased())"
        ) else {
            return
        }
        do {
            try await appUpdateUseCase.recordAppControlEvent(
                registration: registration,
                request: BackendAppControlEventRequest(
                    eventID: appIdentifierProvider.makeIdentifier().lowercased(),
                    event: event,
                    platform: "ios",
                    channel: appDistributionContext.appControlChannel,
                    currentVersion: appDistributionContext.appVersion,
                    currentBuild: appDistributionContext.appBuild,
                    policyID: resolution.policyID,
                    policyRevision: resolution.policyRevision,
                    campaignID: resolution.campaignID,
                    evaluatedAction: resolution.action,
                    occurredAt: appClock.now
                )
            )
        } catch {
            log(
                .warning,
                "앱 제어 이벤트 기록 실패: event=\(event.rawValue), error=\(error.localizedDescription)"
            )
        }
    }

    private var isMaintenanceAccessBlocked: Bool {
        isServiceUnderMaintenance && !isMaintenanceBypassedForDeveloper
    }

    func bypassMaintenanceForDeveloper() async {
        guard canAccessDeveloperOptions else {
            return
        }
        isMaintenanceBypassedForDeveloper = true
        log(.info, "활성화된 개발자 옵션으로 현재 점검 화면을 우회했습니다.")
        if appControlPolicy != nil {
            await recordAppControlEvent(.maintenanceBypassed, resolution: appControlResolution)
        }
        await completeStartupTasksIfNeeded()
    }

    @discardableResult
    func handleBackgroundRefresh() async -> Bool {
        await refreshAvailabilityControl()
        guard !isMaintenanceAccessBlocked else {
            return false
        }
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
        await platformEffectsProvider.runExpiringBackgroundTask(named: "StudyMate.prepareQuestions") { isExpired in
            await self.prepareScheduledQuestionsForLockedDevice(isExpired: isExpired)
        }
    }

    func backgroundRefreshEarliestBeginDate(now: Date? = nil) -> Date {
        refreshStudyProgressFromStore()
        return nextQuestionDueDate(now: now ?? appClock.now)
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

    func refreshBackendRecords() async {
        #if DEBUG
        if isAppStoreScreenshotFixtureEnabled {
            return
        }
        #endif
        if let backendRecordRefreshTask {
            await backendRecordRefreshTask.value
            return
        }

        let task = Task { @MainActor [weak self] in
            guard let self else {
                return
            }
            await loadBackendRecordsPage(reset: true)
        }
        backendRecordRefreshTask = task
        await task.value
        backendRecordRefreshTask = nil
    }

    func loadMoreBackendRecords() async {
        guard recordsState.canLoadMore else {
            return
        }
        await loadBackendRecordsPage(reset: false)
    }

    func fetchBackendRecords(
        studyID: Int,
        limit: Int = 30,
        offset: Int
    ) async throws -> BackendRecordsPage {
        guard let storedRegistration = storedBackendIdentityUseCase.loadRegistration(),
              let registration = await registrationWithAccessToken(
                storedRegistration,
                reason: "study-records"
              ) else {
            throw AppStateError.missingRemotePushRegistration
        }

        return try await performWithBackendIdentityRecovery(
            registration: registration,
            reason: "study-records",
            operation: { recoveredRegistration in
                try await recordsUseCase.fetchRecordsForStudy(
                    registration: recoveredRegistration,
                    studyID: studyID,
                    limit: max(1, min(limit, 100)),
                    offset: max(0, offset),
                    language: settings.appLanguage
                )
            }
        )
    }

    private func loadBackendRecordsPage(reset: Bool) async {
        var loadingState = recordsState
        guard loadingState.beginPageLoad() else {
            return
        }
        recordsState = loadingState

        guard let storedRegistration = storedBackendIdentityUseCase.loadRegistration(),
              let registration = await registrationWithAccessToken(storedRegistration, reason: "records") else {
            finishBackendRecordPageLoad()
            log(.warning, "백엔드 등록이 없어 기록 새로고침을 건너뛰었습니다.")
            return
        }

        let offset = reset ? 0 : recordsState.loadedBackendCount
        await actionRunner.run(
            operation: {
                try await performWithBackendIdentityRecovery(
                    registration: registration,
                    reason: "records",
                    operation: { recoveredRegistration in
                        try await recordsUseCase.fetchRecords(
                            registration: recoveredRegistration,
                            limit: Self.recordPageSize,
                            offset: offset,
                            query: "",
                            language: settings.appLanguage
                        )
                    }
                )
            },
            onSuccess: { recordsPage in
                let pendingRecords = studyRecords.filter { $0.gradingResult == nil }
                applyBackendRecordsPage(
                    recordsPage,
                    pendingRecords: pendingRecords,
                    updateVisibleQuestion: false,
                    preserveLocalQuestionState: true,
                    append: !reset
                )
                var nextState = recordsState
                nextState.applyPage(recordsPage, reset: reset)
                recordsState = nextState
                log(.info, "백엔드 기록만 새로고침했습니다. records=\(recordsPage.records.count)")
            },
            onFailure: { error in
                if Self.isCancellationLikeError(error) {
                    log(.info, "기록 조회 취소를 인증 또는 페이지 접근 오류로 처리하지 않습니다.")
                    return
                }
                if handlePageAccessError(error, page: .records) {
                    return
                }
                log(.warning, "백엔드 기록 새로고침 실패: \(error.localizedDescription)")
            },
            onCompletion: {
                finishBackendRecordPageLoad()
            }
        )
    }

    private func finishBackendRecordPageLoad() {
        var nextState = recordsState
        nextState.finishPageLoad()
        recordsState = nextState
    }

    func refreshNotificationUnreadCount() async {
        let sessionGeneration = communitySessionState.generation
        guard isCurrentCommunitySession(sessionGeneration) else {
            updateNotificationState { state in
                state.applyUnreadCount(0)
            }
            log(.info, "로그아웃 상태라 알림 개수 조회를 건너뛰었습니다.")
            return
        }

        guard let storedRegistration = storedBackendIdentityUseCase.loadRegistration(),
              let registration = await registrationWithAccessToken(storedRegistration, reason: "notification-count") else {
            updateNotificationState { state in
                state.applyUnreadCount(0)
            }
            return
        }
        guard isCurrentCommunitySession(sessionGeneration) else {
            log(.info, "로그아웃으로 알림 개수 조회를 중단했습니다. stage=registration")
            return
        }

        await actionRunner.run(
            operation: {
                try await performWithBackendIdentityRecovery(
                    registration: registration,
                    reason: "notification-count",
                    operation: { recoveredRegistration in
                        try await notificationsUseCase.fetchUnreadCount(registration: recoveredRegistration)
                    }
                )
            },
            onSuccess: { unreadCount in
                guard isCurrentCommunitySession(sessionGeneration) else {
                    log(.info, "로그아웃 후 알림 개수 응답 반영을 건너뛰었습니다.")
                    return
                }
                updateNotificationState { state in
                    state.applyUnreadCount(unreadCount)
                }
            },
            onFailure: { error in
                guard isCurrentCommunitySession(sessionGeneration) else {
                    log(.info, "로그아웃 후 알림 개수 오류 처리를 건너뛰었습니다.")
                    return
                }
                updateNotificationState { state in
                    state.applyUnreadCount(0)
                }
                log(.warning, "알림 개수 조회 실패: \(error.localizedDescription)")
            }
        )
    }

    func loadNotifications(reset: Bool = false) async {
        let sessionGeneration = communitySessionState.generation
        guard isCurrentCommunitySession(sessionGeneration) else {
            updateNotificationState { state in
                state.reset()
            }
            return
        }
        guard !isLoadingNotifications else {
            return
        }
        guard let storedRegistration = storedBackendIdentityUseCase.loadRegistration(),
              let registration = await registrationWithAccessToken(storedRegistration, reason: "notifications") else {
            notificationErrorMessage = strings.myStudyLoginHelp
            return
        }
        guard isCurrentCommunitySession(sessionGeneration) else {
            log(.info, "로그아웃으로 알림 목록 조회를 중단했습니다. stage=registration")
            return
        }

        updateNotificationState { state in
            state.beginLoading()
        }

        let offset = reset ? 0 : notifications.count
        await actionRunner.run(
            operation: {
                try await performWithBackendIdentityRecovery(
                    registration: registration,
                    reason: "notifications",
                    operation: { recoveredRegistration in
                        try await notificationsUseCase.fetchNotifications(
                            registration: recoveredRegistration,
                            limit: 30,
                            offset: offset
                        )
                    }
                )
            },
            onSuccess: { page in
                guard isCurrentCommunitySession(sessionGeneration) else {
                    log(.info, "로그아웃 후 알림 목록 응답 반영을 건너뛰었습니다.")
                    return
                }
                updateNotificationState { state in
                    state.applyPage(page, reset: reset)
                }
            },
            onFailure: { error in
                guard isCurrentCommunitySession(sessionGeneration) else {
                    log(.info, "로그아웃 후 알림 목록 오류 처리를 건너뛰었습니다.")
                    return
                }
                handleAppError(
                    error,
                    fallback: strings.notificationLoadRetryDescription,
                    target: .notification
                )
                log(
                    .warning,
                    "알림 목록 조회 실패: \(appErrorHandlingUseCase.diagnosticDescription(for: error))"
                )
            },
            onCompletion: {
                guard isCurrentCommunitySession(sessionGeneration) else {
                    return
                }
                updateNotificationState { state in
                    state.finishLoading()
                }
            }
        )
    }

    func loadMoreNotificationsIfNeeded(current notification: BackendAppNotification) async {
        guard notificationState.canLoadMore(current: notification) else {
            return
        }
        await loadNotifications(reset: false)
    }

    func markNotificationRead(_ notification: BackendAppNotification) async {
        await markNotificationRead(notificationID: notification.id)
    }

    func markNotificationRead(notificationID: String) async {
        updateNotificationState { state in
            state.markRead(notificationID: notificationID, at: appClock.now)
        }

        await runBackendNotificationMutation(
            reason: "notification-read",
            operation: { recoveredRegistration in
                try await self.notificationsUseCase.markRead(
                    registration: recoveredRegistration,
                    notificationID: notificationID
                )
            },
            onSuccess: {
                await refreshNotificationUnreadCount()
            },
            failureMessage: { "알림 읽음 처리 실패: \($0.localizedDescription)" }
        )
    }

    func markAllNotificationsRead() async {
        await runBackendNotificationMutation(
            reason: "notifications-read-all",
            operation: { recoveredRegistration in
                try await self.notificationsUseCase.markAllRead(registration: recoveredRegistration)
            },
            onSuccess: {
                updateNotificationState { state in
                    state.markAllRead(at: appClock.now)
                }
            },
            failureMessage: { "알림 모두 읽음 처리 실패: \($0.localizedDescription)" }
        )
    }

    func deleteNotification(_ notification: BackendAppNotification) async {
        await runBackendNotificationMutation(
            reason: "notification-delete",
            operation: { recoveredRegistration in
                try await self.notificationsUseCase.deleteNotification(
                    registration: recoveredRegistration,
                    notificationID: notification.id
                )
            },
            onSuccess: {
                updateNotificationState { state in
                    state.delete(notificationID: notification.id)
                }
            },
            failureMessage: { "알림 삭제 실패: \($0.localizedDescription)" }
        )
    }

    func deleteAllNotifications() async {
        await runBackendNotificationMutation(
            reason: "notifications-delete-all",
            operation: { recoveredRegistration in
                try await self.notificationsUseCase.deleteAllNotifications(registration: recoveredRegistration)
            },
            onSuccess: {
                updateNotificationState { state in
                    state.deleteAll()
                }
            },
            failureMessage: { "알림 전체삭제 실패: \($0.localizedDescription)" }
        )
    }

    func removeNotifications(forRecordID recordID: String) async {
        await notificationService.cancelDeliveredQuestionNotifications(recordID: recordID)

        let matchingNotifications = notifications.filter { notification in
            NotificationRouteResolver.route(for: notification) == .recordDetail(recordID: recordID)
        }
        for notification in matchingNotifications {
            await deleteNotification(notification)
        }
    }

    func isBackendRecordNotFound(_ error: Error) -> Bool {
        appErrorHandlingUseCase.isBackendRecordNotFound(error)
    }

    private func runBackendNotificationMutation(
        reason: String,
        operation: (RemotePushRegistration) async throws -> Void,
        onSuccess: () async -> Void = {},
        failureMessage: (Error) -> String
    ) async {
        let sessionGeneration = communitySessionState.generation
        guard isCurrentCommunitySession(sessionGeneration) else {
            log(.info, "로그아웃 상태라 알림 변경 요청을 건너뛰었습니다. reason=\(reason)")
            return
        }
        guard let storedRegistration = storedBackendIdentityUseCase.loadRegistration(),
              let registration = await registrationWithAccessToken(storedRegistration, reason: reason) else {
            return
        }
        guard isCurrentCommunitySession(sessionGeneration) else {
            log(.info, "로그아웃으로 알림 변경 요청을 중단했습니다. stage=registration, reason=\(reason)")
            return
        }

        await actionRunner.runVoid(
            operation: {
                try await performWithBackendIdentityRecovery(
                    registration: registration,
                    reason: reason,
                    operation: operation
                )
            },
            onSuccess: {
                guard isCurrentCommunitySession(sessionGeneration) else {
                    log(.info, "로그아웃 후 알림 변경 응답 반영을 건너뛰었습니다. reason=\(reason)")
                    return
                }
                await onSuccess()
            },
            onFailure: { error in
                guard isCurrentCommunitySession(sessionGeneration) else {
                    log(.info, "로그아웃 후 알림 변경 오류 처리를 건너뛰었습니다. reason=\(reason)")
                    return
                }
                handleAppError(error, fallback: "", target: .notification)
                log(.warning, failureMessage(error))
            }
        )
    }

    private func updateNotificationState(_ mutate: (inout NotificationStateStore) -> Void) {
        let previousUnreadCount = notificationState.unreadCount
        var nextState = notificationState
        mutate(&nextState)
        notificationState = nextState

        if nextState.unreadCount != previousUnreadCount {
            updateApplicationIconBadge(nextState.unreadCount)
        }
    }

    private func updateApplicationIconBadge(_ count: Int) {
        platformEffectsProvider.setApplicationIconBadge(count)
    }

    private func loadOpenAIModelOptions() async {
        await actionRunner.run(
            operation: {
                try await settingsUseCase.fetchOpenAIModelOptions()
            },
            onSuccess: { fetchedOptions in
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
            },
            onFailure: { error in
                openAIModelOptions = OpenAIModelOption.all
                log(.warning, "OpenAI 모델 목록 갱신 실패: \(error.localizedDescription)")
            }
        )
    }

    @discardableResult
    private func refreshBackendStudyIfPossible(
        updateVisibleQuestion: Bool = true,
        preserveLocalSettings: Bool = true
    ) async -> Bool {
        _ = preserveLocalSettings
        backendStudyLoadState = .loading
        guard let storedRegistration = storedBackendIdentityUseCase.loadRegistration(),
              let registration = await registrationWithAccessToken(storedRegistration, reason: "state") else {
            backendStudyLoadState = .failed
            return false
        }

        let didRefresh = await actionRunner.run(
            operation: {
                try await performWithBackendIdentityRecovery(
                    registration: registration,
                    reason: "state",
                    operation: { recoveredRegistration in
                        try await studyRoomUseCase.fetchStudy(
                            registration: recoveredRegistration,
                            limit: 500,
                            offset: 0,
                            query: "",
                            language: settings.appLanguage
                        )
                    }
                )
            },
            onSuccess: { studyPage in
                applyBackendStudyPage(studyPage)
                backendStudyLoadState = .loaded
                let pendingCount = studyPage.studies.compactMap(\.pendingQuestion).count
                statusMessage = updateVisibleQuestion ? strings.refreshed : statusMessage
                log(.info, "백엔드 학습 데이터를 동기화했습니다. studies=\(studyPage.studies.count), pending=\(pendingCount)")
            },
            onFailure: { error in
                backendStudyLoadState = .failed
                handleAppError(error, fallback: strings.pageAccessRequiresLogin, target: .none)
                log(.warning, "백엔드 학습 데이터 동기화 실패: \(error.localizedDescription)")
            }
        ) != nil
        return didRefresh
    }

    private func applyBackendStudyPage(_ studyPage: BackendStudyPage) {
        let cachedRoomsByID = Dictionary(uniqueKeysWithValues: backendStudyRooms.map { ($0.id, $0) })
        let visibleStudies = studyPage.studies
            .filter { !isLocallyDeletedStudy($0) }
            .map { room in
                var mergedRoom = room
                if mergedRoom.latestQuestion == nil {
                    mergedRoom.latestQuestion = cachedRoomsByID[room.id]?.latestQuestion
                }
                return mergedRoom
            }
        let pendingRecords = visibleStudies.compactMap(\.pendingQuestion)
        let visibleStudyIDs = Set(visibleStudies.map(\.id))
        let pendingRecordIDsByStudyID = Dictionary(
            uniqueKeysWithValues: pendingRecords.compactMap { record in
                record.studyID.map { ($0, record.id) }
            }
        )
        let staleLocalPendingRecords = studyRecords.filter { record in
            guard record.gradingResult == nil,
                  let studyID = record.studyID,
                  visibleStudyIDs.contains(studyID) else {
                return false
            }
            return pendingRecordIDsByStudyID[studyID] != record.id
        }
        let authoritativeLocalRecords = studyRecords.filter { record in
            !staleLocalPendingRecords.contains(where: { $0.id == record.id })
        }
        let mergedRecords = pendingRecords.reduce(authoritativeLocalRecords) { records, record in
            mergeBackendRecord(record, into: records)
        }
        if mergedRecords != studyRecords {
            localStudyRecordUseCase.replaceRecords(mergedRecords)
            reloadStudyRecordsFromStore(refreshRooms: false)
        }
        if let currentQuestion,
           staleLocalPendingRecords.contains(where: {
               studyRecordMatches($0, question: currentQuestion)
           }),
           !pendingRecords.contains(where: {
               studyRecordMatches($0, question: currentQuestion)
           }) {
            self.currentQuestion = nil
            lastAnswer = ""
            gradingResult = nil
            currentStudySessionUseCase.saveCurrentQuestionState(
                question: nil,
                lastAnswer: "",
                gradingResult: nil
            )
        }
        studyRoomState.replace(with: visibleStudies)
        studyRoomState.refreshPendingQuestions(from: studyRecords)
        guard !isEditingSettings else {
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

        let categories = visibleStudies.filter { $0.parentStudyId == nil }.map { room in
            let topicKey = Self.normalizedCategoryText(for: room.topic)
            let existing = existingCategoriesByTopic[topicKey]
            return StudyCategory(
                id: String(room.id),
                title: room.topic,
                difficulty: Difficulty(level: room.difficultyLevel),
                customPrompt: room.customPrompt,
                openAIModel: room.openAIModel,
                createdAt: existing?.createdAt ?? room.createdAt
            )
        }

        let selectedRootRoomID = StudyRoomDisplayPolicy.rootRoomID(
            containing: settings.selectedStudyCategoryID.flatMap(Int.init),
            rooms: visibleStudies
        )
        let selectedCategoryID = selectedRootRoomID.map(String.init).flatMap { rootID in
            categories.first { $0.id == rootID }?.id
        } ?? selectedTopicKey.flatMap { key in
            categories.first { Self.normalizedCategoryText(for: $0.title) == key }?.id
        } ?? categories.first?.id
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
        localStudySettingsUseCase.saveSettings(nextSettings)
    }

    private func fetchBackendStudyDetailIfPossible(studyID: Int) async -> BackendStudyRoom? {
        guard let storedRegistration = storedBackendIdentityUseCase.loadRegistration(),
              let registration = await registrationWithAccessToken(storedRegistration, reason: "study-detail") else {
            return nil
        }

        return await actionRunner.run(
            operation: {
                try await performWithBackendIdentityRecovery(
                    registration: registration,
                    reason: "study-detail",
                    operation: { recoveredRegistration in
                        try await studyRoomUseCase.fetchStudyDetail(
                            registration: recoveredRegistration,
                            studyID: studyID,
                            language: settings.appLanguage
                        )
                    }
                )
            },
            onSuccess: { _ in },
            onFailure: { error in
                handleAppError(error, fallback: strings.pageAccessRequiresLogin, target: .none)
                log(.warning, "백엔드 학습 상세 로드 실패: studyID=\(studyID), error=\(error.localizedDescription)")
            }
        )
    }

    private func applyBackendStudyDetail(_ room: BackendStudyRoom) {
        guard !isLocallyDeletedStudy(room) else {
            return
        }

        let returnedRecords = [room.pendingQuestion, room.latestQuestion].compactMap { $0 }
        let returnedPendingID = room.pendingQuestion?.id
        let authoritativeRecords = studyRecords.filter { record in
            guard record.studyID == room.id,
                  record.gradingResult == nil else {
                return true
            }
            return record.id == returnedPendingID
        }
        let mergedRecords = returnedRecords.reduce(authoritativeRecords) { records, record in
            mergeBackendRecord(record, into: records)
        }
        if mergedRecords != studyRecords {
            localStudyRecordUseCase.replaceRecords(mergedRecords)
            reloadStudyRecordsFromStore(refreshRooms: false)
        }

        studyRoomState.upsertStudy(room)
        studyRoomState.refreshPendingQuestions(from: studyRecords)
    }

    private func refreshBackendStudyRoomsFromRecords() {
        studyRoomState.refreshPendingQuestions(from: studyRecords)
    }

    private func replaceHomeStudySearchResults(_ results: [StudyCategory]?) {
        var nextState = searchState
        nextState.replaceHomeStudyResults(results)
        searchState = nextState
    }

    private func replaceRecordSearchResults(_ results: [StudyRecord]?) {
        var nextState = searchState
        nextState.replaceRecordResults(results)
        searchState = nextState
    }

    func searchBackendStudies(query: String) async {
        let trimmedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedQuery.isEmpty else {
            replaceHomeStudySearchResults(nil)
            return
        }

        guard let storedRegistration = storedBackendIdentityUseCase.loadRegistration(),
              let registration = await registrationWithAccessToken(storedRegistration, reason: "study-search") else {
            replaceHomeStudySearchResults([])
            return
        }

        do {
            let page = try await performWithBackendIdentityRecovery(
                registration: registration,
                reason: "study-search",
                operation: { recoveredRegistration in
                    try await studyRoomUseCase.fetchStudy(
                        registration: recoveredRegistration,
                        limit: 100,
                        offset: 0,
                        query: trimmedQuery,
                        language: settings.appLanguage
                    )
                }
            )
            let visibleStudies = page.studies.filter { !isLocallyDeletedStudy($0) }
            let existingCategoriesByTopic = settings.studyCategories.reduce(into: [String: StudyCategory]()) { result, category in
                let key = Self.normalizedCategoryText(for: category.title)
                if result[key] == nil {
                    result[key] = category
                }
            }
            replaceHomeStudySearchResults(visibleStudies.map { room in
                let existing = existingCategoriesByTopic[Self.normalizedCategoryText(for: room.topic)]
                return StudyCategory(
                    id: existing?.id ?? String(room.id),
                    title: room.topic,
                    difficulty: Difficulty(level: room.difficultyLevel),
                    customPrompt: room.customPrompt,
                    openAIModel: room.openAIModel,
                    createdAt: existing?.createdAt ?? room.createdAt
                )
            })
        } catch {
            replaceHomeStudySearchResults([])
            log(.warning, "학습 검색 실패: \(error.localizedDescription)")
        }
    }

    func clearBackendStudySearchResults() {
        replaceHomeStudySearchResults(nil)
    }

    func searchBackendRecords(query: String, reset: Bool = true) async {
        let trimmedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedQuery.isEmpty else {
            replaceRecordSearchResults(nil)
            return
        }

        var loadingState = searchState
        guard let requestID = loadingState.beginRecordPage(query: trimmedQuery, reset: reset) else {
            return
        }
        searchState = loadingState

        guard let storedRegistration = storedBackendIdentityUseCase.loadRegistration(),
              let registration = await registrationWithAccessToken(storedRegistration, reason: "record-search") else {
            finishBackendRecordSearchPage(query: trimmedQuery, requestID: requestID)
            return
        }

        do {
            let offset = reset ? 0 : searchState.recordLoadedCount
            let page = try await performWithBackendIdentityRecovery(
                registration: registration,
                reason: "record-search",
                operation: { recoveredRegistration in
                    try await recordsUseCase.fetchRecords(
                        registration: recoveredRegistration,
                        limit: Self.recordPageSize,
                        offset: offset,
                        query: trimmedQuery,
                        language: settings.appLanguage
                    )
                }
            )
            var nextState = searchState
            nextState.applyRecordPage(
                page,
                query: trimmedQuery,
                reset: reset,
                requestID: requestID
            )
            searchState = nextState
        } catch {
            log(.warning, "기록 검색 실패: \(error.localizedDescription)")
        }
        finishBackendRecordSearchPage(query: trimmedQuery, requestID: requestID)
    }

    func loadMoreBackendRecordSearchResults() async {
        guard searchState.canLoadMoreRecordResults,
              !searchState.recordQuery.isEmpty else {
            return
        }
        await searchBackendRecords(query: searchState.recordQuery, reset: false)
    }

    private func finishBackendRecordSearchPage(query: String, requestID: UUID) {
        var nextState = searchState
        nextState.finishRecordPage(query: query, requestID: requestID)
        searchState = nextState
    }

    func clearBackendRecordSearchResults() {
        replaceRecordSearchResults(nil)
    }

    private func beginCommunityFeedLoad() -> UUID {
        var nextState = communityFeedState
        let requestID = nextState.beginLoading()
        communityFeedState = nextState
        return requestID
    }

    private func isCurrentCommunityFeedLoad(_ requestID: UUID) -> Bool {
        communityFeedState.isCurrentRequest(requestID)
    }

    private func finishCommunityFeedLoad(_ requestID: UUID) {
        var nextState = communityFeedState
        nextState.finishLoading(requestID)
        communityFeedState = nextState
    }

    private func applyCommunityFeedPage(
        _ response: CommunityQuestionsResponse,
        offset: Int,
        reset: Bool
    ) {
        var nextState = communityFeedState
        nextState.applyPage(response, offset: offset, reset: reset)
        communityFeedState = nextState
    }

    private func clearCommunityFeedPage() {
        var nextState = communityFeedState
        nextState.clearPage()
        communityFeedState = nextState
    }

    private func removeCommunityQuestion(id: String) {
        var nextState = communityFeedState
        nextState.removeQuestion(id: id)
        communityFeedState = nextState
    }

    private func restoreCommunityQuestion(id: String) {
        var nextState = communityFeedState
        nextState.restoreQuestion(id: id)
        communityFeedState = nextState
    }

    private func removeCommunityQuestions(ids: Set<String>) {
        var nextState = communityFeedState
        nextState.removeQuestions(ids: ids)
        communityFeedState = nextState
    }

    private func restoreCommunityQuestions(ids: Set<String>) {
        var nextState = communityFeedState
        nextState.restoreQuestions(ids: ids)
        communityFeedState = nextState
    }

    private func beginBackendStatsRequest() -> UUID {
        var nextState = statsState
        let requestID = nextState.beginRequest()
        statsState = nextState
        return requestID
    }

    private func isCurrentBackendStatsRequest(_ requestID: UUID) -> Bool {
        statsState.isCurrentRequest(requestID)
    }

    private func finishBackendStatsRequest(_ requestID: UUID) {
        var nextState = statsState
        nextState.finishRequest(requestID)
        statsState = nextState
    }

    private func applyBackendStats(_ stats: BackendStats, requestID: UUID) {
        var nextState = statsState
        nextState.applyStats(stats, requestID: requestID)
        statsState = nextState
    }

    private func applyBackendStatsError(_ message: String, requestID: UUID) {
        var nextState = statsState
        nextState.applyError(message, requestID: requestID)
        statsState = nextState
    }

    private func beginBackendStatsActivityRequest() -> UUID {
        var nextState = statsState
        let requestID = nextState.beginActivityRequest()
        statsState = nextState
        return requestID
    }

    private func isCurrentBackendStatsActivityRequest(_ requestID: UUID) -> Bool {
        statsState.isCurrentActivityRequest(requestID)
    }

    private func finishBackendStatsActivityRequest(_ requestID: UUID) {
        var nextState = statsState
        nextState.finishActivityRequest(requestID)
        statsState = nextState
    }

    private func applyBackendStatsActivity(_ activity: BackendStatsActivity, requestID: UUID) {
        var nextState = statsState
        nextState.applyActivity(activity, requestID: requestID)
        statsState = nextState
    }

    private func applyBackendStatsActivityError(_ message: String, requestID: UUID) {
        var nextState = statsState
        nextState.applyActivityError(message, requestID: requestID)
        statsState = nextState
    }

    private func beginBackendStudyGrowthRequest() -> UUID {
        var nextState = statsState
        let requestID = nextState.beginStudyGrowthRequest()
        statsState = nextState
        return requestID
    }

    private func isCurrentBackendStudyGrowthRequest(_ requestID: UUID) -> Bool {
        statsState.isCurrentStudyGrowthRequest(requestID)
    }

    private func finishBackendStudyGrowthRequest(_ requestID: UUID) {
        var nextState = statsState
        nextState.finishStudyGrowthRequest(requestID)
        statsState = nextState
    }

    private func applyBackendStudyGrowth(_ growth: BackendStudyGrowth, requestID: UUID) {
        var nextState = statsState
        nextState.applyStudyGrowth(growth, requestID: requestID)
        statsState = nextState
    }

    private func applyBackendStudyGrowthError(_ message: String, requestID: UUID) {
        var nextState = statsState
        nextState.applyStudyGrowthError(message, requestID: requestID)
        statsState = nextState
    }

    func fetchBackendStats(
        period: BackendStatsPeriod = .all,
        sort: BackendStatsSort = .level,
        startAt: Date? = nil,
        endAt: Date? = nil,
        limit: Int = 8,
        offset: Int = 0
    ) async {
        await actionRunner.runViewIndependent { [weak self] in
            await self?.performFetchBackendStats(
                period: period,
                sort: sort,
                startAt: startAt,
                endAt: endAt,
                limit: limit,
                offset: offset
            )
        }
    }

    private func performFetchBackendStats(
        period: BackendStatsPeriod,
        sort: BackendStatsSort,
        startAt: Date?,
        endAt: Date?,
        limit: Int,
        offset: Int
    ) async {
        #if DEBUG
        if isAppStoreScreenshotFixtureEnabled {
            return
        }
        #endif
        let requestID = beginBackendStatsRequest()

        let normalizedLimit = max(1, min(limit, 100))
        let normalizedOffset = max(0, offset)

        guard let registration = await backendRegistrationForOpenAIRequests(reason: "stats") else {
            applyBackendStatsError("백엔드 등록이 필요합니다. 네트워크 또는 설정을 확인하세요.", requestID: requestID)
            finishBackendStatsRequest(requestID)
            log(.warning, "통계 조회를 위한 백엔드 등록이 없어 요청을 중단했습니다.")
            return
        }

        await actionRunner.run(
            operation: {
                try await statsUseCase.fetchStats(
                    registration: registration,
                    period: period,
                    startAt: startAt,
                    endAt: endAt,
                    sort: sort,
                    limit: normalizedLimit,
                    offset: normalizedOffset
                )
            },
            onSuccess: { stats in
                guard isCurrentBackendStatsRequest(requestID) else {
                    return
                }

                applyBackendStats(stats, requestID: requestID)
                log(.info, "통계 조회 완료. topics=\(stats.topics.count), totalTopics=\(stats.totalTopics), totalResponses=\(stats.totalResponses), offset=\(stats.offset)")
            },
            onFailure: { error in
                guard isCurrentBackendStatsRequest(requestID) else {
                    return
                }

                if Self.isCancellationLikeError(error) {
                    log(.info, "통계 조회가 취소되어 화면 오류 상태에 반영하지 않습니다.")
                    return
                }

                if handlePageAccessError(error, page: .statistics) {
                    applyBackendStatsError(strings.pageAccessDenied(strings.tabStatistics), requestID: requestID)
                    return
                }

                applyBackendStatsError(backendErrorDisplayMessage(error, fallback: "통계 조회 실패"), requestID: requestID)
                log(.warning, "백엔드 통계 조회 실패: \(error.localizedDescription)")
            },
            onCompletion: {
                finishBackendStatsRequest(requestID)
            }
        )
    }

    func fetchBackendStatsActivity(startAt: Date? = nil, endAt: Date? = nil) async {
        await actionRunner.runViewIndependent { [weak self] in
            await self?.performFetchBackendStatsActivity(startAt: startAt, endAt: endAt)
        }
    }

    private func performFetchBackendStatsActivity(startAt: Date?, endAt: Date?) async {
        #if DEBUG
        if isAppStoreScreenshotFixtureEnabled {
            return
        }
        #endif
        let requestID = beginBackendStatsActivityRequest()

        guard let registration = await backendRegistrationForOpenAIRequests(reason: "stats-activity") else {
            applyBackendStatsActivityError("백엔드 등록이 필요합니다. 네트워크 또는 설정을 확인하세요.", requestID: requestID)
            finishBackendStatsActivityRequest(requestID)
            log(.warning, "통계 활동 조회를 위한 백엔드 등록이 없어 요청을 중단했습니다.")
            return
        }

        await actionRunner.run(
            operation: {
                try await statsUseCase.fetchStatsActivity(
                    registration: registration,
                    startAt: startAt,
                    endAt: endAt
                )
            },
            onSuccess: { activity in
                guard isCurrentBackendStatsActivityRequest(requestID) else {
                    return
                }

                applyBackendStatsActivity(activity, requestID: requestID)
                log(.info, "통계 활동 조회 완료. days=\(activity.days.count), streak=\(activity.streakDays), month=\(activity.monthAnswerCount)")
            },
            onFailure: { error in
                guard isCurrentBackendStatsActivityRequest(requestID) else {
                    return
                }

                if Self.isCancellationLikeError(error) {
                    log(.info, "통계 활동 조회가 취소되어 화면 오류 상태에 반영하지 않습니다.")
                    return
                }

                if handlePageAccessError(error, page: .statistics) {
                    applyBackendStatsActivityError(strings.pageAccessDenied(strings.tabStatistics), requestID: requestID)
                    return
                }

                applyBackendStatsActivityError(backendErrorDisplayMessage(error, fallback: "통계 활동 조회 실패"), requestID: requestID)
                log(.warning, "백엔드 통계 활동 조회 실패: \(error.localizedDescription)")
            },
            onCompletion: {
                finishBackendStatsActivityRequest(requestID)
            }
        )
    }

    func fetchBackendStudyGrowth(startAt: Date? = nil, endAt: Date? = nil) async {
        await actionRunner.runViewIndependent { [weak self] in
            await self?.performFetchBackendStudyGrowth(startAt: startAt, endAt: endAt)
        }
    }

    private func performFetchBackendStudyGrowth(startAt: Date?, endAt: Date?) async {
        #if DEBUG
        if isAppStoreScreenshotFixtureEnabled {
            return
        }
        #endif
        let requestID = beginBackendStudyGrowthRequest()

        guard let registration = await backendRegistrationForOpenAIRequests(reason: "study-growth") else {
            applyBackendStudyGrowthError(
                "백엔드 등록이 필요합니다. 네트워크 또는 설정을 확인하세요.",
                requestID: requestID
            )
            finishBackendStudyGrowthRequest(requestID)
            log(.warning, "학습 성장 조회를 위한 백엔드 등록이 없어 요청을 중단했습니다.")
            return
        }

        await actionRunner.run(
            operation: {
                try await statsUseCase.fetchStudyGrowth(
                    registration: registration,
                    startAt: startAt,
                    endAt: endAt
                )
            },
            onSuccess: { growth in
                guard isCurrentBackendStudyGrowthRequest(requestID) else {
                    return
                }

                applyBackendStudyGrowth(growth, requestID: requestID)
                log(.info, "학습 성장 조회 완료. roots=\(growth.roots.count), nodes=\(growth.nodes.count)")
            },
            onFailure: { error in
                guard isCurrentBackendStudyGrowthRequest(requestID) else {
                    return
                }

                if Self.isCancellationLikeError(error) {
                    log(.info, "학습 성장 조회가 취소되어 화면 오류 상태에 반영하지 않습니다.")
                    return
                }

                if handlePageAccessError(error, page: .statistics) {
                    applyBackendStudyGrowthError(
                        strings.pageAccessDenied(strings.tabStatistics),
                        requestID: requestID
                    )
                    return
                }

                applyBackendStudyGrowthError(
                    backendErrorDisplayMessage(error, fallback: "학습 성장 조회 실패"),
                    requestID: requestID
                )
                log(.warning, "백엔드 학습 성장 조회 실패: \(error.localizedDescription)")
            },
            onCompletion: {
                finishBackendStudyGrowthRequest(requestID)
            }
        )
    }

    func loadCommunityQuestions(reset: Bool = true, userInitiated: Bool = false) async {
        #if DEBUG
        if isAppStoreScreenshotFixtureEnabled {
            return
        }
        #endif
        let trimmedTopic = communitySearchText.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedOffset = reset ? 0 : communityOffset
        let limit = Self.communityQuestionPageSize

        if normalizedOffset > 0 && !canLoadMoreCommunityQuestions(currentCount: normalizedOffset) {
            return
        }

        let requestID = beginCommunityFeedLoad()

        guard let registration = await backendRegistrationForOpenAIRequests(reason: "community-feed") else {
            if userInitiated {
                clearCommunityErrorForMissingRegistration(reason: "community-feed")
            }
            finishCommunityFeedLoad(requestID)
            return
        }

        await actionRunner.run(
            operation: {
                try await communityUseCase.fetchPublicQuestions(
                    registration: registration,
                    query: trimmedTopic.isEmpty ? nil : trimmedTopic,
                    limit: limit,
                    offset: normalizedOffset,
                    excludeDeviceID: nil,
                    language: settings.appLanguage
                )
            },
            onSuccess: { response in
                guard isCurrentCommunityFeedLoad(requestID) else {
                    return
                }

                applyCommunityFeedPage(response, offset: normalizedOffset, reset: reset)
                log(.info, "공개 질문 목록을 로드했습니다. count=\(response.questions.count), total=\(response.totalCount), offset=\(communityOffset)")
            },
            onFailure: { error in
                guard isCurrentCommunityFeedLoad(requestID) else {
                    return
                }
                if reset {
                    clearCommunityFeedPage()
                }
                _ = handleCommunityError(error)
                if !userInitiated {
                    communityErrorMessage = nil
                }
                log(
                    .warning,
                    "공개 질문 로드 실패: \(appErrorHandlingUseCase.diagnosticDescription(for: error))"
                )
            },
            onCompletion: {
                finishCommunityFeedLoad(requestID)
            }
        )
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
        communityFeedState.canLoadMore(currentCount: currentCount)
    }

    private func applyBackendRecordsPage(
        _ recordsPage: BackendRecordsPage,
        pendingRecords: [StudyRecord] = [],
        updateVisibleQuestion: Bool,
        preserveLocalQuestionState: Bool = true,
        append: Bool = false
    ) {
        guard !isEditingSettings else {
            log(.info, "설정 편집 중이어서 백엔드 기록 페이지 적용을 건너뛰었습니다.")
            return
        }

        let localCurrentQuestion = currentQuestion
        let localLastAnswer = lastAnswer
        let localGradingResult = gradingResult

        let existingRecords = append
            ? studyRecords.filter { $0.gradingResult != nil }
            : []
        let pageRecords = recordsPage.records.reduce(existingRecords) { records, record in
            mergeBackendRecord(record, into: records)
        }
        let mergedRecords = pendingRecords.reduce(pageRecords) { records, pendingRecord in
            mergeBackendRecord(pendingRecord, into: records)
        }
        localStudyRecordUseCase.replaceBackendRecords(mergedRecords)
        reloadStudyRecordsFromStore(refreshRooms: true)

        guard updateVisibleQuestion else {
            if preserveLocalQuestionState {
                currentQuestion = localCurrentQuestion
                lastAnswer = localLastAnswer
                gradingResult = localGradingResult
                currentStudySessionUseCase.saveCurrentQuestionState(
                    question: localCurrentQuestion,
                    lastAnswer: localLastAnswer,
                    gradingResult: localGradingResult
                )
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
        currentStudySessionUseCase.saveCurrentQuestionState(
            question: currentQuestion,
            lastAnswer: lastAnswer,
            gradingResult: gradingResult
        )
        restartTimer()
    }

    private func reloadPersistedState(restartTimerAfterReload: Bool = true) {
        let loadedLocalStudySettings = localStudySettingsUseCase.loadSettings()
        let loadedCloudSyncState = cloudSyncStateUseCase.loadState()
        let loadedSettings = loadedLocalStudySettings.settings
        let synchronizedLoadedSettings = synchronizedTopicCategories(for: loadedSettings)
        let loadedAPIKey = loadedLocalStudySettings.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let loadedAPIKeyUpdatedAt = loadedLocalStudySettings.openAIAPIKeyUpdatedAt
        let effectiveAPIKeyUpdatedAt = loadedAPIKeyUpdatedAt ?? (loadedAPIKey.isEmpty ? nil : appClock.now)

        settings = synchronizedLoadedSettings
        let loadedCurrentStudySession = currentStudySessionUseCase.loadSession()
        currentQuestion = loadedCurrentStudySession.question
        lastAnswer = loadedCurrentStudySession.lastAnswer
        gradingResult = loadedCurrentStudySession.gradingResult
        isRunning = loadedCurrentStudySession.isRunning
        reloadStudyRecordsFromStore(refreshRooms: true)
        apiKey = loadedAPIKey
        savedSettings = synchronizedLoadedSettings
        savedAPIKey = loadedAPIKey.trimmingCharacters(in: .whitespacesAndNewlines)
        lastAPIKeyUpdatedAt = effectiveAPIKeyUpdatedAt
        hasCompletedOnboarding = onboardingStateUseCase.hasCompletedOnboarding()
        isCloudSyncEnabled = loadedCloudSyncState.isEnabled
        cloudLastSyncedAt = loadedCloudSyncState.stateUpdatedAt
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
            localStudySettingsUseCase.saveAPIKeyUpdatedAt(effectiveAPIKeyUpdatedAt)
        }
    }

    private func refreshStudyProgressFromStore() {
        let loadedCurrentStudySession = currentStudySessionUseCase.loadSession()
        currentQuestion = loadedCurrentStudySession.question
        lastAnswer = loadedCurrentStudySession.lastAnswer
        gradingResult = loadedCurrentStudySession.gradingResult
        reloadStudyRecordsFromStore(refreshRooms: true)
    }

    private func reloadStudyRecordsFromStore(refreshRooms: Bool = false) {
        recordsState.replace(with: localStudyRecordUseCase.loadRecords())
        if refreshRooms {
            refreshBackendStudyRoomsFromRecords()
        }
    }

    private func showPendingQuestionLimitStatus(reason: String) {
        statusMessage = strings.pendingQuestionLimitTitle
        errorMessage = strings.pendingQuestionLimitMessage
        log(.warning, "미채점 질문이 \(Self.maxPendingQuestionCount)개라 \(reason)을 건너뛰었습니다.")
    }

    private func showPendingQuestionLimitStatus(reason: String, categoryID: String?) {
        statusMessage = strings.pendingQuestionLimitTitle
        errorMessage = nil
        pendingQuestionLimitCategoryID = categoryID
        log(
            .warning,
            "해당 주제에 미채점 질문이 있어 \(reason)을 건너뛰었습니다. studyCategoryID=\(categoryID ?? "-")"
        )
    }

    func clearPendingQuestionLimitNotice(categoryID: String?) {
        guard pendingQuestionLimitCategoryID == categoryID else {
            return
        }
        pendingQuestionLimitCategoryID = nil
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
            _ = try await settingsUseCase.validateAPIKey(registration: registration)
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

    func loadBackendSettingsForEditing() async {
        if !isEditingSettings {
            beginSettingsEditing()
        }

        guard isEditingSettings, !isLoadingBackendSettingsForEditing else {
            return
        }

        guard !hasUnsavedSettingsChanges else {
            log(.info, "수정 중인 설정이 있어 백엔드 설정 로드를 건너뛰었습니다.")
            return
        }

        isLoadingBackendSettingsForEditing = true
        defer {
            isLoadingBackendSettingsForEditing = false
        }

        await refreshBackendSettingsFromServer(
            reason: "settings-load",
            requireCleanEditingState: true
        )
    }

    @discardableResult
    private func refreshBackendSettingsFromServer(
        reason: String,
        requireCleanEditingState: Bool = false
    ) async -> Bool {
        guard let registration = await backendRegistrationForOpenAIRequests(
            reason: "settings-\(reason)",
            syncSettingsAfterRegistration: false
        ) else {
            log(.warning, "백엔드 등록이 없어 설정 로드를 건너뛰었습니다. reason=\(reason)")
            return false
        }

        do {
            let backendSettings = try await performWithBackendIdentityRecovery(
                registration: registration,
                reason: "settings-\(reason)",
                syncSettingsAfterRegistration: false
            ) { recoveredRegistration in
                try await settingsUseCase.fetchSettings(registration: recoveredRegistration)
            }

            if requireCleanEditingState {
                guard isEditingSettings else {
                    return false
                }
                guard !hasUnsavedSettingsChanges else {
                    log(.info, "백엔드 설정 로드 중 사용자가 설정을 수정해 응답 반영을 건너뛰었습니다.")
                    return false
                }
            }

            var nextSettings = backendSettings.studySettings(fallback: settings)
            nextSettings = synchronizedTopicCategories(for: nextSettings)
            if !isCommunitySessionActive {
                nextSettings = nextSettings.withQuestionPrivacy(false)
            }
            let normalizedNextSettings = normalizedSettings(nextSettings)
            let shouldUpdateDraftSettings = !isEditingSettings || !hasUnsavedSettingsChanges

            settings = normalizedNextSettings
            savedSettings = normalizedNextSettings
            if shouldUpdateDraftSettings {
                draftSettings = normalizedNextSettings
            }
            isRunning = backendSettings.enabled
            isBackendOpenAIKeyConfigured = backendSettings.openAIKeyConfigured
            didReceiveCloudStateWhileEditing = false

            localStudySettingsUseCase.saveSettings(normalizedNextSettings)
            currentStudySessionUseCase.saveIsRunning(backendSettings.enabled)
            log(
                .info,
                "백엔드 설정을 기준으로 로컬 설정을 갱신했습니다. reason=\(reason), interval=\(normalizedNextSettings.sanitizedIntervalMinutes)"
            )
            return true
        } catch {
            if handlePageAccessError(error, page: .studyDetail) {
                return false
            }
            log(.warning, "백엔드 설정 로드 실패: \(error.localizedDescription), reason=\(reason)")
            return false
        }
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
        draftSettings.isQuestionPublic = isCommunitySessionActive && isQuestionPublic
    }

    func signInToCommunity() {
        logAuthTrace("community_sign_in_start", reason: "google", deduplicate: false)
        AppAnalytics.login(method: .google, outcome: .started)
        Task {
            #if os(iOS)
            do {
                communityErrorMessage = nil
                let idToken = try await googleSignInUseCase.signIn()
                await signInToCommunity(idToken: idToken)
            } catch GoogleOAuthError.cancelled {
                communityErrorMessage = nil
                AppAnalytics.login(method: .google, outcome: .cancelled)
                logAuthTrace("community_sign_in_cancelled", reason: "google", deduplicate: false)
                log(.info, "Google Login이 사용자에 의해 취소되었습니다.")
            } catch GoogleOAuthError.notConfigured {
                statusMessage = strings.googleLoginSetupRequired
                AppAnalytics.login(method: .google, outcome: .failed)
                logAuthTrace("community_sign_in_not_configured", reason: "google", deduplicate: false)
                log(.warning, "Google Login 설정이 없습니다.")
            } catch {
                handleCommunityError(error)
                AppAnalytics.login(method: .google, outcome: .failed)
                logAuthTrace(
                    "community_sign_in_failure",
                    reason: "google",
                    extra: ["error=\(error.localizedDescription)"],
                    deduplicate: false
                )
                log(.warning, "Google Login 실패: \(error.localizedDescription)")
            }
            #else
            statusMessage = strings.googleLoginSetupRequired
            #endif
        }
    }

    func signInToCommunity(idToken: String) async {
        logAuthTrace("community_sign_in_token_exchange_start", reason: "google-login", deduplicate: false)
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "google-login") else {
            AppAnalytics.login(method: .google, outcome: .failed)
            logAuthTrace("community_sign_in_missing_registration", reason: "google-login", deduplicate: false)
            clearCommunityErrorForMissingRegistration(reason: "google-login")
            return
        }

        await actionRunner.run(
            operation: {
                try await runCommunityAuthenticationOperation(
                    registration: registration,
                    reason: "google-login"
                ) { recoveredRegistration in
                    try await communityUseCase.loginWithGoogle(
                        registration: recoveredRegistration,
                        idToken: idToken
                    )
                }
            },
            onSuccess: { result in
                applyCommunityProfile(result.profile)
                storedBackendIdentityUseCase.saveRegistration(result.registration)
                communityErrorMessage = nil
                AppAnalytics.login(method: .google, outcome: .completed)
                logAuthTrace("community_sign_in_success", reason: "google-login", deduplicate: false)
                refreshCommunitySignInDataInBackground(reason: "google-login")
            },
            onFailure: { error in
                handleCommunityAuthenticationError(error)
                AppAnalytics.login(method: .google, outcome: .failed)
                logAuthTrace(
                    "community_sign_in_token_exchange_failure",
                    reason: "google-login",
                    extra: ["error=\(error.localizedDescription)"],
                    deduplicate: false
                )
                log(.warning, "Google 로그인 실패: \(error.localizedDescription)")
            }
        )
    }

    func signInToCommunityWithApple(identityToken: String) async {
        logAuthTrace("community_sign_in_start", reason: "apple", deduplicate: false)
        AppAnalytics.login(method: .apple, outcome: .started)
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "apple-login") else {
            AppAnalytics.login(method: .apple, outcome: .failed)
            logAuthTrace("community_sign_in_missing_registration", reason: "apple-login", deduplicate: false)
            clearCommunityErrorForMissingRegistration(reason: "apple-login")
            return
        }

        communityErrorMessage = nil
        await actionRunner.run(
            operation: {
                try await runCommunityAuthenticationOperation(
                    registration: registration,
                    reason: "apple-login"
                ) { recoveredRegistration in
                    try await communityUseCase.loginWithApple(
                        registration: recoveredRegistration,
                        idToken: identityToken
                    )
                }
            },
            onSuccess: { result in
                applyCommunityProfile(result.profile)
                storedBackendIdentityUseCase.saveRegistration(result.registration)
                AppAnalytics.login(method: .apple, outcome: .completed)
                logAuthTrace("community_sign_in_success", reason: "apple-login", deduplicate: false)
                refreshCommunitySignInDataInBackground(reason: "apple-login")
            },
            onFailure: { error in
                handleCommunityAuthenticationError(error)
                AppAnalytics.login(method: .apple, outcome: .failed)
                logAuthTrace(
                    "community_sign_in_token_exchange_failure",
                    reason: "apple-login",
                    extra: ["error=\(error.localizedDescription)"],
                    deduplicate: false
                )
                log(.warning, "Apple 로그인 실패: \(error.localizedDescription)")
            }
        )
    }

    func appleSignInCancelled() {
        AppAnalytics.login(method: .apple, outcome: .cancelled)
        logAuthTrace("community_sign_in_cancelled", reason: "apple", deduplicate: false)
    }

    func appleSignInFailed(_ error: Error? = nil) {
        communityErrorMessage = strings.communityRequestFailed
        AppAnalytics.login(method: .apple, outcome: .failed)
        logAuthTrace(
            "community_sign_in_failure",
            reason: "apple",
            extra: error.map { ["error=\($0.localizedDescription)"] } ?? [],
            deduplicate: false
        )
    }

    private func refreshCommunitySignInDataInBackground(reason: String) {
        let sessionGeneration = communitySessionState.generation
        logAuthTrace("community_sign_in_data_refresh_schedule", reason: reason, deduplicate: false)
        communitySignInRefreshTask?.cancel()
        communitySignInRefreshTask = Task { [weak self] in
            await self?.refreshCommunitySignInData(
                reason: reason,
                sessionGeneration: sessionGeneration
            )
        }
    }

    private func refreshCommunitySignInData(
        reason: String,
        sessionGeneration: UInt64
    ) async {
        guard isCurrentCommunitySession(sessionGeneration) else {
            logAuthTrace("community_sign_in_data_refresh_cancelled", reason: reason, deduplicate: false)
            return
        }
        logAuthTrace("community_sign_in_data_refresh_start", reason: reason, deduplicate: false)
        // Every login endpoint already returns the authoritative profile. Fetching /me again
        // here duplicated the first authenticated request without adding newer information.
        guard !Task.isCancelled, isCurrentCommunitySession(sessionGeneration) else {
            return
        }
        await refreshPermissionEvaluations(reason: reason)
        guard !Task.isCancelled, isCurrentCommunitySession(sessionGeneration) else {
            return
        }
        await refreshTermsAndNotificationPreferences(reason: reason)
        guard !Task.isCancelled, isCurrentCommunitySession(sessionGeneration) else {
            return
        }
        await refreshBackendStudyIfPossible(
            updateVisibleQuestion: true,
            preserveLocalSettings: false
        )
        guard !Task.isCancelled, isCurrentCommunitySession(sessionGeneration) else {
            return
        }
        await loadCommunityQuestions(reset: true, userInitiated: false)
        logAuthTrace("community_sign_in_data_refresh_success", reason: reason, deduplicate: false)
    }

    func requestEmailVerificationCode(email: String) async -> Bool {
        let normalizedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        communityErrorMessage = nil
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "email-code") else {
            clearCommunityErrorForMissingRegistration(reason: "email-code")
            return false
        }

        return await actionRunner.runVoid(
            operation: {
                _ = try await runCommunityAuthenticationOperation(
                    registration: registration,
                    reason: "email-code"
                ) { recoveredRegistration in
                    try await communityUseCase.requestEmailVerificationCode(
                        registration: recoveredRegistration,
                        email: normalizedEmail
                    )
                }
            },
            onSuccess: {
                communityErrorMessage = nil
            },
            onFailure: { error in
                handleCommunityAuthenticationError(
                    error,
                    fallback: strings.emailVerificationSendFailed
                )
                log(.warning, "Email 인증코드 요청 실패: \(error.localizedDescription)")
            }
        )
    }

    func signInToCommunity(email: String, password: String, verificationCode: String? = nil) async -> EmailCommunitySignInResult {
        logAuthTrace("community_sign_in_start", reason: "email-login", deduplicate: false)
        AppAnalytics.login(method: .email, outcome: .started)
        let normalizedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "email-login") else {
            AppAnalytics.login(method: .email, outcome: .failed)
            logAuthTrace("community_sign_in_missing_registration", reason: "email-login", deduplicate: false)
            clearCommunityErrorForMissingRegistration(reason: "email-login")
            return .failed
        }

        communityErrorMessage = nil
        let result = await actionRunner.run(
            operation: {
                try await runCommunityAuthenticationOperation(
                    registration: registration,
                    reason: "email-login"
                ) { recoveredRegistration in
                    try await communityUseCase.loginWithEmail(
                        registration: recoveredRegistration,
                        email: normalizedEmail,
                        password: password,
                        verificationCode: verificationCode
                    )
                }
            },
            onSuccess: { result in
                applyCommunityProfile(result.profile)
                storedBackendIdentityUseCase.saveRegistration(result.registration)
                AppAnalytics.login(method: .email, outcome: .completed)
                logAuthTrace("community_sign_in_success", reason: "email-login", deduplicate: false)
            },
            onFailure: { error in
                if appErrorResolution(error, fallback: strings.communityRequestFailed).requiresEmailVerification {
                    communityErrorMessage = strings.emailVerificationRequired
                    AppAnalytics.login(method: .email, outcome: .verificationRequired)
                    logAuthTrace("community_sign_in_email_verification_required", reason: "email-login", deduplicate: false)
                    log(.info, "Email 로그인에 인증코드가 필요합니다.")
                    return
                }
                handleCommunityAuthenticationError(error)
                AppAnalytics.login(method: .email, outcome: .failed)
                logAuthTrace(
                    "community_sign_in_failure",
                    reason: "email-login",
                    extra: ["error=\(error.localizedDescription)"],
                    deduplicate: false
                )
                log(.warning, "Email 로그인 실패: \(error.localizedDescription)")
            }
        )

        guard result != nil else {
            return communityErrorMessage == strings.emailVerificationRequired ? .verificationRequired : .failed
        }

        refreshCommunitySignInDataInBackground(reason: "email-login")
        return .signedIn
    }

    private func runCommunityAuthenticationOperation<T>(
        registration: RemotePushRegistration,
        reason: String,
        operation: (RemotePushRegistration) async throws -> T
    ) async throws -> T {
        try await performWithBackendIdentityRecovery(
            registration: registration,
            reason: reason,
            operation: operation
        )
    }

    private func handleCommunityAuthenticationError(
        _ error: Error,
        fallback: String? = nil
    ) {
        let fallbackMessage = fallback ?? strings.communityRequestFailed
        let identityRecoveryFailed = appErrorHandlingUseCase.shouldResetBackendIdentity(after: error)
        handleCommunityError(error, fallback: fallbackMessage)
        if identityRecoveryFailed {
            communityErrorMessage = fallbackMessage
        }
    }

    func signOutFromCommunity() {
        logAuthTrace("community_sign_out_start", reason: "manual", deduplicate: false)
        #if os(iOS)
        notificationService.deactivateRemoteNotificationsForLogout()
        #endif
        questionGenerationPollingTask?.cancel()
        questionGenerationPollingTask = nil
        communitySignInRefreshTask?.cancel()
        communitySignInRefreshTask = nil
        finishQuestionGenerationProcess()
        let registrationForLogout = storedBackendIdentityUseCase.loadRegistration()
        resetCommunitySignInState()
        if var registration = registrationForLogout {
            registration.accessToken = nil
            registration.accessTokenExpiresAt = nil
            storedBackendIdentityUseCase.saveRegistration(registration)
        }
        clearCommunityFeedPage()
        communityErrorMessage = nil
        backendAccessState = .signedOut
        if let registrationForLogout {
            Task {
                do {
                    try await communityUseCase.logout(registration: registrationForLogout)
                    log(.info, "백엔드 로그아웃을 완료했습니다. deviceID=\(registrationForLogout.deviceID)")
                } catch {
                    log(.warning, "백엔드 로그아웃 실패: \(error.localizedDescription)")
                }
            }
        }
        if settings.isQuestionPublic || draftSettings.isQuestionPublic {
            settings = settings.withQuestionPrivacy(false)
            draftSettings = draftSettings.withQuestionPrivacy(false)
            localStudySettingsUseCase.saveSettings(settings)
            savedSettings = normalizedSettings(settings)
        }
        statusMessage = strings.communitySignedOut
        logAuthTrace("community_sign_out_local_complete", reason: "manual", deduplicate: false)
    }

    private func resetCommunitySignInState() {
        logAuthTrace("community_session_reset_start", reason: "resetCommunitySignInState", deduplicate: false)
        cancelAllAnswerGradingPolling(reason: "community-session-reset")
        setCommunitySessionSignedIn(false)
        studyRoomState.replace(with: [])
        backendStudyLoadState = .idle
        isRequiredTermsGatePresented = false
        pendingTermsRequirementRetry = nil
        var nextState = communityProfileState
        nextState.resetSignedOutProfile()
        communityProfileState = nextState
        avatarCatalog = nil
        activeTerms = []
        notificationPreferences = []
        communityCommentsCache.removeAll()
        let revenueCatAppAccountToken = billingCatalog?.appAccountToken
        billingCatalog = nil
        billingStatus = nil
        billingInvoices = []
        billingErrorMessage = nil
        isLoadingBilling = false
        #if os(iOS)
        Task { @MainActor in
            await RevenueCatBillingBridge.shared.logOut(
                expectedAppAccountToken: revenueCatAppAccountToken
            )
        }
        #endif
        updateNotificationState { state in
            state.reset()
        }
        isLoadingTermsAndPreferences = false
        backendAccessState = .signedOut
        communityProfileCacheUseCase.saveSignedOutProfile(avatarSymbolName: profileAvatarSymbolName)
        logAuthTrace("community_session_reset_end", reason: "resetCommunitySignInState", deduplicate: false)
    }

    private func setCommunitySessionSignedIn(_ isSignedIn: Bool) {
        var nextState = communitySessionState
        if isSignedIn {
            nextState.signIn()
        } else {
            nextState.signOut()
        }
        communitySessionState = nextState
        communitySessionUseCase.setSignedIn(isSignedIn)
    }

    func updateProfileAvatarSymbolName(_ symbolName: String) {
        var nextState = communityProfileState
        nextState.updateAvatar(symbolName: symbolName)
        communityProfileState = nextState
        communityProfileCacheUseCase.saveAvatarSymbolName(symbolName)
    }

    func updateProfileAvatarColorSeed(_ seed: String) {
        var nextState = communityProfileState
        nextState.updateAvatar(colorSeed: seed)
        communityProfileState = nextState
        communityProfileCacheUseCase.saveAvatarColorSeed(seed)
    }

    func updateProfileAvatarImageData(_ data: Data?) {
        var nextState = communityProfileState
        nextState.setAvatarImageData(data)
        communityProfileState = nextState
        communityProfileCacheUseCase.saveAvatarImageData(data)
    }

    func updateCommunityProfileAvatar(symbolName: String? = nil, colorSeed: String? = nil) {
        if let symbolName {
            updateProfileAvatarSymbolName(symbolName)
        }
        if let colorSeed {
            updateProfileAvatarColorSeed(colorSeed)
        }
        guard isCommunitySessionActive else {
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
                avatarMode: communityProfile?.avatarMode,
                avatarConfig: communityProfile?.avatarConfig
            )
        }
    }

    func loadCommunityProfile() async {
        logAuthTrace("community_profile_load_start", page: .profile, reason: "loadCommunityProfile", deduplicate: false)
        guard isCommunitySessionActive,
              let registration = await backendRegistrationForOpenAIRequests(reason: "community-profile") else {
            logAuthTrace("community_profile_load_skipped", page: .profile, reason: "loadCommunityProfile", deduplicate: false)
            return
        }

        await actionRunner.run(
            operation: {
                try await communityUseCase.fetchMyProfile(registration: registration)
            },
            onSuccess: { profile in
                applyCommunityProfile(profile)
                logAuthTrace("community_profile_load_success", page: .profile, reason: "loadCommunityProfile", deduplicate: false)
            },
            onFailure: { error in
                let handled = handleCommunityError(error)
                if handled {
                    communityProfile = nil
                    logAuthTrace(
                        "community_profile_load_handled_failure",
                        page: .profile,
                        reason: "loadCommunityProfile",
                        extra: ["error=\(error.localizedDescription)"],
                        deduplicate: false
                    )
                    return
                }
                logAuthTrace(
                    "community_profile_load_failure",
                    page: .profile,
                    reason: "loadCommunityProfile",
                    extra: ["error=\(error.localizedDescription)"],
                    deduplicate: false
                )
                log(.warning, "커뮤니티 프로필 조회 실패: \(error.localizedDescription)")
            }
        )
    }

    func refreshDeveloperFeatureAccess(reason: String = "manual") async {
        let settings = developerSettingsUseCase.loadSettings()
        let shouldRestoreAccess = Self.shouldRestoreDeveloperAccess(
            settings: settings,
            distribution: appDistributionContext
        )
        if settings.isDeveloperAccessUnlocked && !shouldRestoreAccess {
            developerSettingsUseCase.saveDeveloperAccessUnlocked(false)
            developerSettingsUseCase.saveIsDebuggingEnabled(false)
        }
        let access: DeveloperFeatureAccess = shouldRestoreAccess ? .fullyAllowed : .restricted
        applyDeveloperFeatureAccess(access, reason: reason)
    }

    @discardableResult
    func unlockDeveloperAccessFromVersionGesture() -> Bool {
        guard appDistributionContext.allowsHiddenDeveloperUnlock else {
            return false
        }
        developerSettingsUseCase.saveDeveloperAccessUnlocked(true)
        developerSettingsUseCase.saveDeveloperAccessBuildIdentifier(
            appDistributionContext.isTestFlight
                ? appDistributionContext.buildIdentifier
                : nil
        )
        applyDeveloperFeatureAccess(.fullyAllowed, reason: "version-five-taps")
        log(.info, "버전 5회 탭으로 이 빌드의 개발자 옵션을 활성화했습니다.")
        return true
    }

    private static func shouldRestoreDeveloperAccess(
        settings: DeveloperSettings,
        distribution: AppDistributionContext
    ) -> Bool {
        guard settings.isDeveloperAccessUnlocked else {
            return false
        }
        guard distribution.allowsHiddenDeveloperUnlock else {
            return false
        }
        guard distribution.isTestFlight else {
            return true
        }
        return settings.developerAccessBuildIdentifier == distribution.buildIdentifier
    }

    private func applyDeveloperFeatureAccess(
        _ access: DeveloperFeatureAccess,
        reason: String
    ) {
        developerFeatureAccess = access
        var nextBackendAccess = backendAccessState
        nextBackendAccess.pageAccess.developer = access.developerOptionsAllowed
        backendAccessState = nextBackendAccess

        if !access.debugPopupAllowed {
            isAPIDebugPanelPresented = false
        }

        guard access.developerOptionsAllowed else {
            let wasDebuggingEnabled = isDebuggingEnabled
            isDebuggingEnabled = false
            if wasDebuggingEnabled {
                refreshRemotePushBackendClient(reason: "developer-access-revoked-\(reason)")
            }
            return
        }

        let storedDeveloperSettings = developerSettingsUseCase.loadSettings()
        let restoredDebugBackendBaseURL = normalizedDebugBackendBaseURL(
            storedDeveloperSettings.debugBackendBaseURL
        )
        debugBackendBaseURL = restoredDebugBackendBaseURL
        draftDebugBackendBaseURL = restoredDebugBackendBaseURL
        let shouldEnableDebugging = storedDeveloperSettings.isDebuggingEnabled
        guard shouldEnableDebugging != isDebuggingEnabled else {
            return
        }
        isDebuggingEnabled = shouldEnableDebugging
        refreshRemotePushBackendClient(reason: "developer-access-\(reason)")
    }

    @discardableResult
    func updateCommunityProfile(
        displayName: String,
        bio: String = "",
        avatarSymbolName: String? = nil,
        avatarColorSeed: String? = nil,
        avatarMode: String? = nil,
        avatarConfig: [String: String]? = nil,
        allowPublicQuestions: Bool? = nil
    ) async -> Bool {
        logAuthTrace(
            "community_profile_update_requested",
            page: .profile,
            reason: "updateCommunityProfile",
            extra: [
                "avatarSymbolName=\(avatarSymbolName ?? "-")",
                "avatarColorSeed=\(avatarColorSeed ?? "-")",
                "avatarMode=\(avatarMode ?? "-")",
            ],
            deduplicate: false
        )
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "community-profile-update") else {
            logAuthTrace(
                "community_profile_update_missing_registration",
                page: .profile,
                reason: "updateCommunityProfile",
                deduplicate: false
            )
            return false
        }
        let previousState = communityProfileState
        applyLocalCommunityProfileDraft(
            displayName: displayName,
            avatarSymbolName: avatarSymbolName,
            avatarColorSeed: avatarColorSeed,
            avatarMode: avatarMode,
            avatarConfig: avatarConfig,
            allowPublicQuestions: allowPublicQuestions
        )
        isUpdatingCommunityProfile = true
        var didSucceed = false

        await actionRunner.run(
            operation: {
                try await communityUseCase.updateMyProfile(
                    registration: registration,
                    displayName: displayName,
                    bio: bio,
                    avatarSymbolName: avatarSymbolName,
                    avatarColorSeed: avatarColorSeed,
                    avatarMode: avatarMode,
                    avatarConfig: avatarConfig,
                    allowPublicQuestions: allowPublicQuestions
                )
            },
            onSuccess: { profile in
                communityProfileCacheUseCase.saveDisplayName(displayName)
                applyCommunityProfile(profile)
                let symbolMatches = avatarSymbolName == nil || profile.avatarSymbolName == avatarSymbolName
                let colorMatches = avatarColorSeed == nil || profile.avatarColorSeed == avatarColorSeed
                let modeMatches = avatarMode == nil
                    || profile.avatarMode.caseInsensitiveCompare(avatarMode ?? "") == .orderedSame
                didSucceed = symbolMatches && colorMatches && modeMatches
                logAuthTrace(
                    didSucceed
                        ? "community_profile_update_succeeded"
                        : "community_profile_update_response_mismatch",
                    page: .profile,
                    reason: "updateCommunityProfile",
                    extra: [
                        "requestedAvatarSymbolName=\(avatarSymbolName ?? "-")",
                        "returnedAvatarSymbolName=\(profile.avatarSymbolName)",
                        "requestedAvatarColorSeed=\(avatarColorSeed ?? "-")",
                        "returnedAvatarColorSeed=\(profile.avatarColorSeed)",
                        "requestedAvatarMode=\(avatarMode ?? "-")",
                        "returnedAvatarMode=\(profile.avatarMode)",
                    ],
                    deduplicate: false
                )
            },
            onFailure: { error in
                let handled = handleCommunityError(error)
                if !handled {
                    restoreCommunityProfileState(previousState)
                }
                logAuthTrace(
                    "community_profile_update_failed",
                    page: .profile,
                    reason: "updateCommunityProfile",
                    extra: [
                        "avatarSymbolName=\(avatarSymbolName ?? "-")",
                        "avatarColorSeed=\(avatarColorSeed ?? "-")",
                        "errorType=\(String(describing: type(of: error)))",
                        "error=\(error.localizedDescription)",
                    ],
                    deduplicate: false
                )
                log(.warning, "커뮤니티 프로필 저장 실패: \(error.localizedDescription)")
            },
            onCompletion: {
                isUpdatingCommunityProfile = false
            }
        )
        return didSucceed
    }

    private func restoreCommunityProfileState(_ state: CommunityProfileStateStore) {
        communityProfileState = state
        communityProfileCacheUseCase.saveAvatarSymbolName(state.avatarSymbolName)
        communityProfileCacheUseCase.saveAvatarColorSeed(state.avatarColorSeed)
        communityProfileCacheUseCase.saveAvatarConfig(state.avatarConfig)
        if let profile = state.profile {
            communityProfileCacheUseCase.saveDisplayName(profile.displayName)
        } else {
            communityProfileCacheUseCase.clearProfileIdentity()
        }
    }

    private func applyLocalCommunityProfileDraft(
        displayName: String,
        avatarSymbolName: String?,
        avatarColorSeed: String?,
        avatarMode: String?,
        avatarConfig: [String: String]?,
        allowPublicQuestions: Bool?
    ) {
        let trimmedDisplayName = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedAvatarSymbolName = avatarSymbolName?.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedAvatarColorSeed = avatarColorSeed?.trimmingCharacters(in: .whitespacesAndNewlines)
        let nextDisplayName = trimmedDisplayName.isEmpty ? communityProfile?.displayName : trimmedDisplayName
        let nextAvatarSymbolName: String
        if let trimmedAvatarSymbolName, !trimmedAvatarSymbolName.isEmpty {
            nextAvatarSymbolName = trimmedAvatarSymbolName
        } else {
            nextAvatarSymbolName = communityProfile?.avatarSymbolName ?? profileAvatarSymbolName
        }
        let nextAvatarColorSeed: String
        if let trimmedAvatarColorSeed, !trimmedAvatarColorSeed.isEmpty {
            nextAvatarColorSeed = trimmedAvatarColorSeed
        } else {
            nextAvatarColorSeed = communityProfile?.avatarColorSeed ?? profileAvatarColorSeed
        }

        if let nextDisplayName {
            communityProfileCacheUseCase.saveDisplayName(nextDisplayName)
        }
        communityProfileCacheUseCase.saveAvatarSymbolName(nextAvatarSymbolName)
        communityProfileCacheUseCase.saveAvatarColorSeed(nextAvatarColorSeed)
        if let avatarConfig {
            communityProfileCacheUseCase.saveAvatarConfig(avatarConfig)
        }

        var nextState = communityProfileState
        nextState.updateAvatar(symbolName: nextAvatarSymbolName, colorSeed: nextAvatarColorSeed, config: avatarConfig)
        if let profile = nextState.profile {
            nextState.profile = CommunityUserProfile(
                id: profile.id,
                displayName: nextDisplayName ?? profile.displayName,
                provider: profile.provider,
                email: profile.email,
                bio: profile.bio,
                avatarURL: profile.avatarURL,
                avatarSymbolName: nextAvatarSymbolName,
                avatarColorSeed: nextAvatarColorSeed,
                avatarMode: avatarMode ?? profile.avatarMode,
                avatarConfig: avatarConfig ?? profile.avatarConfig,
                allowPublicQuestions: allowPublicQuestions ?? profile.allowPublicQuestions,
                pageAccess: profile.pageAccess
            )
        }
        communityProfileState = nextState
        logAuthTrace(
            "community_profile_apply_local_draft",
            page: .profile,
            reason: "updateCommunityProfile",
            extra: [
                "avatarSymbolName=\(nextAvatarSymbolName)",
                "avatarColorSeed=\(nextAvatarColorSeed)",
                "avatarConfigSlots=\((avatarConfig ?? [:]).keys.sorted().joined(separator: ","))",
            ],
            deduplicate: false
        )
    }

    func setPublicQuestionsAllowed(_ allowed: Bool) async {
        guard let profile = communityProfile else {
            return
        }

        await updateCommunityProfile(
            displayName: profile.displayName,
            bio: profile.bio,
            avatarSymbolName: profile.avatarSymbolName,
            avatarColorSeed: profile.avatarColorSeed,
            avatarMode: profile.avatarMode,
            avatarConfig: profile.avatarConfig,
            allowPublicQuestions: allowed
        )
    }

    func loadAvatarCatalog() async {
        guard isCommunitySessionActive,
              let registration = await backendRegistrationForOpenAIRequests(reason: "avatar-catalog") else {
            avatarCatalog = nil
            return
        }
        isLoadingAvatarCatalog = true
        await actionRunner.run(
            operation: {
                try await communityUseCase.fetchAvatarCatalog(registration: registration)
            },
            onSuccess: { catalog in
                avatarCatalog = catalog
                let config = communityProfile?.avatarConfig ?? catalog.currentConfig
                updateProfileAvatarConfig(config)
            },
            onFailure: { error in
                log(.warning, "아바타 카탈로그 조회 실패: \(error.localizedDescription)")
            },
            onCompletion: {
                isLoadingAvatarCatalog = false
            }
        )
    }

    func updateProfileAvatarConfig(_ config: [String: String]) {
        var nextState = communityProfileState
        nextState.setAvatarConfig(config)
        communityProfileState = nextState
        communityProfileCacheUseCase.saveAvatarConfig(config)
    }

    private func applyCommunityProfile(_ profile: CommunityUserProfile) {
        logAuthTrace(
            "community_profile_apply_start",
            page: .profile,
            reason: "applyCommunityProfile",
            extra: ["profileId=\(profile.id)", "provider=\(profile.provider)"],
            deduplicate: false
        )
        let resolvedProfile = communityProfileCacheUseCase.applyProfile(profile)
        var nextState = communityProfileState
        nextState.applyProfile(resolvedProfile)
        communityProfileState = nextState
        if resolvedProfile.status == "PENDING_TERMS" {
            isRequiredTermsGatePresented = true
        } else if isRequiredTermsGatePresented {
            logAuthTrace(
                "required_terms_gate_preserved",
                page: .profile,
                reason: "active-profile-does-not-confirm-terms",
                extra: ["profileId=\(resolvedProfile.id)", "status=\(resolvedProfile.status)"],
                deduplicate: false
            )
        }
        applyProfilePageAccess(resolvedProfile)
        logAuthTrace(
            "community_profile_apply_end",
            page: .profile,
            reason: "applyCommunityProfile",
            extra: ["profileId=\(resolvedProfile.id)", "provider=\(resolvedProfile.provider)"],
            deduplicate: false
        )
    }

    private func applyProfilePageAccess(_ profile: CommunityUserProfile) {
        backendAccessState = BackendAccessState(
            user: BackendAccessUser(
                id: Int64(profile.id),
                status: "ACTIVE",
                displayName: profile.displayName,
                createdAt: nil
            ),
            pageAccess: backendAccessState.pageAccess
        )
        setCommunitySessionSignedIn(true)
        #if os(iOS)
        Task { @MainActor [weak self] in
            guard let self else {
                return
            }
            _ = await self.notificationService.requestAuthorizationIfNeeded(
                language: self.settings.appLanguage
            )
            await self.recoverAppleBillingTransactions(reason: "sign-in")
        }
        #endif
    }

    func withdrawCommunityAccount() async -> Bool {
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "community-withdraw") else {
            clearCommunityErrorForMissingRegistration(reason: "community-withdraw")
            return false
        }

        isWithdrawingCommunityAccount = true
        var didSucceed = false

        await actionRunner.run(
            operation: {
                try await communityUseCase.withdrawMyProfile(registration: registration)
            },
            onSuccess: { updatedRegistration in
                didSucceed = true
                storedBackendIdentityUseCase.saveRegistration(updatedRegistration)
                signOutFromCommunity()
                communityProfileCacheUseCase.clearProfileIdentity()
                statusMessage = strings.accountDeleted
            },
            onFailure: { error in
                handleCommunityError(error)
                log(.warning, "커뮤니티 탈퇴 실패: \(error.localizedDescription)")
            },
            onCompletion: {
                isWithdrawingCommunityAccount = false
            }
        )
        return didSucceed
    }

    func reportCommunityQuestion(_ question: CommunityQuestion, reason: String, message: String = "") async {
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "community-report") else {
            clearCommunityErrorForMissingRegistration(reason: "community-report")
            return
        }

        await actionRunner.runVoid(
            operation: {
                try await communityUseCase.reportQuestion(
                    registration: registration,
                    questionID: question.id,
                    reason: reason,
                    message: message
                )
            },
            onSuccess: {
                statusMessage = strings.reportSubmitted
            },
            onFailure: { error in
                handleCommunityError(error)
                log(.warning, "공개 질문 신고 실패: \(error.localizedDescription)")
            }
        )
    }

    func submitAppFeedback(content: String) async -> Bool {
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "app-feedback") else {
            clearCommunityErrorForMissingRegistration(reason: "app-feedback")
            return false
        }

        var submitted = false
        await actionRunner.runVoid(
            operation: {
                try await communityUseCase.submitFeedback(
                    registration: registration,
                    content: content
                )
            },
            onSuccess: {
                submitted = true
                statusMessage = strings.feedbackSubmitted
            },
            onFailure: { error in
                handleCommunityError(error)
                log(.warning, "앱 피드백 제출 실패: \(error.localizedDescription)")
            }
        )
        return submitted
    }

    func setCommunityQuestionLike(_ question: CommunityQuestion, isLiked: Bool) async -> CommunityLikeState? {
        guard isCommunitySessionActive else {
            return nil
        }

        guard let registration = await backendRegistrationForOpenAIRequests(reason: "community-like") else {
            clearCommunityErrorForMissingRegistration(reason: "community-like")
            return nil
        }

        let previous = communityQuestions.first(where: { $0.id == question.id })
        updateCommunityQuestionLike(id: question.id, isLiked: isLiked, likeCount: max(0, question.likeCount + (isLiked ? 1 : -1)))

        return await actionRunner.run(
            operation: {
                try await communityUseCase.setQuestionLike(
                    registration: registration,
                    questionID: question.id,
                    isLiked: isLiked
                )
            },
            onSuccess: { state in
                updateCommunityQuestionLike(id: question.id, isLiked: state.isLikedByMe, likeCount: state.likeCount)
            },
            onFailure: { error in
                if let previous {
                    updateCommunityQuestionLike(id: question.id, isLiked: previous.isLikedByMe, likeCount: previous.likeCount)
                }
                handleCommunityError(error)
                log(.warning, "공개 질문 좋아요 처리 실패: \(error.localizedDescription)")
            }
        )
    }

    func cachedCommunityQuestionComments(questionID: String) -> CommunityCommentsResponse? {
        communityCommentsCache[questionID]
    }

    func loadCommunityQuestionComments(
        questionID: String,
        limit: Int = 30,
        offset: Int = 0,
        refresh: Bool = false,
        view: LocalizedContentView = .localized
    ) async -> CommunityCommentsResponse? {
        if view == .localized, !refresh, offset == 0, let cached = communityCommentsCache[questionID] {
            return cached
        }

        guard let registration = await backendRegistrationForOpenAIRequests(reason: "community-comments") else {
            clearCommunityErrorForMissingRegistration(reason: "community-comments")
            return nil
        }

        return await actionRunner.run(
            operation: {
                try await communityUseCase.fetchComments(
                    registration: registration,
                    questionID: questionID,
                    limit: limit,
                    offset: offset,
                    language: settings.appLanguage,
                    view: view
                )
            },
            onSuccess: { response in
                if view == .localized, offset == 0 {
                    communityCommentsCache[questionID] = response
                }
            },
            onFailure: { error in
                handleCommunityError(error)
                log(.warning, "공개 질문 댓글 로드 실패: \(error.localizedDescription)")
            }
        )
    }

    func loadCommunityQuestionDetail(
        questionID: String,
        view: LocalizedContentView = .localized
    ) async -> CommunityQuestion? {
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "community-question-detail") else {
            clearCommunityErrorForMissingRegistration(reason: "community-question-detail")
            return nil
        }

        return await actionRunner.run(
            operation: {
                try await communityUseCase.fetchPublicQuestion(
                    registration: registration,
                    questionID: questionID,
                    language: settings.appLanguage,
                    view: view
                )
            },
            onSuccess: { question in
                if view == .localized,
                   let index = communityQuestions.firstIndex(where: { $0.id == questionID }) {
                    communityQuestions[index] = question
                }
            },
            onFailure: { error in
                handleCommunityError(error)
                log(.warning, "공개 질문 상세 로드 실패: \(error.localizedDescription)")
            }
        )
    }

    func loadStudyRecordDetail(
        recordID: String,
        view: LocalizedContentView = .localized
    ) async -> StudyRecord? {
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "record-detail") else {
            return nil
        }
        return await actionRunner.run(
            operation: {
                try await recordsUseCase.fetchRecord(
                    registration: registration,
                    recordID: recordID,
                    language: settings.appLanguage,
                    view: view
                )
            },
            onSuccess: { _ in },
            onFailure: { error in
                log(.warning, "기록 상세 로드 실패: \(error.localizedDescription)")
            }
        )
    }

    func createCommunityQuestionComment(questionID: String, body: String) async -> CommunityQuestionComment? {
        guard isCommunitySessionActive else {
            return nil
        }

        guard let registration = await backendRegistrationForOpenAIRequests(reason: "community-comment-create") else {
            clearCommunityErrorForMissingRegistration(reason: "community-comment-create")
            return nil
        }

        return await actionRunner.run(
            operation: {
                try await communityUseCase.createComment(
                    registration: registration,
                    questionID: questionID,
                    body: body,
                    sourceLanguage: ContentLanguageRecognizer.detect(
                        body,
                        fallback: settings.appLanguage
                    )
                )
            },
            onSuccess: { comment in
                if let index = communityQuestions.firstIndex(where: { $0.id == questionID }) {
                    communityQuestions[index].commentCount += 1
                }
                if var cached = communityCommentsCache[questionID],
                   !cached.comments.contains(where: { $0.id == comment.id }) {
                    cached.comments.append(comment)
                    cached.totalCount += 1
                    communityCommentsCache[questionID] = cached
                }
            },
            onFailure: { error in
                handleCommunityError(error)
                log(.warning, "공개 질문 댓글 작성 실패: \(error.localizedDescription)")
            }
        )
    }

    func deleteCommunityQuestionComment(questionID: String, commentID: String) async -> Bool {
        guard isCommunitySessionActive else {
            return false
        }

        guard let registration = await backendRegistrationForOpenAIRequests(reason: "community-comment-delete") else {
            clearCommunityErrorForMissingRegistration(reason: "community-comment-delete")
            return false
        }

        return await actionRunner.runVoid(
            operation: {
                try await communityUseCase.deleteComment(
                    registration: registration,
                    questionID: questionID,
                    commentID: commentID
                )
            },
            onSuccess: {
                if let index = communityQuestions.firstIndex(where: { $0.id == questionID }) {
                    communityQuestions[index].commentCount = max(0, communityQuestions[index].commentCount - 1)
                }
                if var cached = communityCommentsCache[questionID] {
                    let previousCount = cached.comments.count
                    cached.comments.removeAll { $0.id == commentID }
                    if cached.comments.count != previousCount {
                        cached.totalCount = max(0, cached.totalCount - 1)
                    }
                    communityCommentsCache[questionID] = cached
                }
            },
            onFailure: { error in
                handleCommunityError(error)
                log(.warning, "공개 질문 댓글 삭제 실패: \(error.localizedDescription)")
            }
        )
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
                    try? await appSleepProvider.sleep(nanoseconds: shortInterval * 1_000_000)
                    continue
                }
            }

            let pollingInterval = min(
                intervalMilliseconds * UInt64(attempt + 1),
                160
            )
            try? await appSleepProvider.sleep(nanoseconds: max(pollingInterval, 6) * 1_000_000)
        }

        return nil
    }

    private func currentClipboardChangeCount() -> Int {
        clipboardProvider.changeCount()
    }

    func fetchClipboardOpenAIAPIKey() -> String? {
        clipboardProvider.fetchOpenAIAPIKey()
    }

    nonisolated static func extractOpenAIAPIKey(from text: String) -> String? {
        OpenAIAPIKeyExtractionPolicy.extract(from: text)
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
            customPrompt: customPrompt ?? StudySettings.defaultCustomPrompt,
            openAIModel: openAIModel ?? settings.sanitizedOpenAIModel
        )
        locallyDeletedStudyTopicKeys.remove(Self.normalizedCategoryText(for: raw))
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

        persistSettings(nextSettings, apiKey: apiKey, syncBackendSchedule: false)
        if settings.selectedStudyCategoryID == nil {
            activateStudyContext(forTopic: nextSettings.topic)
        }
        statusMessage = nil
        Task { [weak self] in
            _ = await self?.createBackendStudyIfPossible(nextCategory, settings: nextSettings)
        }
    }

    func updateStudyCategory(
        id: String,
        title: String,
        difficulty: Difficulty,
        syncBackendSchedule: Bool = true
    ) {
        guard let category = settings.studyCategories.first(where: { $0.id == id }) else {
            return
        }
        updateStudyCategory(
            id: id,
            title: title,
            difficulty: difficulty,
            customPrompt: category.customPrompt,
            openAIModel: category.sanitizedOpenAIModel,
            syncBackendSchedule: syncBackendSchedule
        )
    }

    private func updateStudyCategory(
        id: String,
        title: String,
        difficulty: Difficulty,
        customPrompt: String,
        openAIModel: String,
        syncBackendSchedule: Bool = true
    ) {
        let raw = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else {
            return
        }

        locallyDeletedStudyTopicKeys.remove(Self.normalizedCategoryText(for: raw))
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

        persistSettings(
            nextSettings,
            apiKey: apiKey,
            syncBackendSchedule: syncBackendSchedule
        )
        if nextSettings.selectedStudyCategoryID == id {
            activateStudyContext(forTopic: nextSettings.topic)
        }
        statusMessage = nil
    }

    func updateStudyTreeCategory(
        roomID: Int,
        title: String,
        difficulty: Difficulty
    ) {
        let normalizedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedTitle.isEmpty,
              let room = backendStudyRoom(id: roomID) else {
            return
        }
        let questionSettings = rootStudyRoom(for: roomID) ?? room
        let updatedCategory = StudyCategory(
            id: String(roomID),
            title: normalizedTitle,
            difficulty: difficulty,
            customPrompt: questionSettings.customPrompt,
            openAIModel: questionSettings.openAIModel,
            createdAt: room.createdAt
        )
        var optimisticRoom = room
        optimisticRoom.topic = normalizedTitle
        optimisticRoom.difficultyLevel = difficulty.level
        studyRoomState.upsertStudy(optimisticRoom)
        updateStudyCategory(
            id: updatedCategory.id,
            title: updatedCategory.title,
            difficulty: updatedCategory.difficulty,
            customPrompt: updatedCategory.customPrompt,
            openAIModel: updatedCategory.openAIModel,
            syncBackendSchedule: false
        )

        let currentSettings = settings
        Task { [weak self] in
            await self?.updateBackendStudyIfPossible(
                updatedCategory,
                studyID: roomID,
                settings: currentSettings
            )
        }
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

    func openStudyCategory(_ categoryID: String) {
        let categories = synchronizedTopicCategories(for: settings).studyCategories
        guard let targetCategory = categories.first(where: { $0.id == categoryID }) ?? categories.first else {
            return
        }

        if settings.selectedStudyCategoryID != targetCategory.id {
            persistSettings(
                settings.withSelectedCategoryID(targetCategory.id),
                apiKey: apiKey,
                syncBackendSchedule: false
            )
        }

        applyPreferredPendingRecord(for: targetCategory)
        showStudyScreen(categoryID: targetCategory.id)
    }

    func openStudyTree(_ categoryID: String) {
        guard backendStudyRoom(categoryID: categoryID) != nil else {
            openStudyCategory(categoryID)
            return
        }

        selectedTab = .home
        homeStudyRoute = HomeStudyRoute(categoryID: categoryID, showsTree: true)
    }

    @discardableResult
    func addChildStudyCategory(
        _ title: String,
        parentStudyID: Int,
        difficulty: Difficulty,
        customPrompt: String,
        openAIModel: String
    ) async -> Bool {
        let addedTopics = await addChildStudyCategories(
            [title],
            parentStudyID: parentStudyID,
            difficulty: difficulty,
            customPrompt: customPrompt,
            openAIModel: openAIModel
        )
        return !addedTopics.isEmpty
    }

    @discardableResult
    func addChildStudyCategories(
        _ titles: [String],
        parentStudyID: Int,
        difficulty: Difficulty,
        customPrompt: String,
        openAIModel: String
    ) async -> [String] {
        var existingTopicKeys = Set(
            backendStudyRooms.map { Self.normalizedCategoryText(for: $0.topic) }
        )
        var candidates: [StudyCategory] = []

        for title in titles {
            let raw = title.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !raw.isEmpty else {
                continue
            }
            let topicKey = Self.normalizedCategoryText(for: raw)
            guard existingTopicKeys.insert(topicKey).inserted else {
                continue
            }
            candidates.append(
                StudyCategory(
                    title: raw,
                    difficulty: difficulty,
                    customPrompt: customPrompt,
                    openAIModel: openAIModel
                )
            )
        }

        guard !candidates.isEmpty,
              let registration = await backendRegistrationForOpenAIRequests(
                reason: "create-study-topics"
              ) else {
            return []
        }

        var addedTopics: [String] = []
        var sortOrder = childStudyRooms(parentStudyID: parentStudyID).count
        for category in candidates {
            let saved = await createBackendStudyTopicIfPossible(
                topic: category.normalizedTitle,
                difficulty: category.difficulty,
                parentStudyID: parentStudyID,
                sortOrder: sortOrder,
                activeForQuestions: true,
                registration: registration,
                refreshAfterCreation: false
            )
            guard saved else {
                continue
            }
            addedTopics.append(category.normalizedTitle)
            sortOrder += 1
        }

        if !addedTopics.isEmpty {
            await refreshBackendStudyIfPossible(updateVisibleQuestion: false)
        }
        return addedTopics
    }

    func prepareStudyRoom(
        categoryID: String?,
        gradingPollingOwnerID: String? = nil,
        onInitialStateResolved: (@MainActor () -> Void)? = nil
    ) async {
        guard let initialCategory = studyCategoryForRoom(categoryID) else {
            onInitialStateResolved?()
            return
        }

        applyPreferredPendingRecord(for: initialCategory)

        guard let studyID = Int(initialCategory.id),
              let detail = await fetchBackendStudyDetailIfPossible(studyID: studyID) else {
            onInitialStateResolved?()
            return
        }
        applyBackendStudyDetail(detail)

        guard let refreshedCategory = studyCategoryForRoom(categoryID) ?? studyCategoryMatchingTopic(initialCategory.title) else {
            onInitialStateResolved?()
            return
        }

        applyPreferredPendingRecord(for: refreshedCategory)
        onInitialStateResolved?()

        guard let gradingPollingOwnerID,
              let record = studyRoomRecordForDisplay(categoryID: categoryID),
              record.gradingResult == nil,
              let gradingStatus = record.gradingStatus,
              !gradingStatus.isTerminal,
              let gradingRequestID = record.gradingRequestID,
              !gradingRequestID.isEmpty else {
            return
        }

        await resumeStudyRoomAnswerGrading(
            record,
            pollingOwnerID: gradingPollingOwnerID
        )
    }

    func pendingStudyRecord(categoryID: String?) -> StudyRecord? {
        guard let category = studyCategoryForRoom(categoryID) else {
            return nil
        }

        if let record = preferredPendingRecord(for: category) {
            return record
        }

        if let studyID = Int(category.id) {
            guard let record = backendStudyRoom(id: studyID)?.pendingQuestion,
                  record.gradingResult == nil else {
                return nil
            }
            return record
        }

        let categoryKey = Self.normalizedCategoryText(for: category.title)
        let matchesCategory: (StudyRecord?) -> StudyRecord? = { record in
            guard let record,
                  record.gradingResult == nil,
                  Self.normalizedCategoryText(for: record.topic) == categoryKey else {
                return nil
            }
            return record
        }

        return backendStudyRooms
            .compactMap(\.pendingQuestion)
            .compactMap(matchesCategory)
            .max { $0.question.createdAt < $1.question.createdAt }
    }

    func studyRoomRecordForDisplay(categoryID: String?) -> StudyRecord? {
        if let pendingRecord = pendingStudyRecord(categoryID: categoryID) {
            return pendingRecord
        }

        if let latestQuestion = backendStudyRoom(categoryID: categoryID)?.latestQuestion {
            return latestQuestion
        }

        guard let category = studyCategoryForRoom(categoryID),
              let currentRecord = studyRecord(matching: currentQuestion),
              currentRecord.gradingResult != nil else {
            return nil
        }

        if let studyID = Int(category.id) {
            return currentRecord.studyID == studyID ? currentRecord : nil
        }

        return Self.normalizedCategoryText(for: currentRecord.topic) ==
            Self.normalizedCategoryText(for: category.title)
            ? currentRecord
            : nil
    }

    func backendStudyRoom(categoryID: String?) -> BackendStudyRoom? {
        studyRoomState.room(categoryID: categoryID, settings: settings)
    }

    func backendStudyRoom(id: Int) -> BackendStudyRoom? {
        backendStudyRooms.first { $0.id == id }
    }

    func childStudyRooms(parentStudyID: Int) -> [BackendStudyRoom] {
        backendStudyRooms
            .filter { $0.parentStudyId == parentStudyID }
            .sorted {
                if $0.sortOrder == $1.sortOrder {
                    return $0.id < $1.id
                }
                return $0.sortOrder < $1.sortOrder
            }
    }

    func rootStudyRoom(for studyID: Int) -> BackendStudyRoom? {
        let roomsByID = Dictionary(uniqueKeysWithValues: backendStudyRooms.map { ($0.id, $0) })
        guard var current = roomsByID[studyID] else {
            return nil
        }
        var visited = Set<Int>()
        while let parentID = current.parentStudyId,
              visited.insert(current.id).inserted,
              let parent = roomsByID[parentID] {
            current = parent
        }
        return current
    }

    func studyTreeDepth(for studyID: Int) -> Int {
        let roomsByID = Dictionary(uniqueKeysWithValues: backendStudyRooms.map { ($0.id, $0) })
        guard var current = roomsByID[studyID] else {
            return 0
        }
        var depth = 0
        var visited = Set<Int>()
        while let parentID = current.parentStudyId,
              visited.insert(current.id).inserted,
              let parent = roomsByID[parentID] {
            depth += 1
            current = parent
        }
        return depth
    }

    func suggestChildStudyTopics(parentStudyID: Int) async -> [String] {
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "suggest-study-topics") else {
            return []
        }
        do {
            let suggestions = try await studyRoomUseCase.suggestStudyTopics(
                registration: registration,
                parentStudyID: parentStudyID,
                count: 10
            )
            log(.info, "학습 트리 주제를 추천했습니다. parentStudyID=\(parentStudyID), count=\(suggestions.count)")
            return suggestions
        } catch {
            if handleAppError(
                error,
                fallback: "",
                target: .none,
                protectedPage: .myStudies,
                termsRetry: nil
            ) {
                return []
            }
            log(.warning, "학습 트리 주제 추천 실패: parentStudyID=\(parentStudyID), error=\(error.localizedDescription)")
            return []
        }
    }

    func setStudyTopicActive(studyID: Int, active: Bool) {
        guard let current = backendStudyRoom(id: studyID) else {
            return
        }
        var optimistic = current
        optimistic.activeForQuestions = active
        studyRoomState.upsertStudy(optimistic)

        Task { [weak self] in
            guard let self,
                  let registration = await backendRegistrationForOpenAIRequests(reason: "study-topic-activation") else {
                self?.studyRoomState.upsertStudy(current)
                return
            }
            do {
                let saved = try await studyRoomUseCase.updateStudyTopicActivation(
                    registration: registration,
                    studyID: studyID,
                    active: active
                )
                studyRoomState.upsertStudy(saved)
                log(.info, "학습 트리 질문 받기 설정을 변경했습니다. studyID=\(studyID), active=\(active)")
            } catch {
                studyRoomState.upsertStudy(current)
                if handleAppError(
                    error,
                    fallback: "",
                    target: .none,
                    protectedPage: .myStudies,
                    termsRetry: { [weak self] in
                        self?.setStudyTopicActive(studyID: studyID, active: active)
                    }
                ) {
                    return
                }
                log(.warning, "학습 트리 질문 받기 설정 변경 실패: studyID=\(studyID), error=\(error.localizedDescription)")
            }
        }
    }

    func setStudyTopicsActive(studyIDs: Set<Int>, active: Bool) {
        for studyID in studyIDs.sorted() {
            setStudyTopicActive(studyID: studyID, active: active)
        }
    }

    func deleteStudyCategories(ids: Set<Int>) {
        deleteStudyCategories(categoryIDs: Set(ids.map(String.init)))
    }

    func deleteStudyCategories(categoryIDs: Set<String>) {
        let offsets = IndexSet(
            studyCategoriesForDisplay.enumerated().compactMap { index, category in
                categoryIDs.contains(category.id) ? index : nil
            }
        )
        deleteStudyCategories(at: offsets)
    }

    func loadStudyTreeNodeOffsets(rootStudyID: Int) -> [Int: CGSize] {
        localStudySettingsUseCase.loadStudyTreeNodeOffsets(rootStudyID: rootStudyID).mapValues {
            CGSize(width: $0.x, height: $0.y)
        }
    }

    func saveStudyTreeNodeOffsets(_ offsets: [Int: CGSize], rootStudyID: Int) {
        localStudySettingsUseCase.saveStudyTreeNodeOffsets(
            offsets.mapValues { StudyTreeNodeOffset(x: $0.width, y: $0.height) },
            rootStudyID: rootStudyID
        )
    }

    func loadStudyTreeViewport(rootStudyID: Int) -> StudyTreeViewportState {
        localStudySettingsUseCase.loadStudyTreeViewport(rootStudyID: rootStudyID)
    }

    func hasStudyTreeViewport(rootStudyID: Int) -> Bool {
        localStudySettingsUseCase.hasStudyTreeViewport(rootStudyID: rootStudyID)
    }

    func saveStudyTreeViewport(_ viewport: StudyTreeViewportState, rootStudyID: Int) {
        localStudySettingsUseCase.saveStudyTreeViewport(viewport, rootStudyID: rootStudyID)
    }

    func studyCategory(for room: BackendStudyRoom) -> StudyCategory {
        studyCategoriesForDisplay.first(where: { $0.id == String(room.id) })
            ?? StudyCategory(
                id: String(room.id),
                title: room.topic,
                difficulty: Difficulty(level: room.difficultyLevel),
                customPrompt: room.customPrompt,
                openAIModel: room.openAIModel,
                createdAt: room.createdAt
            )
    }

    func refreshQuestionQuota() async {
        let refreshOrder = membershipRefreshOrder.issue()
        let clientGeneration = backendClientGeneration
        let currentStudyRoomUseCase = studyRoomUseCase
        guard isCommunitySessionActive,
              let storedRegistration = storedBackendIdentityUseCase.loadRegistration(),
              let registration = await registrationWithAccessToken(storedRegistration, reason: "question-quota") else {
            if clientGeneration == backendClientGeneration,
               membershipRefreshOrder.isLatest(refreshOrder) {
                questionQuota = nil
            }
            return
        }

        do {
            let quota = try await performWithBackendIdentityRecovery(
                registration: registration,
                reason: "question-quota",
                operation: { recoveredRegistration in
                    try await currentStudyRoomUseCase.fetchQuestionQuota(registration: recoveredRegistration)
                }
            )
            guard clientGeneration == backendClientGeneration,
                  membershipRefreshOrder.isLatest(refreshOrder) else {
                return
            }
            if let billingStatus,
               billingStatus.tierCode != quota.tierCode {
                log(
                    .warning,
                    "최신 quota와 이전 결제 상태의 티어가 달라 이전 결제 상태를 폐기합니다. " +
                        "billingTier=\(billingStatus.tierCode), quotaTier=\(quota.tierCode)"
                )
                self.billingStatus = nil
            }
            questionQuota = quota
            if quota.remainingCount > 0 {
                questionQuotaNotice = nil
            }
            log(
                .info,
                "월간 질문 한도를 동기화했습니다. used=\(quota.usedCount), limit=\(quota.monthlyLimit), remaining=\(quota.remainingCount)"
            )
        } catch where !Self.isCancellationLikeError(error) {
            guard clientGeneration == backendClientGeneration,
                  membershipRefreshOrder.isLatest(refreshOrder) else {
                return
            }
            log(.warning, "월간 질문 한도 조회에 실패했습니다: \(error.localizedDescription)")
        } catch {
            return
        }
    }

    func refreshBilling() async {
        if let billingRefreshTask {
            await billingRefreshTask.value
            return
        }

        let task = Task { @MainActor [weak self] in
            guard let self else {
                return
            }
            await self.performBillingRefresh()
        }
        billingRefreshTask = task
        await task.value
        billingRefreshTask = nil
    }

    @discardableResult
    func reconcileBillingSubscription() async -> Bool {
        guard isCommunitySessionActive,
              let storedRegistration = storedBackendIdentityUseCase.loadRegistration(),
              let registration = await registrationWithAccessToken(
                storedRegistration,
                reason: "billing-subscription-reconcile"
              ) else {
            return false
        }

        do {
            let resolvedStatus = try await performWithBackendIdentityRecovery(
                registration: registration,
                reason: "billing-subscription-reconcile",
                operation: { recoveredRegistration in
                    try await self.billingUseCase.reconcileSubscription(registration: recoveredRegistration)
                }
            )
            applyBillingStatus(resolvedStatus)
            billingErrorMessage = nil
            let pendingChange = resolvedStatus.pendingChange ?? "-"
            log(
                .info,
                "구독 공급자 상태를 즉시 재조정했습니다. tier=\(resolvedStatus.tierCode), " +
                    "renewal=\(resolvedStatus.renewalStatus), pending=\(pendingChange)"
            )
            return true
        } catch where !Self.isCancellationLikeError(error) {
            log(.warning, "구독 공급자 상태 재조정에 실패했습니다: \(error.localizedDescription)")
            return false
        } catch {
            return false
        }
    }

    private func performBillingRefresh() async {
        let refreshOrder = membershipRefreshOrder.issue()
        let clientGeneration = backendClientGeneration
        billingRefreshRequestID += 1
        let requestID = billingRefreshRequestID
        let currentBillingUseCase = billingUseCase
        guard isCommunitySessionActive,
              let storedRegistration = storedBackendIdentityUseCase.loadRegistration(),
              let registration = await registrationWithAccessToken(storedRegistration, reason: "billing-refresh") else {
            if clientGeneration == backendClientGeneration,
               requestID == billingRefreshRequestID,
               membershipRefreshOrder.isLatest(refreshOrder) {
                billingCatalog = nil
                billingStatus = nil
                billingInvoices = []
                billingErrorMessage = nil
            }
            return
        }

        billingRefreshInFlightCount += 1
        isLoadingBilling = true
        defer {
            billingRefreshInFlightCount = max(0, billingRefreshInFlightCount - 1)
            isLoadingBilling = billingRefreshInFlightCount > 0
        }

        do {
            let resolvedStatus = try await performWithBackendIdentityRecovery(
                registration: registration,
                reason: "billing-status",
                operation: { recoveredRegistration in
                    try await currentBillingUseCase.status(registration: recoveredRegistration)
                }
            )
            guard clientGeneration == backendClientGeneration,
                  requestID == billingRefreshRequestID,
                  membershipRefreshOrder.isLatest(refreshOrder) else {
                return
            }
            applyBillingStatus(resolvedStatus)
            billingErrorMessage = nil
            log(
                .info,
                "결제 상태를 동기화했습니다. tier=\(resolvedStatus.tierCode), " +
                    "limit=\(resolvedStatus.quota.baseLimit + resolvedStatus.quota.bonusLimit), " +
                    "product=\(resolvedStatus.productId ?? "-")"
            )
        } catch where !Self.isCancellationLikeError(error) {
            guard clientGeneration == backendClientGeneration,
                  requestID == billingRefreshRequestID,
                  membershipRefreshOrder.isLatest(refreshOrder) else {
                return
            }
            billingErrorMessage = error.localizedDescription
            log(.warning, "결제 상태를 동기화하지 못했습니다: \(error.localizedDescription)")
            return
        } catch {
            return
        }

        do {
            let resolvedCatalog = try await performWithBackendIdentityRecovery(
                registration: registration,
                reason: "billing-catalog",
                operation: { recoveredRegistration in
                    try await currentBillingUseCase.catalog(registration: recoveredRegistration)
                }
            )
            guard clientGeneration == backendClientGeneration,
                  requestID == billingRefreshRequestID else {
                return
            }
            billingCatalog = resolvedCatalog
            #if os(iOS)
            try await RevenueCatBillingBridge.shared.identify(appAccountToken: resolvedCatalog.appAccountToken)
            #endif
        } catch where !Self.isCancellationLikeError(error) {
            guard clientGeneration == backendClientGeneration,
                  requestID == billingRefreshRequestID else {
                return
            }
            billingErrorMessage = error.localizedDescription
            log(.warning, "결제 상품 정보를 동기화하지 못했습니다: \(error.localizedDescription)")
        } catch {
            return
        }

        do {
            let resolvedInvoices = try await performWithBackendIdentityRecovery(
                registration: registration,
                reason: "billing-invoices",
                operation: { recoveredRegistration in
                    try await currentBillingUseCase.invoices(registration: recoveredRegistration)
                }
            )
            guard clientGeneration == backendClientGeneration,
                  requestID == billingRefreshRequestID else {
                return
            }
            billingInvoices = resolvedInvoices.invoices
        } catch where !Self.isCancellationLikeError(error) {
            guard clientGeneration == backendClientGeneration,
                  requestID == billingRefreshRequestID else {
                return
            }
            log(.warning, "결제 원장 목록을 동기화하지 못했습니다: \(error.localizedDescription)")
        } catch {
            return
        }
    }

    private func applyBillingStatus(_ resolvedStatus: BackendBillingStatus) {
        billingStatus = resolvedStatus
        questionQuota = BackendQuestionQuota(
            usedCount: resolvedStatus.quota.usedCount,
            monthlyLimit: resolvedStatus.quota.baseLimit + resolvedStatus.quota.bonusLimit,
            remainingCount: resolvedStatus.quota.remainingCount,
            resetAt: resolvedStatus.quota.resetAt,
            tierCode: resolvedStatus.tierCode,
            periodStartedAt: resolvedStatus.quota.periodStartedAt,
            reservedCount: resolvedStatus.quota.reservedCount,
            baseLimit: resolvedStatus.quota.baseLimit,
            bonusLimit: resolvedStatus.quota.bonusLimit,
            anchorType: resolvedStatus.quota.anchorType,
            policyVersion: resolvedStatus.quota.policyVersion
        )
    }

    func syncAppleBillingTransaction(
        signedTransaction: String,
        environment: String,
        invoiceNumber: UUID?
    ) async throws -> BackendBillingInvoice {
        guard let storedRegistration = storedBackendIdentityUseCase.loadRegistration(),
              let registration = await registrationWithAccessToken(storedRegistration, reason: "billing-transaction") else {
            throw AppStateError.missingRemotePushRegistration
        }
        let invoice = try await performWithBackendIdentityRecovery(
            registration: registration,
            reason: "billing-transaction",
            operation: { recoveredRegistration in
                try await self.billingUseCase.syncAppleTransaction(
                    registration: recoveredRegistration,
                    signedTransaction: signedTransaction,
                    environment: environment,
                    invoiceNumber: invoiceNumber
                )
            }
        )
        log(
            invoice.isApplied ? .info : .error,
            "Apple 결제 검증 응답을 확인했습니다. endpoint=/api/v1/billing/apple/transactions " +
                "invoiceId=\(invoice.id), status=\(invoice.status), " +
                "paymentStatus=\(invoice.paymentStatus ?? "nil"), " +
                "fulfilledAtPresent=\(invoice.fulfilledAt != nil), " +
                "transactionIdPresent=\(invoice.transactionId != nil)"
        )
        return invoice
    }

    func confirmRevenueCatBillingTransaction(
        transactionID: String?,
        invoiceNumber: UUID
    ) async throws -> BackendBillingInvoice {
        guard let storedRegistration = storedBackendIdentityUseCase.loadRegistration(),
              let registration = await registrationWithAccessToken(
                storedRegistration,
                reason: "billing-revenuecat-confirm"
              ) else {
            throw AppStateError.missingRemotePushRegistration
        }
        let invoice = try await performWithBackendIdentityRecovery(
            registration: registration,
            reason: "billing-revenuecat-confirm",
            operation: { recoveredRegistration in
                try await self.billingUseCase.confirmRevenueCatTransaction(
                    registration: recoveredRegistration,
                    invoiceNumber: invoiceNumber,
                    transactionID: transactionID
                )
            }
        )
        log(
            invoice.isApplied ? .info : .error,
            "RevenueCat 결제 확정 응답을 확인했습니다. " +
                "endpoint=/api/v1/billing/invoices/{invoiceNumber}/confirm " +
                "invoiceId=\(invoice.id), status=\(invoice.status), " +
                "paymentStatus=\(invoice.paymentStatus ?? "nil"), " +
                "fulfilledAtPresent=\(invoice.fulfilledAt != nil)"
        )
        return invoice
    }

    #if os(iOS)
    private func startAppleBillingTransactionListener() {
        RevenueCatBillingBridge.shared.start()
        guard !RevenueCatBillingBridge.shared.isEnabled,
              appleBillingUpdatesTask == nil else {
            return
        }
        appleBillingUpdatesTask = Task { @MainActor [weak self] in
            for await verification in Transaction.updates {
                guard !Task.isCancelled else {
                    return
                }
                guard let self else {
                    return
                }
                let didSynchronize = await self.reconcileAppleTransaction(
                    verification,
                    reason: "storekit-update",
                    finishAfterSync: true
                )
                if didSynchronize {
                    await self.refreshBilling()
                }
            }
        }
    }

    private func recoverAppleBillingTransactions(reason: String) async {
        RevenueCatBillingBridge.shared.start()
        guard !RevenueCatBillingBridge.shared.isEnabled,
              isCommunitySessionActive,
              appleBillingRecoveryTask == nil else {
            return
        }
        let task = Task { @MainActor [weak self] in
            guard let self else {
                return
            }
            var didSynchronize = false
            for await verification in Transaction.unfinished {
                guard !Task.isCancelled else {
                    return
                }
                didSynchronize = await reconcileAppleTransaction(
                    verification,
                    reason: reason,
                    finishAfterSync: true
                ) || didSynchronize
            }
            for await verification in Transaction.currentEntitlements {
                guard !Task.isCancelled else {
                    return
                }
                didSynchronize = await reconcileAppleTransaction(
                    verification,
                    reason: "\(reason)-current-entitlement",
                    finishAfterSync: false
                ) || didSynchronize
            }
            if didSynchronize {
                await refreshBilling()
            }
        }
        appleBillingRecoveryTask = task
        await task.value
        appleBillingRecoveryTask = nil
    }

    private func reconcileAppleTransaction(
        _ verification: VerificationResult<Transaction>,
        reason: String,
        finishAfterSync: Bool
    ) async -> Bool {
        guard case .verified(let transaction) = verification else {
            log(.error, "검증되지 않은 StoreKit 거래는 완료 처리하지 않았습니다. reason=\(reason)")
            return false
        }
        guard isCommunitySessionActive,
              !recoveringAppleTransactionIDs.contains(transaction.id),
              !synchronizedAppleTransactionIDs.contains(transaction.id) else {
            return false
        }

        recoveringAppleTransactionIDs.insert(transaction.id)
        defer { recoveringAppleTransactionIDs.remove(transaction.id) }

        if billingCatalog == nil {
            await refreshBilling()
        }
        guard let expectedToken = billingCatalog?.appAccountToken,
              transaction.appAccountToken == expectedToken else {
            log(
                .warning,
                "현재 로그인 계정과 다른 StoreKit 거래를 보류했습니다. transactionID=\(transaction.id), reason=\(reason)"
            )
            return false
        }

        for attempt in 1...Self.appleBillingRecoveryAttempts {
            do {
                let invoice = try await syncAppleBillingTransaction(
                    signedTransaction: verification.jwsRepresentation,
                    environment: AppleBillingStore.backendEnvironment(transaction),
                    invoiceNumber: nil
                )
                _ = try AppleBillingStore.requireApplied(invoice)
                synchronizedAppleTransactionIDs.insert(transaction.id)
                if finishAfterSync {
                    await transaction.finish()
                }
                log(
                    .info,
                    "StoreKit 거래를 백엔드에 동기화했습니다. transactionID=\(transaction.id), " +
                        "attempt=\(attempt), reason=\(reason)"
                )
                return true
            } catch {
                log(
                    .warning,
                    "StoreKit 거래 백엔드 동기화를 보류했습니다. transactionID=\(transaction.id), " +
                        "attempt=\(attempt), reason=\(reason), error=\(error.localizedDescription)"
                )
                // A 200 response whose invoice is not fully applied is a completed business
                // response, not a transient transport failure. Reposting the same JWS several
                // times cannot repair an older/incomplete backend contract and only creates
                // duplicate traffic. Keep the entitlement unfinished so a later launch/restore
                // can retry after the backend is fixed.
                if let billingError = error as? AppleBillingStoreError,
                   case .membershipApplicationIncomplete = billingError {
                    return false
                }
                guard attempt < Self.appleBillingRecoveryAttempts else {
                    return false
                }
                let delay = Self.appleBillingRecoveryDelayNanoseconds[attempt - 1]
                try? await Task.sleep(nanoseconds: delay)
                guard !Task.isCancelled else {
                    return false
                }
            }
        }
        return false
    }
    #endif

    func createAppleBillingCheckout(productID: String) async throws -> BackendBillingInvoice {
        guard let storedRegistration = storedBackendIdentityUseCase.loadRegistration(),
              let registration = await registrationWithAccessToken(storedRegistration, reason: "billing-checkout") else {
            throw AppStateError.missingRemotePushRegistration
        }
        let invoice = try await performWithBackendIdentityRecovery(
            registration: registration,
            reason: "billing-checkout",
            operation: { recoveredRegistration in
                try await self.billingUseCase.createCheckout(
                    registration: recoveredRegistration,
                    productID: productID,
                    idempotencyKey: "ios-checkout-\(UUID().uuidString.lowercased())"
                )
            }
        )
        await refreshBilling()
        return invoice
    }

    func waitForRevenueCatBillingFulfillment(invoiceID: Int64) async throws -> BackendBillingInvoice {
        guard let storedRegistration = storedBackendIdentityUseCase.loadRegistration(),
              let registration = await registrationWithAccessToken(storedRegistration, reason: "billing-webhook") else {
            throw AppStateError.missingRemotePushRegistration
        }
        let delays: [UInt64] = [1_000_000_000, 2_000_000_000, 4_000_000_000]
        var latest = try await performWithBackendIdentityRecovery(
            registration: registration,
            reason: "billing-webhook",
            operation: { recoveredRegistration in
                try await self.billingUseCase.invoice(
                    registration: recoveredRegistration,
                    invoiceID: invoiceID
                )
            }
        )
        for delay in delays where latest.status == "WAITING" {
            try await Task.sleep(nanoseconds: delay)
            latest = try await performWithBackendIdentityRecovery(
                registration: registration,
                reason: "billing-webhook",
                operation: { recoveredRegistration in
                    try await self.billingUseCase.invoice(
                        registration: recoveredRegistration,
                        invoiceID: invoiceID
                    )
                }
            )
        }
        return latest
    }

    func abandonAppleBillingCheckout(invoiceNumber: UUID) async throws {
        guard let storedRegistration = storedBackendIdentityUseCase.loadRegistration(),
              let registration = await registrationWithAccessToken(storedRegistration, reason: "billing-checkout-abandon") else {
            throw AppStateError.missingRemotePushRegistration
        }
        _ = try await performWithBackendIdentityRecovery(
            registration: registration,
            reason: "billing-checkout-abandon",
            operation: { recoveredRegistration in
                try await self.billingUseCase.abandonCheckout(
                    registration: recoveredRegistration,
                    invoiceNumber: invoiceNumber
                )
            }
        )
        await refreshBilling()
    }

    func requestBillingRefund(paymentID: Int64) async throws -> BackendBillingAction {
        guard let storedRegistration = storedBackendIdentityUseCase.loadRegistration(),
              let registration = await registrationWithAccessToken(storedRegistration, reason: "billing-refund") else {
            throw AppStateError.missingRemotePushRegistration
        }
        let action = try await performWithBackendIdentityRecovery(
            registration: registration,
            reason: "billing-refund",
            operation: { recoveredRegistration in
                try await self.billingUseCase.requestRefund(
                    registration: recoveredRegistration,
                    paymentID: paymentID,
                    idempotencyKey: "ios-refund-\(UUID().uuidString.lowercased())"
                )
            }
        )
        await refreshBilling()
        return action
    }

    func requestBillingCancellation(originalTransactionID: String) async throws -> BackendBillingAction {
        guard let storedRegistration = storedBackendIdentityUseCase.loadRegistration(),
              let registration = await registrationWithAccessToken(storedRegistration, reason: "billing-cancellation") else {
            throw AppStateError.missingRemotePushRegistration
        }
        let action = try await performWithBackendIdentityRecovery(
            registration: registration,
            reason: "billing-cancellation",
            operation: { recoveredRegistration in
                try await self.billingUseCase.requestCancellation(
                    registration: recoveredRegistration,
                    originalTransactionID: originalTransactionID,
                    idempotencyKey: "ios-cancel-\(UUID().uuidString.lowercased())"
                )
            }
        )
        await refreshBilling()
        return action
    }

    func clearQuestionQuotaNotice() {
        questionQuotaNotice = nil
    }

    func answerDraft(for record: StudyRecord?) -> String {
        guard let record else {
            return ""
        }

        return answerForCurrentSession(record)
    }

    private func answerForCurrentSession(_ record: StudyRecord) -> String {
        StudyAnswerPresentationPolicy.submittedAnswer(for: record)
            ?? localStudyRecordUseCase.loadAnswerDraft(recordID: record.id)
    }

    func isAnswerGradingInProgress(for record: StudyRecord?) -> Bool {
        guard let record else {
            return false
        }
        return StudyAnswerPresentationPolicy.state(
            for: record,
            isSubmitting: answerSubmissionRecordIDs.contains(record.id)
        ).isInProgress
    }

    func gradingPresentationMessage(for record: StudyRecord?) -> String? {
        if let record,
           answerSubmissionRecordIDs.contains(record.id),
           record.gradingStatus == nil {
            return strings.gradingQueued
        }
        if let answerGradingStatusMessage {
            return answerGradingStatusMessage
        }
        if let gradingStatus = record?.gradingStatus,
           !gradingStatus.isTerminal {
            return gradingMessage(for: gradingStatus)
        }
        if record?.gradingStatus == nil,
           let gradingRequestID = record?.gradingRequestID,
           !gradingRequestID.isEmpty {
            return strings.gradingQueued
        }
        return isGradingAnswer ? strings.gradingQueued : nil
    }

    func updateAnswer(_ answer: String, for record: StudyRecord) {
        pendingAnswerDraft = PendingAnswerDraft(question: record.question, recordID: record.id, answer: answer)
        answerDraftSaveTask?.cancel()
        let sleepProvider = appSleepProvider
        answerDraftSaveTask = Task { [weak self] in
            do {
                try await sleepProvider.sleep(nanoseconds: 450_000_000)
            } catch {
                return
            }
            await MainActor.run {
                self?.persistPendingAnswerDraft()
            }
        }
    }

    func gradeStudyRoomRecord(
        _ record: StudyRecord,
        answer submittedAnswer: String,
        pollingOwnerID suppliedPollingOwnerID: String? = nil
    ) async {
        let pollingOwnerID = suppliedPollingOwnerID ?? appIdentifierProvider.makeIdentifier()
        flushPendingAnswerDraftSave()

        let trimmedAnswer = submittedAnswer.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedAnswer.isEmpty else {
            errorMessage = "답변을 입력하세요."
            return
        }
        guard beginAnswerSubmission(for: record) else {
            errorMessage = strings.answerAlreadySubmitted
            statusMessage = strings.answerAlreadySubmitted
            return
        }
        defer {
            finishAnswerSubmission(recordID: record.id)
        }
        let sessionGeneration = communitySessionState.generation

        activateAnswerGrading(ownerID: pollingOwnerID)
        answerGradingStatusMessage = strings.gradingQueued

        errorMessage = nil
        statusMessage = "답변을 채점 중입니다."
        localStudyRecordUseCase.saveAnswerDraft(submittedAnswer, recordID: record.id)
        log(.info, "학습룸 질문 답변 채점 요청을 전송합니다. recordID=\(record.id)")

        guard let registration = await backendRegistrationForOpenAIRequests(reason: "grade-study-room-answer") else {
            errorMessage = "백엔드 등록이 없어 채점할 수 없습니다."
            statusMessage = nil
            finishAnswerGrading(ownerID: pollingOwnerID)
            log(.warning, "백엔드 등록이 없어 학습룸 질문 채점을 중단했습니다.")
            return
        }

        await actionRunner.run(
            operation: {
                let queued = try await recordsUseCase.gradeRecord(
                    registration: registration,
                    recordID: record.id,
                    answer: trimmedAnswer,
                    sourceLanguage: ContentLanguageRecognizer.detect(
                        trimmedAnswer,
                        fallback: settings.appLanguage
                    )
                )
                persistAcceptedAnswerGrading(queued)
                AppAnalytics.answerSubmitted()
                try Task.checkCancellation()
                guard isAnswerGradingOwnerCurrent(pollingOwnerID) else {
                    throw CancellationError()
                }
                return try await startAnswerGradingPolling(
                    queued,
                    registration: registration,
                    sessionGeneration: sessionGeneration,
                    ownerID: pollingOwnerID
                )
            },
            onSuccess: { updatedRecord in
                AppAnalytics.answerGradingCompleted()
                applyStudyRoomRecord(updatedRecord, answer: submittedAnswer)
                await syncRemotePushScheduleIfPossible(reason: "grade")
            },
            onFailure: { error in
                guard !(error is CancellationError),
                      isAnswerGradingSessionCurrent(sessionGeneration),
                      isAnswerGradingOwnerCurrent(pollingOwnerID) else {
                    return
                }
                AppAnalytics.answerGradingFailed()
                handleOpenAIError(error)
                statusMessage = nil
            },
            onCompletion: {
                finishAnswerGrading(ownerID: pollingOwnerID)
            }
        )
    }

    private func resumeStudyRoomAnswerGrading(
        _ queuedRecord: StudyRecord,
        pollingOwnerID: String
    ) async {
        guard let storedRegistration = storedBackendIdentityUseCase.loadRegistration(),
              let registration = await registrationWithAccessToken(
                storedRegistration,
                reason: "resume-study-room-grading"
              ) else {
            return
        }

        let sessionGeneration = communitySessionState.generation
        activateAnswerGrading(ownerID: pollingOwnerID)
        let progressMessage = gradingMessage(for: queuedRecord.gradingStatus ?? .queued)
        answerGradingStatusMessage = progressMessage
        statusMessage = progressMessage
        log(
            .info,
            "저장된 학습룸 답변 채점을 이어서 조회합니다. recordID=\(queuedRecord.id), requestID=\(queuedRecord.gradingRequestID ?? "")"
        )

        await actionRunner.run(
            operation: {
                try await startAnswerGradingPolling(
                    queuedRecord,
                    registration: registration,
                    sessionGeneration: sessionGeneration,
                    ownerID: pollingOwnerID,
                    resumeFromCurrentStatus: true
                )
            },
            onSuccess: { updatedRecord in
                applyStudyRoomRecord(
                    updatedRecord,
                    answer: updatedRecord.answer ?? queuedRecord.answer ?? ""
                )
            },
            onFailure: { error in
                guard !(error is CancellationError),
                      isAnswerGradingSessionCurrent(sessionGeneration),
                      isAnswerGradingOwnerCurrent(pollingOwnerID) else {
                    return
                }
                handleOpenAIError(error)
                statusMessage = nil
            },
            onCompletion: {
                finishAnswerGrading(ownerID: pollingOwnerID)
            }
        )
    }

    func skipStudyRoomRecord(_ record: StudyRecord) {
        skipPendingQuestion(record, shouldOpenNextQuestion: false)
    }

    func deleteStudyCategory(id: String) {
        guard let index = studyCategoriesForDisplay.firstIndex(where: { $0.id == id }) else {
            return
        }

        deleteStudyCategories(at: IndexSet(integer: index))
    }

    func deleteStudyCategories(at offsets: IndexSet) {
        let displayCategories = studyCategoriesForDisplay
        let categoriesToDelete = offsets.compactMap { index in
            displayCategories.indices.contains(index) ? displayCategories[index] : nil
        }
        let idsToDelete = Set(offsets.compactMap { index in
            displayCategories.indices.contains(index) ? displayCategories[index].id : nil
        })
        guard !idsToDelete.isEmpty else {
            return
        }
        let rootStudyIDsToDelete = Set(categoriesToDelete.compactMap { backendStudyIDIfLoaded(for: $0) })
        let studyIDsToDelete = backendStudySubtreeIDs(rootIDs: rootStudyIDsToDelete)
        let studyDeletionOrder = StudyTreeDeletionPolicy.childFirstDeletionOrder(
            studyIDs: studyIDsToDelete,
            parentByRoomID: backendStudyParentByRoomID
        )
        let legacyCategories = categoriesToDelete.filter { backendStudyIDIfLoaded(for: $0) == nil }
        let topicKeysToDelete = Set(legacyCategories.map { Self.normalizedCategoryText(for: $0.title) })
        locallyDeletedStudyIDs.formUnion(studyIDsToDelete)
        locallyDeletedStudyTopicKeys.formUnion(topicKeysToDelete)

        let currentSelectedID = settings.selectedStudyCategoryID
        let didDeleteActiveCategory = currentSelectedID.map { idsToDelete.contains($0) } ?? false
        let categoryIDsToDelete = idsToDelete.union(studyIDsToDelete.map(String.init))
        let categories = settings.studyCategories.filter { !categoryIDsToDelete.contains($0.id) }
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
            topic: selectedCategory?.normalizedTitle
                ?? StudySettings.fallbackTopic(for: settings.appLanguage),
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

        persistSettings(nextSettings, apiKey: apiKey, syncBackendSchedule: false)
        studyIDsToDelete.forEach { studyRoomState.removeStudy(id: $0) }
        Task { [weak self] in
            await self?.deleteBackendStudiesIfPossible(
                knownStudyIDs: studyIDsToDelete,
                preferredDeletionOrder: studyDeletionOrder,
                topicKeys: topicKeysToDelete
            )
        }
        if didDeleteActiveCategory {
            if let selectedCategory {
                activateStudyContext(forTopic: selectedCategory.title)
            } else {
                currentQuestion = nil
                lastAnswer = ""
                gradingResult = nil
                currentStudySessionUseCase.saveCurrentQuestionState(
                    question: nil,
                    lastAnswer: "",
                    gradingResult: nil
                )
            }
        }
    }

    private func backendStudyIDIfLoaded(for category: StudyCategory) -> Int? {
        if let studyID = Int(category.id) {
            return studyID
        }

        let topicKey = Self.normalizedCategoryText(for: category.title)
        return backendStudyRooms.first {
            Self.normalizedCategoryText(for: $0.topic) == topicKey
        }?.id
    }

    private func backendStudySubtreeIDs(rootIDs: Set<Int>) -> Set<Int> {
        StudyTreeDeletionPolicy.subtreeIDs(
            rootIDs: rootIDs,
            parentByRoomID: backendStudyParentByRoomID
        )
    }

    private var backendStudyParentByRoomID: [Int: Int] {
        Dictionary(uniqueKeysWithValues: backendStudyRooms.compactMap { room -> (Int, Int)? in
            guard let parentID = room.parentStudyId else {
                return nil
            }
            return (room.id, parentID)
        })
    }

    private func deleteBackendStudiesIfPossible(
        knownStudyIDs: Set<Int>,
        preferredDeletionOrder: [Int],
        topicKeys: Set<String>
    ) async {
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "delete-study") else {
            log(.warning, "백엔드 등록이 없어 학습 삭제 동기화를 건너뛰었습니다. studyIDs=\(knownStudyIDs.sorted()), topics=\(topicKeys.sorted())")
            return
        }

        var studyIDs = knownStudyIDs
        if !topicKeys.isEmpty {
            await actionRunner.run(
                operation: {
                    try await performWithBackendIdentityRecovery(
                        registration: registration,
                        reason: "delete-study-resolve",
                        operation: { recoveredRegistration in
                            try await studyRoomUseCase.fetchStudy(
                                registration: recoveredRegistration,
                                limit: 500,
                                offset: 0,
                                query: "",
                                language: settings.appLanguage
                            )
                        }
                    )
                },
                onSuccess: { studyPage in
                    let resolvedIDs = studyPage.studies
                        .filter { topicKeys.contains(Self.normalizedCategoryText(for: $0.topic)) }
                        .map(\.id)
                    studyIDs.formUnion(resolvedIDs)
                    locallyDeletedStudyIDs.formUnion(resolvedIDs)
                },
                onFailure: { error in
                    log(.warning, "백엔드 학습 삭제용 id 조회 실패: topics=\(topicKeys.sorted()), error=\(error.localizedDescription)")
                }
            )
        }

        guard !studyIDs.isEmpty else {
            log(.warning, "삭제할 백엔드 학습 id를 찾지 못했습니다. topics=\(topicKeys.sorted())")
            return
        }

        let remainingStudyIDs = studyIDs.subtracting(preferredDeletionOrder)
        let deletionOrder = preferredDeletionOrder.filter(studyIDs.contains)
            + remainingStudyIDs.sorted()
        var deletedStudyIDs = Set<Int>()
        for studyID in deletionOrder {
            let didDelete = await actionRunner.runVoid(
                operation: {
                    try await performWithBackendIdentityRecovery(
                        registration: registration,
                        reason: "delete-study",
                        operation: { recoveredRegistration in
                            try await studyRoomUseCase.deleteStudy(registration: recoveredRegistration, studyID: studyID)
                        }
                    )
                },
                onFailure: { error in
                    log(.warning, "백엔드 학습 삭제 실패: id=\(studyID), error=\(error.localizedDescription)")
                }
            )

            if didDelete {
                deletedStudyIDs.insert(studyID)
                log(.info, "백엔드 학습을 삭제했습니다. id=\(studyID)")
            }
        }

        guard !deletedStudyIDs.isEmpty else {
            return
        }

        let didRefresh = await refreshBackendStudyIfPossible(updateVisibleQuestion: false)
        if didRefresh {
            locallyDeletedStudyIDs.subtract(deletedStudyIDs)
            locallyDeletedStudyTopicKeys.subtract(topicKeys)
            log(.info, "백엔드 학습 삭제 후 내 학습을 다시 동기화했습니다. deletedStudyIDs=\(deletedStudyIDs.sorted())")
        }
    }

    private func isLocallyDeletedStudy(_ study: BackendStudyRoom) -> Bool {
        locallyDeletedStudyIDs.contains(study.id) ||
            locallyDeletedStudyTopicKeys.contains(Self.normalizedCategoryText(for: study.topic))
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

    func completeOnboarding(settings pendingSettings: StudySettings, apiKey _: String = "") async {
        let initialCategory = pendingSettings.activeCategory ?? pendingSettings.studyCategories.first
        let completionLanguage = pendingSettings.appLanguage

        persistSettings(
            pendingSettings,
            apiKey: ""
        )
        onboardingStateUseCase.setHasCompletedOnboarding(true)
        hasCompletedOnboarding = true
        #if os(iOS)
        selectedTab = .home
        #else
        selectedTab = .study
        #endif
        markCloudDataChanged()
        isValidatingAPIKey = false
        hasAPIKeyError = false
        errorMessage = nil
        statusMessage = AppStrings(language: completionLanguage).onboardingCompleted
        log(.info, "온보딩을 완료했습니다. OpenAI 요청은 서버 시스템 키로 처리됩니다.")
        restartTimer()

        Task { [weak self] in
            guard let self else { return }

            if let initialCategory {
                _ = await self.createBackendStudyIfPossible(initialCategory, settings: pendingSettings)
            }

            #if os(iOS)
            if self.isCommunitySessionActive {
                _ = await self.notificationService.requestAuthorizationIfNeeded(language: completionLanguage)
            } else {
                self.notificationService.deactivateRemoteNotificationsForLogout()
            }
            #else
            _ = await self.notificationService.requestAuthorizationIfNeeded(language: completionLanguage)
            #endif
        }
    }

    func skipOnboarding(language selectedLanguage: AppLanguage? = nil) {
        if let selectedLanguage {
            var skippedSettings = settings
            let currentTopic = skippedSettings.topic.trimmingCharacters(in: .whitespacesAndNewlines)
            let previousFallback = StudySettings.fallbackTopic(for: skippedSettings.appLanguage)

            skippedSettings.appLanguage = selectedLanguage
            skippedSettings.language = selectedLanguage.studyLanguage
            if currentTopic.isEmpty || currentTopic == previousFallback {
                skippedSettings.topic = StudySettings.fallbackTopic(for: selectedLanguage)
            }

            persistSettings(
                skippedSettings,
                apiKey: apiKey,
                syncBackendSchedule: false
            )
            AppAnalytics.setLanguage(selectedLanguage)
        }

        onboardingStateUseCase.setHasCompletedOnboarding(true)
        hasCompletedOnboarding = true
        #if os(iOS)
        selectedTab = .home
        #else
        selectedTab = .settings
        #endif

        hasAPIKeyError = false
        errorMessage = nil
        statusMessage = AppStrings(language: selectedLanguage ?? settings.appLanguage).onboardingSkipped
        log(.info, "온보딩을 나중에 설정하도록 건너뛰었습니다.")
        markCloudDataChanged()
        restartTimer()
    }

    private func persistSettings(
        _ pendingSettings: StudySettings,
        apiKey pendingAPIKey: String,
        syncBackendSchedule: Bool = true
    ) {
        let profileSettings = settingsWithResolvedStudyProfile(from: pendingSettings)
        let synchronizedSettings = synchronizedTopicCategories(
            for: profileSettings,
            includeResolvedTopicCategory: false
        )
        var sanitizedSettings = normalizedSettings(synchronizedSettings)
        if !isCommunitySessionActive {
            sanitizedSettings = sanitizedSettings.withQuestionPrivacy(false)
        }
        let trimmedAPIKey = pendingAPIKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedDebugBackendURL = normalizedDebugBackendBaseURL(activeDebugBackendBaseURLForEditing)
        let now = appClock.now
        let didAPIKeyChange = trimmedAPIKey != savedAPIKey
        if didAPIKeyChange {
            lastAPIKeyUpdatedAt = now
            localStudySettingsUseCase.saveAPIKeyUpdatedAt(now)
        } else if localStudySettingsUseCase.loadSettings().openAIAPIKeyUpdatedAt == nil, !trimmedAPIKey.isEmpty {
            lastAPIKeyUpdatedAt = now
            localStudySettingsUseCase.saveAPIKeyUpdatedAt(now)
        }
        lastLocalSettingsMutationAt = now
        localStudySettingsUseCase.saveSettingsMutationAt(now)

        settings = sanitizedSettings
        apiKey = trimmedAPIKey
        debugBackendBaseURL = normalizedDebugBackendURL
        draftSettings = sanitizedSettings
        draftAPIKey = trimmedAPIKey
        draftDebugBackendBaseURL = normalizedDebugBackendURL
        didReceiveCloudStateWhileEditing = false

        localStudySettingsUseCase.saveSettings(sanitizedSettings)
        localStudySettingsUseCase.saveAPIKey(trimmedAPIKey)
        developerSettingsUseCase.saveDebugBackendBaseURL(normalizedDebugBackendURL)
        savedSettings = sanitizedSettings
        savedAPIKey = trimmedAPIKey
        savedDebugBackendBaseURL = normalizedDebugBackendURL
        reloadStudyRecordsFromStore()
        if trimmedAPIKey.isEmpty {
            hasAPIKeyError = false
            errorMessage = nil
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
            if syncBackendSchedule {
                await syncRemotePushScheduleIfPossible(reason: "settings")
            }
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

        persistSettings(
            pendingSettings,
            apiKey: ""
        )
        hasAPIKeyError = false
        isValidatingAPIKey = false
        statusMessage = nil
        errorMessage = nil
        log(.info, "설정을 저장했습니다. OpenAI 요청은 서버 시스템 키로 처리됩니다.")
    }

    func setRunning(_ running: Bool) {
        isRunning = running
        currentStudySessionUseCase.saveExplicitIsRunning(running)
        statusMessage = running ? "질문 타이머가 실행 중입니다." : "질문 타이머를 일시정지했습니다."
        log(.info, running ? "질문 타이머를 실행했습니다." : "질문 타이머를 중지했습니다.")
        markCloudDataChanged()
        restartTimer()

        Task {
            await syncRemotePushScheduleIfPossible(reason: "running")
        }
    }

    func setTimerInterval(_ minutes: Int) {
        let intervalMinutes = min(max(minutes, 1), 240)
        settings.intervalMinutes = intervalMinutes
        draftSettings.intervalMinutes = intervalMinutes
        localStudySettingsUseCase.saveSettings(settings)
        savedSettings = normalizedSettings(settings)
        reloadStudyRecordsFromStore()
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

    func ensureSystemNotificationPermissionForPreferenceEnable(reason: String) async -> Bool {
        let isAuthorized = await notificationService.requestAuthorizationIfNeeded(language: settings.appLanguage)
        if !isAuthorized {
            statusMessage = strings.notificationSystemPermissionRequired
            notificationService.openSystemNotificationSettings()
            log(.info, "시스템 알림 권한이 꺼져 있어 설정으로 이동합니다. reason=\(reason)")
        }
        return isAuthorized
    }

    func setAppLanguage(_ language: AppLanguage) {
        updateAppLanguage(language)
        AppAnalytics.setLanguage(language)
        localStudySettingsUseCase.saveSettings(settings)
        savedSettings = normalizedSettings(settings)
        reloadStudyRecordsFromStore()
        StudyNotificationDelegate.shared.register(language: language)
        statusMessage = language == .korean ? "앱 언어를 한국어로 설정했습니다." : "App language set to English."
        log(.info, "앱 언어를 \(language.rawValue)로 변경했습니다.")
        markCloudDataChanged()

        Task {
            await syncRemotePushScheduleIfPossible(reason: "language")
        }
    }

    func generateQuestion(manual: Bool = true, studyCategoryID: String? = nil) async {
        notificationLandingMessage = nil

        if !manual && !isRunning {
            log(.info, "타이머가 중지되어 예약 질문 생성을 건너뛰었습니다.")
            return
        }

        guard !isGeneratingQuestion, questionGenerationPollingTask == nil else {
            log(.info, "이미 질문 생성 중이라 새 요청을 무시했습니다.")
            return
        }

        let resolvedCategoryID = studyCategoryID ?? settings.selectedStudyCategoryID
        generatingQuestionCategoryID = resolvedCategoryID
        isGeneratingQuestion = true

        guard let registration = await backendRegistrationForOpenAIRequests(reason: manual ? "manual-question" : "scheduled-question") else {
            statusMessage = nil
            errorMessage = "백엔드 등록이 없어 질문을 생성할 수 없습니다. 네트워크와 알림 권한을 확인한 뒤 다시 시도하세요."
            log(.warning, "백엔드 등록이 없어 질문 생성을 중단했습니다.")
            finishQuestionGenerationProcess()
            return
        }

        await generateBackendQuestion(
            registration: registration,
            manual: manual,
            studyCategoryID: resolvedCategoryID
        )
    }

    func isGeneratingQuestion(categoryID: String?) -> Bool {
        isGeneratingQuestion && generatingQuestionCategoryID == categoryID
    }

    private func generateBackendQuestion(registration: RemotePushRegistration, manual: Bool, studyCategoryID: String?) async {
        guard requirePageAccess(.studyDetail) else {
            finishQuestionGenerationProcess()
            return
        }

        guard await canCreateQuestionAfterPendingCheck(
            studyCategoryID: studyCategoryID,
            reason: "백엔드 새 질문 생성",
            updateVisibleQuestion: manual
        ) else {
            finishQuestionGenerationProcess()
            return
        }

        errorMessage = nil
        statusMessage = strings.fetchingQuestion
        log(.info, "백엔드 새 질문 생성을 준비합니다. studyCategoryID=\(studyCategoryID ?? "-")")

        guard let studyID = await backendStudyID(for: studyCategoryID) else {
            await handleQuestionGenerationRequestFailure(
                AppStateError.backendStudyMissing,
                manual: manual,
                studyCategoryID: studyCategoryID
            )
            finishQuestionGenerationProcess()
            return
        }

        let pending = PendingQuestionGenerationProcess(
            idempotencyKey: appIdentifierProvider.makeIdentifier(),
            correlationID: nil,
            studyID: studyID,
            studyCategoryID: studyCategoryID,
            submittedAt: appClock.now
        )
        AppAnalytics.questionRequested(source: manual ? .manual : .scheduled)
        startQuestionGenerationPolling(pending: pending, registration: registration, manual: manual)
    }

    private func startQuestionGenerationPolling(
        pending: PendingQuestionGenerationProcess,
        registration: RemotePushRegistration,
        manual: Bool
    ) {
        guard questionGenerationPollingTask == nil else {
            let activePending = currentStudySessionUseCase.loadPendingQuestionGenerationProcess()
            isGeneratingQuestion = true
            generatingQuestionCategoryID = activePending?.studyCategoryID
            log(.warning, "종료되지 않은 질문 생성 작업이 있어 중복 폴링 시작을 차단했습니다.")
            return
        }
        currentStudySessionUseCase.savePendingQuestionGenerationProcess(pending)
        isGeneratingQuestion = true
        generatingQuestionCategoryID = pending.studyCategoryID
        statusMessage = strings.fetchingQuestion
        questionGenerationPollingTask = Task { @MainActor [weak self] in
            guard let self else {
                return
            }
            await runQuestionGenerationPolling(
                pending: pending,
                registration: registration,
                manual: manual
            )
        }
    }

    private func runQuestionGenerationPolling(
        pending initialPending: PendingQuestionGenerationProcess,
        registration: RemotePushRegistration,
        manual: Bool
    ) async {
        var pending = initialPending
        var consecutiveTransportFailures = 0
        defer {
            questionGenerationPollingTask = nil
        }

        while !Task.isCancelled {
            if pending.correlationID == nil {
                do {
                    log(.info, "백엔드 질문 생성 요청을 등록합니다. studyID=\(pending.studyID)")
                    let accepted = try await performWithBackendIdentityRecovery(
                        registration: registration,
                        reason: "question-generation-submit",
                        operation: { recoveredRegistration in
                            try await studyRoomUseCase.createQuestion(
                                registration: recoveredRegistration,
                                studyID: pending.studyID,
                                idempotencyKey: pending.idempotencyKey
                            )
                        }
                    )
                    pending.correlationID = accepted.correlationID
                    currentStudySessionUseCase.savePendingQuestionGenerationProcess(pending)
                    consecutiveTransportFailures = 0
                    statusMessage = strings.fetchingQuestion
                    log(
                        .info,
                        "질문 생성 요청이 접수됐습니다. correlationID=\(accepted.correlationID), status=\(accepted.status.rawValue)"
                    )
                    await sleepForQuestionGeneration(milliseconds: accepted.pollAfterMilliseconds)
                } catch {
                    if appErrorHandlingUseCase.isPermanentBackendOperationError(error) {
                        await handleQuestionGenerationRequestFailure(
                            error,
                            manual: manual,
                            studyCategoryID: pending.studyCategoryID
                        )
                        finishQuestionGenerationProcess()
                        return
                    }
                    consecutiveTransportFailures += 1
                    statusMessage = strings.fetchingQuestion
                    log(.warning, "질문 생성 요청 연결 실패 후 재시도합니다. error=\(error.localizedDescription)")
                    await sleepForQuestionGenerationRetry(failureCount: consecutiveTransportFailures)
                }
                continue
            }

            guard let correlationID = pending.correlationID else {
                continue
            }

            do {
                let process = try await performWithBackendIdentityRecovery(
                    registration: registration,
                    reason: "question-generation-poll",
                    operation: { recoveredRegistration in
                        try await studyRoomUseCase.fetchQuestionGenerationProcess(
                            registration: recoveredRegistration,
                            correlationID: correlationID
                        )
                    }
                )
                consecutiveTransportFailures = 0
                if process.terminal {
                    if process.status == .completed, let record = process.question {
                        AppAnalytics.questionGenerationCompleted(source: manual ? .manual : .scheduled)
                        applyCompletedQuestionGeneration(record, fallbackStudyID: pending.studyID)
                    } else {
                        AppAnalytics.questionGenerationFailed(source: manual ? .manual : .scheduled)
                        let message = process.error?.message ?? strings.communityRequestFailed
                        errorMessage = message
                        statusMessage = nil
                        log(
                            .error,
                            "질문 생성 Saga가 실패했습니다. correlationID=\(correlationID), step=\(process.failedStep?.rawValue ?? "-"), error=\(message)"
                        )
                    }
                    finishQuestionGenerationProcess()
                    scheduleQuestionQuotaRefresh()
                    return
                }

                statusMessage = strings.fetchingQuestion
                await sleepForQuestionGeneration(milliseconds: process.pollAfterMilliseconds ?? 250)
            } catch {
                if appErrorHandlingUseCase.isPermanentBackendOperationError(error) {
                    await handleQuestionGenerationRequestFailure(
                        error,
                        manual: manual,
                        studyCategoryID: pending.studyCategoryID
                    )
                    finishQuestionGenerationProcess()
                    return
                }
                consecutiveTransportFailures += 1
                statusMessage = strings.fetchingQuestion
                log(
                    .warning,
                    "질문 생성 상태 조회 연결 실패 후 재시도합니다. correlationID=\(correlationID), error=\(error.localizedDescription)"
                )
                await sleepForQuestionGenerationRetry(failureCount: consecutiveTransportFailures)
            }
        }
    }

    private func applyCompletedQuestionGeneration(_ record: StudyRecord, fallbackStudyID: Int) {
        localStudyRecordUseCase.appendQuestionToHistory(record.question)
        localStudyRecordUseCase.replaceRecords(mergeBackendRecord(record, into: studyRecords))
        reloadStudyRecordsFromStore()
        studyRoomState.setPendingQuestion(record, forStudyID: record.studyID ?? fallbackStudyID)

        let shouldActivateQuestion = !hasActiveUngradedCurrentQuestion
        if shouldActivateQuestion {
            currentQuestion = record.question
            gradingResult = record.gradingResult
            lastAnswer = record.answer ?? ""
            currentStudySessionUseCase.saveCurrentQuestionState(
                question: record.question,
                lastAnswer: record.answer ?? "",
                gradingResult: record.gradingResult
            )
        }

        hasAPIKeyError = false
        statusMessage = shouldActivateQuestion
            ? strings.questionGenerationCompleted
            : strings.questionGenerationCompletedWhileDrafting
        log(
            .info,
            "질문 생성 Saga가 완료됐습니다. studyID=\(record.studyID ?? fallbackStudyID), recordID=\(record.id)"
        )
    }

    private func resumePendingQuestionGenerationIfNeeded(reason: String) async {
        guard questionGenerationPollingTask == nil,
              let pending = currentStudySessionUseCase.loadPendingQuestionGenerationProcess() else {
            return
        }
        guard let registration = await backendRegistrationForOpenAIRequests(
            reason: "question-generation-resume-\(reason)"
        ) else {
            isGeneratingQuestion = true
            generatingQuestionCategoryID = pending.studyCategoryID
            statusMessage = strings.fetchingQuestion
            log(.warning, "저장된 질문 생성 상태를 복구할 인증 정보를 아직 가져오지 못했습니다. reason=\(reason)")
            return
        }
        log(
            .info,
            "저장된 질문 생성 상태 조회를 재개합니다. correlationID=\(pending.correlationID ?? "pending-submit"), reason=\(reason)"
        )
        startQuestionGenerationPolling(pending: pending, registration: registration, manual: true)
    }

    private func finishQuestionGenerationProcess() {
        currentStudySessionUseCase.savePendingQuestionGenerationProcess(nil)
        isGeneratingQuestion = false
        generatingQuestionCategoryID = nil
    }

    private func sleepForQuestionGeneration(milliseconds: Int) async {
        let normalized = max(50, min(milliseconds, 5_000))
        try? await appSleepProvider.sleep(nanoseconds: UInt64(normalized) * 1_000_000)
    }

    private func sleepForQuestionGenerationRetry(failureCount: Int) async {
        let delay = min(5_000, max(1_000, failureCount * 1_000))
        await sleepForQuestionGeneration(milliseconds: delay)
    }

    private func handleQuestionGenerationRequestFailure(
        _ error: Error,
        manual: Bool,
        studyCategoryID: String?
    ) async {
        statusMessage = nil
        let resolution = appErrorHandlingUseCase.resolve(
            error,
            fallback: strings.monthlyQuotaReached,
            language: settings.appLanguage
        )
        if resolution.isQuotaExceeded {
            let message = resolution.featureMessage ?? strings.monthlyQuotaReached
            questionQuotaNotice = message
            errorMessage = message
            scheduleQuestionQuotaRefresh()
        }
        if resolution.isPendingQuestionConflict {
            await refreshBackendStudyIfPossible(updateVisibleQuestion: false)
            showPendingQuestionLimitStatus(
                reason: "백엔드 질문 생성 충돌",
                categoryID: studyCategoryID
            )
            return
        }
        if handleAppError(
            error,
            fallback: "",
            target: .none,
            protectedPage: .studyDetail,
            termsRetry: { [weak self] in
                await self?.generateQuestion(manual: manual, studyCategoryID: studyCategoryID)
            }
        ) {
            return
        }
        handleOpenAIError(error)
        log(.error, "백엔드 질문 생성 요청에 실패했습니다: \(error.localizedDescription)")
    }

    private func scheduleQuestionQuotaRefresh() {
        Task { @MainActor [weak self] in
            await self?.refreshQuestionQuota()
        }
    }

    private func backendStudyID(for categoryID: String?) async -> Int? {
        if let categoryID,
           let studyID = Int(categoryID) {
            return studyID
        }

        if let activeCategory = settings.activeCategory,
           categoryID == nil,
           let studyID = Int(activeCategory.id) {
            return studyID
        }

        let requestedTopicKey = categoryID
            .flatMap { settings.category(for: $0)?.title }
            .map { Self.normalizedCategoryText(for: $0) }

        await refreshBackendStudyIfPossible(updateVisibleQuestion: false)

        if let requestedTopicKey,
           let matched = settings.studyCategories.first(where: { Self.normalizedCategoryText(for: $0.title) == requestedTopicKey }),
           let studyID = Int(matched.id) {
            return studyID
        }

        return settings.activeCategory.flatMap { Int($0.id) }
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
        #if os(macOS)
        await refreshBackendStudyIfPossible(updateVisibleQuestion: false)
        #endif
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

    private func canCreateQuestionAfterPendingCheck(
        studyCategoryID: String?,
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

        let targetCategoryID = studyCategoryID ?? settings.selectedStudyCategoryID
        guard !hasReachedPendingQuestionLimit(categoryID: targetCategoryID) else {
            log(.info, "\(reason)을 건너뛰었습니다. 해당 주제에 답변 대기 중인 질문이 있습니다. studyCategoryID=\(targetCategoryID ?? "-")")
            showPendingQuestionLimitStatus(reason: reason, categoryID: targetCategoryID)
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
            try? await appSleepProvider.sleep(nanoseconds: 100_000_000)
            if !isCloudSyncing {
                return
            }
        }
    }

    func sendTestNotification() async {
        let question = QuestionItem(
            question: strings.testNotificationBody,
            expectedAnswerHint: nil,
            createdAt: appClock.now
        )

        let didSend = await notificationService.showQuestionNotification(
            question: question,
            title: strings.newQuestionNotificationTitle,
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
        QuestionSchedulePolicy.isDue(
            now: now,
            intervalMinutes: settings.sanitizedIntervalMinutes,
            currentQuestion: currentQuestion,
            studyRecords: studyRecords
        )
    }

    private func nextQuestionDueDate(now: Date) -> Date {
        QuestionSchedulePolicy.nextDueDate(
            now: now,
            intervalMinutes: settings.sanitizedIntervalMinutes,
            currentQuestion: currentQuestion,
            studyRecords: studyRecords
        )
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
        if let studyID = Int(category.id) {
            let exactStudyRecords = pendingRecordsIncludingCurrent.filter { $0.studyID == studyID }
            if let currentQuestion,
               let currentRecord = exactStudyRecords.first(where: { studyRecordMatches($0, question: currentQuestion) }) {
                return currentRecord
            }

            return exactStudyRecords.max { $0.question.createdAt < $1.question.createdAt }
        }

        let categoryKey = Self.normalizedCategoryText(for: category.title)
        let preferredRecords = pendingRecordsIncludingCurrent
            .filter { Self.normalizedCategoryText(for: $0.topic) == categoryKey }

        if let currentQuestion,
           let currentRecord = preferredRecords.first(where: { studyRecordMatches($0, question: currentQuestion) }) {
            return currentRecord
        }

        return preferredRecords.max { $0.question.createdAt < $1.question.createdAt }
    }

    private func applyPreferredPendingRecord(for category: StudyCategory) {
        guard let record = preferredPendingRecord(for: category) else {
            return
        }

        let answer = answerForCurrentSession(record)
        notificationLandingMessage = nil
        currentQuestion = record.question
        lastAnswer = answer
        gradingResult = record.gradingResult
        currentStudySessionUseCase.saveCurrentQuestionState(
            question: record.question,
            lastAnswer: answer,
            gradingResult: record.gradingResult
        )
    }

    private func studyCategoryForRoom(_ categoryID: String?) -> StudyCategory? {
        let categories = synchronizedTopicCategories(for: settings).studyCategories
        if let categoryID,
           let category = categories.first(where: { $0.id == categoryID }) {
            return category
        }

        if let selectedCategoryID = settings.selectedStudyCategoryID,
           let category = categories.first(where: { $0.id == selectedCategoryID }) {
            return category
        }

        return categories.first
    }

    private func studyCategoryMatchingTopic(_ topic: String) -> StudyCategory? {
        let topicKey = Self.normalizedCategoryText(for: topic)
        return synchronizedTopicCategories(for: settings).studyCategories.first {
            Self.normalizedCategoryText(for: $0.title) == topicKey
        }
    }

    func gradeCurrentAnswer(
        answer submittedAnswer: String? = nil,
        pollingOwnerID suppliedPollingOwnerID: String? = nil
    ) async {
        let pollingOwnerID = suppliedPollingOwnerID ?? appIdentifierProvider.makeIdentifier()
        guard let currentQuestion else {
            errorMessage = "먼저 질문을 생성하세요."
            return
        }

        flushPendingAnswerDraftSave()

        let answerToGrade = submittedAnswer ?? lastAnswer
        let trimmedAnswer = answerToGrade.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedAnswer.isEmpty else {
            errorMessage = "답변을 입력하세요."
            return
        }
        guard let record = studyRecord(matching: currentQuestion) else {
            errorMessage = "이 질문은 백엔드 기록에 없어 채점할 수 없습니다. 새 질문을 다시 생성하세요."
            statusMessage = nil
            log(.warning, "현재 질문에 매칭되는 백엔드 기록이 없어 채점을 중단했습니다.")
            return
        }
        let sessionGeneration = communitySessionState.generation

        activateAnswerGrading(ownerID: pollingOwnerID)
        answerGradingStatusMessage = strings.gradingQueued
        errorMessage = nil
        statusMessage = "답변을 채점 중입니다."
        lastAnswer = answerToGrade
        currentStudySessionUseCase.saveLastAnswer(answerToGrade)
        localStudyRecordUseCase.saveAnswerDraft(answerToGrade, recordID: record.id)
        log(.info, "현재 질문 답변 채점 요청을 전송합니다.")

        guard let registration = await backendRegistrationForOpenAIRequests(reason: "grade-current-answer") else {
            errorMessage = "백엔드 등록이 없어 채점할 수 없습니다."
            statusMessage = nil
            finishAnswerGrading(ownerID: pollingOwnerID)
            log(.warning, "백엔드 등록이 없어 현재 질문 채점을 중단했습니다.")
            return
        }

        await actionRunner.run(
            operation: {
                let queued = try await recordsUseCase.gradeRecord(
                    registration: registration,
                    recordID: record.id,
                    answer: trimmedAnswer,
                    sourceLanguage: ContentLanguageRecognizer.detect(
                        trimmedAnswer,
                        fallback: settings.appLanguage
                    )
                )
                persistAcceptedAnswerGrading(queued)
                AppAnalytics.answerSubmitted()
                try Task.checkCancellation()
                guard isAnswerGradingOwnerCurrent(pollingOwnerID) else {
                    throw CancellationError()
                }
                return try await startAnswerGradingPolling(
                    queued,
                    registration: registration,
                    sessionGeneration: sessionGeneration,
                    ownerID: pollingOwnerID
                )
            },
            onSuccess: { updatedRecord in
                AppAnalytics.answerGradingCompleted()
                applyGradedRecord(updatedRecord, answer: trimmedAnswer)
                await syncRemotePushScheduleIfPossible(reason: "grade")
            },
            onFailure: { error in
                guard !(error is CancellationError),
                      isAnswerGradingSessionCurrent(sessionGeneration),
                      isAnswerGradingOwnerCurrent(pollingOwnerID) else {
                    return
                }
                AppAnalytics.answerGradingFailed()
                handleOpenAIError(error)
                statusMessage = nil
            },
            onCompletion: {
                finishAnswerGrading(ownerID: pollingOwnerID)
            }
        )
    }

    private func applyGradedRecord(_ record: StudyRecord, answer: String) {
        localStudyRecordUseCase.deleteAnswerDraft(recordID: record.id)
        currentQuestion = record.question
        lastAnswer = answer
        gradingResult = record.gradingResult
        currentStudySessionUseCase.saveCurrentQuestionState(
            question: record.question,
            lastAnswer: answer,
            gradingResult: record.gradingResult
        )
        localStudyRecordUseCase.replaceRecords(mergeBackendRecord(record, into: studyRecords))
        notificationService.cancelQuestionNotification(for: record.question)
        reloadStudyRecordsFromStore()
        hasAPIKeyError = false
        statusMessage = "채점이 완료됐습니다."
        log(.info, "백엔드에서 답변을 채점했습니다. score=\(record.gradingResult?.score ?? 0)")
    }

    private func applyStudyRoomRecord(_ record: StudyRecord, answer: String) {
        localStudyRecordUseCase.deleteAnswerDraft(recordID: record.id)
        localStudyRecordUseCase.replaceRecords(mergeBackendRecord(record, into: studyRecords))
        reloadStudyRecordsFromStore()
        studyRoomState.applyAnsweredRecord(record)
        currentQuestion = record.question
        lastAnswer = answer
        gradingResult = record.gradingResult
        currentStudySessionUseCase.saveCurrentQuestionState(
            question: record.question,
            lastAnswer: answer,
            gradingResult: record.gradingResult
        )
        notificationService.cancelQuestionNotification(for: record.question)
        hasAPIKeyError = false
        statusMessage = "채점이 완료됐습니다."
        log(.info, "학습룸 답변을 채점했습니다. recordID=\(record.id), score=\(record.gradingResult?.score ?? 0)")
    }

    func gradeRecord(
        _ record: StudyRecord,
        answer: String,
        pollingOwnerID suppliedPollingOwnerID: String? = nil
    ) async {
        let pollingOwnerID = suppliedPollingOwnerID ?? appIdentifierProvider.makeIdentifier()
        let trimmedAnswer = answer.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedAnswer.isEmpty else {
            errorMessage = "답변을 입력하세요."
            return
        }
        guard beginAnswerSubmission(for: record) else {
            errorMessage = strings.answerAlreadySubmitted
            statusMessage = strings.answerAlreadySubmitted
            return
        }
        defer {
            finishAnswerSubmission(recordID: record.id)
        }
        let sessionGeneration = communitySessionState.generation

        activateAnswerGrading(ownerID: pollingOwnerID)
        answerGradingStatusMessage = strings.gradingQueued
        errorMessage = nil
        statusMessage = "기록의 답변을 채점 중입니다."
        log(.info, "기록 답변 채점 요청을 전송합니다.")

        guard let registration = await backendRegistrationForOpenAIRequests(reason: "grade-record") else {
            errorMessage = "백엔드 등록이 없어 채점할 수 없습니다."
            statusMessage = nil
            finishAnswerGrading(ownerID: pollingOwnerID)
            log(.warning, "백엔드 등록이 없어 기록 채점을 중단했습니다.")
            return
        }

        await actionRunner.run(
            operation: {
                let queued = try await recordsUseCase.gradeRecord(
                    registration: registration,
                    recordID: record.id,
                    answer: trimmedAnswer,
                    sourceLanguage: ContentLanguageRecognizer.detect(
                        trimmedAnswer,
                        fallback: settings.appLanguage
                    )
                )
                persistAcceptedAnswerGrading(queued)
                try Task.checkCancellation()
                guard isAnswerGradingOwnerCurrent(pollingOwnerID) else {
                    throw CancellationError()
                }
                return try await startAnswerGradingPolling(
                    queued,
                    registration: registration,
                    sessionGeneration: sessionGeneration,
                    ownerID: pollingOwnerID
                )
            },
            onSuccess: { updatedRecord in
                applyGradedRecord(updatedRecord, answer: trimmedAnswer)
                await syncRemotePushScheduleIfPossible(reason: "grade-record")
                markCloudDataChanged()
            },
            onFailure: { error in
                guard !(error is CancellationError),
                      isAnswerGradingSessionCurrent(sessionGeneration),
                      isAnswerGradingOwnerCurrent(pollingOwnerID) else {
                    return
                }
                handleOpenAIError(error)
                statusMessage = nil
            },
            onCompletion: {
                finishAnswerGrading(ownerID: pollingOwnerID)
            }
        )
    }

    private func startAnswerGradingPolling(
        _ queuedRecord: StudyRecord,
        registration: RemotePushRegistration,
        sessionGeneration: UInt64,
        ownerID: String,
        resumeFromCurrentStatus: Bool = false
    ) async throws -> StudyRecord {
        guard isAnswerGradingOwnerCurrent(ownerID) else {
            throw CancellationError()
        }
        answerGradingPollingTask?.cancel()
        let pollingID = appIdentifierProvider.makeIdentifier()
        let task = Task { @MainActor [weak self] () throws -> StudyRecord in
            guard let self else {
                throw CancellationError()
            }
            return try await awaitGradingResult(
                queuedRecord,
                registration: registration,
                sessionGeneration: sessionGeneration,
                ownerID: ownerID,
                resumeFromCurrentStatus: resumeFromCurrentStatus
            )
        }
        answerGradingPollingID = pollingID
        answerGradingPollingTask = task
        defer {
            if answerGradingPollingID == pollingID {
                answerGradingPollingID = nil
                answerGradingPollingTask = nil
            }
        }
        return try await task.value
    }

    private func awaitGradingResult(
        _ queuedRecord: StudyRecord,
        registration: RemotePushRegistration,
        sessionGeneration: UInt64,
        ownerID: String,
        resumeFromCurrentStatus: Bool
    ) async throws -> StudyRecord {
        guard isAnswerGradingSessionCurrent(sessionGeneration),
              isAnswerGradingOwnerCurrent(ownerID) else {
            throw CancellationError()
        }
        if queuedRecord.gradingResult != nil {
            return queuedRecord
        }
        guard let correlationID = queuedRecord.correlationID ?? queuedRecord.gradingRequestID,
              !correlationID.isEmpty else {
            throw AnswerGradingProcessError.failed(strings.gradingFailed)
        }

        var cursor = queuedRecord.gradingLastEventID ?? 0
        var consecutiveTransportFailures = 0
        var displayedStatus = queuedRecord.gradingStatus ?? .queued
        var displayedAt = appClock.now
        let queuedMessage = gradingMessage(for: displayedStatus)
        statusMessage = queuedMessage
        answerGradingStatusMessage = queuedMessage

        while !Task.isCancelled &&
                isAnswerGradingSessionCurrent(sessionGeneration) &&
                isAnswerGradingOwnerCurrent(ownerID) {
            do {
                let process = try await recordsUseCase.fetchAnswerGradingProcess(
                    registration: registration,
                    correlationID: correlationID,
                    afterEventID: cursor
                )
                try Task.checkCancellation()
                guard isAnswerGradingSessionCurrent(sessionGeneration),
                      isAnswerGradingOwnerCurrent(ownerID) else {
                    throw CancellationError()
                }
                consecutiveTransportFailures = 0
                if resumeFromCurrentStatus {
                    cursor = process.events.reduce(cursor) { max($0, $1.id) }
                    persistAnswerGradingProgress(
                        process.status,
                        questionStatus: process.questionStatus,
                        errorMessage: process.errorMessage,
                        eventID: cursor > 0 ? cursor : nil,
                        for: queuedRecord,
                        usesAuthoritativeStatus: true
                    )
                    if process.status != displayedStatus {
                        displayedStatus = process.status
                        displayedAt = appClock.now
                        let progressMessage = gradingMessage(for: process.status)
                        statusMessage = progressMessage
                        answerGradingStatusMessage = progressMessage
                        log(
                            .info,
                            "재개한 채점의 현재 상태를 복원했습니다. recordID=\(process.recordID), status=\(process.status.rawValue)"
                        )
                    }
                    if process.terminal {
                        if process.status == .completed {
                            guard isAnswerGradingSessionCurrent(sessionGeneration),
                                  isAnswerGradingOwnerCurrent(ownerID) else {
                                throw CancellationError()
                            }
                            return try await fetchCompletedAnswerGradingRecord(
                                registration: registration,
                                recordID: process.recordID,
                                cursor: cursor
                            )
                        }
                        throw AnswerGradingProcessError.failed(
                            process.errorMessage ?? strings.gradingFailed
                        )
                    }
                    await sleepForAnswerGradingPoll()
                    continue
                }
                for event in process.events {
                    cursor = max(cursor, event.id)
                    let shouldAdvance = shouldAdvanceGradingStatus(
                        from: displayedStatus,
                        to: event.status
                    )
                    persistAnswerGradingProgress(
                        displayedStatus,
                        questionStatus: event.questionStatus,
                        errorMessage: nil,
                        eventID: event.id,
                        for: queuedRecord
                    )
                    guard shouldAdvance else {
                        continue
                    }
                    try await keepGradingStatusVisible(
                        displayedStatus,
                        since: displayedAt
                    )
                    displayedStatus = event.status
                    displayedAt = appClock.now
                    persistAnswerGradingProgress(
                        event.status,
                        questionStatus: event.questionStatus,
                        errorMessage: event.errorMessage,
                        eventID: event.id,
                        for: queuedRecord
                    )
                    let progressMessage = gradingMessage(for: event.status)
                    statusMessage = progressMessage
                    answerGradingStatusMessage = progressMessage
                    log(
                        .info,
                        "채점 상태를 수신했습니다. recordID=\(event.recordID), status=\(event.status.rawValue), eventID=\(event.id)"
                    )
                    await Task.yield()
                    try await keepGradingStatusVisible(
                        displayedStatus,
                        since: displayedAt
                    )
                    switch event.status {
                    case .completed:
                        guard isAnswerGradingSessionCurrent(sessionGeneration),
                              isAnswerGradingOwnerCurrent(ownerID) else {
                            throw CancellationError()
                        }
                        return try await fetchCompletedAnswerGradingRecord(
                            registration: registration,
                            recordID: queuedRecord.id,
                            cursor: cursor
                        )
                    case .failed:
                        throw AnswerGradingProcessError.failed(
                            event.errorMessage ?? strings.gradingFailed
                        )
                    default:
                        break
                    }
                }
                if !process.terminal,
                   shouldAdvanceGradingStatus(
                       from: displayedStatus,
                       to: process.status
                   ) {
                    try await keepGradingStatusVisible(
                        displayedStatus,
                        since: displayedAt
                    )
                    displayedStatus = process.status
                    displayedAt = appClock.now
                    persistAnswerGradingProgress(
                        process.status,
                        questionStatus: process.questionStatus,
                        errorMessage: process.errorMessage,
                        for: queuedRecord
                    )
                    let progressMessage = gradingMessage(for: process.status)
                    statusMessage = progressMessage
                    answerGradingStatusMessage = progressMessage
                    log(
                        .info,
                        "채점 현재 상태를 응답 스냅샷에서 복원했습니다. recordID=\(process.recordID), status=\(process.status.rawValue)"
                    )
                    await Task.yield()
                    try await keepGradingStatusVisible(
                        displayedStatus,
                        since: displayedAt
                    )
                }
                if process.terminal {
                    try await keepGradingStatusVisible(
                        displayedStatus,
                        since: displayedAt
                    )
                    if process.status == .completed {
                        guard isAnswerGradingSessionCurrent(sessionGeneration),
                              isAnswerGradingOwnerCurrent(ownerID) else {
                            throw CancellationError()
                        }
                        return try await fetchCompletedAnswerGradingRecord(
                            registration: registration,
                            recordID: process.recordID,
                            cursor: cursor
                        )
                    }
                    throw AnswerGradingProcessError.failed(
                        process.errorMessage ?? strings.gradingFailed
                    )
                }
                await sleepForAnswerGradingPoll()
            } catch is CancellationError {
                throw CancellationError()
            } catch let error as AnswerGradingProcessError {
                throw error
            } catch {
                if appErrorHandlingUseCase.isPermanentBackendOperationError(error) {
                    throw error
                }
                consecutiveTransportFailures += 1
                log(
                    .warning,
                    "채점 상태 조회 실패 후 재시도합니다. correlationID=\(correlationID), failureCount=\(consecutiveTransportFailures), error=\(error.localizedDescription)"
                )
                await sleepForAnswerGradingRetry()
            }
        }

        throw CancellationError()
    }

    private func fetchCompletedAnswerGradingRecord(
        registration: RemotePushRegistration,
        recordID: String,
        cursor: Int64
    ) async throws -> StudyRecord {
        var record = try await recordsUseCase.fetchRecord(
            registration: registration,
            recordID: recordID,
            language: settings.appLanguage,
            view: .localized
        )
        if cursor > (record.gradingLastEventID ?? 0) {
            record.gradingLastEventID = cursor
        }
        return record
    }

    private func shouldAdvanceGradingStatus(
        from currentStatus: AnswerGradingStatus,
        to candidateStatus: AnswerGradingStatus
    ) -> Bool {
        guard candidateStatus != currentStatus else {
            return false
        }
        if candidateStatus.isTerminal {
            return true
        }
        return gradingStatusOrder(candidateStatus) > gradingStatusOrder(currentStatus)
    }

    private func gradingStatusOrder(_ status: AnswerGradingStatus) -> Int {
        switch status {
        case .queued:
            0
        case .analyzingEvidence:
            1
        case .critiquing:
            2
        case .judging:
            3
        case .adjudicating:
            4
        case .completed, .failed:
            5
        }
    }

    private func persistAnswerGradingProgress(
        _ status: AnswerGradingStatus,
        questionStatus: QuestionStatus? = nil,
        errorMessage: String?,
        eventID: Int64? = nil,
        for queuedRecord: StudyRecord,
        usesAuthoritativeStatus: Bool = false
    ) {
        let currentRecord = studyRecords.first(where: { $0.id == queuedRecord.id })
            ?? studyRoomState.rooms
                .compactMap(\.pendingQuestion)
                .first(where: { $0.id == queuedRecord.id })
            ?? queuedRecord
        guard currentRecord.gradingResult == nil else {
            return
        }

        var progressedRecord = currentRecord
        if let questionStatus {
            progressedRecord.questionStatus = questionStatus
        } else if progressedRecord.questionStatus == .ungraded {
            progressedRecord.questionStatus = .grading
        }
        if let eventID,
           eventID > (progressedRecord.gradingLastEventID ?? 0) {
            progressedRecord.gradingLastEventID = eventID
        }
        let shouldApplyStatus = status != .completed && (
            usesAuthoritativeStatus ||
                progressedRecord.gradingStatus == nil ||
                progressedRecord.gradingStatus.map {
                    shouldAdvanceGradingStatus(from: $0, to: status)
                } == true
        )
        if shouldApplyStatus {
            progressedRecord.gradingStatus = status
            progressedRecord.gradingError = errorMessage
        }
        guard progressedRecord != currentRecord else {
            return
        }
        localStudyRecordUseCase.replaceRecords(
            mergeBackendRecord(progressedRecord, into: studyRecords)
        )
        reloadStudyRecordsFromStore(refreshRooms: false)
        _ = studyRoomState.applyIncomingRecord(progressedRecord)
    }

    private func persistAcceptedAnswerGrading(_ queuedRecord: StudyRecord) {
        var acceptedRecord = queuedRecord
        if acceptedRecord.gradingStatus == nil {
            acceptedRecord.gradingStatus = .queued
        }
        acceptedRecord.questionStatus = .grading
        if acceptedRecord.studyID == nil {
            acceptedRecord.studyID = studyRecords.first(where: { $0.id == acceptedRecord.id })?.studyID
        }
        if StudyAnswerPresentationPolicy.submittedAnswer(for: acceptedRecord) != nil {
            localStudyRecordUseCase.deleteAnswerDraft(recordID: acceptedRecord.id)
        }
        localStudyRecordUseCase.replaceRecords(
            mergeBackendRecord(acceptedRecord, into: studyRecords)
        )
        reloadStudyRecordsFromStore(refreshRooms: false)
        _ = studyRoomState.applyIncomingRecord(acceptedRecord)
    }

    func cancelAnswerGradingPolling(ownerID: String, reason: String) {
        guard isAnswerGradingOwnerCurrent(ownerID) else {
            return
        }
        cancelAllAnswerGradingPolling(reason: reason)
    }

    private func cancelAllAnswerGradingPolling(reason: String) {
        guard answerGradingPollingTask != nil || answerGradingOwnerID != nil else {
            return
        }
        answerGradingPollingTask?.cancel()
        answerGradingPollingTask = nil
        answerGradingPollingID = nil
        answerGradingOwnerID = nil
        isGradingAnswer = false
        answerGradingStatusMessage = nil
        statusMessage = nil
        log(.info, "화면 또는 로그인 세션 변경으로 채점 상태 조회를 중단했습니다. reason=\(reason)")
    }

    private func isAnswerGradingSessionCurrent(_ generation: UInt64) -> Bool {
        communitySessionState.generation == generation
    }

    private func isAnswerGradingOwnerCurrent(_ ownerID: String) -> Bool {
        answerGradingOwnerID == ownerID
    }

    private func beginAnswerSubmission(for record: StudyRecord) -> Bool {
        guard !answerSubmissionRecordIDs.contains(record.id) else {
            return false
        }
        let authoritativeRecord = studyRecords.first(where: { $0.id == record.id })
            ?? studyRoomState.rooms
                .compactMap(\.pendingQuestion)
                .first(where: { $0.id == record.id })
            ?? record
        guard StudyAnswerPresentationPolicy.state(for: authoritativeRecord).allowsEditing else {
            return false
        }
        answerSubmissionRecordIDs.insert(record.id)
        return true
    }

    private func finishAnswerSubmission(recordID: String) {
        answerSubmissionRecordIDs.remove(recordID)
    }

    private func activateAnswerGrading(ownerID: String) {
        answerGradingPollingTask?.cancel()
        answerGradingPollingTask = nil
        answerGradingPollingID = nil
        answerGradingOwnerID = ownerID
        isGradingAnswer = true
    }

    private func finishAnswerGrading(ownerID: String) {
        guard isAnswerGradingOwnerCurrent(ownerID) else {
            return
        }
        answerGradingPollingTask?.cancel()
        answerGradingPollingTask = nil
        answerGradingPollingID = nil
        answerGradingOwnerID = nil
        isGradingAnswer = false
        answerGradingStatusMessage = nil
    }

    private func sleepForAnswerGradingPoll() async {
        await sleepForAnswerGrading(milliseconds: Self.answerGradingPollIntervalMilliseconds)
    }

    private func sleepForAnswerGrading(milliseconds: Int) async {
        let normalized = max(100, min(milliseconds, 5_000))
        try? await appSleepProvider.sleep(nanoseconds: UInt64(normalized) * 1_000_000)
    }

    private func sleepForAnswerGradingRetry() async {
        await sleepForAnswerGradingPoll()
    }

    private func keepGradingStatusVisible(
        _ status: AnswerGradingStatus,
        since displayedAt: Date
    ) async throws {
        let elapsed = max(0, appClock.now.timeIntervalSince(displayedAt))
        let minimumDuration = minimumGradingStatusDuration(for: status)
        let remaining = minimumDuration - elapsed
        if remaining > 0 {
            try await appSleepProvider.sleep(
                nanoseconds: UInt64(remaining * 1_000_000_000)
            )
        }
    }

    private func minimumGradingStatusDuration(
        for status: AnswerGradingStatus
    ) -> TimeInterval {
        switch status {
        case .queued, .analyzingEvidence, .critiquing, .judging, .adjudicating, .completed, .failed:
            1
        }
    }

    private func gradingMessage(for status: AnswerGradingStatus) -> String {
        switch status {
        case .queued:
            strings.gradingQueued
        case .analyzingEvidence:
            strings.gradingAnalyzing
        case .critiquing:
            strings.gradingCritiquing
        case .judging:
            strings.gradingJudging
        case .adjudicating:
            strings.gradingAdjudicating
        case .completed:
            strings.gradingCompleted
        case .failed:
            strings.gradingFailed
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

        skipPendingQuestion(skippedRecord, shouldOpenNextQuestion: true)
    }

    func skipPendingQuestion(_ record: StudyRecord, shouldOpenNextQuestion: Bool = true) {
        guard record.gradingResult == nil else {
            return
        }

        notificationLandingMessage = nil

        let matchesCurrentQuestion = currentQuestion.map {
            Self.questionsMatch($0, record.question)
        } ?? false
        let isStudyRoomPendingQuestion = studyRoomState.containsPendingQuestion(recordID: record.id)

        if matchesCurrentQuestion {
            notificationService.cancelQuestionNotification(for: record.question)
        }

        if let storedRecord = studyRecord(matching: record.question),
           storedRecord.gradingResult == nil {
            notificationService.cancelQuestionNotification(for: storedRecord.question)
            localStudyRecordUseCase.deleteRecord(storedRecord)
        } else if !matchesCurrentQuestion && !isStudyRoomPendingQuestion {
            return
        }

        localStudyRecordUseCase.deleteAnswerDraft(recordID: record.id)
        reloadStudyRecordsFromStore()
        studyRoomState.clearPendingQuestion(recordID: record.id)

        if matchesCurrentQuestion {
            self.currentQuestion = nil
            lastAnswer = ""
            gradingResult = nil

            guard shouldOpenNextQuestion else {
                currentStudySessionUseCase.saveCurrentQuestionState(
                    question: nil,
                    lastAnswer: "",
                    gradingResult: nil
                )
                statusMessage = "질문을 넘겼습니다."
                errorMessage = nil
                log(.info, "미제출 질문을 넘겼습니다.")
                sendRemoteSkip(for: record)
                markCloudDataChanged(syncDelaySeconds: 0)
                return
            }

            let remainingPendingRecords = studyRecords
                .filter { $0.gradingResult == nil }
                .sorted { $0.question.createdAt > $1.question.createdAt }

            if let nextRecord = remainingPendingRecords.first {
                self.currentQuestion = nextRecord.question
                lastAnswer = nextRecord.answer ?? ""
                gradingResult = nil
                currentStudySessionUseCase.saveCurrentQuestionState(
                    question: nextRecord.question,
                    lastAnswer: nextRecord.answer ?? "",
                    gradingResult: nil
                )
                statusMessage = "질문을 넘기고 다음 미제출 질문을 열었습니다."
            } else {
                currentStudySessionUseCase.saveCurrentQuestionState(
                    question: nil,
                    lastAnswer: "",
                    gradingResult: nil
                )
                statusMessage = "질문을 넘겼습니다."
            }
        } else {
            statusMessage = "질문을 넘겼습니다."
        }

        errorMessage = nil
        log(.info, "미제출 질문을 넘겼습니다.")
        sendRemoteSkip(for: record)
        markCloudDataChanged(syncDelaySeconds: 0)
    }

    private func sendRemoteSkip(for record: StudyRecord) {
        Task {
            guard let storedRegistration = storedBackendIdentityUseCase.loadRegistration(),
                  let registration = await registrationWithAccessToken(
                      storedRegistration,
                      reason: "skip-record"
                  ) else {
                statusMessage = nil
                errorMessage = strings.skipQuestionFailed
                log(.warning, "백엔드 등록 또는 access token이 없어 질문을 넘기지 못했습니다. recordID=\(record.id)")
                await refreshBackendStudyIfPossible(updateVisibleQuestion: false)
                return
            }

            await actionRunner.run(
                operation: {
                    try await performWithBackendIdentityRecovery(
                        registration: registration,
                        reason: "skip-record",
                        operation: { recoveredRegistration in
                            try await recordsUseCase.skipRecord(
                                registration: recoveredRegistration,
                                recordID: record.id
                            )
                        }
                    )
                },
                onSuccess: { _ in
                    await removeNotifications(forRecordID: record.id)
                    await refreshBackendStudyIfPossible(updateVisibleQuestion: false)
                    await syncRemotePushScheduleIfPossible(reason: "skip")
                },
                onFailure: { error in
                    let handled = handlePageAccessError(error, page: .studyDetail)
                    if !handled {
                        statusMessage = nil
                        errorMessage = strings.skipQuestionFailed
                    }
                    log(
                        .warning,
                        "백엔드 미제출 질문 넘기기 실패: recordID=\(record.id), error=\(appErrorHandlingUseCase.diagnosticDescription(for: error))"
                    )
                    await refreshBackendStudyIfPossible(updateVisibleQuestion: false)
                }
            )
        }
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
        pendingAnswerDraft = PendingAnswerDraft(question: currentQuestion, answer: answer)
        answerDraftSaveTask?.cancel()
        let sleepProvider = appSleepProvider
        answerDraftSaveTask = Task { [weak self] in
            do {
                try await sleepProvider.sleep(nanoseconds: 450_000_000)
            } catch {
                return
            }
            await MainActor.run {
                self?.persistPendingAnswerDraft()
            }
        }
    }

    func flushPendingAnswerDraftSave() {
        answerDraftSaveTask?.cancel()
        answerDraftSaveTask = nil
        persistPendingAnswerDraft()
    }

    private func persistPendingAnswerDraft() {
        guard let draft = pendingAnswerDraft else {
            return
        }

        pendingAnswerDraft = nil
        answerDraftSaveTask = nil
        persistAnswerDraft(draft)
    }

    private func persistAnswerDraft(_ draft: PendingAnswerDraft) {
        if let recordID = draft.recordID {
            localStudyRecordUseCase.saveAnswerDraft(draft.answer, recordID: recordID)
            if let question = draft.question,
               let currentQuestion,
               Self.questionsMatch(currentQuestion, question) {
                lastAnswer = draft.answer
                currentStudySessionUseCase.saveLastAnswer(draft.answer)
            }
            return
        }

        guard let question = draft.question else {
            lastAnswer = draft.answer
            currentStudySessionUseCase.saveLastAnswer(draft.answer)
            return
        }

        if let currentQuestion,
           Self.questionsMatch(currentQuestion, question) {
            lastAnswer = draft.answer
            currentStudySessionUseCase.saveLastAnswer(draft.answer)
        }

        markCloudDataChanged(syncDelaySeconds: 4)
    }

    func selectStudyRecord(_ record: StudyRecord) {
        guard requirePageAccess(.studyDetail) else {
            return
        }

        flushPendingAnswerDraftSave()
        notificationLandingMessage = nil
        let answer = answerForCurrentSession(record)
        currentQuestion = record.question
        lastAnswer = answer
        gradingResult = record.gradingResult
        currentStudySessionUseCase.saveQuestion(record.question)
        currentStudySessionUseCase.saveLastAnswer(answer)
        currentStudySessionUseCase.saveGradingResult(record.gradingResult)
        showStudyScreen(categoryID: categoryID(forTopic: record.topic))
        focusedRecordRequest = nil
        statusMessage = record.gradingResult == nil ? "미제출 질문을 열었습니다." : "학습 기록을 열었습니다."
        markCloudDataChanged(syncDelaySeconds: 4)
    }

    @discardableResult
    func notificationRoute(for record: StudyRecord) -> AppRoute {
        if record.gradingResult == nil {
            return .studyRoom(categoryID: categoryID(forTopic: record.topic))
        }

        return .recordDetail(recordID: record.id)
    }

    @discardableResult
    func openNotificationRecord(_ record: StudyRecord) -> Bool {
        if record.gradingResult == nil {
            selectStudyRecord(record)
            return true
        }

        if openRoute(.recordDetail(recordID: record.id)) {
            notificationLandingMessage = nil
            return true
        }

        return false
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
           storedBackendIdentityUseCase.loadRegistration() != nil {
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

    func fetchBackendNotificationRecord(recordID: String, replyText: String? = nil) async throws -> StudyRecord {
        guard let storedRegistration = storedBackendIdentityUseCase.loadRegistration(),
              let registration = await registrationWithAccessToken(storedRegistration, reason: "backend-record-push") else {
            log(.warning, "백엔드 push record를 열 수 없습니다. 기기 등록 정보가 없습니다.")
            throw AppStateError.missingRemotePushRegistration
        }

        var record = try await recordsUseCase.fetchRecord(
            registration: registration,
            recordID: recordID,
            language: settings.appLanguage,
            view: .localized
        )

        let trimmedReply = replyText?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !trimmedReply.isEmpty, record.gradingResult == nil {
            record = try await recordsUseCase.gradeRecord(
                registration: registration,
                recordID: recordID,
                answer: trimmedReply,
                sourceLanguage: ContentLanguageRecognizer.detect(
                    trimmedReply,
                    fallback: settings.appLanguage
                )
            )
        }

        localStudyRecordUseCase.replaceRecords(mergeBackendRecord(record, into: studyRecords))
        reloadStudyRecordsFromStore()
        _ = studyRoomState.applyIncomingRecord(record)

        if currentQuestion.map({ Self.questionsMatch($0, record.question) }) == true {
            lastAnswer = record.answer ?? lastAnswer
            gradingResult = record.gradingResult
            currentStudySessionUseCase.saveLastAnswer(lastAnswer)
            currentStudySessionUseCase.saveGradingResult(gradingResult)
        }

        return record
    }

    @discardableResult
    func openRecordFromNotification(questionCreatedAt: TimeInterval?, replyText: String? = nil) -> Bool {
        reloadStudyRecordsFromStore()

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
            localStudyRecordUseCase.saveSubmittedAnswer(
                question: record.question,
                answer: trimmedReply,
                onlyIfUngraded: false
            )
        }

        reloadStudyRecordsFromStore()
        let refreshedRecord = recordMatching(questionCreatedAt: questionCreatedAt) ??
            studyRecords.first { $0.id == record.id } ??
            record
        _ = openNotificationRecord(refreshedRecord)
        notificationLandingMessage = nil
        statusMessage = trimmedReply.isEmpty
            ? (refreshedRecord.gradingResult == nil ? "알림에서 열린 질문입니다." : "알림에서 기록을 열었습니다.")
            : "알림 답장을 기록에 저장했습니다."
        markCloudDataChanged()
        return true
    }

    @discardableResult
    func handleBackendRecordPush(recordID: String, openStudy: Bool, replyText: String? = nil) async -> Bool {
        do {
            let record = try await fetchBackendNotificationRecord(recordID: recordID, replyText: replyText)
            let trimmedReply = replyText?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

            if openStudy {
                _ = openNotificationRecord(record)
                notificationLandingMessage = nil
                statusMessage = trimmedReply.isEmpty
                    ? (record.gradingResult == nil ? "알림에서 열린 질문입니다." : "알림에서 기록을 열었습니다.")
                    : "알림 답장을 기록에 저장했습니다."
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
           storedBackendIdentityUseCase.loadRegistration() != nil {
            Task {
                await handleBackendRecordPush(
                    recordID: recordID,
                    openStudy: false,
                    replyText: replyText
                )
            }
            return true
        }

        reloadStudyRecordsFromStore()

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

        localStudyRecordUseCase.saveSubmittedAnswer(
            question: record.question,
            answer: trimmedReply,
            onlyIfUngraded: true
        )

        if currentQuestion.map({ Self.questionsMatch($0, record.question) }) == true {
            lastAnswer = trimmedReply
            currentStudySessionUseCase.saveLastAnswer(trimmedReply)
        }

        reloadStudyRecordsFromStore()
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

        localStudyRecordUseCase.saveSubmittedAnswer(
            question: question,
            answer: trimmedReply,
            onlyIfUngraded: true
        )

        if currentQuestion.map({ Self.questionsMatch($0, question) }) == true {
            lastAnswer = trimmedReply
            currentStudySessionUseCase.saveLastAnswer(trimmedReply)
        }

        reloadStudyRecordsFromStore()
        if showStatus {
            statusMessage = "알림 답장을 기록에 저장했습니다."
        }
        log(.info, "CloudKit push 알림 답장을 기록에 저장했습니다.")
        return true
    }

    func showNotificationQuestionUnavailable(preserveCurrentQuestion: Bool) {
        showStudyScreen(categoryID: nil)
        errorMessage = nil

        if !preserveCurrentQuestion || currentQuestion == nil {
            currentQuestion = nil
            lastAnswer = ""
            gradingResult = nil
            currentStudySessionUseCase.saveQuestion(nil)
            currentStudySessionUseCase.saveLastAnswer("")
            currentStudySessionUseCase.saveGradingResult(nil)
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
        let recordsToClear = studyRecords
        let currentQuestionToRestore = currentQuestion
        let lastAnswerToRestore = lastAnswer
        let gradingResultToRestore = gradingResult
        let deletedMarkersToRestore = localStudyRecordUseCase.loadDeletedRecordMarkers()
        let recordsClearedAtToRestore = localStudyRecordUseCase.loadRecordsClearedAt()
        let ownCommunityQuestionIDs: Set<String> = Set(
            communityQuestions.compactMap { question -> String? in
                guard let profileID = communityProfile?.id,
                      question.author?.id == profileID else {
                    return nil
                }
                return question.id
            }
        )
        let clearedRecordIDs = Set(recordsToClear.map(\.id)).union(ownCommunityQuestionIDs)

        notificationService.cancelQuestionNotifications(for: recordsToClear.map(\.question))
        removeCommunityQuestions(ids: clearedRecordIDs)
        localStudyRecordUseCase.clearRecords()
        recordsToClear.forEach { localStudyRecordUseCase.deleteAnswerDraft(recordID: $0.id) }
        recordsState.clear()
        replaceRecordSearchResults(nil)
        currentQuestion = nil
        lastAnswer = ""
        gradingResult = nil
        currentStudySessionUseCase.saveCurrentQuestionState(
            question: nil,
            lastAnswer: "",
            gradingResult: nil
        )
        refreshBackendStudyRoomsFromRecords()
        notificationLandingMessage = nil
        statusMessage = "학습 기록을 삭제했습니다."
        log(.warning, "학습 기록을 모두 삭제했습니다.")

        guard storedBackendIdentityUseCase.loadRegistration() != nil else {
            markCloudDataChanged(syncDelaySeconds: 0)
            return
        }

        runBackendRecordMutation(
            reason: "clear-records",
            operation: { recoveredRegistration in
                try await self.recordsUseCase.clearRecords(registration: recoveredRegistration)
            },
            onSuccess: { _ in
                await self.refreshBackendStudyIfPossible(updateVisibleQuestion: false)
                await self.loadCommunityQuestions(reset: true, userInitiated: false)
                await self.syncRemotePushScheduleIfPossible(reason: "clear-records")
            },
            onFailure: { _ in
                self.localStudyRecordUseCase.saveRecordsClearedAt(recordsClearedAtToRestore)
                self.localStudyRecordUseCase.saveDeletedRecordMarkers(deletedMarkersToRestore)
                self.localStudyRecordUseCase.replaceRecords(recordsToClear)
                self.reloadStudyRecordsFromStore()
                self.currentQuestion = currentQuestionToRestore
                self.lastAnswer = lastAnswerToRestore
                self.gradingResult = gradingResultToRestore
                self.currentStudySessionUseCase.saveCurrentQuestionState(
                    question: currentQuestionToRestore,
                    lastAnswer: lastAnswerToRestore,
                    gradingResult: gradingResultToRestore
                )
                self.restoreCommunityQuestions(ids: clearedRecordIDs)
                await self.refreshBackendRecords()
                await self.refreshBackendStudyIfPossible(updateVisibleQuestion: false)
                await self.loadCommunityQuestions(reset: true, userInitiated: false)
            },
            failureMessage: { "백엔드 학습 기록 전체삭제 실패: \($0.localizedDescription)" }
        )
        markCloudDataChanged(syncDelaySeconds: 0)
    }

    func deleteStudyRecord(_ record: StudyRecord) {
        notificationService.cancelQuestionNotification(for: record.question)
        var nextRecordsState = recordsState
        nextRecordsState.removeLoadedBackendRecord(record)
        recordsState = nextRecordsState
        var nextSearchState = searchState
        nextSearchState.removeRecordResult(id: record.id)
        searchState = nextSearchState
        localStudyRecordUseCase.deleteRecord(record)
        reloadStudyRecordsFromStore()
        removeCommunityQuestion(id: record.id)
        notificationLandingMessage = nil

        if StudyRecordIdentityPolicy.questionsMatch(currentQuestion?.question ?? "", record.question.question) {
            currentQuestion = nil
            gradingResult = nil
            lastAnswer = ""
            currentStudySessionUseCase.saveQuestion(nil)
            currentStudySessionUseCase.saveGradingResult(nil)
            currentStudySessionUseCase.saveLastAnswer("")
        }

        statusMessage = "기록을 삭제했습니다."
        log(.info, "학습 기록을 1개 삭제했습니다.")
        runBackendRecordMutation(
            reason: "delete-record",
            operation: { recoveredRegistration in
                try await self.recordsUseCase.deleteRecord(registration: recoveredRegistration, recordID: record.id)
            },
            onSuccess: { _ in
                await self.refreshBackendStudyIfPossible(updateVisibleQuestion: false)
                await self.loadCommunityQuestions(reset: true, userInitiated: false)
                await self.syncRemotePushScheduleIfPossible(reason: "delete-record")
            },
            onFailure: { _ in
                self.localStudyRecordUseCase.saveRecord(record)
                self.reloadStudyRecordsFromStore()
                self.restoreCommunityQuestion(id: record.id)
                await self.refreshBackendRecords()
                let recordQuery = self.searchState.recordQuery
                if !recordQuery.isEmpty {
                    await self.searchBackendRecords(query: recordQuery)
                }
                await self.loadCommunityQuestions(reset: true, userInitiated: false)
            },
            failureMessage: { "백엔드 학습 기록 삭제 실패: \($0.localizedDescription)" }
        )
        markCloudDataChanged(syncDelaySeconds: 0)
    }

    func updateStudyRecordPublicity(_ record: StudyRecord, isPublic: Bool) {
        var updatedRecord = record
        updatedRecord.isPublic = isPublic
        localStudyRecordUseCase.saveRecord(updatedRecord)
        reloadStudyRecordsFromStore()
        if isPublic {
            restoreCommunityQuestion(id: record.id)
        } else {
            removeCommunityQuestion(id: record.id)
        }
        markCloudDataChanged()

        runBackendRecordMutation(
            reason: "record-publicity",
            operation: { recoveredRegistration in
                try await self.recordsUseCase.updateRecordPublicity(
                    registration: recoveredRegistration,
                    recordID: record.id,
                    isPublic: isPublic
                )
            },
            onSuccess: { backendRecord in
                self.localStudyRecordUseCase.saveRecord(backendRecord)
                self.reloadStudyRecordsFromStore()
                if backendRecord.isPublic {
                    self.restoreCommunityQuestion(id: backendRecord.id)
                } else {
                    self.removeCommunityQuestion(id: backendRecord.id)
                }
                await self.loadCommunityQuestions(reset: true, userInitiated: false)
            },
            onFailure: { _ in
                self.localStudyRecordUseCase.saveRecord(record)
                self.reloadStudyRecordsFromStore()
                if record.isPublic {
                    self.restoreCommunityQuestion(id: record.id)
                } else {
                    self.removeCommunityQuestion(id: record.id)
                }
                await self.loadCommunityQuestions(reset: true, userInitiated: false)
            },
            failureMessage: { "기록 공개 상태 변경 실패: \($0.localizedDescription)" }
        )
    }

    private func runBackendRecordMutation<Value>(
        reason: String,
        operation: @escaping (RemotePushRegistration) async throws -> Value,
        onSuccess: @escaping (Value) async -> Void = { _ in },
        onFailure: @escaping (Error) async -> Void = { _ in },
        failureMessage: @escaping (Error) -> String
    ) {
        guard let registration = storedBackendIdentityUseCase.loadRegistration() else {
            return
        }

        Task { [weak self] in
            guard let self,
                  let tokenRegistration = await registrationWithAccessToken(registration, reason: reason) else {
                return
            }

            await actionRunner.run(
                operation: {
                    try await performWithBackendIdentityRecovery(
                        registration: tokenRegistration,
                        reason: reason,
                        operation: operation
                    )
                },
                onSuccess: onSuccess,
                onFailure: { error in
                    await onFailure(error)
                    handleAppError(error, fallback: "", target: .none)
                    log(.warning, failureMessage(error))
                }
            )
        }
    }

    func clearAppLogs() {
        appLogUseCase.clearLogs()
        var nextState = developerState
        nextState.clearAppLogs()
        developerState = nextState
    }

    func appendAPITrafficLog(_ entry: APITrafficLogEntry) {
        var nextState = developerState
        nextState.appendAPITrafficLog(entry, limit: Self.maxAPITrafficLogs)
        developerState = nextState
    }

    func clearAPITrafficLogs() {
        var nextState = developerState
        nextState.clearAPITrafficLogs()
        developerState = nextState
    }

    func resetDebugLogs() {
        appLogUseCase.clearLogs()
        var nextState = developerState
        nextState.clearAppLogs()
        nextState.clearAPITrafficLogs()
        developerState = nextState
    }

    func showAPIDebugPanel() {
        guard canShowDebugPopup else {
            return
        }
        isAPIDebugPanelPresented = true
    }

    func requestDebugPanelIfEnabledOrEnableOnDemand() {
        guard canShowDebugPopup else {
            return
        }
        loadAppLogPage(0)
        isAPIDebugPanelPresented = true
        log(.info, "APP/API 디버그 패널을 열었습니다.")
    }

    func logRemoteNotificationEvent(_ message: String, isWarning: Bool = false) {
        log(isWarning ? .warning : .info, message)
    }

    func loadAppLogPage(_ page: Int) {
        let logPage = appLogUseCase.loadLogs(page: page, pageSize: Self.developerLogPageSize)
        var nextState = developerState
        nextState.applyLogPage(logPage)
        developerState = nextState
    }

    func loadPreviousAppLogPage() {
        loadAppLogPage(appLogPage - 1)
    }

    func loadNextAppLogPage() {
        loadAppLogPage(appLogPage + 1)
    }

    func setDebuggingEnabled(_ isEnabled: Bool) {
        guard !isEnabled || canAccessDeveloperOptions else {
            return
        }
        isDebuggingEnabled = isEnabled
        developerSettingsUseCase.saveIsDebuggingEnabled(isEnabled)
        refreshRemotePushBackendClient(reason: isEnabled ? "debug-enabled" : "debug-disabled")
        log(.info, isEnabled ? "디버깅 모드를 켰습니다." : "디버깅 모드를 껐습니다.")
        Task {
            await refreshBackendSettingsFromServer(reason: "backend-environment-change")
        }
    }

    func saveTermsAgreement(
        type: BackendTermsType,
        isAgreed: Bool,
        source: BackendTermsAgreementSource = .settings
    ) async -> Bool {
        updateLocalTermsAgreement(type: type, isAgreed: isAgreed)

        guard await persistTermsAgreement(type: type, isAgreed: isAgreed, source: source, shouldShowError: true) else {
            return false
        }

        await refreshPermissionEvaluations(reason: "terms-agreement")
        await refreshTermsAndNotificationPreferences(reason: "terms-agreement")
        let hasAcceptedAllRequiredTerms = [BackendTermsType.termsOfService, .privacyPolicy].allSatisfy { type in
            activeTerms.first(where: { $0.type == type })?.agreed == true
        }
        if hasAcceptedAllRequiredTerms {
            isRequiredTermsGatePresented = false
            if isAgreed, let retry = pendingTermsRequirementRetry {
                pendingTermsRequirementRetry = nil
                await retry()
            }
        }
        return true
    }

    func saveTermsAgreementInBackground(
        type: BackendTermsType,
        isAgreed: Bool,
        source: BackendTermsAgreementSource = .settings
    ) {
        updateLocalTermsAgreement(type: type, isAgreed: isAgreed)

        Task { [weak self] in
            guard let self else {
                return
            }
            guard await self.persistTermsAgreement(type: type, isAgreed: isAgreed, source: source, shouldShowError: false) else {
                await self.refreshTermsAndNotificationPreferences(reason: "terms-agreement-background-reconcile")
                return
            }
            await self.refreshPermissionEvaluations(reason: "terms-agreement-background")
            let hasAcceptedAllRequiredTerms = [BackendTermsType.termsOfService, .privacyPolicy].allSatisfy { type in
                self.activeTerms.first(where: { $0.type == type })?.agreed == true
            }
            if hasAcceptedAllRequiredTerms {
                self.isRequiredTermsGatePresented = false
                if isAgreed, let retry = self.pendingTermsRequirementRetry {
                    self.pendingTermsRequirementRetry = nil
                    await retry()
                }
            }
        }
    }

    private func persistTermsAgreement(
        type: BackendTermsType,
        isAgreed: Bool,
        source: BackendTermsAgreementSource,
        shouldShowError: Bool
    ) async -> Bool {
        guard source != .profile || isCommunitySessionActive else {
            log(.warning, "프로필 약관 동의는 로그인 후 저장할 수 있습니다. type=\(type.rawValue)")
            return false
        }

        guard let registration = await backendRegistrationForOpenAIRequests(reason: "terms-agreement") else {
            log(.warning, "약관 동의 저장을 위한 백엔드 등록이 없습니다. type=\(type.rawValue)")
            return false
        }

        do {
            _ = try await performWithBackendIdentityRecovery(
                registration: registration,
                reason: "terms-agreement",
                operation: { recoveredRegistration in
                    try await self.termsUseCase.saveAgreement(
                        registration: recoveredRegistration,
                        type: type,
                        action: isAgreed ? .agreed : .withdrawn,
                        source: source
                    )
                }
            )
            log(.info, "약관 동의 상태를 저장했습니다. type=\(type.rawValue), agreed=\(isAgreed)")
            return true
        } catch {
            if shouldShowError {
                handleAppError(error, fallback: "", target: .none)
            }
            log(.warning, "약관 동의 상태 저장 실패: type=\(type.rawValue), error=\(error.localizedDescription)")
            return false
        }
    }

    private func updateLocalTermsAgreement(type: BackendTermsType, isAgreed: Bool) {
        activeTerms = activeTerms.map { term in
            guard term.type == type else {
                return term
            }
            var nextTerm = term
            nextTerm.agreed = term.required ? true : isAgreed
            return nextTerm
        }
    }

    func refreshTermsAndNotificationPreferences(reason: String) async {
        let sessionGeneration = communitySessionState.generation
        guard isCommunitySessionActive else {
            activeTerms = []
            notificationPreferences = []
            return
        }

        guard let registration = await backendRegistrationForOpenAIRequests(reason: "terms-preferences-\(reason)") else {
            log(.warning, "약관/알림 설정 조회를 위한 백엔드 등록이 없습니다. reason=\(reason)")
            return
        }
        guard isCurrentCommunitySession(sessionGeneration) else {
            log(.info, "로그아웃으로 약관/알림 설정 조회를 중단했습니다. stage=registration, reason=\(reason)")
            return
        }

        isLoadingTermsAndPreferences = true
        defer {
            if isCurrentCommunitySession(sessionGeneration) {
                isLoadingTermsAndPreferences = false
            }
        }

        do {
            let terms = try await termsUseCase.fetchActiveTerms(registration: registration)
            guard isCurrentCommunitySession(sessionGeneration) else {
                log(.info, "로그아웃으로 약관/알림 설정 조회를 중단했습니다. stage=terms, reason=\(reason)")
                return
            }
            let preferences = try await termsUseCase.fetchNotificationPreferences(registration: registration)
            guard isCurrentCommunitySession(sessionGeneration) else {
                log(.info, "로그아웃으로 약관/알림 설정 응답 반영을 건너뛰었습니다. stage=preferences, reason=\(reason)")
                return
            }
            activeTerms = terms
            notificationPreferences = preferences
            log(.info, "약관/알림 설정을 갱신했습니다. terms=\(terms.count), preferences=\(preferences.count), reason=\(reason)")
        } catch {
            guard isCurrentCommunitySession(sessionGeneration) else {
                log(.info, "로그아웃 후 약관/알림 설정 오류 처리를 건너뛰었습니다. reason=\(reason)")
                return
            }
            handleAppError(error, fallback: "", target: .none)
            log(.warning, "약관/알림 설정 갱신 실패: \(error.localizedDescription), reason=\(reason)")
        }
    }

    private func isCurrentCommunitySession(_ generation: UInt64) -> Bool {
        communitySessionState.isCurrent(generation)
    }

    func saveNotificationPreference(type: BackendNotificationPreferenceType, enabled: Bool) async -> Bool {
        updateLocalNotificationPreference(type: type, enabled: enabled)

        guard isCommunitySessionActive else {
            log(.warning, "알림 설정은 로그인 후 저장할 수 있습니다. type=\(type.rawValue)")
            return false
        }

        guard let registration = await backendRegistrationForOpenAIRequests(reason: "notification-preference") else {
            log(.warning, "알림 설정 저장을 위한 백엔드 등록이 없습니다. type=\(type.rawValue)")
            return false
        }

        do {
            let preference = try await termsUseCase.saveNotificationPreference(
                registration: registration,
                type: type,
                enabled: enabled
            )
            updateLocalNotificationPreference(type: preference.type, enabled: preference.enabled)
            log(.info, "알림 설정을 저장했습니다. type=\(type.rawValue), enabled=\(enabled)")
            return true
        } catch {
            handleAppError(error, fallback: "", target: .none)
            log(.warning, "알림 설정 저장 실패: type=\(type.rawValue), error=\(error.localizedDescription)")
            return false
        }
    }

    func saveNotificationPreferenceInBackground(type: BackendNotificationPreferenceType, enabled: Bool) {
        updateLocalNotificationPreference(type: type, enabled: enabled)

        Task { [weak self] in
            guard let self else {
                return
            }
            guard self.isCommunitySessionActive else {
                self.log(.warning, "알림 설정은 로그인 후 저장할 수 있습니다. type=\(type.rawValue)")
                return
            }
            guard let registration = await self.backendRegistrationForOpenAIRequests(reason: "notification-preference-background") else {
                self.log(.warning, "알림 설정 저장을 위한 백엔드 등록이 없습니다. type=\(type.rawValue)")
                return
            }
            do {
                let preference = try await self.termsUseCase.saveNotificationPreference(
                    registration: registration,
                    type: type,
                    enabled: enabled
                )
                self.updateLocalNotificationPreference(type: preference.type, enabled: preference.enabled)
                self.log(.info, "알림 설정을 백그라운드 저장했습니다. type=\(type.rawValue), enabled=\(enabled)")
            } catch {
                self.log(.warning, "알림 설정 백그라운드 저장 실패: type=\(type.rawValue), error=\(error.localizedDescription)")
                await self.refreshTermsAndNotificationPreferences(reason: "notification-preference-background-reconcile")
            }
        }
    }

    private func updateLocalNotificationPreference(type: BackendNotificationPreferenceType, enabled: Bool) {
        var nextPreferences = notificationPreferences
        if let index = nextPreferences.firstIndex(where: { $0.type == type }) {
            nextPreferences[index].enabled = enabled
        } else {
            nextPreferences.append(BackendNotificationPreference(type: type, key: type.key, enabled: enabled))
        }
        notificationPreferences = nextPreferences
    }

    func setCloudSyncEnabled(_ isEnabled: Bool) {
        isCloudSyncEnabled = isEnabled
        cloudSyncStateUseCase.saveIsEnabled(isEnabled)
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

        guard cloudSyncService != nil || cloudSyncProvider.canUseCloudSync() else {
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
            let storedLocalUpdatedAt = cloudSyncStateUseCase.loadState().stateUpdatedAt
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
                        cloudSyncStateUseCase.saveStateUpdatedAt(firstSync.state.updatedAt)
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
                        mergedRemoteState.updatedAt = max(appClock.now, remoteState.updatedAt, localUpdatedAt)
                        try await cloudSyncService.saveState(mergedRemoteState)
                        applyCloudState(mergedRemoteState, updateVisibleQuestion: updateVisibleQuestion)
                        cloudSyncStateUseCase.saveStateUpdatedAt(mergedRemoteState.updatedAt)
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
                        state.updatedAt = max(localUpdatedAt, remoteState.updatedAt, appClock.now)
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
                let updatedAt = max(localUpdatedAt, appClock.now)
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
            cloudSyncStateUseCase.saveIsEnabled(isCloudSyncEnabled)
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
        do {
            try platformEffectsProvider.uninstallApplication()
            log(.warning, "앱 제거를 실행했습니다.")
        } catch {
            errorMessage = strings.uninstallFailed(error.localizedDescription)
            log(.error, "앱 제거 실패: \(error.localizedDescription)")
        }
    }

    private func restartTimer() {
        timerTask?.cancel()
        #if os(iOS)
        timerTask = nil
        return
        #else
        guard hasCompletedOnboarding, isRunning else {
            return
        }

        let sleepProvider = appSleepProvider
        timerTask = Task { [weak self] in
            while !Task.isCancelled {
                let seconds = self?.timerPollIntervalSeconds() ?? 60
                try? await sleepProvider.sleep(nanoseconds: seconds * 1_000_000_000)

                guard !Task.isCancelled else {
                    return
                }

                await self?.handleScheduledQuestionTick()
            }
        }
        #endif
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

    private func backendRegistrationForOpenAIRequests(
        reason: String,
        syncSettingsAfterRegistration: Bool = true
    ) async -> RemotePushRegistration? {
        if let registration = storedBackendIdentityUseCase.loadRegistration() {
            return await registrationWithAccessToken(
                registration,
                reason: reason,
                syncSettingsAfterRegistration: syncSettingsAfterRegistration
            )
        }

        do {
            return try await registerFreshBackendDevice(
                apnsToken: nil,
                reason: reason,
                includeAPIKey: true,
                syncSettingsAfterRegistration: syncSettingsAfterRegistration
            )
        } catch {
            log(.warning, "OpenAI 요청용 백엔드 기기 등록 실패: \(error.localizedDescription)")
            return nil
        }
    }

    @discardableResult
    private func createBackendStudyIfPossible(
        _ category: StudyCategory,
        settings: StudySettings
    ) async -> Bool {
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "create-study") else {
            log(.warning, "백엔드 등록이 없어 학습 추가 동기화를 건너뛰었습니다. topic=\(category.normalizedTitle)")
            return false
        }

        do {
            let room = try await studyRoomUseCase.createStudy(
                registration: registration,
                category: category,
                settings: settings
            )
            AppAnalytics.studyCreated(kind: .root)
            log(.info, "백엔드 학습을 추가했습니다. id=\(room.id), topic=\(room.topic)")
            await refreshBackendStudyIfPossible(updateVisibleQuestion: false)
            return true
        } catch {
            if handleAppError(
                error,
                fallback: "",
                target: .none,
                protectedPage: .myStudies,
                termsRetry: { [weak self] in
                    _ = await self?.createBackendStudyIfPossible(
                        category,
                        settings: settings
                    )
                }
            ) {
                return false
            }
            log(.warning, "백엔드 학습 추가 실패: \(error.localizedDescription)")
            return false
        }
    }

    @discardableResult
    private func createBackendStudyTopicIfPossible(
        topic: String,
        difficulty: Difficulty,
        parentStudyID: Int,
        sortOrder: Int,
        activeForQuestions: Bool,
        registration providedRegistration: RemotePushRegistration? = nil,
        refreshAfterCreation: Bool = true
    ) async -> Bool {
        let registration: RemotePushRegistration
        if let providedRegistration {
            registration = providedRegistration
        } else if let resolvedRegistration = await backendRegistrationForOpenAIRequests(
            reason: "create-study-topic"
        ) {
            registration = resolvedRegistration
        } else {
            log(.warning, "백엔드 등록이 없어 하위 주제 추가를 건너뛰었습니다. topic=\(topic)")
            return false
        }

        do {
            let room = try await studyRoomUseCase.createStudyTopic(
                registration: registration,
                parentStudyID: parentStudyID,
                topic: topic,
                difficulty: difficulty,
                sortOrder: sortOrder,
                activeForQuestions: activeForQuestions
            )
            AppAnalytics.studyCreated(kind: .topic)
            log(
                .info,
                "백엔드 하위 주제를 추가했습니다. id=\(room.id), parentStudyId=\(parentStudyID), topic=\(room.topic)"
            )
            if refreshAfterCreation {
                await refreshBackendStudyIfPossible(updateVisibleQuestion: false)
            }
            return true
        } catch {
            if handleAppError(
                error,
                fallback: "",
                target: .none,
                protectedPage: .myStudies,
                termsRetry: { [weak self] in
                    _ = await self?.createBackendStudyTopicIfPossible(
                        topic: topic,
                        difficulty: difficulty,
                        parentStudyID: parentStudyID,
                        sortOrder: sortOrder,
                        activeForQuestions: activeForQuestions,
                        refreshAfterCreation: true
                    )
                }
            ) {
                return false
            }
            log(.warning, "백엔드 하위 주제 추가 실패: \(error.localizedDescription)")
            return false
        }
    }

    private func updateBackendStudyIfPossible(
        _ category: StudyCategory,
        studyID: Int,
        settings: StudySettings
    ) async {
        guard let registration = await backendRegistrationForOpenAIRequests(reason: "update-study") else {
            log(.warning, "백엔드 등록이 없어 학습 편집 동기화를 건너뛰었습니다. id=\(studyID)")
            return
        }

        do {
            try await studyRoomUseCase.updateStudy(
                registration: registration,
                studyID: studyID,
                category: category,
                settings: settings
            )
            log(.info, "백엔드 학습을 수정했습니다. id=\(studyID), topic=\(category.normalizedTitle)")
            await refreshBackendStudyIfPossible(updateVisibleQuestion: false)
        } catch {
            if handleAppError(
                error,
                fallback: "",
                target: .none,
                protectedPage: .myStudies,
                termsRetry: { [weak self] in
                    await self?.updateBackendStudyIfPossible(
                        category,
                        studyID: studyID,
                        settings: settings
                    )
                }
            ) {
                return
            }
            log(.warning, "백엔드 학습 수정 실패: id=\(studyID), error=\(error.localizedDescription)")
            await refreshBackendStudyIfPossible(updateVisibleQuestion: false)
        }
    }

    private func registrationWithAccessToken(
        _ registration: RemotePushRegistration,
        reason: String,
        syncSettingsAfterRegistration: Bool = true
    ) async -> RemotePushRegistration? {
        guard !registration.hasAccessToken else {
            logAuthTrace("backend_access_token_reuse", reason: reason)
            return registration
        }

        do {
            logAuthTrace("backend_access_token_bootstrap_start", reason: reason, deduplicate: false)
            let updatedRegistration = try await backendIdentityUseCase.bootstrapAccessToken(registration: registration)
            storedBackendIdentityUseCase.saveRegistration(updatedRegistration)
            logAuthTrace("backend_access_token_bootstrap_success", reason: reason, deduplicate: false)
            log(.info, "백엔드 access token을 갱신했습니다. reason=\(reason), deviceID=\(updatedRegistration.deviceID)")
            return updatedRegistration
        } catch {
            if appErrorHandlingUseCase.shouldResetBackendIdentity(after: error) {
                logAuthTrace(
                    "backend_access_token_bootstrap_reset_identity",
                    reason: reason,
                    extra: ["error=\(error.localizedDescription)"],
                    deduplicate: false
                )
                log(.warning, "저장된 백엔드 identity가 유효하지 않아 새 기기를 등록합니다. reason=\(reason), deviceID=\(registration.deviceID), error=\(error.localizedDescription)")
                return await resetBackendIdentityAndRegisterFresh(
                    previousRegistration: registration,
                    reason: "\(reason)-device-recovery",
                    syncSettingsAfterRegistration: syncSettingsAfterRegistration
                )
            }
            logAuthTrace(
                "backend_access_token_bootstrap_failure",
                reason: reason,
                extra: ["error=\(error.localizedDescription)"],
                deduplicate: false
            )
            log(.warning, "백엔드 access token 갱신 실패: \(error.localizedDescription)")
            return nil
        }
    }

    private func performWithBackendIdentityRecovery<T>(
        registration: RemotePushRegistration,
        reason: String,
        syncSettingsAfterRegistration: Bool = true,
        operation: (RemotePushRegistration) async throws -> T
    ) async throws -> T {
        do {
            return try await operation(registration)
        } catch {
            if appErrorHandlingUseCase.shouldRefreshBackendAccessToken(after: error) {
                logAuthTrace(
                    "backend_access_token_recovery_start",
                    reason: reason,
                    extra: ["error=\(error.localizedDescription)"],
                    deduplicate: false
                )
                var expiredRegistration = registration
                expiredRegistration.accessToken = nil
                expiredRegistration.accessTokenExpiresAt = nil
                storedBackendIdentityUseCase.saveRegistration(expiredRegistration)
                guard let refreshedRegistration = await registrationWithAccessToken(
                    expiredRegistration,
                    reason: "\(reason)-access-token-recovery",
                    syncSettingsAfterRegistration: syncSettingsAfterRegistration
                ) else {
                    logAuthTrace("backend_access_token_recovery_failure", reason: reason, deduplicate: false)
                    throw error
                }
                logAuthTrace("backend_access_token_recovery_success", reason: reason, deduplicate: false)
                return try await operation(refreshedRegistration)
            }

            guard appErrorHandlingUseCase.shouldResetBackendIdentity(after: error) else {
                throw error
            }
            logAuthTrace(
                "backend_identity_recovery_start",
                reason: reason,
                extra: ["error=\(error.localizedDescription)"],
                deduplicate: false
            )
            guard let recoveredRegistration = await resetBackendIdentityAndRegisterFresh(
                previousRegistration: registration,
                reason: reason,
                syncSettingsAfterRegistration: syncSettingsAfterRegistration
            ) else {
                logAuthTrace("backend_identity_recovery_failure", reason: reason, deduplicate: false)
                throw error
            }

            logAuthTrace("backend_identity_recovery_success", reason: reason, deduplicate: false)
            return try await operation(recoveredRegistration)
        }
    }

    private func resetBackendIdentityAndRegisterFresh(
        previousRegistration: RemotePushRegistration,
        reason: String,
        syncSettingsAfterRegistration: Bool = true
    ) async -> RemotePushRegistration? {
        storedBackendIdentityUseCase.saveRegistration(nil)
        resetCommunitySignInState()
        clearCommunityFeedPage()
        let apnsToken = previousRegistration.apnsToken.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            let registration = try await registerFreshBackendDevice(
                apnsToken: apnsToken.isEmpty ? nil : apnsToken,
                reason: "\(reason)-identity-reset",
                includeAPIKey: true,
                syncSettingsAfterRegistration: syncSettingsAfterRegistration
            )
            log(.warning, "백엔드 device/token이 무효화되어 새 기기로 복구했습니다. reason=\(reason), oldDeviceID=\(previousRegistration.deviceID), newDeviceID=\(registration.deviceID)")
            return registration
        } catch {
            log(.warning, "백엔드 device/token 복구 실패: \(error.localizedDescription), reason=\(reason), oldDeviceID=\(previousRegistration.deviceID)")
            return nil
        }
    }

    private func registerFreshBackendDevice(
        apnsToken: String?,
        reason: String,
        includeAPIKey: Bool,
        syncSettingsAfterRegistration: Bool = true
    ) async throws -> RemotePushRegistration {
        let registration = try await backendIdentityUseCase.registerDevice(
            installationIdentifier: storedBackendIdentityUseCase.installationIdentifier(),
            apnsToken: apnsToken,
            language: settings.appLanguage,
            timezone: appTimeZoneProvider.currentIdentifier,
            apnsEnvironment: Self.backendAPNSEnvironment
        )
        storedBackendIdentityUseCase.saveRegistration(registration)
        log(.info, "새 백엔드 기기를 등록했습니다. reason=\(reason), deviceID=\(registration.deviceID)")
        if syncSettingsAfterRegistration {
            try await updateBackendSettings(
                registration: registration,
                reason: reason,
                includeAPIKey: includeAPIKey
            )
        }
        return registration
    }

    private func clearStoredBackendAccessToken() {
        logAuthTrace("backend_access_token_clear_start", reason: "clearStoredBackendAccessToken", deduplicate: false)
        cancelAllAnswerGradingPolling(reason: "backend-access-token-cleared")
        guard var registration = storedBackendIdentityUseCase.loadRegistration(),
              registration.accessToken != nil || registration.accessTokenExpiresAt != nil else {
            logAuthTrace("backend_access_token_clear_skipped", reason: "clearStoredBackendAccessToken", deduplicate: false)
            return
        }

        registration.accessToken = nil
        registration.accessTokenExpiresAt = nil
        storedBackendIdentityUseCase.saveRegistration(registration)
        backendAccessState = .signedOut
        setCommunitySessionSignedIn(false)
        communityProfile = nil
        logAuthTrace("backend_access_token_clear_end", reason: "clearStoredBackendAccessToken", deduplicate: false)
        log(.warning, "백엔드 401 응답으로 저장된 access token을 삭제했습니다. deviceID=\(registration.deviceID)")
    }

    private func updateBackendSettings(
        registration: RemotePushRegistration,
        reason: String,
        includeAPIKey: Bool = false
    ) async throws {
        let trimmedAPIKey = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        #if os(iOS)
        let shouldEnableRemotePush = QuestionSchedulePolicy.shouldEnableIOSRemotePush(
            apnsToken: registration.apnsToken
        )
        #else
        let shouldEnableRemotePush = QuestionSchedulePolicy.shouldEnableRemotePush(
            isRunning: isRunning,
            apnsToken: registration.apnsToken
        )
        #endif
        let shouldUploadAPIKey = !trimmedAPIKey.isEmpty && (includeAPIKey || !isBackendOpenAIKeyConfigured)
        let sessionSettings = isCommunitySessionActive ? settings : settings.withQuestionPrivacy(false)
        let backendSettings = settingsForRootScheduleSync(sessionSettings)
        try await settingsUseCase.updateSchedule(
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

    private func settingsForRootScheduleSync(_ source: StudySettings) -> StudySettings {
        let rootCategories = StudyRoomDisplayPolicy.rootCategories(
            from: source.studyCategories,
            rooms: backendStudyRooms
        )
        let selectedRootID = source.selectedStudyCategoryID.flatMap { selectedID in
            rootCategories.contains(where: { $0.id == selectedID }) ? selectedID : nil
        } ?? rootCategories.first?.id
        return StudySettings(
            topic: rootCategories.first?.normalizedTitle ?? source.topic,
            difficulty: rootCategories.first?.difficulty ?? source.difficulty,
            appLanguage: source.appLanguage,
            language: source.appLanguage.studyLanguage,
            openAIModel: rootCategories.first?.sanitizedOpenAIModel ?? source.sanitizedOpenAIModel,
            notificationSound: source.notificationSound,
            customPrompt: rootCategories.first?.normalizedCustomPrompt ?? source.customPrompt,
            intervalMinutes: source.sanitizedIntervalMinutes,
            maxHistoryCount: source.sanitizedMaxHistoryCount,
            isQuestionPublic: source.isQuestionPublic,
            studyCategories: rootCategories,
            selectedStudyCategoryID: selectedRootID
        )
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
            let existingRegistration = storedBackendIdentityUseCase.loadRegistration()

            if let existingRegistration,
               existingRegistration.apnsToken == token {
                // APNs invokes this callback on every launch even when nothing changed.
                // Startup/login already refresh settings and studies, so doing it here again
                // caused a second full study-list request.
                return
            } else if let existingRegistration {
                guard let tokenRegistration = await registrationWithAccessToken(existingRegistration, reason: "device-token-update") else {
                    return
                }
                let registration = try await performWithBackendIdentityRecovery(
                    registration: tokenRegistration,
                    reason: "device-token-update",
                    operation: { recoveredRegistration in
                        try await backendIdentityUseCase.updatePushToken(
                            registration: recoveredRegistration,
                            apnsToken: token,
                            apnsEnvironment: Self.backendAPNSEnvironment
                        )
                    }
                )
                storedBackendIdentityUseCase.saveRegistration(registration)
                log(.info, "서버 push 백엔드의 iPhone APNs 토큰을 갱신했습니다.")
                return
            } else {
                let registration = try await backendIdentityUseCase.registerDevice(
                    installationIdentifier: storedBackendIdentityUseCase.installationIdentifier(),
                    apnsToken: token,
                    language: settings.appLanguage,
                    timezone: appTimeZoneProvider.currentIdentifier,
                    apnsEnvironment: Self.backendAPNSEnvironment
                )
                storedBackendIdentityUseCase.saveRegistration(registration)
                log(.info, "서버 push 백엔드에 iPhone 기기를 등록했습니다.")
                try await performWithBackendIdentityRecovery(
                    registration: registration,
                    reason: "device-token",
                    operation: { recoveredRegistration in
                        try await updateBackendSettings(
                            registration: recoveredRegistration,
                            reason: "device-token"
                        )
                    }
                )
                await refreshBackendStudyIfPossible(updateVisibleQuestion: false)
            }
        } catch {
            log(.warning, "서버 push 백엔드 등록 실패: \(error.localizedDescription)")
        }
    }

    private func syncRemotePushScheduleIfPossible(reason: String) async {
        guard let storedRegistration = storedBackendIdentityUseCase.loadRegistration(),
              let registration = await registrationWithAccessToken(storedRegistration, reason: reason) else {
            return
        }

        do {
            try await performWithBackendIdentityRecovery(
                registration: registration,
                reason: reason,
                operation: { recoveredRegistration in
                    try await updateBackendSettings(
                        registration: recoveredRegistration,
                        reason: reason
                    )
                }
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
        if localStudyRecordUseCase.loadDeletedRecordMarkers().contains(where: { $0.matches(pushRecord) }) {
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

        localStudyRecordUseCase.appendQuestionToHistory(push.question)
        localStudyRecordUseCase.appendRecord(question: push.question, settings: pushSettings)
        reloadStudyRecordsFromStore()
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

        let updatedAt = appClock.now
        cloudSyncStateUseCase.saveStateUpdatedAt(updatedAt)
        cloudLastSyncedAt = updatedAt
    }

    private func scheduleCloudSync(delaySeconds: UInt64 = 2) {
        guard isCloudSyncEnabled else {
            return
        }

        cloudSyncTask?.cancel()
        let sleepProvider = appSleepProvider
        cloudSyncTask = Task { [weak self] in
            if delaySeconds > 0 {
                try? await sleepProvider.sleep(nanoseconds: delaySeconds * 1_000_000_000)
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
            questionHistory: localStudyRecordUseCase.loadQuestionHistory(),
            lastAnswer: lastAnswer,
            gradingResult: gradingResult,
            isRunning: isRunning,
            hasCompletedOnboarding: hasCompletedOnboarding,
            studyRecords: studyRecords,
            deletedStudyRecordMarkers: localStudyRecordUseCase.loadDeletedRecordMarkers(),
            studyRecordsClearedAt: localStudyRecordUseCase.loadRecordsClearedAt()
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
        mergedState.updatedAt = max(state.updatedAt, appClock.now)

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

        let deletedMarkers = mergedDeletedStudyRecordMarkers(
            remote: remoteState.deletedStudyRecordMarkers,
            local: localStudyRecordUseCase.loadDeletedRecordMarkers()
        )
        let recordsClearedAt = mergedStudyRecordsClearedAt(
            remote: remoteState.studyRecordsClearedAt,
            local: localStudyRecordUseCase.loadRecordsClearedAt()
        )
        let mergedRecords = mergedStudyRecords(
            remote: remoteState.studyRecords,
            local: studyRecords,
            deletedMarkers: deletedMarkers,
            recordsClearedAt: recordsClearedAt
        )

        mergedState.deletedStudyRecordMarkers = deletedMarkers
        mergedState.studyRecordsClearedAt = recordsClearedAt
        mergedState.studyRecords = mergedRecords
        mergedState.questionHistory = mergedQuestionHistory(
            remote: remoteState.questionHistory,
            local: localStudyRecordUseCase.loadQuestionHistory()
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

        if previousAPIKey != resolvedAPIKey.key {
            let trimmedResolved = resolvedAPIKey.key ?? ""
            if !isEditingSettings && !trimmedResolved.isEmpty {
                apiKey = trimmedResolved
                draftAPIKey = trimmedResolved
                savedAPIKey = trimmedResolved
                localStudySettingsUseCase.saveAPIKey(trimmedResolved)
                lastAPIKeyUpdatedAt = resolvedAPIKey.updatedAt
                if let updatedAt = resolvedAPIKey.updatedAt {
                    localStudySettingsUseCase.saveAPIKeyUpdatedAt(updatedAt)
                } else {
                    localStudySettingsUseCase.saveAPIKeyUpdatedAt(nil)
                }
            }

            if resolvedAPIKey.key == nil {
                localStudySettingsUseCase.saveAPIKey("")
                apiKey = ""
                draftAPIKey = ""
                savedAPIKey = ""
                lastAPIKeyUpdatedAt = nil
                localStudySettingsUseCase.saveAPIKeyUpdatedAt(nil)
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
            recordsClearedAt: recordsClearedAt
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

        return appClock.now.timeIntervalSince(localMutationAt) <= Self.recentLocalSettingsMutationWindow
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
        mergedState.updatedAt = appClock.now
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
            local: localStudyRecordUseCase.loadDeletedRecordMarkers()
        )
        mergedState.studyRecordsClearedAt = mergedStudyRecordsClearedAt(
            remote: remoteState.studyRecordsClearedAt,
            local: localStudyRecordUseCase.loadRecordsClearedAt()
        )
        mergedState.studyRecords = mergedStudyRecords(
            remote: remoteState.studyRecords,
            local: studyRecords,
            deletedMarkers: mergedState.deletedStudyRecordMarkers,
            recordsClearedAt: mergedState.studyRecordsClearedAt
        )
        mergedState.questionHistory = mergedQuestionHistory(
            remote: remoteState.questionHistory,
            local: localStudyRecordUseCase.loadQuestionHistory()
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
            !localStudyRecordUseCase.loadQuestionHistory().isEmpty ||
            currentQuestion != nil ||
            !lastAnswer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            gradingResult != nil ||
            !apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            !localStudyRecordUseCase.loadDeletedRecordMarkers().isEmpty ||
            localStudyRecordUseCase.loadRecordsClearedAt() != nil ||
            normalizedSettings(settings) != .default
    }

    private func mergedStudyRecords(
        remote remoteRecords: [StudyRecord],
        local localRecords: [StudyRecord],
        deletedMarkers: [DeletedStudyRecordMarker],
        recordsClearedAt: Date?
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

        return recordsByKey.values.sorted {
            studyRecordSortDate($0) < studyRecordSortDate($1)
        }
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

        return localStudyRecordUseCase.limitedDeletedRecordMarkers(
            markersByKey.values.sorted { $0.deletedAt < $1.deletedAt }
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
            let key = StudyRecordIdentityPolicy.normalizedQuestionText(question.question)
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
            StudyRecordIdentityPolicy.questionsMatch(lhs.question, rhs.question)
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
        let matchingRecords = records.filter {
            $0.id == record.id || studyRecordMatches($0, question: record.question)
        }
        var mergedRecord = record
        let matchingCursor = matchingRecords
            .filter { $0.gradingRequestID == record.gradingRequestID }
            .compactMap(\.gradingLastEventID)
            .max()
        if let matchingCursor,
           matchingCursor > (mergedRecord.gradingLastEventID ?? 0) {
            mergedRecord.gradingLastEventID = matchingCursor
        }

        var merged = records.filter { $0.id != record.id && !studyRecordMatches($0, question: record.question) }
        merged.append(mergedRecord)
        return merged.sorted { studyRecordSortDate($0) < studyRecordSortDate($1) }
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
            cloudSyncStateUseCase.saveStateUpdatedAt(state.updatedAt)
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
        let mergedHasCompletedOnboarding = hasCompletedOnboarding || state.hasCompletedOnboarding
        let localCurrentQuestion = currentQuestion
        let localLastAnswer = lastAnswer
        let localGradingResult = gradingResult
        let localStudyRecords = studyRecords
        let localQuestionHistory = localStudyRecordUseCase.loadQuestionHistory()
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
            local: localStudyRecordUseCase.loadDeletedRecordMarkers()
        )
        let mergedRecordsClearedAt = mergedStudyRecordsClearedAt(
            remote: state.studyRecordsClearedAt,
            local: localStudyRecordUseCase.loadRecordsClearedAt()
        )
        let mergedRecords = mergedStudyRecords(
            remote: state.studyRecords,
            local: localStudyRecords,
            deletedMarkers: mergedDeletedMarkers,
            recordsClearedAt: mergedRecordsClearedAt
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
                    localStudySettingsUseCase.saveAPIKey(syncedAPIKey)
                    log(.info, "원격 OpenAI API 키 동기화를 반영해 앱 키를 갱신했습니다.")
                }

                lastAPIKeyUpdatedAt = resolvedAPIKey.updatedAt
                if let updatedAt = resolvedAPIKey.updatedAt {
                    localStudySettingsUseCase.saveAPIKeyUpdatedAt(updatedAt)
                } else {
                    localStudySettingsUseCase.saveAPIKeyUpdatedAt(nil)
                }

                hasAPIKeyError = false
            } else if previousAPIKey != nil {
                apiKey = ""
                draftAPIKey = ""
                savedAPIKey = ""
                lastAPIKeyUpdatedAt = nil
                localStudySettingsUseCase.saveAPIKey("")
                localStudySettingsUseCase.saveAPIKeyUpdatedAt(nil)
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

        localStudySettingsUseCase.saveSettings(effectiveSettings)
        currentStudySessionUseCase.saveQuestion(appliedCurrentQuestion)
        localStudyRecordUseCase.saveQuestionHistory(mergedHistory)
        currentStudySessionUseCase.saveLastAnswer(appliedLastAnswer)
        currentStudySessionUseCase.saveGradingResult(appliedGradingResult)
        currentStudySessionUseCase.saveIsRunning(state.isRunning)
        onboardingStateUseCase.setHasCompletedOnboarding(mergedHasCompletedOnboarding)
        cloudSyncStateUseCase.saveIsEnabled(preservedCloudSyncEnabled)
        localStudyRecordUseCase.saveDeletedRecordMarkers(mergedDeletedMarkers)
        localStudyRecordUseCase.saveRecordsClearedAt(mergedRecordsClearedAt)
        localStudyRecordUseCase.replaceRecords(mergedRecords)
        let nextCloudSyncTimestamp = max(
            state.updatedAt,
            cloudLastSyncedAt ?? state.updatedAt,
            lastLocalSettingsMutationAt ?? .distantPast
        )
        cloudSyncStateUseCase.saveStateUpdatedAt(nextCloudSyncTimestamp)

        reloadStudyRecordsFromStore()
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

        let now = appClock.now
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
            cloudSyncService = cloudSyncProvider.makeService()
        }

        return cloudSyncService
    }

    nonisolated private static func isDuplicate(_ question: QuestionItem, in recentQuestions: [QuestionItem]) -> Bool {
        let normalizedQuestion = StudyRecordIdentityPolicy.normalizedQuestionText(question.question)
        return recentQuestions.contains {
            StudyRecordIdentityPolicy.normalizedQuestionText($0.question) == normalizedQuestion
        }
    }

    private func handleOpenAIError(_ error: Error) {
        if appErrorHandlingUseCase.isAPIKeyError(error) {
            hasAPIKeyError = true
            errorMessage = apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? strings.apiKeyEmptyDetailed
                : strings.apiKeyInvalidDetailed
            log(.error, errorMessage ?? "OpenAI API 키 오류가 발생했습니다.")
        } else {
            hasAPIKeyError = true
            let message = backendErrorDisplayMessage(error, fallback: strings.openAIAPIKeyMissing)
            errorMessage = message
            log(.error, message)
        }
    }

    private func log(_ level: LogLevel, _ message: String) {
        writeSystemLog(level, message)
        let entry = AppLogEntry(level: level, message: message)
        appLogUseCase.appendLog(entry)
        loadAppLogPage(appLogPage)
    }

    private func writeSystemLog(_ level: LogLevel, _ message: String) {
        let logger = message.contains("auth_trace") ? appAuthLogger : appStateLogger
        print("[StudyMate][\(level.rawValue)] \(message)")

        switch level {
        case .info:
            logger.info("\(message, privacy: .public)")
        case .warning:
            logger.warning("\(message, privacy: .public)")
        case .error:
            logger.error("\(message, privacy: .public)")
        }
    }

    private func logAuthTrace(
        _ event: String,
        page: ProtectedAppPage? = nil,
        reason: String,
        extra: [String] = [],
        deduplicate: Bool = true
    ) {
        let message = authTraceMessage(event: event, page: page, reason: reason, extra: extra)
        let key = [
            event,
            page?.accessLogName ?? "-",
            reason
        ].joined(separator: "|")

        if deduplicate, lastAuthTraceMessages[key] == message {
            return
        }

        lastAuthTraceMessages[key] = message
        Task { @MainActor [weak self] in
            self?.log(.info, message)
        }
    }

    private func authTraceMessage(
        event: String,
        page: ProtectedAppPage?,
        reason: String,
        extra: [String]
    ) -> String {
        let registration = storedBackendIdentityUseCase.loadRegistration()
        let access = backendAccessState.pageAccess
        var fields = [
            "auth_trace",
            "event=\(event)",
            "reason=\(reason)",
            "selectedTab=\(String(describing: selectedTab))",
            "visibleTab=\(String(describing: mobileVisibleTab))",
            "storedSignedIn=\(isCommunitySignedIn)",
            "sessionActive=\(isCommunitySessionActive)",
            "userId=\(backendAccessState.user.id)",
            "userStatus=\(backendAccessState.user.status)",
            "hasAccessToken=\(registration?.hasAccessToken == true)",
            "hasRegisteredAccessToken=\(registration?.hasRegisteredAccessToken == true)",
            "hasRegistration=\(registration != nil)",
            "hasProfile=\(communityProfile != nil)",
            "profileId=\(communityProfile?.id ?? 0)",
            "profileProvider=\(communityProfile?.provider ?? "-")",
            "pageAccess.home=\(access.home)",
            "pageAccess.publicQuestions=\(access.publicQuestions)",
            "pageAccess.myStudies=\(access.myStudies)",
            "pageAccess.studyRoom=\(access.studyRoom)",
            "pageAccess.records=\(access.records)",
            "pageAccess.stats=\(access.stats)",
            "pageAccess.profile=\(access.profile)",
            "pageAccess.developer=\(access.developer)",
            "pageAccess.admin=\(access.admin)",
            "pageAccessPrompt=\(pageAccessPrompt != nil)",
            "homeRoute=\(homeStudyRoute != nil)"
        ]

        if let page {
            let pageShowLoginGate = PageAccessPolicy.shouldShowLoginGate(
                for: page,
                isSignedIn: isCommunitySessionActive
            )
            fields.append("page=\(page.accessLogName)")
            fields.append("pageCanAccess=\(PageAccessPolicy.canAccess(page, in: access))")
            fields.append("pageShowLoginGate=\(pageShowLoginGate)")
        }

        fields.append(contentsOf: extra)
        return fields.joined(separator: " ")
    }

    private func openURLString(_ urlString: String) {
        guard let url = URL(string: urlString) else {
            return
        }

        platformEffectsProvider.open(url)
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

    func categoryIDForStudyTopic(_ topic: String) -> String? {
        categoryID(forTopic: topic)
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
            currentStudySessionUseCase.saveQuestion(preferredRecord.question)
            currentStudySessionUseCase.saveLastAnswer(preferredRecord.answer ?? "")
            currentStudySessionUseCase.saveGradingResult(preferredRecord.gradingResult)
        } else {
            currentQuestion = nil
            lastAnswer = ""
            gradingResult = nil
            currentStudySessionUseCase.saveQuestion(nil)
            currentStudySessionUseCase.saveLastAnswer("")
            currentStudySessionUseCase.saveGradingResult(nil)
        }
    }

    private func showStudyScreen(categoryID: String?) {
        guard requirePageAccess(.studyDetail) else {
            return
        }

        #if os(iOS)
        selectedTab = .home
        homeStudyRoute = HomeStudyRoute(categoryID: categoryID, showsTree: false)
        #else
        selectedTab = .study
        #endif
    }

    private func communityErrorMessage(for error: Error) -> String? {
        appErrorResolution(error, fallback: strings.communityRequestFailed).featureMessage
    }

    private func backendErrorDisplayMessage(_ error: Error, fallback: String) -> String {
        appErrorResolution(error, fallback: fallback).featureMessage ?? fallback
    }

    private func appErrorResolution(_ error: Error, fallback: String) -> AppErrorHandlingResolution {
        appErrorHandlingUseCase.resolve(
            error,
            fallback: fallback,
            language: settings.appLanguage
        )
    }

    private func recordMatching(questionCreatedAt: TimeInterval?) -> StudyRecord? {
        recordsState.record(questionCreatedAt: questionCreatedAt)
    }

    private func studyRecord(matching question: QuestionItem?) -> StudyRecord? {
        recordsState.record(matching: question, matches: studyRecordMatches)
    }

    nonisolated private static func isCancellationLikeError(_ error: Error) -> Bool {
        if error is CancellationError {
            return true
        }

        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled {
            return true
        }

        return error.localizedDescription
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .localizedCaseInsensitiveContains("cancelled")
    }

    nonisolated private static func trimmedOptional(_ value: String) -> String? {
        let trimmedValue = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmedValue.isEmpty ? nil : trimmedValue
    }
}
