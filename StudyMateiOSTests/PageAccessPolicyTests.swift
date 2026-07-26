import XCTest
@testable import StudyMate

final class PageAccessPolicyTests: XCTestCase {
    func testLoginGateUsesAuthoritativeSignedInState() {
        XCTAssertFalse(
            PageAccessPolicy.shouldShowLoginGate(
                for: .statistics,
                isSignedIn: true
            )
        )
        XCTAssertTrue(
            PageAccessPolicy.shouldShowLoginGate(
                for: .records,
                isSignedIn: false
            )
        )
    }

    func testCommunitySessionStateInvalidatesInFlightRequestAfterSignOut() {
        var session = CommunitySessionStateStore(isSignedIn: true)
        let requestSnapshot = session.generation

        XCTAssertTrue(session.isCurrent(requestSnapshot))

        session.signOut()

        XCTAssertFalse(session.isCurrent(requestSnapshot))
        XCTAssertFalse(session.isCurrent(session.generation))

        session.signIn()

        XCTAssertTrue(session.isCurrent(session.generation))
    }

    func testTermsAgreementBackendErrorRoutesToAgreementGate() throws {
        let payload = """
        {
          "error": {
            "errorCode": "TERMS_AGREEMENT_REQUIRED",
            "code": 302,
            "message": "Latest terms agreement is required.",
            "requestId": "request-1",
            "status": 403
          }
        }
        """
        let envelope = try JSONDecoder().decode(
            BackendAPIErrorResponse.self,
            from: Data(payload.utf8)
        )
        let error = RemotePushBackendError.httpStatus(403, payload, envelope.error)

        let resolution = AppErrorHandlingPolicy.resolve(error, fallback: "")

        XCTAssertTrue(resolution.requiresTermsAgreement)
        XCTAssertFalse(resolution.requiresLogin)
        XCTAssertNil(resolution.featureMessage)
    }

    func testTermsAgreementBackendErrorStillRoutesWhenOptionalPayloadCannotDecode() {
        let payload = """
        {
          "error": {
            "errorCode": "TERMS_AGREEMENT_REQUIRED",
            "requiredTerms": [{"unexpected": true}]
          }
        }
        """
        let error = RemotePushBackendError.httpStatus(403, payload, nil)

        let resolution = AppErrorHandlingPolicy.resolve(error, fallback: "")

        XCTAssertTrue(resolution.requiresTermsAgreement)
        XCTAssertFalse(resolution.requiresLogin)
        XCTAssertNil(resolution.featureMessage)
    }

    func testPendingQuestionConflictUsesDedicatedErrorCode() throws {
        let payload = """
        {
          "error": {
            "errorCode": "STUDY_PENDING_QUESTION_EXISTS",
            "code": 501,
            "message": "이 주제에 답변 대기 중인 질문이 있습니다.",
            "requestId": "request-pending",
            "status": 409
          }
        }
        """
        let envelope = try JSONDecoder().decode(
            BackendAPIErrorResponse.self,
            from: Data(payload.utf8)
        )
        let error = RemotePushBackendError.httpStatus(409, payload, envelope.error)

        let resolution = AppErrorHandlingPolicy.resolve(error, fallback: "")

        XCTAssertTrue(resolution.isPendingQuestionConflict)
    }

    func testLegacyValidationErrorStillRecognizesPendingQuestionConflict() throws {
        let payload = """
        {
          "error": {
            "errorCode": "VALIDATION_ERROR",
            "code": 500,
            "message": "요청 값이 올바르지 않습니다.",
            "debugDescription": "A pending question already exists for this study.",
            "requestId": "request-legacy",
            "status": 409
          }
        }
        """
        let envelope = try JSONDecoder().decode(
            BackendAPIErrorResponse.self,
            from: Data(payload.utf8)
        )
        let error = RemotePushBackendError.httpStatus(409, payload, envelope.error)

        let resolution = AppErrorHandlingPolicy.resolve(error, fallback: "")

        XCTAssertTrue(resolution.isPendingQuestionConflict)
    }
}

