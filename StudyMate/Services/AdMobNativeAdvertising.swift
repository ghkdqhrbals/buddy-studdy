#if os(iOS)
import GoogleMobileAds
import SwiftUI
import UIKit
import UserMessagingPlatform

struct NativeAdvertisementRequestPolicy {
    static let loadTimeout: Duration = .seconds(5)
    static let minimumRequestInterval: TimeInterval = 60
    static let cacheLifetime: TimeInterval = 55 * 60

    static func canStartRequest(lastRequestAt: Date?, now: Date) -> Bool {
        guard let lastRequestAt else { return true }
        return now.timeIntervalSince(lastRequestAt) >= minimumRequestInterval
    }

    static func isFresh(loadedAt: Date, now: Date) -> Bool {
        let age = now.timeIntervalSince(loadedAt)
        return age >= 0 && age < cacheLifetime
    }

    static func canUseLoadedAdvertisement(
        loadedAt: Date,
        now: Date,
        loadedAuthorizationGeneration: UInt64,
        currentAuthorization: AdMobPrivacyAuthorization
    ) -> Bool {
        currentAuthorization.permitsRequest &&
            loadedAuthorizationGeneration == currentAuthorization.generation &&
            isFresh(loadedAt: loadedAt, now: now)
    }
}

struct NativeAdvertisementRowLayoutPolicy {
    static let contentInset: CGFloat = 10
    // Keep image creatives in the same compact row geometry as the feed cards.
    // Production restricts COMMUNITY_FEED to Image in AdMob, while test builds
    // use Google's Native demo unit rather than its separate Native Video unit.
    // The separate app icon is hidden so only the primary image is shown.
    static let mediaSideLength: CGFloat = 64
    static let mediaCornerRadius: CGFloat = 9
    static let sectionSpacing: CGFloat = 10
    static let mainContentSpacing: CGFloat = 10
    static let textStackSpacing: CGFloat = 6
    static let metadataSpacing: CGFloat = 7
    static let callToActionMinimumHeight: CGFloat = 28
    static let headlineLineLimit = 2
    static let callToActionLineLimit = 1
    static let minimumHeight = mediaSideLength + (contentInset * 2)

    static func resolvedHeight(fittingHeight: CGFloat) -> CGFloat {
        max(minimumHeight, fittingHeight)
    }
}

enum NativeAdvertisementSlotResolution: Equatable {
    case loadingAdMob
    case displayingAdMob
    case loadingFallback
    case displayingFallback
    case unavailable
}

struct NativeAdvertisementSlotStateMachine: Equatable {
    private(set) var resolution: NativeAdvertisementSlotResolution = .loadingAdMob

    @discardableResult
    mutating func resolveAdMob(available: Bool) -> Bool {
        guard resolution == .loadingAdMob else { return false }
        resolution = available ? .displayingAdMob : .loadingFallback
        return true
    }

    @discardableResult
    mutating func resolveFallback(available: Bool) -> Bool {
        guard resolution == .loadingFallback else { return false }
        resolution = available ? .displayingFallback : .unavailable
        return true
    }
}

enum AdMobIdentifierPolicy {
    static let googleDemoPublisherPrefix = "ca-app-pub-3940256099942544"
    static let sampleAppID = "ca-app-pub-3940256099942544~1458002511"
    static let sampleImageNativeAdUnitID = "ca-app-pub-3940256099942544/3986624511"
    static let sampleVideoNativeAdUnitID = "ca-app-pub-3940256099942544/2521693316"

    static func isValidAppID(_ value: String, allowsSample: Bool) -> Bool {
        isValid(value, separator: "~", allowsSample: allowsSample)
    }

    static func isValidNativeAdUnitID(_ value: String, allowsSample: Bool) -> Bool {
        isValid(value, separator: "/", allowsSample: allowsSample)
    }

    private static func isValid(
        _ value: String,
        separator: Character,
        allowsSample: Bool
    ) -> Bool {
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard allowsSample || !normalized.hasPrefix(googleDemoPublisherPrefix) else { return false }
        let prefix = "ca-app-pub-"
        guard normalized.hasPrefix(prefix) else { return false }
        let body = normalized.dropFirst(prefix.count)
        let components = body.split(separator: separator, omittingEmptySubsequences: false)
        guard components.count == 2,
              components[0].count == 16,
              components[1].count == 10 else {
            return false
        }
        return components.allSatisfy { component in
            component.allSatisfy { $0.isNumber }
        }
    }
}

struct AdMobPrivacyAuthorization: Equatable {
    var permitsRequest: Bool
    var generation: UInt64

    func permitsResult(from generation: UInt64) -> Bool {
        permitsRequest && self.generation == generation
    }
}

enum AdMobPrivacyConsentStatus: Equatable {
    case notRequired
    case obtained
    case unavailable
}

enum AdMobPrivacyPreparationPolicy {
    static let purposeConsentsKey = "IABTCF_PurposeConsents"
    static let gdprAppliesKey = "IABTCF_gdprApplies"
    static let gppStringKey = "IABGPP_HDR_GppString"

