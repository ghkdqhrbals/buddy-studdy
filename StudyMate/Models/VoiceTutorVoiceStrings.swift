import Foundation

extension AppStrings {
    var voiceTutorVoiceSetting: String {
        switch language {
        case .korean: "AI 선생님 목소리"
        case .english: "AI tutor voice"
        case .japanese: "AIチューターの声"
        }
    }

    var voiceTutorVoiceSettingHelp: String {
        switch language {
        case .korean: "저장한 목소리는 다음 통화부터 적용돼요."
        case .english: "Your saved voice applies to your next call."
        case .japanese: "保存した声は次の通話から適用されます。"
        }
    }

    func voiceTutorVoiceName(_ voice: VoiceTutorVoice) -> String {
        guard voice != .serverDefault else {
            switch language {
            case .korean: return "기본"
            case .english: return "Default"
            case .japanese: return "デフォルト"
            }
        }
        // Provider voice names are proper names, not descriptions of gender or accent.
        return voice.rawValue.capitalized
    }
}
