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
