import Foundation

enum MarkdownContent {
    private static let parsingOptions = AttributedString.MarkdownParsingOptions(
        interpretedSyntax: .full,
        failurePolicy: .returnPartiallyParsedIfPossible
    )

    static func attributedString(_ source: String) -> AttributedString {
        var value = (try? AttributedString(markdown: source, options: parsingOptions))
            ?? AttributedString(source)
        let blockedLinkRanges: [Range<AttributedString.Index>] = value.runs.compactMap { run in
            guard let link = run.link,
                  !allowedLinkSchemes.contains(link.scheme?.lowercased() ?? "") else {
                return nil
            }
            return run.range
        }

        for range in blockedLinkRanges {
            value[range].link = nil
        }
        return value
    }

    static func plainText(_ source: String) -> String {
        String(attributedString(source).characters)
            .replacingOccurrences(of: "\u{FFFC}", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static let allowedLinkSchemes = Set(["http", "https"])
}

struct StudyTreeNodeOffset: Codable, Equatable {
    var x: CGFloat
    var y: CGFloat
}

struct StudyTreeViewportState: Codable, Equatable {
    var zoomScale: CGFloat
    var contentOffsetX: CGFloat
    var contentOffsetY: CGFloat
    var canvasAlignmentX: CGFloat?
    var canvasAlignmentY: CGFloat?

    init(
        zoomScale: CGFloat,
        contentOffsetX: CGFloat,
        contentOffsetY: CGFloat,
        canvasAlignmentX: CGFloat? = nil,
        canvasAlignmentY: CGFloat? = nil
    ) {
        self.zoomScale = zoomScale
        self.contentOffsetX = contentOffsetX
        self.contentOffsetY = contentOffsetY
        self.canvasAlignmentX = canvasAlignmentX
        self.canvasAlignmentY = canvasAlignmentY
    }

    static let `default` = StudyTreeViewportState(
        zoomScale: 1,
        contentOffsetX: 0,
        contentOffsetY: 0
    )
}

struct StudyTreeCanvasLayout: Equatable {
    var size: CGSize
    var translation: CGSize
}

struct StudyTreeCanvasTranslationCompensation: Equatable {
    var viewportOffset: CGPoint
    var alignmentInset: CGSize
}

struct StudyTreeDirectionalEdgeGeometry: Equatable {
    var start: CGPoint
    var end: CGPoint
    var arrowLeft: CGPoint
    var arrowRight: CGPoint
}

enum StudyTreeNodeStylePolicy {
    static func levelFillFraction(_ difficultyLevel: Int) -> CGFloat {
        CGFloat(min(max(difficultyLevel, 1), 10)) / 10
    }

    static func levelText(_ difficultyLevel: Int) -> String {
        "\(min(max(difficultyLevel, 1), 10))/10"
    }
}

enum StudyOutlinePolicy {
    static let childPreviewLimit = 5

    static func visibleCount(totalTopicCount: Int) -> Int {
        min(max(totalTopicCount, 0), childPreviewLimit)
    }

    static func remainingCount(totalTopicCount: Int) -> Int {
        max(0, totalTopicCount - childPreviewLimit)
    }

    static func ancestorPath(
        rootID: Int,
        targetID: Int,
        parentByID: [Int: Int]
    ) -> [Int] {
        guard targetID != rootID else {
            return [rootID]
        }

        var reversedPath = [targetID]
        var visited = Set<Int>([targetID])
        var currentID = targetID

        while let parentID = parentByID[currentID],
              visited.insert(parentID).inserted {
            reversedPath.append(parentID)
            if parentID == rootID {
                return reversedPath.reversed()
            }
            currentID = parentID
        }

        return [rootID]
    }
}

enum StudyTreeEdgePolicy {
    static func directionalGeometry(
        parent: CGPoint,
        child: CGPoint,
        nodeRadius: CGFloat,
        arrowLength: CGFloat = 10,
        arrowHalfWidth: CGFloat = 5
    ) -> StudyTreeDirectionalEdgeGeometry? {
        let deltaX = child.x - parent.x
        let deltaY = child.y - parent.y
        let distance = hypot(deltaX, deltaY)
        guard distance.isFinite, distance > 0.5 else {
            return nil
        }

        let unitX = deltaX / distance
        let unitY = deltaY / distance
        let safeArrowLength = max(0, arrowLength)
        let safeArrowHalfWidth = max(0, arrowHalfWidth)
        let effectiveRadius = min(
            max(0, nodeRadius),
            max(0, (distance - safeArrowLength) / 2)
        )
        let start = CGPoint(
            x: parent.x + unitX * effectiveRadius,
            y: parent.y + unitY * effectiveRadius
        )
        let end = CGPoint(
            x: child.x - unitX * effectiveRadius,
            y: child.y - unitY * effectiveRadius
        )
        let arrowBase = CGPoint(
            x: end.x - unitX * safeArrowLength,
            y: end.y - unitY * safeArrowLength
        )
        let perpendicularX = -unitY * safeArrowHalfWidth
        let perpendicularY = unitX * safeArrowHalfWidth

        return StudyTreeDirectionalEdgeGeometry(
            start: start,
            end: end,
            arrowLeft: CGPoint(
                x: arrowBase.x + perpendicularX,
                y: arrowBase.y + perpendicularY
            ),
            arrowRight: CGPoint(
                x: arrowBase.x - perpendicularX,
                y: arrowBase.y - perpendicularY
            )
        )
    }
}

enum StudyTreeDeletionPolicy {
    static func subtreeIDs(
        rootIDs: Set<Int>,
        parentByRoomID: [Int: Int]
    ) -> Set<Int> {
        guard !rootIDs.isEmpty else {
            return []
        }
        let childrenByParent = Dictionary(grouping: parentByRoomID, by: \.value)
            .mapValues { entries in entries.map(\.key) }
        var result = rootIDs
        var pending = Array(rootIDs)
        while let parentID = pending.popLast() {
            for childID in childrenByParent[parentID, default: []]
            where result.insert(childID).inserted {
                pending.append(childID)
            }
        }
        return result
    }

    static func childFirstDeletionOrder(
        studyIDs: Set<Int>,
        parentByRoomID: [Int: Int]
    ) -> [Int] {
        var depths: [Int: Int] = [:]

        func depth(for roomID: Int, visiting: Set<Int>) -> Int {
            if let cached = depths[roomID] {
                return cached
            }
            guard let parentID = parentByRoomID[roomID],
                  studyIDs.contains(parentID),
                  !visiting.contains(parentID) else {
                depths[roomID] = 0
                return 0
            }
            let resolvedDepth = depth(
                for: parentID,
                visiting: visiting.union([roomID])
            ) + 1
            depths[roomID] = resolvedDepth
            return resolvedDepth
        }

        return studyIDs.sorted { lhs, rhs in
            let lhsDepth = depth(for: lhs, visiting: [])
            let rhsDepth = depth(for: rhs, visiting: [])
            if lhsDepth == rhsDepth {
                return lhs < rhs
            }
            return lhsDepth > rhsDepth
        }
    }
}

enum StudyTreeCanvasPolicy {
    static func sanitizedOffset(_ offset: CGSize) -> CGSize {
        CGSize(
            width: offset.width.isFinite ? offset.width : 0,
            height: offset.height.isFinite ? offset.height : 0
        )
    }

    static func offsetsPlacingNewNodesWithoutSameLevelOverlap(
        newRoomIDs: Set<Int>,
        baseCenters: [Int: CGPoint],
        nodeOffsets: [Int: CGSize],
        nodeSize: CGSize,
        spacing: CGFloat = 16
    ) -> [Int: CGSize] {
        var resolvedOffsets = nodeOffsets.mapValues(sanitizedOffset)
        let minimumHorizontalDistance = max(0, nodeSize.width + spacing)
        guard minimumHorizontalDistance > 0, !newRoomIDs.isEmpty else {
            return resolvedOffsets
        }

        let orderedNewRoomIDs = newRoomIDs.compactMap { roomID -> (Int, CGPoint)? in
            guard let center = baseCenters[roomID],
                  center.x.isFinite,
                  center.y.isFinite else {
                return nil
            }
            return (roomID, center)
        }
        .sorted { lhs, rhs in
            if lhs.1.y == rhs.1.y {
                if lhs.1.x == rhs.1.x {
                    return lhs.0 < rhs.0
                }
                return lhs.1.x < rhs.1.x
            }
            return lhs.1.y < rhs.1.y
        }

        var placedNewRoomIDs = Set<Int>()
        for (roomID, baseCenter) in orderedNewRoomIDs {
            let initialOffset = resolvedOffsets[roomID] ?? .zero
            var candidateX = baseCenter.x + initialOffset.width
            var attempts = 0

            while attempts < baseCenters.count {
                let occupiedCenters = baseCenters.compactMap { otherRoomID, otherBaseCenter -> CGFloat? in
                    guard otherRoomID != roomID,
                          abs(otherBaseCenter.y - baseCenter.y) < 0.5,
                          !newRoomIDs.contains(otherRoomID) || placedNewRoomIDs.contains(otherRoomID) else {
                        return nil
                    }
                    let otherOffset = resolvedOffsets[otherRoomID] ?? .zero
                    return otherBaseCenter.x + otherOffset.width
                }
                let collisions = occupiedCenters.filter {
                    abs(candidateX - $0) < minimumHorizontalDistance
                }
                guard !collisions.isEmpty else {
                    break
                }
                candidateX = collisions
                    .map { $0 + minimumHorizontalDistance }
                    .max() ?? candidateX
                attempts += 1
            }

            resolvedOffsets[roomID] = sanitizedOffset(
                CGSize(
                    width: candidateX - baseCenter.x,
                    height: initialOffset.height
                )
            )
            placedNewRoomIDs.insert(roomID)
        }
        return resolvedOffsets
    }

    static func expandedLayout(
        baseCenters: [Int: CGPoint],
        nodeOffsets: [Int: CGSize],
        baseCanvasSize: CGSize,
        nodeSize: CGSize,
        padding: CGFloat = 8
    ) -> StudyTreeCanvasLayout {
        let halfWidth = max(0, nodeSize.width / 2 + padding)
        let halfHeight = max(0, nodeSize.height / 2 + padding)
        var minimumX: CGFloat = 0
        var minimumY: CGFloat = 0
        var maximumX = max(0, baseCanvasSize.width)
        var maximumY = max(0, baseCanvasSize.height)

        for (roomID, baseCenter) in baseCenters {
            let offset = sanitizedOffset(nodeOffsets[roomID] ?? .zero)
            let centerX = baseCenter.x + offset.width
            let centerY = baseCenter.y + offset.height
            minimumX = min(minimumX, centerX - halfWidth)
            minimumY = min(minimumY, centerY - halfHeight)
            maximumX = max(maximumX, centerX + halfWidth)
            maximumY = max(maximumY, centerY + halfHeight)
        }

        return StudyTreeCanvasLayout(
            size: CGSize(
                width: maximumX - minimumX,
                height: maximumY - minimumY
            ),
            translation: CGSize(
                width: -minimumX,
                height: -minimumY
            )
        )
    }
}

enum StudyTreeViewportPolicy {
    static let minimumZoomScale: CGFloat = 0.02
    static let maximumZoomScale: CGFloat = 1.8

    static func normalizedContentOffset(
        rawContentOffset: CGPoint,
        leadingInset: CGSize
    ) -> CGPoint {
        CGPoint(
            x: max(0, rawContentOffset.x + max(0, leadingInset.width)),
            y: max(0, rawContentOffset.y + max(0, leadingInset.height))
        )
    }

    static func rawContentOffset(
        normalizedContentOffset: CGPoint,
        leadingInset: CGSize
    ) -> CGPoint {
        CGPoint(
            x: max(0, normalizedContentOffset.x) - max(0, leadingInset.width),
            y: max(0, normalizedContentOffset.y) - max(0, leadingInset.height)
        )
    }

    static func maximumNormalizedContentOffset(
        contentSize: CGSize,
        viewportSize: CGSize,
        totalInset: CGSize
    ) -> CGPoint {
        CGPoint(
            x: max(0, contentSize.width - viewportSize.width + totalInset.width),
            y: max(0, contentSize.height - viewportSize.height + totalInset.height)
        )
    }

    static func shouldApplyInitialFit(
        isRequested: Bool,
        hasApplied: Bool,
        hasUserInteracted: Bool,
        hasFinishedRefresh: Bool,
        viewportSize: CGSize
    ) -> Bool {
        isRequested
            && !hasApplied
            && !hasUserInteracted
            && hasFinishedRefresh
            && viewportSize.width > 0
            && viewportSize.height > 0
    }

    static func fittedZoomScale(
        canvasSize: CGSize,
        viewportSize: CGSize,
        padding: CGFloat = 28
    ) -> CGFloat {
        guard canvasSize.width.isFinite,
              canvasSize.height.isFinite,
              viewportSize.width.isFinite,
              viewportSize.height.isFinite,
              canvasSize.width > 0,
              canvasSize.height > 0,
              viewportSize.width > 0,
              viewportSize.height > 0 else {
            return 1
        }

        let availableWidth = max(1, viewportSize.width - padding * 2)
        let availableHeight = max(1, viewportSize.height - padding * 2)
        let fittedScale = min(
            availableWidth / canvasSize.width,
            availableHeight / canvasSize.height,
            1
        )
        return min(max(fittedScale, minimumZoomScale), maximumZoomScale)
    }

    static func centeredCanvasAlignmentInset(
        canvasSize: CGSize,
        viewportSize: CGSize,
        zoomScale: CGFloat
    ) -> CGSize {
        let safeScale = max(
            zoomScale.isFinite ? zoomScale : 1,
            minimumZoomScale
        )
        guard canvasSize.width.isFinite,
              canvasSize.height.isFinite,
              viewportSize.width.isFinite,
              viewportSize.height.isFinite else {
            return .zero
        }
        return CGSize(
            width: max(0, (viewportSize.width - canvasSize.width * safeScale) / 2),
            height: max(0, (viewportSize.height - canvasSize.height * safeScale) / 2)
        )
    }

    static func compensationPreservingCanvasTranslation(
        startOffset: CGPoint,
        startAlignmentInset: CGSize,
        startCanvasTranslation: CGSize,
        targetCanvasTranslation: CGSize,
        zoomScale: CGFloat
    ) -> StudyTreeCanvasTranslationCompensation {
        let safeScale = max(
            zoomScale.isFinite ? zoomScale : 1,
            minimumZoomScale
        )
        let rawHorizontalOffset = startOffset.x
            + (targetCanvasTranslation.width - startCanvasTranslation.width)
                * safeScale
        let rawVerticalOffset = startOffset.y
            + (targetCanvasTranslation.height - startCanvasTranslation.height)
                * safeScale
        return StudyTreeCanvasTranslationCompensation(
            viewportOffset: CGPoint(
                x: max(0, rawHorizontalOffset),
                y: max(0, rawVerticalOffset)
            ),
            alignmentInset: CGSize(
                width: rawHorizontalOffset >= 0
                    ? max(0, startAlignmentInset.width)
                    : max(0, startAlignmentInset.width - rawHorizontalOffset),
                height: rawVerticalOffset >= 0
                    ? max(0, startAlignmentInset.height)
                    : max(0, startAlignmentInset.height - rawVerticalOffset)
            )
        )
    }

    static func contentOffsetPreservingAnchor(
        startOffset: CGPoint,
        anchor: CGPoint,
        canvasSize: CGSize,
        viewportSize: CGSize,
        startAlignmentInset: CGSize,
        targetAlignmentInset: CGSize,
        startScale: CGFloat,
        targetScale: CGFloat
    ) -> CGPoint {
        let safeStartScale = max(
            startScale.isFinite ? startScale : 1,
            minimumZoomScale
        )
        let safeTargetScale = max(
            targetScale.isFinite ? targetScale : safeStartScale,
            minimumZoomScale
        )
        let safeStartOffset = CGPoint(
            x: startOffset.x.isFinite ? max(0, startOffset.x) : 0,
            y: startOffset.y.isFinite ? max(0, startOffset.y) : 0
        )
        let canvasPoint = CGPoint(
            x: (
                safeStartOffset.x
                    + anchor.x
                    - max(0, startAlignmentInset.width)
            ) / safeStartScale,
            y: (
                safeStartOffset.y
                    + anchor.y
                    - max(0, startAlignmentInset.height)
            ) / safeStartScale
        )
        let targetOffset = CGPoint(
            x: max(0, targetAlignmentInset.width)
                + canvasPoint.x * safeTargetScale
                - anchor.x,
            y: max(0, targetAlignmentInset.height)
                + canvasPoint.y * safeTargetScale
                - anchor.y
        )
        let maximumTargetOffset = CGPoint(
            x: max(
                0,
                canvasSize.width * safeTargetScale
                    + max(0, targetAlignmentInset.width)
                    - viewportSize.width
            ),
            y: max(
                0,
                canvasSize.height * safeTargetScale
                    + max(0, targetAlignmentInset.height)
                    - viewportSize.height
            )
        )
        return CGPoint(
            x: min(max(0, targetOffset.x), maximumTargetOffset.x),
            y: min(max(0, targetOffset.y), maximumTargetOffset.y)
        )
    }
}

private extension Collection {
    subscript(safe index: Index) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

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
    case japanese

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
        case .japanese:
            "日本語"
        }
    }

