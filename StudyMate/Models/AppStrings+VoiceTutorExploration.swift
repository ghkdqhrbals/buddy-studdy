#if os(iOS)
import Foundation

extension AppStrings {
    var voiceTutorExplorationsTitle: String {
        voiceTutorExplorationText("하위 주제별 학습", "Topic deep dives", "テーマ別の学習")
    }

    var voiceTutorExplorationDepth: String {
        voiceTutorExplorationText("깊게 다룬 내용", "What we explored", "詳しく学んだ内容")
    }

    var voiceTutorExplorationTutorQuestion: String {
        voiceTutorExplorationText("버디 질문", "Buddy’s question", "バディの質問")
    }

    var voiceTutorExplorationLearnerQuestion: String {
        voiceTutorExplorationText("내 질문", "My question", "自分の質問")
    }

    var voiceTutorExplorationLearnerAnswer: String {
        voiceTutorExplorationText("내 답변", "My answer", "自分の回答")
    }

    var voiceTutorExplorationTutorAnswer: String {
        voiceTutorExplorationText("버디 설명", "Buddy’s explanation", "バディの説明")
    }

    var voiceTutorExplorationNoAnswer: String {
        voiceTutorExplorationText("기록된 답변이 없어요.", "No answer was recorded.", "回答は記録されていません。")
    }

    var voiceTutorExplorationSource: String {
        voiceTutorExplorationText("원문 대화", "Original conversation", "元の会話")
    }

    var voiceTutorExplorationMoreTopics: String {
        voiceTutorExplorationText("주제 더 보기", "More topics", "テーマをもっと見る")
    }

    var voiceTutorExplorationMoreExchanges: String {
        voiceTutorExplorationText("대화 더 보기", "More exchanges", "会話をもっと見る")
    }

    func voiceTutorExplorationCount(_ count: Int) -> String {
        voiceTutorExplorationText("\(count)개 주제", "\(count) topics", "\(count)テーマ")
    }

    func voiceTutorExplorationScore(_ score: Int) -> String {
        voiceTutorExplorationText("대화 평가 \(score)점", "Conversation assessment: \(score)", "対話中の評価：\(score)点")
    }

    /// Explain only the authenticated answer receipt. Partial transcription is
    /// a review warning, not a submission failure, and never changes the draft.
    func voiceTutorAnswerFailureHelp(phase: VoiceTutorAnswerDraftState.Phase, code: String?) -> String? {
        guard phase == .review || phase == .failed else { return nil }
        if code == "ANSWER_TRANSCRIPT_INCOMPLETE" {
            return voiceTutorExplorationText(
                "말한 내용 일부를 받아쓰지 못했어요. 답변을 확인하고 빠진 내용을 입력한 뒤 제출해 주세요.",
                "Some of your speech could not be transcribed. Review your answer, add anything missing, then submit.",
                "話した内容の一部を書き起こせませんでした。回答を確認し、足りない内容を入力してから送信してください。"
            )
        }
        guard phase == .failed else { return nil }
        return code == "ANSWER_TOO_LONG" ? voiceTutorAnswerTooLong : voiceTutorAnswerFailedHelp
    }

    /// Lesson phases do not include a cause code. Do not invent a network,
    /// provider-credit, or authentication diagnosis from a generic failure.
    func voiceTutorLessonFailureHelp(_ phase: VoiceTutorSessionStateEvent.Phase?) -> String? {
        switch phase {
        case .questionFailed:
            return voiceTutorExplorationText(
                "질문을 준비하지 못했어요. 질문을 다시 요청하거나 다른 주제로 이어가 주세요.",
                "The question could not be prepared. Ask for the question again or continue with another topic.",
                "問題を準備できませんでした。もう一度問題をリクエストするか、別のテーマに進んでください。"
            )
        case .gradingFailed:
            return voiceTutorExplorationText(
                "채점을 완료하지 못했어요. 학습 기록에서 제출한 답변과 처리 상태를 확인해 주세요.",
                "Grading could not be completed. Check your submitted answer and its status in Records.",
                "採点を完了できませんでした。学習記録で送信した回答と処理状況を確認してください。"
            )
        default: return nil
        }
    }

    func voiceTutorGradingResultFailureHelp(_ failure: VoiceTutorGradingResultState.Failure?) -> String {
        switch failure {
        case .timedOut:
            return voiceTutorExplorationText(
                "채점 결과를 불러오는 데 시간이 오래 걸리고 있어요. 결과를 다시 불러와 주세요.",
                "Loading your grading result is taking too long. Try loading the result again.",
                "採点結果の読み込みに時間がかかっています。結果をもう一度読み込んでください。"
            )
        case .incompleteResult:
            return voiceTutorExplorationText(
                "채점 결과가 아직 완전히 도착하지 않았어요. 잠시 후 결과를 다시 불러와 주세요.",
                "The full grading result has not arrived yet. Wait a moment, then load the result again.",
                "採点結果の一部がまだ届いていません。少し待ってから結果をもう一度読み込んでください。"
            )
        case .mismatchedRecord:
            return voiceTutorExplorationText(
                "현재 질문의 채점 결과를 확인하지 못했어요. 결과를 다시 불러와 주세요.",
                "The grading result could not be matched to this question. Load the result again.",
                "現在の問題に対応する採点結果を確認できませんでした。結果をもう一度読み込んでください。"
            )
        case .unavailable, nil:
            return voiceTutorExplorationText(
                "채점 결과를 불러오지 못했어요. 연결 상태를 확인한 뒤 결과를 다시 불러와 주세요.",
                "The grading result could not be loaded. Check your connection, then load the result again.",
                "採点結果を読み込めませんでした。接続を確認してから結果をもう一度読み込んでください。"
            )
        }
    }

    var voiceTutorGradingUnavailable: String {
        voiceTutorExplorationText("채점 확인 필요", "Check grading status", "採点状況の確認が必要です")
    }

    var voiceTutorGradingUnavailableHelp: String {
        voiceTutorExplorationText("답안은 제출됐어요. 결과를 다시 확인해 주세요.",
            "Your answer was submitted. Check the result again.",
            "回答は送信済みです。結果をもう一度確認してください。")
    }

    var voiceTutorGradingResultReloadTitle: String {
        voiceTutorExplorationText("결과 다시 불러오기", "Reload result", "結果を再読み込み")
    }

    private func voiceTutorExplorationText(_ korean: String, _ english: String, _ japanese: String) -> String {
        switch language {
        case .korean: korean
        case .english: english
        case .japanese: japanese
        }
    }
}
#endif
