import Foundation

enum ProtectedAppPage {
    case publicQuestions
    case myStudies
    case records
    case statistics
    case studyDetail
    case profile

    var accessLogName: String {
        switch self {
        case .publicQuestions:
            return "public-questions"
        case .myStudies:
            return "my-studies"
        case .records:
            return "records"
        case .statistics:
            return "statistics"
        case .studyDetail:
            return "study-detail"
        case .profile:
            return "profile"
        }
    }

    func title(strings: AppStrings) -> String {
        switch self {
        case .publicQuestions:
            return strings.homeScopeAll
        case .myStudies:
            return strings.homeScopeMy
        case .records:
            return strings.tabRecords
        case .statistics:
            return strings.tabStatistics
        case .studyDetail:
            return strings.tabStudy
        case .profile:
            return strings.profile
        }
    }
}

struct PageAccessPrompt: Identifiable, Equatable {
    let id = UUID()
    var title: String
    var message: String
}

enum PageAccessPolicy {
    static func protectedPage(for tab: AppTab) -> ProtectedAppPage? {
        switch tab {
        case .study:
            return .studyDetail
        case .records:
            return .records
        case .statistics:
            return .statistics
        case .home, .settings:
            return nil
        }
    }

    static func canAccess(_ page: ProtectedAppPage, in access: BackendPageAccess) -> Bool {
        switch page {
        case .publicQuestions:
            return access.publicQuestions
        case .myStudies:
            return access.myStudies
        case .records:
            return access.records
        case .statistics:
            return access.stats
        case .studyDetail:
            return access.studyRoom
        case .profile:
            return access.profile
        }
    }

    static func shouldShowLoginGate(
        for _: ProtectedAppPage,
        isSignedIn: Bool
    ) -> Bool {
        !isSignedIn
    }

    static func prompt(for page: ProtectedAppPage, strings: AppStrings) -> PageAccessPrompt {
        PageAccessPrompt(
            title: strings.communityLogin,
            message: ""
        )
    }
}
