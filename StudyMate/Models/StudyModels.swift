import Foundation

enum Difficulty: Int, CaseIterable, Codable, Identifiable {
    case level1 = 1
    case level2 = 2
    case level3 = 3
    case level4 = 4
    case level5 = 5
    case level6 = 6
    case level7 = 7
    case level8 = 8
    case level9 = 9
    case level10 = 10

    var id: Int { rawValue }
    var level: Int { rawValue }

    init(level: Int) {
        self = Difficulty(rawValue: min(max(level, 1), 10)) ?? .level5
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()

        if let level = try? container.decode(Int.self) {
            self.init(level: level)
            return
        }

        let rawValue = try container.decode(String.self)
        if let level = Int(rawValue) {
            self.init(level: level)
            return
        }

        if let legacyDifficulty = Self.legacyMap[rawValue] {
            self = legacyDifficulty
            return
        }

        if rawValue.hasPrefix("level"),
           let level = Int(rawValue.dropFirst("level".count)) {
            self.init(level: level)
            return
        }

        self = .level5
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(rawValue)
    }

    static var novice: Difficulty { .level1 }
    static var beginner: Difficulty { .level2 }
    static var elementary: Difficulty { .level4 }
    static var intermediate: Difficulty { .level5 }
    static var upperIntermediate: Difficulty { .level7 }
    static var advanced: Difficulty { .level9 }
    static var expert: Difficulty { .level10 }

    private static let legacyMap: [String: Difficulty] = [
        "novice": .novice,
        "beginner": .beginner,
        "elementary": .elementary,
        "intermediate": .intermediate,
        "upperIntermediate": .upperIntermediate,
        "upper-intermediate": .upperIntermediate,
        "advanced": .advanced,
        "expert": .expert
    ]

    var displayName: String {
        "레벨 \(level)/10"
    }

    var promptLabel: String {
        let descriptor: String
        switch level {
        case 1:
            descriptor = "absolute beginner"
        case 2:
            descriptor = "introductory"
        case 3:
            descriptor = "basic"
        case 4:
            descriptor = "elementary"
        case 5:
            descriptor = "intermediate"
        case 6:
            descriptor = "solid intermediate"
        case 7:
            descriptor = "upper-intermediate"
        case 8:
            descriptor = "advanced"
        case 9:
            descriptor = "very advanced"
        default:
            descriptor = "expert"
        }

        return "level \(level) out of 10 (\(descriptor))"
    }
}

enum StudyLanguage: String, CaseIterable, Codable, Identifiable {
    case korean
    case english

    var id: String { rawValue }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let rawValue = try container.decode(String.self)
        self = StudyLanguage(rawValue: rawValue) ?? .korean
    }

    var displayName: String {
        switch self {
        case .korean:
            "한국어"
        case .english:
            "English"
        }
    }

    var promptLabel: String {
        switch self {
        case .korean:
            "Korean"
        case .english:
            "English"
        }
    }
}

enum AppLanguage: String, CaseIterable, Codable, Identifiable {
    case korean
    case english

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .korean:
            "한국어"
        case .english:
            "English"
        }
    }
}

enum NotificationSoundOption: String, CaseIterable, Codable, Identifiable {
    case defaultSound
    case softPing
    case chime
    case pop
    case bell
    case tap
    case none

    var id: String { rawValue }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let rawValue = try container.decode(String.self)
        self = NotificationSoundOption(rawValue: rawValue) ?? .defaultSound
    }

    var bundledFileName: String? {
        switch self {
        case .defaultSound, .none:
            nil
        case .softPing:
            "study_ping.wav"
        case .chime:
            "study_chime.wav"
        case .pop:
            "study_pop.wav"
        case .bell:
            "study_bell.wav"
        case .tap:
            "study_tap.wav"
        }
    }

    func displayName(language: AppLanguage) -> String {
        switch (self, language) {
        case (.defaultSound, .korean):
            "기본음"
        case (.defaultSound, .english):
            "Default"
        case (.softPing, .korean):
            "부드러운 핑"
        case (.softPing, .english):
            "Soft Ping"
        case (.chime, .korean):
            "차임"
        case (.chime, .english):
            "Chime"
        case (.pop, .korean):
            "팝"
        case (.pop, .english):
            "Pop"
        case (.bell, .korean):
            "벨"
        case (.bell, .english):
            "Bell"
        case (.tap, .korean):
            "탭"
        case (.tap, .english):
            "Tap"
        case (.none, .korean):
            "없음"
        case (.none, .english):
            "None"
        }
    }
}

enum AppTab: Int, Hashable {
    case home
    case study
    case settings
    case records
    case statistics
}

struct HomeStudyRoute: Identifiable, Hashable {
    let id = UUID()
    var categoryID: String?
}

struct FocusedRecordRequest: Equatable {
    var token = UUID()
    var recordID: String
}

struct StudyCategory: Codable, Equatable, Identifiable {
    var id: String
    var title: String
    var difficulty: Difficulty
    var customPrompt: String
    var openAIModel: String
    var createdAt: Date

    init(
        id: String = UUID().uuidString,
        title: String,
        difficulty: Difficulty = .beginner,
        customPrompt: String = StudySettings.defaultCustomPrompt,
        openAIModel: String = StudySettings.defaultOpenAIModel,
        createdAt: Date = Date()
    ) {
        self.id = id
        self.title = title
        self.difficulty = difficulty
        self.customPrompt = customPrompt
        self.openAIModel = openAIModel
        self.createdAt = createdAt
    }

    private enum CodingKeys: String, CodingKey {
        case id
        case title
        case difficulty
        case customPrompt
        case openAIModel
        case createdAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decodeIfPresent(String.self, forKey: .id) ?? UUID().uuidString
        title = try container.decode(String.self, forKey: .title)
        difficulty = try container.decodeIfPresent(Difficulty.self, forKey: .difficulty) ?? .beginner
        customPrompt = try container.decodeIfPresent(String.self, forKey: .customPrompt) ?? StudySettings.defaultCustomPrompt
        openAIModel = try container.decodeIfPresent(String.self, forKey: .openAIModel) ?? StudySettings.defaultOpenAIModel
        createdAt = try container.decodeIfPresent(Date.self, forKey: .createdAt) ?? Date()
    }

    var normalizedTitle: String {
        title.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var normalizedCustomPrompt: String {
        let trimmed = customPrompt.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? StudySettings.defaultCustomPrompt : trimmed
    }

    var sanitizedOpenAIModel: String {
        let trimmedModel = openAIModel.trimmingCharacters(in: .whitespacesAndNewlines)
        guard OpenAIModelOption.all.contains(where: { $0.id == trimmedModel }) else {
            return StudySettings.defaultOpenAIModel
        }

        return trimmedModel
    }
}

extension AppLanguage {
    var studyLanguage: StudyLanguage {
        switch self {
        case .korean:
            .korean
        case .english:
            .english
        }
    }
}

struct StudySettings: Codable, Equatable {
    static let defaultOpenAIModel = "gpt-5.4"
    static let fallbackTopic = "내 학습"
    static let fallbackTopicEnglish = "My Study"
    static let defaultCustomPrompt = "짧고 명확하게 질문하세요. 사용자가 답하기 좋은 한 문제만 내세요."
    private static let fallbackCategoryCreatedAt = Date(timeIntervalSince1970: 0)
    private static let fallbackTopicIDByLanguage: [AppLanguage: String] = [
        .korean: "builtin-study-category-default-ko",
        .english: "builtin-study-category-default-en"
    ]

    static func fallbackTopic(for appLanguage: AppLanguage) -> String {
        appLanguage == .english ? fallbackTopicEnglish : fallbackTopic
    }

    static func fallbackTopicID(for appLanguage: AppLanguage) -> String {
        fallbackTopicIDByLanguage[appLanguage] ?? "builtin-study-category-default"
    }

    static func deterministicFallbackCategoryID(for appLanguage: AppLanguage) -> String {
        fallbackTopicID(for: appLanguage)
    }

    static func localizedFallbackTopic(for appLanguage: AppLanguage) -> String {
        fallbackTopic(for: appLanguage)
    }

    var topic: String
    var difficulty: Difficulty
    var appLanguage: AppLanguage
    var language: StudyLanguage
    var openAIModel: String
    var notificationSound: NotificationSoundOption
    var customPrompt: String
    var intervalMinutes: Int
    var maxHistoryCount: Int
    var isQuestionPublic: Bool
    var studyCategories: [StudyCategory]
    var selectedStudyCategoryID: String?

