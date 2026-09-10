#if os(iOS)
import Foundation

extension AppStrings {
    func voiceTutorOperationStatus(name: String, phase: VoiceTutorOperationEvent.Phase,
                                   elapsedMilliseconds: Int64) -> String {
        let status: String
        switch phase {
        case .started: status = voiceTutorPauseText("실행 중", "Running", "実行中")
        case .completed: status = voiceTutorPauseText("완료", "Completed", "完了")
        case .failed: status = voiceTutorPauseText("실패", "Failed", "失敗")
        }
        let seconds = max(0, elapsedMilliseconds) / 1_000
        let duration = seconds < 1 ? voiceTutorPauseText("1초 미만", "<1 sec", "1秒未満")
            : seconds < 60 ? voiceTutorPauseText("\(seconds)초", "\(seconds) sec", "\(seconds)秒")
            : voiceTutorPauseText("\(seconds / 60)분", "\(seconds / 60) min", "\(seconds / 60)分")
        return "\(name) · \(status) · \(duration)"
    }

    var voiceTutorProviderCallFailed: String {
        voiceTutorPauseText(
            "AI 응답 중단됨",
            "AI response stopped",
            "AIの応答が中断されました"
        )
    }

    var voiceTutorCallUnavailable: String {
        voiceTutorPauseText(
            "연결 실패",
            "Couldn't connect",
            "接続できません"
        )
    }

    var voiceTutorUpdateRequiredMessage: String {
        voiceTutorPauseText(
            "앱을 업데이트한 뒤 다시 대화해 주세요.",
            "Update the app before starting another conversation.",
            "アプリをアップデートしてから、もう一度対話してください。"
        )
    }

    var voiceTutorRequestRejected: String {
        voiceTutorPauseText(
            "대화 요청을 처리할 수 없습니다. 앱을 다시 실행해 주세요.",
            "The conversation request couldn't be processed. Reopen the app and try again.",
            "対話リクエストを処理できません。アプリを開き直してください。"
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
            "일시정지 중에도 대화 시간은 사용돼요.",
            "Conversation time continues while paused.",
            "一時停止中も対話時間を消費します。"
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
            "대화로 돌아가기",
            "Return to conversation",
            "対話に戻る"
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
            "아래로 쓸어 대화 화면으로 돌아갑니다.",
            "Swipe down to return to the conversation.",
            "下にスワイプすると対話画面に戻ります。"
        )
    }

