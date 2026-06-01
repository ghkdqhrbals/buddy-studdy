import Foundation

@MainActor
enum OpenAIClient {
    nonisolated static func questionPrompt(settings: StudySettings, recentQuestions: [QuestionItem]) -> String {
        let language = questionLanguage(for: settings)
        let languageInstruction = questionLanguageInstruction(for: settings)
        let recentQuestionText = recentQuestions
            .suffix(80)
            .enumerated()
            .map { index, item in "\(index + 1). \(item.question)" }
            .joined(separator: "\n")

        return """
        Create one study question.

        Topic: \(settings.topic)
        Difficulty: \(settings.difficulty.promptLabel)
        Language: \(language.promptLabel)
        Teacher instruction: \(settings.customPrompt)
        Question language instruction: \(languageInstruction)
        Previous questions to avoid:
        \(recentQuestionText.isEmpty ? "None" : recentQuestionText)

        Requirements:
        - Return JSON only.
        - \(languageInstruction)
        - Write the question and expectedAnswerHint in \(language.promptLabel).
        - If Teacher instruction conflicts with Language, Language wins.
        - The question should be concise and practical.
        - Do not repeat or closely paraphrase any previous question.
        - Vary the concept, angle, example, or required reasoning from previous questions.
        - If the topic is broad, rotate through different subtopics.
        """
    }

    nonisolated private static func questionLanguage(for settings: StudySettings) -> StudyLanguage {
        settings.appLanguage.studyLanguage
    }

    nonisolated private static func questionLanguageInstruction(for settings: StudySettings) -> String {
        switch settings.appLanguage {
        case .korean:
            return "한국어로 질문해."
        case .english:
            return "Ask the question in English."
        }
    }

    nonisolated static func normalizedGradingResult(_ result: GradingResult) -> GradingResult {
        let score = min(max(result.score, 0), 100)
        return GradingResult(
            score: score,
            isCorrect: score >= 70,
            feedback: result.feedback,
            explanation: result.explanation
        )
    }

    nonisolated static func extractOutputText(from data: Data) -> String? {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }

        if let outputText = object["output_text"] as? String {
            return outputText
        }

        guard let output = object["output"] as? [[String: Any]] else {
            return nil
        }

        for item in output {
            guard let content = item["content"] as? [[String: Any]] else {
                continue
            }

            for contentItem in content {
                if let text = contentItem["text"] as? String {
                    return text
                }
            }
        }

        return nil
    }

    nonisolated static func extractResponseID(from data: Data) -> String? {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }

        return object["id"] as? String
    }

    nonisolated static func extractUsage(from data: Data) -> OpenAIUsage? {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let usage = object["usage"] as? [String: Any] else {
            return nil
        }

        let inputTokens = intValue(usage["input_tokens"])
        let outputTokens = intValue(usage["output_tokens"])
        let totalTokens = intValue(usage["total_tokens"], fallback: inputTokens + outputTokens)
        let inputDetails = usage["input_tokens_details"] as? [String: Any]
        let cachedInputTokens = intValue(inputDetails?["cached_tokens"])

        return OpenAIUsage(
            inputTokens: inputTokens,
            cachedInputTokens: cachedInputTokens,
            outputTokens: outputTokens,
            totalTokens: totalTokens
        )
    }

    nonisolated private static func intValue(_ value: Any?, fallback: Int = 0) -> Int {
        if let intValue = value as? Int {
            return intValue
        }

        if let doubleValue = value as? Double {
            return Int(doubleValue)
        }

        if let numberValue = value as? NSNumber {
            return numberValue.intValue
        }

        return fallback
    }

    nonisolated static func structuredRequestBody(
        model: String,
        instructions: String,
        input: String,
        previousResponseID: String?,
        schemaName: String,
        schema: [String: Any]
    ) -> [String: Any] {
        let trimmedModel = model.trimmingCharacters(in: .whitespacesAndNewlines)
        let modelID = trimmedModel.isEmpty ? StudySettings.defaultOpenAIModel : trimmedModel

        var text: [String: Any] = [
            "format": [
                "type": "json_schema",
                "name": schemaName,
                "schema": schema,
                "strict": true
            ]
        ]

        if OpenAIModelOption.supportsTextVerbosity(modelID: modelID) {
            text["verbosity"] = "low"
        }

        var body: [String: Any] = [
            "model": modelID,
            "instructions": instructions,
            "input": input,
            "text": text
        ]

        if let previousResponseID,
           !previousResponseID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            body["previous_response_id"] = previousResponseID
        }

        return body
    }

}