    static func permitsAdMobRequest(
        didRefreshConsentInformation: Bool,
        didCompleteConsentGathering: Bool,
        canRequestAds: Bool,
        consentStatus: AdMobPrivacyConsentStatus,
        gdprApplies: Int?,
        purposeConsents: String?,
        gppString: String?
    ) -> Bool {
        guard didRefreshConsentInformation,
              didCompleteConsentGathering,
              canRequestAds else {
            return false
        }

        switch consentStatus {
        case .notRequired:
            return true
        case .obtained:
            switch gdprApplies {
            case 1:
                return purposeConsents?.first == "1"
            case 0:
                return true
            case nil:
                return gppString?.isEmpty == false
            default:
                return false
            }
        case .unavailable:
            return false
        }
    }
}

enum AdMobAppConfiguration {
    static let nativeAdUnitInfoKey = "BuddyStudyAdMobNativeAdUnitID"

    static var nativeAdUnitID: String? {
        guard let value = Bundle.main.object(forInfoDictionaryKey: nativeAdUnitInfoKey) as? String else {
            return nil
        }
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return AdMobIdentifierPolicy.isValidNativeAdUnitID(normalized, allowsSample: true)
            ? normalized
            : nil
    }
}

@MainActor
final class AdMobPrivacyCoordinator: ObservableObject {
    static let shared = AdMobPrivacyCoordinator()

    @Published private(set) var isPrivacyOptionsRequired = false
    @Published private(set) var lastErrorDescription: String?

    private enum PreparationState {
        case idle
        case preparing
        case prepared(AdMobPrivacyAuthorization)
    }

    private var preparationState: PreparationState = .idle
    private var waitingCompletions: [(AdMobPrivacyAuthorization) -> Void] = []
    private var didConfigureMobileAds = false
    private var didStartMobileAds = false
    private var nextAuthorizationGeneration: UInt64 = 1

    @Published private(set) var currentAuthorization = AdMobPrivacyAuthorization(
        permitsRequest: false,
        generation: 0
    )

    private init() {
        isPrivacyOptionsRequired =
            ConsentInformation.shared.privacyOptionsRequirementStatus == .required
    }

    func prepareForAppLaunch() {
        prepare { _ in }
    }

    func prepare(completion: @escaping (AdMobPrivacyAuthorization) -> Void) {
        switch preparationState {
        case .prepared(let authorization):
            completion(authorization)
            return
        case .preparing:
            waitingCompletions.append(completion)
            return
        case .idle:
            waitingCompletions.append(completion)
            preparationState = .preparing
        }

        Task { @MainActor [weak self] in
            await self?.performPreparation()
        }
    }

    func presentPrivacyOptions() async {
        await withCheckedContinuation { continuation in
            prepare { _ in
                continuation.resume()
            }
        }

        var didCompleteConsentGathering = false
        do {
            try await ConsentForm.presentPrivacyOptionsForm(from: nil)
            lastErrorDescription = nil
            didCompleteConsentGathering = true
        } catch {
            lastErrorDescription = error.localizedDescription
        }
        updatePrivacyOptionsRequirement()
        if case .prepared = preparationState {
            let permitsRequest = AdMobPrivacyPreparationPolicy.permitsAdMobRequest(
                didRefreshConsentInformation: true,
                didCompleteConsentGathering: didCompleteConsentGathering,
                canRequestAds: ConsentInformation.shared.canRequestAds,
                consentStatus: currentConsentStatus,
                gdprApplies: currentGDPRApplicability,
                purposeConsents: UserDefaults.standard.string(
                    forKey: AdMobPrivacyPreparationPolicy.purposeConsentsKey
                ),
                gppString: UserDefaults.standard.string(
                    forKey: AdMobPrivacyPreparationPolicy.gppStringKey
                )
            )
            let authorization = updateAuthorization(
                permitsRequest: permitsRequest,
                forceNewGeneration: true
            )
            preparationState = .prepared(authorization)
        }
    }

    private func performPreparation() async {
        let parameters = RequestParameters()
        var didUpdateConsentInformation = false
        var didCompleteConsentGathering = false

        do {
            try await ConsentInformation.shared.requestConsentInfoUpdate(with: parameters)
            didUpdateConsentInformation = true
            lastErrorDescription = nil
        } catch {
            lastErrorDescription = error.localizedDescription
        }

        updatePrivacyOptionsRequirement()

        if didUpdateConsentInformation {
            do {
                try await ConsentForm.loadAndPresentIfRequired(from: nil)
                lastErrorDescription = nil
                didCompleteConsentGathering = true
            } catch {
                lastErrorDescription = error.localizedDescription
            }
            updatePrivacyOptionsRequirement()
        }

        let permitsRequest = AdMobPrivacyPreparationPolicy.permitsAdMobRequest(
            didRefreshConsentInformation: didUpdateConsentInformation,
            didCompleteConsentGathering: didCompleteConsentGathering,
            canRequestAds: ConsentInformation.shared.canRequestAds,
            consentStatus: currentConsentStatus,
            gdprApplies: currentGDPRApplicability,
            purposeConsents: UserDefaults.standard.string(
                forKey: AdMobPrivacyPreparationPolicy.purposeConsentsKey
            ),
            gppString: UserDefaults.standard.string(
                forKey: AdMobPrivacyPreparationPolicy.gppStringKey
            )
        )
        let authorization = updateAuthorization(
            permitsRequest: permitsRequest,
            forceNewGeneration: true
        )

        preparationState = .prepared(authorization)
        let completions = waitingCompletions
        waitingCompletions.removeAll()
        completions.forEach { $0(authorization) }
    }

