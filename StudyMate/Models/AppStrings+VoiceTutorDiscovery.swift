import Foundation

extension AppStrings {
    var voiceTutorDiscoveryTeacher: String {
        switch language {
        case .korean: "AI 선생님"
        case .english: "AI tutor"
        case .japanese: "AI先生"
        }
    }

    var voiceTutorDiscoveryPrompt: String {
        switch language {
        case .korean: "오늘은 어떤 주제를 공부할까요?"
        case .english: "What would you like to study today?"
        case .japanese: "今日はどのテーマを勉強しましょうか？"
        }
    }
}
