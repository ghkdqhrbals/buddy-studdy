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
        voiceTutorExplorationText("선생님 질문", "Tutor question", "先生の質問")
    }

    var voiceTutorExplorationLearnerQuestion: String {
        voiceTutorExplorationText("내 질문", "My question", "自分の質問")
    }

    var voiceTutorExplorationLearnerAnswer: String {
        voiceTutorExplorationText("내 답변", "My answer", "自分の回答")
    }

    var voiceTutorExplorationTutorAnswer: String {
        voiceTutorExplorationText("선생님 설명", "Tutor explanation", "先生の説明")
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
        voiceTutorExplorationText("통화 평가 \(score)점", "Call assessment: \(score)", "通話中の評価：\(score)点")
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