    var promptLabel: String {
        switch self {
        case .korean:
            "Korean"
        case .english:
            "English"
        case .japanese:
            "Japanese"
        }
    }
}

enum AppLanguage: String, CaseIterable, Codable, Identifiable {
    case korean
    case english
    case japanese

    var id: String { rawValue }

    var locale: Locale {
        switch self {
        case .korean:
            Locale(identifier: "ko_KR")
        case .english:
            Locale(identifier: "en_US")
        case .japanese:
            Locale(identifier: "ja_JP")
        }
    }

    var displayName: String {
        switch self {
        case .korean:
            "한국어"
        case .english:
            "English"
        case .japanese:
            "日本語"
        }
    }

    static func preferred(from languageIdentifiers: [String]) -> AppLanguage {
        for identifier in languageIdentifiers {
            let languageCode = identifier
                .replacingOccurrences(of: "_", with: "-")
                .split(separator: "-", maxSplits: 1)
                .first?
                .lowercased()

            switch languageCode {
            case "ko":
                return .korean
            case "en":
                return .english
            case "ja":
                return .japanese
            default:
                continue
            }
        }

        return .english
    }

    static var systemPreferred: AppLanguage {
        preferred(from: Locale.preferredLanguages)
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
        case (.defaultSound, .japanese):
            "デフォルト"
        case (.softPing, .korean):
            "부드러운 핑"
        case (.softPing, .english):
            "Soft Ping"
        case (.softPing, .japanese):
            "ソフトピング"
        case (.chime, .korean):
            "차임"
        case (.chime, .english):
            "Chime"
        case (.chime, .japanese):
            "チャイム"
        case (.pop, .korean):
            "팝"
        case (.pop, .english):
            "Pop"
        case (.pop, .japanese):
            "ポップ"
        case (.bell, .korean):
            "벨"
        case (.bell, .english):
            "Bell"
        case (.bell, .japanese):
            "ベル"
        case (.tap, .korean):
            "탭"
        case (.tap, .english):
            "Tap"
        case (.tap, .japanese):
            "タップ"
        case (.none, .korean):
            "없음"
        case (.none, .english):
            "None"
        case (.none, .japanese):
            "なし"
        }
    }
}

enum AppTab: Int, Hashable {
    case home
    case study
    case settings
    case records
    case statistics
    case notifications
}

struct HomeStudyRoute: Identifiable, Hashable {
    let id = UUID()
    var categoryID: String?
    var showsTree = false
}

struct HomeAnnouncement: Identifiable, Equatable {
    var notificationID: String?
    var title: String
    var message: String

    var id: String {
        notificationID ?? "\(title)|\(message)"
    }

    init(notificationID: String?, title: String, message: String) {
        self.notificationID = notificationID
        self.title = title
        self.message = message
    }

    init?(notification: BackendAppNotification) {
        guard notification.type
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .uppercased() == "ADMIN_MESSAGE",
              Self.isMessageDeepLink(notification.deepLink) else {
            return nil
        }
        self.init(
            notificationID: notification.id,
            title: notification.title,
            message: notification.body
        )
    }

    static func isMessageDeepLink(_ value: String?) -> Bool {
        guard let value,
              let url = URL(string: value),
              url.scheme?.lowercased() == "buddystudy" else {
            return false
        }
        let components = [url.host, url.path]
            .compactMap { $0 }
            .flatMap { $0.split(separator: "/") }
            .map { $0.lowercased() }
        return components == ["home", "message"]
    }
}

enum AppRoute: Equatable, Hashable {
    case home
    case studyList
    case studyRoom(categoryID: String?)
    case records
    case recordDetail(recordID: String)
    case statistics
    case settings
    case settingsOpenAI
    case profile
    case publicQuestions
    case publicQuestion(id: String)

    init?(url: URL) {
        guard url.scheme?.lowercased() == "buddystudy" else {
            return nil
        }

        let queryParams = URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?
            .reduce(into: [String: String]()) { result, item in
                result[item.name] = item.value
            } ?? [:]
        func queryValue(_ keys: String...) -> String? {
            keys.lazy.compactMap { queryParams[$0] }.first
        }

        let components = [url.host, url.path]
            .compactMap { $0 }
            .flatMap { $0.split(separator: "/") }
            .map(String.init)

        guard !components.isEmpty else {
            self = .home
            return
        }

        let normalized = components.map { $0.lowercased() }
        if normalized == ["home"] || normalized == ["home", "message"] {
            self = .home
        } else if normalized == ["test-push"] {
            self = .home
        } else if normalized == ["study"] || normalized == ["studies"],
                  let categoryID = queryValue("categoryId", "studyId", "id") {
            self = .studyRoom(categoryID: categoryID)
        } else if normalized == ["study"] || normalized == ["studies"] {
            self = .studyList
        } else if normalized.count == 2,
                  normalized[0] == "study" || normalized[0] == "studies" {
            self = .studyRoom(categoryID: components[safe: 1])
        } else if normalized == ["records"] || normalized == ["history"] {
            if let recordID = queryValue("recordId", "recordID", "id") {
                self = .recordDetail(recordID: recordID)
            } else {
                self = .records
            }
        } else if normalized.count == 2,
                  normalized[0] == "record" || normalized[0] == "records" || normalized[0] == "history",
                  let recordID = components[safe: 1] {
            self = .recordDetail(recordID: recordID)
        } else if normalized == ["stats"] || normalized == ["statistics"] {
            self = .statistics
        } else if normalized == ["settings"] {
            self = .settings
        } else if normalized == ["settings", "openai"] || normalized == ["settings", "api-key"] {
            self = .settingsOpenAI
        } else if normalized == ["profile"] {
            self = .profile
        } else if normalized == ["public"] || normalized == ["public", "questions"] {
            if let questionID = queryValue("questionId", "questionID", "id") {
                self = .publicQuestion(id: questionID)
            } else {
                self = .publicQuestions
            }
        } else if normalized.count == 3,
                  normalized[0] == "public",
                  normalized[1] == "question" || normalized[1] == "questions",
                  let questionID = components[safe: 2] {
            self = .publicQuestion(id: questionID)
        } else {
            return nil
        }
    }

    init?(route: String, params: [String: String] = [:]) {
        let normalized = route.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        switch normalized {
        case "home":
            self = .home
        case "study", "study.list", "studies":
            self = .studyList
        case "study.room", "study.detail":
            self = .studyRoom(categoryID: params["categoryId"] ?? params["studyId"])
        case "records", "record.list", "history":
            self = .records
        case "record.detail", "records.detail", "history.detail":
            guard let recordID = params["recordId"] ?? params["recordID"] ?? params["id"] else {
                return nil
            }
            self = .recordDetail(recordID: recordID)
        case "stats", "statistics":
            self = .statistics
        case "settings":
            self = .settings
        case "settings.openai", "settings.api-key":
            self = .settingsOpenAI
        case "profile":
            self = .profile
        case "public.questions", "community.questions":
            self = .publicQuestions
        case "public.question", "community.question":
            guard let questionID = params["questionId"] ?? params["questionID"] ?? params["id"] else {
                return nil
            }
            self = .publicQuestion(id: questionID)
        default:
            return nil
        }
    }
}

enum AppRoutePresentation: Equatable {
    case direct
    case notificationInbox
}

struct AppRouteRequest: Identifiable, Equatable {
    let id = UUID()
    var route: AppRoute
    var presentation: AppRoutePresentation = .direct
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
        case .japanese:
            .japanese
        }
    }
}

struct StudySettings: Codable, Equatable {
    static let defaultOpenAIModel = "gpt-5.4"
    static let fallbackTopic = "내 학습"
    static let fallbackTopicEnglish = "My Study"
    static let fallbackTopicJapanese = "マイ学習"
    static let defaultCustomPrompt = "짧고 명확하게 질문하세요. 사용자가 답하기 좋은 한 문제만 내세요."
    private static let fallbackCategoryCreatedAt = Date(timeIntervalSince1970: 0)
    private static let fallbackTopicIDByLanguage: [AppLanguage: String] = [
        .korean: "builtin-study-category-default-ko",
        .english: "builtin-study-category-default-en",
        .japanese: "builtin-study-category-default-ja"
    ]