    init(
        topic: String,
        difficulty: Difficulty,
        appLanguage: AppLanguage = .korean,
        language: StudyLanguage = .korean,
        openAIModel: String = StudySettings.defaultOpenAIModel,
        notificationSound: NotificationSoundOption = .defaultSound,
        customPrompt: String,
        intervalMinutes: Int,
        maxHistoryCount: Int = 100,
        isQuestionPublic: Bool = false,
        studyCategories: [StudyCategory] = [],
        selectedStudyCategoryID: String? = nil
    ) {
        let languageFallback = Self.fallbackTopic(for: appLanguage)
        let resolvedTopic = Self.normalizedString(topic, fallback: languageFallback)
        let categoryFallback = studyCategories.isEmpty ? languageFallback : languageFallback
        let normalizedCategories = Self.normalizedCategories(
            categories: studyCategories,
            fallbackTopic: categoryFallback,
            fallbackTitle: languageFallback
        )
        let activeCategoryID = Self.activeCategoryID(
            selected: selectedStudyCategoryID,
            in: normalizedCategories
        )
        let effectiveTopic = Self.resolveActiveTopic(
            topic: resolvedTopic,
            categories: normalizedCategories,
            selectedCategoryID: activeCategoryID,
            fallbackTitle: Self.localizedFallbackTopic(for: appLanguage)
        )

        let activeCategory = normalizedCategories.first { $0.id == activeCategoryID }

        self.topic = effectiveTopic
        self.difficulty = activeCategory?.difficulty ?? difficulty
        self.appLanguage = appLanguage
        self.language = language
        self.openAIModel = activeCategory?.sanitizedOpenAIModel ?? openAIModel
        self.notificationSound = notificationSound
        self.customPrompt = activeCategory?.normalizedCustomPrompt ?? customPrompt
        self.intervalMinutes = intervalMinutes
        self.maxHistoryCount = maxHistoryCount
        self.isQuestionPublic = isQuestionPublic
        self.studyCategories = normalizedCategories
        self.selectedStudyCategoryID = activeCategoryID
    }

    private enum CodingKeys: String, CodingKey {
        case topic
        case difficulty
        case appLanguage
        case language
        case openAIModel
        case notificationSound
        case customPrompt
        case intervalMinutes
        case maxHistoryCount
        case isQuestionPublic
        case studyCategories
        case selectedStudyCategoryID
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let rawTopic = try container.decode(String.self, forKey: .topic)
        let decodedDifficulty = try container.decode(Difficulty.self, forKey: .difficulty)
        appLanguage = try container.decodeIfPresent(AppLanguage.self, forKey: .appLanguage) ?? .korean
        language = try container.decodeIfPresent(StudyLanguage.self, forKey: .language) ?? .korean
        openAIModel = try container.decodeIfPresent(String.self, forKey: .openAIModel) ?? Self.defaultOpenAIModel
        notificationSound = try container.decodeIfPresent(NotificationSoundOption.self, forKey: .notificationSound) ?? .defaultSound
        let decodedCustomPrompt = try container.decode(String.self, forKey: .customPrompt)
        intervalMinutes = try container.decode(Int.self, forKey: .intervalMinutes)
        maxHistoryCount = try container.decodeIfPresent(Int.self, forKey: .maxHistoryCount) ?? 100
        isQuestionPublic = try container.decodeIfPresent(Bool.self, forKey: .isQuestionPublic) ?? false

        let decodedCategories = try container.decodeIfPresent([StudyCategory].self, forKey: .studyCategories) ?? []
        let resolvedTopic = Self.normalizedString(rawTopic, fallback: Self.fallbackTopic(for: appLanguage))
        let languageFallback = Self.fallbackTopic(for: appLanguage)
        let categoryFallback = decodedCategories.isEmpty ? languageFallback : languageFallback
        let decodedSelectedID = try container.decodeIfPresent(String.self, forKey: .selectedStudyCategoryID)
        let resolvedCategories = Self.normalizedCategories(
            categories: decodedCategories,
            fallbackTopic: categoryFallback,
            fallbackTitle: languageFallback
        )
        let selectedID = Self.activeCategoryID(
            selected: decodedSelectedID,
            in: resolvedCategories
        )

        let activeCategory = resolvedCategories.first { $0.id == selectedID }

        topic = Self.resolveActiveTopic(
            topic: resolvedTopic,
            categories: resolvedCategories,
            selectedCategoryID: selectedID,
            fallbackTitle: languageFallback
        )
        difficulty = activeCategory?.difficulty ?? decodedDifficulty
        customPrompt = activeCategory?.normalizedCustomPrompt ?? decodedCustomPrompt
        studyCategories = resolvedCategories
        selectedStudyCategoryID = selectedID
    }

    static let `default` = StudySettings(
        topic: fallbackTopic(for: .korean),
        difficulty: .beginner,
        customPrompt: defaultCustomPrompt,
        intervalMinutes: 15
    )

    func category(for id: String?) -> StudyCategory? {
        guard let id else {
            return nil
        }

        return studyCategories.first { $0.id == id }
    }

    var activeCategory: StudyCategory? {
        category(for: selectedStudyCategoryID)
    }

    var effectiveTopic: String {
        activeCategory?.normalizedTitle.isEmpty == true
            ? Self.fallbackTopic(for: appLanguage)
            : activeCategory?.normalizedTitle ?? topic
    }

    var sanitizedIntervalMinutes: Int {
        min(max(intervalMinutes, 1), 240)
    }

    var sanitizedMaxHistoryCount: Int {
        min(max(maxHistoryCount, 10), 10_000)
    }

    var sanitizedOpenAIModel: String {
        let trimmedModel = openAIModel.trimmingCharacters(in: .whitespacesAndNewlines)
        guard OpenAIModelOption.all.contains(where: { $0.id == trimmedModel }) else {
            return Self.defaultOpenAIModel
        }

        return trimmedModel
    }

    static func normalizedCategories(
        categories: [StudyCategory],
        fallbackTopic: String,
        fallbackTitle: String
    ) -> [StudyCategory] {
        let fallback = normalizedString(fallbackTopic, fallback: fallbackTitle)
        var result: [StudyCategory] = []
        var seen: Set<String> = []
        let fallbackKey = normalizedCategoryKey(fallback)

        for category in categories {
            let title = normalizedString(category.title, fallback: "")
            guard !title.isEmpty else {
                continue
            }

            let key = Self.normalizedCategoryKey(title)
            guard key != fallbackKey else {
                continue
            }

            guard !seen.contains(key) else {
                continue
            }

            seen.insert(key)
            result.append(
                StudyCategory(
                    id: category.id.isEmpty ? UUID().uuidString : category.id,
                    title: title,
                    difficulty: category.difficulty,
                    customPrompt: category.normalizedCustomPrompt,
                    openAIModel: category.sanitizedOpenAIModel,
                    createdAt: category.createdAt
                )
            )
        }

        return result
    }

    static func activeCategoryID(
        selected: String?,
        in categories: [StudyCategory]
    ) -> String? {
        guard let selected,
              categories.contains(where: { $0.id == selected }) else {
            return nil
        }

        return selected
    }

    static func resolveActiveTopic(
        topic: String,
        categories: [StudyCategory],
        selectedCategoryID: String?,
        fallbackTitle: String
    ) -> String {
        let normalizedTopic = normalizedString(topic, fallback: fallbackTitle)
        let selectedCategoryTitle = categories
            .first { $0.id == selectedCategoryID }?
            .normalizedTitle

        let fallback = normalizedCategoryTitle(
            from: normalizedTopic,
            fallbackTitle: fallbackTitle
        )

        if let selectedCategoryTitle, !selectedCategoryTitle.isEmpty {
            return selectedCategoryTitle
        }

        return fallback
    }

    private static func normalizedCategoryTitle(
        from text: String,
        fallbackTitle: String
    ) -> String {
        normalizedString(text, fallback: fallbackTitle)
    }

    private static func normalizedString(_ text: String, fallback: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? fallback : trimmed
    }

    private static func normalizedCategoryKey(_ text: String) -> String {
        text
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined()
    }

    func withStudyCategories(_ categories: [StudyCategory], selectedID: String? = nil) -> StudySettings {
        let fallbackTitle = Self.fallbackTopic(for: appLanguage)
        let categoryFallback = fallbackTitle
        let resolved = Self.normalizedCategories(
            categories: categories,
            fallbackTopic: categoryFallback,
            fallbackTitle: fallbackTitle
        )
        let resolvedSelectedID = Self.activeCategoryID(selected: selectedID, in: resolved)
        let resolvedTopic = Self.resolveActiveTopic(
            topic: topic,
            categories: resolved,
            selectedCategoryID: resolvedSelectedID,
            fallbackTitle: fallbackTitle
        )
        let activeCategory = resolved.first { $0.id == resolvedSelectedID }

        return StudySettings(
            topic: resolvedTopic,
            difficulty: activeCategory?.difficulty ?? difficulty,
            appLanguage: appLanguage,
            language: language,
            openAIModel: activeCategory?.sanitizedOpenAIModel ?? openAIModel,
            notificationSound: notificationSound,
            customPrompt: activeCategory?.normalizedCustomPrompt ?? customPrompt,
            intervalMinutes: intervalMinutes,
            maxHistoryCount: maxHistoryCount,
            isQuestionPublic: isQuestionPublic,
            studyCategories: resolved,
            selectedStudyCategoryID: resolvedSelectedID
        )
    }