    private func updateAuthorization(
        permitsRequest: Bool,
        forceNewGeneration: Bool
    ) -> AdMobPrivacyAuthorization {
        if forceNewGeneration {
            currentAuthorization = AdMobPrivacyAuthorization(
                permitsRequest: permitsRequest,
                generation: nextAuthorizationGeneration
            )
            nextAuthorizationGeneration &+= 1
            AdMobNativeAdCoordinator.shared.privacyAuthorizationDidChange(
                to: currentAuthorization
            )
        } else {
            currentAuthorization.permitsRequest = permitsRequest
        }
        return currentAuthorization
    }

    private func updatePrivacyOptionsRequirement() {
        isPrivacyOptionsRequired =
            ConsentInformation.shared.privacyOptionsRequirementStatus == .required
    }

    private var currentConsentStatus: AdMobPrivacyConsentStatus {
        switch ConsentInformation.shared.consentStatus {
        case .notRequired:
            return .notRequired
        case .obtained:
            return .obtained
        case .unknown, .required:
            return .unavailable
        @unknown default:
            return .unavailable
        }
    }

    private var currentGDPRApplicability: Int? {
        let storedValue = UserDefaults.standard.object(
            forKey: AdMobPrivacyPreparationPolicy.gdprAppliesKey
        )
        let value: Int?
        if let number = storedValue as? NSNumber {
            value = number.intValue
        } else if let string = storedValue as? String {
            value = Int(string)
        } else {
            value = nil
        }
        guard value == 0 || value == 1 else { return nil }
        return value
    }

    func startMobileAdsIfAuthorized(
        _ authorization: AdMobPrivacyAuthorization
    ) -> Bool {
        guard authorization.permitsRequest,
              authorization == currentAuthorization else {
            return false
        }

        if !didConfigureMobileAds {
            let mobileAds = MobileAds.shared
            let requestConfiguration = mobileAds.requestConfiguration
            requestConfiguration.ageRestrictedTreatment = .teen
            requestConfiguration.maxAdContentRating = GADMaxAdContentRating.teen
            requestConfiguration.publisherPrivacyPersonalizationState = .disabled
            requestConfiguration.setPublisherFirstPartyIDEnabled(false)
            mobileAds.disableSDKCrashReporting()
            didConfigureMobileAds = true
        }

        if !didStartMobileAds {
            didStartMobileAds = true
            MobileAds.shared.start()
        }
        return true
    }
}

@MainActor
private final class AdMobNativeAdLoadAttempt: NSObject, NativeAdLoaderDelegate {
    let slotID: String

    private var adLoader: AdLoader?
    private var timeoutTask: Task<Void, Never>?
    private var completion: ((NativeAd?) -> Void)?
    private var didFinish = false

    init(slotID: String, completion: @escaping (NativeAd?) -> Void) {
        self.slotID = slotID
        self.completion = completion
    }

    func start(adUnitID: String, rootViewController: UIViewController) {
        let viewOptions = NativeAdViewAdOptions()
        viewOptions.preferredAdChoicesPosition = .topRightCorner

        let adLoader = AdLoader(
            adUnitID: adUnitID,
            rootViewController: rootViewController,
            adTypes: [.native],
            options: [viewOptions]
        )
        adLoader.delegate = self
        self.adLoader = adLoader

        let request = Request()
        let extras = Extras()
        extras.additionalParameters = ["npa": "1"]
        request.register(extras)
        adLoader.load(request)

        timeoutTask = Task { @MainActor [weak self] in
            do {
                try await Task.sleep(for: NativeAdvertisementRequestPolicy.loadTimeout)
                self?.finish(with: nil)
            } catch {
                return
            }
        }
    }

    func adLoader(_ adLoader: AdLoader, didReceive nativeAd: NativeAd) {
        finish(with: nativeAd)
    }

    func adLoader(_ adLoader: AdLoader, didFailToReceiveAdWithError error: Error) {
        finish(with: nil)
    }

    func cancel() {
        finish(with: nil)
    }

    private func finish(with nativeAd: NativeAd?) {
        guard !didFinish else { return }
        didFinish = true
        timeoutTask?.cancel()
        timeoutTask = nil
        adLoader = nil
        let completion = completion
        self.completion = nil
        completion?(nativeAd)
    }
}

