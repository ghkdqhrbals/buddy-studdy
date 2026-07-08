import XCTest
@testable import StudyMate

final class AvatarBuilderAssetTests: XCTestCase {
    func testSnooRendererCoversSeededCatalogKeys() {
        for itemKey in Self.seededCatalogItemKeys {
            XCTAssertTrue(
                AvatarBuilderVisualRegistry.supportsItemKey(itemKey),
                "Missing Snoo avatar renderer support for \(itemKey)"
            )
        }
    }

    func testLegacyProfileSymbolsResolveToSnooBaseKeys() {
        XCTAssertEqual(AvatarBuilderVisualRegistry.baseItemKey(forSymbolName: "pixel-cat-laptop"), "base-cat")
        XCTAssertEqual(AvatarBuilderVisualRegistry.baseItemKey(forSymbolName: "pixel-fox-scholar"), "base-fox")
        XCTAssertEqual(AvatarBuilderVisualRegistry.baseItemKey(forSymbolName: "pixel-rabbit-pencil"), "base-rabbit")
        XCTAssertEqual(AvatarBuilderVisualRegistry.baseItemKey(forSymbolName: "pixel-dog-corgi-reader"), "base-dog")
        XCTAssertEqual(AvatarBuilderVisualRegistry.baseItemKey(forSymbolName: "person.fill"), "base-fox")
    }
}

private extension AvatarBuilderAssetTests {
    static var seededCatalogItemKeys: [String] {
        [
            "base-cat",
            "base-fox",
            "base-rabbit",
            "base-dog",
            "background-teal",
            "background-indigo",
            "background-slate",
            "top-hoodie-blue",
            "top-varsity-green",
            "top-sweater-rose",
            "bottom-denim-pants",
            "bottom-jogger-black",
            "bottom-shorts-tan",
            "shoes-white-sneakers",
            "shoes-brown-loafers",
            "shoes-blue-boots",
            "hat-beanie-navy",
            "hat-cap-orange",
            "hat-grad-black",
            "item-laptop",
            "item-book",
            "item-pencil"
        ]
    }
}