    func withSelectedCategoryID(_ categoryID: String?) -> StudySettings {
        let fallbackTitle = Self.fallbackTopic(for: appLanguage)
        let categoryFallback = fallbackTitle
        let normalizedCategories = Self.normalizedCategories(
            categories: studyCategories,
            fallbackTopic: categoryFallback,
            fallbackTitle: fallbackTitle
        )
        let resolvedCategoryID = Self.activeCategoryID(selected: categoryID, in: normalizedCategories)
        let resolvedTopic = Self.resolveActiveTopic(
            topic: topic,
            categories: normalizedCategories,
            selectedCategoryID: resolvedCategoryID,
            fallbackTitle: fallbackTitle
        )
        let activeCategory = normalizedCategories.first { $0.id == resolvedCategoryID }

        return StudySettings(
            topic: resolvedTopic,
            difficulty: activeCategory?.difficulty ?? difficulty,
            appLanguage: appLanguage,
            language: language,
            openAIModel: activeCategory?.sanitizedOpenAIModel ?? openAIModel,
            notificationSound: notificationSound,
            customPrompt: activeCategory?.normalizedCustomPrompt ?? customPrompt,
            intervalMinutes: intervalMinutes,
            maxHistoryCount: maxHistoryCount,
            isQuestionPublic: isQuestionPublic,
            studyCategories: normalizedCategories,
            selectedStudyCategoryID: resolvedCategoryID
        )
    }

    func withQuestionPrivacy(_ isQuestionPublic: Bool) -> StudySettings {
        StudySettings(
            topic: topic,
            difficulty: difficulty,
            appLanguage: appLanguage,
            language: language,
            openAIModel: openAIModel,
            notificationSound: notificationSound,
            customPrompt: customPrompt,
            intervalMinutes: intervalMinutes,
            maxHistoryCount: maxHistoryCount,
            isQuestionPublic: isQuestionPublic,
            studyCategories: studyCategories,
            selectedStudyCategoryID: selectedStudyCategoryID
        )
    }
}

struct OpenAIModelOption: Identifiable, Equatable {
    var id: String
    var displayName: String
    var supportsTextVerbosity: Bool

    static let all: [OpenAIModelOption] = [
        OpenAIModelOption(id: "gpt-5.5", displayName: "GPT-5.5", supportsTextVerbosity: true),
        OpenAIModelOption(id: "gpt-5.4", displayName: "GPT-5.4", supportsTextVerbosity: true),
        OpenAIModelOption(id: "gpt-5.2", displayName: "GPT-5.2", supportsTextVerbosity: true),
        OpenAIModelOption(id: "gpt-5.2-pro", displayName: "GPT-5.2 pro", supportsTextVerbosity: true),
        OpenAIModelOption(id: "gpt-5.1", displayName: "GPT-5.1", supportsTextVerbosity: true),
        OpenAIModelOption(id: "gpt-5", displayName: "GPT-5", supportsTextVerbosity: true),
        OpenAIModelOption(id: "gpt-5-mini", displayName: "GPT-5 mini", supportsTextVerbosity: true),
        OpenAIModelOption(id: "gpt-5-nano", displayName: "GPT-5 nano", supportsTextVerbosity: true),
        OpenAIModelOption(id: "gpt-4.1", displayName: "GPT-4.1", supportsTextVerbosity: false),
        OpenAIModelOption(id: "gpt-4.1-mini", displayName: "GPT-4.1 mini", supportsTextVerbosity: false),
        OpenAIModelOption(id: "gpt-4.1-nano", displayName: "GPT-4.1 nano", supportsTextVerbosity: false),
        OpenAIModelOption(id: "gpt-4o", displayName: "GPT-4o", supportsTextVerbosity: false),
        OpenAIModelOption(id: "gpt-4o-mini", displayName: "GPT-4o mini", supportsTextVerbosity: false),
    ]

    static func supportsTextVerbosity(modelID: String) -> Bool {
        all.first { $0.id == modelID }?.supportsTextVerbosity ?? false
    }
}

enum RecommendedPrompt: String, CaseIterable, Identifiable {
    case concept
    case interview
    case practical
    case scale
    case enterprise
    case review

    var id: String { rawValue }

    func title(language: AppLanguage) -> String {
        switch language {
        case .korean:
            switch self {
            case .concept:
                return "개념 확인형"
            case .interview:
                return "면접 질문형"
            case .practical:
                return "실전 예제형"
            case .scale:
                return "스케일 설계형"
            case .enterprise:
                return "대기업 실무형"
            case .review:
                return "복습 강화형"
            }
        case .english:
            switch self {
            case .concept:
                return "Concept Check"
            case .interview:
                return "Interview Style"
            case .practical:
                return "Practical Example"
            case .scale:
                return "Scale Design"
            case .enterprise:
                return "Enterprise Practice"
            case .review:
                return "Review Focus"
            }
        }
    }

    func text(language: AppLanguage) -> String {
        switch language {
        case .korean:
            switch self {
            case .concept:
                return "핵심 개념을 정확히 이해했는지 확인하는 짧은 질문을 내세요. 한 번에 하나의 개념만 다루세요."
            case .interview:
                return "기술 면접처럼 질문하세요. 단순 정의보다 이유, trade-off, 실제 적용 상황을 설명하게 만드세요."
            case .practical:
                return "실무 상황이나 작은 예제를 기반으로 질문하세요. 사용자가 개념을 적용해서 답하도록 만드세요."
            case .scale:
                return "스케일 인/아웃 관점에서 질문하세요. 트래픽 증가, 병목, 샤딩/파티셔닝, 캐시, 큐, 장애 격리, 비용 trade-off를 함께 설명하게 만드세요."
            case .enterprise:
                return "대기업 실무 관점에서 질문하세요. 운영 안정성, 배포/롤백, 모니터링, 보안, 권한, 데이터 정합성, 장애 대응, 팀 간 협업까지 고려하게 만드세요."
            case .review:
                return "이전 질문과 겹치지 않게 복습 질문을 내세요. 자주 틀릴 만한 부분과 헷갈리는 차이를 확인하세요."
            }
        case .english:
            switch self {
            case .concept:
                return "Ask a short question that checks whether the core concept is understood. Cover only one concept at a time."
            case .interview:
                return "Ask like a technical interview. Make the user explain reasons, trade-offs, and practical usage, not just definitions."
            case .practical:
                return "Ask from a real work scenario or a small example. Make the user apply the concept in the answer."
            case .scale:
                return "Ask from a scale-in/scale-out design perspective. Make the user explain traffic growth, bottlenecks, sharding/partitioning, caching, queues, failure isolation, and cost trade-offs."
            case .enterprise:
                return "Ask from a large-company production perspective. Make the user consider reliability, deployment/rollback, monitoring, security, permissions, data consistency, incident response, and cross-team collaboration."
            case .review:
                return "Ask a review question that does not overlap with previous questions. Check common mistakes and confusing differences."
            }
        }
    }
}

struct OpenAIUsage: Codable, Equatable {
    var inputTokens: Int
    var cachedInputTokens: Int
    var outputTokens: Int
    var totalTokens: Int
}

struct QuestionItem: Codable, Equatable {
    var question: String
    var expectedAnswerHint: String?
    var createdAt: Date
}

struct GradingResult: Codable, Equatable {
    var score: Int
    var isCorrect: Bool
    var feedback: String
    var explanation: String
}

struct StudyRecord: Codable, Equatable, Identifiable {
    var id: String
    var question: QuestionItem
    var answer: String?
    var gradingResult: GradingResult?
    var topic: String
    var difficulty: Difficulty
    var answeredAt: Date?
    var isPublic: Bool

    enum CodingKeys: String, CodingKey {
        case id
        case question
        case answer
        case gradingResult
        case topic
        case difficulty
        case answeredAt
        case isPublic
    }

    init(
        id: String = UUID().uuidString,
        question: QuestionItem,
        answer: String? = nil,
        gradingResult: GradingResult? = nil,
        topic: String,
        difficulty: Difficulty,
        answeredAt: Date? = nil,
        isPublic: Bool = false
    ) {
        self.id = id
        self.question = question
        self.answer = answer
        self.gradingResult = gradingResult
        self.topic = topic
        self.difficulty = difficulty
        self.answeredAt = answeredAt
        self.isPublic = isPublic
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decodeIfPresent(String.self, forKey: .id) ?? UUID().uuidString
        question = try container.decode(QuestionItem.self, forKey: .question)
        answer = try container.decodeIfPresent(String.self, forKey: .answer)
        gradingResult = try container.decodeIfPresent(GradingResult.self, forKey: .gradingResult)
        topic = try container.decodeIfPresent(String.self, forKey: .topic) ?? ""
        difficulty = try container.decodeIfPresent(Difficulty.self, forKey: .difficulty) ?? Difficulty(level: 5)
        answeredAt = try container.decodeIfPresent(Date.self, forKey: .answeredAt)
        isPublic = try container.decodeIfPresent(Bool.self, forKey: .isPublic) ?? false
    }
}

struct DeletedStudyRecordMarker: Codable, Equatable, Identifiable {
    var recordID: String
    var normalizedQuestion: String
    var mergeKey: String
    var deletedAt: Date