@MainActor
final class AdMobNativeAdCoordinator: NSObject, NativeAdDelegate {
    static let shared = AdMobNativeAdCoordinator()

    private struct CachedAd {
        var nativeAd: NativeAd
        var loadedAt: Date
        var authorizationGeneration: UInt64
    }

    private struct EventHandlers {
        var onImpression: () -> Void
        var onClick: () -> Void
    }

    private var cache: [String: CachedAd] = [:]
    private var pendingCompletions: [String: [(NativeAd?) -> Void]] = [:]
    private var activeAttempt: AdMobNativeAdLoadAttempt?
    private var lastRequestAt: Date?
    private var slotByNativeAdIdentity: [ObjectIdentifier: String] = [:]
    private var eventHandlersBySlotID: [String: EventHandlers] = [:]

    private override init() {}

    func requestNativeAd(
        for slotID: String,
        onImpression: @escaping () -> Void,
        onClick: @escaping () -> Void,
        completion: @escaping (NativeAd?) -> Void
    ) {
        eventHandlersBySlotID[slotID] = EventHandlers(
            onImpression: onImpression,
            onClick: onClick
        )

        if pendingCompletions[slotID] != nil {
            pendingCompletions[slotID, default: []].append(completion)
            return
        }
        pendingCompletions[slotID] = [completion]

        AdMobPrivacyCoordinator.shared.prepare { [weak self] authorization in
            guard let self else { return }
            let now = Date()

            if let cached = cache[slotID] {
                if NativeAdvertisementRequestPolicy.canUseLoadedAdvertisement(
                    loadedAt: cached.loadedAt,
                    now: now,
                    loadedAuthorizationGeneration: cached.authorizationGeneration,
                    currentAuthorization: authorization
                ) {
                    finish(
                        slotID: slotID,
                        nativeAd: cached.nativeAd,
                        authorizationGeneration: authorization.generation,
                        shouldCache: false
                    )
                    return
                }
                cache.removeValue(forKey: slotID)
                slotByNativeAdIdentity.removeValue(forKey: ObjectIdentifier(cached.nativeAd))
            }

            guard authorization.permitsRequest,
                  activeAttempt == nil,
                  NativeAdvertisementRequestPolicy.canStartRequest(
                    lastRequestAt: lastRequestAt,
                    now: Date()
                  ),
                  let adUnitID = AdMobAppConfiguration.nativeAdUnitID,
                  let rootViewController = AdMobPresentationContext.rootViewController,
                  AdMobPrivacyCoordinator.shared.startMobileAdsIfAuthorized(
                    authorization
                  ) else {
                finish(
                    slotID: slotID,
                    nativeAd: nil,
                    authorizationGeneration: authorization.generation
                )
                return
            }

            lastRequestAt = Date()
            let authorizationGeneration = authorization.generation
            let attempt = AdMobNativeAdLoadAttempt(slotID: slotID) { [weak self] nativeAd in
                self?.finish(
                    slotID: slotID,
                    nativeAd: nativeAd,
                    authorizationGeneration: authorizationGeneration
                )
            }
            activeAttempt = attempt
            attempt.start(
                adUnitID: adUnitID,
                rootViewController: rootViewController
            )
        }
    }

    func nativeAdDidRecordImpression(_ nativeAd: NativeAd) {
        guard let slotID = slotByNativeAdIdentity[ObjectIdentifier(nativeAd)] else { return }
        eventHandlersBySlotID[slotID]?.onImpression()
    }

    func nativeAdDidRecordClick(_ nativeAd: NativeAd) {
        guard let slotID = slotByNativeAdIdentity[ObjectIdentifier(nativeAd)] else { return }
        eventHandlersBySlotID[slotID]?.onClick()
    }

    func privacyAuthorizationDidChange(to _: AdMobPrivacyAuthorization) {
        for cached in cache.values {
            slotByNativeAdIdentity.removeValue(forKey: ObjectIdentifier(cached.nativeAd))
        }
        cache.removeAll()

        guard activeAttempt != nil else { return }
        activeAttempt?.cancel()
    }

    private func finish(
        slotID: String,
        nativeAd: NativeAd?,
        authorizationGeneration: UInt64,
        shouldCache: Bool = true
    ) {
        if activeAttempt?.slotID == slotID {
            activeAttempt = nil
        }

        let currentAuthorization = AdMobPrivacyCoordinator.shared.currentAuthorization
        let acceptedNativeAd = currentAuthorization.permitsResult(
            from: authorizationGeneration
        ) ? nativeAd : nil

        if let acceptedNativeAd, shouldCache {
            acceptedNativeAd.delegate = self
            cache[slotID] = CachedAd(
                nativeAd: acceptedNativeAd,
                loadedAt: Date(),
                authorizationGeneration: authorizationGeneration
            )
            slotByNativeAdIdentity[ObjectIdentifier(acceptedNativeAd)] = slotID
        }

        let completions = pendingCompletions.removeValue(forKey: slotID) ?? []
        completions.forEach { $0(acceptedNativeAd) }
    }
}