    static func fallbackTopic(for appLanguage: AppLanguage) -> String {
        switch appLanguage {
        case .korean:
            fallbackTopic
        case .english:
            fallbackTopicEnglish
        case .japanese:
            fallbackTopicJapanese
        }
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
        isQuestionPublic: Bool = true,
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
        isQuestionPublic = try container.decodeIfPresent(Bool.self, forKey: .isQuestionPublic) ?? true

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

    static func initial(for appLanguage: AppLanguage) -> StudySettings {
        StudySettings(
            topic: fallbackTopic(for: appLanguage),
            difficulty: .beginner,
            appLanguage: appLanguage,
            language: appLanguage.studyLanguage,
            customPrompt: defaultCustomPrompt,
            intervalMinutes: 15
        )
    }

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
        case .japanese:
            switch self {
            case .concept:
                return "概念チェック"
            case .interview:
                return "面接形式"
            case .practical:
                return "実践例"
            case .scale:
                return "スケール設計"
            case .enterprise:
                return "大規模運用"
            case .review:
                return "復習重視"
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
        case .japanese:
            switch self {
            case .concept:
                return "中心となる概念を正しく理解しているか確認する短い質問をしてください。一度に一つの概念だけを扱ってください。"
            case .interview:
                return "技術面接のように質問してください。定義だけでなく、理由、トレードオフ、実際の利用場面を説明させてください。"
            case .practical:
                return "実務の状況や小さな例をもとに質問し、概念を適用して答えられるようにしてください。"
            case .scale:
                return "スケールイン・アウトの設計観点から、トラフィック増加、ボトルネック、分割、キャッシュ、キュー、障害分離、コストを説明させてください。"
            case .enterprise:
                return "大規模な本番運用の観点から、信頼性、デプロイとロールバック、監視、セキュリティ、権限、整合性、障害対応、チーム連携を考慮させてください。"
            case .review:
                return "以前の質問と重ならない復習問題を出し、よくある間違いや混同しやすい違いを確認してください。"
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

    enum CodingKeys: String, CodingKey {
        case score
        case isCorrect
        case correct
        case feedback
        case explanation
    }

    init(score: Int, isCorrect: Bool, feedback: String, explanation: String) {
        self.score = score
        self.isCorrect = isCorrect
        self.feedback = feedback
        self.explanation = explanation
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        score = try container.decode(Int.self, forKey: .score)
        isCorrect = try container.decodeIfPresent(Bool.self, forKey: .isCorrect)
            ?? container.decode(Bool.self, forKey: .correct)
        feedback = try container.decode(String.self, forKey: .feedback)
        explanation = try container.decode(String.self, forKey: .explanation)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(score, forKey: .score)
        try container.encode(isCorrect, forKey: .isCorrect)
        try container.encode(feedback, forKey: .feedback)
        try container.encode(explanation, forKey: .explanation)
    }
}

enum AnswerGradingStatus: String, Codable, Equatable {
    case queued = "QUEUED"
    case analyzingEvidence = "ANALYZING_EVIDENCE"
    case critiquing = "CRITIQUING"
    case judging = "JUDGING"
    case adjudicating = "ADJUDICATING"
    case completed = "COMPLETED"
    case failed = "FAILED"

    var isTerminal: Bool {
        self == .completed || self == .failed
    }
}

struct AnswerGradingProgressEvent: Codable, Equatable, Identifiable {
    var id: Int64
    var recordID: String
    var correlationID: String
    var status: AnswerGradingStatus
    var questionStatus: QuestionStatus?
    var errorMessage: String?
    var occurredAt: Date

    enum CodingKeys: String, CodingKey {
        case id
        case recordID = "recordId"
        case correlationID = "correlationId"
        case status
        case questionStatus
        case errorMessage
        case occurredAt
    }
}

struct AnswerGradingProcess: Codable, Equatable {
    var correlationID: String
    var recordID: String
    var status: AnswerGradingStatus
    var questionStatus: QuestionStatus?
    var terminal: Bool
    var pollAfterMilliseconds: Int?
    var events: [AnswerGradingProgressEvent]
    var errorMessage: String?
    var updatedAt: Date

    enum CodingKeys: String, CodingKey {
        case correlationID = "correlationId"
        case recordID = "recordId"
        case status
        case questionStatus
        case terminal
        case pollAfterMilliseconds = "pollAfterMs"
        case events
        case errorMessage
        case updatedAt
    }
}

enum LocalizedContentView: String, Codable {
    case localized
    case original
}

struct ContentLocalizationMetadata: Codable, Equatable {
    var sourceLanguage: String
    var requestedLanguage: String
    var displayLanguage: String
    var translationState: String
    var isTranslated: Bool
    var originalAvailable: Bool
    var translationReason: String

    var isPending: Bool {
        translationState == "PENDING"
    }

    private enum CodingKeys: String, CodingKey {
        case sourceLanguage
        case requestedLanguage
        case displayLanguage
        case translationState
        case isTranslated
        case originalAvailable
        case translationReason
    }

    private enum LegacyCodingKeys: String, CodingKey {
        case translated
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let legacyContainer = try decoder.container(keyedBy: LegacyCodingKeys.self)
        sourceLanguage = try container.decode(String.self, forKey: .sourceLanguage)
        requestedLanguage = try container.decode(String.self, forKey: .requestedLanguage)
        displayLanguage = try container.decode(String.self, forKey: .displayLanguage)
        translationState = try container.decode(String.self, forKey: .translationState)
        isTranslated = try container.decodeIfPresent(Bool.self, forKey: .isTranslated)
            ?? legacyContainer.decodeIfPresent(Bool.self, forKey: .translated)
            ?? false
        originalAvailable = try container.decode(Bool.self, forKey: .originalAvailable)
        translationReason = try container.decode(String.self, forKey: .translationReason)
    }
}

struct RecordLocalizationMetadata: Codable, Equatable {
    var question: ContentLocalizationMetadata
    var answer: ContentLocalizationMetadata?
    var aiResponse: ContentLocalizationMetadata?

    var containsTranslation: Bool {
        [question, answer, aiResponse].compactMap { $0 }.contains { $0.isTranslated }
    }

    var containsPendingTranslation: Bool {
        [question, answer, aiResponse].compactMap { $0 }.contains { $0.isPending }
    }
}

struct StudyRecord: Codable, Equatable, Identifiable {
    var id: String
    var studyID: Int?
    var question: QuestionItem
    var answer: String?
    var gradingResult: GradingResult?
    var topic: String
    var difficulty: Difficulty
    var answeredAt: Date?
    var isPublic: Bool
    var likeCount: Int
    var commentCount: Int
    var viewCount: Int
    var gradingRequestID: String?
    var correlationID: String?
    var gradingStatus: AnswerGradingStatus?
    var gradingError: String?
    var questionStatus: QuestionStatus
    var gradingLastEventID: Int64?
    var localization: RecordLocalizationMetadata?

    enum CodingKeys: String, CodingKey {
        case id
        case studyID = "studyId"
        case question
        case answer
        case gradingResult
        case topic
        case difficulty
        case answeredAt
        case isPublic
        case likeCount
        case commentCount
        case viewCount
        case gradingRequestID = "gradingRequestId"
        case correlationID = "correlationId"
        case gradingStatus
        case gradingError
        case questionStatus
        case gradingLastEventID = "gradingLastEventId"
        case localization
    }

    private enum BackendBooleanCodingKeys: String, CodingKey {
        case publicValue = "public"
    }

    init(
        id: String = UUID().uuidString,
        studyID: Int? = nil,
        question: QuestionItem,
        answer: String? = nil,
        gradingResult: GradingResult? = nil,
        topic: String,
        difficulty: Difficulty,
        answeredAt: Date? = nil,
        isPublic: Bool = true,
        likeCount: Int = 0,
        commentCount: Int = 0,
        viewCount: Int = 0,
        gradingRequestID: String? = nil,
        correlationID: String? = nil,
        gradingStatus: AnswerGradingStatus? = nil,
        gradingError: String? = nil,
        questionStatus: QuestionStatus? = nil,
        gradingLastEventID: Int64? = nil,
        localization: RecordLocalizationMetadata? = nil
    ) {
        self.id = id
        self.studyID = studyID
        self.question = question
        self.answer = answer
        self.gradingResult = gradingResult
        self.topic = topic
        self.difficulty = difficulty
        self.answeredAt = answeredAt
        self.isPublic = isPublic
        self.likeCount = likeCount
        self.commentCount = commentCount
        self.viewCount = viewCount
        self.gradingRequestID = gradingRequestID ?? correlationID
        self.correlationID = correlationID ?? gradingRequestID
        self.gradingStatus = gradingStatus
        self.gradingError = gradingError
        self.questionStatus = questionStatus
            ?? (gradingResult != nil
                ? .graded
                : (self.gradingRequestID?.isEmpty == false || gradingStatus != nil ? .grading : .ungraded))
        self.gradingLastEventID = gradingLastEventID
        self.localization = localization
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let backendBooleanContainer = try decoder.container(keyedBy: BackendBooleanCodingKeys.self)
        id = try container.decodeIfPresent(String.self, forKey: .id) ?? UUID().uuidString
        studyID = try container.decodeIfPresent(Int.self, forKey: .studyID)
        question = try container.decode(QuestionItem.self, forKey: .question)
        answer = try container.decodeIfPresent(String.self, forKey: .answer)
        gradingResult = try container.decodeIfPresent(GradingResult.self, forKey: .gradingResult)
        topic = try container.decodeIfPresent(String.self, forKey: .topic) ?? ""
        difficulty = try container.decodeIfPresent(Difficulty.self, forKey: .difficulty) ?? Difficulty(level: 5)
        answeredAt = try container.decodeIfPresent(Date.self, forKey: .answeredAt)
        isPublic = try container.decodeIfPresent(Bool.self, forKey: .isPublic)
            ?? backendBooleanContainer.decodeIfPresent(Bool.self, forKey: .publicValue)
            ?? true
        likeCount = try container.decodeIfPresent(Int.self, forKey: .likeCount) ?? 0
        commentCount = try container.decodeIfPresent(Int.self, forKey: .commentCount) ?? 0
        viewCount = try container.decodeIfPresent(Int.self, forKey: .viewCount) ?? 0
        let decodedGradingRequestID = try container.decodeIfPresent(String.self, forKey: .gradingRequestID)
        let decodedCorrelationID = try container.decodeIfPresent(String.self, forKey: .correlationID)
        gradingRequestID = decodedGradingRequestID ?? decodedCorrelationID
        correlationID = decodedCorrelationID ?? decodedGradingRequestID
        gradingStatus = try container.decodeIfPresent(AnswerGradingStatus.self, forKey: .gradingStatus)
        gradingError = try container.decodeIfPresent(String.self, forKey: .gradingError)
        questionStatus = try container.decodeIfPresent(QuestionStatus.self, forKey: .questionStatus)
            ?? (gradingResult != nil
                ? .graded
                : (gradingRequestID?.isEmpty == false || gradingStatus != nil ? .grading : .ungraded))
        gradingLastEventID = try container.decodeIfPresent(Int64.self, forKey: .gradingLastEventID)
        localization = try container.decodeIfPresent(RecordLocalizationMetadata.self, forKey: .localization)
    }

    func asCommunityQuestion(author: CommunityUserProfile?) -> CommunityQuestion? {
        guard isPublic, gradingResult != nil else {
            return nil
        }

        return asQuestionBrowseQuestion(author: author)
    }

    func asQuestionBrowseQuestion(author: CommunityUserProfile?) -> CommunityQuestion {
        CommunityQuestion(
            id: id,
            question: question.question,
            answer: answer,
            gradingResult: gradingResult,
            topic: topic,
            difficultyLevel: difficulty.level,
            status: "graded",
            source: "record",
            createdAt: question.createdAt,
            answeredAt: answeredAt,
            author: author,
            likeCount: likeCount,
            commentCount: commentCount,
            viewCount: viewCount,
            isLikedByMe: false,
            localization: localization
        )
    }
}

enum QuestionStatus: String, Codable, Equatable {
    case ungraded = "UNGRADED"
    case grading = "GRADING"
    case graded = "GRADED"
    case failed = "FAILED"
    case skipped = "SKIPPED"

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let value = try container.decode(String.self).uppercased()
        guard let status = Self(rawValue: value) else {
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Unsupported question status: \(value)"
            )
        }
        self = status
    }
}

enum StudyDateDisplayFormatter {
    private static let absoluteThreshold: TimeInterval = 7 * 24 * 60 * 60

