import Foundation
import Combine
import StoreKit
import RevenueCat
import UIKit

struct StoreKitTransactionSyncEvidence: Equatable {
    var signedTransaction: String
    var environment: String
}

struct StoreKitRestoreCandidate: Equatable {
    var transactionID: UInt64
    var originalTransactionID: UInt64
    var productID: String
    var appAccountToken: UUID?
    var purchaseDate: Date
    var expirationDate: Date?
    var revocationDate: Date?
}

enum StoreKitRestoreCandidateSelector {
    nonisolated static func latestActiveMonthly(
        from candidates: [StoreKitRestoreCandidate],
        appAccountToken: UUID,
        now: Date = Date()
    ) -> StoreKitRestoreCandidate? {
        candidates
            .filter { candidate in
                candidate.appAccountToken == appAccountToken
                    && MembershipProductPolicy.purchasableMonthlyProductIDs.contains(candidate.productID)
                    && candidate.revocationDate == nil
                    && candidate.expirationDate.map { $0 > now } == true
            }
            .max { lhs, rhs in
                if lhs.purchaseDate != rhs.purchaseDate {
                    return lhs.purchaseDate < rhs.purchaseDate
                }
                return lhs.transactionID < rhs.transactionID
            }
    }
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
    private struct ProductCacheEntry {
        var productIDs: [String]
        var usesRevenueCat: Bool
        var sourcesByProductID: [String: TierProduct.StoreProductSource]
        var expiresAt: Date
    }

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

    @Published private(set) var products: [TierProduct] = []
    @Published private(set) var isLoading = false
    @Published private(set) var processingProductID: String?
    @Published private(set) var errorMessage: String?

    private static var productCache: ProductCacheEntry?
    private static let productCacheLifetime: TimeInterval = 15 * 60

    func load(catalog: BackendBillingCatalog) async {
        // Annual subscriptions are retained only as historical billing records on the backend.
        // The storefront is monthly-only, so an older or stale catalog must never surface them.
        let availableProducts = MembershipProductPolicy.monthlyProducts(catalog.products)
        let identifiers = availableProducts.map(\.productId)
        RevenueCatBillingBridge.shared.start()
        let usesRevenueCat = RevenueCatBillingBridge.shared.isEnabled

        if let cachedSources = Self.cachedProductSources(
            productIDs: identifiers,
            usesRevenueCat: usesRevenueCat
        ) {
            applyProducts(availableProducts, sourcesByProductID: cachedSources)
            return
        }

        isLoading = true
        defer { isLoading = false }
        do {
            try await RevenueCatBillingBridge.shared.identify(appAccountToken: catalog.appAccountToken)
            let byIdentifier: [String: TierProduct.StoreProductSource]
            if usesRevenueCat {
                let storeProducts = await Purchases.shared.products(identifiers)
                byIdentifier = Dictionary(
                    uniqueKeysWithValues: storeProducts.map { ($0.productIdentifier, .revenueCat($0)) }
                )
            } else {
                let storeProducts = try await Product.products(for: identifiers)
                byIdentifier = Dictionary(uniqueKeysWithValues: storeProducts.map { ($0.id, .appStore($0)) })
            }
            applyProducts(availableProducts, sourcesByProductID: byIdentifier)
            let missingProducts = Set(availableProducts.map(\.productId)).subtracting(byIdentifier.keys)
            errorMessage = missingProducts.isEmpty
                ? nil
                : "App Store에서 일부 요금제를 불러올 수 없습니다."
            if missingProducts.isEmpty {
                Self.productCache = ProductCacheEntry(
                    productIDs: identifiers.sorted(),
                    usesRevenueCat: usesRevenueCat,
                    sourcesByProductID: byIdentifier,
                    expiresAt: Date().addingTimeInterval(Self.productCacheLifetime)
                )
            }
        } catch {
            products = []
            errorMessage = error.localizedDescription
        }
    }

    private static func cachedProductSources(
        productIDs: [String],
        usesRevenueCat: Bool,
        now: Date = Date()
    ) -> [String: TierProduct.StoreProductSource]? {
        guard let productCache,
              productCache.expiresAt > now,
              productCache.usesRevenueCat == usesRevenueCat,
              productCache.productIDs == productIDs.sorted() else {
            return nil
        }
        return productCache.sourcesByProductID
    }