@MainActor
private enum AdMobPresentationContext {
    static var rootViewController: UIViewController? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .filter { $0.activationState == .foregroundActive }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)?
            .rootViewController
    }
}

@MainActor
final class MobileNativeAdvertisementSlotViewModel: ObservableObject {
    enum Phase {
        case loadingAdMob
        case displayingAdMob(NativeAd)
        case loadingFallback
        case displayingFallback(CommunityNativeAdvertisement)
        case unavailable
    }

    @Published private(set) var phase: Phase = .loadingAdMob

    var resolution: NativeAdvertisementSlotResolution {
        stateMachine.resolution
    }

    private var stateMachine = NativeAdvertisementSlotStateMachine()
    private var startedSlotID: String?
    private var fallbackBeforeHiding: CommunityNativeAdvertisement?

    func start(
        slot: CommunityNativeAdvertisementSlot,
        adMobLoader: @escaping @MainActor (
            _ slotID: String,
            _ onImpression: @escaping () -> Void,
            _ onClick: @escaping () -> Void,
            _ completion: @escaping (NativeAd?) -> Void
        ) -> Void,
        fallbackLoader: @escaping @MainActor () async -> CommunityNativeAdvertisement?,
        onAdMobImpression: @escaping @MainActor () -> Void,
        onAdMobClick: @escaping @MainActor () -> Void
    ) {
        guard startedSlotID == nil else { return }
        startedSlotID = slot.slotID

        adMobLoader(
            slot.slotID,
            onAdMobImpression,
            onAdMobClick
        ) { [weak self] nativeAd in
            guard let self, startedSlotID == slot.slotID else { return }
            guard stateMachine.resolveAdMob(available: nativeAd != nil) else { return }
            if let nativeAd {
                phase = .displayingAdMob(nativeAd)
            } else {
                phase = .loadingFallback
                loadFallback(using: fallbackLoader, slotID: slot.slotID)
            }
        }
    }

    func hideFallback() {
        guard case .displayingFallback(let advertisement) = phase else { return }
        fallbackBeforeHiding = advertisement
        phase = .unavailable
    }

    func restoreFallback() {
        guard let fallbackBeforeHiding else { return }
        phase = .displayingFallback(fallbackBeforeHiding)
        self.fallbackBeforeHiding = nil
    }

    func confirmFallbackHidden() {
        fallbackBeforeHiding = nil
    }

    func invalidateAdMob(
        using fallbackLoader: @escaping @MainActor () async -> CommunityNativeAdvertisement?
    ) {
        guard let startedSlotID else { return }
        switch phase {
        case .loadingAdMob, .displayingAdMob:
            stateMachine = NativeAdvertisementSlotStateMachine()
            guard stateMachine.resolveAdMob(available: false) else { return }
            phase = .loadingFallback
            loadFallback(using: fallbackLoader, slotID: startedSlotID)
        case .loadingFallback, .displayingFallback, .unavailable:
            return
        }
    }

    private func loadFallback(
        using loader: @escaping @MainActor () async -> CommunityNativeAdvertisement?,
        slotID: String
    ) {
        Task { @MainActor [weak self] in
            let advertisement = await loader()
            guard let self, startedSlotID == slotID else { return }
            guard stateMachine.resolveFallback(available: advertisement != nil) else { return }
            if let advertisement {
                phase = .displayingFallback(advertisement)
            } else {
                phase = .unavailable
            }
        }
    }
}

