import Foundation
import Combine
import StoreKit
import RevenueCat
import UIKit

struct StoreKitTransactionSyncEvidence: Equatable {
    var signedTransaction: String
    var environment: String
}

enum StoreKitTransactionSyncResolver {
    nonisolated static func matches(
        revenueCatTransactionIdentifier: String,
        storeKitTransactionID: UInt64,
        storeKitProductID: String,
        expectedProductID: String,
        storeKitAppAccountToken: UUID?,
        expectedAppAccountToken: UUID
    ) -> Bool {
        guard let revenueCatTransactionID = UInt64(revenueCatTransactionIdentifier) else {
            return false
        }
        return revenueCatTransactionID == storeKitTransactionID
            && storeKitProductID == expectedProductID
            && storeKitAppAccountToken == expectedAppAccountToken
    }

    static func resolve(
        revenueCatTransactionIdentifier: String,
        productID: String,
        appAccountToken: UUID
    ) async -> StoreKitTransactionSyncEvidence? {
        for await verification in Transaction.currentEntitlements {
            if let evidence = evidence(
                from: verification,
                revenueCatTransactionIdentifier: revenueCatTransactionIdentifier,
                productID: productID,
                appAccountToken: appAccountToken
            ) {
                return evidence
            }
        }

        // RevenueCat normally finishes StoreKit transactions after posting them. Transaction.all
        // remains the durable fallback when the just-purchased subscription is not yet visible in
        // currentEntitlements or has already moved out of the current entitlement set.
        for await verification in Transaction.all {
            if let evidence = evidence(
                from: verification,
                revenueCatTransactionIdentifier: revenueCatTransactionIdentifier,
                productID: productID,
                appAccountToken: appAccountToken
            ) {
                return evidence
            }
        }
        return nil
    }

    private static func evidence(
        from verification: StoreKit.VerificationResult<StoreKit.Transaction>,
        revenueCatTransactionIdentifier: String,
        productID: String,
        appAccountToken: UUID
    ) -> StoreKitTransactionSyncEvidence? {
        guard case .verified(let transaction) = verification,
              matches(
                revenueCatTransactionIdentifier: revenueCatTransactionIdentifier,
                storeKitTransactionID: transaction.id,
                storeKitProductID: transaction.productID,
                expectedProductID: productID,
                storeKitAppAccountToken: transaction.appAccountToken,
                expectedAppAccountToken: appAccountToken
              ) else {
            return nil
        }
        return StoreKitTransactionSyncEvidence(
            signedTransaction: verification.jwsRepresentation,
            environment: backendEnvironment(transaction)
        )
    }

    private static func backendEnvironment(_ transaction: Transaction) -> String {
        switch transaction.environment {
        case .production:
            return "PRODUCTION"
        case .sandbox:
            return "SANDBOX"
        case .xcode:
            return "XCODE"
        default:
            return transaction.environment.rawValue.uppercased()
        }
    }
}

@MainActor
final class RevenueCatBillingBridge {
    enum Mode: Equatable {
        case disabled
        case appStore
    }

    static let shared = RevenueCatBillingBridge()

    private(set) var mode: Mode = .disabled
    var isEnabled: Bool { mode != .disabled }

    private init() {}

    nonisolated static func isValidPublicSDKKey(_ value: String?) -> Bool {
        guard let value else { return false }
        let normalizedKey = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return !normalizedKey.isEmpty
            && !normalizedKey.contains("$(")
            && normalizedKey.hasPrefix("appl_")
    }

