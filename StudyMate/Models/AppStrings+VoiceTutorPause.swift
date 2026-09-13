#if os(iOS)
import Foundation

extension AppStrings {
    var voiceTutorProviderQuotaUnavailableTitle: String {
        voiceTutorPauseText("AI 서비스 이용 불가", "AI service unavailable", "AIサービスを利用できません")
    }
    var voiceTutorProviderQuotaUnavailableMessage: String {
        voiceTutorPauseText(
            "AI 제공사의 사용 한도에 도달해 대화를 시작할 수 없어요. 서비스가 복구된 뒤 다시 시도해 주세요.",
            "The service's AI provider has reached its usage limit. Please try again after service is restored.",
            "AI提供元の利用上限に達したため、会話を開始できません。サービス復旧後にもう一度お試しください。"
        )
    }
    func voiceTutorFailureTitle(_ cause: VoiceTutorFailureCause) -> String {
        switch cause {
        case .offline: return voiceTutorPauseText("인터넷 연결 필요", "Internet connection needed", "インターネット接続が必要です")
        case .timeout: return voiceTutorPauseText("연결 시간 초과", "Connection timed out", "接続がタイムアウトしました")
        case .signInRequired: return voiceTutorPauseText("로그인 확인 필요", "Sign-in needed", "ログインが必要です")
        case .accountUnavailable: return voiceTutorPauseText("계정 상태 확인 필요", "Check your account", "アカウントの確認が必要です")
        case .termsRequired: return voiceTutorPauseText("약관 확인 필요", "Review the terms", "規約の確認が必要です")
        case .sessionConflict: return voiceTutorPauseText("기존 대화 확인 필요", "Check the previous conversation", "前の対話の確認が必要です")
        case .proRequired: return voiceTutorProRequired
        case .monthlyQuota: return voiceTutorCallQuotaEnded
        case .providerQuotaUnavailable: return voiceTutorProviderQuotaUnavailableTitle
        case .providerUnavailable: return voiceTutorPauseText("서비스 연결 지연", "Service temporarily unavailable", "サービスに接続できません")
        case .rateLimited: return voiceTutorPauseText("잠시 후 다시 시도", "Try again shortly", "しばらくしてから再試行")
        case .microphone: return voiceTutorPauseText("마이크 권한 필요", "Microphone access needed", "マイクの許可が必要です")
        case .inputPreparation: return voiceTutorPauseText("음성 준비 오류", "Audio setup unavailable", "音声を準備できません")
        case .audio: return voiceTutorPauseText("음성 사용 중단", "Audio interrupted", "音声が中断されました")
        case .localControl: return voiceTutorPauseText("대화 상태 확인 필요", "Conversation state unavailable", "対話状態を確認できません")
        case .invalidResponse: return voiceTutorPauseText("응답 확인 불가", "Response could not be verified", "応答を確認できません")
        case .finalization: return voiceTutorPauseText("대화 결과 확인 필요", "Conversation result pending", "対話結果の確認が必要です")
        case .provider: return voiceTutorProviderCallFailed
        case .updateRequired: return updateRequired
        case .requestRejected: return voiceTutorCallUnavailable
        case .connection: return voiceTutorCallFailed
        case .service, .unknown: return voiceTutorPauseText("대화를 계속할 수 없어요", "Conversation unavailable", "対話を続けられません")
        }
    }