    static func relativeOrShortDateString(
        for date: Date,
        relativeTo referenceDate: Date = Date(),
        language: AppLanguage? = nil
    ) -> String {
        let locale = language?.locale ?? .autoupdatingCurrent

        if abs(referenceDate.timeIntervalSince(date)) >= absoluteThreshold {
            let formatter = DateFormatter()
            formatter.calendar = Calendar(identifier: .gregorian)
            formatter.locale = locale
            formatter.timeZone = .autoupdatingCurrent
            formatter.dateStyle = .short
            formatter.timeStyle = .none
            return formatter.string(from: date)
        }

        let relativeFormatter = RelativeDateTimeFormatter()
        relativeFormatter.locale = locale
        relativeFormatter.unitsStyle = .short
        return relativeFormatter.localizedString(for: date, relativeTo: referenceDate)
    }
}

enum StudyAnswerPresentationPolicy {
    enum State: Equatable {
        case awaitingAnswer
        case submitting
        case grading(AnswerGradingStatus)
        case completed
        case failed

        var allowsEditing: Bool {
            self == .awaitingAnswer
        }

        var isInProgress: Bool {
            switch self {
            case .submitting, .grading:
                true
            case .awaitingAnswer, .completed, .failed:
                false
            }
        }
    }

    static func submittedAnswer(for record: StudyRecord?) -> String? {
        guard let answer = record?.answer,
              !answer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        return answer
    }

    static func state(for record: StudyRecord?, isSubmitting: Bool = false) -> State {
        guard let record else {
            return .awaitingAnswer
        }
        if record.questionStatus == .graded ||
            record.gradingResult != nil ||
            record.gradingStatus == .completed {
            return .completed
        }
        if record.gradingStatus == .failed {
            return .failed
        }
        if record.questionStatus == .grading,
           record.gradingStatus == nil {
            return .grading(.queued)
        }
        if let gradingStatus = record.gradingStatus, !gradingStatus.isTerminal {
            return .grading(gradingStatus)
        }
        if isSubmitting {
            return .submitting
        }
        if submittedAnswer(for: record) != nil ||
            !(record.gradingRequestID ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return .grading(.queued)
        }
        return .awaitingAnswer
    }

    static func shouldShowEditor(for record: StudyRecord?) -> Bool {
        guard record != nil else {
            return false
        }
        return state(for: record).allowsEditing
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
        StudyRecordIdentityPolicy.normalizedQuestionText(question)
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

struct CloudSyncState: Codable, Equatable {
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

enum BackendAuthorizationNotification {
    static let didReceiveUnauthorized = Notification.Name("studyBackendDidReceiveUnauthorized")
}

enum BackendServiceStatus: String, Codable, Equatable {
    case operational = "OPERATIONAL"
    case maintenance = "MAINTENANCE"
}

struct BackendServiceAvailability: Codable, Equatable {
    var status: BackendServiceStatus
    var maintenanceID: Int?
    var title: String?
    var message: String?
    var startsAt: Date?
    var endsAt: Date?
    var retryAfterSeconds: Int?
    var checkedAt: Date?

    private enum CodingKeys: String, CodingKey {
        case status
        case maintenanceID = "maintenanceId"
        case title
        case message
        case startsAt
        case endsAt
        case retryAfterSeconds
        case checkedAt
    }

    init(
        status: BackendServiceStatus,
        maintenanceID: Int?,
        title: String?,
        message: String?,
        startsAt: Date?,
        endsAt: Date?,
        retryAfterSeconds: Int?,
        checkedAt: Date?
    ) {
        self.status = status
        self.maintenanceID = maintenanceID
        self.title = title
        self.message = message
        self.startsAt = startsAt
        self.endsAt = endsAt
        self.retryAfterSeconds = retryAfterSeconds
        self.checkedAt = checkedAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        status = try container.decode(BackendServiceStatus.self, forKey: .status)
        maintenanceID = try? container.decode(Int.self, forKey: .maintenanceID)
        title = try? container.decode(String.self, forKey: .title)
        message = try? container.decode(String.self, forKey: .message)
        startsAt = try? container.decode(Date.self, forKey: .startsAt)
        endsAt = try? container.decode(Date.self, forKey: .endsAt)
        retryAfterSeconds = try? container.decode(Int.self, forKey: .retryAfterSeconds)
        checkedAt = try? container.decode(Date.self, forKey: .checkedAt)
    }

    var isUnderMaintenance: Bool {
        status == .maintenance
    }

    static var operational: BackendServiceAvailability {
        BackendServiceAvailability(
            status: .operational,
            maintenanceID: nil,
            title: nil,
            message: nil,
            startsAt: nil,
            endsAt: nil,
            retryAfterSeconds: nil,
            checkedAt: Date()
        )
    }
}

enum BackendAppUpdateMode: String, Codable, Equatable {
    case force = "FORCE"
    case optional = "OPTIONAL"
}

enum BackendAppUpdateEvent: String, Codable {
    case shown = "SHOWN"
    case dismissed = "DISMISSED"
    case appStoreOpened = "APP_STORE_OPENED"
}

enum BackendAppControlEventType: String, Codable {
    case versionObserved = "VERSION_OBSERVED"
    case policyEvaluated = "POLICY_EVALUATED"
    case promptShown = "PROMPT_SHOWN"
    case dismissed = "DISMISSED"
    case storeOpened = "STORE_OPENED"
    case updated = "UPDATED"
    case maintenanceShown = "MAINTENANCE_SHOWN"
    case maintenanceBypassed = "MAINTENANCE_BYPASSED"
}

struct BackendAppControlEventRequest: Codable {
    var eventID: String
    var event: BackendAppControlEventType
    var platform: String
    var channel: AppControlDistributionChannel
    var currentVersion: String
    var currentBuild: String
    var policyID: String?
    var policyRevision: Int64?
    var campaignID: Int64?
    var evaluatedAction: String?
    var occurredAt: Date

    private enum CodingKeys: String, CodingKey {
        case eventID = "eventId"
        case event
        case platform
        case channel
        case currentVersion
        case currentBuild
        case policyID = "policyId"
        case policyRevision
        case campaignID = "campaignId"
        case evaluatedAction
        case occurredAt
    }
}

struct BackendAppUpdateDecision: Codable, Equatable, Identifiable {
    var updateAvailable: Bool
    var shouldPresent: Bool
    var campaignID: Int64?
    var mode: BackendAppUpdateMode?
    var targetVersion: String?
    var targetBuild: String?
    var title: String?
    var message: String?
    var appStoreURL: String?

    var id: Int64 { campaignID ?? -1 }
    var isForced: Bool { mode == .force }

    private enum CodingKeys: String, CodingKey {
        case updateAvailable
        case shouldPresent
        case campaignID = "campaignId"
        case mode
        case targetVersion
        case targetBuild
        case title
        case message
        case appStoreURL = "appStoreUrl"
    }
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
        case .japanese:
            return "レベル \(level)/10"
        }
    }
}

enum AppLegalLinks {
    static func termsOfServiceURL(language: AppLanguage) -> URL {
        switch language {
        case .korean:
            return URL(string: "https://ghkdqhrbals.github.io/buddy-studdy/terms-2026-07-30.html")!
        case .english:
            return URL(string: "https://ghkdqhrbals.github.io/buddy-studdy/en/terms-2026-07-30.html")!
        case .japanese:
            return URL(string: "https://ghkdqhrbals.github.io/buddy-studdy/ja/terms-2026-07-30.html")!
        }
    }

    static func privacyPolicyURL(language: AppLanguage) -> URL {
        switch language {
        case .korean:
            return URL(string: "https://ghkdqhrbals.github.io/buddy-studdy/privacy-2026-07-30.html")!
        case .english:
            return URL(string: "https://ghkdqhrbals.github.io/buddy-studdy/en/privacy-2026-07-30.html")!
        case .japanese:
            return URL(string: "https://ghkdqhrbals.github.io/buddy-studdy/ja/privacy-2026-07-30.html")!
        }
    }

    static func infoNotificationURL(language: AppLanguage) -> URL {
        switch language {
        case .korean:
            return URL(string: "https://ghkdqhrbals.github.io/buddy-studdy/terms-2026-07-30.html#notifications")!
        case .english:
            return URL(string: "https://ghkdqhrbals.github.io/buddy-studdy/en/terms-2026-07-30.html#notifications")!
        case .japanese:
            return URL(string: "https://ghkdqhrbals.github.io/buddy-studdy/ja/terms-2026-07-30.html#notifications")!
        }
    }

    static func marketingNotificationURL(language: AppLanguage) -> URL {
        switch language {
        case .korean:
            return URL(string: "https://ghkdqhrbals.github.io/buddy-studdy/marketing-consent-2026-07-30.html")!
        case .english:
            return URL(string: "https://ghkdqhrbals.github.io/buddy-studdy/en/marketing-consent-2026-07-30.html")!
        case .japanese:
            return URL(string: "https://ghkdqhrbals.github.io/buddy-studdy/ja/marketing-consent-2026-07-30.html")!
        }
    }

    static func nightMarketingNotificationURL(language: AppLanguage) -> URL {
        switch language {
        case .korean:
            return URL(string: "https://ghkdqhrbals.github.io/buddy-studdy/marketing-consent-2026-07-30.html#channel")!
        case .english:
            return URL(string: "https://ghkdqhrbals.github.io/buddy-studdy/en/marketing-consent-2026-07-30.html#channel")!
        case .japanese:
            return URL(string: "https://ghkdqhrbals.github.io/buddy-studdy/ja/marketing-consent-2026-07-30.html#channel")!
        }
    }
}

struct AppStrings {
    var language: AppLanguage

    private func text(_ korean: String, _ english: String, _ japanese: String? = nil) -> String {
        switch language {
        case .korean:
            korean
        case .english:
            english
        case .japanese:
            japanese ?? JapaneseAppStrings.translation(for: english)
        }
    }

    var showOriginal: String { text("원문 보기", "Show original", "原文を見る") }
    var updateNow: String { text("지금 업데이트", "Update now", "今すぐアップデート") }
    var updateLater: String { text("나중에", "Later", "あとで") }
    var updateRequired: String { text("필수 업데이트", "Update required", "アップデートが必要です") }
    var showTranslation: String { text("번역 보기", "View translation", "翻訳を見る") }
    var translatedIntoLanguage: String {
        text(
            "한국어로 번역됨",
            "Translated into English",
            "日本語に翻訳済み"
        )
    }

    var gradingQueued: String { text("답변을 접수했습니다. 채점을 준비하고 있습니다.", "Answer received. Preparing to grade.", "回答を受け付けました。採点を準備しています。") }
    var gradingAnalyzing: String { text("답변의 근거를 분석하고 있습니다.", "Analyzing answer evidence.") }
    var gradingCritiquing: String { text("답변의 오류와 누락을 검토하고 있습니다.", "Reviewing errors and omissions.") }
    var gradingJudging: String { text("채점 기준에 따라 판정하고 있습니다.", "Judging against the rubric.") }
    var gradingAdjudicating: String { text("판정 결과를 다시 검증하고 있습니다.", "Verifying the grading decision.") }
    var gradingCompleted: String { text("채점이 완료됐습니다.", "Grading completed.") }
    var gradingFailed: String { text("채점을 완료하지 못했습니다. 다시 시도해 주세요.", "Grading could not be completed. Please try again.") }
    var answerAlreadySubmitted: String {
        text(
            "이미 제출한 답변입니다. 현재 채점 상태를 불러옵니다.",
            "This answer was already submitted. Loading its grading status.",
            "この回答はすでに送信されています。採点状況を読み込みます。"
        )
    }
    var questionGenerationCompleted: String {
        text("새 질문이 준비됐습니다.", "Your new question is ready.")
    }
    var questionGenerationCompletedWhileDrafting: String {
        text(
            "새 질문이 준비됐지만 작성 중인 답변은 유지했습니다.",
            "Your new question is ready. Your current draft was kept."
        )
    }

