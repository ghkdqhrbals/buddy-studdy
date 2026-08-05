import XCTest
@testable import StudyMate

final class BillingLocalizationTests: XCTestCase {
    func testMembershipAndBillingLabelsAreLocalizedInJapanese() {
        let strings = AppStrings(language: .japanese)

        XCTAssertEqual(strings.membershipAndBilling, "メンバーシップと支払い")
        XCTAssertEqual(strings.membershipManagement, "メンバーシップ管理")
        XCTAssertEqual(strings.membershipTierName("TIER1"), "ティア1")
        XCTAssertEqual(strings.membershipTierName("TIER2"), "ティア2")
        XCTAssertEqual(AppStrings(language: .korean).membershipTierName("TIER1"), "티어 1")
        XCTAssertEqual(AppStrings(language: .english).membershipTierName("TIER1"), "Tier 1")
        XCTAssertEqual(strings.monthlyQuestionAllowanceText(300), "毎月300問")
        XCTAssertEqual(AppStrings(language: .korean).monthlyQuestionAllowanceText(300), "매월 질문 300개")
        XCTAssertEqual(AppStrings(language: .english).monthlyQuestionAllowanceText(300), "300 questions each month")
    }

    func testMembershipCatalogOnlyOffersMonthlyProducts() {
        let products = [
            BackendBillingTierProduct(
                tierCode: "TIER2",
                description: "Monthly",
                monthlyQuestionLimit: 300,
                productId: "tier2.monthly",
                productType: "AUTO_RENEWABLE_SUBSCRIPTION",
                billingPeriod: "P1M",
                sortOrder: 20
            ),
            BackendBillingTierProduct(
                tierCode: "TIER2",
                description: "Legacy annual",
                monthlyQuestionLimit: 300,
                productId: "tier2.yearly",
                productType: "AUTO_RENEWABLE_SUBSCRIPTION",
                billingPeriod: "P1Y",
                sortOrder: 21
            ),
            BackendBillingTierProduct(
                tierCode: "TIER3",
                description: "Monthly",
                monthlyQuestionLimit: 1_000,
                productId: "tier3.monthly",
                productType: "AUTO_RENEWABLE_SUBSCRIPTION",
                billingPeriod: "p1m",
                sortOrder: 30
            ),
        ]

        XCTAssertEqual(
            MembershipProductPolicy.monthlyProducts(products).map(\.productId),
            ["tier2.monthly", "tier3.monthly"]
        )
    }

    func testMembershipActionDistinguishesCurrentChangeAndDowngrade() {
        XCTAssertEqual(
            MembershipPlanActionPolicy.resolve(
                activeProductID: nil,
                activeMonthlyLimit: nil,
                selectedProductID: "tier2.monthly",
                selectedMonthlyLimit: 300
            ),
            .subscribe
        )
        XCTAssertEqual(
            MembershipPlanActionPolicy.resolve(
                activeProductID: "tier2.monthly",
                activeMonthlyLimit: 300,
                selectedProductID: "tier2.monthly",
                selectedMonthlyLimit: 300
            ),
            .current
        )
        XCTAssertEqual(
            MembershipPlanActionPolicy.resolve(
                activeProductID: "tier2.monthly",
                activeMonthlyLimit: 300,
                selectedProductID: "tier3.monthly",
                selectedMonthlyLimit: 1_000
            ),
            .change
        )
        XCTAssertEqual(
            MembershipPlanActionPolicy.resolve(
                activeProductID: "tier3.monthly",
                activeMonthlyLimit: 1_000,
                selectedProductID: "tier2.monthly",
                selectedMonthlyLimit: 300
            ),
            .downgrade
        )
    }

    func testBackendBillingStatusDecodesAuthoritativeEntitlementAndQuota() throws {
        let payload = """
        {
          "tierCode": "TIER2",
          "source": "APP_STORE",
          "accessStatus": "ACTIVE",
          "renewalStatus": "CANCELED",
          "productId": "io.github.ghkdqhrbals.StudyMate.tier2.monthly",
          "startedAt": "2026-08-01T00:00:00Z",
          "expiresAt": "2026-09-01T00:00:00Z",
          "willRenew": false,
          "pendingChange": null,
          "synchronizedAt": "2026-08-05T00:00:00Z",
          "quota": {
            "periodStartedAt": "2026-08-01T00:00:00Z",
            "resetAt": "2026-09-01T00:00:00Z",
            "anchorType": "FIRST_PAID",
            "baseLimit": 300,
            "bonusLimit": 20,
            "usedCount": 41,
            "reservedCount": 1,
            "remainingCount": 278,
            "policyVersion": 2
          }
        }
        """.data(using: .utf8)!

        let status = try RemotePushBackendClient.makeDecoder().decode(BackendBillingStatus.self, from: payload)

        XCTAssertEqual(status.tierCode, "TIER2")
        XCTAssertTrue(status.isEntitlementActive)
        XCTAssertFalse(status.willRenew)
        XCTAssertEqual(status.renewalStatus, "CANCELED")
        XCTAssertEqual(status.quota.usedCount, 41)
        XCTAssertEqual(status.quota.reservedCount, 1)
        XCTAssertEqual(status.quota.remainingCount, 278)
        XCTAssertEqual(status.quota.anchorType, "FIRST_PAID")
    }

    func testBackendBillingStatusTreatsGracePeriodAsActiveButExpiredAsInactive() throws {
        func decode(accessStatus: String) throws -> BackendBillingStatus {
            let payload = """
            {
              "tierCode": "TIER2",
              "source": "APP_STORE",
              "accessStatus": "\(accessStatus)",
              "renewalStatus": "BILLING_RETRY",
              "willRenew": true,
              "synchronizedAt": "2026-08-05T00:00:00Z",
              "quota": {
                "periodStartedAt": "2026-08-01T00:00:00Z",
                "resetAt": "2026-09-01T00:00:00Z",
                "anchorType": "FIRST_PAID",
                "baseLimit": 300,
                "bonusLimit": 0,
                "usedCount": 0,
                "reservedCount": 0,
                "remainingCount": 300,
                "policyVersion": 2
              }
            }
            """.data(using: .utf8)!
            return try RemotePushBackendClient.makeDecoder().decode(BackendBillingStatus.self, from: payload)
        }

        XCTAssertTrue(try decode(accessStatus: "GRACE_PERIOD").isEntitlementActive)
        XCTAssertFalse(try decode(accessStatus: "EXPIRED").isEntitlementActive)
    }
}

final class CommunityQuestionResultPresentationTests: XCTestCase {
    func testKeepsValidScoreAndDifficulty() {
        let presentation = CommunityQuestionResultPresentation(score: 94, difficulty: 4)

        XCTAssertEqual(presentation.score, 94)
        XCTAssertEqual(presentation.difficulty, 4)
    }

    func testClampsScoreAndDifficultyToDisplayedRanges() {
        XCTAssertEqual(
            CommunityQuestionResultPresentation(score: 101, difficulty: 0),
            CommunityQuestionResultPresentation(score: 100, difficulty: 1)
        )
        XCTAssertEqual(
            CommunityQuestionResultPresentation(score: -1, difficulty: 11),
            CommunityQuestionResultPresentation(score: 0, difficulty: 10)
        )
    }

