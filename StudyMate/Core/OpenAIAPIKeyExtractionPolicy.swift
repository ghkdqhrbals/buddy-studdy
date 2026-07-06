import Foundation

enum OpenAIAPIKeyExtractionPolicy {
    static func extract(from text: String) -> String? {
        let normalized = text
            .replacingOccurrences(of: "`", with: " ")
            .replacingOccurrences(of: "\"", with: " ")
            .replacingOccurrences(of: "'", with: " ")
            .replacingOccurrences(of: "“", with: " ")
            .replacingOccurrences(of: "”", with: " ")
            .replacingOccurrences(of: "<", with: " ")
            .replacingOccurrences(of: ">", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\u{200b}", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)

        guard !normalized.isEmpty else {
            return nil
        }

        let candidateSeparators = CharacterSet(charactersIn: " \t\n\r.,:;()[]{}<>/\\\"'`~!@#$%^&*+=|?:;<>[]{}")
        let tokenCandidates = normalized
            .components(separatedBy: candidateSeparators)
            .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }

        for token in tokenCandidates {
            if (token.hasPrefix("sk-proj-") || token.hasPrefix("sk-")) && token.count >= 20 {
                return token
            }
        }

        let patterns = [
            "sk-(?:proj-)?[A-Za-z0-9_-]{20,}",
            "sk-proj-[A-Za-z0-9_-]{20,}",
            "sk-[A-Za-z0-9_-]{20,}"
        ]

        for pattern in patterns {
            guard let regex = try? NSRegularExpression(pattern: pattern, options: []),
                  let match = regex.firstMatch(
                      in: normalized,
                      options: [],
                      range: NSRange(location: 0, length: normalized.utf16.count)
                  ) else {
                continue
            }

            let start = String.Index(utf16Offset: match.range.location, in: normalized)
            let end = String.Index(utf16Offset: match.range.location + match.range.length, in: normalized)
            let extracted = String(normalized[start..<end]).trimmingCharacters(in: .whitespacesAndNewlines)

            if !extracted.isEmpty {
                return extracted
            }
        }

        if let tokenRegex = try? NSRegularExpression(pattern: "[A-Za-z0-9_-]+", options: []) {
            let tokenRange = NSRange(location: 0, length: normalized.utf16.count)
            let tokenMatches = tokenRegex.matches(in: normalized, options: [], range: tokenRange)
            for token in tokenMatches {
                let start = String.Index(utf16Offset: token.range.location, in: normalized)
                let end = String.Index(utf16Offset: token.range.location + token.range.length, in: normalized)
                let tokenText = String(normalized[start..<end])

                if (tokenText.hasPrefix("sk-proj-") || tokenText.hasPrefix("sk-")) && tokenText.count >= 20 {
                    return tokenText
                }
            }
        }

        let trimmed = normalized.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.hasPrefix("sk-") && trimmed.count >= 20 ? trimmed : nil
    }
}
