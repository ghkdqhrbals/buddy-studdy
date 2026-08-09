import Foundation

@MainActor
protocol AppUpdateRepository {
    func check(
        registration: RemotePushRegistration,
        language: AppLanguage
    ) async throws -> BackendAppUpdateDecision
    func record(
        registration: RemotePushRegistration,
        campaignID: Int64,
        event: BackendAppUpdateEvent
    ) async throws
    func recordAppControlEvent(
        registration: RemotePushRegistration,
        request: BackendAppControlEventRequest
    ) async throws
}

@MainActor
struct RemoteAppUpdateRepository: AppUpdateRepository {
    private let backendClient: RemotePushBackendClientProtocol

    init(backendClient: RemotePushBackendClientProtocol) {
        self.backendClient = backendClient
    }

    func check(
        registration: RemotePushRegistration,
        language: AppLanguage
    ) async throws -> BackendAppUpdateDecision {
        try await backendClient.checkAppUpdate(registration: registration, language: language)
    }

    func record(
        registration: RemotePushRegistration,
        campaignID: Int64,
        event: BackendAppUpdateEvent
    ) async throws {
        try await backendClient.recordAppUpdateEvent(
            registration: registration,
            campaignID: campaignID,
            event: event
        )
    }

    func recordAppControlEvent(
        registration: RemotePushRegistration,
        request: BackendAppControlEventRequest
    ) async throws {
        try await backendClient.recordAppControlEvent(
            registration: registration,
            request: request
        )
    }
}

@MainActor
struct AppUpdateUseCase {
    private let repository: AppUpdateRepository

    init(repository: AppUpdateRepository) {
        self.repository = repository
    }

    func check(
        registration: RemotePushRegistration,
        language: AppLanguage
    ) async throws -> BackendAppUpdateDecision {
        try await repository.check(registration: registration, language: language)
    }

    func record(
        registration: RemotePushRegistration,
        campaignID: Int64,
        event: BackendAppUpdateEvent
    ) async throws {
        try await repository.record(registration: registration, campaignID: campaignID, event: event)
    }

    func recordAppControlEvent(
        registration: RemotePushRegistration,
        request: BackendAppControlEventRequest
    ) async throws {
        try await repository.recordAppControlEvent(
            registration: registration,
            request: request
        )
    }
}

@MainActor
protocol TermsRepository {
    func fetchActiveTerms(registration: RemotePushRegistration) async throws -> [BackendTerms]

    func saveAgreement(
        registration: RemotePushRegistration,
        type: BackendTermsType,
        action: BackendTermsAgreementAction,
        source: BackendTermsAgreementSource
    ) async throws -> BackendPermissionEvaluations

    func fetchPermissionEvaluations(registration: RemotePushRegistration) async throws -> BackendPermissionEvaluations
    func fetchNotificationPreferences(registration: RemotePushRegistration) async throws -> [BackendNotificationPreference]
    func saveNotificationPreference(
        registration: RemotePushRegistration,
        type: BackendNotificationPreferenceType,
        enabled: Bool
    ) async throws -> BackendNotificationPreference
}

@MainActor
struct RemoteTermsRepository: TermsRepository {
    private let backendClient: RemotePushBackendClientProtocol

    init(backendClient: RemotePushBackendClientProtocol) {
        self.backendClient = backendClient
    }

    func fetchActiveTerms(registration: RemotePushRegistration) async throws -> [BackendTerms] {
        try await backendClient.fetchActiveTerms(registration: registration)
    }

    func saveAgreement(
        registration: RemotePushRegistration,
        type: BackendTermsType,
        action: BackendTermsAgreementAction,
        source: BackendTermsAgreementSource
    ) async throws -> BackendPermissionEvaluations {
        try await backendClient.saveTermsAgreement(
            registration: registration,
            type: type,
            action: action,
            source: source
        )
    }

    func fetchPermissionEvaluations(registration: RemotePushRegistration) async throws -> BackendPermissionEvaluations {
        try await backendClient.fetchPermissionEvaluations(registration: registration)
    }