    var voiceTutorCallThinking: String {
        voiceTutorPauseText("응답 준비 중", "Preparing a reply", "返答を準備中")
    }
    var voiceTutorQuestionLoading: String {
        voiceTutorPauseText("질문 불러오는 중", "Loading a question", "問題を読み込み中")
    }
    var voiceTutorQuestionGenerating: String {
        voiceTutorPauseText("질문 만드는 중", "Creating a question", "問題を作成中")
    }
    var voiceTutorQuestionReady: String {
        voiceTutorPauseText("질문 준비 완료", "Question ready", "問題の準備完了")
    }
    var voiceTutorQuestionReading: String {
        voiceTutorPauseText("질문 읽는 중", "Reading the question", "問題を読み上げ中")
    }
    var voiceTutorAnswerGrading: String {
        voiceTutorPauseText("채점 중", "Grading your answer", "採点中")
    }
    var voiceTutorAnswerGraded: String {
        voiceTutorPauseText("채점 완료", "Answer graded", "採点完了")
    }
    var voiceTutorQuestionFailed: String {
        voiceTutorPauseText("질문을 준비하지 못했어요", "Couldn't prepare the question", "問題を準備できませんでした")
    }
    var voiceTutorGradingFailed: String {
        voiceTutorPauseText("채점하지 못했어요", "Couldn't grade the answer", "採点できませんでした")
    }
    var voiceTutorCallSessionLabel: String {
        voiceTutorPauseText("음성 수업", "VOICE LESSON", "音声レッスン")
    }
    var voiceTutorCallConnectingHelp: String {
        voiceTutorPauseText(
            "마이크와 AI 선생님을 연결하고 있어요.",
            "Connecting your microphone and AI tutor.",
            "マイクとAI先生を接続しています。"
        )
    }
    var voiceTutorCallFirstReplyHelp: String {
        voiceTutorPauseText(
            "AI 선생님의 첫 응답을 기다리고 있어요.",
            "Waiting for your tutor’s first reply.",
            "AI先生の最初の返答を待っています。"
        )
    }
    var voiceTutorCallThinkingHelp: String {
        voiceTutorPauseText(
            "답변을 준비하고 있어요. 잠시만 기다려 주세요.",
            "Your tutor is preparing a reply. One moment.",
            "返答を準備しています。少々お待ちください。"
        )
    }
    var voiceTutorCallListeningHelp: String {
        voiceTutorPauseText(
            "편하게 말해 주세요. 이야기를 듣고 있어요.",
            "Take your time. Your tutor is listening.",
            "ゆっくりお話しください。先生が聞いています。"
        )
    }
    var voiceTutorCallSpeakingHelp: String {
        voiceTutorPauseText(
            "말이 끝나면 편하게 이어서 이야기해 주세요.",
            "When your tutor finishes, continue in your own words.",
            "先生が話し終えたら、続けてお話しください。"
        )
    }
    var voiceTutorCallPausedHelp: String {
        voiceTutorPauseText(
            "준비되면 계속하기를 눌러 주세요.",
            "Tap Continue whenever you’re ready.",
            "準備ができたら「続ける」をタップしてください。"
        )
    }
    var voiceTutorCallPausingHelp: String {
        voiceTutorPauseText(
            "AI 선생님의 말이 끝나면 잠시 쉬어갈게요.",
            "Your break will begin when your tutor finishes speaking.",
            "先生が話し終えたら休憩に入ります。"
        )
    }
    var voiceTutorCallResumingHelp: String {
        voiceTutorPauseText(
            "대화를 이어갈 준비를 하고 있어요.",
            "Getting ready to continue your conversation.",
            "会話を再開する準備をしています。"
        )
    }
    var voiceTutorCallInterruptedHelp: String {
        voiceTutorPauseText(
            "대화가 중단됐어요. 다시 연결해 주세요.",
            "The conversation was interrupted. Try reconnecting.",
            "会話が中断されました。接続し直してください。"
        )
    }
    var voiceTutorCallNoReplyHelp: String {
        voiceTutorPauseText(
            "AI의 응답을 받기 전에 대화가 중단됐어요. 다시 연결해 주세요.",
            "The conversation stopped before your tutor replied. Try reconnecting.",
            "AIの返答が届く前に対話が中断されました。接続し直してください。"
        )
    }
    var voiceTutorCallEndingHelp: String {
        voiceTutorPauseText("대화를 마무리하고 있어요.", "Finishing your lesson.", "レッスンを終了しています。")
    }
    var voiceTutorCallEndedHelp: String {
        voiceTutorPauseText(
            "나눈 이야기는 대화 내용에서 다시 볼 수 있어요.",
            "You can revisit what you discussed in the conversation.",
            "話した内容は会話画面で振り返れます。"
        )
    }
    var voiceTutorCallEndedWithoutCaptions: String {
        voiceTutorPauseText(
            "표시할 대화 내용이 없어요.",
            "There is no conversation to display.",
            "表示できる会話はありません。"
        )
    }
    var voiceTutorCallConversationAction: String {
        voiceTutorPauseText("대화", "Conversation", "会話")
    }
    var voiceTutorCallVoiceAction: String {
        voiceTutorPauseText("음성 화면", "Voice view", "音声画面")
    }
    var voiceTutorCallReconnect: String {
        voiceTutorPauseText("다시 연결", "Reconnect", "再接続")
    }
    var voiceTutorCallEmptyConversation: String {
        voiceTutorPauseText("대화가 여기에 표시돼요", "Your conversation appears here", "会話はここに表示されます")
    }

    var voiceTutorOrbEndConfirmation: String {
        voiceTutorPauseText("대화를 종료할까요?", "End this conversation?", "対話を終了しますか？")
    }
    var voiceTutorOrbKeepHoldingToEnd: String {
        voiceTutorPauseText("계속 누르면 종료", "Keep holding to end", "長押しを続けると終了")
    }
    var voiceTutorOrbReleaseCancels: String {
        voiceTutorPauseText("손을 떼면 취소돼요", "Release to cancel", "指を離すとキャンセル")
    }
    var voiceTutorOrbHoldToEndHint: String {
        voiceTutorPauseText(
            "길게 누르면 종료 안내가 나타나고, 계속 누르면 대화가 끝납니다. 손을 떼면 취소됩니다.",
            "Hold to show the end prompt, then keep holding to end. Release to cancel.",
            "長押しで終了の案内が表示され、そのまま押し続けると対話が終了します。指を離すとキャンセルします。"
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