    func voiceTutorFailureMessage(_ cause: VoiceTutorFailureCause) -> String {
        switch cause {
        case .offline:
            return voiceTutorPauseText("인터넷에 연결할 수 없어요. Wi-Fi나 셀룰러 데이터 연결을 확인한 뒤 다시 연결해 주세요.", "Internet access is unavailable. Check Wi-Fi or cellular data, then reconnect.", "インターネットに接続できません。Wi-Fiまたはモバイル通信を確認してから接続し直してください。")
        case .timeout:
            return voiceTutorPauseText("제한 시간 안에 응답을 받지 못했어요. 연결 상태를 확인하고 잠시 후 다시 연결해 주세요.", "No response arrived in time. Check your connection and reconnect shortly.", "時間内に応答が届きませんでした。通信状態を確認し、しばらくしてから接続し直してください。")
        case .signInRequired:
            return voiceTutorPauseText("로그인 상태를 확인할 수 없어요. 프로필에서 다시 로그인한 뒤 대화를 시작해 주세요.", "Your sign-in could not be verified. Sign in again from Profile, then start a conversation.", "ログイン状態を確認できません。プロフィールで再度ログインしてから対話を始めてください。")
        case .accountUnavailable:
            return voiceTutorPauseText("현재 계정으로 대화를 이용할 수 없어요. 프로필에서 계정과 이용 권한을 확인해 주세요.", "This account cannot use conversations right now. Check your account and access in Profile.", "現在のアカウントでは対話を利用できません。プロフィールでアカウントと利用権限を確認してください。")
        case .termsRequired:
            return voiceTutorPauseText("대화를 시작하려면 약관 확인이 필요해요. 프로필의 약관에서 확인한 뒤 다시 시작해 주세요.", "The terms need your review. Open Terms in Profile, then start again.", "対話を始めるには規約の確認が必要です。プロフィールの規約を確認してから再開してください。")
        case .sessionConflict:
            return voiceTutorPauseText("기존 대화가 진행 중이거나 연결 상태가 바뀌었어요. 진행 중인 대화를 마친 뒤 잠시 후 다시 시작해 주세요.", "A previous conversation is active or its connection state has changed. End any active conversation, then try again shortly.", "前の対話が進行中か、接続状態が変わりました。進行中の対話を終了し、しばらくしてから再開してください。")
        case .proRequired:
            return voiceTutorPauseText("음성 대화는 Pro에서 이용할 수 있어요. 프로필에서 멤버십 상태를 확인해 주세요.", "Voice conversations require Pro. Check your membership in Profile.", "音声対話にはProが必要です。プロフィールでメンバーシップを確認してください。")
        case .monthlyQuota:
            return voiceTutorPauseText("이번 달 대화 시간을 모두 사용했어요. 대화 화면의 월간 사용량에서 다음 갱신일을 확인해 주세요.", "You have used this month's conversation time. Check monthly usage on the conversation screen for the next reset.", "今月の対話時間を使い切りました。対話画面の月間使用量で次回更新日を確認してください。")
        case .providerQuotaUnavailable: return voiceTutorProviderQuotaUnavailableMessage
        case .providerUnavailable, .provider:
            return voiceTutorPauseText("서비스에서 응답을 받지 못했어요. 잠시 후 다시 연결해 주세요.", "The service could not respond. Please reconnect shortly.", "サービスから応答を受け取れませんでした。しばらくしてから接続し直してください。")
        case .rateLimited:
            return voiceTutorPauseText("요청이 일시적으로 몰려 있어요. 잠시 기다린 뒤 다시 연결해 주세요.", "Requests are temporarily limited. Wait a moment, then reconnect.", "リクエストが一時的に制限されています。少し待ってから接続し直してください。")
        case .microphone: return voiceTutorMicrophoneDenied
        case .inputPreparation:
            return voiceTutorPauseText("마이크와 음성 처리를 준비하지 못했어요. 다른 오디오 사용을 마치고 앱을 다시 열어 주세요.", "The microphone and audio processing could not be prepared. Finish other audio activity and reopen the app.", "マイクと音声処理を準備できませんでした。他の音声利用を終了し、アプリを開き直してください。")
        case .audio:
            return voiceTutorPauseText("음성 재생이나 마이크 사용이 중단됐어요. 다른 오디오 사용을 마친 뒤 다시 연결해 주세요.", "Audio playback or microphone use was interrupted. Finish other audio activity, then reconnect.", "音声再生またはマイクの使用が中断されました。他の音声利用を終了してから接続し直してください。")
        case .localControl: return voiceTutorPauseFailed
        case .invalidResponse:
            return voiceTutorPauseText("서버 응답을 확인하지 못했어요. 잠시 후 다시 연결하고, 계속되면 앱을 업데이트해 주세요.", "The server response could not be verified. Reconnect shortly; if it continues, update the app.", "サーバー応答を確認できませんでした。しばらくしてから再接続し、続く場合はアプリを更新してください。")
        case .finalization:
            return voiceTutorPauseText("대화 결과 처리가 완료됐는지 확인하지 못했어요. 대화 기록에서 결과를 다시 확인해 주세요.", "We could not confirm that the conversation result finished processing. Check the result in conversation history.", "対話結果の処理完了を確認できませんでした。対話履歴で結果を確認してください。")
        case .updateRequired: return voiceTutorUpdateRequiredMessage
        case .requestRejected: return voiceTutorRequestRejected
        case .connection: return voiceTutorConnectionFailed
        case .unknown:
            return voiceTutorPauseText("대화를 계속하지 못했어요. 잠시 후 다시 연결해 주세요.", "The conversation could not continue. Please reconnect shortly.", "対話を続けられませんでした。しばらくしてから接続し直してください。")
        case .service:
            return voiceTutorPauseText("대화를 계속하지 못했어요. 이 화면을 닫고 잠시 후 다시 시작해 주세요.", "The conversation could not continue. Close this screen and try again shortly.", "対話を続けられませんでした。この画面を閉じ、しばらくしてから再開してください。")
        }
    }

    var voiceTutorOpenMicrophoneSettings: String {
        voiceTutorPauseText("마이크 설정", "Microphone settings", "マイク設定")
    }

    var voiceTutorUnsubmittedAnswer: String {
        voiceTutorPauseText("미제출 답변", "Unsubmitted answer", "未送信の回答")
    }

    var voiceTutorInterruptedResponse: String {
        voiceTutorPauseText("중단된 응답", "Interrupted response", "中断された応答")
    }

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
            "버디 응답 중단됨",
            "Buddy’s response stopped",
            "バディの応答が中断されました"
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