struct MobileNativeAdvertisementSlotRow: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.openURL) private var openURL
    @StateObject private var viewModel = MobileNativeAdvertisementSlotViewModel()
    @ObservedObject private var privacyCoordinator = AdMobPrivacyCoordinator.shared
    @State private var isShowingSelectionExplanation = false
    @State private var advertisementReportTarget: CommunityNativeAdvertisement?

    var slot: CommunityNativeAdvertisementSlot
    var strings: AppStrings

    var body: some View {
        Group {
            switch viewModel.phase {
            case .loadingAdMob, .loadingFallback:
                loadingPlaceholder
                    .frame(minHeight: NativeAdvertisementRowLayoutPolicy.minimumHeight)
            case .displayingAdMob(let nativeAd):
                MobileAdMobNativeAdView(
                    nativeAd: nativeAd,
                    advertisementLabel: strings.advertisementLabel
                )
            case .displayingFallback(let advertisement):
                fallbackView(advertisement)
            case .unavailable:
                Color.clear
                    .frame(height: 0)
                    .accessibilityHidden(true)
            }
        }
        .frame(maxWidth: .infinity)
        .task(id: slot.slotID) {
            viewModel.start(
                slot: slot,
                adMobLoader: { slotID, onImpression, onClick, completion in
                    AdMobNativeAdCoordinator.shared.requestNativeAd(
                        for: slotID,
                        onImpression: onImpression,
                        onClick: onClick,
                        completion: completion
                    )
                },
                fallbackLoader: {
                    await appState.fetchNativeAdvertisementFallback(slotID: slot.slotID)
                },
                onAdMobImpression: {
                    Task {
                        await appState.recordAdMobNativeAdvertisementImpression(slotID: slot.slotID)
                    }
                },
                onAdMobClick: {
                    Task {
                        await appState.recordAdMobNativeAdvertisementClick(slotID: slot.slotID)
                    }
                }
            )
        }
        .modifier(
            MobileNativeAdvertisementReviewModifier(
                isShowingSelectionExplanation: $isShowingSelectionExplanation,
                advertisementReportTarget: $advertisementReportTarget,
                slotID: slot.slotID,
                strings: strings
            )
        )
        .onChange(of: privacyCoordinator.currentAuthorization) { previous, current in
            guard previous.generation > 0,
                  previous.generation != current.generation else {
                return
            }
            viewModel.invalidateAdMob {
                await appState.fetchNativeAdvertisementFallback(slotID: slot.slotID)
            }
        }
    }

    private var loadingPlaceholder: some View {
        ProgressView()
            .controlSize(.small)
            .accessibilityLabel(strings.advertisementLoading)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .allowsHitTesting(false)
    }

    private func fallbackView(_ advertisement: CommunityNativeAdvertisement) -> some View {
        HStack(alignment: .top, spacing: 2) {
            Button {
                openFallbackAdvertisement(advertisement)
            } label: {
                MobileNativeAdvertisementRow(
                    advertisement: advertisement,
                    strings: strings,
                    style: .compactSlot
                )
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .frame(maxWidth: .infinity, alignment: .leading)

            Menu {
                selectionExplanationButton
                reportButton(advertisement)
                notInterestedButton(advertisement)
            } label: {
                Image(systemName: "ellipsis")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Color.secondary)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(strings.more)
        }
        .contextMenu {
            selectionExplanationButton
            reportButton(advertisement)
            notInterestedButton(advertisement)
        }
        .background {
            MobileNativeAdvertisementImpressionReporter {
                await appState.recordNativeAdvertisementImpression(
                    selectionID: advertisement.selectionID
                )
            }
        }
    }

    private var selectionExplanationButton: some View {
        Button {
            isShowingSelectionExplanation = true
        } label: {
            Label(strings.advertisementWhyShown, systemImage: "info.circle")
        }
    }

    private func reportButton(_ advertisement: CommunityNativeAdvertisement) -> some View {
        Button(role: .destructive) {
            advertisementReportTarget = advertisement
        } label: {
            Label(strings.advertisementReport, systemImage: "exclamationmark.bubble")
        }
    }

    private func notInterestedButton(_ advertisement: CommunityNativeAdvertisement) -> some View {
        Button {
            viewModel.hideFallback()
            Task {
                let didSuppress = await appState.suppressNativeAdvertisement(
                    selectionID: advertisement.selectionID,
                    campaignID: advertisement.campaignID
                )
                if didSuppress {
                    viewModel.confirmFallbackHidden()
                } else {
                    viewModel.restoreFallback()
                }
            }
        } label: {
            Label(strings.advertisementNotInterested, systemImage: "eye.slash")
        }
    }

    private func openFallbackAdvertisement(_ advertisement: CommunityNativeAdvertisement) {
        guard let url = URL(string: advertisement.deepLink) else { return }
        Task {
            await appState.recordNativeAdvertisementView(selectionID: advertisement.selectionID)
        }
        if let route = AppRoute(url: url) {
            _ = appState.openRoute(route)
        } else if url.scheme?.caseInsensitiveCompare("https") == .orderedSame {
            openURL(url)
        }
    }

}

struct MobileNativeAdvertisementReviewModifier: ViewModifier {
    @EnvironmentObject private var appState: AppState
    @Binding var isShowingSelectionExplanation: Bool
    @Binding var advertisementReportTarget: CommunityNativeAdvertisement?

    var slotID: String?
    var strings: AppStrings

    func body(content: Content) -> some View {
        content
            .alert(
                strings.advertisementWhyShown,
                isPresented: $isShowingSelectionExplanation
            ) {
                Button(strings.done, role: .cancel) {}
            } message: {
                Text(strings.advertisementWhyShownExplanation)
            }
            .confirmationDialog(
                strings.advertisementReport,
                isPresented: reportDialogBinding,
                titleVisibility: .visible
            ) {
                if let advertisementReportTarget {
                    Button(strings.advertisementReportInappropriate, role: .destructive) {
                        report(advertisementReportTarget, reason: .inappropriate)
                    }
                    Button(strings.advertisementReportAgeInappropriate, role: .destructive) {
                        report(advertisementReportTarget, reason: .ageInappropriate)
                    }
                }
                Button(strings.cancel, role: .cancel) {}
            } message: {
                Text(strings.advertisementReportPrompt)
            }
    }

    private var reportDialogBinding: Binding<Bool> {
        Binding(
            get: { advertisementReportTarget != nil },
            set: { isPresented in
                if !isPresented {
                    advertisementReportTarget = nil
                }
            }
        )
    }