    func testCompactResultCopyIncludesScoreAndDifficultyInEveryLanguage() {
        XCTAssertEqual(
            AppStrings(language: .korean).communityQuestionResult(score: 8, difficulty: 2),
            "8점 · 난이도 2"
        )
        XCTAssertEqual(
            AppStrings(language: .english).communityQuestionResult(score: 8, difficulty: 2),
            "8 pts · Difficulty 2"
        )
        XCTAssertEqual(
            AppStrings(language: .japanese).communityQuestionResult(score: 8, difficulty: 2),
            "8点 · 難易度 2"
        )
    }
}

final class CommunityQuestionActionPolicyTests: XCTestCase {
    func testOwnerCanManageWithoutReportingOwnQuestion() {
        let policy = CommunityQuestionActionPolicy(isSignedIn: true, isOwner: true)

        XCTAssertTrue(policy.canManage)
        XCTAssertFalse(policy.canReport)
    }

    func testSignedInViewerCanReportAnotherUsersQuestion() {
        let policy = CommunityQuestionActionPolicy(isSignedIn: true, isOwner: false)

        XCTAssertFalse(policy.canManage)
        XCTAssertTrue(policy.canReport)
    }

    func testGuestOnlyGetsTheOpenAction() {
        let policy = CommunityQuestionActionPolicy(isSignedIn: false, isOwner: false)

        XCTAssertFalse(policy.canManage)
        XCTAssertFalse(policy.canReport)
    }
}

final class MobileHomeRefreshPresentationPolicyTests: XCTestCase {
    func testShowsLoadingOnlyWhenTheInitialContentIsEmpty() {
        XCTAssertTrue(
            MobileHomeRefreshPresentationPolicy.showsInitialLoading(
                hasContent: false,
                isRefreshing: true
            )
        )
        XCTAssertFalse(
            MobileHomeRefreshPresentationPolicy.showsInitialLoading(
                hasContent: true,
                isRefreshing: true
            )
        )
    }

    func testDoesNotShowLoadingWhenThereIsNoRefreshInFlight() {
        XCTAssertFalse(
            MobileHomeRefreshPresentationPolicy.showsInitialLoading(
                hasContent: false,
                isRefreshing: false
            )
        )
    }
}

final class MobileHomeStudyPresentationPolicyTests: XCTestCase {
    func testCachedCategoriesDoNotRenderAsRootStudiesWithoutBackendRooms() {
        let root = StudyCategory(id: "11", title: "Message Queue")
        let child = StudyCategory(id: "12", title: "Retry and Dead Letter Queue")

        XCTAssertEqual(
            StudyRoomDisplayPolicy.rootCategories(
                from: [root, child],
                rooms: []
            ),
            []
        )
    }

    func testOnlyBackendRootRoomsRenderAsRootStudies() {
        let root = StudyCategory(id: "11", title: "Message Queue")
        let child = StudyCategory(id: "12", title: "Retry and Dead Letter Queue")
        let rooms = [
            backendRoom(id: 11, topic: root.title, parentStudyId: nil),
            backendRoom(id: 12, topic: child.title, parentStudyId: 11),
        ]

        XCTAssertEqual(
            StudyRoomDisplayPolicy.rootCategories(
                from: [root, child],
                rooms: rooms
            ),
            [root]
        )
    }

    func testStudyPresentationShowsLoadingFailureAndContentWithoutFlatFallback() {
        XCTAssertEqual(
            MobileHomeStudyPresentationPolicy.resolve(
                hasContent: false,
                loadState: .idle
            ),
            .loading
        )
        XCTAssertEqual(
            MobileHomeStudyPresentationPolicy.resolve(
                hasContent: false,
                loadState: .failed
            ),
            .loadFailure
        )
        XCTAssertEqual(
            MobileHomeStudyPresentationPolicy.resolve(
                hasContent: true,
                loadState: .failed
            ),
            .content
        )
    }

    func testStudyLoadFailureCopyIsLocalizedForEverySupportedLanguage() {
        XCTAssertEqual(AppStrings(language: .korean).unableToLoadStudies, "학습을 불러오지 못했습니다")
        XCTAssertEqual(AppStrings(language: .english).unableToLoadStudies, "Couldn’t load your studies")
        XCTAssertEqual(AppStrings(language: .japanese).unableToLoadStudies, "学習を読み込めませんでした")
    }

    private func backendRoom(
        id: Int,
        topic: String,
        parentStudyId: Int?
    ) -> BackendStudyRoom {
        BackendStudyRoom(
            id: id,
            topic: topic,
            parentStudyId: parentStudyId,
            sortOrder: 0,
            difficultyLevel: 5,
            intervalMinutes: 60,
            enabled: true,
            activeForQuestions: true,
            notificationSound: "default",
            customPrompt: "",
            openAIModel: StudySettings.defaultOpenAIModel,
            maxHistoryCount: 100,
            nextDueAt: nil,
            lastSentAt: nil,
            lastError: nil,
            pendingQuestion: nil,
            createdAt: Date(timeIntervalSince1970: 1),
            updatedAt: Date(timeIntervalSince1970: 1)
        )
    }
}

final class PageAccessPolicyTests: XCTestCase {
    @MainActor
    func testNotificationStudyListRouteUsesExistingHomeMyStudiesScreen() {
        let suiteName = "StudyMateiOSTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }

        let appState = AppState(settingsStore: SettingsStore(defaults: defaults))

        XCTAssertTrue(appState.openRouteFromNotification(.studyList))

        XCTAssertEqual(appState.mobileVisibleTab, .home)
        XCTAssertEqual(appState.appRouteRequest?.route, .studyList)
        XCTAssertEqual(appState.appRouteRequest?.presentation, .direct)
        XCTAssertNil(appState.homeStudyRoute)
    }

    @MainActor
    func testNotificationHomeRouteDoesNotCreateNestedStudyListDestination() {
        let suiteName = "StudyMateiOSTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }

        let appState = AppState(settingsStore: SettingsStore(defaults: defaults))

        XCTAssertTrue(appState.openRouteFromNotification(.home))

        XCTAssertEqual(appState.mobileVisibleTab, .home)
        XCTAssertNil(appState.appRouteRequest)
        XCTAssertNil(appState.homeStudyRoute)
    }

    func testAppLanguageResolvesSupportedPreferredLanguagesInOrder() {
        XCTAssertEqual(AppLanguage.preferred(from: ["ko-KR"]), .korean)
        XCTAssertEqual(AppLanguage.preferred(from: ["en-GB"]), .english)
        XCTAssertEqual(AppLanguage.preferred(from: ["ja-JP"]), .japanese)
        XCTAssertEqual(AppLanguage.preferred(from: ["fr-FR", "ja-JP"]), .japanese)
        XCTAssertEqual(AppLanguage.preferred(from: ["zh-Hans"]), .english)
    }

