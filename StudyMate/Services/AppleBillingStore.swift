import Foundation
import Combine
import StoreKit
import UIKit

@MainActor
final class AppleBillingStore: ObservableObject {
    struct TierProduct: Identifiable {
        var tier: BackendBillingTierProduct
        var product: Product

        var id: String { tier.productId }
    }

    enum PurchaseOutcome: Equatable {
        case purchased(BackendBillingInvoice)
        case pending
        case cancelled
    }

    @Published private(set) var products: [TierProduct] = []
    @Published private(set) var isLoading = false
    @Published private(set) var processingProductID: String?
    @Published private(set) var errorMessage: String?

    func load(catalog: BackendBillingCatalog) async {
        isLoading = true
        defer { isLoading = false }
        do {
            let storeProducts = try await Product.products(for: catalog.products.map(\.productId))
            let byIdentifier = Dictionary(uniqueKeysWithValues: storeProducts.map { ($0.id, $0) })
            products = catalog.products
                .compactMap { tier in
                    byIdentifier[tier.productId].map { TierProduct(tier: tier, product: $0) }
                }
                .sorted { $0.tier.sortOrder < $1.tier.sortOrder }
            let missingProducts = Set(catalog.products.map(\.productId)).subtracting(byIdentifier.keys)
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
        prepareCheckout: @escaping (String) async throws -> BackendBillingInvoice,
        synchronize: @escaping (String, String, UUID?) async throws -> BackendBillingInvoice,
        abandonCheckout: @escaping (UUID) async throws -> Void
    ) async throws -> PurchaseOutcome {
        guard processingProductID == nil else {
            throw AppleBillingStoreError.purchaseAlreadyInProgress
        }
        processingProductID = tierProduct.id
        defer { processingProductID = nil }

        // The backend owns the order lifecycle. Persist a WAITING/NORMAL invoice before StoreKit
        // presents a sheet so every user-initiated attempt has an invoice aggregate.
        let checkout = try await prepareCheckout(tierProduct.id)
        let result = try await tierProduct.product.purchase(options: [.appAccountToken(appAccountToken)])
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
                checkout.invoiceNumber
            )
            await transaction.finish()
            return .purchased(invoice)
        case .pending:
            return .pending
        case .userCancelled:
            try? await abandonCheckout(checkout.invoiceNumber)
            return .cancelled
        @unknown default:
            throw AppleBillingStoreError.unknownPurchaseResult
        }
    }

    func restore(
        appAccountToken: UUID,
        synchronize: @escaping (String, String, UUID?) async throws -> BackendBillingInvoice
    ) async throws -> [BackendBillingInvoice] {
        try await AppStore.sync()
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
            await transaction.finish()
            restored.append(invoice)
        }
        return restored
    }

    func beginRefundRequest(transactionID: String, in scene: UIWindowScene) async throws -> Transaction.RefundRequestStatus {
        guard let identifier = UInt64(transactionID) else {
            throw AppleBillingStoreError.invalidTransactionIdentifier
        }
        return try await Transaction.beginRefundRequest(for: identifier, in: scene)
    }

    func showManageSubscriptions(in scene: UIWindowScene) async throws {
        try await AppStore.showManageSubscriptions(in: scene)
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

enum AppleBillingStoreError: LocalizedError {
    case purchaseAlreadyInProgress
    case unverifiedTransaction
    case accountTokenMismatch
    case invalidTransactionIdentifier
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
        case .unknownPurchaseResult:
            return "알 수 없는 App Store 결제 결과입니다."
        }
    }
}