final class StudyRoomStateStoreTests: XCTestCase {
    @MainActor
    func testPendingStudyRecordIsSelectedPerTopicStudy() {
        let suiteName = "StudyMateiOSTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }

        let store = SettingsStore(defaults: defaults)
        let swiftCategory = StudyCategory(id: "11", title: "Swift", difficulty: .level5)
        let kotlinCategory = StudyCategory(id: "12", title: "Kotlin", difficulty: .level6)
        store.saveSettings(
            StudySettings(
                topic: swiftCategory.title,
                difficulty: swiftCategory.difficulty,
                customPrompt: "",
                intervalMinutes: 30,
                studyCategories: [swiftCategory, kotlinCategory],
                selectedStudyCategoryID: swiftCategory.id
            )
        )
        store.replaceStudyRecords([
            StudyRecord(
                id: "swift-question",
                studyID: 11,
                question: QuestionItem(
                    question: "What is actor isolation?",
                    expectedAnswerHint: nil,
                    createdAt: Date(timeIntervalSince1970: 11)
                ),
                topic: swiftCategory.title,
                difficulty: swiftCategory.difficulty
            ),
            StudyRecord(
                id: "kotlin-question",
                studyID: 12,
                question: QuestionItem(
                    question: "What is structured concurrency?",
                    expectedAnswerHint: nil,
                    createdAt: Date(timeIntervalSince1970: 12)
                ),
                topic: kotlinCategory.title,
                difficulty: kotlinCategory.difficulty
            )
        ])
        let appState = AppState(settingsStore: store)

        XCTAssertEqual(appState.pendingStudyRecord(categoryID: swiftCategory.id)?.id, "swift-question")
        XCTAssertEqual(appState.pendingStudyRecord(categoryID: kotlinCategory.id)?.id, "kotlin-question")
    }

    func testBackendPendingQuestionCanBeClearedWithoutLocalRecordCacheEntry() {
        let record = StudyRecord(
            id: "record-42",
            question: QuestionItem(
                question: "What does SKIP LOCKED do?",
                expectedAnswerHint: nil,
                createdAt: Date(timeIntervalSince1970: 42)
            ),
            topic: "Database",
            difficulty: .intermediate
        )
        let room = BackendStudyRoom(
            id: 19,
            topic: "Database",
            difficultyLevel: 5,
            intervalMinutes: 30,
            enabled: true,
            notificationSound: nil,
            customPrompt: "",
            openAIModel: "gpt-5.4",
            maxHistoryCount: 100,
            nextDueAt: nil,
            lastSentAt: nil,
            lastError: nil,
            pendingQuestion: record,
            createdAt: Date(timeIntervalSince1970: 1),
            updatedAt: Date(timeIntervalSince1970: 1)
        )
        var state = StudyRoomStateStore()
        state.replace(with: [room])

        XCTAssertTrue(state.containsPendingQuestion(recordID: record.id))

        state.clearPendingQuestion(recordID: record.id)

        XCTAssertFalse(state.containsPendingQuestion(recordID: record.id))
        XCTAssertEqual(state.pendingQuestionCount, 0)
    }

    func testPendingQuestionCountUsesStudyIDWhenTopicsAreEqual() {
        let pendingRecord = StudyRecord(
            id: "record-11",
            studyID: 11,
            question: QuestionItem(
                question: "Root question",
                expectedAnswerHint: nil,
                createdAt: Date(timeIntervalSince1970: 11)
            ),
            topic: "Redis",
            difficulty: .intermediate
        )
        var state = StudyRoomStateStore()
        state.replace(with: [
            backendRoom(id: 11, topic: "Redis", pendingQuestion: pendingRecord),
            backendRoom(id: 12, topic: "Redis", pendingQuestion: nil)
        ])

        XCTAssertEqual(
            state.pendingQuestionCount(for: StudyCategory(id: "11", title: "Redis")),
            1
        )
        XCTAssertEqual(
            state.pendingQuestionCount(for: StudyCategory(id: "12", title: "Redis")),
            0
        )
    }