    nonisolated static func resolvedPublicSDKKey(_ appStoreKey: String?) -> String? {
        guard isValidPublicSDKKey(appStoreKey) else {
            return nil
        }
        return appStoreKey?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    nonisolated static func matchesExpectedAppUserID(
        currentAppUserID: String,
        expectedAppAccountToken: UUID
    ) -> Bool {
        currentAppUserID.caseInsensitiveCompare(expectedAppAccountToken.uuidString) == .orderedSame
    }

    nonisolated static func shouldLogOut(
        currentAppUserID: String,
        expectedAppAccountToken: UUID?
    ) -> Bool {
        guard let expectedAppAccountToken else { return false }
        return matchesExpectedAppUserID(
            currentAppUserID: currentAppUserID,
            expectedAppAccountToken: expectedAppAccountToken
        )
    }

    func start() {
        guard !Purchases.isConfigured else {
            return
        }
        let appStoreKey = ProcessInfo.processInfo.environment["REVENUECAT_PUBLIC_SDK_KEY"]
            ?? Bundle.main.object(forInfoDictionaryKey: "RevenueCatPublicSDKKey") as? String
        guard let normalizedKey = Self.resolvedPublicSDKKey(appStoreKey) else {
            return
        }

        #if DEBUG
        Purchases.logLevel = .debug
        #else
        Purchases.logLevel = .warn
        #endif
        Purchases.configure(
            withAPIKey: normalizedKey,
            appUserID: nil,
            purchasesAreCompletedBy: .revenueCat,
            storeKitVersion: .storeKit2
        )
        mode = .appStore
    }

    func identify(appAccountToken: UUID) async throws {
        start()
        guard isEnabled else { return }
        let appUserID = appAccountToken.uuidString.lowercased()
        guard !Self.matchesExpectedAppUserID(
            currentAppUserID: Purchases.shared.appUserID,
            expectedAppAccountToken: appAccountToken
        ) else { return }
        _ = try await Purchases.shared.logIn(appUserID)
        guard Self.matchesExpectedAppUserID(
            currentAppUserID: Purchases.shared.appUserID,
            expectedAppAccountToken: appAccountToken
        ) else {
            throw RevenueCatBillingBridgeError.identityMismatch
        }
    }

    func syncPurchases() async throws {
        guard isEnabled else { return }
        _ = try await Purchases.shared.syncPurchases()
    }

    func logOut(expectedAppAccountToken: UUID?) async {
        guard isEnabled,
              !Purchases.shared.isAnonymous,
              Self.shouldLogOut(
                currentAppUserID: Purchases.shared.appUserID,
                expectedAppAccountToken: expectedAppAccountToken
              ) else { return }
        _ = try? await Purchases.shared.logOut()
    }
}

@MainActor
final class AppleBillingStore: ObservableObject {
    struct ActiveSubscription: Equatable {
        var productID: String
        var expirationDate: Date?
        var willRenew: Bool?
    }

    struct TierProduct: Identifiable {
        enum StoreProductSource {
            case appStore(Product)
            case revenueCat(StoreProduct)
        }

        var tier: BackendBillingTierProduct
        var source: StoreProductSource

        var id: String { tier.productId }
        var displayName: String {
            switch source {
            case .appStore(let product): product.displayName
            case .revenueCat(let product): product.localizedTitle
            }
        }
        var description: String {
            switch source {
            case .appStore(let product): product.description
            case .revenueCat(let product): product.localizedDescription
            }
        }
        var displayPrice: String {
            switch source {
            case .appStore(let product): product.displayPrice
            case .revenueCat(let product): product.localizedPriceString
            }
        }
    }

    enum PurchaseOutcome: Equatable {
        case purchased(BackendBillingInvoice)
        case alreadyCurrent
        case pending
        case changeScheduled
        case cancelled
    }

    nonisolated static func requireApplied(_ invoice: BackendBillingInvoice) throws -> BackendBillingInvoice {
        guard invoice.isApplied else {
            throw AppleBillingStoreError.membershipApplicationIncomplete
        }
        return invoice
    }

    nonisolated static func shouldCreateCheckout(for action: MembershipPrimaryAction) -> Bool {
        action == .subscribe || action == .change
    }

    nonisolated static func shouldSynchronizeCurrentEntitlementsBeforePurchase(
        for action: MembershipPrimaryAction
    ) -> Bool {
        action == .subscribe || action == .change
    }

    nonisolated static func canOpenUnfilteredSubscriptionManagement(
        availableProductIDs: Set<String>
    ) -> Bool {
        MembershipProductPolicy.retiredAnnualProductIDs.isDisjoint(with: availableProductIDs)
    }

    @Published private(set) var products: [TierProduct] = []
    @Published private(set) var isLoading = false
    @Published private(set) var processingProductID: String?
    @Published private(set) var errorMessage: String?