    var id: String {
        [recordID, mergeKey, String(deletedAt.timeIntervalSince1970)].joined(separator: "|")
    }

    init(record: StudyRecord, deletedAt: Date = Date()) {
        self.recordID = record.id
        self.normalizedQuestion = Self.normalizedQuestionText(record.question.question)
        self.mergeKey = Self.mergeKey(for: record)
        self.deletedAt = deletedAt
    }

    func matches(_ record: StudyRecord) -> Bool {
        record.id == recordID ||
            Self.mergeKey(for: record) == mergeKey ||
            Self.normalizedQuestionText(record.question.question) == normalizedQuestion
    }

    static func mergeKey(for record: StudyRecord) -> String {
        [
            record.topic.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
            String(record.difficulty.level),
            normalizedQuestionText(record.question.question)
        ].joined(separator: "|")
    }

    static func normalizedQuestionText(_ question: String) -> String {
        question
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }
}

enum TopicGrouping {
    static func displayTopic(for record: StudyRecord, fallback: String) -> String {
        displayTopic(record.topic, fallback: fallback)
    }

    static func displayTopic(_ topic: String, fallback: String) -> String {
        let trimmed = topic.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? fallback : trimmed
    }

    static func normalizedKey(for record: StudyRecord, fallback: String) -> String {
        normalizedKey(for: record.topic, fallback: fallback)
    }

    static func normalizedKey(for topic: String, fallback: String) -> String {
        let display = displayTopic(topic, fallback: fallback)
        let expanded = display
            .replacingOccurrences(
                of: "([a-z0-9])([A-Z])",
                with: "$1 $2",
                options: .regularExpression
            )
            .replacingOccurrences(
                of: "([A-Za-z])([0-9])",
                with: "$1 $2",
                options: .regularExpression
            )
            .replacingOccurrences(
                of: "([0-9])([A-Za-z])",
                with: "$1 $2",
                options: .regularExpression
            )
        let folded = expanded
            .folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: .current)
            .lowercased()

        var key = ""
        for scalar in folded.unicodeScalars where scalar.properties.isAlphabetic || scalar.properties.numericType != nil {
            key.unicodeScalars.append(scalar)
        }

        return key.isEmpty ? "study" : key
    }

    static func preferredDisplayTopic(for records: [StudyRecord], fallback: String) -> String {
        var summaries: [String: (name: String, count: Int, latest: Date)] = [:]

        for record in records {
            let name = displayTopic(for: record, fallback: fallback)
            let latest = record.answeredAt ?? record.question.createdAt
            let key = name.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: .current)
                .lowercased()

            if let existing = summaries[key] {
                summaries[key] = (
                    name: existing.name,
                    count: existing.count + 1,
                    latest: max(existing.latest, latest)
                )
            } else {
                summaries[key] = (name: name, count: 1, latest: latest)
            }
        }

        return summaries.values.sorted {
            if $0.count != $1.count {
                return $0.count > $1.count
            }
            if $0.latest != $1.latest {
                return $0.latest > $1.latest
            }
            if $0.name.count != $1.name.count {
                return $0.name.count < $1.name.count
            }
            return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }.first?.name ?? fallback
    }

    static func displayAliases(for records: [StudyRecord], fallback: String) -> [String] {
        let names = Set(records.map { displayTopic(for: $0, fallback: fallback) })
        return names.sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
    }
}

struct CloudSyncSnapshot: Codable, Equatable {
    var schemaVersion: Int
    var updatedAt: Date
    var apiKey: String?
    var apiKeyUpdatedAt: Date?
    var settings: StudySettings
    var currentQuestion: QuestionItem?
    var questionHistory: [QuestionItem]
    var lastAnswer: String
    var gradingResult: GradingResult?
    var isRunning: Bool
    var hasCompletedOnboarding: Bool
    var studyRecords: [StudyRecord]
    var deletedStudyRecordMarkers: [DeletedStudyRecordMarker]
    var studyRecordsClearedAt: Date?

    private enum CodingKeys: String, CodingKey {
        case schemaVersion
        case updatedAt
        case apiKey
        case apiKeyUpdatedAt
        case settings
        case currentQuestion
        case questionHistory
        case lastAnswer
        case gradingResult
        case isRunning
        case hasCompletedOnboarding
        case studyRecords
        case deletedStudyRecordMarkers
        case studyRecordsClearedAt
    }

    init(
        schemaVersion: Int = 3,
        updatedAt: Date,
        apiKey: String? = nil,
        apiKeyUpdatedAt: Date? = nil,
        settings: StudySettings,
        currentQuestion: QuestionItem?,
        questionHistory: [QuestionItem],
        lastAnswer: String,
        gradingResult: GradingResult?,
        isRunning: Bool,
        hasCompletedOnboarding: Bool,
        studyRecords: [StudyRecord],
        deletedStudyRecordMarkers: [DeletedStudyRecordMarker] = [],
        studyRecordsClearedAt: Date? = nil
    ) {
        self.schemaVersion = schemaVersion
        self.updatedAt = updatedAt
        self.apiKey = apiKey
        self.apiKeyUpdatedAt = apiKeyUpdatedAt
        self.settings = settings
        self.currentQuestion = currentQuestion
        self.questionHistory = questionHistory
        self.lastAnswer = lastAnswer
        self.gradingResult = gradingResult
        self.isRunning = isRunning
        self.hasCompletedOnboarding = hasCompletedOnboarding
        self.studyRecords = studyRecords
        self.deletedStudyRecordMarkers = deletedStudyRecordMarkers
        self.studyRecordsClearedAt = studyRecordsClearedAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)

        schemaVersion = try container.decodeIfPresent(Int.self, forKey: .schemaVersion) ?? 1
        updatedAt = try container.decode(Date.self, forKey: .updatedAt)
        apiKey = try container.decodeIfPresent(String.self, forKey: .apiKey)
        apiKeyUpdatedAt = try container.decodeIfPresent(Date.self, forKey: .apiKeyUpdatedAt)
        settings = try container.decode(StudySettings.self, forKey: .settings)
        currentQuestion = try container.decodeIfPresent(QuestionItem.self, forKey: .currentQuestion)
        questionHistory = try container.decodeIfPresent([QuestionItem].self, forKey: .questionHistory) ?? []
        lastAnswer = try container.decodeIfPresent(String.self, forKey: .lastAnswer) ?? ""
        gradingResult = try container.decodeIfPresent(GradingResult.self, forKey: .gradingResult)
        isRunning = try container.decodeIfPresent(Bool.self, forKey: .isRunning) ?? false
        hasCompletedOnboarding = try container.decodeIfPresent(Bool.self, forKey: .hasCompletedOnboarding) ?? true
        studyRecords = try container.decodeIfPresent([StudyRecord].self, forKey: .studyRecords) ?? []
        deletedStudyRecordMarkers = try container.decodeIfPresent(
            [DeletedStudyRecordMarker].self,
            forKey: .deletedStudyRecordMarkers
        ) ?? []
        studyRecordsClearedAt = try container.decodeIfPresent(Date.self, forKey: .studyRecordsClearedAt)
    }
}

struct AppLogEntry: Codable, Equatable, Identifiable {
    var id: String
    var createdAt: Date
    var level: LogLevel
    var message: String

    init(
        id: String = UUID().uuidString,
        createdAt: Date = Date(),
        level: LogLevel,
        message: String
    ) {
        self.id = id
        self.createdAt = createdAt
        self.level = level
        self.message = message
    }
}

struct AppLogPage: Equatable {
    var entries: [AppLogEntry]
    var totalCount: Int
    var page: Int
    var pageSize: Int

    var pageCount: Int {
        let sanitizedPageSize = max(1, pageSize)
        return max(1, (totalCount + sanitizedPageSize - 1) / sanitizedPageSize)
    }
}

struct APITrafficLogEntry: Equatable, Identifiable {
    var id: String
    var createdAt: Date
    var method: String
    var url: String
    var statusCode: Int?
    var durationMS: Double
    var requestHeaders: String
    var requestBody: String
    var responseBody: String
    var error: String?
    var isError: Bool