    func testIncomingRecordOnlyUpdatesItsStudyID() {
        let record = StudyRecord(
            id: "record-12",
            studyID: 12,
            question: QuestionItem(
                question: "Child question",
                expectedAnswerHint: nil,
                createdAt: Date(timeIntervalSince1970: 12)
            ),
            topic: "Redis",
            difficulty: .intermediate
        )
        var state = StudyRoomStateStore()
        state.replace(with: [
            backendRoom(id: 11, topic: "Redis", pendingQuestion: nil),
            backendRoom(id: 12, topic: "Redis", pendingQuestion: nil)
        ])

        XCTAssertTrue(state.applyIncomingRecord(record))
        XCTAssertNil(state.rooms.first(where: { $0.id == 11 })?.pendingQuestion)
        XCTAssertEqual(state.rooms.first(where: { $0.id == 12 })?.pendingQuestion?.id, record.id)
    }

    @MainActor
    func testPendingLimitDoesNotFallBackFromRootToChildStudy() {
        let suiteName = "StudyMateiOSTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }

        let store = SettingsStore(defaults: defaults)
        let root = StudyCategory(id: "11", title: "Redis", difficulty: .level5)
        let child = StudyCategory(id: "12", title: "Redis Streams", difficulty: .level5)
        store.saveSettings(
            StudySettings(
                topic: child.title,
                difficulty: root.difficulty,
                customPrompt: "",
                intervalMinutes: 30,
                studyCategories: [root, child],
                selectedStudyCategoryID: root.id
            )
        )
        store.replaceStudyRecords([
            StudyRecord(
                id: "root-question",
                studyID: 11,
                question: QuestionItem(
                    question: "Root question",
                    expectedAnswerHint: nil,
                    createdAt: Date(timeIntervalSince1970: 11)
                ),
                topic: root.title,
                difficulty: root.difficulty
            )
        ])
        let appState = AppState(settingsStore: store)

        XCTAssertTrue(appState.hasReachedPendingQuestionLimit(categoryID: root.id))
        XCTAssertFalse(appState.hasReachedPendingQuestionLimit(categoryID: child.id))
        XCTAssertNil(appState.pendingStudyRecord(categoryID: child.id))
    }

    private func backendRoom(
        id: Int,
        topic: String,
        pendingQuestion: StudyRecord?
    ) -> BackendStudyRoom {
        BackendStudyRoom(
            id: id,
            topic: topic,
            difficultyLevel: 5,
            intervalMinutes: 30,
            enabled: true,
            notificationSound: nil,
            customPrompt: "",
            openAIModel: "gpt-5.4",
            maxHistoryCount: 100,
            nextDueAt: nil,
            lastSentAt: nil,
            lastError: nil,
            pendingQuestion: pendingQuestion,
            createdAt: Date(timeIntervalSince1970: 1),
            updatedAt: Date(timeIntervalSince1970: 1)
        )
    }
}

final class StudyTreeViewportPersistenceTests: XCTestCase {
    func testViewportPersistsPerRootStudyAndSanitizesValues() {
        let suiteName = "StudyMateiOSTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }

        let store = SettingsStore(defaults: defaults)
        XCTAssertFalse(store.hasStudyTreeViewport(rootStudyID: 7))
        store.saveStudyTreeViewport(
            StudyTreeViewportState(
                zoomScale: 1.45,
                contentOffsetX: 180,
                contentOffsetY: 96,
                canvasAlignmentX: 24,
                canvasAlignmentY: 80
            ),
            rootStudyID: 7
        )
        XCTAssertTrue(store.hasStudyTreeViewport(rootStudyID: 7))

        XCTAssertEqual(
            store.loadStudyTreeViewport(rootStudyID: 7),
            StudyTreeViewportState(
                zoomScale: 1.45,
                contentOffsetX: 180,
                contentOffsetY: 96,
                canvasAlignmentX: 24,
                canvasAlignmentY: 80
            )
        )
        XCTAssertEqual(store.loadStudyTreeViewport(rootStudyID: 8), .default)

        store.saveStudyTreeViewport(
            StudyTreeViewportState(
                zoomScale: 4,
                contentOffsetX: -20,
                contentOffsetY: .infinity,
                canvasAlignmentX: -.infinity,
                canvasAlignmentY: .infinity
            ),
            rootStudyID: 9
        )
        XCTAssertEqual(
            store.loadStudyTreeViewport(rootStudyID: 9),
            StudyTreeViewportState(
                zoomScale: 1.8,
                contentOffsetX: 0,
                contentOffsetY: 0,
                canvasAlignmentX: 0,
                canvasAlignmentY: 0
            )
        )
    }
}

