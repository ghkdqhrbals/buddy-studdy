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

    func testRevenueCatIdentityMustMatchTheBackendAccountToken() {
        let accountToken = UUID(uuidString: "f9d47348-7b53-41c3-9f06-ef0b6f3c5e22")!

        XCTAssertTrue(
            RevenueCatBillingBridge.matchesExpectedAppUserID(
                currentAppUserID: accountToken.uuidString.uppercased(),
                expectedAppAccountToken: accountToken
            )
        )
        XCTAssertFalse(
            RevenueCatBillingBridge.matchesExpectedAppUserID(
                currentAppUserID: "$RCAnonymousID:other-account",
                expectedAppAccountToken: accountToken
            )
        )
    }

    func testRevenueCatStoreKitTransactionMatchRequiresTransactionProductAndAccount() {
        let accountToken = UUID(uuidString: "3f0c5f50-6521-4ba0-a990-73500e915f57")!
        let otherAccountToken = UUID(uuidString: "4f0c5f50-6521-4ba0-a990-73500e915f57")!

        XCTAssertTrue(
            StoreKitTransactionSyncResolver.matches(
                revenueCatTransactionIdentifier: "200000000000001",
                storeKitTransactionID: 200000000000001,
                storeKitProductID: "io.github.ghkdqhrbals.StudyMate.tier3.monthly",
                expectedProductID: "io.github.ghkdqhrbals.StudyMate.tier3.monthly",
                storeKitAppAccountToken: accountToken,
                expectedAppAccountToken: accountToken
            )
        )
        XCTAssertFalse(
            StoreKitTransactionSyncResolver.matches(
                revenueCatTransactionIdentifier: "200000000000001",
                storeKitTransactionID: 200000000000002,
                storeKitProductID: "io.github.ghkdqhrbals.StudyMate.tier3.monthly",
                expectedProductID: "io.github.ghkdqhrbals.StudyMate.tier3.monthly",
                storeKitAppAccountToken: accountToken,
                expectedAppAccountToken: accountToken
            )
        )
        XCTAssertFalse(
            StoreKitTransactionSyncResolver.matches(
                revenueCatTransactionIdentifier: "200000000000001",
                storeKitTransactionID: 200000000000001,
                storeKitProductID: "io.github.ghkdqhrbals.StudyMate.tier2.monthly",
                expectedProductID: "io.github.ghkdqhrbals.StudyMate.tier3.monthly",
                storeKitAppAccountToken: accountToken,
                expectedAppAccountToken: accountToken
            )
        )
        XCTAssertFalse(
            StoreKitTransactionSyncResolver.matches(
                revenueCatTransactionIdentifier: "200000000000001",
                storeKitTransactionID: 200000000000001,
                storeKitProductID: "io.github.ghkdqhrbals.StudyMate.tier3.monthly",
                expectedProductID: "io.github.ghkdqhrbals.StudyMate.tier3.monthly",
                storeKitAppAccountToken: otherAccountToken,
                expectedAppAccountToken: accountToken
            )
        )
        XCTAssertFalse(
            StoreKitTransactionSyncResolver.matches(
                revenueCatTransactionIdentifier: "test-store-transaction",
                storeKitTransactionID: 200000000000001,
                storeKitProductID: "io.github.ghkdqhrbals.StudyMate.tier3.monthly",
                expectedProductID: "io.github.ghkdqhrbals.StudyMate.tier3.monthly",
                storeKitAppAccountToken: accountToken,
                expectedAppAccountToken: accountToken
            )
        )
    }

    func testRevenueCatPurchaseConfirmsInvoiceOrWaitsForWebhook() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let billingStore = try String(
            contentsOf: root.appendingPathComponent("StudyMate/Services/AppleBillingStore.swift"),
            encoding: .utf8
        )
        let appState = try String(
            contentsOf: root.appendingPathComponent("StudyMate/ViewModels/AppState.swift"),
            encoding: .utf8
        )

        XCTAssertTrue(billingStore.contains("checkout.invoiceNumber"))
        XCTAssertTrue(billingStore.contains("let invoice = try await waitForFulfillment(checkout.id)"))
        XCTAssertTrue(billingStore.contains("Self.requireApplied(invoice)"))
        XCTAssertFalse(billingStore.contains("try? await synchronize("))
        XCTAssertTrue(billingStore.contains("for await verification in Transaction.currentEntitlements"))
        XCTAssertTrue(billingStore.contains("let appliedInvoice = try Self.requireApplied(invoice)"))
        XCTAssertTrue(billingStore.contains("try await synchronizeCurrentEntitlements("))
        XCTAssertTrue(billingStore.contains("var action = await resolveActionAfterSynchronization()"))
        XCTAssertTrue(billingStore.contains("let checkout = Self.shouldCreateCheckout(for: action)"))
        XCTAssertFalse(billingStore.contains("return action == .downgrade ? .changeScheduled : .pending"))
        XCTAssertTrue(billingStore.contains("revenueCatTransaction.transactionIdentifier"))
        XCTAssertTrue(billingStore.contains("revenueCatTransaction.productIdentifier == tierProduct.id"))
        XCTAssertTrue(billingStore.contains("StoreKitTransactionSyncResolver.matches("))
        XCTAssertTrue(billingStore.contains("for delay in Self.revenueCatConfirmationRetryDelays"))
        XCTAssertTrue(billingStore.contains("if action == .downgrade"))
        XCTAssertTrue(appState.contains("for await verification in Transaction.currentEntitlements"))
        XCTAssertTrue(appState.contains("finishAfterSync: false"))
        XCTAssertTrue(appState.contains("_ = try AppleBillingStore.requireApplied(invoice)"))
        XCTAssertTrue(appState.contains("case .membershipApplicationIncomplete = billingError"))
    }

    func testCheckoutIsCreatedOnlyForAChargeablePurchaseAction() {
        XCTAssertTrue(AppleBillingStore.shouldCreateCheckout(for: .subscribe))
        XCTAssertTrue(AppleBillingStore.shouldCreateCheckout(for: .change))
        XCTAssertFalse(AppleBillingStore.shouldCreateCheckout(for: .current))
        XCTAssertFalse(AppleBillingStore.shouldCreateCheckout(for: .downgrade))
    }

    func testCurrentEntitlementsAreReplayedOnlyForSubscribeAndUpgrade() {
        XCTAssertTrue(
            AppleBillingStore.shouldSynchronizeCurrentEntitlementsBeforePurchase(for: .subscribe)
        )
        XCTAssertTrue(
            AppleBillingStore.shouldSynchronizeCurrentEntitlementsBeforePurchase(for: .change)
        )
        XCTAssertFalse(
            AppleBillingStore.shouldSynchronizeCurrentEntitlementsBeforePurchase(for: .current)
        )
        XCTAssertFalse(
            AppleBillingStore.shouldSynchronizeCurrentEntitlementsBeforePurchase(for: .downgrade)
        )
    }

    func testSubscriptionCancellationAndRefundUseNativeStoreKitFlows() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let mobileRoot = try String(
            contentsOf: root.appendingPathComponent("StudyMate/Views/MobileRootView.swift"),
            encoding: .utf8
        )

        let billingStore = try String(
            contentsOf: root.appendingPathComponent("StudyMate/Services/AppleBillingStore.swift"),
            encoding: .utf8
        )

        XCTAssertTrue(mobileRoot.contains(".manageSubscriptionsSheet(isPresented:"))
        XCTAssertTrue(mobileRoot.contains(".refundRequestSheet("))
        XCTAssertTrue(mobileRoot.contains("try await billingStore.refundTransactionID("))
        XCTAssertFalse(mobileRoot.contains("https://reportaproblem.apple.com/"))
        XCTAssertFalse(mobileRoot.contains("requestRefundOnWeb"))
        XCTAssertTrue(mobileRoot.contains("try? await appState.requestBillingRefund(paymentID: paymentID)"))
        XCTAssertTrue(billingStore.contains("for await verification in Transaction.all"))
        XCTAssertTrue(billingStore.contains("transaction.id == identifier"))
        XCTAssertTrue(billingStore.contains("transaction.productID == productID"))
        XCTAssertFalse(billingStore.contains("Transaction.beginRefundRequest(for:"))
        XCTAssertFalse(billingStore.contains("transaction.beginRefundRequest(in: scene)"))
    }

    func testMembershipScreenUsesCachedProductsBeforeProviderReconciliation() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let mobileRoot = try String(
            contentsOf: root.appendingPathComponent("StudyMate/Views/MobileRootView.swift"),
            encoding: .utf8
        )
        let billingStore = try String(
            contentsOf: root.appendingPathComponent("StudyMate/Services/AppleBillingStore.swift"),
            encoding: .utf8
        )

        XCTAssertTrue(mobileRoot.contains("await loadMembershipScreen()"))
        XCTAssertTrue(mobileRoot.contains("async let billingRefresh: Void = appState.refreshBilling()"))
        XCTAssertTrue(mobileRoot.contains("await billingStore.load(catalog: cachedCatalog)"))
        XCTAssertTrue(billingStore.contains("private static var productCache: ProductCacheEntry?"))
        XCTAssertTrue(billingStore.contains("private static let productCacheLifetime: TimeInterval = 15 * 60"))
    }

    func testNativeSubscriptionManagementIsNotBlockedByRetiredProducts() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let mobileRoot = try String(
            contentsOf: root.appendingPathComponent("StudyMate/Views/MobileRootView.swift"),
            encoding: .utf8
        )
        let billingStore = try String(
            contentsOf: root.appendingPathComponent("StudyMate/Services/AppleBillingStore.swift"),
            encoding: .utf8
        )

        XCTAssertTrue(mobileRoot.contains(".manageSubscriptionsSheet(isPresented:"))
        XCTAssertFalse(billingStore.contains("canOpenUnfilteredSubscriptionManagement"))
        XCTAssertFalse(billingStore.contains("legacyAnnualProductsStillAvailable"))
    }

    func testPurchaseActionIsResolvedBeforeEntitlementReplayAndCheckoutCreation() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let billingStore = try String(
            contentsOf: root.appendingPathComponent("StudyMate/Services/AppleBillingStore.swift"),
            encoding: .utf8
        )

        let synchronization = try XCTUnwrap(
            billingStore.range(of: "try await synchronizeCurrentEntitlements(")
        )
        let actionResolution = try XCTUnwrap(
            billingStore.range(of: "var action = await resolveActionAfterSynchronization()")
        )
        let checkoutCreation = try XCTUnwrap(
            billingStore.range(of: "let checkout = Self.shouldCreateCheckout(for: action)")
        )

        XCTAssertLessThan(actionResolution.lowerBound, synchronization.lowerBound)
        XCTAssertLessThan(synchronization.lowerBound, checkoutCreation.lowerBound)
    }

    func testPurchaseSuccessRequiresSettledAndFulfilledBackendInvoice() throws {
        let fulfilledAt = Date(timeIntervalSince1970: 1_785_744_722)
        let applied = BackendBillingInvoice(
            id: 41,
            invoiceNumber: UUID(uuidString: "9f041446-e898-4ef7-974d-91ac70e1a89b")!,
            tierCode: "TIER3",
            productId: "io.github.ghkdqhrbals.StudyMate.tier3.monthly",
            status: "COMPLETED",
            version: 4,
            paymentId: 87,
            transactionId: "2000000812345678",
            originalTransactionId: "2000000712345678",
            paymentStatus: "SETTLED",
            priceMilliunits: 17_900_000,
            currency: "KRW",
            purchaseAt: Date(timeIntervalSince1970: 1_785_744_720),
            expiresAt: Date(timeIntervalSince1970: 1_788_422_720),
            createdAt: Date(timeIntervalSince1970: 1_785_744_721),
            updatedAt: fulfilledAt,
            fulfilledAt: fulfilledAt
        )

        XCTAssertTrue(applied.isApplied)
        XCTAssertNoThrow(try AppleBillingStore.requireApplied(applied))

        var paymentOnly = applied
        paymentOnly.paymentStatus = "VERIFIED"
        paymentOnly.fulfilledAt = nil
        XCTAssertFalse(paymentOnly.isApplied)
        XCTAssertThrowsError(try AppleBillingStore.requireApplied(paymentOnly))

        var unfulfilled = applied
        unfulfilled.fulfilledAt = nil
        XCTAssertEqual(unfulfilled.paymentStatus, "SETTLED")
        XCTAssertFalse(unfulfilled.isApplied)
        XCTAssertThrowsError(try AppleBillingStore.requireApplied(unfulfilled))
    }

    private func billingInvoice(
        id: Int64,
        status: String,
        createdAt: TimeInterval
    ) -> BackendBillingInvoice {
        BackendBillingInvoice(
            id: id,
            invoiceNumber: UUID(),
            tierCode: "TIER2",
            productId: "io.github.ghkdqhrbals.StudyMate.tier2.monthly",
            status: status,
            version: 1,
            paymentId: nil,
            transactionId: nil,
            originalTransactionId: nil,
            paymentStatus: nil,
            priceMilliunits: nil,
            currency: nil,
            purchaseAt: nil,
            expiresAt: nil,
            createdAt: Date(timeIntervalSince1970: createdAt),
            updatedAt: Date(timeIntervalSince1970: createdAt),
            fulfilledAt: nil
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