    init(
        id: String = UUID().uuidString,
        createdAt: Date = Date(),
        method: String,
        url: String,
        statusCode: Int? = nil,
        durationMS: Double = 0,
        requestHeaders: String = "",
        requestBody: String = "",
        responseBody: String = "",
        error: String? = nil,
        isError: Bool = false
    ) {
        self.id = id
        self.createdAt = createdAt
        self.method = method
        self.url = url
        self.statusCode = statusCode
        self.durationMS = durationMS
        self.requestHeaders = requestHeaders
        self.requestBody = requestBody
        self.responseBody = responseBody
        self.error = error
        self.isError = isError
    }
}

extension APITrafficLogEntry {
    var headerSummary: String {
        statusCode.map { "\(method) \(url) [\($0)]" } ?? "\(method) \(url)"
    }

    var shortError: String {
        error ?? "No error"
    }

    var durationText: String {
        String(format: "%.0fms", durationMS)
    }

    var compactSummary: String {
        "\(headerSummary) in \(durationText)"
    }
}

enum APITrafficNotification {
    static let didReceiveLog = Notification.Name("studyAPITrafficDidReceiveLog")
    static let userInfoKey = "studyAPITrafficLogEntry"
}

enum LogLevel: String, Codable, CaseIterable {
    case info
    case warning
    case error

    var displayName: String {
        switch self {
        case .info:
            "Info"
        case .warning:
            "Warning"
        case .error:
            "Error"
        }
    }
}

extension Difficulty {
    func displayName(language: AppLanguage) -> String {
        switch language {
        case .korean:
            return displayName
        case .english:
            return "Level \(level)/10"
        }
    }
}

struct AppStrings {
    var language: AppLanguage

    private var isKorean: Bool {
        language == .korean
    }

    private func text(_ korean: String, _ english: String) -> String {
        isKorean ? korean : english
    }

    var tabStudy: String { text("학습", "Study") }
    var tabHome: String { text("홈", "Home") }
    var tabSettings: String { text("설정", "Settings") }
    var tabRecords: String { text("기록", "Records") }
    var tabStatistics: String { text("통계", "Stats") }
    func homePath(_ category: String) -> String {
        category
    }
    var onboardingTitle: String { text("BuddyStuddy 시작하기", "Set Up BuddyStuddy") }
    var onboardingSubtitle: String {
        text(
            "AI를 더 잘 쓰려면 스스로의 지식도 필요합니다. BuddyStuddy는 짧은 질문으로 그 지식을 계속 유지하게 돕습니다.",
            "Better AI output still depends on what you know. BuddyStuddy keeps that knowledge active with short questions."
        )
    }
    var onboardingFreeNote: String {
        text(
            "앱은 무료입니다. OpenAI API 키만 있으면 바로 사용할 수 있습니다.",
            "The app is free. You only need your own OpenAI API key."
        )
    }
    var onboardingLanguage: String { text("언어", "Language") }
    var onboardingOpenAI: String { text("OpenAI 연결", "OpenAI Connection") }
    var onboardingStudySetup: String { text("학습 설정", "Study Setup") }
    var onboardingAPIKeyHelp: String {
        text(
            "API 키는 이 Mac의 앱 설정에 저장됩니다. 나중에 Settings > Secrets에서 바꿀 수 있습니다.",
            "The API key is stored in this Mac's app settings. You can change it later in Settings > Secrets."
        )
    }
    var onboardingCreateAPIKeyHelp: String { text("OpenAI 키가 없다면", "If you don't have an OpenAI key") }
    var onboardingCreateAPIKeyAction: String { text("여기서 키 발급하기", "Create a key here") }
    var onboardingStart: String { text("시작하기", "Start") }
    var onboardingSkip: String { text("나중에 설정", "Set Up Later") }
    var onboardingCompleted: String { text("온보딩을 완료했습니다.", "Onboarding complete.") }
    var onboardingSkipped: String { text("설정 탭에서 나중에 마저 설정하세요.", "Finish setup later in Settings.") }
    var onboardingCompletedWithoutAPIKey: String {
        text(
            "API 키가 없어 타이머를 일시정지했습니다. Settings > Secrets에서 키를 입력하세요.",
            "Timer paused because the API key is empty. Add it in Settings > Secrets."
        )
    }
    var apiKeyCheckingAfterOnboarding: String { text("API 키를 확인 중입니다.", "Checking API key.") }

    func statusTitle(isRunning: Bool) -> String {
        if isRunning {
            return text("BuddyStuddy 실행 중", "BuddyStuddy is running")
        }
        return text("BuddyStuddy 정지됨", "BuddyStuddy is stopped")
    }

    var invalidAPIKey: String { text("API 키가 잘못되었습니다", "Invalid API key") }
    var notificationTitle: String { "BuddyStuddy" }
    var cloudQuestionPushBody: String {
        text("새 학습 질문이 도착했습니다. 탭해서 이어가세요.", "A new study question is ready. Tap to continue.")
    }
    var reply: String { text("답장", "Reply") }
    var send: String { text("보내기", "Send") }
    var answerPlaceholder: String { text("답변작성", "Write answer") }
    var otherAnswer: String { text("다른 응답", "Other Answer") }
    var ignore: String { text("무시", "Ignore") }
    var openStudy: String { text("학습 열기...", "Open Study...") }
    var aboutStudyMate: String { text("BuddyStuddy 정보", "About BuddyStuddy") }
    func timerTitle(minutes: Int) -> String { text("타이머: \(minutes)분", "Timer: \(minutes) min") }
    func minuteLabel(_ minutes: Int) -> String { text("\(minutes)분", "\(minutes) min") }
    var languageMenu: String { text("언어", "Language") }
    var pause: String { text("일시정지", "Pause") }
    var resume: String { text("재개", "Resume") }
    var quit: String { text("BuddyStuddy 종료", "Quit BuddyStuddy") }

    var general: String { "General" }
    var secrets: String { "Secrets" }
    var study: String { "Study" }
    var records: String { "Records" }
    var developer: String { "Developer" }

    var checking: String { text("확인 중", "Checking") }
    var save: String { text("저장", "Save") }
    var saving: String { text("저장 중", "Saving") }
    var cancel: String { text("취소", "Cancel") }
    var pasteboardChecking: String { text("클립보드에서 키를 확인 중입니다.", "Checking clipboard for API key.") }
    var saved: String { text("저장됨", "Saved") }
    var done: String { text("완료", "Done") }
    var add: String { text("추가", "Add") }
    var refresh: String { text("새로고침", "Refresh") }
    var refreshed: String { text("새로고침했습니다.", "Refreshed.") }
    var apiKey: String { text("API 키", "API key") }
    var openAIAPIKey: String { text("OpenAI API 키", "OpenAI API key") }
    var hide: String { text("숨기기", "Hide") }
    var show: String { text("보기", "Show") }
    var openAIAPIKeyCopied: String {
        text("OpenAI API 키를 붙여넣었습니다.", "OpenAI API key pasted.")
    }

