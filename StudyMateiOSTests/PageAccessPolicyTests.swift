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
}

final class StudyRoomStateStoreTests: XCTestCase {
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
                contentOffsetY: 96
            ),
            rootStudyID: 7
        )
        XCTAssertTrue(store.hasStudyTreeViewport(rootStudyID: 7))

        XCTAssertEqual(
            store.loadStudyTreeViewport(rootStudyID: 7),
            StudyTreeViewportState(
                zoomScale: 1.45,
                contentOffsetX: 180,
                contentOffsetY: 96
            )
        )
        XCTAssertEqual(store.loadStudyTreeViewport(rootStudyID: 8), .default)

        store.saveStudyTreeViewport(
            StudyTreeViewportState(
                zoomScale: 4,
                contentOffsetX: -20,
                contentOffsetY: .infinity
            ),
            rootStudyID: 9
        )
        XCTAssertEqual(
            store.loadStudyTreeViewport(rootStudyID: 9),
            StudyTreeViewportState(
                zoomScale: 1.8,
                contentOffsetX: 0,
                contentOffsetY: 0
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

        let zoomedInOffset = StudyTreeViewportPolicy.contentOffsetPreservingAnchor(
            startOffset: .zero,
            anchor: viewportCenter,
            canvasSize: canvasSize,
            viewportSize: viewportSize,
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
                startScale: 3,
                targetScale: 1
            ),
            .zero
        )
    }
}
