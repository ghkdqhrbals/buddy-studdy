#if os(iOS)
import Foundation

extension AppStrings {
    var studyLearningRecordsTitle: String { learningText("학습 기록", "Learning records", "学習記録") }
    var studyLearningVoiceLabel: String { learningText("음성 문답", "Voice conversation", "音声のやり取り") }
    var studyLearningQuestionLabel: String { learningText("질문·답변", "Question & answer", "質問・回答") }
    var studyLearningNodeScope: String { learningText("이 주제", "This topic", "このトピック") }
    var studyLearningSubtreeScope: String { learningText("하위 주제 포함", "Include subtopics", "子トピックを含む") }
    var studyLearningEmpty: String { learningText("아직 학습 기록이 없습니다.", "No learning records yet.", "学習記録はまだありません。") }
    var studyLearningLoadFailed: String { learningText("기록을 불러오지 못했습니다.", "Couldn't load records.", "記録を読み込めませんでした。") }
    var studyLearningSignIn: String { learningText("로그인하면 학습 기록을 볼 수 있습니다.", "Sign in to view learning records.", "ログインすると学習記録を確認できます。") }
    var studyLearningReopen: String { learningText("목록에서 기록을 다시 열어 주세요.", "Reopen this record from the list.", "一覧から記録を開き直してください。") }
    var studyLearningPrevious: String { learningText("이전", "Previous", "前へ") }
    var studyLearningNext: String { learningText("다음", "Next", "次へ") }
    var studyLearningTranslationPending: String { learningText("번역 중", "Translating", "翻訳中") }
    var studyLearningOriginal: String { learningText("원문", "Original", "原文") }
    var studyLearningLocalized: String { learningText("번역", "Translated", "翻訳") }
    var studyLearningTeacherQuestion: String { learningText("선생님 질문", "Tutor's question", "先生の質問") }
    var studyLearningLearnerQuestion: String { learningText("내 질문", "My question", "自分の質問") }
    var studyLearningTutorAnswer: String { learningText("선생님 답변", "Tutor's answer", "先生の回答") }
    var studyLearningLearnerAnswer: String { learningText("내 답변", "My answer", "自分の回答") }
    var studyLearningFeedback: String { learningText("피드백", "Feedback", "フィードバック") }
    var studyLearningDepth: String { learningText("더 알아본 내용", "What we explored", "掘り下げた内容") }
    var studyLearningStrengths: String { learningText("잘한 점", "Strengths", "よかった点") }
    var studyLearningImprovements: String { learningText("보완할 점", "To improve", "改善点") }
    var studyLearningNoAnswer: String { learningText("저장된 답변이 없습니다.", "No saved answer.", "保存された回答はありません。") }

    func studyLearningPageNumber(_ page: Int) -> String {
        learningText("\(page)페이지", "Page \(page)", "\(page)ページ")
    }

    private func learningText(_ korean: String, _ english: String, _ japanese: String) -> String {
        switch language {
        case .korean: korean
        case .english: english
        case .japanese: japanese
        }
    }
}
#endif
