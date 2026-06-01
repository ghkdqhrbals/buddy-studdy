import Foundation
#if os(macOS)
import AppKit
#elseif os(iOS)
import UIKit
#endif

private enum QuestionGenerationSkip: Error {
    case pendingLimit
    case duplicateQuestion
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
    static let maxPendingQuestionCount = 3

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
    @Published var hasAPIKeyError = false
    @Published var isValidatingAPIKey = false
    @Published var appLogs: [AppLogEntry]
    @Published var appLogTotalCount: Int
    @Published var appLogPage: Int
    @Published var isDebuggingEnabled: Bool
    @Published var statusMessage: String?
    @Published var errorMessage: String?
    @Published var notificationLandingMessage: String?
    @Published var selectedTab: AppTab = .study
    @Published var focusedRecordRequest: FocusedRecordRequest?
    @Published var hasCompletedOnboarding: Bool
    @Published var isRefreshingVisibleData = false
    @Published var isCloudSyncEnabled: Bool
    @Published var isCloudSyncing = false
    @Published var cloudSyncMessage: String?
    @Published var hasCloudSyncError = false
    @Published var cloudLastSyncedAt: Date?
    @Published var isBackendOpenAIKeyConfigured = false

    private let settingsStore: SettingsStore
    private let openAIClient: OpenAIClientProtocol
    private let remotePushBackendClient: RemotePushBackendClientProtocol
    private let notificationService: NotificationServicing
    private var cloudSyncService: CloudSyncServiceProtocol?
    private var timerTask: Task<Void, Never>?
    private var cloudSyncTask: Task<Void, Never>?
    private var lastBackgroundQuestionPreparationAt: Date?
    private var didStart = false
    private var savedSettings: StudySettings
    private var savedAPIKey: String
    private var isEditingSettings = false

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
        normalizedSettings(activeSettingsForEditing) != savedSettings ||
            activeAPIKeyForEditing.trimmingCharacters(in: .whitespacesAndNewlines) != savedAPIKey
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
        isEditingSettings ? draftAPIKey : apiKey
    }

    var pendingQuestionCount: Int {
        pendingRecordsIncludingCurrent.count
    }

    var hasReachedPendingQuestionLimit: Bool {
        pendingQuestionCount >= Self.maxPendingQuestionCount
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
        openAIClient: OpenAIClientProtocol? = nil,
        remotePushBackendClient: RemotePushBackendClientProtocol = RemotePushBackendClient(),
        notificationService: NotificationServicing = NotificationService(),
        cloudSyncService: CloudSyncServiceProtocol? = nil
    ) {
        let loadedSettings = settingsStore.loadSettings()
        let loadedAPIKey = settingsStore.loadAPIKey()
        let loadedLogPage = settingsStore.loadAppLogs(page: 0, pageSize: Self.developerLogPageSize)
        let loadedHasCompletedOnboarding = settingsStore.loadHasCompletedOnboarding()
        let loadedCloudLastSyncedAt = settingsStore.loadCloudSyncSnapshotUpdatedAt()

        self.settingsStore = settingsStore
        self.settings = loadedSettings
        self.draftSettings = loadedSettings
        self.currentQuestion = settingsStore.loadQuestion()
        self.lastAnswer = settingsStore.loadLastAnswer()
        self.gradingResult = settingsStore.loadGradingResult()
        self.isRunning = settingsStore.loadIsRunning()
        self.studyRecords = settingsStore.loadStudyRecords()
        self.apiKey = loadedAPIKey
        self.draftAPIKey = loadedAPIKey
        self.savedSettings = loadedSettings
        self.savedAPIKey = loadedAPIKey
        self.appLogs = loadedLogPage.entries
        self.appLogTotalCount = loadedLogPage.totalCount
        self.appLogPage = loadedLogPage.page
        self.isDebuggingEnabled = settingsStore.loadIsDebuggingEnabled()
        self.hasCompletedOnboarding = loadedHasCompletedOnboarding
        self.isCloudSyncEnabled = settingsStore.loadIsCloudSyncEnabled()
        self.cloudLastSyncedAt = loadedCloudLastSyncedAt
        self.notificationService = notificationService
        self.cloudSyncService = cloudSyncService
        self.openAIClient = openAIClient ?? OpenAIClient()
        self.remotePushBackendClient = remotePushBackendClient
        self.hasAPIKeyError = apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty

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
        timerTask?.cancel()
        cloudSyncTask?.cancel()
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

        await refreshBackendSnapshotIfPossible(updateVisibleQuestion: false)
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
        await refreshBackendSnapshotIfPossible(updateVisibleQuestion: false)
        if isCloudSyncEnabled {
            await syncCloudNow(updateVisibleQuestion: false)
            await ensureCloudQuestionPushSubscription()
        }
        await generateDueQuestionIfNeeded(reason: "foreground")
    }

    @discardableResult
    func handleBackgroundRefresh() async -> Bool {
        guard hasCompletedOnboarding else {
            return false
        }

        reloadPersistedState()
        await refreshBackendSnapshotIfPossible(updateVisibleQuestion: false)
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
        guard !isRefreshingVisibleData else {
            return
        }

        isRefreshingVisibleData = true
        defer {
            isRefreshingVisibleData = false
        }

        reloadPersistedState()
        let didRefreshBackend = await refreshBackendSnapshotIfPossible()
        if isCloudSyncEnabled {
            await syncCloudNow()
        } else if !didRefreshBackend {
            statusMessage = strings.refreshed
            log(.info, "화면 데이터를 새로고침했습니다.")
        }
    }

    @discardableResult
    private func refreshBackendSnapshotIfPossible(updateVisibleQuestion: Bool = true) async -> Bool {
        guard let registration = settingsStore.loadRemotePushRegistration() else {
            return false
        }

        do {
            let snapshot = try await remotePushBackendClient.fetchSnapshot(
                registration: registration,
                limit: settings.sanitizedMaxHistoryCount,
                offset: 0
            )
            applyBackendSnapshot(snapshot, updateVisibleQuestion: updateVisibleQuestion)
            statusMessage = updateVisibleQuestion ? strings.refreshed : statusMessage
            log(.info, "백엔드 학습 데이터를 동기화했습니다. records=\(snapshot.records.count)")
            return true
        } catch {
            log(.warning, "백엔드 학습 데이터 동기화 실패: \(error.localizedDescription)")
            return false
        }
    }

    private func applyBackendSnapshot(_ snapshot: BackendSnapshot, updateVisibleQuestion: Bool) {
        let sanitizedSettings = normalizedSettings(snapshot.settings.studySettings(fallback: settings))
        let localCurrentQuestion = currentQuestion
        let localLastAnswer = lastAnswer
        let localGradingResult = gradingResult

        settings = sanitizedSettings
        if !isEditingSettings {
            draftSettings = sanitizedSettings
        }
        isRunning = snapshot.settings.enabled
        savedSettings = sanitizedSettings

        settingsStore.saveSettings(sanitizedSettings)
        settingsStore.saveIsRunning(snapshot.settings.enabled)
        settingsStore.replaceStudyRecords(snapshot.records)
        studyRecords = settingsStore.loadStudyRecords()

        if snapshot.settings.openAIKeyConfigured {
            isBackendOpenAIKeyConfigured = true
            hasAPIKeyError = false
            if apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
               errorMessage == strings.apiKeyEmptyDetailed {
                errorMessage = nil
            }
        } else if apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            isBackendOpenAIKeyConfigured = false
            hasAPIKeyError = true
        } else {
            isBackendOpenAIKeyConfigured = false
        }

        guard updateVisibleQuestion else {
            currentQuestion = localCurrentQuestion
            lastAnswer = localLastAnswer
            gradingResult = localGradingResult
            settingsStore.saveQuestion(localCurrentQuestion)
            settingsStore.saveLastAnswer(localLastAnswer)
            settingsStore.saveGradingResult(localGradingResult)
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
        let loadedAPIKey = settingsStore.loadAPIKey()

        settings = loadedSettings
        currentQuestion = settingsStore.loadQuestion()
        lastAnswer = settingsStore.loadLastAnswer()
        gradingResult = settingsStore.loadGradingResult()
        isRunning = settingsStore.loadIsRunning()
        studyRecords = settingsStore.loadStudyRecords()
        apiKey = loadedAPIKey
        savedSettings = loadedSettings
        savedAPIKey = loadedAPIKey.trimmingCharacters(in: .whitespacesAndNewlines)
        hasCompletedOnboarding = settingsStore.loadHasCompletedOnboarding()
        isCloudSyncEnabled = settingsStore.loadIsCloudSyncEnabled()
        cloudLastSyncedAt = settingsStore.loadCloudSyncSnapshotUpdatedAt()
        loadAppLogPage(appLogPage)

        if !isEditingSettings {
            draftSettings = loadedSettings
            draftAPIKey = loadedAPIKey
        }

        hasAPIKeyError = loadedAPIKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        if restartTimerAfterReload {
            restartTimer()
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
        guard !trimmedAPIKey.isEmpty else {
            if settingsStore.loadRemotePushRegistration() != nil, !hasAPIKeyError {
                log(.info, "로컬 API 키는 비어 있지만 백엔드에 키가 설정되어 있어 시작 검증을 건너뜁니다.")
                return
            }
            hasAPIKeyError = true
            errorMessage = strings.apiKeyEmptyDetailed
            log(.warning, "시작 시 API 키 검증을 건너뛰었습니다. API 키가 비어 있습니다.")
            return
        }

        isValidatingAPIKey = true
        log(.info, "시작 시 OpenAI API 키를 검증합니다.")

        do {
            try await openAIClient.validateAPIKey(trimmedAPIKey)
            hasAPIKeyError = false
            if errorMessage?.contains("API 키") == true {
                errorMessage = nil
            }
            log(.info, "시작 시 OpenAI API 키 검증에 성공했습니다.")
        } catch {
            handleOpenAIError(error)
        }

        isValidatingAPIKey = false
    }

    func beginSettingsEditing() {
        draftSettings = settings
        draftAPIKey = apiKey
        isEditingSettings = true
    }

    func cancelSettingsEditing() {
        guard isEditingSettings else {
            return
        }

        draftSettings = settings
        draftAPIKey = apiKey
        isEditingSettings = false
    }

    func updateDraftAppLanguage(_ language: AppLanguage) {
        draftSettings.appLanguage = language
        draftSettings.language = language.studyLanguage
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

    func saveSettings() {
        persistSettings(
            activeSettingsForEditing,
            apiKey: activeAPIKeyForEditing
        )
    }

    func completeOnboarding(settings pendingSettings: StudySettings, apiKey pendingAPIKey: String) async {
        persistSettings(
            pendingSettings,
            apiKey: pendingAPIKey
        )
        settingsStore.saveHasCompletedOnboarding(true)
        hasCompletedOnboarding = true
        selectedTab = .study
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
            try await openAIClient.validateAPIKey(trimmedAPIKey)
            hasAPIKeyError = false
            statusMessage = strings.onboardingCompleted
            log(.info, "온보딩 완료 후 OpenAI API 키 검증에 성공했습니다.")
        } catch {
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
        let sanitizedSettings = normalizedSettings(pendingSettings)
        let trimmedAPIKey = pendingAPIKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let didAPIKeyChange = trimmedAPIKey != savedAPIKey

        settings = sanitizedSettings
        apiKey = pendingAPIKey
        draftSettings = sanitizedSettings
        draftAPIKey = pendingAPIKey

        settingsStore.saveSettings(sanitizedSettings)
        if didAPIKeyChange {
            settingsStore.saveAPIKey(pendingAPIKey)
        }
        savedSettings = sanitizedSettings
        savedAPIKey = trimmedAPIKey
        studyRecords = settingsStore.loadStudyRecords()
        if trimmedAPIKey.isEmpty {
            hasAPIKeyError = true
            errorMessage = strings.apiKeyEmptyDetailed
        } else if didAPIKeyChange || !hasAPIKeyError {
            isBackendOpenAIKeyConfigured = true
            errorMessage = nil
        }
        statusMessage = "설정을 저장했습니다."
        StudyNotificationDelegate.shared.register(language: sanitizedSettings.appLanguage)
        log(.info, "설정을 저장했습니다. interval=\(sanitizedSettings.sanitizedIntervalMinutes), maxHistory=\(sanitizedSettings.sanitizedMaxHistoryCount)")
        markCloudDataChanged()

        restartTimer()

        Task {
            await ensureCloudQuestionPushSubscription()
            await syncRemotePushScheduleIfPossible(reason: "settings")
        }
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
        statusMessage = "API 키를 확인 중입니다."
        errorMessage = nil

        do {
            try await openAIClient.validateAPIKey(trimmedAPIKey)
            hasAPIKeyError = false
            statusMessage = "설정을 저장했고 API 키도 확인했습니다."
            log(.info, "OpenAI API 키 검증에 성공했습니다.")
        } catch {
            handleOpenAIError(error)
            statusMessage = nil
        }

        isValidatingAPIKey = false
    }

    func setRunning(_ running: Bool) {
        isRunning = running
        settingsStore.saveIsRunning(running)
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

        if let registration = settingsStore.loadRemotePushRegistration() {
            await generateBackendQuestion(registration: registration, manual: manual)
            return
        }

        guard await canCreateQuestionAfterGlobalPendingCheck(
            reason: "새 질문 생성",
            updateVisibleQuestion: manual
        ) else {
            return
        }

        errorMessage = nil
        statusMessage = manual ? "질문을 생성 중입니다." : "예약된 질문을 생성 중입니다."
        log(.info, "새 질문 생성 요청을 전송합니다. topic=\(settings.topic), difficulty=\(settings.difficulty.level), model=\(settings.sanitizedOpenAIModel)")

        do {
            let shouldActivateQuestion = manual || !hasActiveUngradedCurrentQuestion
            let question = try await createAndStoreQuestion(activate: shouldActivateQuestion)
            guard await syncGeneratedQuestionIfNeeded(
                question,
                updateVisibleQuestion: shouldActivateQuestion
            ) else {
                return
            }
            statusMessage = shouldActivateQuestion ? "새 질문이 준비됐습니다." : "새 질문이 미제출 목록에 추가됐습니다."
            log(.info, "질문을 생성했습니다: \(question.question)")
            let didScheduleNotification = await notificationService.showQuestionNotification(
                question: question,
                title: strings.notificationTitle,
                subtitle: notificationSubtitle,
                sound: settings.notificationSound,
                language: settings.appLanguage,
                deliveryDate: nil
            )
            if !didScheduleNotification {
                statusMessage = strings.testNotificationFailed
                log(.warning, "질문 알림을 표시하지 못했습니다. 알림 권한 또는 시스템 설정을 확인하세요.")
            }
            await saveQuestionPushIfNeeded(question)
            markCloudDataChanged()
        } catch QuestionGenerationSkip.pendingLimit {
            showPendingQuestionLimitStatus(reason: "질문 저장")
        } catch QuestionGenerationSkip.duplicateQuestion {
            statusMessage = strings.duplicateQuestionSkipped
            log(.warning, "OpenAI가 기존 질문과 중복되는 질문을 반복 생성해 저장하지 않았습니다.")
        } catch {
            handleOpenAIError(error)
            statusMessage = nil
            log(.error, "질문 생성에 실패했습니다: \(error.localizedDescription)")
        }
    }

    private func generateBackendQuestion(registration: RemotePushRegistration, manual: Bool) async {
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
            let record = try await remotePushBackendClient.createQuestion(registration: registration)
            settingsStore.appendQuestionToHistory(record.question)
            settingsStore.replaceStudyRecords(mergeBackendRecord(record, into: studyRecords))
            studyRecords = settingsStore.loadStudyRecords()

            let shouldActivateQuestion = manual || !hasActiveUngradedCurrentQuestion
            if shouldActivateQuestion {
                currentQuestion = record.question
                gradingResult = record.gradingResult
                lastAnswer = record.answer ?? ""
                settingsStore.saveQuestion(record.question)
                settingsStore.saveGradingResult(record.gradingResult)
                settingsStore.saveLastAnswer(record.answer ?? "")
            }

            hasAPIKeyError = false
            statusMessage = shouldActivateQuestion ? "새 질문이 준비됐습니다." : "새 질문이 미제출 목록에 추가됐습니다."
            log(.info, "백엔드 질문을 생성했습니다: \(record.question.question)")
            await syncRemotePushScheduleIfPossible(reason: "manual-question")
        } catch {
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

        if settingsStore.loadRemotePushRegistration() != nil {
            await syncRemotePushScheduleIfPossible(reason: reason)
            await refreshBackendSnapshotIfPossible(updateVisibleQuestion: false)
            log(.info, "백엔드 스케줄러가 예약 질문을 담당하므로 로컬 타이머 생성을 건너뛰었습니다. reason=\(reason)")
            return false
        }

        refreshStudyProgressFromStore()

        guard apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false else {
            log(.warning, "API 키가 비어 있어 \(reason) 예약 질문 생성을 건너뛰었습니다.")
            return false
        }

        guard !hasAPIKeyError else {
            log(.warning, "API 키 오류가 있어 \(reason) 예약 질문 생성을 건너뛰었습니다.")
            return false
        }

        guard !hasReachedPendingQuestionLimit else {
            showPendingQuestionLimitStatus(reason: "\(reason) 예약 질문 생성")
            return false
        }

        guard isQuestionDue(now: Date()) else {
            return false
        }

        let latestBeforeGeneration = latestQuestionCreatedAt
        log(.info, "\(reason) 기준 질문 간격이 지나 새 질문을 생성합니다.")
        await generateQuestion(manual: false)
        return latestQuestionCreatedAt != latestBeforeGeneration
    }

    @discardableResult
    private func prepareScheduledQuestionsForLockedDevice(isExpired: () -> Bool) async -> Int {
        reloadPersistedState(restartTimerAfterReload: false)

        guard hasCompletedOnboarding, isRunning else {
            return 0
        }

        if settingsStore.loadRemotePushRegistration() != nil {
            await syncRemotePushScheduleIfPossible(reason: "background")
            log(.info, "백엔드/APNs 스케줄러가 잠금화면 질문을 담당하므로 로컬 예약 알림 준비를 건너뛰었습니다.")
            return 0
        }

        guard !isGeneratingQuestion else {
            log(.info, "질문 생성 중이라 잠금화면용 예약 질문 준비를 건너뛰었습니다.")
            return 0
        }

        guard apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false else {
            log(.warning, "API 키가 비어 있어 잠금화면용 예약 질문 준비를 건너뛰었습니다.")
            return 0
        }

        guard !hasAPIKeyError else {
            log(.warning, "API 키 오류가 있어 잠금화면용 예약 질문 준비를 건너뛰었습니다.")
            return 0
        }

        guard await notificationService.requestAuthorizationIfNeeded(language: settings.appLanguage) else {
            log(.warning, "알림 권한이 없어 잠금화면용 예약 질문 준비를 건너뛰었습니다.")
            return 0
        }

        if isCloudSyncEnabled {
            await syncCloudNow(updateVisibleQuestion: false)
            await ensureCloudQuestionPushSubscription()
        }
        refreshStudyProgressFromStore()

        let pendingScheduledNotificationCount = await notificationService.pendingQuestionNotificationCount()
        guard pendingScheduledNotificationCount == 0 else {
            log(.info, "이미 예약된 질문 알림이 있어 잠금화면용 예약 질문 준비를 건너뛰었습니다. pendingNotifications=\(pendingScheduledNotificationCount)")
            return 0
        }

        guard pendingQuestionCount < Self.maxPendingQuestionCount else {
            showPendingQuestionLimitStatus(reason: "잠금화면용 예약 질문 준비")
            return 0
        }

        let now = Date()
        let deliveryDate = max(nextQuestionDueDate(now: now), now.addingTimeInterval(2))
        guard shouldPrepareBackgroundQuestionNotification(now: now) else {
            return 0
        }

        guard !isExpired() else {
            log(.warning, "iOS background 시간이 만료되어 잠금화면용 예약 질문 준비를 중단했습니다.")
            return 0
        }

        isGeneratingQuestion = true
        defer {
            isGeneratingQuestion = false
        }

        do {
            let question = try await createAndStoreQuestion(activate: false)
            guard await syncGeneratedQuestionIfNeeded(question, updateVisibleQuestion: false) else {
                return 0
            }

            let didScheduleNotification = await notificationService.showQuestionNotification(
                question: question,
                title: strings.notificationTitle,
                subtitle: notificationSubtitle,
                sound: settings.notificationSound,
                language: settings.appLanguage,
                deliveryDate: deliveryDate
            )

            guard didScheduleNotification else {
                log(.warning, "잠금화면용 질문 알림 예약에 실패했습니다.")
                return 0
            }

            lastBackgroundQuestionPreparationAt = now
            statusMessage = "잠금화면용 질문 1개를 예약했습니다."
            log(
                .info,
                "잠금화면용 예약 질문을 1개 준비했습니다. deliveryAt=\(deliveryDate), pending=\(pendingQuestionCount)"
            )
            markCloudDataChanged()
            return 1
        } catch QuestionGenerationSkip.pendingLimit {
            showPendingQuestionLimitStatus(reason: "잠금화면용 예약 질문 저장")
            return 0
        } catch {
            handleOpenAIError(error)
            statusMessage = nil
            log(.error, "잠금화면용 예약 질문 준비에 실패했습니다: \(error.localizedDescription)")
            return 0
        }
    }

    private func shouldPrepareBackgroundQuestionNotification(now: Date) -> Bool {
        if let lastBackgroundQuestionPreparationAt,
           now.timeIntervalSince(lastBackgroundQuestionPreparationAt) < 30 {
            log(.info, "백그라운드 질문 준비가 너무 자주 호출되어 건너뛰었습니다.")
            return false
        }

        return true
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

        guard !hasReachedPendingQuestionLimit else {
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

    private func createAndStoreQuestion(activate: Bool) async throws -> QuestionItem {
        await refreshGlobalStudyProgressFromStore(updateVisibleQuestion: activate)
        guard !hasReachedPendingQuestionLimit else {
            throw QuestionGenerationSkip.pendingLimit
        }

        let generated = try await generateUniqueQuestion()
        let question = generated.question

        await refreshGlobalStudyProgressFromStore(updateVisibleQuestion: activate)
        guard !hasReachedPendingQuestionLimit else {
            throw QuestionGenerationSkip.pendingLimit
        }
        guard !Self.isDuplicate(question, in: previousQuestionsForGeneration()) else {
            throw QuestionGenerationSkip.duplicateQuestion
        }

        settingsStore.appendQuestionToHistory(question)
        settingsStore.appendStudyRecord(question: question, settings: settings)
        settingsStore.saveQuestionResponseID(generated.responseID)

        if activate {
            currentQuestion = question
            gradingResult = nil
            lastAnswer = ""
            settingsStore.saveQuestion(question)
            settingsStore.saveGradingResult(nil)
            settingsStore.saveLastAnswer("")
        }

        studyRecords = settingsStore.loadStudyRecords()
        hasAPIKeyError = false

        return question
    }

    private func syncGeneratedQuestionIfNeeded(
        _ question: QuestionItem,
        updateVisibleQuestion: Bool = true
    ) async -> Bool {
        guard isCloudSyncEnabled else {
            return true
        }

        markCloudDataDirtyWithoutScheduling()
        await syncCloudNow(updateVisibleQuestion: updateVisibleQuestion)
        refreshStudyProgressFromStore()

        if isCloudSyncEnabled, hasCloudSyncError {
            log(.warning, "질문은 로컬에 저장했지만 iCloud 동기화에 실패했습니다.")
            return true
        }

        if pendingQuestionCount > Self.maxPendingQuestionCount {
            log(.warning, "iCloud 동기화 후 미채점 질문이 \(pendingQuestionCount)개입니다. 기존 데이터는 보존하고 이후 새 질문 생성을 막습니다.")
            showPendingQuestionLimitStatus(reason: "iCloud 동기화 후 질문 알림 전송")
            return false
        }

        return true
    }

    private func generateUniqueQuestion() async throws -> GeneratedQuestionResult {
        var previousQuestions = previousQuestionsForGeneration()

        for _ in 0..<5 {
            let generated = try await openAIClient.generateQuestion(
                settings: settings,
                recentQuestions: Array(previousQuestions.suffix(80)),
                previousResponseID: settingsStore.loadQuestionResponseID(),
                apiKey: apiKey
            )

            if !Self.isDuplicate(generated.question, in: previousQuestions) {
                return generated
            }

            previousQuestions.append(generated.question)
            log(.warning, "생성된 질문이 기존 질문과 중복되어 다시 생성합니다.")
        }

        throw QuestionGenerationSkip.duplicateQuestion
    }

    private func previousQuestionsForGeneration() -> [QuestionItem] {
        var questions = settingsStore.loadQuestionHistory()
        questions.append(contentsOf: settingsStore.loadStudyRecords().map(\.question))

        if let currentQuestion {
            questions.append(currentQuestion)
        }

        var questionsByKey: [String: QuestionItem] = [:]
        for question in questions {
            let key = SettingsStore.normalizedQuestionText(question.question)
            guard !key.isEmpty else {
                continue
            }

            if let existingQuestion = questionsByKey[key] {
                questionsByKey[key] = question.createdAt >= existingQuestion.createdAt ? question : existingQuestion
            } else {
                questionsByKey[key] = question
            }
        }

        return questionsByKey.values.sorted { $0.createdAt < $1.createdAt }
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

        if let registration = settingsStore.loadRemotePushRegistration(),
           let record = studyRecord(matching: currentQuestion) {
            do {
                let updatedRecord = try await remotePushBackendClient.gradeRecord(
                    registration: registration,
                    recordID: record.id,
                    answer: trimmedAnswer
                )
                applyGradedRecord(updatedRecord, answer: trimmedAnswer)
                await syncRemotePushScheduleIfPossible(reason: "grade")
                return
            } catch {
                handleOpenAIError(error)
                statusMessage = nil
                return
            }
        }

        do {
            let result = try await openAIClient.gradeAnswer(
                question: currentQuestion,
                answer: trimmedAnswer,
                settings: settings,
                apiKey: apiKey
            )
            gradingResult = result
            settingsStore.saveGradingResult(result)
            settingsStore.saveLastAnswer(trimmedAnswer)
            settingsStore.updateStudyRecord(
                question: currentQuestion,
                answer: trimmedAnswer,
                gradingResult: result
            )
            notificationService.cancelQuestionNotification(for: currentQuestion)
            studyRecords = settingsStore.loadStudyRecords()
            hasAPIKeyError = false
            statusMessage = "채점이 완료됐습니다."
            log(.info, "현재 질문 답변을 채점했습니다. score=\(result.score)")
            markCloudDataChanged()
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

        let gradingSettings = StudySettings(
            topic: record.topic.isEmpty ? settings.topic : record.topic,
            difficulty: record.difficulty,
            appLanguage: settings.appLanguage,
            language: settings.appLanguage.studyLanguage,
            openAIModel: settings.sanitizedOpenAIModel,
            customPrompt: settings.customPrompt,
            intervalMinutes: settings.sanitizedIntervalMinutes,
            maxHistoryCount: settings.sanitizedMaxHistoryCount
        )

        if let registration = settingsStore.loadRemotePushRegistration() {
            do {
                let updatedRecord = try await remotePushBackendClient.gradeRecord(
                    registration: registration,
                    recordID: record.id,
                    answer: trimmedAnswer
                )
                applyGradedRecord(updatedRecord, answer: trimmedAnswer)
                await syncRemotePushScheduleIfPossible(reason: "grade-record")
                markCloudDataChanged()
                return
            } catch {
                handleOpenAIError(error)
                statusMessage = nil
                return
            }
        }

        do {
            let result = try await openAIClient.gradeAnswer(
                question: record.question,
                answer: trimmedAnswer,
                settings: gradingSettings,
                apiKey: apiKey
            )

            currentQuestion = record.question
            lastAnswer = trimmedAnswer
            gradingResult = result
            settingsStore.saveQuestion(record.question)
            settingsStore.saveLastAnswer(trimmedAnswer)
            settingsStore.saveGradingResult(result)
            settingsStore.updateStudyRecord(
                question: record.question,
                answer: trimmedAnswer,
                gradingResult: result
            )
            notificationService.cancelQuestionNotification(for: record.question)
            studyRecords = settingsStore.loadStudyRecords()
            hasAPIKeyError = false
            statusMessage = "채점이 완료됐습니다."
            log(.info, "기록 답변을 채점했습니다. score=\(result.score)")
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
                    await refreshBackendSnapshotIfPossible(updateVisibleQuestion: false)
                    await syncRemotePushScheduleIfPossible(reason: "skip")
                } catch {
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

    func copyToClipboard(_ text: String, message: String? = nil) {
        let trimmedText = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedText.isEmpty else {
            return
        }

        #if os(macOS)
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(trimmedText, forType: .string)
        #elseif os(iOS)
        UIPasteboard.general.string = trimmedText
        #endif
        statusMessage = message ?? strings.copiedToClipboard
        log(.info, "텍스트를 클립보드에 복사했습니다.")
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
        notificationLandingMessage = nil
        currentQuestion = record.question
        lastAnswer = record.answer ?? ""
        gradingResult = record.gradingResult
        settingsStore.saveQuestion(record.question)
        settingsStore.saveLastAnswer(record.answer ?? "")
        settingsStore.saveGradingResult(record.gradingResult)
        selectedTab = .study
        focusedRecordRequest = nil
        statusMessage = record.gradingResult == nil ? "미제출 질문을 열었습니다." : "학습 기록을 열었습니다."
        markCloudDataChanged(syncDelaySeconds: 4)
    }

    func prepareToOpenQuestionFromNotification() {
        selectedTab = .study
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
            selectedTab = .study
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
                selectedTab = .study
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
        guard let registration = settingsStore.loadRemotePushRegistration() else {
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
        selectedTab = .study
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
                do {
                    try await remotePushBackendClient.clearRecords(registration: registration)
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
                do {
                    try await remotePushBackendClient.deleteRecord(registration: registration, recordID: record.id)
                    await refreshBackendSnapshotIfPossible(updateVisibleQuestion: false)
                    await syncRemotePushScheduleIfPossible(reason: "delete-record")
                } catch {
                    log(.warning, "백엔드 학습 기록 삭제 실패: \(error.localizedDescription)")
                }
            }
        }
        markCloudDataChanged(syncDelaySeconds: 0)
    }

    func clearAppLogs() {
        settingsStore.clearAppLogs()
        appLogs = []
        appLogTotalCount = 0
        appLogPage = 0
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
            let storedLocalUpdatedAt = settingsStore.loadCloudSyncSnapshotUpdatedAt()
            let localUpdatedAt = storedLocalUpdatedAt ?? .distantPast
            let fetchedRemoteSnapshot = try await cloudSyncService.fetchSnapshot()

            if let fetchedRemoteSnapshot {
                let apiKeyMerge = remoteSnapshotByFillingMissingAPIKey(fetchedRemoteSnapshot)
                let remoteSnapshot = apiKeyMerge.snapshot
                if storedLocalUpdatedAt == nil {
                    let firstSync = firstSyncSnapshot(from: remoteSnapshot)
                    applyCloudSnapshot(firstSync.snapshot, updateVisibleQuestion: updateVisibleQuestion)

                    if firstSync.shouldPushMergedSnapshot {
                        try await cloudSyncService.saveSnapshot(firstSync.snapshot)
                        settingsStore.saveCloudSyncSnapshotUpdatedAt(firstSync.snapshot.updatedAt)
                        cloudLastSyncedAt = firstSync.snapshot.updatedAt
                        cloudSyncMessage = strings.syncMergedRemote
                        log(.info, "iCloud 데이터를 불러오고 이 기기의 기록을 병합했습니다.")
                    } else {
                        cloudSyncMessage = strings.syncPulledRemote
                        log(.info, "첫 iCloud 동기화에서 원격 학습 데이터를 불러왔습니다.")
                    }
                } else if remoteSnapshot.updatedAt > localUpdatedAt {
                    var mergedRemoteSnapshot = incomingSnapshotMergingLocalData(remoteSnapshot)
                    let shouldPushMergedRemote = apiKeyMerge.shouldPush ||
                        cloudSnapshotContentDiffers(mergedRemoteSnapshot, remoteSnapshot)

                    if shouldPushMergedRemote {
                        mergedRemoteSnapshot.updatedAt = max(Date(), remoteSnapshot.updatedAt, localUpdatedAt)
                        try await cloudSyncService.saveSnapshot(mergedRemoteSnapshot)
                        applyCloudSnapshot(mergedRemoteSnapshot, updateVisibleQuestion: updateVisibleQuestion)
                        settingsStore.saveCloudSyncSnapshotUpdatedAt(mergedRemoteSnapshot.updatedAt)
                        cloudLastSyncedAt = mergedRemoteSnapshot.updatedAt
                        cloudSyncMessage = strings.syncMergedRemote
                        log(.info, "iCloud 최신 데이터에 이 기기의 로컬 변경사항을 병합했습니다.")
                    } else {
                        applyCloudSnapshot(remoteSnapshot, updateVisibleQuestion: updateVisibleQuestion)
                        cloudSyncMessage = strings.syncPulledRemote
                        log(.info, "iCloud에서 최신 학습 데이터를 불러왔습니다.")
                    }
                } else {
                    let mergedSnapshot = outgoingSnapshotMergingRemoteData(
                        makeCloudSnapshot(updatedAt: localUpdatedAt),
                        remoteSnapshot: remoteSnapshot
                    )
                    if cloudSnapshotContentDiffers(mergedSnapshot, remoteSnapshot) {
                        var snapshot = mergedSnapshot
                        snapshot.updatedAt = max(localUpdatedAt, remoteSnapshot.updatedAt, Date())
                        try await cloudSyncService.saveSnapshot(snapshot)
                        applyCloudSnapshot(snapshot, updateVisibleQuestion: updateVisibleQuestion)
                        cloudSyncMessage = strings.syncPushedLocal
                        log(.info, "학습 데이터를 iCloud에 저장했습니다.")
                    } else {
                        applyCloudSnapshot(remoteSnapshot, updateVisibleQuestion: updateVisibleQuestion)
                        cloudSyncMessage = strings.syncAlreadyCurrent
                    }
                }
            } else {
                let updatedAt = max(localUpdatedAt, Date())
                let snapshot = makeCloudSnapshot(updatedAt: updatedAt)
                try await cloudSyncService.saveSnapshot(snapshot)
                applyCloudSnapshot(snapshot, updateVisibleQuestion: updateVisibleQuestion)
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

    #if os(iOS)
    func registerRemotePushDeviceToken(_ deviceToken: Data) async {
        let token = Self.hexDeviceToken(deviceToken)
        do {
            let existingRegistration = settingsStore.loadRemotePushRegistration()
            let registration: RemotePushRegistration

            if let existingRegistration,
               existingRegistration.apnsToken == token {
                registration = existingRegistration
            } else {
                registration = try await remotePushBackendClient.registerDevice(
                    apnsToken: token,
                    language: settings.appLanguage,
                    timezone: TimeZone.current.identifier,
                    apnsEnvironment: Self.apnsEnvironment
                )
                settingsStore.saveRemotePushRegistration(registration)
                log(.info, "서버 push 백엔드에 iPhone 기기를 등록했습니다.")
            }

            try await updateRemotePushSchedule(
                registration: registration,
                reason: "device-token"
            )
            await refreshBackendSnapshotIfPossible(updateVisibleQuestion: false)
        } catch {
            log(.warning, "서버 push 백엔드 등록 실패: \(error.localizedDescription)")
        }
    }

    private func syncRemotePushScheduleIfPossible(reason: String) async {
        guard let registration = settingsStore.loadRemotePushRegistration() else {
            return
        }

        do {
            try await updateRemotePushSchedule(
                registration: registration,
                reason: reason
            )
        } catch {
            log(.warning, "서버 push 일정 동기화 실패: \(error.localizedDescription)")
        }
    }

    private func updateRemotePushSchedule(
        registration: RemotePushRegistration,
        reason: String
    ) async throws {
        let trimmedAPIKey = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let shouldEnableRemotePush = isRunning && (!trimmedAPIKey.isEmpty || isBackendOpenAIKeyConfigured)
        try await remotePushBackendClient.updateSchedule(
            registration: registration,
            settings: settings,
            apiKey: trimmedAPIKey.isEmpty ? nil : trimmedAPIKey,
            enabled: shouldEnableRemotePush
        )
        log(.info, "서버 push 일정을 동기화했습니다. reason=\(reason), enabled=\(shouldEnableRemotePush)")
    }

    private static var apnsEnvironment: String {
        #if DEBUG
        return "sandbox"
        #else
        return "production"
        #endif
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
            maxHistoryCount: settings.sanitizedMaxHistoryCount
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
        settingsStore.saveCloudSyncSnapshotUpdatedAt(updatedAt)
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

    private func makeCloudSnapshot(updatedAt: Date) -> CloudSyncSnapshot {
        CloudSyncSnapshot(
            updatedAt: updatedAt,
            apiKey: Self.trimmedOptional(apiKey),
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

    private func remoteSnapshotByFillingMissingAPIKey(_ snapshot: CloudSyncSnapshot) -> (snapshot: CloudSyncSnapshot, shouldPush: Bool) {
        guard Self.trimmedOptional(snapshot.apiKey ?? "") == nil,
              let localAPIKey = Self.trimmedOptional(apiKey) else {
            return (snapshot, false)
        }

        var mergedSnapshot = snapshot
        mergedSnapshot.apiKey = localAPIKey
        mergedSnapshot.updatedAt = max(snapshot.updatedAt, Date())
        return (mergedSnapshot, true)
    }

    private func incomingSnapshotMergingLocalData(_ remoteSnapshot: CloudSyncSnapshot) -> CloudSyncSnapshot {
        var mergedSnapshot = remoteSnapshot
        let maxHistoryCount = max(
            remoteSnapshot.settings.sanitizedMaxHistoryCount,
            settings.sanitizedMaxHistoryCount
        )
        let deletedMarkers = mergedDeletedStudyRecordMarkers(
            remote: remoteSnapshot.deletedStudyRecordMarkers,
            local: settingsStore.loadDeletedStudyRecordMarkers()
        )
        let recordsClearedAt = mergedStudyRecordsClearedAt(
            remote: remoteSnapshot.studyRecordsClearedAt,
            local: settingsStore.loadStudyRecordsClearedAt()
        )
        let mergedRecords = mergedStudyRecords(
            remote: remoteSnapshot.studyRecords,
            local: studyRecords,
            deletedMarkers: deletedMarkers,
            recordsClearedAt: recordsClearedAt,
            maxCount: maxHistoryCount
        )

        mergedSnapshot.deletedStudyRecordMarkers = deletedMarkers
        mergedSnapshot.studyRecordsClearedAt = recordsClearedAt
        mergedSnapshot.studyRecords = mergedRecords
        mergedSnapshot.questionHistory = mergedQuestionHistory(
            remote: remoteSnapshot.questionHistory,
            local: settingsStore.loadQuestionHistory()
        )

        if let currentQuestion = preferredCurrentQuestion(
            local: currentQuestion,
            remote: remoteSnapshot.currentQuestion,
            mergedRecords: mergedRecords
        ) {
            mergedSnapshot.currentQuestion = currentQuestion
            if let currentRecord = mergedRecords.last(where: {
                studyRecordMatches($0, question: currentQuestion)
            }) {
                mergedSnapshot.lastAnswer = currentRecord.answer ?? ""
                mergedSnapshot.gradingResult = currentRecord.gradingResult
            }
        } else {
            mergedSnapshot.currentQuestion = nil
            mergedSnapshot.lastAnswer = ""
            mergedSnapshot.gradingResult = nil
        }

        return mergedSnapshot
    }

    private func cloudSnapshotContentDiffers(_ lhs: CloudSyncSnapshot, _ rhs: CloudSyncSnapshot) -> Bool {
        var normalizedLHS = lhs
        var normalizedRHS = rhs
        normalizedLHS.updatedAt = .distantPast
        normalizedRHS.updatedAt = .distantPast
        return normalizedLHS != normalizedRHS
    }

    private func outgoingSnapshotMergingRemoteData(
        _ snapshot: CloudSyncSnapshot,
        remoteSnapshot: CloudSyncSnapshot
    ) -> CloudSyncSnapshot {
        var mergedSnapshot = snapshot
        let maxHistoryCount = max(
            snapshot.settings.sanitizedMaxHistoryCount,
            remoteSnapshot.settings.sanitizedMaxHistoryCount
        )

        if Self.trimmedOptional(snapshot.apiKey ?? "") == nil,
           let remoteAPIKey = Self.trimmedOptional(remoteSnapshot.apiKey ?? "") {
            mergedSnapshot.apiKey = remoteAPIKey
            apiKey = remoteAPIKey
            draftAPIKey = remoteAPIKey
            savedAPIKey = remoteAPIKey
            settingsStore.saveAPIKey(remoteAPIKey)
            hasAPIKeyError = false
            if errorMessage == strings.apiKeyEmptyDetailed || errorMessage == strings.apiKeyInvalidDetailed {
                errorMessage = nil
            }
            log(.info, "iCloud 원격 OpenAI API 키를 보존해 로컬 변경사항과 함께 저장합니다.")
        }

        mergedSnapshot.hasCompletedOnboarding = snapshot.hasCompletedOnboarding || remoteSnapshot.hasCompletedOnboarding
        let deletedMarkers = mergedDeletedStudyRecordMarkers(
            remote: remoteSnapshot.deletedStudyRecordMarkers,
            local: snapshot.deletedStudyRecordMarkers
        )
        let recordsClearedAt = mergedStudyRecordsClearedAt(
            remote: remoteSnapshot.studyRecordsClearedAt,
            local: snapshot.studyRecordsClearedAt
        )
        let mergedRecords = mergedStudyRecords(
            remote: remoteSnapshot.studyRecords,
            local: snapshot.studyRecords,
            deletedMarkers: deletedMarkers,
            recordsClearedAt: recordsClearedAt,
            maxCount: maxHistoryCount
        )
        let currentCandidate = preferredCurrentQuestion(
            local: snapshot.currentQuestion,
            remote: remoteSnapshot.currentQuestion,
            mergedRecords: mergedRecords
        )
        mergedSnapshot.studyRecords = mergedRecords
        mergedSnapshot.deletedStudyRecordMarkers = deletedMarkers
        mergedSnapshot.studyRecordsClearedAt = recordsClearedAt
        mergedSnapshot.questionHistory = mergedQuestionHistory(
            remote: remoteSnapshot.questionHistory,
            local: snapshot.questionHistory
        )

        if let preferredCurrentQuestion = currentCandidate,
           mergedSnapshot.studyRecords.contains(where: {
               studyRecordMatches($0, question: preferredCurrentQuestion)
           }) {
            mergedSnapshot.currentQuestion = preferredCurrentQuestion
            if let currentRecord = mergedSnapshot.studyRecords.last(where: {
                studyRecordMatches($0, question: preferredCurrentQuestion)
            }) {
                mergedSnapshot.lastAnswer = currentRecord.answer ?? ""
                mergedSnapshot.gradingResult = currentRecord.gradingResult
            }
        } else {
            mergedSnapshot.currentQuestion = nil
            mergedSnapshot.lastAnswer = ""
            mergedSnapshot.gradingResult = nil
        }

        return mergedSnapshot
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

    private func firstSyncSnapshot(from remoteSnapshot: CloudSyncSnapshot) -> (snapshot: CloudSyncSnapshot, shouldPushMergedSnapshot: Bool) {
        guard hasMeaningfulLocalCloudData else {
            return (remoteSnapshot, false)
        }

        var mergedSnapshot = remoteSnapshot
        mergedSnapshot.updatedAt = Date()
        if Self.trimmedOptional(mergedSnapshot.apiKey ?? "") == nil {
            mergedSnapshot.apiKey = Self.trimmedOptional(apiKey)
        }
        mergedSnapshot.hasCompletedOnboarding = remoteSnapshot.hasCompletedOnboarding || hasCompletedOnboarding
        mergedSnapshot.deletedStudyRecordMarkers = mergedDeletedStudyRecordMarkers(
            remote: remoteSnapshot.deletedStudyRecordMarkers,
            local: settingsStore.loadDeletedStudyRecordMarkers()
        )
        mergedSnapshot.studyRecordsClearedAt = mergedStudyRecordsClearedAt(
            remote: remoteSnapshot.studyRecordsClearedAt,
            local: settingsStore.loadStudyRecordsClearedAt()
        )
        mergedSnapshot.studyRecords = mergedStudyRecords(
            remote: remoteSnapshot.studyRecords,
            local: studyRecords,
            deletedMarkers: mergedSnapshot.deletedStudyRecordMarkers,
            recordsClearedAt: mergedSnapshot.studyRecordsClearedAt,
            maxCount: max(
                remoteSnapshot.settings.sanitizedMaxHistoryCount,
                settings.sanitizedMaxHistoryCount
            )
        )
        mergedSnapshot.questionHistory = mergedQuestionHistory(
            remote: remoteSnapshot.questionHistory,
            local: settingsStore.loadQuestionHistory()
        )

        if mergedSnapshot.currentQuestion == nil {
            mergedSnapshot.currentQuestion = currentQuestion
        }
        if mergedSnapshot.lastAnswer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            mergedSnapshot.lastAnswer = lastAnswer
        }
        if mergedSnapshot.gradingResult == nil {
            mergedSnapshot.gradingResult = gradingResult
        }

        return (mergedSnapshot, true)
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

    private func applyCloudSnapshot(_ snapshot: CloudSyncSnapshot, updateVisibleQuestion: Bool = true) {
        let preservedCloudSyncEnabled = isCloudSyncEnabled
        let sanitizedSettings = normalizedSettings(snapshot.settings)
        let mergedHasCompletedOnboarding = hasCompletedOnboarding || snapshot.hasCompletedOnboarding
        let syncedAPIKey = Self.trimmedOptional(snapshot.apiKey ?? "")
        let localCurrentQuestion = currentQuestion
        let localLastAnswer = lastAnswer
        let localGradingResult = gradingResult
        let localStudyRecords = studyRecords
        let localQuestionHistory = settingsStore.loadQuestionHistory()
        let previousAPIKey = Self.trimmedOptional(apiKey)
        let shouldPreserveActiveQuestion = shouldPreserveActiveQuestion(whenApplying: snapshot)
        let mergedDeletedMarkers = mergedDeletedStudyRecordMarkers(
            remote: snapshot.deletedStudyRecordMarkers,
            local: settingsStore.loadDeletedStudyRecordMarkers()
        )
        let mergedRecordsClearedAt = mergedStudyRecordsClearedAt(
            remote: snapshot.studyRecordsClearedAt,
            local: settingsStore.loadStudyRecordsClearedAt()
        )
        let mergedRecords = mergedStudyRecords(
            remote: snapshot.studyRecords,
            local: localStudyRecords,
            deletedMarkers: mergedDeletedMarkers,
            recordsClearedAt: mergedRecordsClearedAt,
            maxCount: max(
                sanitizedSettings.sanitizedMaxHistoryCount,
                settings.sanitizedMaxHistoryCount
            )
        )
        let mergedHistory = mergedQuestionHistory(
            remote: snapshot.questionHistory,
            local: localQuestionHistory
        )
        let appliedCurrentQuestion: QuestionItem?
        let appliedLastAnswer: String
        let appliedGradingResult: GradingResult?

        settings = sanitizedSettings
        draftSettings = sanitizedSettings
        if let syncedAPIKey {
            apiKey = syncedAPIKey
            draftAPIKey = syncedAPIKey
            savedAPIKey = syncedAPIKey
            settingsStore.saveAPIKey(syncedAPIKey)
            hasAPIKeyError = false
            if errorMessage == strings.apiKeyEmptyDetailed || errorMessage == strings.apiKeyInvalidDetailed {
                errorMessage = nil
            }
            if previousAPIKey != syncedAPIKey {
                log(.info, "iCloud에서 OpenAI API 키를 불러왔습니다.")
            }
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
            let snapshotQuestion = snapshot.currentQuestion
            if let snapshotQuestion,
               mergedRecords.contains(where: { studyRecordMatches($0, question: snapshotQuestion) }) {
                appliedCurrentQuestion = snapshotQuestion
                appliedLastAnswer = snapshot.lastAnswer
                appliedGradingResult = snapshot.gradingResult
            } else {
                appliedCurrentQuestion = nil
                appliedLastAnswer = ""
                appliedGradingResult = nil
            }
        }

        currentQuestion = appliedCurrentQuestion
        lastAnswer = appliedLastAnswer
        gradingResult = appliedGradingResult
        isRunning = snapshot.isRunning
        hasCompletedOnboarding = mergedHasCompletedOnboarding
        isCloudSyncEnabled = preservedCloudSyncEnabled

        settingsStore.saveSettings(sanitizedSettings)
        settingsStore.saveQuestion(appliedCurrentQuestion)
        settingsStore.saveQuestionHistory(mergedHistory)
        settingsStore.saveLastAnswer(appliedLastAnswer)
        settingsStore.saveGradingResult(appliedGradingResult)
        settingsStore.saveIsRunning(snapshot.isRunning)
        settingsStore.saveHasCompletedOnboarding(mergedHasCompletedOnboarding)
        settingsStore.saveIsCloudSyncEnabled(preservedCloudSyncEnabled)
        settingsStore.saveDeletedStudyRecordMarkers(mergedDeletedMarkers)
        settingsStore.saveStudyRecordsClearedAt(mergedRecordsClearedAt)
        settingsStore.replaceStudyRecords(mergedRecords)
        settingsStore.saveCloudSyncSnapshotUpdatedAt(snapshot.updatedAt)

        studyRecords = settingsStore.loadStudyRecords()
        savedSettings = sanitizedSettings
        cloudLastSyncedAt = snapshot.updatedAt
        restartTimer()
    }

    private func shouldPreserveActiveQuestion(whenApplying snapshot: CloudSyncSnapshot) -> Bool {
        guard let currentQuestion else {
            return false
        }

        if let remoteCurrentQuestion = snapshot.currentQuestion,
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
            maxHistoryCount: settings.sanitizedMaxHistoryCount
        )
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
        if let clientError = error as? OpenAIClientError {
            switch clientError {
            case .missingAPIKey:
                return true
            case .httpError(let status, let body):
                let lowercasedBody = body.lowercased()
                return status == 401 ||
                    status == 403 ||
                    lowercasedBody.contains("invalid api key") ||
                    lowercasedBody.contains("incorrect api key") ||
                    lowercasedBody.contains("unauthorized")
            default:
                return false
            }
        }

        if let backendError = error as? RemotePushBackendError {
            switch backendError {
            case .httpStatus(let status, let body):
                let lowercasedBody = body.lowercased()
                return status == 401 ||
                    status == 403 ||
                    lowercasedBody.contains("api key") ||
                    lowercasedBody.contains("unauthorized")
            case .invalidResponse:
                return false
            }
        }

        return false
    }

    nonisolated private static func trimmedOptional(_ value: String) -> String? {
        let trimmedValue = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmedValue.isEmpty ? nil : trimmedValue
    }
}