    func load(catalog: BackendBillingCatalog) async {
        isLoading = true
        defer { isLoading = false }
        do {
            try await RevenueCatBillingBridge.shared.identify(appAccountToken: catalog.appAccountToken)
            // Annual subscriptions are retained only as historical billing records on the backend.
            // The storefront is monthly-only, so an older or stale catalog must never surface them.
            let availableProducts = MembershipProductPolicy.monthlyProducts(catalog.products)
            let identifiers = availableProducts.map(\.productId)
            let byIdentifier: [String: TierProduct.StoreProductSource]
            if RevenueCatBillingBridge.shared.isEnabled {
                let storeProducts = await Purchases.shared.products(identifiers)
                byIdentifier = Dictionary(
                    uniqueKeysWithValues: storeProducts.map { ($0.productIdentifier, .revenueCat($0)) }
                )
            } else {
                let storeProducts = try await Product.products(for: identifiers)
                byIdentifier = Dictionary(uniqueKeysWithValues: storeProducts.map { ($0.id, .appStore($0)) })
            }
            products = availableProducts
                .compactMap { tier in
                    byIdentifier[tier.productId].map { TierProduct(tier: tier, source: $0) }
                }
                .sorted { $0.tier.sortOrder < $1.tier.sortOrder }
            let missingProducts = Set(availableProducts.map(\.productId)).subtracting(byIdentifier.keys)
            errorMessage = missingProducts.isEmpty
                ? nil
                : "App Store에서 일부 요금제를 불러올 수 없습니다."
        } catch {
            products = []
            errorMessage = error.localizedDescription
        }
    }