    func testFreshInstallPersistsSystemPreferredAppLanguage() {
        let suiteName = "StudyMateiOSTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }

        let firstStore = SettingsStore(
            defaults: defaults,
            preferredAppLanguageProvider: { .japanese }
        )
        let initialSettings = firstStore.loadSettings()

        XCTAssertEqual(initialSettings.appLanguage, .japanese)
        XCTAssertEqual(initialSettings.language, .japanese)
        XCTAssertEqual(initialSettings.topic, StudySettings.fallbackTopicJapanese)

        let relaunchedStore = SettingsStore(
            defaults: defaults,
            preferredAppLanguageProvider: { .english }
        )
        XCTAssertEqual(relaunchedStore.loadSettings().appLanguage, .japanese)
    }

    func testSavedAppLanguageOverridesCurrentSystemPreference() {
        let suiteName = "StudyMateiOSTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }

        let store = SettingsStore(
            defaults: defaults,
            preferredAppLanguageProvider: { .japanese }
        )
        store.saveSettings(.initial(for: .korean))

        let relaunchedStore = SettingsStore(
            defaults: defaults,
            preferredAppLanguageProvider: { .english }
        )
        XCTAssertEqual(relaunchedStore.loadSettings().appLanguage, .korean)
    }

    @MainActor
    func testViewIndependentLoadFinishesAfterCallingTaskIsCancelled() async {
        var didFinish = false

        let callingTask = Task { @MainActor in
            await AppActionRunner().runViewIndependent {
                try? await Task.sleep(nanoseconds: 20_000_000)
                didFinish = true
            }
        }

        await Task.yield()
        callingTask.cancel()
        await callingTask.value

        XCTAssertTrue(didFinish)
    }

    func testLoginGateUsesAuthoritativeSignedInState() {
        XCTAssertFalse(
            PageAccessPolicy.shouldShowLoginGate(
                for: .statistics,
                isSignedIn: true
            )
        )
        XCTAssertTrue(
            PageAccessPolicy.shouldShowLoginGate(
                for: .records,
                isSignedIn: false
            )
        )
    }

    func testGuestSettingsHideAccountBackedPreferences() {
        XCTAssertFalse(
            SettingsAccessPolicy.canEditAccountBackedPreferences(isSignedIn: false)
        )
        XCTAssertTrue(
            SettingsAccessPolicy.canEditAccountBackedPreferences(isSignedIn: true)
        )
    }

    func testCommunitySessionStateInvalidatesInFlightRequestAfterSignOut() {
        var session = CommunitySessionStateStore(isSignedIn: true)
        let requestSnapshot = session.generation

        XCTAssertTrue(session.isCurrent(requestSnapshot))

        session.signOut()

        XCTAssertFalse(session.isCurrent(requestSnapshot))
        XCTAssertFalse(session.isCurrent(session.generation))

        session.signIn()

        XCTAssertTrue(session.isCurrent(session.generation))
    }

    func testTermsAgreementBackendErrorRoutesToAgreementGate() throws {
        let payload = """
        {
          "error": {
            "errorCode": "TERMS_AGREEMENT_REQUIRED",
            "code": 302,
            "message": "Latest terms agreement is required.",
            "requestId": "request-1",
            "status": 403
          }
        }
        """
        let envelope = try JSONDecoder().decode(
            BackendAPIErrorResponse.self,
            from: Data(payload.utf8)
        )
        let error = RemotePushBackendError.httpStatus(403, payload, envelope.error)

        let resolution = AppErrorHandlingPolicy.resolve(error, fallback: "")

        XCTAssertTrue(resolution.requiresTermsAgreement)
        XCTAssertFalse(resolution.requiresLogin)
        XCTAssertNil(resolution.featureMessage)
    }

    func testTermsAgreementBackendErrorStillRoutesWhenOptionalPayloadCannotDecode() {
        let payload = """
        {
          "error": {
            "errorCode": "TERMS_AGREEMENT_REQUIRED",
            "requiredTerms": [{"unexpected": true}]
          }
        }
        """
        let error = RemotePushBackendError.httpStatus(403, payload, nil)

        let resolution = AppErrorHandlingPolicy.resolve(error, fallback: "")

        XCTAssertTrue(resolution.requiresTermsAgreement)
        XCTAssertFalse(resolution.requiresLogin)
        XCTAssertNil(resolution.featureMessage)
    }

    func testPendingQuestionConflictUsesDedicatedErrorCode() throws {
        let payload = """
        {
          "error": {
            "errorCode": "STUDY_PENDING_QUESTION_EXISTS",
            "code": 501,
            "message": "이 주제에 답변 대기 중인 질문이 있습니다.",
            "requestId": "request-pending",
            "status": 409
          }
        }
        """
        let envelope = try JSONDecoder().decode(
            BackendAPIErrorResponse.self,
            from: Data(payload.utf8)
        )
        let error = RemotePushBackendError.httpStatus(409, payload, envelope.error)

        let resolution = AppErrorHandlingPolicy.resolve(error, fallback: "")

        XCTAssertTrue(resolution.isPendingQuestionConflict)
    }

    func testLegacyValidationErrorStillRecognizesPendingQuestionConflict() throws {
        let payload = """
        {
          "error": {
            "errorCode": "VALIDATION_ERROR",
            "code": 500,
            "message": "요청 값이 올바르지 않습니다.",
            "debugDescription": "A pending question already exists for this study.",
            "requestId": "request-legacy",
            "status": 409
          }
        }
        """
        let envelope = try JSONDecoder().decode(
            BackendAPIErrorResponse.self,
            from: Data(payload.utf8)
        )
        let error = RemotePushBackendError.httpStatus(409, payload, envelope.error)

        let resolution = AppErrorHandlingPolicy.resolve(error, fallback: "")

        XCTAssertTrue(resolution.isPendingQuestionConflict)
    }
}

final class DeveloperAccessPolicyTests: XCTestCase {
    func testHiddenDeveloperUnlockIsLimitedToDebugAndTestFlight() {
        XCTAssertTrue(
            AppDistributionContext(
                isTestFlight: false,
                buildIdentifier: "1.1.0(80)",
                isDebugBuild: true
            ).allowsHiddenDeveloperUnlock
        )
        XCTAssertTrue(
            AppDistributionContext(
                isTestFlight: true,
                buildIdentifier: "1.1.0(80)",
                isDebugBuild: false
            ).allowsHiddenDeveloperUnlock
        )
        XCTAssertFalse(
            AppDistributionContext(
                isTestFlight: false,
                buildIdentifier: "1.1.0(80)",
                isDebugBuild: false
            ).allowsHiddenDeveloperUnlock
        )
    }

    func testFiveRapidVersionTapsUnlockAndExpiredWindowRestarts() {
        var tracker = RapidDeveloperUnlockTapTracker()
        let startedAt = Date(timeIntervalSince1970: 1_000)

        for offset in 0..<4 {
            XCTAssertFalse(
                tracker.registerTap(
                    at: startedAt.addingTimeInterval(Double(offset) * 0.25)
                )
            )
        }
        XCTAssertTrue(tracker.registerTap(at: startedAt.addingTimeInterval(1)))
        XCTAssertFalse(tracker.registerTap(at: startedAt.addingTimeInterval(4)))
        XCTAssertEqual(tracker.tapCount, 1)
    }

