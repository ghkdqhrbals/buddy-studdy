#if os(iOS)
import Foundation

extension AppStrings {
    var voiceTutorTakeBreak: String { voiceTutorPauseText("잠깐 쉬기", "Take a break", "少し休む") }
    var voiceTutorResumeLesson: String { voiceTutorPauseText("계속하기", "Continue", "続ける") }
    var voiceTutorPausing: String {
        voiceTutorPauseText("말을 마치면 쉬어요", "Finishing this sentence", "話し終えたら休憩")
    }
    var voiceTutorPaused: String { voiceTutorPauseText("잠깐 쉬는 중", "On a break", "休憩中") }
    var voiceTutorResuming: String { voiceTutorPauseText("계속할 준비 중", "Getting ready to continue", "再開の準備中") }
    var voiceTutorPauseUsesTime: String {
        voiceTutorPauseText(
            "연결 중에는 통화 시간이 계속 사용돼요.",
            "Call time continues while connected.",
            "接続中は通話時間を消費します。"
        )
    }
    var voiceTutorPauseFailed: String {
        voiceTutorPauseText(
            "쉬는 상태를 확인하지 못했어요. 다시 연결해 주세요.",
            "We couldn't confirm the break. Please reconnect.",
            "休憩の状態を確認できませんでした。接続し直してください。"
        )
    }

    private func voiceTutorPauseText(_ korean: String, _ english: String, _ japanese: String) -> String {
        switch language {
        case .korean: return korean
        case .english: return english
        case .japanese: return japanese
        }
    }
}
#endif
