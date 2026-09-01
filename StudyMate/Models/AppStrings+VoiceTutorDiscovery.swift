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
        case .korean: "어떤 주제로 이야기해 볼까요?"
        case .english: "What topic would you like to talk about?"
        case .japanese: "どのテーマについて話しましょうか？"
        }
    }
}