    func testDebugOverlayOffsetAccumulatesAndClampsToVisibleBounds() {
        XCTAssertEqual(
            DebugOverlayPositionPolicy.offsetAfterDrag(
                committed: CGSize(width: 12, height: 74),
                translation: CGSize(width: 500, height: -500),
                containerSize: CGSize(width: 390, height: 844),
                panelSize: CGSize(width: 300, height: 64),
                margin: 12
            ),
            CGSize(width: 78, height: 12)
        )
    }

    @MainActor
    func testTestFlightRequiresVersionGestureAgainForEachBuild() async {
        let suiteName = "StudyMateiOSTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }

        let store = SettingsStore(defaults: defaults)
        store.saveDeveloperAccessUnlocked(true)
        store.saveDeveloperAccessBuildIdentifier("1.0.17(48)")
        store.saveIsDebuggingEnabled(true)

        let currentBuild = AppDistributionContext(
            isTestFlight: true,
            buildIdentifier: "1.1.0(57)",
            isDebugBuild: false
        )
        let appState = AppState(
            settingsStore: store,
            appDistributionContext: currentBuild
        )

        XCTAssertFalse(appState.canAccessDeveloperOptions)
        XCTAssertFalse(appState.canShowDebugPopup)
        XCTAssertFalse(appState.isDebuggingEnabled)
        XCTAssertFalse(store.loadIsDeveloperAccessUnlocked())
        XCTAssertFalse(store.loadIsDebuggingEnabled())
        XCTAssertNil(store.loadDeveloperAccessBuildIdentifier())

        let developerAccessUnlocked = appState.unlockDeveloperAccessFromVersionGesture()

        XCTAssertTrue(developerAccessUnlocked)
        XCTAssertTrue(appState.canAccessDeveloperOptions)
        XCTAssertTrue(appState.canShowDebugPopup)
        XCTAssertFalse(appState.isAPIDebugPanelPresented)
        XCTAssertFalse(appState.isDebuggingEnabled)
        XCTAssertEqual(store.loadDeveloperAccessBuildIdentifier(), "1.1.0(57)")

        let restoredSameBuild = AppState(
            settingsStore: store,
            appDistributionContext: currentBuild
        )
        XCTAssertTrue(restoredSameBuild.canAccessDeveloperOptions)
        XCTAssertFalse(restoredSameBuild.isDebuggingEnabled)

        let nextBuild = AppState(
            settingsStore: store,
            appDistributionContext: AppDistributionContext(
                isTestFlight: true,
                buildIdentifier: "1.1.1(58)",
                isDebugBuild: false
            )
        )
        XCTAssertFalse(nextBuild.canAccessDeveloperOptions)
        XCTAssertFalse(nextBuild.canShowDebugPopup)
        XCTAssertFalse(nextBuild.isDebuggingEnabled)
    }

    @MainActor
    func testAppStoreBuildRejectsVersionGestureUnlock() {
        let suiteName = "StudyMateiOSTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }

        let store = SettingsStore(defaults: defaults)
        let appState = AppState(
            settingsStore: store,
            appDistributionContext: AppDistributionContext(
                isTestFlight: false,
                buildIdentifier: "1.1.0(80)",
                isDebugBuild: false
            )
        )

        XCTAssertFalse(appState.unlockDeveloperAccessFromVersionGesture())
        XCTAssertFalse(appState.canAccessDeveloperOptions)
        XCTAssertFalse(store.loadIsDeveloperAccessUnlocked())
        XCTAssertNil(store.loadDeveloperAccessBuildIdentifier())
    }
}

final class NotificationStateStoreTests: XCTestCase {
    @MainActor
    func testMarkAllReadUpdatesEveryLoadedNotificationAndUnreadCount() {
        let readAt = Date(timeIntervalSince1970: 100)
        var store = NotificationStateStore(
            notifications: [
                BackendAppNotification(
                    id: "unread",
                    type: "QUESTION",
                    title: "새 질문",
                    body: "질문 본문",
                    isRead: false,
                    createdAt: Date(timeIntervalSince1970: 1)
                ),
                BackendAppNotification(
                    id: "already-read",
                    type: "QUESTION",
                    title: "읽은 질문",
                    body: "질문 본문",
                    isRead: true,
                    createdAt: Date(timeIntervalSince1970: 2),
                    readAt: Date(timeIntervalSince1970: 50)
                ),
            ],
            unreadCount: 1,
            totalCount: 2
        )

        store.markAllRead(at: readAt)

        XCTAssertEqual(store.unreadCount, 0)
        XCTAssertTrue(store.notifications.allSatisfy(\.isRead))
        XCTAssertEqual(store.notifications[0].readAt, readAt)
        XCTAssertEqual(store.notifications[1].readAt, Date(timeIntervalSince1970: 50))
    }
}

final class StudyRoomStateStoreTests: XCTestCase {
    @MainActor
    func testPendingStudyRecordIsSelectedPerTopicStudy() {
        let suiteName = "StudyMateiOSTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }

        let store = SettingsStore(defaults: defaults)
        let swiftCategory = StudyCategory(id: "11", title: "Swift", difficulty: .level5)
        let kotlinCategory = StudyCategory(id: "12", title: "Kotlin", difficulty: .level6)
        store.saveSettings(
            StudySettings(
                topic: swiftCategory.title,
                difficulty: swiftCategory.difficulty,
                customPrompt: "",
                intervalMinutes: 30,
                studyCategories: [swiftCategory, kotlinCategory],
                selectedStudyCategoryID: swiftCategory.id
            )
        )
        store.replaceStudyRecords([
            StudyRecord(
                id: "swift-question",
                studyID: 11,
                question: QuestionItem(
                    question: "What is actor isolation?",
                    expectedAnswerHint: nil,
                    createdAt: Date(timeIntervalSince1970: 11)
                ),
                topic: swiftCategory.title,
                difficulty: swiftCategory.difficulty
            ),
            StudyRecord(
                id: "kotlin-question",
                studyID: 12,
                question: QuestionItem(
                    question: "What is structured concurrency?",
                    expectedAnswerHint: nil,
                    createdAt: Date(timeIntervalSince1970: 12)
                ),
                topic: kotlinCategory.title,
                difficulty: kotlinCategory.difficulty
            )
        ])
        let appState = AppState(settingsStore: store)

        XCTAssertEqual(appState.pendingStudyRecord(categoryID: swiftCategory.id)?.id, "swift-question")
        XCTAssertEqual(appState.pendingStudyRecord(categoryID: kotlinCategory.id)?.id, "kotlin-question")
    }