    var tabStudy: String { text("학습", "Study") }
    var tabHome: String { text("홈", "Home") }
    var tabSettings: String { text("설정", "Settings") }
    var tabRecords: String { text("기록", "Records") }
    var tabStatistics: String { text("통계", "Stats") }
    var myStudyLoginBenefit: String {
        text(
            "로그인하면 학습 주제와 질문 설정을 기기 간에 이어서 관리할 수 있습니다.",
            "Sign in to keep study topics and question settings synced across devices."
        )
    }
    var myStudyGuestPreviewTitle: String { text("내 방식대로 만드는 학습 공간", "A study space built your way") }
    var myStudyGuestPreviewSubtitle: String {
        text(
            "주제마다 난이도와 질문 방식을 정하고, 필요한 공부를 한곳에서 이어가세요.",
            "Set the difficulty and question style for each topic, then keep everything in one place."
        )
    }
    var myStudyGuestTopicOne: String { text("아키텍처 연습 · 레벨 6", "Architecture practice · Level 6") }
    var myStudyGuestTopicTwo: String { text("메시징 시스템 · 레벨 7", "Messaging systems · Level 7") }
    var myStudyGuestTopicThree: String { text("서비스 경계 · 레벨 7", "Service boundaries · Level 7") }
    var myStudyGuestLoginTitle: String { text("첫 학습 주제를 만들어보세요", "Create your first study topic") }
    var myStudyGuestLoginSubtitle: String {
        text(
            "로그인하면 주제 설정과 진행 상황이 계정에 안전하게 저장됩니다.",
            "Sign in to safely keep topic settings and progress with your account."
        )
    }
    var recordsLoginBenefit: String {
        text(
            "학습할수록 나만의 기록이 차곡차곡 쌓입니다.",
            "Your personal record grows with every study session."
        )
    }
    var statisticsLoginBenefit: String {
        text(
            "주제별 변화와 학습 흐름을 한눈에 확인할 수 있습니다.",
            "See topic progress and learning patterns at a glance."
        )
    }
    var myStudyLoginAction: String { text("내 학습 시작하기", "Start My Studies") }
    var recordsLoginAction: String { text("기록 이어가기", "Continue My Records") }
    var statisticsLoginAction: String { text("성장 확인하기", "See My Progress") }
    var recordsGuestPreviewTitle: String { text("꾸준히 쌓이는 학습 기록", "A learning record that grows with you") }
    var recordsGuestPreviewSubtitle: String {
        text(
            "공부할수록 지난 질문과 답변을 더 자세히 돌아볼 수 있어요.",
            "The more you study, the more of your questions and answers you can revisit."
        )
    }
    var statisticsGuestPreviewTitle: String { text("성장을 한눈에", "See your growth at a glance") }
    var statisticsGuestPreviewSubtitle: String {
        text(
            "정답률과 난이도 변화를 주제별로 차분하게 보여드려요.",
            "Follow accuracy and difficulty changes for each topic."
        )
    }
    var guestWeeklySummary: String { text("이번 주 요약", "This week") }
    var guestStudyDays: String { text("학습일", "Study days") }
    var guestActivities: String { text("총 활동", "Activities") }
    var guestAverageScore: String { text("평균 점수", "Average") }
    var guestTopicCount: String { text("학습 주제", "Topics") }
    var learningActivity: String { text("학습 활동", "Learning activity") }
    var recentLearningRecords: String { text("최근 학습 기록", "Recent learning records") }
    var topicGrowthOverview: String { text("주제별 성장", "Growth by topic") }
    var learningRhythmSettings: String { text("학습 리듬", "Learning rhythm") }
    var appEnvironmentSettings: String { text("앱 환경", "App preferences") }
    var dataSyncSettings: String { text("데이터 동기화", "Data sync") }
    var enabledStatus: String { text("켜짐", "On") }
    var disabledStatus: String { text("꺼짐", "Off") }
    var guestRecentRecords: String { text("최근 기록 미리보기", "Recent record preview") }
    var guestTopicProgress: String { text("주제별 성장 미리보기", "Topic progress preview") }
    var guestPreviewRecordTopicOne: String { text("시스템 설계", "System Design") }
    var guestPreviewRecordTopicTwo: String { "Kafka" }
    var guestPreviewRecordTopicThree: String { "Microservice Architecture" }
    var guestPreviewRecordQuestionOne: String {
        text(
            "확장 가능한 시스템의 병목을 찾아보세요.",
            "Identify a bottleneck in a scalable system."
        )
    }
    var guestPreviewRecordQuestionTwo: String {
        text(
            "Kafka 파티션과 컨슈머 그룹의 관계를 설명해보세요.",
            "Explain how Kafka partitions relate to consumer groups."
        )
    }
    var guestPreviewRecordQuestionThree: String {
        text(
            "서비스 경계와 데이터 소유권을 어떻게 나눌지 설명해보세요.",
            "Explain how you would divide service boundaries and data ownership."
        )
    }
    var guestPreviewProgressOne: String { text("안정적으로 향상 중", "Improving steadily") }
    var guestPreviewProgressTwo: String { text("조금 더 연습하면 좋아요", "A little more practice") }
    var guestPreviewProgressThree: String { text("꾸준히 유지 중", "Holding steady") }
    var recordsGuestLoginTitle: String { text("계정으로 기록을 안전하게 이어가세요", "Keep your records safe with an account") }
    var recordsGuestLoginSubtitle: String {
        text(
            "여러 기기에서도 이어서 학습하고 모든 기록을 보관할 수 있어요.",
            "Continue on any device and keep your full learning history."
        )
    }
    var statisticsGuestLoginTitle: String { text("나만의 학습 흐름을 이어가세요", "Keep your learning progress with you") }
    var statisticsGuestLoginSubtitle: String {
        text(
            "로그인하면 주제별 변화와 학습 통계를 안전하게 보관해요.",
            "Sign in to safely keep topic progress and learning statistics."
        )
    }
    var loading: String { text("불러오는 중", "Loading") }
    var retry: String { text("다시 시도", "Retry") }
    var serviceTemporarilyUnavailable: String {
        text(
            "서비스가 잠시 불안정합니다. 잠시 후 다시 시도하세요.",
            "The service is temporarily unavailable. Please try again shortly."
        )
    }
    var invalidServerResponse: String {
        text(
            "서버 응답을 확인할 수 없습니다. 잠시 후 다시 시도하세요.",
            "The server response could not be read. Please try again shortly."
        )
    }
    var responseDataUnreadable: String {
        text(
            "응답 데이터를 읽을 수 없습니다. 잠시 후 다시 시도하세요.",
            "The response data could not be read. Please try again shortly."
        )
    }
    var networkUnavailableRetry: String {
        text(
            "인터넷 연결을 확인한 뒤 다시 시도하세요.",
            "Check your internet connection and try again."
        )
    }
    var requestTimedOutRetry: String {
        text(
            "응답이 지연되고 있습니다. 잠시 후 다시 시도하세요.",
            "The response is taking longer than expected. Please try again shortly."
        )
    }
    var monthlyQuestionQuota: String { text("월간 질문", "Monthly questions") }
    var monthlyQuotaReached: String { text("이번 달 질문 한도에 도달했습니다.", "You have reached this month's question limit.") }
    func monthlyQuotaUsage(remaining: Int, limit: Int) -> String {
        text("\(limit)개 중 \(remaining)개 남음", "\(remaining) of \(limit) remaining", "残り\(remaining)件／\(limit)件")
    }
    func monthlyQuotaReset(_ resetAt: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = language.locale
        formatter.timeZone = .current
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return text(
            "\(formatter.string(from: resetAt))에 초기화",
            "Resets \(formatter.string(from: resetAt))",
            "\(formatter.string(from: resetAt))にリセット"
        )
    }
    var maintenanceDefaultTitle: String {
        text("서비스 점검 중입니다", "Service maintenance", "サービスメンテナンス中です")
    }
    var maintenanceDefaultMessage: String {
        text(
            "더 안정적인 서비스를 위해 점검을 진행하고 있습니다. 잠시 후 다시 확인해 주세요.",
            "BuddyStudy is undergoing maintenance for improved reliability. Please try again shortly.",
            "より安定したサービスのため、メンテナンスを実施しています。しばらくしてからもう一度お試しください。"
        )
    }
    var maintenancePlannedEnd: String {
        text("예상 종료", "Expected completion", "終了予定")
    }
    var maintenanceNoPlannedEnd: String {
        text(
            "점검이 완료되는 대로 자동으로 다시 연결합니다.",
            "The app will reconnect automatically when maintenance is complete.",
            "メンテナンスが完了すると自動的に再接続します。"
        )
    }
    var maintenanceRetry: String { text("다시 확인", "Check again", "もう一度確認") }
    var maintenanceChecking: String { text("확인 중", "Checking", "確認中") }
    var maintenanceDeveloperAccessTitle: String {
        text("개발자 접근", "Developer Access", "開発者アクセス")
    }
    var maintenanceDeveloperAccessHelp: String {
        text(
            "개발자 코드를 입력하면 현재 점검 화면을 닫고 앱에 접근할 수 있습니다.",
            "Enter the developer code to dismiss the current maintenance screen and access the app.",
            "開発者コードを入力すると、現在のメンテナンス画面を閉じてアプリにアクセスできます。"
        )
    }
    var studyTree: String { text("학습 트리", "Study Tree") }
    var activateTopics: String { text("주제 활성화", "Activate topics") }
    var deleteTopics: String { text("주제 삭제", "Delete topics") }
    var resetTreeLayout: String { text("트리 배치 초기화", "Reset tree layout") }
    var deleteSelectedTopics: String { text("선택한 주제를 삭제할까요?", "Delete selected topics?") }
    func deleteStudySubtree(_ topic: String) -> String {
        text(
            "\"\(topic)\" 및 모든 하위 주제를 삭제할까요?",
            "Delete \"\(topic)\" and all of its subtopics?",
            "「\(topic)」とそのすべてのサブトピックを削除しますか？"
        )
    }
    func selectedTopicCount(_ count: Int) -> String {
        text("\(count)개 선택", "\(count) selected", "\(count)件選択中")
    }
    func moreStudyTopics(_ count: Int) -> String {
        text("+ \(count)개 주제 더 보기", "+ \(count) more topics", "他\(count)件のトピック")
    }
    var viewFullStudyTree: String { text("전체 트리 보기", "View full tree") }
    var moveToParentTopic: String { text("상위로", "Up one level") }
    var childTopics: String { text("하위 주제", "Child topics") }
    var studyAction: String { text("학습", "Study") }
    var openStudyPage: String { text("학습 열기", "Open study") }
    var collapseStudyTopics: String { text("주제 목록 접기", "Collapse topics") }
    var expandStudyTopics: String { text("주제 목록 펼치기", "Expand topics") }
    func childTopicCount(_ count: Int) -> String {
        text("하위 주제 \(count)개", "\(count) child topics", "サブトピック\(count)件")
    }
    func childTopicAction(_ count: Int) -> String {
        text("하위 \(count)", "\(count) children", "下位\(count)件")
    }
    var addSubstudy: String { text("하위 주제 추가", "Add subtopic") }
    var recommendSubstudy: String { text("추천 주제", "Suggested topics") }
    var recommendSubstudyTab: String { text("추천", "Suggestions") }
    var recommendSubstudyDescription: String {
        text(
            "시스템 주제 목록을 먼저 사용하고, 필요한 경우 새 추천을 만들어 보완합니다.",
            "Uses the system topic catalog first and generates missing suggestions when needed."
        )
    }
    var refreshRecommendations: String { text("다시 추천", "Refresh suggestions") }
    var selectAll: String { text("전체 선택", "Select all") }
    var deselectAll: String { text("선택 해제", "Clear") }
    var addTopicManually: String { text("직접 추가", "Add manually") }
    var recommendedTopicsEmpty: String {
        text("새 추천을 만들지 못했습니다. 다시 시도하거나 직접 추가해 주세요.", "No new suggestions were available. Retry or add one manually.")
    }
    var studyTopicDepthLimit: String {
        text(
            "시스템 주제 트리는 5단계까지 제공합니다. 현재 주제에서 학습을 진행하거나 상위 단계에 주제를 추가해 주세요.",
            "The system topic tree supports five levels. Continue studying here or add a topic to an earlier level."
        )
    }
    var questionTopicToggle: String {
        text("이 주제에서 질문 받기", "Receive questions from this topic")
    }
    var questionTopicActive: String { text("질문 받기 켜짐", "Questions enabled") }
    var questionTopicInactive: String { text("질문 받기 꺼짐", "Questions disabled") }
    var questionRotationHelp: String {
        text(
            "켜 둔 주제에서는 예약 질문이 순서대로 도착합니다.",
            "Scheduled questions rotate through the topics you enable."
        )
    }
    var duplicateStudyTopic: String { text("이미 트리에 있는 주제입니다.", "This topic already exists in the tree.") }
    var addStudyTopicFailed: String { text("하위 주제를 추가하지 못했습니다.", "Could not add the subtopic.") }
    func addSelectedSubstudies(_ count: Int) -> String {
        text("선택한 \(count)개 추가", "Add \(count) selected", "選択した\(count)件を追加")
    }
    func sharedDifficultyDescription(_ count: Int) -> String {
        text(
            "선택한 \(count)개 주제에 같은 숫자가 적용됩니다.",
            "The same number applies to all \(count) selected topics.",
            "選択した\(count)件のトピックすべてに同じ数値が適用されます。"
        )
    }
    func partialSubstudyAddFailure(added: Int, failed: Int) -> String {
        text(
            "\(added)개를 추가했고 \(failed)개는 추가하지 못했습니다. 선택된 주제를 다시 시도해 주세요.",
            "Added \(added). \(failed) could not be added; retry the selected topics.",
            "\(added)件を追加しました。\(failed)件は追加できませんでした。選択したトピックを再試行してください。"
        )
    }
    var deleteStudy: String { text("학습 삭제", "Delete Study") }
    var openQuestions: String { text("질문 열기", "Open Questions") }
    func monthlyQuotaExceededMessage(resetAt: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = language.locale
        formatter.timeZone = .current
        formatter.dateStyle = .long
        formatter.timeStyle = .short
        let resetAtText = formatter.string(from: resetAt)
        return text(
            "\(monthlyQuotaReached) \(resetAtText)에 다시 사용할 수 있습니다.",
            "\(monthlyQuotaReached) You can create questions again on \(resetAtText).",
            "\(monthlyQuotaReached) \(resetAtText)から再び質問を作成できます。"
        )
    }
    func homePath(_ category: String) -> String {
        category
    }
    var onboardingTitle: String { text("BuddyStudy 시작하기", "Set Up BuddyStudy") }
    var onboardingSubtitle: String {
        text(
            "AI를 더 잘 쓰려면 스스로의 지식도 필요합니다. BuddyStudy는 짧은 질문으로 그 지식을 계속 유지하게 돕습니다.",
            "Better AI output still depends on what you know. BuddyStudy keeps that knowledge active with short questions."
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
            return text("BuddyStudy 실행 중", "BuddyStudy is running")
        }
        return text("BuddyStudy 정지됨", "BuddyStudy is stopped")
    }