    func purchase(
        _ tierProduct: TierProduct,
        appAccountToken: UUID,
        resolveActionAfterSynchronization: @escaping () async -> MembershipPrimaryAction,
        prepareCheckout: @escaping (String) async throws -> BackendBillingInvoice,
        confirmRevenueCat: @escaping (String, UUID) async throws -> BackendBillingInvoice,
        synchronize: @escaping (String, String, UUID?) async throws -> BackendBillingInvoice,
        waitForFulfillment: @escaping (Int64) async throws -> BackendBillingInvoice,
        abandonCheckout: @escaping (UUID) async throws -> Void
    ) async throws -> PurchaseOutcome {
        guard MembershipProductPolicy.isPurchasableMonthlyProduct(tierProduct.tier) else {
            throw AppleBillingStoreError.unsupportedProduct
        }
        guard processingProductID == nil else {
            throw AppleBillingStoreError.purchaseAlreadyInProgress
        }
        processingProductID = tierProduct.id
        defer { processingProductID = nil }

        try await RevenueCatBillingBridge.shared.identify(appAccountToken: appAccountToken)
        var action = await resolveActionAfterSynchronization()
        if action == .current {
            return .alreadyCurrent
        }

        // A server-confirmed downgrade is a future Store subscription change, not a replay of the
        // current entitlement. Replaying the active higher-tier transaction here can be rejected as
        // already owned and must not prevent RevenueCat from scheduling the lower tier.
        if Self.shouldSynchronizeCurrentEntitlementsBeforePurchase(for: action) {
            try await synchronizeCurrentEntitlements(
                appAccountToken: appAccountToken,
                synchronize: synchronize
            )

            // StoreKit may already own an active transaction while the backend projection is stale.
            // Re-read the server-owned status before creating an invoice so an existing transaction
            // is never attached to a fresh checkout for the same product or a scheduled downgrade.
            action = await resolveActionAfterSynchronization()
            if action == .current {
                return .alreadyCurrent
            }
        }

        // A downgrade does not charge now. Apple schedules it for the next renewal, so creating a
        // financial invoice here would leave a WAITING order that can never receive a transaction.
        let checkout = Self.shouldCreateCheckout(for: action)
            ? try await prepareCheckout(tierProduct.id)
            : nil
        switch tierProduct.source {
        case .revenueCat(let product):
            let (revenueCatTransaction, _, userCancelled) = try await Purchases.shared.purchase(product: product)
            if userCancelled {
                if let checkout {
                    try? await abandonCheckout(checkout.invoiceNumber)
                }
                return .cancelled
            }
            if action == .downgrade {
                return .changeScheduled
            }
            guard let checkout else {
                return .changeScheduled
            }
            guard let revenueCatTransaction else {
                try? await abandonCheckout(checkout.invoiceNumber)
                throw AppleBillingStoreError.missingRevenueCatTransaction
            }
            let transactionIdentifier = revenueCatTransaction.transactionIdentifier
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard !transactionIdentifier.isEmpty,
                  revenueCatTransaction.productIdentifier == tierProduct.id,
                  let storeKitTransaction = revenueCatTransaction.sk2Transaction,
                  StoreKitTransactionSyncResolver.matches(
                    revenueCatTransactionIdentifier: transactionIdentifier,
                    storeKitTransactionID: storeKitTransaction.id,
                    storeKitProductID: storeKitTransaction.productID,
                    expectedProductID: tierProduct.id,
                    storeKitAppAccountToken: storeKitTransaction.appAccountToken,
                    expectedAppAccountToken: appAccountToken
                  ) else {
                try? await abandonCheckout(checkout.invoiceNumber)
                throw AppleBillingStoreError.revenueCatTransactionMismatch
            }
            do {
                let invoice = try await confirmRevenueCat(
                    transactionIdentifier,
                    checkout.invoiceNumber
                )
                return .purchased(try Self.requireApplied(invoice))
            } catch let confirmationError {
                guard Self.shouldWaitForRevenueCatWebhook(after: confirmationError) else {
                    throw confirmationError
                }
                var latestConfirmationError = confirmationError
                for delay in Self.revenueCatConfirmationRetryDelays {
                    try await Task.sleep(nanoseconds: delay)
                    do {
                        let invoice = try await confirmRevenueCat(
                            transactionIdentifier,
                            checkout.invoiceNumber
                        )
                        return .purchased(try Self.requireApplied(invoice))
                    } catch {
                        guard Self.shouldWaitForRevenueCatWebhook(after: error) else {
                            throw error
                        }
                        latestConfirmationError = error
                    }
                }

                // The webhook and the client confirmation update the same prepared invoice
                // idempotently. Poll only after retrying the exact transaction/invoice pair.
                do {
                    let invoice = try await waitForFulfillment(checkout.id)
                    return .purchased(try Self.requireApplied(invoice))
                } catch {
                    throw latestConfirmationError
                }
            }
        case .appStore(let product):
            let result = try await product.purchase(options: [.appAccountToken(appAccountToken)])
            switch result {
            case .success(let verification):
                guard case .verified(let transaction) = verification else {
                    throw AppleBillingStoreError.unverifiedTransaction
                }
                guard transaction.appAccountToken == appAccountToken else {
                    throw AppleBillingStoreError.accountTokenMismatch
                }
                // Finish only after the backend verifies Apple JWS, commits the invoice/payment ledger,
                // and grants the tier. A backend failure leaves the transaction available for recovery.
                let invoice = try await synchronize(
                    verification.jwsRepresentation,
                    Self.backendEnvironment(transaction),
                    checkout?.invoiceNumber
                )
                let appliedInvoice = try Self.requireApplied(invoice)
                await transaction.finish()
                return .purchased(appliedInvoice)
            case .pending:
                return .pending
            case .userCancelled:
                if let checkout {
                    try? await abandonCheckout(checkout.invoiceNumber)
                }
                return .cancelled
            @unknown default:
                throw AppleBillingStoreError.unknownPurchaseResult
            }
        }
    }

    private func synchronizeCurrentEntitlements(
        appAccountToken: UUID,
        synchronize: @escaping (String, String, UUID?) async throws -> BackendBillingInvoice
    ) async throws {
        for await verification in Transaction.currentEntitlements {
            guard case .verified(let transaction) = verification,
                  transaction.appAccountToken == appAccountToken else {
                continue
            }
            let invoice = try await synchronize(
                verification.jwsRepresentation,
                Self.backendEnvironment(transaction),
                nil
            )
            _ = try Self.requireApplied(invoice)
        }
    }

    func restore(
        appAccountToken: UUID,
        synchronize: @escaping (String, String, UUID?) async throws -> BackendBillingInvoice
    ) async throws -> [BackendBillingInvoice] {
        try await RevenueCatBillingBridge.shared.identify(appAccountToken: appAccountToken)
        if RevenueCatBillingBridge.shared.isEnabled {
            _ = try await Purchases.shared.restorePurchases()
        } else {
            try await AppStore.sync()
        }

        // RevenueCat restores the Store account, but BuddyStudy still needs Apple's verified JWS
        // for its own invoice/payment ledger. Replay every current entitlement through the same
        // synchronous backend contract used by a new purchase.
        var restored: [BackendBillingInvoice] = []
        for await verification in Transaction.currentEntitlements {
            guard case .verified(let transaction) = verification,
                  transaction.appAccountToken == appAccountToken else {
                continue
            }
            let invoice = try await synchronize(
                verification.jwsRepresentation,
                Self.backendEnvironment(transaction),
                nil
            )
            let appliedInvoice = try Self.requireApplied(invoice)
            await transaction.finish()
            restored.append(appliedInvoice)
        }
        return restored
    }