    func testBackendPendingQuestionCanBeClearedWithoutLocalRecordCacheEntry() {
        let record = StudyRecord(
            id: "record-42",
            question: QuestionItem(
                question: "What does SKIP LOCKED do?",
                expectedAnswerHint: nil,
                createdAt: Date(timeIntervalSince1970: 42)
            ),
            topic: "Database",
            difficulty: .intermediate
        )
        let room = BackendStudyRoom(
            id: 19,
            topic: "Database",
            difficultyLevel: 5,
            intervalMinutes: 30,
            enabled: true,
            notificationSound: nil,
            customPrompt: "",
            openAIModel: "gpt-5.4",
            maxHistoryCount: 100,
            nextDueAt: nil,
            lastSentAt: nil,
            lastError: nil,
            pendingQuestion: record,
            createdAt: Date(timeIntervalSince1970: 1),
            updatedAt: Date(timeIntervalSince1970: 1)
        )
        var state = StudyRoomStateStore()
        state.replace(with: [room])

        XCTAssertTrue(state.containsPendingQuestion(recordID: record.id))

        state.clearPendingQuestion(recordID: record.id)

        XCTAssertFalse(state.containsPendingQuestion(recordID: record.id))
        XCTAssertEqual(state.pendingQuestionCount, 0)
    }

    func testPendingQuestionCountUsesStudyIDWhenTopicsAreEqual() {
        let pendingRecord = StudyRecord(
            id: "record-11",
            studyID: 11,
            question: QuestionItem(
                question: "Root question",
                expectedAnswerHint: nil,
                createdAt: Date(timeIntervalSince1970: 11)
            ),
            topic: "Redis",
            difficulty: .intermediate
        )
        var state = StudyRoomStateStore()
        state.replace(with: [
            backendRoom(id: 11, topic: "Redis", pendingQuestion: pendingRecord),
            backendRoom(id: 12, topic: "Redis", pendingQuestion: nil)
        ])

        XCTAssertEqual(
            state.pendingQuestionCount(for: StudyCategory(id: "11", title: "Redis")),
            1
        )
        XCTAssertEqual(
            state.pendingQuestionCount(for: StudyCategory(id: "12", title: "Redis")),
            0
        )
    }

    func testIncomingRecordOnlyUpdatesItsStudyID() {
        let record = StudyRecord(
            id: "record-12",
            studyID: 12,
            question: QuestionItem(
                question: "Child question",
                expectedAnswerHint: nil,
                createdAt: Date(timeIntervalSince1970: 12)
            ),
            topic: "Redis",
            difficulty: .intermediate
        )
        var state = StudyRoomStateStore()
        state.replace(with: [
            backendRoom(id: 11, topic: "Redis", pendingQuestion: nil),
            backendRoom(id: 12, topic: "Redis", pendingQuestion: nil)
        ])

        XCTAssertTrue(state.applyIncomingRecord(record))
        XCTAssertNil(state.rooms.first(where: { $0.id == 11 })?.pendingQuestion)
        XCTAssertEqual(state.rooms.first(where: { $0.id == 12 })?.pendingQuestion?.id, record.id)
    }

    @MainActor
    func testPendingLimitDoesNotFallBackFromRootToChildStudy() {
        let suiteName = "StudyMateiOSTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }

        let store = SettingsStore(defaults: defaults)
        let root = StudyCategory(id: "11", title: "Redis", difficulty: .level5)
        let child = StudyCategory(id: "12", title: "Redis Streams", difficulty: .level5)
        store.saveSettings(
            StudySettings(
                topic: child.title,
                difficulty: root.difficulty,
                customPrompt: "",
                intervalMinutes: 30,
                studyCategories: [root, child],
                selectedStudyCategoryID: root.id
            )
        )
        store.replaceStudyRecords([
            StudyRecord(
                id: "root-question",
                studyID: 11,
                question: QuestionItem(
                    question: "Root question",
                    expectedAnswerHint: nil,
                    createdAt: Date(timeIntervalSince1970: 11)
                ),
                topic: root.title,
                difficulty: root.difficulty
            )
        ])
        let appState = AppState(settingsStore: store)

        XCTAssertTrue(appState.hasReachedPendingQuestionLimit(categoryID: root.id))
        XCTAssertFalse(appState.hasReachedPendingQuestionLimit(categoryID: child.id))
        XCTAssertNil(appState.pendingStudyRecord(categoryID: child.id))
    }

    private func backendRoom(
        id: Int,
        topic: String,
        pendingQuestion: StudyRecord?
    ) -> BackendStudyRoom {
        BackendStudyRoom(
            id: id,
            topic: topic,
            difficultyLevel: 5,
            intervalMinutes: 30,
            enabled: true,
            notificationSound: nil,
            customPrompt: "",
            openAIModel: "gpt-5.4",
            maxHistoryCount: 100,
            nextDueAt: nil,
            lastSentAt: nil,
            lastError: nil,
            pendingQuestion: pendingQuestion,
            createdAt: Date(timeIntervalSince1970: 1),
            updatedAt: Date(timeIntervalSince1970: 1)
        )
    }
}

final class StudyTreeViewportPersistenceTests: XCTestCase {
    func testViewportPersistsPerRootStudyAndSanitizesValues() {
        let suiteName = "StudyMateiOSTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }

        let store = SettingsStore(defaults: defaults)
        XCTAssertFalse(store.hasStudyTreeViewport(rootStudyID: 7))
        store.saveStudyTreeViewport(
            StudyTreeViewportState(
                zoomScale: 1.45,
                contentOffsetX: 180,
                contentOffsetY: 96,
                canvasAlignmentX: 24,
                canvasAlignmentY: 80
            ),
            rootStudyID: 7
        )
        XCTAssertTrue(store.hasStudyTreeViewport(rootStudyID: 7))

        XCTAssertEqual(
            store.loadStudyTreeViewport(rootStudyID: 7),
            StudyTreeViewportState(
                zoomScale: 1.45,
                contentOffsetX: 180,
                contentOffsetY: 96,
                canvasAlignmentX: 24,
                canvasAlignmentY: 80
            )
        )
        XCTAssertEqual(store.loadStudyTreeViewport(rootStudyID: 8), .default)

        store.saveStudyTreeViewport(
            StudyTreeViewportState(
                zoomScale: 4,
                contentOffsetX: -20,
                contentOffsetY: .infinity,
                canvasAlignmentX: -.infinity,
                canvasAlignmentY: .infinity
            ),
            rootStudyID: 9
        )
        XCTAssertEqual(
            store.loadStudyTreeViewport(rootStudyID: 9),
            StudyTreeViewportState(
                zoomScale: 1.8,
                contentOffsetX: 0,
                contentOffsetY: 0,
                canvasAlignmentX: 0,
                canvasAlignmentY: 0
            )
        )
    }
}

final class StudyTreeLayoutPolicyTests: XCTestCase {
    func testNodeLevelProgressUsesClampedTenPointScale() {
        XCTAssertEqual(StudyTreeNodeStylePolicy.levelFillFraction(1), 0.1)
        XCTAssertEqual(StudyTreeNodeStylePolicy.levelFillFraction(5), 0.5)
        XCTAssertEqual(StudyTreeNodeStylePolicy.levelFillFraction(10), 1)
        XCTAssertEqual(StudyTreeNodeStylePolicy.levelText(0), "1/10")
        XCTAssertEqual(StudyTreeNodeStylePolicy.levelText(3), "3/10")
        XCTAssertEqual(StudyTreeNodeStylePolicy.levelText(11), "10/10")
    }

