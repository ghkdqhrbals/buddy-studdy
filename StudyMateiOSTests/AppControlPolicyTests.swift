import XCTest
@testable import StudyMate

final class AppControlPolicyTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    func testRevenueCatAcceptsOnlyAppStorePublicSDKKey() {
        XCTAssertTrue(RevenueCatBillingBridge.isValidPublicSDKKey("appl_public-key"))
        XCTAssertFalse(RevenueCatBillingBridge.isValidPublicSDKKey("test_public-key"))
        XCTAssertFalse(RevenueCatBillingBridge.isValidPublicSDKKey("$(UNRESOLVED)"))
        XCTAssertEqual(
            RevenueCatBillingBridge.resolvedPublicSDKKey(" appl_apple "),
            "appl_apple"
        )
        XCTAssertNil(RevenueCatBillingBridge.resolvedPublicSDKKey("test_internal"))
    }

    func testRevenueCatUILocaleFollowsTheInAppLanguage() {
        XCTAssertEqual(
            RevenueCatBillingBridge.preferredUILocaleIdentifier(for: .korean),
            "ko_KR"
        )
        XCTAssertEqual(
            RevenueCatBillingBridge.preferredUILocaleIdentifier(for: .english),
            "en_US"
        )
        XCTAssertEqual(
            RevenueCatBillingBridge.preferredUILocaleIdentifier(for: .japanese),
            "ja_JP"
        )
    }

    func testMaintenanceTakesPriorityOverForcedUpdate() {
        let result = AppControlPolicyResolver.resolve(
            policy: policy(
                maintenance: maintenance(
                    startsAt: now.addingTimeInterval(-60),
                    endsAt: now.addingTimeInterval(600)
                ),
                update: update(mode: .force)
            ),
            language: .korean,
            channel: .appStore,
            currentVersion: "1.0.0",
            currentBuild: "1",
            dismissedOptionalCampaignID: nil,
            now: now
        )

        XCTAssertEqual(result.action, "MAINTENANCE")
        XCTAssertNotNil(result.maintenance)
        XCTAssertNil(result.update)
    }

    func testOptionalUpdateCanBeDismissedPerCampaign() {
        let result = AppControlPolicyResolver.resolve(
            policy: policy(update: update(mode: .optional)),
            language: .english,
            channel: .appStore,
            currentVersion: "1.0.0",
            currentBuild: "1",
            dismissedOptionalCampaignID: 7,
            now: now
        )

        XCTAssertEqual(result.action, "OPTIONAL_UPDATE")
        XCTAssertEqual(result.campaignID, 7)
        XCTAssertEqual(result.update?.shouldPresent, false)
    }

    func testCurrentVersionIsReportedAsUpToDateForConversion() {
        let result = AppControlPolicyResolver.resolve(
            policy: policy(update: update(mode: .force)),
            language: .japanese,
            channel: .appStore,
            currentVersion: "1.2.0",
            currentBuild: "80",
            dismissedOptionalCampaignID: nil,
            now: now
        )

        XCTAssertEqual(result.action, "UP_TO_DATE")
        XCTAssertEqual(result.campaignID, 7)
        XCTAssertNil(result.update)
    }

    func testExpiredPolicyFailsOpen() {
        let expired = AppControlRemotePolicy(
            schemaVersion: 1,
            policyID: "expired",
            revision: 2,
            publishedAt: now.addingTimeInterval(-7200),
            validUntil: now.addingTimeInterval(-1),
            maintenance: maintenance(
                startsAt: now.addingTimeInterval(-60),
                endsAt: nil
            ),
            channels: ["APP_STORE": update(mode: .force)]
        )

        let result = AppControlPolicyResolver.resolve(
            policy: expired,
            language: .korean,
            channel: .appStore,
            currentVersion: "1.0.0",
            currentBuild: "1",
            dismissedOptionalCampaignID: nil,
            now: now
        )

        XCTAssertEqual(result, .normal)
    }

    func testScheduledMaintenanceReturnsLocalReevaluationBoundary() {
        let startsAt = now.addingTimeInterval(120)
        let result = AppControlPolicyResolver.resolve(
            policy: policy(
                maintenance: maintenance(startsAt: startsAt, endsAt: nil),
                update: disabledUpdate()
            ),
            language: .korean,
            channel: .appStore,
            currentVersion: "1.0.0",
            currentBuild: "1",
            dismissedOptionalCampaignID: nil,
            now: now
        )

        XCTAssertEqual(result.action, "NORMAL")
        XCTAssertEqual(result.nextEvaluationAt, startsAt)
    }

    private func policy(
        maintenance: AppControlMaintenancePolicy? = nil,
        update: AppControlUpdatePolicy
    ) -> AppControlRemotePolicy {
        AppControlRemotePolicy(
            schemaVersion: 1,
            policyID: "ios-2",
            revision: 2,
            publishedAt: now.addingTimeInterval(-10),
            validUntil: now.addingTimeInterval(86_400),
            maintenance: maintenance ?? AppControlMaintenancePolicy(
                enabled: false,
                maintenanceID: nil,
                startsAt: nil,
                endsAt: nil,
                title: nil,
                message: nil
            ),
            channels: [
                "APP_STORE": update,
                "TESTFLIGHT": update,
            ]
        )
    }

    private func maintenance(startsAt: Date, endsAt: Date?) -> AppControlMaintenancePolicy {
        AppControlMaintenancePolicy(
            enabled: true,
            maintenanceID: 3,
            startsAt: startsAt,
            endsAt: endsAt,
            title: localized("점검", "Maintenance", "メンテナンス"),
            message: localized("점검 중", "Under maintenance", "メンテナンス中")
        )
    }

    private func update(mode: BackendAppUpdateMode) -> AppControlUpdatePolicy {
        AppControlUpdatePolicy(
            enabled: true,
            campaignID: 7,
            mode: mode,
            minimumVersion: "1.1.0",
            minimumBuild: "70",
            title: localized("업데이트", "Update", "更新"),
            message: localized("업데이트 필요", "Update required", "更新が必要です"),
            storeURL: "https://apps.apple.com/app/id6774108938"
        )
    }

    private func disabledUpdate() -> AppControlUpdatePolicy {
        AppControlUpdatePolicy(
            enabled: false,
            campaignID: nil,
            mode: nil,
            minimumVersion: nil,
            minimumBuild: nil,
            title: nil,
            message: nil,
            storeURL: nil
        )
    }

    private func localized(_ ko: String, _ en: String, _ ja: String) -> AppControlLocalizedContent {
        AppControlLocalizedContent(ko: ko, en: en, ja: ja)
    }
}
