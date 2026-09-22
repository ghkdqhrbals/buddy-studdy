import Combine
import Foundation
import StoreKit
import SwiftUI
import UIKit

/// Installation-local engagement counters only; no study content or account identity.
struct StudyReviewProgress: Codable, Equatable {
    var firstCompletionAt: Date?
    var learningDays: [Date] = []
    var requestDates: [Date] = []
    var lastRequestedVersion: String?

    mutating func recordCompletion(at now: Date, calendar: Calendar = .current) {
        if firstCompletionAt == nil { firstCompletionAt = now }
        if !learningDays.contains(where: { calendar.isDate($0, inSameDayAs: now) }) {
            learningDays.append(now)
            learningDays = Array(learningDays.suffix(3))
        }
    }

    func canRequest(at now: Date, version: String) -> Bool {
        guard let firstCompletionAt,
              now.timeIntervalSince(firstCompletionAt) >= 7 * 86_400,
              learningDays.count >= 3,
              !version.isEmpty,
              lastRequestedVersion != version else { return false }
        // Count attempts, since StoreKit does not disclose whether a prompt was shown.
        let recent = requestDates.filter { now.timeIntervalSince($0) < 365 * 86_400 }
        return recent.count < 3 && recent.allSatisfy { now.timeIntervalSince($0) >= 120 * 86_400 }
    }

    mutating func recordRequest(at now: Date, version: String) {
        requestDates = requestDates.filter { now.timeIntervalSince($0) < 365 * 86_400 }
        requestDates.append(now)
        lastRequestedVersion = version
    }
}

struct StudyReviewCompletion: Equatable {
    let recordID: String?
    let isGraded: Bool

    func isNewCompletion(after previous: Self) -> Bool {
        recordID != nil && recordID == previous.recordID && !previous.isGraded && isGraded
    }
}

@MainActor
final class StudyReviewCoordinator: ObservableObject {
    static let shared = StudyReviewCoordinator(store: SettingsStore())
    @Published private(set) var pendingSince: Date?
    private let store: SettingsStore

    init(store: SettingsStore) { self.store = store }

    func recordLearningCompletion(at now: Date = Date()) {
        var progress = store.loadStudyReviewProgress()
        progress.recordCompletion(at: now)
        store.saveStudyReviewProgress(progress)
        pendingSince = now
    }

    func cancelPendingRequest() { pendingSince = nil }

    func consumeRequest(at now: Date = Date(), version: String) -> Bool {
        guard let pendingSince else { return false }
        self.pendingSince = nil
        guard (0...3_600).contains(now.timeIntervalSince(pendingSince)) else { return false }
        var progress = store.loadStudyReviewProgress()
        guard progress.canRequest(at: now, version: version) else { return false }
        progress.recordRequest(at: now, version: version)
        store.saveStudyReviewProgress(progress)
        return true
    }
}

/// Applied only to idle Home. Leaving the screen or opening a sheet cancels the delay.
struct StudyReviewPromptModifier: ViewModifier {
    @EnvironmentObject private var appState: AppState
    @Environment(\.requestReview) private var requestReview
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject private var coordinator = StudyReviewCoordinator.shared
    let isEligibleScreen: Bool

    private var canPresent: Bool {
        isEligibleScreen && scenePhase == .active && appState.isCommunitySessionActive &&
        appState.mobileVisibleTab == .home && appState.homeStudyRoute == nil &&
        !appState.isRequiredTermsGatePresented && appState.homeAnnouncement == nil &&
        appState.referralNotice == nil && !appState.isMembershipScreenshotFixtureEnabled
    }

    func body(content: Content) -> some View {
        content
            .task(id: canPresent ? coordinator.pendingSince : nil) {
                guard canPresent, coordinator.pendingSince != nil else { return }
                do { try await Task.sleep(for: .seconds(2)) } catch { return }
                guard !Task.isCancelled, canPresent, Self.hasUnobstructedWindow else { return }
                let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
                if coordinator.consumeRequest(version: version) {
                    AppAnalytics.reviewRequested()
                    requestReview()
                }
            }
            .onChange(of: appState.isCommunitySessionActive) { _, isActive in
                if !isActive { coordinator.cancelPendingRequest() }
            }
    }

    private static var hasUnobstructedWindow: Bool {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .filter { $0.activationState == .foregroundActive }
            .flatMap(\.windows)
            .contains { $0.isKeyWindow && $0.rootViewController?.presentedViewController == nil }
    }
}