    private func report(
        _ advertisement: CommunityNativeAdvertisement,
        reason: NativeAdvertisementReportReason
    ) {
        advertisementReportTarget = nil
        Task {
            _ = await appState.reportNativeAdvertisement(
                advertisement,
                slotID: slotID,
                reason: reason
            )
        }
    }
}

private struct MobileAdMobNativeAdView: UIViewRepresentable {
    var nativeAd: NativeAd
    var advertisementLabel: String

    func makeUIView(context: Context) -> BuddyStudyNativeAdView {
        BuddyStudyNativeAdView()
    }

    func updateUIView(_ nativeAdView: BuddyStudyNativeAdView, context: Context) {
        nativeAdView.populate(with: nativeAd, advertisementLabel: advertisementLabel)
    }

    func sizeThatFits(
        _ proposal: ProposedViewSize,
        uiView: BuddyStudyNativeAdView,
        context: Context
    ) -> CGSize? {
        guard let width = proposal.width, width > 0 else { return nil }
        let fittingSize = uiView.systemLayoutSizeFitting(
            CGSize(width: width, height: UIView.layoutFittingCompressedSize.height),
            withHorizontalFittingPriority: .required,
            verticalFittingPriority: .fittingSizeLevel
        )
        return CGSize(
            width: width,
            height: NativeAdvertisementRowLayoutPolicy.resolvedHeight(
                fittingHeight: fittingSize.height
            )
        )
    }
}

private final class BuddyStudyNativeAdView: NativeAdView {
    private let media = MediaView()
    private let headlineLabel = UILabel()
    private let advertiserLabel = UILabel()
    private let badgeLabel = UILabel()
    private let callToActionButton = UIButton(type: .system)
    private let choicesView = AdChoicesView()

    override init(frame: CGRect) {
        super.init(frame: frame)
        configureView()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        configureView()
    }

    func populate(with nativeAd: NativeAd, advertisementLabel: String) {
        self.nativeAd = nil

        badgeLabel.text = advertisementLabel
        advertiserLabel.text = nativeAd.advertiser?.trimmingCharacters(in: .whitespacesAndNewlines)
        advertiserLabel.isHidden = advertiserLabel.text?.isEmpty != false
        headlineLabel.text = nativeAd.headline
        media.mediaContent = nativeAd.mediaContent

        callToActionButton.setTitle(nativeAd.callToAction, for: .normal)
        callToActionButton.isHidden = nativeAd.callToAction?.isEmpty != false
        callToActionButton.isUserInteractionEnabled = false

        self.nativeAd = nativeAd
    }