    var openAIAPIKeyMissing: String {
        text("클립보드에 OpenAI 키가 없습니다.", "No OpenAI key in clipboard.")
    }
    var apiKeyEmpty: String { text("API 키를 입력하세요.", "Enter an API key.") }
    var apiKeyCheck: String { text("API 키를 확인하세요.", "Check the API key.") }
    var apiKeyEmptyDetailed: String { text("API 키가 비어 있습니다. Settings > Secrets에서 OpenAI API 키를 입력하세요.", "API key is empty. Enter an OpenAI API key in Settings > Secrets.") }
    var apiKeyInvalidDetailed: String { text("API 키가 잘못되었습니다. Settings > Secrets에서 OpenAI API 키를 확인하세요.", "API key is invalid. Check your OpenAI API key in Settings > Secrets.") }
    var openAIAPIKeyHelp: String {
        text(
            "질문 생성과 채점에 사용합니다. 일반 프로젝트 API 키를 입력하세요.",
            "Used for question generation and grading. Enter a normal project API key."
        )
    }
    var openAIModel: String { text("모델", "Model") }
    var openAIModelHelp: String {
        text("질문 생성과 채점에 사용할 OpenAI 모델입니다.", "OpenAI model for question generation and grading.")
    }
    var openAIBilling: String { text("OpenAI 사용량/결제", "OpenAI Usage / Billing") }
    var openAIUsageAndCostsPage: String { text("사용량/비용 보기", "View Usage / Costs") }
    var openAIBillingPage: String { text("빌링 추가", "Add Billing") }
    var openAIBillingHelp: String {
        text(
            "사용량, 비용, 빌링은 OpenAI Platform에서 직접 확인하세요.",
            "Check usage, costs, and billing directly in OpenAI Platform."
        )
    }
    var iCloudSync: String { text("iCloud 동기화", "iCloud Sync") }
    var iCloudSyncHelp: String {
        text(
            "학습 설정, 현재 질문, 답변 초안, 기록, OpenAI API 키를 iPhone과 Mac 사이에 동기화합니다.",
            "Syncs study settings, the current question, answer drafts, records, and the OpenAI API key between iPhone and Mac."
        )
    }
    var iCloudSyncOn: String { text("동기화 켜짐", "Sync On") }
    var iCloudSyncOff: String { text("동기화 꺼짐", "Sync Off") }
    var syncNow: String { text("지금 동기화", "Sync Now") }
    var syncing: String { text("동기화 중", "Syncing") }
    var syncAlreadyInProgress: String { text("이미 동기화 중입니다.", "Sync is already in progress.") }
    var syncUpdated: String { text("iCloud 동기화가 완료됐습니다.", "iCloud sync complete.") }
    var syncAlreadyCurrent: String { text("iCloud 데이터가 최신입니다.", "iCloud data is up to date.") }
    var syncPulledRemote: String { text("iCloud의 최신 데이터를 불러왔습니다.", "Loaded the latest iCloud data.") }
    var syncMergedRemote: String {
        text(
            "iCloud 데이터를 불러오고 이 기기의 기록을 함께 병합했습니다.",
            "Loaded iCloud data and merged this device's records."
        )
    }
    var syncPushedLocal: String { text("이 기기의 데이터를 iCloud에 저장했습니다.", "Saved this device's data to iCloud.") }
    var syncUnavailable: String {
        text(
            "iCloud 계정 또는 CloudKit 권한을 확인하세요.",
            "Check the iCloud account or CloudKit permission."
        )
    }
    var syncEntitlementMissing: String {
        text(
            "이 앱 빌드에 iCloud 권한이 없습니다. 최신 릴리즈를 다시 설치하세요.",
            "This app build does not include iCloud entitlement. Reinstall the latest release."
        )
    }
    var syncQuotaExceeded: String {
        text(
            "iCloud 저장 공간이 부족해 동기화하지 못했습니다. iCloud 공간을 확보한 뒤 다시 시도하세요.",
            "iCloud storage is full, so sync could not finish. Free up iCloud storage and try again."
        )
    }
    var syncNotAuthenticated: String {
        text(
            "iCloud 로그인이 필요합니다. 시스템 설정에서 iCloud 계정을 확인하세요.",
            "iCloud sign-in is required. Check your iCloud account in System Settings."
        )
    }
    var syncPermissionDenied: String {
        text(
            "iCloud 권한 또는 앱의 CloudKit 설정을 확인하세요.",
            "Check iCloud permission or the app's CloudKit setup."
        )
    }
    var syncNetworkUnavailable: String {
        text(
            "네트워크 연결 문제로 iCloud 동기화가 실패했습니다. 연결 후 다시 시도하세요.",
            "iCloud sync failed because the network is unavailable. Reconnect and try again."
        )
    }
    var syncServiceUnavailable: String {
        text(
            "iCloud 서비스가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도하세요.",
            "iCloud is temporarily unavailable. Try again later."
        )
    }
    var syncRateLimited: String {
        text(
            "iCloud 요청이 너무 많아 잠시 대기 중입니다. 조금 뒤 다시 시도하세요.",
            "iCloud is rate limiting requests. Try again shortly."
        )
    }
    var syncLimitExceeded: String {
        text(
            "동기화 데이터가 iCloud 제한을 초과했습니다. 오래된 기록을 줄인 뒤 다시 시도하세요.",
            "Sync data exceeded an iCloud limit. Reduce older records and try again."
        )
    }
    var syncConflictRetry: String {
        text(
            "다른 기기와 동시에 변경되어 동기화가 실패했습니다. 다시 동기화하세요.",
            "Sync conflicted with another device change. Sync again."
        )
    }
    func syncFailed(_ reason: String) -> String {
        text("iCloud 동기화 실패: \(reason)", "iCloud sync failed: \(reason)")
    }
    func lastSyncedAt(_ date: Date) -> String {
        text(
            "마지막 동기화: \(date.formatted(date: .abbreviated, time: .shortened))",
            "Last synced: \(date.formatted(date: .abbreviated, time: .shortened))"
        )
    }
    var unsavedAPIKeyHelp: String {
        text("변경사항이 있습니다. 저장해도 API 키 검증 실패 시 값은 유지됩니다.", "You have unsaved changes. Values are kept even if API key validation fails.")
    }
    var apiKeyStorageHelp: String { text("키는 앱 설정에 저장됩니다.", "Keys are stored in app settings.") }

    var generalSettings: String { text("일반", "General") }
    var appLanguageHelp: String { text("언어를 바꾸면 학습 언어도 같은 언어로 설정됩니다.", "Changing Language also sets the study language to match.") }
    var notifications: String { text("알림", "Notifications") }
    var notificationPermissionHelp: String {
        text("시스템 설정에서 BuddyStuddy 알림과 사운드 허용 여부를 직접 확인하세요.", "Check BuddyStuddy notification and sound permissions directly in system settings.")
    }
    var openNotificationSettings: String { text("시스템 알림 설정 열기", "Open Notification Settings") }
    var testNotification: String { text("테스트 알림", "Test Notification") }
    var testNotificationBody: String {
        text(
            "알림이 보이면 BuddyStuddy 알림 권한은 정상입니다.",
            "If you see this, BuddyStuddy notification permission is working."
        )
    }
    var testNotificationSent: String { text("테스트 알림을 보냈습니다.", "Test notification sent.") }
    var testNotificationFailed: String {
        text(
            "알림을 보내지 못했습니다. 시스템 알림 설정을 확인하세요.",
            "Could not send a notification. Check system notification settings."
        )
    }
    var notificationSound: String { text("알림음", "Notification sound") }
    var notificationSoundHelp: String { text("질문 알림을 받을 때 소리를 낼지 선택합니다.", "Choose whether question notifications play a sound.") }
    var updates: String { text("업데이트", "Updates") }
    var automaticallyCheckForUpdates: String { text("자동으로 업데이트 확인", "Automatically check for updates") }
    var automaticallyDownloadUpdates: String { text("가능하면 자동으로 다운로드", "Automatically download updates when available") }
    var checkForUpdates: String { text("업데이트 확인...", "Check for Updates...") }
    var search: String { text("검색", "Search") }
    var edit: String { text("편집", "Edit") }
    var updateHelp: String {
        text("GitHub Releases에 새 DMG가 올라오면 BuddyStuddy가 업데이트를 안내합니다.", "BuddyStuddy checks GitHub Releases and offers updates when a new DMG is available.")
    }
    var updateInstallHelp: String {
        text(
            "DMG 안이나 임시 위치에서 실행 중이면 업데이트할 수 없습니다. BuddyStuddy.app을 Applications 폴더로 옮긴 뒤 다시 실행하세요.",
            "Updates are unavailable when BuddyStuddy is running from a DMG or temporary location. Move BuddyStuddy.app to Applications and relaunch it."
        )
    }
    var uninstall: String { text("BuddyStuddy 제거", "Uninstall BuddyStuddy") }
    var uninstallHelp: String {
        text("앱을 휴지통으로 이동하고 로컬 설정과 캐시를 삭제합니다.", "Move the app to Trash and delete local settings and caches.")
    }
    var uninstallConfirmationTitle: String { text("BuddyStuddy를 제거할까요?", "Uninstall BuddyStuddy?") }
    var uninstallConfirmationMessage: String {
        text("앱, 로컬 설정, 캐시가 삭제되고 BuddyStuddy가 종료됩니다.", "The app, local settings, and caches will be deleted, then BuddyStuddy will quit.")
    }
    func uninstallFailed(_ reason: String) -> String {
        text("앱 제거 실패: \(reason)", "Uninstall failed: \(reason)")
    }
    var studySettings: String { text("학습 설정", "Study Settings") }
    var studyCategories: String { text("내 학습", "My Studies") }
    var homeScopeMy: String { text("내 학습", "My Studies") }
    var homeScopeAll: String { text("모든 학습들", "All Studies") }
    var myStudyLoginHelp: String {
        text(
            "내 학습을 만들고 답변하려면 먼저 로그인하세요.",
            "Sign in first to create and answer your own studies."
        )
    }
    var editCategories: String { text("학습 편집", "Edit Studies") }
    var studyCategory: String { text("학습", "Study") }
    var newStudyCategory: String { text("학습 추가", "Add Study") }
    var editStudyCategory: String { text("학습 편집", "Edit Study") }
    var activeStudy: String { text("활성 학습", "Active Study") }
    var activateStudy: String { text("활성화", "Activate") }
    var enterStudyTopic: String { text("선택한 학습 시작", "Open selected study") }
    var noStudyCategoryHelp: String { text("학습을 하나 이상 추가하세요.", "Add at least one study.") }
    var currentStudyCategory: String { text("현재 학습", "Current Study") }
    var studyProfileHelp: String {
        text("각 학습마다 주제, 난이도, 프롬프트를 따로 저장합니다. 질문 간격은 설정에서 공통으로 관리합니다.", "Each study keeps its own topic, difficulty, and prompt. Question interval is shared in Settings.")
    }
    var editInHome: String { text("홈에서 관리", "Manage in Home") }
    var questionVisibility: String { text("질문 공개", "Question Visibility") }
    var makeQuestionPublic: String { text("질문 공개", "Make Question Public") }
    var makeQuestionPrivate: String { text("질문 비공개", "Make Question Private") }
    var questionVisibilityHelp: String {
        text(
            "로그인한 사용자에게만 공개됩니다. OFF로 설정하면 내가 생성한 질문이 커뮤니티에 노출되지 않습니다.",
            "Visible only to signed-in users. When off, your generated questions are not shown in the community feed."
        )
    }
    var appLanguage: String { text("언어", "Language") }
    var studyTopic: String { text("공부할 주제", "Study topic") }
    var difficulty: String { text("난이도", "Difficulty") }
    var difficultyScaleHint: String { text("1은 가장 쉬움, 10은 전문가 수준입니다.", "1 is easiest, 10 is expert-level.") }
    func questionInterval(minutes: Int) -> String { text("질문 간격: \(minutes)분", "Question interval: \(minutes) min") }
    var recommendedPrompt: String { text("추천 프롬프트", "Recommended Prompt") }
    var relatedPrompt: String { text("관련 프롬프트", "Prompt") }