    private func applyProducts(
        _ availableProducts: [BackendBillingTierProduct],
        sourcesByProductID: [String: TierProduct.StoreProductSource]
    ) {
        products = availableProducts
            .compactMap { tier in
                sourcesByProductID[tier.productId].map { TierProduct(tier: tier, source: $0) }
            }
            .sorted { $0.tier.sortOrder < $1.tier.sortOrder }
        errorMessage = nil
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
        guard let entitlement = await currentRestorableEntitlement(
            appAccountToken: appAccountToken
        ) else {
            return
        }
        let invoice = try await synchronize(
            entitlement.verification.jwsRepresentation,
            Self.backendEnvironment(entitlement.transaction),
            nil
        )
        _ = try Self.requireApplied(invoice)
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

        // RevenueCat restores the Store account, while BuddyStudy verifies Apple's signed
        // transaction in its own ledger. One subscription group has one effective entitlement;
        // replaying retired, expired, or superseded transactions makes an unrelated historical
        // validation failure abort an otherwise valid restore.
        guard let entitlement = await currentRestorableEntitlement(
            appAccountToken: appAccountToken
        ) else {
            throw AppleBillingStoreError.noRestorablePurchases
        }
        let invoice = try await synchronize(
            entitlement.verification.jwsRepresentation,
            Self.backendEnvironment(entitlement.transaction),
            nil
        )
        let appliedInvoice = try Self.requireApplied(invoice)
        await entitlement.transaction.finish()
        return [appliedInvoice]
    }

    private func currentRestorableEntitlement(
        appAccountToken: UUID,
        now: Date = Date()
    ) async -> (
        verification: StoreKit.VerificationResult<StoreKit.Transaction>,
        transaction: StoreKit.Transaction
    )? {
        var verifiedEntitlements: [(
            verification: StoreKit.VerificationResult<StoreKit.Transaction>,
            transaction: StoreKit.Transaction,
            candidate: StoreKitRestoreCandidate
        )] = []

        for await verification in Transaction.currentEntitlements {
            guard case .verified(let transaction) = verification else {
                continue
            }
            verifiedEntitlements.append((
                verification: verification,
                transaction: transaction,
                candidate: StoreKitRestoreCandidate(
                    transactionID: transaction.id,
                    originalTransactionID: transaction.originalID,
                    productID: transaction.productID,
                    appAccountToken: transaction.appAccountToken,
                    purchaseDate: transaction.purchaseDate,
                    expirationDate: transaction.expirationDate,
                    revocationDate: transaction.revocationDate
                )
            ))
        }

        guard let selected = StoreKitRestoreCandidateSelector.latestActiveMonthly(
            from: verifiedEntitlements.map(\.candidate),
            appAccountToken: appAccountToken,
            now: now
        ), let entitlement = verifiedEntitlements.first(where: {
            $0.transaction.id == selected.transactionID
        }) else {
            return nil
        }
        return (entitlement.verification, entitlement.transaction)
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

    func refundTransactionID(
        transactionID: String,
        productID: String,
        appAccountToken: UUID
    ) async throws -> Transaction.ID {
        guard let identifier = UInt64(transactionID) else {
            throw AppleBillingStoreError.invalidTransactionIdentifier
        }
        for await verification in Transaction.all {
            guard case .verified(let transaction) = verification,
                  transaction.id == identifier,
                  transaction.productID == productID,
                  transaction.appAccountToken == nil || transaction.appAccountToken == appAccountToken else {
                continue
            }
            return transaction.id
        }
        throw AppleBillingStoreError.refundTransactionNotFound
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
    case refundTransactionNotFound
    case unsupportedProduct
    case membershipApplicationIncomplete
    case missingRevenueCatTransaction
    case revenueCatTransactionMismatch
    case noRestorablePurchases
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
        case .refundTransactionNotFound:
            return "현재 App Store 계정에서 환불할 결제 내역을 확인할 수 없습니다."
        case .unsupportedProduct:
            return "현재 구매할 수 없는 요금제입니다."
        case .membershipApplicationIncomplete:
            return "결제 결과를 확인했지만 멤버십 적용이 완료되지 않았습니다. 구매 복원을 다시 시도해 주세요."
        case .missingRevenueCatTransaction:
            return "이번 App Store 결제의 거래 정보를 확인할 수 없습니다. 구매 복원을 시도해 주세요."
        case .revenueCatTransactionMismatch:
            return "이번 App Store 결제와 일치하는 거래를 확인할 수 없습니다. 구매 복원을 시도해 주세요."
        case .noRestorablePurchases:
            return "복원할 수 있는 활성 구매가 없습니다."
        case .unknownPurchaseResult:
            return "알 수 없는 App Store 결제 결과입니다."
        }
    }
}