    private func configureView() {
        backgroundColor = .clear

        media.translatesAutoresizingMaskIntoConstraints = false
        media.contentMode = .scaleAspectFill
        media.clipsToBounds = true
        media.layer.cornerRadius = NativeAdvertisementRowLayoutPolicy.mediaCornerRadius
        media.setContentHuggingPriority(.required, for: .horizontal)
        media.setContentCompressionResistancePriority(.required, for: .horizontal)

        headlineLabel.textColor = .label
        headlineLabel.setContentCompressionResistancePriority(.required, for: .vertical)

        advertiserLabel.textColor = .secondaryLabel
        advertiserLabel.lineBreakMode = .byTruncatingTail
        advertiserLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        badgeLabel.textAlignment = .center
        badgeLabel.textColor = .secondaryLabel
        badgeLabel.backgroundColor = UIColor.tertiarySystemFill
        badgeLabel.layer.cornerRadius = 4
        badgeLabel.layer.masksToBounds = true
        badgeLabel.setContentHuggingPriority(.required, for: .horizontal)

        var callToActionConfiguration = UIButton.Configuration.filled()
        callToActionConfiguration.baseBackgroundColor = .tertiarySystemFill
        callToActionConfiguration.baseForegroundColor = .secondaryLabel
        callToActionConfiguration.cornerStyle = .capsule
        callToActionConfiguration.contentInsets = NSDirectionalEdgeInsets(
            top: 4,
            leading: 9,
            bottom: 4,
            trailing: 9
        )
        callToActionButton.configuration = callToActionConfiguration
        callToActionButton.titleLabel?.textAlignment = .center
        callToActionButton.setContentHuggingPriority(.required, for: .horizontal)
        callToActionButton.setContentCompressionResistancePriority(.required, for: .horizontal)
        callToActionButton.setContentCompressionResistancePriority(.required, for: .vertical)
        updateTypography()
        updateLineLimits()
        _ = registerForTraitChanges([UITraitPreferredContentSizeCategory.self]) {
            (view: BuddyStudyNativeAdView, _) in
            view.updateTypography()
            view.updateLineLimits()
        }

        choicesView.translatesAutoresizingMaskIntoConstraints = false
        choicesView.setContentHuggingPriority(.required, for: .horizontal)
        choicesView.setContentCompressionResistancePriority(.required, for: .horizontal)

        let metadataSpacer = UIView()
        metadataSpacer.setContentHuggingPriority(.defaultLow, for: .horizontal)
        metadataSpacer.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        // AdChoices is rendered and colored by Google. Keep it beside the ad
        // attribution instead of in the trailing overflow-menu position used by
        // regular feed cards.
        let metadataRow = UIStackView(
            arrangedSubviews: [
                advertiserLabel,
                badgeLabel,
                choicesView,
                metadataSpacer,
            ]
        )
        metadataRow.axis = .horizontal
        metadataRow.alignment = .center
        metadataRow.spacing = NativeAdvertisementRowLayoutPolicy.metadataSpacing

        let callToActionSpacer = UIView()
        callToActionSpacer.setContentHuggingPriority(.defaultLow, for: .horizontal)
        callToActionSpacer.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        let callToActionRow = UIStackView(
            arrangedSubviews: [callToActionButton, callToActionSpacer]
        )
        callToActionRow.axis = .horizontal
        callToActionRow.alignment = .center

        let textStack = UIStackView(
            arrangedSubviews: [headlineLabel, callToActionRow]
        )
        textStack.axis = .vertical
        textStack.alignment = .fill
        textStack.spacing = NativeAdvertisementRowLayoutPolicy.textStackSpacing

        let mainContentRow = UIStackView(arrangedSubviews: [textStack, media])
        mainContentRow.axis = .horizontal
        mainContentRow.alignment = .top
        mainContentRow.spacing = NativeAdvertisementRowLayoutPolicy.mainContentSpacing

        let contentStack = UIStackView(arrangedSubviews: [metadataRow, mainContentRow])
        contentStack.translatesAutoresizingMaskIntoConstraints = false
        contentStack.axis = .vertical
        contentStack.alignment = .fill
        contentStack.spacing = NativeAdvertisementRowLayoutPolicy.sectionSpacing

        addSubview(contentStack)

        NSLayoutConstraint.activate([
            contentStack.leadingAnchor.constraint(equalTo: leadingAnchor),
            contentStack.trailingAnchor.constraint(equalTo: trailingAnchor),
            contentStack.topAnchor.constraint(
                equalTo: topAnchor,
                constant: NativeAdvertisementRowLayoutPolicy.contentInset
            ),
            contentStack.bottomAnchor.constraint(
                equalTo: bottomAnchor,
                constant: -NativeAdvertisementRowLayoutPolicy.contentInset
            ),
            media.widthAnchor.constraint(
                equalToConstant: NativeAdvertisementRowLayoutPolicy.mediaSideLength
            ),
            media.heightAnchor.constraint(equalTo: media.widthAnchor),

            badgeLabel.heightAnchor.constraint(greaterThanOrEqualToConstant: 18),
            badgeLabel.widthAnchor.constraint(greaterThanOrEqualToConstant: 24),
            callToActionButton.heightAnchor.constraint(
                greaterThanOrEqualToConstant: NativeAdvertisementRowLayoutPolicy.callToActionMinimumHeight
            ),

            choicesView.widthAnchor.constraint(greaterThanOrEqualToConstant: 20),
            choicesView.heightAnchor.constraint(greaterThanOrEqualToConstant: 20),
        ])

        headlineView = headlineLabel
        advertiserView = advertiserLabel
        callToActionView = callToActionButton
        mediaView = media
        adChoicesView = choicesView
    }

    private func updateTypography() {
        advertiserLabel.font = scaledFont(textStyle: .caption1, pointSize: 12, weight: .semibold)
        badgeLabel.font = scaledFont(textStyle: .caption2, pointSize: 11, weight: .semibold)
        headlineLabel.font = scaledFont(textStyle: .body, pointSize: 17, weight: .medium)
        callToActionButton.titleLabel?.font = scaledFont(
            textStyle: .footnote,
            pointSize: 13,
            weight: .semibold
        )
    }

    private func scaledFont(
        textStyle: UIFont.TextStyle,
        pointSize: CGFloat,
        weight: UIFont.Weight
    ) -> UIFont {
        UIFontMetrics(forTextStyle: textStyle).scaledFont(
            for: .systemFont(ofSize: pointSize, weight: weight),
            compatibleWith: traitCollection
        )
    }

    private func updateLineLimits() {
        let usesAccessibilityLayout = traitCollection.preferredContentSizeCategory.isAccessibilityCategory
        headlineLabel.numberOfLines = usesAccessibilityLayout
            ? 0
            : NativeAdvertisementRowLayoutPolicy.headlineLineLimit
        headlineLabel.lineBreakMode = usesAccessibilityLayout ? .byWordWrapping : .byTruncatingTail
        callToActionButton.titleLabel?.numberOfLines = usesAccessibilityLayout
            ? 0
            : NativeAdvertisementRowLayoutPolicy.callToActionLineLimit
        callToActionButton.titleLabel?.lineBreakMode = usesAccessibilityLayout
            ? .byWordWrapping
            : .byTruncatingTail
    }
}
#endif