    func testCanvasExpandsToIncludeMovedNodesAndRecoversInvalidValues() {
        let baseCenters = [7: CGPoint(x: 100, y: 100)]

        XCTAssertEqual(
            StudyTreeCanvasPolicy.expandedLayout(
                baseCenters: baseCenters,
                nodeOffsets: [7: CGSize(width: 1_000, height: -1_000)],
                baseCanvasSize: CGSize(width: 320, height: 320),
                nodeSize: CGSize(width: 112, height: 112)
            ),
            StudyTreeCanvasLayout(
                size: CGSize(width: 1_164, height: 1_284),
                translation: CGSize(width: 0, height: 964)
            )
        )
        XCTAssertEqual(
            StudyTreeCanvasPolicy.expandedLayout(
                baseCenters: baseCenters,
                nodeOffsets: [7: CGSize(width: 100_000, height: 100_000)],
                baseCanvasSize: CGSize(width: 320, height: 320),
                nodeSize: CGSize(width: 112, height: 112)
            ),
            StudyTreeCanvasLayout(
                size: CGSize(width: 100_164, height: 100_164),
                translation: .zero
            )
        )
        XCTAssertEqual(
            StudyTreeCanvasPolicy.sanitizedOffset(
                CGSize(width: CGFloat.infinity, height: CGFloat.nan)
            ),
            .zero
        )
    }

    func testInitialFitStopsAsSoonAsTheUserMovesTheTree() {
        let viewportSize = CGSize(width: 390, height: 700)

        XCTAssertTrue(
            StudyTreeViewportPolicy.shouldApplyInitialFit(
                isRequested: true,
                hasApplied: false,
                hasUserInteracted: false,
                hasFinishedRefresh: true,
                viewportSize: viewportSize
            )
        )
        XCTAssertFalse(
            StudyTreeViewportPolicy.shouldApplyInitialFit(
                isRequested: true,
                hasApplied: false,
                hasUserInteracted: true,
                hasFinishedRefresh: true,
                viewportSize: viewportSize
            )
        )
    }

    func testScrollOffsetPreservesSafeAreaInsetAtLogicalOrigin() {
        let leadingInset = CGSize(width: 0, height: 116)

        XCTAssertEqual(
            StudyTreeViewportPolicy.normalizedContentOffset(
                rawContentOffset: CGPoint(x: 0, y: -116),
                leadingInset: leadingInset
            ),
            .zero
        )
        XCTAssertEqual(
            StudyTreeViewportPolicy.rawContentOffset(
                normalizedContentOffset: .zero,
                leadingInset: leadingInset
            ),
            CGPoint(x: 0, y: -116)
        )
        XCTAssertEqual(
            StudyTreeViewportPolicy.maximumNormalizedContentOffset(
                contentSize: CGSize(width: 390, height: 800),
                viewportSize: CGSize(width: 390, height: 700),
                totalInset: CGSize(width: 0, height: 116)
            ),
            CGPoint(x: 0, y: 216)
        )
    }

    func testNewNodesMoveAsideFromExistingNodesAtTheSameLevel() {
        let baseCenters = [
            1: CGPoint(x: 100, y: 100),
            2: CGPoint(x: 254, y: 100),
            3: CGPoint(x: 408, y: 100),
            4: CGPoint(x: 408, y: 286)
        ]
        let offsets = StudyTreeCanvasPolicy.offsetsPlacingNewNodesWithoutSameLevelOverlap(
            newRoomIDs: [3, 4],
            baseCenters: baseCenters,
            nodeOffsets: [1: CGSize(width: 308, height: 0)],
            nodeSize: CGSize(width: 112, height: 112)
        )

        XCTAssertEqual(offsets[3], CGSize(width: 128, height: 0))
        XCTAssertEqual(offsets[4], .zero)
        XCTAssertEqual(offsets[1], CGSize(width: 308, height: 0))
    }

    func testTreeEdgesPointFromParentTowardChild() throws {
        let geometry = try XCTUnwrap(
            StudyTreeEdgePolicy.directionalGeometry(
                parent: CGPoint(x: 100, y: 100),
                child: CGPoint(x: 100, y: 286),
                nodeRadius: 60
            )
        )

        XCTAssertEqual(geometry.start, CGPoint(x: 100, y: 160))
        XCTAssertEqual(geometry.end, CGPoint(x: 100, y: 226))
        XCTAssertEqual(geometry.arrowLeft, CGPoint(x: 95, y: 216))
        XCTAssertEqual(geometry.arrowRight, CGPoint(x: 105, y: 216))
    }

    func testPixelTreeEdgesUseAStableOrthogonalBranch() throws {
        let geometry = try XCTUnwrap(
            StudyTreeEdgePolicy.steppedGeometry(
                start: CGPoint(x: 100, y: 160),
                end: CGPoint(x: 254, y: 226)
            )
        )

        XCTAssertEqual(
            geometry,
            StudyTreeSteppedEdgeGeometry(
                start: CGPoint(x: 100, y: 160),
                parentCorner: CGPoint(x: 100, y: 193),
                childCorner: CGPoint(x: 254, y: 193),
                end: CGPoint(x: 254, y: 226)
            )
        )
        XCTAssertNil(
            StudyTreeEdgePolicy.steppedGeometry(
                start: CGPoint(x: 12, y: 12),
                end: CGPoint(x: 12, y: 12)
            )
        )
    }

    func testStudySubtreeDeletesChildrenBeforeTheirParent() {
        let parentByRoomID = [
            2: 1,
            3: 2,
            4: 1,
            9: 8
        ]
        let subtree = StudyTreeDeletionPolicy.subtreeIDs(
            rootIDs: [1],
            parentByRoomID: parentByRoomID
        )

        XCTAssertEqual(subtree, [1, 2, 3, 4])
        XCTAssertEqual(
            StudyTreeDeletionPolicy.childFirstDeletionOrder(
                studyIDs: subtree,
                parentByRoomID: parentByRoomID
            ),
            [3, 2, 4, 1]
        )
    }

    func testInitialZoomFitsEntireCanvasWithoutEnlargingSmallTrees() {
        XCTAssertEqual(
            StudyTreeViewportPolicy.fittedZoomScale(
                canvasSize: CGSize(width: 1_000, height: 500),
                viewportSize: CGSize(width: 400, height: 300),
                padding: 20
            ),
            0.36,
            accuracy: 0.0001
        )
        XCTAssertEqual(
            StudyTreeViewportPolicy.fittedZoomScale(
                canvasSize: CGSize(width: 200, height: 200),
                viewportSize: CGSize(width: 400, height: 500)
            ),
            1
        )
    }

    func testZoomKeepsGestureAnchorStationary() {
        XCTAssertEqual(
            StudyTreeViewportPolicy.contentOffsetPreservingAnchor(
                startOffset: CGPoint(x: 100, y: 50),
                anchor: CGPoint(x: 200, y: 300),
                canvasSize: CGSize(width: 1_000, height: 800),
                viewportSize: CGSize(width: 400, height: 600),
                startAlignmentInset: .zero,
                targetAlignmentInset: .zero,
                startScale: 1,
                targetScale: 2
            ),
            CGPoint(x: 400, y: 400)
        )
        XCTAssertEqual(
            StudyTreeViewportPolicy.contentOffsetPreservingAnchor(
                startOffset: .zero,
                anchor: CGPoint(x: 200, y: 300),
                canvasSize: CGSize(width: 1_000, height: 1_000),
                viewportSize: CGSize(width: 400, height: 600),
                startAlignmentInset: .zero,
                targetAlignmentInset: .zero,
                startScale: 1,
                targetScale: 0.5
            ),
            .zero
        )
    }