    var maxRecordCount: String { text("기록 최대 개수", "Max records") }
    var countUnit: String { text("개", "") }
    func recordLimitHelp(limit: Int, count: Int) -> String {
        text("저장 시 \(limit)개 범위로 정리됩니다. 현재 저장된 기록: \(count)개", "Records are trimmed to \(limit) on save. Current records: \(count)")
    }
    var deleteRecords: String { text("기록 전체삭제", "Delete All Records") }
    var deleteRecordsHelp: String { text("저장된 질문, 답변, 채점 기록을 모두 삭제합니다.", "Delete all saved questions, answers, and grading results.") }
    var recordSettings: String { text("기록 설정", "Record Settings") }
    var debuggingMode: String { text("디버깅 모드", "Debugging Mode") }
    var paste: String { text("붙여넣기", "Paste") }
    var debuggingHelp: String { text("켜면 Developer 로그를 확인할 수 있습니다.", "When enabled, Developer logs are available.") }
    var developerOptions: String { text("개발자 옵션", "Developer Options") }
    var apiDebugWindowTitle: String { text("API 통신 로그", "API Traffic Logs") }
    var requestLabel: String { text("요청", "Request") }
    var responseLabel: String { text("응답", "Response") }
    var statusLabel: String { text("상태", "Status") }
    var durationLabel: String { text("소요", "Duration") }
    var apiStatus: String { text("API 상태", "API Status") }
    var apiKeyErrorDetected: String { text("API 키 오류가 감지됐습니다.", "An API key error was detected.") }
    var apiKeyNoError: String { text("API 키 오류가 없습니다.", "No API key error.") }
    var logs: String { text("로그", "Logs") }
    var deleteLogs: String { text("로그 삭제", "Delete Logs") }
    var logLimitHelp: String { text("최근 로그는 최대 1000개까지만 보관됩니다. 초과하면 오래된 로그부터 자동 삭제됩니다.", "Only the latest 1000 logs are kept. Older logs are deleted automatically.") }
    var noLogs: String { text("로그 없음", "No Logs") }
    var noLogsDescription: String { text("앱 이벤트와 오류가 여기에 표시됩니다.", "App events and errors appear here.") }

    var newQuestion: String { text("새 질문", "New Question") }
    var studyOverview: String { text("학습 현황", "Study Overview") }
    var studyTopicShort: String { text("주제", "Topic") }
    var studyLevelShort: String { text("레벨", "Level") }
    var studyIntervalShort: String { text("주기", "Interval") }
    var pendingShort: String { text("대기", "Pending") }
    var latestScoreShort: String { text("최근 점수", "Latest") }
    var averageScoreShort: String { text("평균", "Average") }
    var noScoreShort: String { text("-", "-") }
    var draftSaved: String { text("초안 자동 저장됨", "Draft auto-saved") }
    var continueOldestPending: String { text("오래된 질문 이어하기", "Continue Oldest") }
    var pendingQuestions: String { text("미제출 질문", "Pending Questions") }
    func pendingQuestionCount(_ count: Int) -> String { text("\(count)개 대기 중", "\(count) pending") }
    var pendingQuestionLimitTitle: String { text("미채점 질문이 3개입니다.", "There are 3 ungraded questions.") }
    var pendingQuestionLimitMessage: String {
        text(
            "미채점 질문을 답변하거나 기록 탭에서 삭제한 뒤 다시 새 질문 생성을 실행하세요.",
            "Answer an ungraded question or delete one from Records, then run New Question again."
        )
    }
    var current: String { text("현재", "Current") }
    var openPendingQuestion: String { text("답변하기", "Answer") }
    var question: String { text("질문", "Question") }
    var noQuestion: String { text("질문 없음", "No Question") }
    var noQuestionDescription: String { text("설정을 저장한 뒤 새 질문을 생성하세요.", "Save settings, then create a new question.") }
    var duplicateQuestionSkipped: String {
        text(
            "기존 질문과 너무 비슷한 질문이 반복되어 생성하지 않았습니다.",
            "BuddyStuddy did not save a repeated question."
        )
    }
    var notificationQuestionMissingTitle: String { text("열 수 없는 알림", "Unavailable Notification") }
    var openingNotificationQuestion: String { text("알림에서 질문을 여는 중입니다.", "Opening the question from notification.") }
    var notificationQuestionUnavailable: String {
        text(
            "이 질문은 이미 넘기기/삭제되어 열 수 없습니다.",
            "This question was already skipped or deleted and cannot be opened."
        )
    }
    var notificationQuestionUnavailableHelp: String {
        text(
            "남아있는 미제출 질문을 이어가거나 새 질문을 생성하세요.",
            "Continue another pending question or create a new one."
        )
    }
    var answer: String { text("답변", "Answer") }
    var gradeAnswer: String { text("채점 받기", "Grade Answer") }
    var skipQuestion: String { text("넘기기", "Skip") }
    var skipQuestionHelp: String { text("현재 미제출 질문을 넘기고 대기 중인 다음 질문으로 이동합니다.", "Skip the current ungraded question and move to the next pending one.") }
    var showHint: String { text("힌트 보기", "Show Hint") }
    var hideHint: String { text("힌트 숨기기", "Hide Hint") }
    var correct: String { text("정답", "Correct") }
    var nearlyCorrect: String { text("정답에 가까움", "Nearly Correct") }
    var partialCorrect: String { text("부분 정답", "Partially Correct") }
    var needsImprovement: String { text("보완 필요", "Needs Work") }

    var clear: String { text("삭제", "Delete") }
    var searchRecords: String { text("기록 검색", "Search records") }
    func filteredRecordCount(_ shown: Int, total: Int) -> String {
        text("\(shown)/\(total)개 표시", "\(shown)/\(total) shown")
    }
    var noSearchResults: String { text("검색 결과 없음", "No Results") }
    var noSearchResultsDescription: String { text("다른 검색어로 기록을 찾아보세요.", "Try another search term.") }
    var noRecords: String { text("기록 없음", "No Records") }
    var noRecordsDescription: String { text("질문을 생성하고 답변을 채점하면 기록이 쌓입니다.", "Records appear after you create questions and grade answers.") }
    var deleteRecordHelp: String { text("기록 삭제", "Delete Record") }
    var studyFallback: String { text("내 학습", "My Study") }
    var ungraded: String { text("미채점", "Ungraded") }
    var recordDetail: String { text("상세기록", "Record Detail") }
    func answerPrefix(_ answer: String) -> String { text("답변: \(answer)", "Answer: \(answer)") }

