import SwiftUI

struct CommunityQuestionStatsMeta: View {
    var question: CommunityQuestion

    var body: some View {
        HStack(spacing: 12) {
            Label("\(question.viewCount)", systemImage: "eye")
                .foregroundStyle(.secondary)
            Label("\(question.likeCount)", systemImage: question.isLikedByMe ? "heart.fill" : "heart")
                .foregroundStyle(question.isLikedByMe ? .red : .secondary)
            Label("\(question.commentCount)", systemImage: "bubble.right")
                .foregroundStyle(.secondary)
        }
        .font(.caption.weight(.semibold))
        .lineLimit(1)
    }
}