final class StudyTreeLayoutPolicyTests: XCTestCase {
    func testNodeLevelFillProgressesFromLeftToRight() {
        XCTAssertEqual(StudyTreeNodeStylePolicy.levelFillFraction(1), 0.1)
        XCTAssertEqual(StudyTreeNodeStylePolicy.levelFillFraction(5), 0.5)
        XCTAssertEqual(StudyTreeNodeStylePolicy.levelFillFraction(10), 1)
    }

    func testCanvasExpandsToIncludeMovedNodesAndRecoversInvalidValues() {
        let baseCenters = [7: CGPoint(x: 100, y: 100)]

        XCTAssertEqual(
            StudyTreeCanvasPolicy.expandedLayout(
                baseCenters: baseCenters,
                nodeOffsets: [7: CGSize(width: 1_000, height: -1_000)],
                baseCanvasSize: CGSize(width: 320, height: 320),
                nodeSize: CGSize(width: 112, height: 112)
            ),
            StudyTreeCanvasLayout(
                size: CGSize(width: 1_164, height: 1_284),
                translation: CGSize(width: 0, height: 964)
            )
        )
        XCTAssertEqual(
            StudyTreeCanvasPolicy.expandedLayout(
                baseCenters: baseCenters,
                nodeOffsets: [7: CGSize(width: 100_000, height: 100_000)],
                baseCanvasSize: CGSize(width: 320, height: 320),
                nodeSize: CGSize(width: 112, height: 112)
            ),
            StudyTreeCanvasLayout(
                size: CGSize(width: 100_164, height: 100_164),
                translation: .zero
            )
        )
        XCTAssertEqual(
            StudyTreeCanvasPolicy.sanitizedOffset(
                CGSize(width: CGFloat.infinity, height: CGFloat.nan)
            ),
            .zero
        )
    }

    func testInitialFitStopsAsSoonAsTheUserMovesTheTree() {
        let viewportSize = CGSize(width: 390, height: 700)

        XCTAssertTrue(
            StudyTreeViewportPolicy.shouldApplyInitialFit(
                isRequested: true,
                hasApplied: false,
                hasUserInteracted: false,
                hasFinishedRefresh: true,
                viewportSize: viewportSize
            )
        )
        XCTAssertFalse(
            StudyTreeViewportPolicy.shouldApplyInitialFit(
                isRequested: true,
                hasApplied: false,
                hasUserInteracted: true,
                hasFinishedRefresh: true,
                viewportSize: viewportSize
            )
        )
    }

    func testScrollOffsetPreservesSafeAreaInsetAtLogicalOrigin() {
        let leadingInset = CGSize(width: 0, height: 116)

        XCTAssertEqual(
            StudyTreeViewportPolicy.normalizedContentOffset(
                rawContentOffset: CGPoint(x: 0, y: -116),
                leadingInset: leadingInset
            ),
            .zero
        )
        XCTAssertEqual(
            StudyTreeViewportPolicy.rawContentOffset(
                normalizedContentOffset: .zero,
                leadingInset: leadingInset
            ),
            CGPoint(x: 0, y: -116)
        )
        XCTAssertEqual(
            StudyTreeViewportPolicy.maximumNormalizedContentOffset(
                contentSize: CGSize(width: 390, height: 800),
                viewportSize: CGSize(width: 390, height: 700),
                totalInset: CGSize(width: 0, height: 116)
            ),
            CGPoint(x: 0, y: 216)
        )
    }

    func testNewNodesMoveAsideFromExistingNodesAtTheSameLevel() {
        let baseCenters = [
            1: CGPoint(x: 100, y: 100),
            2: CGPoint(x: 254, y: 100),
            3: CGPoint(x: 408, y: 100),
            4: CGPoint(x: 408, y: 286)
        ]
        let offsets = StudyTreeCanvasPolicy.offsetsPlacingNewNodesWithoutSameLevelOverlap(
            newRoomIDs: [3, 4],
            baseCenters: baseCenters,
            nodeOffsets: [1: CGSize(width: 308, height: 0)],
            nodeSize: CGSize(width: 112, height: 112)
        )

        XCTAssertEqual(offsets[3], CGSize(width: 128, height: 0))
        XCTAssertEqual(offsets[4], .zero)
        XCTAssertEqual(offsets[1], CGSize(width: 308, height: 0))
    }

