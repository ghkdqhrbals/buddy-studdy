import Foundation

extension AppStrings {
    var voiceTutorLanguageSetting: String {
        switch language {
        case .korean: "통화 기본 언어"
        case .english: "Default call language"
        case .japanese: "通話の基本言語"
        }
    }

    var voiceTutorLanguageSettingHelp: String {
        switch language {
        case .korean: "선택한 언어로 듣고 답해요. 저장하면 다음 통화부터 적용돼요."
        case .english: "Listen and respond in your selected language. Save to apply it to your next call."
        case .japanese: "選択した言語で聞き取り、応答します。保存すると次の通話から適用されます。"
        }
    }

    func voiceTutorLanguageName(_ preference: VoiceTutorLanguage, appLanguage: AppLanguage) -> String {
        let name = preference.resolve(appLanguage: appLanguage).displayName
        guard preference == .appDefault else { return name }
        switch language {
        case .korean: return "앱 언어 · \(name)"
        case .english: return "App language · \(name)"
        case .japanese: return "アプリの言語 · \(name)"
        }
    }

    var voiceTutorVoiceSetting: String {
        switch language {
        case .korean: "버디 목소리"
        case .english: "Buddy’s voice"
        case .japanese: "バディの声"
        }
    }

    var voiceTutorVoiceSettingHelp: String {
        switch language {
        case .korean: "목소리를 누르면 실제 음성을 듣고 선택할 수 있어요. 저장한 선택은 다음 통화부터 적용돼요."
        case .english: "Tap a voice to hear and select it. Your saved choice applies to your next call."
        case .japanese: "声をタップすると実際の音声を聞いて選べます。保存した選択は次の通話から適用されます。"
        }
    }

    var voiceTutorVoicePreviewDisclosure: String {
        switch language {
        case .korean: "미리듣기는 AI가 생성한 음성입니다."
        case .english: "Previews are AI-generated voices."
        case .japanese: "プレビューはAIが生成した音声です。"
        }
    }

    var voiceTutorVoicePreviewPlaying: String {
        switch language {
        case .korean: "미리듣기 재생 중"
        case .english: "Playing preview"
        case .japanese: "プレビューを再生中"
        }
    }

    var voiceTutorVoicePreviewLoading: String {
        switch language {
        case .korean: "미리듣기 불러오는 중"
        case .english: "Loading preview"
        case .japanese: "プレビューを読み込み中"
        }
    }

    var voiceTutorVoicePreviewFailed: String {
        switch language {
        case .korean: "목소리를 불러오지 못했어요. 잠시 후 다시 눌러주세요."
        case .english: "Couldn't load that voice. Try again shortly."
        case .japanese: "音声を読み込めませんでした。しばらくしてからもう一度お試しください。"
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
