import Foundation

/// An installation preference for the next call, never a live session update.
/// The default deliberately omits `voice` so the backend keeps its configured default.
/// Built-in Realtime choices: https://developers.openai.com/api/docs/guides/realtime-conversations#voice-options
enum VoiceTutorVoice: String, CaseIterable, Codable, Identifiable, Sendable {
    case serverDefault = "default"
    case alloy
    case ash
    case ballad
    case coral
    case echo
    case sage
    case shimmer
    case verse
    case marin
    case cedar

    var id: String { rawValue }

    var apiValue: String? {
        self == .serverDefault ? nil : rawValue
    }
}

/// An installation preference resolved once when the next call begins.
/// Following the app language is explicit; speech recognition never selects it.
enum VoiceTutorLanguage: String, CaseIterable, Codable, Identifiable, Sendable {
    case appDefault = "default"
    case korean = "ko"
    case english = "en"
    case japanese = "ja"

    var id: String { rawValue }

    func resolve(appLanguage: AppLanguage) -> AppLanguage {
        switch self {
        case .appDefault: appLanguage
        case .korean: .korean
        case .english: .english
        case .japanese: .japanese
        }
    }
}