    var voiceTutorTakeBreak: String { voiceTutorPauseText("대화 음소거", "Mute conversation", "対話をミュート") }
    var voiceTutorResumeLesson: String { voiceTutorPauseText("음소거 해제", "Unmute", "ミュート解除") }
    var voiceTutorPausing: String {
        voiceTutorPauseText("음소거 적용 중", "Muting", "ミュート中")
    }
    var voiceTutorPaused: String { voiceTutorPauseText("대화 음소거됨", "Conversation muted", "対話ミュート中") }
    var voiceTutorResuming: String { voiceTutorPauseText("음소거 해제 중", "Unmuting", "ミュート解除中") }
    var voiceTutorPauseUsesTime: String {
        voiceTutorPauseText(
            "음소거 중에도 연결과 이용 시간 차감은 유지돼요.",
            "The connection and time allowance continue while muted.",
            "ミュート中も接続と利用時間の消費は続きます。"
        )
    }
    var voiceTutorPauseFailed: String {
        voiceTutorPauseText(
            "음소거 상태를 확인하지 못했어요. 다시 연결해 주세요.",
            "We couldn't confirm mute status. Please reconnect.",
            "ミュート状態を確認できませんでした。接続し直してください。"
        )
    }
    var voiceTutorOrbPauseHint: String {
        voiceTutorPauseText(
            "탭하면 대화를 음소거해요.",
            "Tap to mute the conversation.",
            "タップすると対話をミュートします。"
        )
    }
    var voiceTutorAnswerResume: String {
        voiceTutorPauseText("음소거 해제", "Unmute", "ミュート解除")
    }
    var voiceTutorAnswerPauseHelp: String {
        voiceTutorPauseText(
            "천천히 생각하세요. 지금까지의 답변은 그대로 남아 있어요.",
            "Take your time. Your answer so far is kept.",
            "ゆっくり考えてください。これまでの回答はそのまま残っています。"
        )
    }
    var voiceTutorAnswerPauseHint: String {
        voiceTutorPauseText(
            "답변을 끝내지 않고 마이크를 잠시 멈춰요. 음소거를 해제하면 이어서 답변할 수 있어요.",
            "Mute the microphone without finishing your answer. Unmute when ready.",
            "回答を終了せずマイクをミュートします。準備ができたら解除してください。"
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
    var voiceTutorAnswerWaiting: String {
        voiceTutorPauseText("답변 대기", "Awaiting your answer", "回答待ち")
    }
    var voiceTutorQuestionHeading: String {
        voiceTutorPauseText("질문", "Question", "問題")
    }
    var voiceTutorAnswerEnter: String {
        voiceTutorPauseText("직접 입력", "Type answer", "直接入力")
    }
    var voiceTutorGradingDetails: String {
        voiceTutorPauseText("점수와 해설 보기", "View score and explanation", "点数と解説を見る")
    }
    var voiceTutorGradingFeedbackTitle: String {
        voiceTutorPauseText("채점 이유", "Why this score", "採点の理由")
    }
    func voiceTutorLessonScore(_ score: Int) -> String {
        voiceTutorPauseText("\(score)점", "\(score) / 100", "\(score)点")
    }
    var voiceTutorGradingResultLoading: String {
        voiceTutorPauseText("채점 결과를 불러오는 중", "Loading your result", "採点結果を読み込み中")
    }
    var voiceTutorGradingResultFailed: String {
        voiceTutorPauseText("채점 결과를 불러오지 못했어요.", "Couldn't load your result.", "採点結果を読み込めませんでした。")
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
            "마이크와 버디를 연결하고 있어요.",
            "Connecting your microphone and Buddy.",
            "マイクとバディを接続しています。"
        )
    }
    var voiceTutorCallFirstReplyHelp: String {
        voiceTutorPauseText(
            "버디의 첫 응답을 기다리고 있어요.",
            "Waiting for Buddy’s first reply.",
            "バディの最初の返答を待っています。"
        )
    }
    var voiceTutorCallThinkingHelp: String {
        voiceTutorPauseText(
            "답변을 준비하고 있어요. 잠시만 기다려 주세요.",
            "Buddy is preparing a reply. One moment.",
            "返答を準備しています。少々お待ちください。"
        )
    }
    var voiceTutorCallListeningHelp: String {
        voiceTutorPauseText(
            "편하게 말해 주세요. 이야기를 듣고 있어요.",
            "Take your time. Buddy is listening.",
            "ゆっくりお話しください。バディが聞いています。"
        )
    }
    var voiceTutorCallSpeakingHelp: String {
        voiceTutorPauseText(
            "말이 끝나면 편하게 이어서 이야기해 주세요.",
            "When Buddy finishes, continue in your own words.",
            "バディが話し終えたら、続けてお話しください。"
        )
    }
    var voiceTutorCallPausedHelp: String {
        voiceTutorPauseText(
            "준비되면 음소거를 해제해 주세요.",
            "Unmute whenever you’re ready.",
            "準備ができたらミュートを解除してください。"
        )
    }
    var voiceTutorCallPausingHelp: String {
        voiceTutorPauseText(
            "대화를 음소거하고 있어요.",
            "Muting the conversation.",
            "対話をミュートしています。"
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
            "버디의 응답을 받기 전에 대화가 중단됐어요. 다시 연결해 주세요.",
            "The conversation stopped before Buddy replied. Try reconnecting.",
            "バディの返答が届く前に対話が中断されました。接続し直してください。"
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