    func testZoomCrossingViewportBoundaryKeepsCenteredCanvasStable() {
        let canvasSize = CGSize(width: 200, height: 200)
        let viewportSize = CGSize(width: 400, height: 600)
        let viewportCenter = CGPoint(x: 200, y: 300)
        let centeredInset = StudyTreeViewportPolicy.centeredCanvasAlignmentInset(
            canvasSize: canvasSize,
            viewportSize: viewportSize,
            zoomScale: 1
        )

        let zoomedInOffset = StudyTreeViewportPolicy.contentOffsetPreservingAnchor(
            startOffset: .zero,
            anchor: viewportCenter,
            canvasSize: canvasSize,
            viewportSize: viewportSize,
            startAlignmentInset: centeredInset,
            targetAlignmentInset: .zero,
            startScale: 1,
            targetScale: 3
        )
        XCTAssertEqual(zoomedInOffset, CGPoint(x: 100, y: 0))

        XCTAssertEqual(
            StudyTreeViewportPolicy.contentOffsetPreservingAnchor(
                startOffset: zoomedInOffset,
                anchor: viewportCenter,
                canvasSize: canvasSize,
                viewportSize: viewportSize,
                startAlignmentInset: .zero,
                targetAlignmentInset: centeredInset,
                startScale: 3,
                targetScale: 1
            ),
            .zero
        )
    }

    func testFiveHundredDragUpdatesNeverTeleportTheTree() {
        let baseCenters = [
            1: CGPoint(x: 100, y: 100),
            2: CGPoint(x: 260, y: 100)
        ]
        let baseCanvasSize = CGSize(width: 320, height: 320)
        let nodeSize = CGSize(width: 112, height: 112)
        let zoomScale: CGFloat = 0.75
        let fixedAlignmentInset = CGSize(width: 35, height: 90)
        let startViewportOffset = CGPoint(x: 40, y: 30)
        let startLayout = StudyTreeCanvasPolicy.expandedLayout(
            baseCenters: baseCenters,
            nodeOffsets: [:],
            baseCanvasSize: baseCanvasSize,
            nodeSize: nodeSize
        )
        let stationaryNodeStart = CGPoint(
            x: (baseCenters[2]!.x + startLayout.translation.width) * zoomScale
                + fixedAlignmentInset.width
                - startViewportOffset.x,
            y: (baseCenters[2]!.y + startLayout.translation.height) * zoomScale
                + fixedAlignmentInset.height
                - startViewportOffset.y
        )
        let draggedNodeStart = CGPoint(
            x: (baseCenters[1]!.x + startLayout.translation.width) * zoomScale
                + fixedAlignmentInset.width
                - startViewportOffset.x,
            y: (baseCenters[1]!.y + startLayout.translation.height) * zoomScale
                + fixedAlignmentInset.height
                - startViewportOffset.y
        )

        for step in 0...500 {
            let progress = CGFloat(step) / 500
            let triangularProgress = progress <= 0.5
                ? progress * 2
                : (1 - progress) * 2
            let draggedOffset = CGSize(
                width: -1_200 * triangularProgress,
                height: -800 * triangularProgress
            )
            let layout = StudyTreeCanvasPolicy.expandedLayout(
                baseCenters: baseCenters,
                nodeOffsets: [1: draggedOffset],
                baseCanvasSize: baseCanvasSize,
                nodeSize: nodeSize
            )
            let compensation =
                StudyTreeViewportPolicy.compensationPreservingCanvasTranslation(
                    startOffset: startViewportOffset,
                    startAlignmentInset: fixedAlignmentInset,
                    startCanvasTranslation: startLayout.translation,
                    targetCanvasTranslation: layout.translation,
                    zoomScale: zoomScale
                )
            let viewportOffset = compensation.viewportOffset
            let alignmentInset = compensation.alignmentInset
            let stationaryNode = CGPoint(
                x: (baseCenters[2]!.x + layout.translation.width) * zoomScale
                    + alignmentInset.width
                    - viewportOffset.x,
                y: (baseCenters[2]!.y + layout.translation.height) * zoomScale
                    + alignmentInset.height
                    - viewportOffset.y
            )
            let draggedNode = CGPoint(
                x: (
                    baseCenters[1]!.x
                        + draggedOffset.width
                        + layout.translation.width
                ) * zoomScale
                    + alignmentInset.width
                    - viewportOffset.x,
                y: (
                    baseCenters[1]!.y
                        + draggedOffset.height
                        + layout.translation.height
                ) * zoomScale
                    + alignmentInset.height
                    - viewportOffset.y
            )

            XCTAssertEqual(stationaryNode.x, stationaryNodeStart.x, accuracy: 0.0001)
            XCTAssertEqual(stationaryNode.y, stationaryNodeStart.y, accuracy: 0.0001)
            XCTAssertEqual(
                draggedNode.x,
                draggedNodeStart.x + draggedOffset.width * zoomScale,
                accuracy: 0.0001
            )
            XCTAssertEqual(
                draggedNode.y,
                draggedNodeStart.y + draggedOffset.height * zoomScale,
                accuracy: 0.0001
            )
        }
    }