    func fetchNotificationPreferences(registration: RemotePushRegistration) async throws -> [BackendNotificationPreference] {
        try await backendClient.fetchNotificationPreferences(registration: registration)
    }

    func saveNotificationPreference(
        registration: RemotePushRegistration,
        type: BackendNotificationPreferenceType,
        enabled: Bool
    ) async throws -> BackendNotificationPreference {
        try await backendClient.saveNotificationPreference(
            registration: registration,
            type: type,
            enabled: enabled
        )
    }
}

@MainActor
struct TermsUseCase {
    private let repository: TermsRepository

    init(repository: TermsRepository) {
        self.repository = repository
    }

    func fetchActiveTerms(registration: RemotePushRegistration) async throws -> [BackendTerms] {
        try await repository.fetchActiveTerms(registration: registration)
    }

    func saveAgreement(
        registration: RemotePushRegistration,
        type: BackendTermsType,
        action: BackendTermsAgreementAction,
        source: BackendTermsAgreementSource = .settings
    ) async throws -> BackendPermissionEvaluations {
        try await repository.saveAgreement(
            registration: registration,
            type: type,
            action: action,
            source: source
        )
    }

    func fetchPermissionEvaluations(registration: RemotePushRegistration) async throws -> BackendPermissionEvaluations {
        try await repository.fetchPermissionEvaluations(registration: registration)
    }

    func fetchNotificationPreferences(registration: RemotePushRegistration) async throws -> [BackendNotificationPreference] {
        try await repository.fetchNotificationPreferences(registration: registration)
    }

    func saveNotificationPreference(
        registration: RemotePushRegistration,
        type: BackendNotificationPreferenceType,
        enabled: Bool
    ) async throws -> BackendNotificationPreference {
        try await repository.saveNotificationPreference(
            registration: registration,
            type: type,
            enabled: enabled
        )
    }
}

@MainActor
protocol BillingRepository {
    func status(registration: RemotePushRegistration) async throws -> BackendBillingStatus
    func reconcileSubscription(registration: RemotePushRegistration) async throws -> BackendBillingStatus
    func catalog(registration: RemotePushRegistration) async throws -> BackendBillingCatalog
    func createCheckout(
        registration: RemotePushRegistration,
        productID: String,
        idempotencyKey: String
    ) async throws -> BackendBillingInvoice
    func abandonCheckout(
        registration: RemotePushRegistration,
        invoiceNumber: UUID
    ) async throws -> BackendBillingInvoice
    func confirmRevenueCatTransaction(
        registration: RemotePushRegistration,
        invoiceNumber: UUID,
        transactionID: String?
    ) async throws -> BackendBillingInvoice
    func syncAppleTransaction(
        registration: RemotePushRegistration,
        signedTransaction: String,
        environment: String,
        invoiceNumber: UUID?
    ) async throws -> BackendBillingInvoice
    func invoices(registration: RemotePushRegistration, limit: Int, offset: Int) async throws -> BackendBillingInvoicePage
    func invoice(registration: RemotePushRegistration, invoiceID: Int64) async throws -> BackendBillingInvoice
    func requestRefund(
        registration: RemotePushRegistration,
        paymentID: Int64,
        idempotencyKey: String,
        reason: String?
    ) async throws -> BackendBillingAction
    func requestCancellation(
        registration: RemotePushRegistration,
        originalTransactionID: String,
        idempotencyKey: String,
        reason: String?
    ) async throws -> BackendBillingAction
}

@MainActor
struct RemoteBillingRepository: BillingRepository {
    private let backendClient: RemotePushBackendClientProtocol

    init(backendClient: RemotePushBackendClientProtocol) {
        self.backendClient = backendClient
    }

    func status(registration: RemotePushRegistration) async throws -> BackendBillingStatus {
        try await backendClient.fetchBillingStatus(registration: registration)
    }