    private static func shouldWaitForRevenueCatWebhook(after error: Error) -> Bool {
        guard let backendError = error as? RemotePushBackendError,
              case .httpStatus(let statusCode, _, _) = backendError else {
            return false
        }
        return statusCode == 429 || statusCode >= 500
    }

    private static let revenueCatConfirmationRetryDelays: [UInt64] = [
        750_000_000,
        1_500_000_000,
        3_000_000_000,
    ]

    func beginRefundRequest(transactionID: String, in scene: UIWindowScene) async throws -> Transaction.RefundRequestStatus {
        guard let identifier = UInt64(transactionID) else {
            throw AppleBillingStoreError.invalidTransactionIdentifier
        }
        return try await Transaction.beginRefundRequest(for: identifier, in: scene)
    }

    func showManageSubscriptions(in scene: UIWindowScene) async throws {
        // Apple's subscription-management sheet renders every currently available product in the
        // subscription group and offers no client-side product filter. Never open that unfiltered
        // surface while either retired annual product is still returned by StoreKit.
        let retiredAnnualProducts = try await Product.products(
            for: MembershipProductPolicy.retiredAnnualProductIDs
        )
        guard Self.canOpenUnfilteredSubscriptionManagement(
            availableProductIDs: Set(retiredAnnualProducts.map(\.id))
        ) else {
            throw AppleBillingStoreError.legacyAnnualProductsStillAvailable
        }
        try await AppStore.showManageSubscriptions(in: scene)
    }

    static func backendEnvironment(_ transaction: Transaction) -> String {
        switch transaction.environment {
        case .production:
            return "PRODUCTION"
        case .sandbox:
            return "SANDBOX"
        case .xcode:
            return "XCODE"
        default:
            return transaction.environment.rawValue.uppercased()
        }
    }
}

enum RevenueCatBillingBridgeError: LocalizedError {
    case identityMismatch

    var errorDescription: String? {
        switch self {
        case .identityMismatch:
            return "RevenueCat 결제 계정을 현재 BuddyStudy 계정으로 전환하지 못했습니다."
        }
    }
}

enum AppleBillingStoreError: LocalizedError {
    case purchaseAlreadyInProgress
    case unverifiedTransaction
    case accountTokenMismatch
    case invalidTransactionIdentifier
    case unsupportedProduct
    case legacyAnnualProductsStillAvailable
    case membershipApplicationIncomplete
    case missingRevenueCatTransaction
    case revenueCatTransactionMismatch
    case unknownPurchaseResult

    var errorDescription: String? {
        switch self {
        case .purchaseAlreadyInProgress:
            return "다른 결제를 처리하고 있습니다."
        case .unverifiedTransaction:
            return "App Store 결제를 확인할 수 없습니다."
        case .accountTokenMismatch:
            return "결제 계정이 현재 로그인 계정과 일치하지 않습니다."
        case .invalidTransactionIdentifier:
            return "환불할 App Store 거래 번호가 올바르지 않습니다."
        case .unsupportedProduct:
            return "현재 구매할 수 없는 요금제입니다."
        case .legacyAnnualProductsStillAvailable:
            return "App Store 요금제를 정리하고 있습니다. 잠시 후 다시 시도해 주세요."
        case .membershipApplicationIncomplete:
            return "결제 결과를 확인했지만 멤버십 적용이 완료되지 않았습니다. 구매 복원을 다시 시도해 주세요."
        case .missingRevenueCatTransaction:
            return "이번 App Store 결제의 거래 정보를 확인할 수 없습니다. 구매 복원을 시도해 주세요."
        case .revenueCatTransactionMismatch:
            return "이번 App Store 결제와 일치하는 거래를 확인할 수 없습니다. 구매 복원을 시도해 주세요."
        case .unknownPurchaseResult:
            return "알 수 없는 App Store 결제 결과입니다."
        }
    }
}
