import SwiftUI

struct CommunityQuestionStatsMeta: View {
    var question: CommunityQuestion

    private static let relativeDateFormatter: RelativeDateTimeFormatter = {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return formatter
    }()

    var body: some View {
        HStack(spacing: 10) {
            HStack(spacing: 9) {
                metric(systemImage: question.isLikedByMe ? "heart.fill" : "heart", value: question.likeCount)
                    .foregroundStyle(question.isLikedByMe ? .red : .secondary)
                metric(systemImage: "bubble.right", value: question.commentCount)
                metric(systemImage: "eye", value: question.viewCount)
            }
            .fixedSize(horizontal: true, vertical: false)

            Spacer(minLength: 8)

            HStack(spacing: 6) {
                Text(question.topic.isEmpty ? "Swift" : question.topic)
                    .lineLimit(1)
                    .truncationMode(.tail)

                Text("Lv.\(question.difficultyLevel)")
                    .fixedSize(horizontal: true, vertical: false)

                Text(Self.relativeDateFormatter.localizedString(for: question.createdAt, relativeTo: Date()))
                    .fixedSize(horizontal: true, vertical: false)

                if let authorName = question.author?.displayName, !authorName.isEmpty {
                    Text(authorName)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
            }
            .multilineTextAlignment(.trailing)
            .frame(maxWidth: .infinity, alignment: .trailing)
            .layoutPriority(1)
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(.secondary)
        .lineLimit(1)
    }

    private func metric(systemImage: String, value: Int) -> some View {
        Label(Self.abbreviatedCount(value), systemImage: systemImage)
            .labelStyle(.titleAndIcon)
            .fixedSize(horizontal: true, vertical: false)
    }

    private static func abbreviatedCount(_ value: Int) -> String {
        let absoluteValue = abs(value)

        if absoluteValue >= 999_500 {
            return compact(value, divisor: 1_000_000, suffix: "M")
        }

        if absoluteValue >= 1_000 {
            return compact(value, divisor: 1_000, suffix: "k")
        }

        return "\(value)"
    }

    private static func compact(_ value: Int, divisor: Int, suffix: String) -> String {
        let scaled = Double(value) / Double(divisor)
        let rounded = (scaled * 10).rounded() / 10

        if rounded.rounded() == rounded {
            return "\(Int(rounded))\(suffix)"
        }

        return String(format: "%.1f%@", rounded, suffix)
    }
}