    var invalidAPIKey: String { text("API 키가 잘못되었습니다", "Invalid API key") }
    var newQuestionNotificationTitle: String { text("새 질문 도착", "New Question") }
    var commentNotificationTitle: String { text("댓글", "Comment") }
    func notificationTitle(type: String, threadType: String?, fallback: String) -> String {
        let normalizedType = type.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let normalizedThreadType = threadType?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        let normalizedFallback = fallback.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()

        if normalizedType == "STUDY_QUESTION" || normalizedThreadType == "study_question" {
            return newQuestionNotificationTitle
        }
        if normalizedFallback.contains("댓글") || normalizedFallback.contains("comment") {
            return commentNotificationTitle
        }
        return fallback
    }
    var cloudQuestionPushBody: String {
        text("새 학습 질문이 도착했습니다. 탭해서 이어가세요.", "A new study question is ready. Tap to continue.")
    }
    var reply: String { text("답장", "Reply") }
    var send: String { text("보내기", "Send") }
    var answerPlaceholder: String { text("답변작성", "Write answer") }
    var otherAnswer: String { text("다른 응답", "Other Answer") }
    var ignore: String { text("무시", "Ignore") }
    var openStudy: String { text("학습 열기...", "Open Study...") }
    var aboutStudyMate: String { text("BuddyStudy 정보", "About BuddyStudy") }
    func timerTitle(minutes: Int) -> String { text("타이머: \(minutes)분", "Timer: \(minutes) min", "タイマー：\(minutes)分") }
    func minuteLabel(_ minutes: Int) -> String { text("\(minutes)분", "\(minutes) min", "\(minutes)分") }
    var languageMenu: String { text("언어", "Language") }
    var pause: String { text("일시정지", "Pause") }
    var resume: String { text("재개", "Resume") }
    var quit: String { text("BuddyStudy 종료", "Quit BuddyStudy") }

    var general: String { text("일반", "General", "一般") }
    var secrets: String { text("비밀 키", "Secrets", "シークレット") }
    var study: String { text("학습", "Study", "学習") }
    var records: String { text("기록", "Records", "履歴") }
    var developer: String { text("개발자", "Developer", "開発者") }

