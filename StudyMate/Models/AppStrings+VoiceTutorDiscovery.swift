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
        case .korean: "주제를 이야기해 주세요"
        case .english: "Tell me what you'd like to discuss"
        case .japanese: "話したいテーマを教えてください"
        }
    }
}