    func reconcileSubscription(registration: RemotePushRegistration) async throws -> BackendBillingStatus {
        try await backendClient.reconcileBillingSubscription(registration: registration)
    }

    func catalog(registration: RemotePushRegistration) async throws -> BackendBillingCatalog {
        try await backendClient.fetchBillingCatalog(registration: registration)
    }

    func createCheckout(
        registration: RemotePushRegistration,
        productID: String,
        idempotencyKey: String
    ) async throws -> BackendBillingInvoice {
        try await backendClient.createBillingCheckout(
            registration: registration,
            productID: productID,
            idempotencyKey: idempotencyKey
        )
    }

    func abandonCheckout(
        registration: RemotePushRegistration,
        invoiceNumber: UUID
    ) async throws -> BackendBillingInvoice {
        try await backendClient.abandonBillingCheckout(
            registration: registration,
            invoiceNumber: invoiceNumber
        )
    }

    func confirmRevenueCatTransaction(
        registration: RemotePushRegistration,
        invoiceNumber: UUID,
        transactionID: String?
    ) async throws -> BackendBillingInvoice {
        try await backendClient.confirmRevenueCatTransaction(
            registration: registration,
            invoiceNumber: invoiceNumber,
            transactionID: transactionID
        )
    }

    func syncAppleTransaction(
        registration: RemotePushRegistration,
        signedTransaction: String,
        environment: String,
        invoiceNumber: UUID?
    ) async throws -> BackendBillingInvoice {
        try await backendClient.syncAppleTransaction(
            registration: registration,
            signedTransaction: signedTransaction,
            environment: environment,
            invoiceNumber: invoiceNumber
        )
    }

    func invoices(registration: RemotePushRegistration, limit: Int, offset: Int) async throws -> BackendBillingInvoicePage {
        try await backendClient.fetchBillingInvoices(registration: registration, limit: limit, offset: offset)
    }

    func invoice(registration: RemotePushRegistration, invoiceID: Int64) async throws -> BackendBillingInvoice {
        try await backendClient.fetchBillingInvoice(registration: registration, invoiceID: invoiceID)
    }

    func requestRefund(
        registration: RemotePushRegistration,
        paymentID: Int64,
        idempotencyKey: String,
        reason: String?
    ) async throws -> BackendBillingAction {
        try await backendClient.requestBillingRefund(
            registration: registration,
            paymentID: paymentID,
            idempotencyKey: idempotencyKey,
            reason: reason
        )
    }

    func requestCancellation(
        registration: RemotePushRegistration,
        originalTransactionID: String,
        idempotencyKey: String,
        reason: String?
    ) async throws -> BackendBillingAction {
        try await backendClient.requestBillingCancellation(
            registration: registration,
            originalTransactionID: originalTransactionID,
            idempotencyKey: idempotencyKey,
            reason: reason
        )
    }
}

@MainActor
struct BillingUseCase {
    private let repository: BillingRepository

    init(repository: BillingRepository) {
        self.repository = repository
    }

    func status(registration: RemotePushRegistration) async throws -> BackendBillingStatus {
        try await repository.status(registration: registration)
    }

    func reconcileSubscription(registration: RemotePushRegistration) async throws -> BackendBillingStatus {
        try await repository.reconcileSubscription(registration: registration)
    }

    func catalog(registration: RemotePushRegistration) async throws -> BackendBillingCatalog {
        try await repository.catalog(registration: registration)
    }

    func createCheckout(
        registration: RemotePushRegistration,
        productID: String,
        idempotencyKey: String
    ) async throws -> BackendBillingInvoice {
        try await repository.createCheckout(
            registration: registration,
            productID: productID,
            idempotencyKey: idempotencyKey
        )
    }

    func abandonCheckout(
        registration: RemotePushRegistration,
        invoiceNumber: UUID
    ) async throws -> BackendBillingInvoice {
        try await repository.abandonCheckout(registration: registration, invoiceNumber: invoiceNumber)
    }

