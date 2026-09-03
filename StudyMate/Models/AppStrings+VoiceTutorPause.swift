#if os(iOS)
import Foundation

extension AppStrings {
    var voiceTutorProviderCallFailed: String {
        voiceTutorPauseText(
            "AI 응답 중단됨",
            "AI response stopped",
            "AIの応答が中断されました"
        )
    }

    var voiceTutorTakeBreak: String { voiceTutorPauseText("일시정지", "Pause", "一時停止") }
    var voiceTutorResumeLesson: String { voiceTutorPauseText("계속하기", "Continue", "続ける") }
    var voiceTutorPausing: String {
        voiceTutorPauseText("문장을 마친 뒤 일시정지", "Pausing after this sentence", "この文の後に一時停止")
    }
    var voiceTutorPaused: String { voiceTutorPauseText("일시정지됨", "Paused", "一時停止中") }
    var voiceTutorResuming: String { voiceTutorPauseText("계속할 준비 중", "Getting ready to continue", "再開の準備中") }
    var voiceTutorPauseUsesTime: String {
        voiceTutorPauseText(
            "일시정지 중에도 통화 시간은 사용돼요.",
            "Call time continues while paused.",
            "一時停止中も通話時間を消費します。"
        )
    }
    var voiceTutorPauseFailed: String {
        voiceTutorPauseText(
            "쉬는 상태를 확인하지 못했어요. 다시 연결해 주세요.",
            "We couldn't confirm the break. Please reconnect.",
            "休憩の状態を確認できませんでした。接続し直してください。"
        )
    }
    var voiceTutorOrbPauseHint: String {
        voiceTutorPauseText(
            "탭하면 선생님이 현재 문장을 마친 뒤 쉬어요.",
            "Tap to pause after the tutor finishes the current sentence.",
            "タップすると、チューターが今の文を話し終えてから休憩します。"
        )
    }
    var voiceTutorOrbResumeHint: String {
        voiceTutorPauseText(
            "탭하면 대화를 계속해요.",
            "Tap to continue the conversation.",
            "タップすると会話を再開します。"
        )
    }
    var voiceTutorCallRevealConversation: String {
        voiceTutorPauseText(
            "전체 대화 보기",
            "View full conversation",
            "会話を全画面で見る"
        )
    }
    var voiceTutorCallCollapseConversation: String {
        voiceTutorPauseText(
            "통화로 돌아가기",
            "Return to call",
            "通話に戻る"
        )
    }
    var voiceTutorCallLatestConversation: String {
        voiceTutorPauseText(
            "최근 대화",
            "Latest conversation",
            "最近の会話"
        )
    }
    var voiceTutorOrbRevealConversationHint: String {
        voiceTutorPauseText(
            "위로 쓸어 전체 대화를 봅니다.",
            "Swipe up to view the full conversation.",
            "上にスワイプすると会話全体を表示します。"
        )
    }
    var voiceTutorOrbHideConversationHint: String {
        voiceTutorPauseText(
            "아래로 쓸어 통화 화면으로 돌아갑니다.",
            "Swipe down to return to the call.",
            "下にスワイプすると通話画面に戻ります。"
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