    func testTreeEdgesPointFromParentTowardChild() throws {
        let geometry = try XCTUnwrap(
            StudyTreeEdgePolicy.directionalGeometry(
                parent: CGPoint(x: 100, y: 100),
                child: CGPoint(x: 100, y: 286),
                nodeRadius: 60
            )
        )

        XCTAssertEqual(geometry.start, CGPoint(x: 100, y: 160))
        XCTAssertEqual(geometry.end, CGPoint(x: 100, y: 226))
        XCTAssertEqual(geometry.arrowLeft, CGPoint(x: 95, y: 216))
        XCTAssertEqual(geometry.arrowRight, CGPoint(x: 105, y: 216))
    }

    func testStudySubtreeDeletesChildrenBeforeTheirParent() {
        let parentByRoomID = [
            2: 1,
            3: 2,
            4: 1,
            9: 8
        ]
        let subtree = StudyTreeDeletionPolicy.subtreeIDs(
            rootIDs: [1],
            parentByRoomID: parentByRoomID
        )

        XCTAssertEqual(subtree, [1, 2, 3, 4])
        XCTAssertEqual(
            StudyTreeDeletionPolicy.childFirstDeletionOrder(
                studyIDs: subtree,
                parentByRoomID: parentByRoomID
            ),
            [3, 2, 4, 1]
        )
    }

    func testInitialZoomFitsEntireCanvasWithoutEnlargingSmallTrees() {
        XCTAssertEqual(
            StudyTreeViewportPolicy.fittedZoomScale(
                canvasSize: CGSize(width: 1_000, height: 500),
                viewportSize: CGSize(width: 400, height: 300),
                padding: 20
            ),
            0.36,
            accuracy: 0.0001
        )
        XCTAssertEqual(
            StudyTreeViewportPolicy.fittedZoomScale(
                canvasSize: CGSize(width: 200, height: 200),
                viewportSize: CGSize(width: 400, height: 500)
            ),
            1
        )
    }

    func testZoomKeepsGestureAnchorStationary() {
        XCTAssertEqual(
            StudyTreeViewportPolicy.contentOffsetPreservingAnchor(
                startOffset: CGPoint(x: 100, y: 50),
                anchor: CGPoint(x: 200, y: 300),
                canvasSize: CGSize(width: 1_000, height: 800),
                viewportSize: CGSize(width: 400, height: 600),
                startAlignmentInset: .zero,
                targetAlignmentInset: .zero,
                startScale: 1,
                targetScale: 2
            ),
            CGPoint(x: 400, y: 400)
        )
        XCTAssertEqual(
            StudyTreeViewportPolicy.contentOffsetPreservingAnchor(
                startOffset: .zero,
                anchor: CGPoint(x: 200, y: 300),
                canvasSize: CGSize(width: 1_000, height: 1_000),
                viewportSize: CGSize(width: 400, height: 600),
                startAlignmentInset: .zero,
                targetAlignmentInset: .zero,
                startScale: 1,
                targetScale: 0.5
            ),
            .zero
        )
    }

    func testZoomCrossingViewportBoundaryKeepsCenteredCanvasStable() {
        let canvasSize = CGSize(width: 200, height: 200)
        let viewportSize = CGSize(width: 400, height: 600)
        let viewportCenter = CGPoint(x: 200, y: 300)
        let centeredInset = StudyTreeViewportPolicy.centeredCanvasAlignmentInset(
            canvasSize: canvasSize,
            viewportSize: viewportSize,
            zoomScale: 1
        )

        let zoomedInOffset = StudyTreeViewportPolicy.contentOffsetPreservingAnchor(
            startOffset: .zero,
            anchor: viewportCenter,
            canvasSize: canvasSize,
            viewportSize: viewportSize,
            startAlignmentInset: centeredInset,
            targetAlignmentInset: .zero,
            startScale: 1,
            targetScale: 3
        )
        XCTAssertEqual(zoomedInOffset, CGPoint(x: 100, y: 0))

        XCTAssertEqual(
            StudyTreeViewportPolicy.contentOffsetPreservingAnchor(
                startOffset: zoomedInOffset,
                anchor: viewportCenter,
                canvasSize: canvasSize,
                viewportSize: viewportSize,
                startAlignmentInset: .zero,
                targetAlignmentInset: centeredInset,
                startScale: 3,
                targetScale: 1
            ),
            .zero
        )
    }

