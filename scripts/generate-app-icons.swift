import AppKit
import Foundation
import UniformTypeIdentifiers

let sourceIconURL = URL(fileURLWithPath: "design/app-icon-concepts/fox-app-icons/app-icon-fox-backpack-book-source-1024.png")
let outputDirectory = URL(fileURLWithPath: "StudyMate/Resources/Assets.xcassets/AppIcon.appiconset")

struct IconImage {
    let filename: String
    let pixels: Int
}

let images = [
    IconImage(filename: "icon_20x20.png", pixels: 20),
    IconImage(filename: "icon_20x20@2x.png", pixels: 40),
    IconImage(filename: "icon_20x20@3x.png", pixels: 60),
    IconImage(filename: "icon_29x29.png", pixels: 29),
    IconImage(filename: "icon_29x29@2x.png", pixels: 58),
    IconImage(filename: "icon_29x29@3x.png", pixels: 87),
    IconImage(filename: "icon_40x40.png", pixels: 40),
    IconImage(filename: "icon_40x40@2x.png", pixels: 80),
    IconImage(filename: "icon_40x40@3x.png", pixels: 120),
    IconImage(filename: "icon_60x60@2x.png", pixels: 120),
    IconImage(filename: "icon_60x60@3x.png", pixels: 180),
    IconImage(filename: "icon_76x76.png", pixels: 76),
    IconImage(filename: "icon_76x76@2x.png", pixels: 152),
    IconImage(filename: "icon_83_5x83_5@2x.png", pixels: 167),
    IconImage(filename: "icon_1024x1024.png", pixels: 1024),
    IconImage(filename: "icon_16x16.png", pixels: 16),
    IconImage(filename: "icon_16x16@2x.png", pixels: 32),
    IconImage(filename: "icon_32x32.png", pixels: 32),
    IconImage(filename: "icon_32x32@2x.png", pixels: 64),
    IconImage(filename: "icon_128x128.png", pixels: 128),
    IconImage(filename: "icon_128x128@2x.png", pixels: 256),
    IconImage(filename: "icon_256x256.png", pixels: 256),
    IconImage(filename: "icon_256x256@2x.png", pixels: 512),
    IconImage(filename: "icon_512x512.png", pixels: 512),
    IconImage(filename: "icon_512x512@2x.png", pixels: 1024)
]

guard let sourceImage = NSImage(contentsOf: sourceIconURL) else {
    fatalError("Missing source icon at \(sourceIconURL.path)")
}

func pngData(from sourceImage: NSImage, size: Int) -> Data? {
    let dimension = CGFloat(size)
    guard let context = CGContext(
        data: nil,
        width: size,
        height: size,
        bitsPerComponent: 8,
        bytesPerRow: 0,
        space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
    ) else {
        return nil
    }

    context.setFillColor(NSColor(calibratedRed: 1.0, green: 0.992, blue: 0.972, alpha: 1.0).cgColor)
    context.fill(CGRect(x: 0, y: 0, width: dimension, height: dimension))

    guard let cgImage = sourceImage.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
        return nil
    }

    context.interpolationQuality = .high
    context.draw(cgImage, in: CGRect(x: 0, y: 0, width: dimension, height: dimension))

    guard let renderedImage = context.makeImage() else {
        return nil
    }

    let data = NSMutableData()
    guard let destination = CGImageDestinationCreateWithData(
        data,
        UTType.png.identifier as CFString,
        1,
        nil
    ) else {
        return nil
    }

    CGImageDestinationAddImage(destination, renderedImage, nil)
    guard CGImageDestinationFinalize(destination) else {
        return nil
    }

    return data as Data
}

try FileManager.default.createDirectory(at: outputDirectory, withIntermediateDirectories: true)

for icon in images {
    guard let png = pngData(from: sourceImage, size: icon.pixels) else {
        fatalError("Failed to render \(icon.filename)")
    }

    try png.write(to: outputDirectory.appendingPathComponent(icon.filename))
}