    var checking: String { text("확인 중", "Checking") }
    var save: String { text("저장", "Save") }
    var saving: String { text("저장 중", "Saving") }
    var cancel: String { text("취소", "Cancel") }
    var close: String { text("닫기", "Close") }
    var errorPopupTitle: String { text("알림", "Notice") }
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
        text("iCloud 동기화 실패: \(reason)", "iCloud sync failed: \(reason)", "iCloudの同期に失敗しました：\(reason)")
    }
    func lastSyncedAt(_ date: Date) -> String {
        text(
            "마지막 동기화: \(date.formatted(date: .abbreviated, time: .shortened))",
            "Last synced: \(date.formatted(date: .abbreviated, time: .shortened))",
            "最終同期：\(date.formatted(date: .abbreviated, time: .shortened))"
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
        text("시스템 설정에서 BuddyStudy 알림과 사운드 허용 여부를 직접 확인하세요.", "Check BuddyStudy notification and sound permissions directly in system settings.")
    }
    var openNotificationSettings: String { text("시스템 알림 설정 열기", "Open Notification Settings") }
    var testNotification: String { text("테스트 알림", "Test Notification") }
    var testNotificationBody: String {
        text(
            "알림이 보이면 BuddyStudy 알림 권한은 정상입니다.",
            "If you see this, BuddyStudy notification permission is working."
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
    var notificationInbox: String { text("알림", "Notifications") }
    var noNotifications: String { text("아직 알림이 없습니다", "No notifications yet") }
    var unableToLoadNotifications: String { text("알림을 불러오지 못했습니다", "Unable to load notifications") }
    var notificationLoadRetryDescription: String {
        text(
            "잠시 후 다시 시도하거나 화면을 아래로 당겨 새로고침해 주세요.",
            "Try again shortly or pull down to refresh."
        )
    }
    var noNotificationsDescription: String {
        text("댓글, 좋아요 같은 활동이 생기면 여기에 표시됩니다.", "Thread activity such as comments and likes will appear here.")
    }
    var deleteAllNotifications: String { text("전체삭제", "Clear All") }
    var markAllNotificationsRead: String { text("모두 읽음", "Mark All as Read") }
    var deleteNotification: String { text("삭제", "Delete") }
    var unreadNotification: String { text("읽지 않음", "Unread") }
    var updates: String { text("업데이트", "Updates") }
    var automaticallyCheckForUpdates: String { text("자동으로 업데이트 확인", "Automatically check for updates") }
    var automaticallyDownloadUpdates: String { text("가능하면 자동으로 다운로드", "Automatically download updates when available") }
    var checkForUpdates: String { text("업데이트 확인...", "Check for Updates...") }
    var search: String { text("검색", "Search") }
    var edit: String { text("편집", "Edit") }
    var updateHelp: String {
        text("GitHub Releases에 새 DMG가 올라오면 BuddyStudy가 업데이트를 안내합니다.", "BuddyStudy checks GitHub Releases and offers updates when a new DMG is available.")
    }
    var updateInstallHelp: String {
        text(
            "DMG 안이나 임시 위치에서 실행 중이면 업데이트할 수 없습니다. BuddyStudy.app을 Applications 폴더로 옮긴 뒤 다시 실행하세요.",
            "Updates are unavailable when BuddyStudy is running from a DMG or temporary location. Move BuddyStudy.app to Applications and relaunch it."
        )
    }
    var uninstall: String { text("BuddyStudy 제거", "Uninstall BuddyStudy") }
    var uninstallHelp: String {
        text("앱을 휴지통으로 이동하고 로컬 설정과 캐시를 삭제합니다.", "Move the app to Trash and delete local settings and caches.")
    }
    var uninstallConfirmationTitle: String { text("BuddyStudy를 제거할까요?", "Uninstall BuddyStudy?") }
    var uninstallConfirmationMessage: String {
        text("앱, 로컬 설정, 캐시가 삭제되고 BuddyStudy가 종료됩니다.", "The app, local settings, and caches will be deleted, then BuddyStudy will quit.")
    }
    func uninstallFailed(_ reason: String) -> String {
        text("앱 제거 실패: \(reason)", "Uninstall failed: \(reason)", "アンインストールに失敗しました：\(reason)")
    }
    var studySettings: String { text("학습 설정", "Study Settings") }
    var studyCategories: String { text("내 학습", "My Studies") }
    var homeScopeMy: String { text("내 학습", "My Studies") }
    var homeScopeAll: String { text("모든 학습들", "All Studies") }
    var myStudyLoginHelp: String {
        text(
            "로그인하면 내 학습을 관리할 수 있습니다.",
            "Sign in to manage your studies."
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
    var answerScore: String { text("답변 점수", "Answer score", "回答スコア") }
    func communityQuestionResult(score: Int, difficulty: Int) -> String {
        text(
            "\(score)점 · 난이도 \(difficulty)",
            "\(score) pts · Difficulty \(difficulty)",
            "\(score)点 · 難易度 \(difficulty)"
        )
    }
    var questionDifficulty: String { text("질문 난이도", "Question difficulty", "問題の難易度") }
    var difficultyScaleHint: String { text("1은 가장 쉬움, 10은 전문가 수준입니다.", "1 is easiest, 10 is expert-level.") }
    func questionInterval(minutes: Int) -> String { text("질문 간격: \(minutes)분", "Question interval: \(minutes) min", "質問間隔：\(minutes)分") }
    var recommendedPrompt: String { text("추천 프롬프트", "Recommended Prompt") }
    var relatedPrompt: String { text("관련 프롬프트", "Prompt") }

    var maxRecordCount: String { text("기록 최대 개수", "Max records") }
    var countUnit: String { text("개", "") }
    func recordLimitHelp(limit: Int, count: Int) -> String {
        text(
            "저장 시 \(limit)개 범위로 정리됩니다. 현재 저장된 기록: \(count)개",
            "Records are trimmed to \(limit) on save. Current records: \(count)",
            "保存時に\(limit)件まで整理されます。現在の履歴：\(count)件"
        )
    }
    var deleteRecords: String { text("기록 전체삭제", "Delete All Records") }
    var deleteRecordsHelp: String { text("저장된 질문, 답변, 채점 기록을 모두 삭제합니다.", "Delete all saved questions, answers, and grading results.") }
    var recordSettings: String { text("기록 설정", "Record Settings") }
    var debuggingMode: String { text("디버깅 모드", "Debugging Mode") }
    var paste: String { text("붙여넣기", "Paste") }
    var debuggingHelp: String { text("켜면 Developer 로그를 확인할 수 있습니다.", "When enabled, Developer logs are available.") }
    var debugBackendBaseURL: String { text("Debug API URL", "Debug API URL") }
    var debugBackendBaseURLPlaceholder: String { "https://lowfidev.cloud" }
    var debugBackendBaseURLInvalid: String { text("http 또는 https URL을 입력하세요.", "Enter an http or https URL.") }
    var debugBackendBaseURLHelp: String {
        text(
            "디버깅 모드가 켜져 있고 URL이 유효하면 모든 API 요청이 이 주소로 전송됩니다.",
            "When debugging is on and the URL is valid, all API requests are sent here."
        )
    }
    var cloudflareTunnel: String { text("Cloudflare 터널", "Cloudflare Tunnel") }
    var developerOptions: String { text("개발자 옵션", "Developer Options") }
    var promotionCode: String { text("프로모션 코드", "Promotion Code", "プロモーションコード") }
    var promotionCodeHelp: String {
        text(
            "발급받은 코드를 적용하면 이 기기에서 개발자 옵션과 디버깅 팝업을 사용할 수 있습니다.",
            "Apply an issued code to use developer options and debug popups on this device.",
            "発行されたコードを適用すると、このデバイスで開発者オプションとデバッグポップアップを使用できます。"
        )
    }
    var promotionCodePlaceholder: String {
        "XXXX-XXXX-XXXX-XXXX"
    }
    var applyPromotionCode: String { text("코드 적용", "Apply Code", "コードを適用") }
    var promotionCodeRequired: String {
        text("프로모션 코드를 입력해 주세요.", "Enter a promotion code.", "プロモーションコードを入力してください。")
    }
    var promotionCodeApplied: String {
        text("개발자 기능이 허용되었습니다.", "Developer features are now available.", "開発者機能が利用可能になりました。")
    }
    var promotionCodeInvalid: String {
        text("유효하지 않은 프로모션 코드입니다.", "This promotion code is invalid.", "このプロモーションコードは無効です。")
    }
    var apiDebugWindowTitle: String { text("API 통신 로그", "API Traffic Logs") }
    var resetDebugLogs: String { text("디버그 로그 초기화", "Reset Debug Logs") }
    var resetDebugLogsConfirmation: String {
        text(
            "저장된 앱 로그와 API 요청 및 응답 로그를 모두 삭제합니다.",
            "Delete all saved app logs and API request and response logs."
        )
    }
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
    func pendingQuestionCount(_ count: Int) -> String { text("\(count)개 대기 중", "\(count) pending", "\(count)件待機中") }
    var pendingQuestionLimitTitle: String { text("답변 대기 중인 질문이 있습니다.", "A question is waiting for your answer.") }
    var pendingQuestionLimitMessage: String {
        text(
            "현재 학습룸의 질문에 먼저 답변한 뒤 새 질문을 생성하세요.",
            "Answer the current study room question before creating a new one."
        )
    }
    var current: String { text("현재", "Current") }
    var openPendingQuestion: String { text("답변하기", "Answer") }
    var question: String { text("질문", "Question") }
    var fetchingQuestion: String { text("새 질문을 준비하고 있어요", "Preparing a new question") }
    var fetchingQuestionDescription: String {
        text(
            "학습 주제와 난이도에 맞춰 질문을 만들고 있습니다.",
            "Creating a question for this topic and difficulty."
        )
    }
    var noQuestion: String { text("질문 없음", "No Question") }
    var noQuestionDescription: String { text("설정을 저장한 뒤 새 질문을 생성하세요.", "Save settings, then create a new question.") }
    var duplicateQuestionSkipped: String {
        text(
            "기존 질문과 너무 비슷한 질문이 반복되어 생성하지 않았습니다.",
            "BuddyStudy did not save a repeated question."
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
    var skipQuestionFailed: String {
        text(
            "질문을 넘기지 못했습니다. 잠시 후 다시 시도하세요.",
            "The question could not be skipped. Please try again shortly."
        )
    }
    var showHint: String { text("힌트 보기", "Show Hint") }
    var hideHint: String { text("힌트 숨기기", "Hide Hint") }
    var correct: String { text("정답", "Correct") }
    var nearlyCorrect: String { text("정답에 가까움", "Nearly Correct") }
    var partialCorrect: String { text("부분 정답", "Partially Correct") }
    var needsImprovement: String { text("보완 필요", "Needs Work") }

    var clear: String { text("삭제", "Delete") }
    var more: String { text("더보기", "More") }
    var searchRecords: String { text("기록 검색", "Search records") }
    func filteredRecordCount(_ shown: Int, total: Int) -> String {
        text("\(shown)/\(total)개 표시", "\(shown)/\(total) shown", "\(shown)/\(total)件を表示")
    }
    var noSearchResults: String { text("검색 결과 없음", "No Results") }
    var noSearchResultsDescription: String { text("다른 검색어로 기록을 찾아보세요.", "Try another search term.") }
    var noRecords: String { text("기록 없음", "No Records") }
    var noRecordsDescription: String { text("질문을 생성하고 답변을 채점하면 기록이 쌓입니다.", "Records appear after you create questions and grade answers.") }
    var deleteRecordHelp: String { text("기록 삭제", "Delete Record") }
    var studyFallback: String { text("내 학습", "My Study") }
    var ungraded: String { text("미채점", "Ungraded") }
    var recordDetail: String { text("상세기록", "Record Detail") }
    func answerPrefix(_ answer: String) -> String { text("답변: \(answer)", "Answer: \(answer)", "回答：\(answer)") }

    var stats: String { text("통계", "Stats") }
    var noStatsRecords: String { text("기록이 없습니다", "No records") }
    var noScores: String { text("점수 없음", "No Scores") }
    var noScoresDescription: String { text("답변을 채점하면 점수 그래프가 표시됩니다.", "A score graph appears after you grade answers.") }
    var noScoresInPeriod: String { text("선택한 기간에 점수 없음", "No Scores in This Period") }
    var noScoresInPeriodDescription: String {
        text("기간을 넓히거나 새 답변을 채점하면 통계가 표시됩니다.", "Widen the period or grade a new answer to show stats.")
    }
    var responses: String { text("응답", "Responses") }
    var responsesShort: String { text("응답", "Resp") }
    var studyStreak: String { text("연속 학습", "Streak") }
    var longestStreak: String { text("해당 연도 최장 기록", "Best streak that year") }
    var streakKeepGoing: String { text("오늘도 이어가세요", "Keep it going today") }
    var streakStartToday: String { text("오늘 시작해보세요", "Start today") }
    var topicGrowth: String { text("성장 주제", "Growth") }
    var studyGrowth: String { text("학습별 성장", "Growth by study") }
    var growthCalculationHelp: String { text("성장 계산 안내", "How growth is calculated") }
    var abilityScale: String { text("실력 위치 · 1–10", "Ability position · 1–10") }
    var growthHelpAbilityTitle: String { text("실력을 1–10으로 환산", "Ability is placed on a 1–10 scale") }
    var growthHelpAbilityBody: String {
        text(
            "문제 난이도에 ‘(점수 − 50) ÷ 30’을 더한 뒤 1–10 범위로 제한합니다. 예를 들어 난이도 6에서 80점을 받으면 실력 위치는 7입니다.",
            "We add “(score − 50) ÷ 30” to the question difficulty, then clamp it to 1–10. For example, a score of 80 at difficulty 6 gives an ability position of 7."
        )
    }
    var growthHelpComparisonTitle: String { text("겹치지 않는 두 구간 비교", "Two non-overlapping windows are compared") }
    var growthHelpComparisonBody: String {
        text(
            "최근 답변 3–5개와 그 직전 답변 3–5개를 비교합니다. 같은 주제의 채점 답변이 6개보다 적으면 성장을 단정하지 않고 ‘측정 중’으로 표시합니다.",
            "The latest 3–5 graded answers are compared with the preceding 3–5. Fewer than 6 answers in a topic is shown as Measuring."
        )
    }
    var growthHelpTreeTitle: String { text("노드는 해당 주제의 기록만 표시", "Each node uses its own topic records") }
    var growthHelpTreeBody: String {
        text(
            "트리의 각 노드는 해당 주제에서 직접 답변한 기록만 보여줍니다. 하위 주제를 포함한 종합 결과는 학습 트리 상단 요약에서만 확인할 수 있습니다.",
            "Each tree node shows only answers recorded directly for that topic. The summary above the tree is the only place that combines descendant topics.",
            "ツリーの各ノードには、そのトピックで直接回答した履歴のみが表示されます。下位トピックを含む総合結果は、ツリー上部の概要でのみ確認できます。"
        )
    }
    var growthHelpSummaryTitle: String {
        text("요약 숫자는 실제 학습 기록을 기준으로 계산", "Summary counts use actual learning records", "概要の数値は実際の学習記録を基準に計算")
    }
    var growthHelpSummaryBody: String {
        text(
            "총 학습은 선택한 기간에 답변과 채점을 마친 기록 수입니다. 전체 주제에는 모든 하위 주제가 포함되고, 측정 주제는 성장 비교에 필요한 답변이 쌓인 주제 수입니다.",
            "Total learning is the number of records answered and graded in the selected period. Topics include every descendant, while Measured counts topics with enough answers for a growth comparison.",
            "総学習は、選択期間内に回答と採点が完了した記録数です。全トピックにはすべての下位トピックが含まれ、測定トピックは成長比較に必要な回答が蓄積されたトピック数です。"
        )
    }
    var totalLearningShort: String { text("총 학습 수", "Total learned", "総学習数") }
    var totalTopicsShort: String { text("전체 주제 수", "Topics", "全トピック数") }
    var measuredTopicsShort: String { text("측정 주제 수", "Measured", "測定トピック数") }
    var allStudies: String { text("전체 학습", "All studies") }
    var allStudiesDescription: String {
        text("필요할 때 펼쳐서 개별 통계를 확인합니다.", "Expand when you need individual study statistics.")
    }
    var studyGrowthSummary: String {
        text("선택한 기간의 전체 하위 주제 요약", "Summary of all subtopics in the selected period")
    }
    var treeSummary: String { text("트리 종합", "Tree summary", "ツリー総合") }
    var studyStatusTree: String { text("학습 상태 트리", "Study status tree") }
    var studyStatusTreeDescription: String {
        text(
            "각 노드는 해당 주제에서 직접 답변한 기록만 반영합니다.",
            "Each node reflects only answers recorded directly for that topic.",
            "各ノードには、そのトピックで直接回答した履歴のみが反映されます。"
        )
    }
    var comprehensive: String { text("종합", "Overall") }
    var studyMap: String { text("학습 지도", "Learning map") }
    var ability: String { text("실력", "Ability") }
    var notMeasured: String { text("미측정", "Unmeasured") }
    var studyMapFocusHint: String {
        text("두 번 탭하면 이 학습의 하위 지도를 확대합니다.", "Double-tap to focus the map on this study.")
    }
    var attentionStudies: String { text("먼저 볼 학습", "Studies to check first") }
    var attentionStudiesDescription: String {
        text("복습이 필요하거나 아직 측정이 부족한 학습입니다.", "Studies that may need review or more activity.")
    }
    func needsMoreAnswers(_ count: Int) -> String {
        text(
            "성장 측정까지 답변 \(max(6 - count, 0))개",
            "\(max(6 - count, 0)) more answers to measure growth",
            "成長測定まであと\(max(6 - count, 0))件"
        )
    }
    var partialMeasurement: String { text("일부 하위 주제 측정 중", "Some subtopics are still measuring") }
    func allStudiesCount(_ count: Int) -> String {
        text("전체 학습 \(count)개", "All studies \(count)", "すべての学習 \(count)件")
    }
    func growthPositionSummary(previous: String, current: String) -> String {
        text("이전 \(previous) → 현재 \(current)", "Previous \(previous) → Current \(current)", "以前 \(previous) → 現在 \(current)")
    }
    var currentAbility: String { text("현재", "Current") }
    var growthChange: String { text("성장", "Growth") }
    var measuringGrowth: String { text("측정 중", "Measuring") }
    var noGrowthRecords: String { text("아직 성장 기록이 없습니다", "No growth data yet") }
    var noGrowthRecordsDescription: String {
        text("같은 주제에서 답변을 6개 이상 채점하면 이전 구간과 최근 구간을 비교합니다.", "Grade at least 6 answers in a topic to compare previous and recent progress.")
    }
    var topicLearningRecords: String {
        text("이 주제의 학습 기록", "Learning records for this topic", "このトピックの学習履歴")
    }
    func topicRecordCount(_ shown: Int, total: Int) -> String {
        text("\(shown)/\(total)개", "\(shown)/\(total)", "\(shown)/\(total)件")
    }
    var loadingTopicRecords: String {
        text("학습 기록을 불러오고 있어요", "Loading learning records", "学習履歴を読み込んでいます")
    }
    var topicRecordsLoadFailed: String {
        text("기록을 불러오지 못했습니다", "Couldn’t load records", "履歴を読み込めませんでした")
    }
    var topicRecordsLoadFailedDescription: String {
        text("잠시 후 다시 시도해 주세요.", "Please try again shortly.", "しばらくしてからもう一度お試しください。")
    }
    var noTopicRecords: String {
        text("아직 기록이 없습니다", "No records yet", "まだ履歴がありません")
    }
    var noTopicRecordsDescription: String {
        text(
            "이 주제의 질문에 답변하고 채점을 완료하면 여기에 표시됩니다.",
            "Completed answers for this topic will appear here.",
            "このトピックの回答と採点が完了すると、ここに表示されます。"
        )
    }
    var needsReview: String { text("복습 필요", "Review") }
    var includesChildTopics: String { text("하위 포함", "Includes children") }
    var abilityTrend: String { text("실력 변화", "Ability over time", "実力の推移") }
    func lastMeasuredAt(_ date: Date) -> String {
        let value = date.formatted(date: .abbreviated, time: .shortened)
        return text("최근 측정 \(value)", "Last measured \(value)", "最終測定 \(value)")
    }
    var notEnoughTrendData: String { text("변화 기록이 더 필요합니다", "More data needed", "推移データが不足しています") }
    var notEnoughTrendDataDescription: String {
        text(
            "시간에 따른 변화를 보려면 이 주제의 채점 기록이 2개 이상 필요합니다.",
            "At least two graded answers in this topic are needed to show change over time.",
            "時間による変化を表示するには、このトピックで2件以上の採点済み回答が必要です。"
        )
    }
    var growthDetails: String { text("성장 상세", "Growth details") }
    var previousAbility: String { text("이전", "Previous") }
    var lastYear: String { text("최근 1년", "Last year") }
    func measuredTopics(_ measured: Int, total: Int) -> String {
        text("\(measured)/\(total)개 주제 측정", "\(measured)/\(total) topics measured", "\(measured)/\(total)件のトピックを測定")
    }
    func growthAnswerCount(_ count: Int) -> String {
        text("답변 \(count)개", "\(count) answers", "回答\(count)件")
    }
    var thisMonth: String { text("이번 달", "This Month") }
    var selectedYear: String { text("선택 연도", "Selected Year") }
    var year: String { text("연도", "Year") }
    var answersUnit: String { text("개", "answers") }
    var noActivityYet: String { text("아직 활동이 없습니다", "No activity yet") }
    func streakValue(_ days: Int) -> String { text("\(days)일", "\(days)d", "\(days)日") }
    func monthSummary(days: Int) -> String { text("\(days)일 학습", "\(days) active days", "\(days)日学習") }
    func monthSummaryWithTopic(days: Int, topic: String) -> String {
        text("\(days)일 · \(topic)", "\(days)d · \(topic)", "\(days)日 · \(topic)")
    }
    func yearSummary(days: Int) -> String { text("\(days)일 학습", "\(days) active days", "\(days)日学習") }
    func yearSummaryWithTopic(days: Int, topic: String) -> String {
        text("\(days)일 · \(topic)", "\(days)d · \(topic)", "\(days)日 · \(topic)")
    }
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
    var communityQuestion: String { text("질문 둘러보기", "Browse Question") }
    var browseQuestions: String { text("질문 둘러보기", "Browse Question") }
    var comments: String { text("댓글", "Comments") }
    var noComments: String { text("아직 댓글이 없습니다.", "No comments yet.") }
    var writeComment: String { text("댓글 쓰기", "Write a comment") }
    var signInToComment: String { text("로그인 후 댓글을 쓸 수 있습니다.", "Sign in to write a comment.") }
    var communityLogin: String { text("로그인", "Sign In") }
    var signInWithApple: String { text("Apple로 로그인", "Sign in with Apple", "Appleでログイン") }
    var signInWithGoogle: String { text("Google로 로그인", "Sign in with Google", "Googleでログイン") }
    var signInWithEmail: String { text("이메일로 로그인", "Sign in with Email", "メールでログイン") }
    var loginPageHelp: String {
        text(
            "로그인하면 기록, 통계, 내 학습 동기화 기능을 사용할 수 있습니다.",
            "Sign in to use records, statistics, and My Study sync."
        )
    }
    var protectedPageLoginHelp: String {
        text(
            "이 화면은 로그인 후 사용할 수 있습니다.",
            "This page is available after sign-in."
        )
    }
    var loginAgreementPrefix: String { text("로그인하면", "By signing in, you agree to") }
    var loginAgreementConjunction: String { text("및", "and") }
    var loginAgreementSuffix: String { text("에 동의하게됩니다.", ".") }
    var termsOfService: String { text("서비스 이용약관", "Terms of Service") }
    var privacyPolicy: String { text("개인정보 처리 방침", "Privacy Policy") }
    var termsAndConsents: String { text("약관 및 수신 동의", "Terms and Consents") }
    var operatingTerms: String { text("운영 약관", "Terms") }
    var notificationSettings: String { text("알림 설정", "Notification Settings") }
    var usage: String { text("사용량", "Usage") }
    var appVersion: String { text("버전", "Version") }
    var requiredTermsBadge: String { text("필수", "Required") }
    var optionalTermsBadge: String { text("선택", "Optional") }
    var agreeAndStart: String { text("동의하고 시작하기", "Agree and Start") }
    var agreeAllAndStart: String { text("모두 동의하고 시작하기", "Agree All and Start") }
    var agreeRequiredOnlyAndStart: String {
        text(
            "필수 약관만 동의하고 시작하기",
            "Continue with required terms only"
        )
    }
    var requiredTermsGateTitle: String {
        text(
            "BuddyStudy를 시작해볼까요?",
            "Ready to start BuddyStudy?"
        )
    }
    var requiredTermsGateSubtitle: String {
        text(
            "모두 동의하면 이벤트와 새로운 기능 소식도 함께 받아볼 수 있어요. 마케팅 정보 수신 동의는 선택입니다.",
            "Agree to all to receive event and new feature updates too. Marketing consent is optional."
        )
    }
    var marketingNotifications: String { text("마케팅 정보 수신 동의", "Marketing communications") }
    var marketingNotificationsHelp: String {
        text(
            "이벤트, 기능 업데이트 등 마케팅성 안내 수신 여부를 관리합니다.",
            "Manage marketing updates such as events and feature announcements."
        )
    }
    var questionNotifications: String { text("질문 알림", "Question notifications") }
    var questionNotificationsHelp: String {
        text(
            "학습 질문 알림은 시스템 알림 권한과 학습 설정에 따라 전송됩니다.",
            "Study question notifications depend on system permission and study settings."
        )
    }
    var notificationSystemPermissionRequired: String {
        text(
            "iPhone 설정에서 BuddyStudy 알림을 켜야 알림을 받을 수 있습니다.",
            "Turn on BuddyStudy notifications in iPhone Settings to receive notifications."
        )
    }
    var infoNotificationConsent: String { text("정보성 알림 수신 동의", "Informational notifications") }
    var marketingNotificationConsent: String { text("마케팅 알림 수신 동의", "Marketing notifications") }
    var nightMarketingNotificationConsent: String { text("야간 마케팅 알림 수신 동의", "Night marketing notifications") }
    var termsConsentHelp: String {
        text(
            "동의 상태는 기능 사용과 알림 수신 가능 여부에 반영됩니다. 자세한 내용은 각 항목의 링크에서 확인할 수 있습니다.",
            "Consent status affects feature availability and notification delivery. Open each detail link for the full text."
        )
    }
    var details: String { text("자세히", "Details") }
    var email: String { text("이메일", "Email") }
    var password: String { text("비밀번호", "Password") }
    var emailLoginHelp: String {
        text(
            "처음 가입하는 이메일은 인증코드를 요청한 뒤 180초 안에 입력해야 합니다.",
            "New email accounts require a verification code within 180 seconds."
        )
    }
    var emailVerificationCode: String { text("인증코드", "Verification Code") }
    var sendVerificationCode: String { text("인증코드 보내기", "Send Code") }
    var resendVerificationCode: String { text("다시 보내기", "Resend") }
    var emailVerificationSent: String { text("인증코드를 보냈습니다.", "Verification code sent.") }
    var emailVerificationSendFailed: String {
        text(
            "인증코드를 보내지 못했습니다. 잠시 후 다시 시도하세요.",
            "Could not send the verification code. Please try again shortly.",
            "確認コードを送信できませんでした。しばらくしてからもう一度お試しください。"
        )
    }
    var emailVerificationRequired: String {
        text(
            "처음 사용하는 이메일입니다. 인증코드를 보낸 뒤 입력해 로그인하세요.",
            "This email is new. Send a verification code, then enter it to sign in."
        )
    }
    var signInRequiredTitle: String { text("로그인", "Sign In") }
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
    var avatar: String { text("아바타", "Avatar") }
    var accountSettings: String { text("계정 설정", "Account Settings") }
    var accountSettingsHelp: String {
        text(
            "로그인 계정과 회원탈퇴를 관리합니다.",
            "Manage your signed-in account and account deletion."
        )
    }
    var profileRequestFailed: String { text("프로필을 불러오지 못했습니다.", "Could not load your profile.") }
    var profileAccount: String { text("로그인 계정", "Signed in as") }
    var profileDisplayName: String { text("이름", "Name") }
    var profileAvatar: String { text("프로필 사진", "Profile Picture") }
    var profileCharacter: String { text("프로필 캐릭터", "Profile Character") }
    var profileColor: String { text("프로필 색상", "Profile Color") }
    var customProfileColor: String { text("직접 설정", "Custom Color") }
    var red: String { text("빨강", "Red") }
    var green: String { text("초록", "Green") }
    var blue: String { text("파랑", "Blue") }
    var profilePhotoScale: String { text("사진 크기", "Photo Size") }
    var useProfilePhoto: String { text("사진 사용", "Use Photo") }
    var profileBio: String { text("소개말", "Bio") }
    var profileSaved: String { text("프로필을 저장했습니다.", "Profile saved.") }
    var deleteAccount: String { text("회원탈퇴", "Delete Account") }
    var deleteAccountNotice: String {
        text(
            "탈퇴하면 즉시 로그아웃되고 계정을 다시 사용할 수 없습니다. 프로필, 공개 질문, 학습과 관련 기록은 탈퇴 이벤트에 따라 순차적으로 삭제됩니다.",
            "You will be signed out immediately and cannot use this account again. Your profile, public questions, studies, and related records are then deleted asynchronously."
        )
    }
    var deleteAccountConfirmMessage: String {
        text(
            "회원탈퇴 후에는 계정과 관련 기록을 복구할 수 없습니다.",
            "Your account and related records cannot be recovered after deletion."
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
            "로그인이 필요합니다.",
            "Sign in required."
        )
    }
    func pageAccessDenied(_: String) -> String {
        text(
            "로그인이 필요합니다.",
            "Sign in required."
        )
    }
    var report: String { text("신고", "Report") }
    var openQuestion: String { text("질문 보기", "View question", "質問を見る") }
    var questionActions: String { text("질문 옵션", "Question options", "質問のオプション") }
    var deleteQuestion: String { text("질문 삭제", "Delete question", "質問を削除") }
    var deleteQuestionConfirmation: String {
        text(
            "이 질문과 답변 기록을 삭제할까요?",
            "Delete this question and its answer record?",
            "この質問と回答履歴を削除しますか？"
        )
    }
    var reportQuestion: String { text("질문 신고", "Report question", "質問を報告") }
    var reportQuestionConfirmation: String {
        text(
            "이 질문을 부적절한 콘텐츠로 신고할까요?",
            "Report this question as inappropriate?",
            "この質問を不適切なコンテンツとして報告しますか？"
        )
    }
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
            "BuddyStudy converts each answer's level and score into an ability estimate, then combines sample count and disagreement between answers. Mixed results across distant levels make the range wider. Answer more questions around that range to narrow it."
        )
    }
    var topicTrend: String { text("주제 레벨 추세", "Topic Level Trend") }
    var topicSummary: String { text("주제 통합 현황", "Topic Summary") }
    var topicCount: String { text("주제", "Topics") }
    var activeTopics: String { text("활동 주제", "Active Topics") }
    var recentActivity: String { text("최근 활동", "Recent Activity") }
    var activity: String { text("활동", "Activity") }
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
        text("\(start)-\(end)/\(total)", "\(start)-\(end)/\(total)", "\(start)-\(end)/\(total)")
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
    var feedbackPromptTitle: String { text("BuddyStudy를 더 좋게 만들어 주세요", "Help improve BuddyStudy") }
    var feedbackPromptBody: String {
        text(
            "피드백 주는 분들께 무료 크레딧을 더 드려요!",
            "Share feedback and receive extra credits for free!",
            "フィードバックをくださった方に無料クレジットを追加します！"
        )
    }
    var feedbackMessage: String { text("내용", "Message") }
    var feedbackMessagePlaceholder: String {
        text("어떤 점을 개선하면 좋을지 알려주세요.", "Describe what we can improve.")
    }
    var feedbackSubmit: String { text("보내기", "Send") }
    var feedbackSubmitted: String { text("피드백을 보냈습니다.", "Feedback sent.") }
    var tipMe: String { text("응원하기", "Tip Me") }
    var supportDeveloper: String { text("개발자 응원", "Support developer") }
    var explanation: String { text("해설", "Explanation") }
    var statsByTopic: String { text("주제별 통계", "Stats by Topic") }
    func currentTopicLevel(_ level: String) -> String {
        text("레벨: \(level)", "Level: \(level)", "レベル：\(level)")
    }
    func topicLevelRange(_ start: String, _ end: String, average: Int, count: Int) -> String {
        text(
            "범위: \(start)-\(end) · \(count)개",
            "Range: \(start)-\(end) · \(count)",
            "範囲：\(start)-\(end) · \(count)件"
        )
    }
    func groupedTopics(_ topics: String) -> String { text("묶인 주제: \(topics)", "Grouped topics: \(topics)", "グループ化されたトピック：\(topics)") }
    var notEnoughStats: String { text("통계를 만들려면 채점 기록이 더 필요합니다.", "Grade more answers to build insights.") }
    func itemCount(_ count: Int) -> String { text("\(count)개", "\(count)", "\(count)件") }
    var correctRate: String { text("정답", "Correct") }
}