    func testFiveHundredInwardDragUpdatesNeverTeleportTheTree() {
        let baseCenters = [
            1: CGPoint(x: 100, y: 100),
            2: CGPoint(x: 260, y: 100)
        ]
        let initialDraggedOffset = CGSize(width: -1_200, height: -800)
        let baseCanvasSize = CGSize(width: 320, height: 320)
        let nodeSize = CGSize(width: 112, height: 112)
        let zoomScale: CGFloat = 0.75
        let startAlignmentInset = CGSize(width: 35, height: 90)
        let startViewportOffset = CGPoint.zero
        let startLayout = StudyTreeCanvasPolicy.expandedLayout(
            baseCenters: baseCenters,
            nodeOffsets: [1: initialDraggedOffset],
            baseCanvasSize: baseCanvasSize,
            nodeSize: nodeSize
        )
        let stationaryNodeStart = CGPoint(
            x: (baseCenters[2]!.x + startLayout.translation.width) * zoomScale
                + startAlignmentInset.width,
            y: (baseCenters[2]!.y + startLayout.translation.height) * zoomScale
                + startAlignmentInset.height
        )
        let draggedNodeStart = CGPoint(
            x: (
                baseCenters[1]!.x
                    + initialDraggedOffset.width
                    + startLayout.translation.width
            ) * zoomScale
                + startAlignmentInset.width,
            y: (
                baseCenters[1]!.y
                    + initialDraggedOffset.height
                    + startLayout.translation.height
            ) * zoomScale
                + startAlignmentInset.height
        )

        for step in 0...500 {
            let progress = CGFloat(step) / 500
            let draggedOffset = CGSize(
                width: initialDraggedOffset.width * (1 - progress),
                height: initialDraggedOffset.height * (1 - progress)
            )
            let layout = StudyTreeCanvasPolicy.expandedLayout(
                baseCenters: baseCenters,
                nodeOffsets: [1: draggedOffset],
                baseCanvasSize: baseCanvasSize,
                nodeSize: nodeSize
            )
            let compensation =
                StudyTreeViewportPolicy.compensationPreservingCanvasTranslation(
                    startOffset: startViewportOffset,
                    startAlignmentInset: startAlignmentInset,
                    startCanvasTranslation: startLayout.translation,
                    targetCanvasTranslation: layout.translation,
                    zoomScale: zoomScale
                )
            let stationaryNode = CGPoint(
                x: (baseCenters[2]!.x + layout.translation.width) * zoomScale
                    + compensation.alignmentInset.width
                    - compensation.viewportOffset.x,
                y: (baseCenters[2]!.y + layout.translation.height) * zoomScale
                    + compensation.alignmentInset.height
                    - compensation.viewportOffset.y
            )
            let draggedNode = CGPoint(
                x: (
                    baseCenters[1]!.x
                        + draggedOffset.width
                        + layout.translation.width
                ) * zoomScale
                    + compensation.alignmentInset.width
                    - compensation.viewportOffset.x,
                y: (
                    baseCenters[1]!.y
                        + draggedOffset.height
                        + layout.translation.height
                ) * zoomScale
                    + compensation.alignmentInset.height
                    - compensation.viewportOffset.y
            )

            XCTAssertEqual(stationaryNode.x, stationaryNodeStart.x, accuracy: 0.0001)
            XCTAssertEqual(stationaryNode.y, stationaryNodeStart.y, accuracy: 0.0001)
            XCTAssertEqual(
                draggedNode.x,
                draggedNodeStart.x
                    + (draggedOffset.width - initialDraggedOffset.width) * zoomScale,
                accuracy: 0.0001
            )
            XCTAssertEqual(
                draggedNode.y,
                draggedNodeStart.y
                    + (draggedOffset.height - initialDraggedOffset.height) * zoomScale,
                accuracy: 0.0001
            )
        }
    }
}

final class StudyOutlinePolicyTests: XCTestCase {
    func testTopicPreviewIsBoundedForLongTrees() {
        XCTAssertEqual(StudyOutlinePolicy.visibleCount(totalTopicCount: 0), 0)
        XCTAssertEqual(StudyOutlinePolicy.visibleCount(totalTopicCount: 2), 2)
        XCTAssertEqual(StudyOutlinePolicy.visibleCount(totalTopicCount: 12), 5)
        XCTAssertEqual(StudyOutlinePolicy.remainingCount(totalTopicCount: 2), 0)
        XCTAssertEqual(StudyOutlinePolicy.remainingCount(totalTopicCount: 12), 7)
    }

    func testAncestorPathSupportsDeepTreesWithoutGrowingIndentation() {
        let parentByID = [
            2: 1,
            3: 2,
            4: 3,
            5: 4,
            6: 5,
            7: 6
        ]

        XCTAssertEqual(
            StudyOutlinePolicy.ancestorPath(
                rootID: 1,
                targetID: 7,
                parentByID: parentByID
            ),
            [1, 2, 3, 4, 5, 6, 7]
        )
        XCTAssertEqual(
            StudyOutlinePolicy.ancestorPath(
                rootID: 1,
                targetID: 99,
                parentByID: parentByID
            ),
            [1]
        )
    }
}

final class RecordsPaginationTests: XCTestCase {
    func testStudyRecordsAreNotTrimmedByLegacyHistoryPreference() {
        let suiteName = "StudyMateiOSTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("StudyMateiOSTests-\(UUID().uuidString).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let store = SettingsStore(defaults: defaults, recordDatabaseURL: databaseURL)
        let settings = StudySettings(
            topic: "운영체제",
            difficulty: .intermediate,
            customPrompt: "짧게",
            intervalMinutes: 15,
            maxHistoryCount: 10
        )

        store.saveSettings(settings)
        for index in 1...12 {
            store.appendStudyRecord(
                question: QuestionItem(
                    question: "Question \(index)",
                    expectedAnswerHint: nil,
                    createdAt: Date()
                ),
                settings: settings
            )
        }

        let records = store.loadStudyRecords()

        XCTAssertEqual(records.count, 12)
        XCTAssertEqual(records.first?.question.question, "Question 1")
    }

    @MainActor
    func testRecordsStateTracksBackendPagesWithoutTreatingPageSizeAsRetention() {
        var state = RecordsStateStore()
        let firstRecords = (1...30).map { index in
            StudyRecord(
                id: "\(index)",
                question: QuestionItem(
                    question: "Question \(index)",
                    expectedAnswerHint: nil,
                    createdAt: Date()
                ),
                gradingResult: GradingResult(
                    score: 80,
                    isCorrect: true,
                    feedback: "좋아요.",
                    explanation: "핵심을 설명했습니다."
                ),
                topic: "Swift",
                difficulty: .level5
            )
        }
        let firstPage = BackendRecordsPage(
            records: firstRecords,
            totalCount: 75,
            limit: 30,
            offset: 0
        )

        XCTAssertTrue(state.beginPageLoad())
        state.applyPage(firstPage, reset: true)
        state.finishPageLoad()

        XCTAssertEqual(state.totalCount, 75)
        XCTAssertEqual(state.loadedBackendCount, 30)
        XCTAssertTrue(state.canLoadMore)

        let finalPage = BackendRecordsPage(
            records: Array(firstRecords.prefix(15)),
            totalCount: 75,
            limit: 30,
            offset: 60
        )
        XCTAssertTrue(state.beginPageLoad())
        state.applyPage(finalPage, reset: false)
        state.finishPageLoad()

        XCTAssertEqual(state.loadedBackendCount, 75)
        XCTAssertFalse(state.canLoadMore)

        state.removeLoadedBackendRecord(firstRecords[0])

        XCTAssertEqual(state.totalCount, 74)
        XCTAssertEqual(state.loadedBackendCount, 74)
    }

    @MainActor
    func testDeletingLoadedSearchResultUpdatesSearchPagination() throws {
        var state = SearchStateStore()
        let record = StudyRecord(
            id: "42",
            question: QuestionItem(
                question: "검색 결과",
                expectedAnswerHint: nil,
                createdAt: Date()
            ),
            topic: "Swift",
            difficulty: .level5
        )
        let requestID = try XCTUnwrap(state.beginRecordPage(query: "Swift", reset: true))
        state.applyRecordPage(
            BackendRecordsPage(records: [record], totalCount: 4, limit: 30, offset: 0),
            query: "Swift",
            reset: true,
            requestID: requestID
        )
        state.finishRecordPage(query: "Swift", requestID: requestID)

        state.removeRecordResult(id: record.id)

        XCTAssertTrue(state.recordResults?.isEmpty == true)
        XCTAssertEqual(state.recordTotalCount, 3)
        XCTAssertEqual(state.recordLoadedCount, 0)
        XCTAssertTrue(state.canLoadMoreRecordResults)
    }
}