    var stats: String { text("통계", "Stats") }
    var noScores: String { text("점수 없음", "No Scores") }
    var noScoresDescription: String { text("답변을 채점하면 점수 그래프가 표시됩니다.", "A score graph appears after you grade answers.") }
    var noScoresInPeriod: String { text("선택한 기간에 점수 없음", "No Scores in This Period") }
    var noScoresInPeriodDescription: String {
        text("기간을 넓히거나 새 답변을 채점하면 통계가 표시됩니다.", "Widen the period or grade a new answer to show stats.")
    }
    var responses: String { text("응답", "Responses") }
    var responsesShort: String { text("응답", "Resp") }
    var average: String { text("평균", "Avg") }
    var best: String { text("최고", "Best") }
    var lowest: String { text("최저", "Low") }
    var latestScore: String { text("최근", "Latest") }
    var trend: String { text("변화", "Trend") }
    var period: String { text("기간", "Period") }
    var topicSearch: String { text("주제 검색", "Search Topics") }
    var clearSearch: String { text("검색어 지우기", "Clear Search") }
    var topic: String { text("주제", "Topic") }
    var topicBrowser: String { text("주제 탐색", "Topic Browser") }
    var communityFeed: String { text("다른 사용자 질문", "Community Questions") }
    var communityQuestion: String { text("공개 질문", "Community Question") }
    var communityLogin: String { text("로그인", "Sign In") }
    var signInWithGoogle: String { "Sign in with Google" }
    var signInWithEmail: String { "Sign in with Email" }
    var email: String { text("이메일", "Email") }
    var password: String { text("비밀번호", "Password") }
    var emailLoginHelp: String {
        text(
            "테스터용 이메일 로그인입니다. 처음 입력한 이메일과 비밀번호로 계정이 생성됩니다.",
            "Tester email sign-in. The first email and password you enter creates the account."
        )
    }
    var signInRequiredTitle: String { text("로그인이 필요합니다", "Sign In Required") }
    var communityLogout: String { text("로그아웃", "Sign Out") }
    var communitySignedIn: String { text("다른 사용자 질문 기능을 사용할 수 있습니다.", "Community questions are enabled.") }
    var communitySignedOut: String { text("다른 사용자 질문 기능을 껐습니다.", "Community questions are disabled.") }
    var communityLoginHelp: String {
        text(
            "로그인하면 다른 사용자들이 공개한 질문을 검색하고 볼 수 있습니다. 로그인 전에는 내 질문이 공개되지 않습니다.",
            "Sign in to search and view questions shared by other users. Your questions are private until you sign in."
        )
    }
    var communitySearchHelp: String { text("주제 키워드로 검색해 공개된 질문을 확인하세요.", "Search topic keywords and view public questions.") }
    var noCommunityQuestions: String { text("표시할 공개 질문이 없습니다.", "No public questions to display.") }
    var communityQuestionLimit: String { text("최대 20개씩 표시됩니다.", "Showing up to 20 questions at a time.") }
    var communityUnavailable: String { text("다른 사용자 질문 기능을 현재 사용할 수 없습니다.", "Community questions are currently unavailable.") }
    var communityRequestFailed: String { text("다른 사용자 질문을 불러오지 못했습니다.", "Could not load community questions.") }
    var profile: String { text("프로필", "Profile") }
    var profileDisplayName: String { text("이름", "Name") }
    var profileAvatar: String { text("프로필 사진", "Profile Picture") }
    var profileCharacter: String { text("프로필 캐릭터", "Profile Character") }
    var profileColor: String { text("프로필 색상", "Profile Color") }
    var chooseProfilePhoto: String { text("사진 선택", "Choose Photo") }
    var removeProfilePhoto: String { text("사진 제거", "Remove Photo") }
    var profilePhotoScale: String { text("사진 크기", "Photo Size") }
    var useProfilePhoto: String { text("사진 사용", "Use Photo") }
    var profileBio: String { text("소개말", "Bio") }
    var profileSaved: String { text("프로필을 저장했습니다.", "Profile saved.") }
    var deleteAccount: String { text("앱 탈퇴", "Delete Account") }
    var deleteAccountNotice: String {
        text(
            "탈퇴하면 프로필, 로그인 정보, 공개 질문과 관련 기록이 즉시 삭제됩니다.",
            "Deleting your account immediately removes your profile, sign-in data, public questions, and related records."
        )
    }
    var deleteAccountConfirmMessage: String {
        text(
            "탈퇴 후에는 이 계정의 프로필과 관련 기록을 복구할 수 없습니다.",
            "After deletion, this account's profile and related records cannot be recovered."
        )
    }
    var accountDeleted: String { text("탈퇴 처리되었습니다.", "Account deleted.") }
    var pageAccess: String { text("페이지 접근 허용", "Page Access") }
    var publicQuestionsPage: String { text("공개 질문", "Public Questions") }
    var publicQuestionsPageHelp: String {
        text(
            "켜두면 개별 기록에서 공개로 설정한 채점 완료 질문만 다른 사용자에게 표시됩니다.",
            "When enabled, only graded questions that you individually mark public are shown to other users."
        )
    }
    var statisticsPage: String { text("통계", "Statistics") }
    var studyDetailPage: String { text("내 학습 내부", "My Study Detail") }
    var recordsPage: String { text("기록", "Records") }
    var accessUnavailable: String { text("허용 불가", "Not allowed") }
    var accessAllowed: String { text("허용됨", "Allowed") }
    var pageAccessRequiresLogin: String {
        text(
            "이 페이지는 로그인 후 사용할 수 있습니다.",
            "Sign in to use this page."
        )
    }
    func pageAccessDenied(_ page: String) -> String {
        text(
            "\(page)은 로그인 후 사용할 수 있습니다.",
            "\(page) is available after sign-in."
        )
    }
    var report: String { text("신고", "Report") }
    var reportSubmitted: String { text("신고를 접수했습니다.", "Report submitted.") }
    var reportReasonInappropriate: String { text("부적절한 질문", "Inappropriate question") }
    var googleLoginSetupRequired: String {
        text(
            "Google Login은 OAuth 클라이언트 설정 후 활성화됩니다.",
            "Google Login becomes available after OAuth client configuration."
        )
    }
    var topicRangeHelpTitle: String { text("Range 계산 방식", "How Range Works") }
    var topicRangeHelpBody: String {
        text(
            "각 답변의 레벨과 점수를 능력 추정치로 바꾼 뒤, 표본 수와 답변 간 차이를 함께 반영해 범위를 계산합니다. 서로 먼 레벨에서 엇갈린 점수가 있으면 범위가 넓어지고, 같은 점수대의 질문을 더 많이 답하면 범위가 더 정확하게 좁아집니다.",
            "BuddyStuddy converts each answer's level and score into an ability estimate, then combines sample count and disagreement between answers. Mixed results across distant levels make the range wider. Answer more questions around that range to narrow it."
        )
    }
    var topicTrend: String { text("주제 레벨 추세", "Topic Level Trend") }
    var topicSummary: String { text("주제 통합 현황", "Topic Summary") }
    var topicCount: String { text("주제", "Topics") }
    var level: String { text("레벨", "Level") }
    var range: String { text("범위", "Range") }
    var sortTopics: String { text("정렬", "Sort") }
    var sortByLevel: String { text("레벨순", "Level") }
    var sortByRecent: String { text("최근순", "Recent") }
    var sortByName: String { text("이름순", "Name") }
    var sortByCount: String { text("응답순", "Count") }
    var noMatchingTopics: String { text("일치하는 주제 없음", "No Matching Topics") }
    var noMatchingTopicsDescription: String {
        text("검색어를 줄이거나 기간을 넓혀보세요.", "Try a broader search or a wider period.")
    }
    var previousPage: String { text("이전 페이지", "Previous Page") }
    var nextPage: String { text("다음 페이지", "Next Page") }
    func topicPageStatus(start: Int, end: Int, total: Int) -> String {
        text("\(start)-\(end)/\(total)", "\(start)-\(end)/\(total)")
    }
    var firstRecord: String { text("처음", "First") }
    var latestRecord: String { text("최근", "Latest") }
    var startDate: String { text("시작", "Start") }
    var endDate: String { text("끝", "End") }
    var allPeriods: String { text("전체", "All") }
    var today: String { text("오늘", "Today") }
    var last7Days: String { text("최근 7일", "Last 7 Days") }
    var last30Days: String { text("최근 30일", "Last 30 Days") }
    var last90Days: String { text("최근 90일", "Last 90 Days") }
    var customPeriod: String { text("직접 설정", "Custom") }
    var scoreByQuestion: String { text("문제별 기록", "Question Records") }
    var scoreDistribution: String { text("점수 분포", "Score Distribution") }
    var excellentScores: String { text("90-100", "90-100") }
    var goodScores: String { text("70-89", "70-89") }
    var partialScores: String { text("40-69", "40-69") }
    var lowScores: String { text("0-39", "0-39") }
    var problem: String { text("문제", "Question") }
    var hint: String { text("힌트", "Hint") }
    var feedback: String { text("피드백", "Feedback") }
    var feedbackLink: String { text("피드백 보내기", "Send feedback") }
    var explanation: String { text("해설", "Explanation") }
    var statsByTopic: String { text("주제별 통계", "Stats by Topic") }
    func currentTopicLevel(_ level: String) -> String {
        text("레벨: \(level)", "Level: \(level)")
    }
    func topicLevelRange(_ start: String, _ end: String, average: Int, count: Int) -> String {
        text(
            "범위: \(start)-\(end) · \(count)개",
            "Range: \(start)-\(end) · \(count)"
        )
    }
    func groupedTopics(_ topics: String) -> String { text("묶인 주제: \(topics)", "Grouped topics: \(topics)") }
    var notEnoughStats: String { text("통계를 만들려면 채점 기록이 더 필요합니다.", "Grade more answers to build insights.") }
    func itemCount(_ count: Int) -> String { text("\(count)개", "\(count)") }
    var correctRate: String { text("정답", "Correct") }
}
