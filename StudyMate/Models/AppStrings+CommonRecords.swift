import Foundation

extension AppStrings {
    func recordTypeLabel(_ type: StudyRecordType) -> String {
        switch type {
        case .question: commonRecordText("질문·답변", "Question & answer", "質問・回答")
        case .voiceTutor: commonRecordText("음성 문답", "Voice conversation", "音声のやり取り")
        }
    }

    var commonRecordTitle: String { commonRecordText("학습 기록", "Learning record", "学習記録") }
    var voiceRecordFeedback: String { commonRecordText("피드백", "Feedback", "フィードバック") }
    var voiceRecordStrengths: String { commonRecordText("잘한 점", "Strengths", "よかった点") }
    var voiceRecordImprovements: String { commonRecordText("보완할 점", "To improve", "改善点") }
    var voiceRecordDepth: String { commonRecordText("더 알아본 내용", "What we explored", "掘り下げた内容") }
    var voiceRecordTutorQuestion: String { commonRecordText("선생님 질문", "Tutor's question", "先生の質問") }
    var voiceRecordLearnerQuestion: String { commonRecordText("학습자 질문", "Learner's question", "学習者の質問") }
    var voiceRecordTutorAnswer: String { commonRecordText("선생님 답변", "Tutor's answer", "先生の回答") }
    var voiceRecordLearnerAnswer: String { commonRecordText("학습자 답변", "Learner's answer", "学習者の回答") }

    private func commonRecordText(_ korean: String, _ english: String, _ japanese: String) -> String {
        switch language {
        case .korean: korean
        case .english: english
        case .japanese: japanese
        }
    }
}