    func confirmRevenueCatTransaction(
        registration: RemotePushRegistration,
        invoiceNumber: UUID,
        transactionID: String?
    ) async throws -> BackendBillingInvoice {
        try await repository.confirmRevenueCatTransaction(
            registration: registration,
            invoiceNumber: invoiceNumber,
            transactionID: transactionID
        )
    }

    func syncAppleTransaction(
        registration: RemotePushRegistration,
        signedTransaction: String,
        environment: String,
        invoiceNumber: UUID?
    ) async throws -> BackendBillingInvoice {
        try await repository.syncAppleTransaction(
            registration: registration,
            signedTransaction: signedTransaction,
            environment: environment,
            invoiceNumber: invoiceNumber
        )
    }

    func invoices(registration: RemotePushRegistration, limit: Int = 30, offset: Int = 0) async throws -> BackendBillingInvoicePage {
        try await repository.invoices(registration: registration, limit: limit, offset: offset)
    }

    func invoice(registration: RemotePushRegistration, invoiceID: Int64) async throws -> BackendBillingInvoice {
        try await repository.invoice(registration: registration, invoiceID: invoiceID)
    }

    func requestRefund(
        registration: RemotePushRegistration,
        paymentID: Int64,
        idempotencyKey: String,
        reason: String? = nil
    ) async throws -> BackendBillingAction {
        try await repository.requestRefund(
            registration: registration,
            paymentID: paymentID,
            idempotencyKey: idempotencyKey,
            reason: reason
        )
    }

    func requestCancellation(
        registration: RemotePushRegistration,
        originalTransactionID: String,
        idempotencyKey: String,
        reason: String? = nil
    ) async throws -> BackendBillingAction {
        try await repository.requestCancellation(
            registration: registration,
            originalTransactionID: originalTransactionID,
            idempotencyKey: idempotencyKey,
            reason: reason
        )
    }
}

@MainActor
struct AppUseCases {
    let appUpdate: AppUpdateUseCase
    let backendIdentity: BackendIdentityUseCase
    let googleSignIn: GoogleSignInUseCase
    let studyRoom: StudyRoomUseCase
    let records: RecordsUseCase
    let notifications: NotificationsUseCase
    let stats: StatsUseCase
    let settings: SettingsUseCase
    let terms: TermsUseCase
    let community: CommunityUseCase
    let billing: BillingUseCase

    init(backendClient: RemotePushBackendClientProtocol) {
        let appUpdateRepository = RemoteAppUpdateRepository(backendClient: backendClient)
        let googleSignInRepository = OAuthGoogleSignInRepository()
        let identityRepository = RemoteIdentityRepository(backendClient: backendClient)
        let communityRepository = RemoteCommunityRepository(backendClient: backendClient)
        let studyRoomRepository = RemoteStudyRoomRepository(backendClient: backendClient)
        let recordsRepository = RemoteRecordsRepository(backendClient: backendClient)
        let statsRepository = RemoteStatsRepository(backendClient: backendClient)
        let notificationsRepository = RemoteNotificationsRepository(backendClient: backendClient)
        let settingsRepository = RemoteSettingsRepository(backendClient: backendClient)
        let termsRepository = RemoteTermsRepository(backendClient: backendClient)
        let billingRepository = RemoteBillingRepository(backendClient: backendClient)
        appUpdate = AppUpdateUseCase(repository: appUpdateRepository)
        backendIdentity = BackendIdentityUseCase(repository: identityRepository)
        googleSignIn = GoogleSignInUseCase(repository: googleSignInRepository)
        studyRoom = StudyRoomUseCase(repository: studyRoomRepository)
        records = RecordsUseCase(repository: recordsRepository)
        notifications = NotificationsUseCase(repository: notificationsRepository)
        stats = StatsUseCase(repository: statsRepository)
        settings = SettingsUseCase(repository: settingsRepository)
        terms = TermsUseCase(repository: termsRepository)
        community = CommunityUseCase(repository: communityRepository)
        billing = BillingUseCase(repository: billingRepository)
    }
}