    func testFiveHundredDragUpdatesNeverTeleportTheTree() {
        let baseCenters = [
            1: CGPoint(x: 100, y: 100),
            2: CGPoint(x: 260, y: 100)
        ]
        let baseCanvasSize = CGSize(width: 320, height: 320)
        let nodeSize = CGSize(width: 112, height: 112)
        let zoomScale: CGFloat = 0.75
        let fixedAlignmentInset = CGSize(width: 35, height: 90)
        let startViewportOffset = CGPoint(x: 40, y: 30)
        let startLayout = StudyTreeCanvasPolicy.expandedLayout(
            baseCenters: baseCenters,
            nodeOffsets: [:],
            baseCanvasSize: baseCanvasSize,
            nodeSize: nodeSize
        )
        let stationaryNodeStart = CGPoint(
            x: (baseCenters[2]!.x + startLayout.translation.width) * zoomScale
                + fixedAlignmentInset.width
                - startViewportOffset.x,
            y: (baseCenters[2]!.y + startLayout.translation.height) * zoomScale
                + fixedAlignmentInset.height
                - startViewportOffset.y
        )
        let draggedNodeStart = CGPoint(
            x: (baseCenters[1]!.x + startLayout.translation.width) * zoomScale
                + fixedAlignmentInset.width
                - startViewportOffset.x,
            y: (baseCenters[1]!.y + startLayout.translation.height) * zoomScale
                + fixedAlignmentInset.height
                - startViewportOffset.y
        )

        for step in 0...500 {
            let progress = CGFloat(step) / 500
            let triangularProgress = progress <= 0.5
                ? progress * 2
                : (1 - progress) * 2
            let draggedOffset = CGSize(
                width: -1_200 * triangularProgress,
                height: -800 * triangularProgress
            )
            let layout = StudyTreeCanvasPolicy.expandedLayout(
                baseCenters: baseCenters,
                nodeOffsets: [1: draggedOffset],
                baseCanvasSize: baseCanvasSize,
                nodeSize: nodeSize
            )
            let compensation =
                StudyTreeViewportPolicy.compensationPreservingCanvasTranslation(
                    startOffset: startViewportOffset,
                    startAlignmentInset: fixedAlignmentInset,
                    startCanvasTranslation: startLayout.translation,
                    targetCanvasTranslation: layout.translation,
                    zoomScale: zoomScale
                )
            let viewportOffset = compensation.viewportOffset
            let alignmentInset = compensation.alignmentInset
            let stationaryNode = CGPoint(
                x: (baseCenters[2]!.x + layout.translation.width) * zoomScale
                    + alignmentInset.width
                    - viewportOffset.x,
                y: (baseCenters[2]!.y + layout.translation.height) * zoomScale
                    + alignmentInset.height
                    - viewportOffset.y
            )
            let draggedNode = CGPoint(
                x: (
                    baseCenters[1]!.x
                        + draggedOffset.width
                        + layout.translation.width
                ) * zoomScale
                    + alignmentInset.width
                    - viewportOffset.x,
                y: (
                    baseCenters[1]!.y
                        + draggedOffset.height
                        + layout.translation.height
                ) * zoomScale
                    + alignmentInset.height
                    - viewportOffset.y
            )

            XCTAssertEqual(stationaryNode.x, stationaryNodeStart.x, accuracy: 0.0001)
            XCTAssertEqual(stationaryNode.y, stationaryNodeStart.y, accuracy: 0.0001)
            XCTAssertEqual(
                draggedNode.x,
                draggedNodeStart.x + draggedOffset.width * zoomScale,
                accuracy: 0.0001
            )
            XCTAssertEqual(
                draggedNode.y,
                draggedNodeStart.y + draggedOffset.height * zoomScale,
                accuracy: 0.0001
            )
        }
    }

    func testFiveHundredInwardDragUpdatesNeverTeleportTheTree() {
        let baseCenters = [
            1: CGPoint(x: 100, y: 100),
            2: CGPoint(x: 260, y: 100)
        ]
        let initialDraggedOffset = CGSize(width: -1_200, height: -800)
        let baseCanvasSize = CGSize(width: 320, height: 320)
        let nodeSize = CGSize(width: 112, height: 112)
        let zoomScale: CGFloat = 0.75
        let startAlignmentInset = CGSize(width: 35, height: 90)
        let startViewportOffset = CGPoint.zero
        let startLayout = StudyTreeCanvasPolicy.expandedLayout(
            baseCenters: baseCenters,
            nodeOffsets: [1: initialDraggedOffset],
            baseCanvasSize: baseCanvasSize,
            nodeSize: nodeSize
        )
        let stationaryNodeStart = CGPoint(
            x: (baseCenters[2]!.x + startLayout.translation.width) * zoomScale
                + startAlignmentInset.width,
            y: (baseCenters[2]!.y + startLayout.translation.height) * zoomScale
                + startAlignmentInset.height
        )
        let draggedNodeStart = CGPoint(
            x: (
                baseCenters[1]!.x
                    + initialDraggedOffset.width
                    + startLayout.translation.width
            ) * zoomScale
                + startAlignmentInset.width,
            y: (
                baseCenters[1]!.y
                    + initialDraggedOffset.height
                    + startLayout.translation.height
            ) * zoomScale
                + startAlignmentInset.height
        )

        for step in 0...500 {
            let progress = CGFloat(step) / 500
            let draggedOffset = CGSize(
                width: initialDraggedOffset.width * (1 - progress),
                height: initialDraggedOffset.height * (1 - progress)
            )
            let layout = StudyTreeCanvasPolicy.expandedLayout(
                baseCenters: baseCenters,
                nodeOffsets: [1: draggedOffset],
                baseCanvasSize: baseCanvasSize,
                nodeSize: nodeSize
            )
            let compensation =
                StudyTreeViewportPolicy.compensationPreservingCanvasTranslation(
                    startOffset: startViewportOffset,
                    startAlignmentInset: startAlignmentInset,
                    startCanvasTranslation: startLayout.translation,
                    targetCanvasTranslation: layout.translation,
                    zoomScale: zoomScale
                )
            let stationaryNode = CGPoint(
                x: (baseCenters[2]!.x + layout.translation.width) * zoomScale
                    + compensation.alignmentInset.width
                    - compensation.viewportOffset.x,
                y: (baseCenters[2]!.y + layout.translation.height) * zoomScale
                    + compensation.alignmentInset.height
                    - compensation.viewportOffset.y
            )
            let draggedNode = CGPoint(
                x: (
                    baseCenters[1]!.x
                        + draggedOffset.width
                        + layout.translation.width
                ) * zoomScale
                    + compensation.alignmentInset.width
                    - compensation.viewportOffset.x,
                y: (
                    baseCenters[1]!.y
                        + draggedOffset.height
                        + layout.translation.height
                ) * zoomScale
                    + compensation.alignmentInset.height
                    - compensation.viewportOffset.y
            )

            XCTAssertEqual(stationaryNode.x, stationaryNodeStart.x, accuracy: 0.0001)
            XCTAssertEqual(stationaryNode.y, stationaryNodeStart.y, accuracy: 0.0001)
            XCTAssertEqual(
                draggedNode.x,
                draggedNodeStart.x
                    + (draggedOffset.width - initialDraggedOffset.width) * zoomScale,
                accuracy: 0.0001
            )
            XCTAssertEqual(
                draggedNode.y,
                draggedNodeStart.y
                    + (draggedOffset.height - initialDraggedOffset.height) * zoomScale,
                accuracy: 0.0001
            )
        }
    }
}
