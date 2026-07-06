import Foundation
#if os(macOS)
import AppKit
#elseif os(iOS)
import UIKit
import UniformTypeIdentifiers
#endif

@MainActor
protocol ClipboardProviding {
    func changeCount() -> Int
    func fetchOpenAIAPIKey() -> String?
}

@MainActor
struct DefaultClipboardProvider: ClipboardProviding {
    func changeCount() -> Int {
        #if os(macOS)
        return Int(NSPasteboard.general.changeCount)
        #elseif os(iOS)
        return UIPasteboard.general.changeCount
        #else
        return 0
        #endif
    }

    func fetchOpenAIAPIKey() -> String? {
        #if os(macOS)
        return fetchMacOpenAIAPIKey()
        #elseif os(iOS)
        return fetchIOSOpenAIAPIKey()
        #else
        return nil
        #endif
    }

    #if os(macOS)
    private func fetchMacOpenAIAPIKey() -> String? {
        let candidates: [NSPasteboard.PasteboardType] = [
            .string,
            .init("public.utf8-plain-text"),
            .init("public.text"),
            .init("public.utf16-plain-text"),
            .init("public.utf16-external-plain-text"),
            .init("public.html"),
            .init("public.rtf")
        ]

        for type in candidates {
            if let value = NSPasteboard.general.string(forType: type),
               let extracted = OpenAIAPIKeyExtractionPolicy.extract(from: value) {
                return extracted
            }
        }

        for item in NSPasteboard.general.pasteboardItems ?? [] {
            for type in item.types {
                if let value = extractString(fromPasteboardItem: item, type: type),
                   let extracted = OpenAIAPIKeyExtractionPolicy.extract(from: value) {
                    return extracted
                }
            }
        }

        let classes: [NSPasteboardReading.Type] = [NSString.self, NSAttributedString.self]
        if let objects = NSPasteboard.general.readObjects(forClasses: classes, options: nil),
           let extracted = objects
                .compactMap({ object -> String? in
                    if let string = object as? String {
                        return OpenAIAPIKeyExtractionPolicy.extract(from: string)
                    }
                    if let attributed = object as? NSAttributedString {
                        return OpenAIAPIKeyExtractionPolicy.extract(from: attributed.string)
                    }
                    return nil
                })
                .first(where: { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) {
            return extracted
        }

        return nil
    }

    private func extractString(fromPasteboardItem item: NSPasteboardItem, type: NSPasteboard.PasteboardType) -> String? {
        if let value = item.string(forType: type), !value.isEmpty {
            return value
        }

        guard let data = item.data(forType: type) else {
            return nil
        }

        if let text = String(data: data, encoding: .utf8), !text.isEmpty {
            return text
        }

        if let text = String(data: data, encoding: .utf16LittleEndian), !text.isEmpty {
            return text
        }

        if let text = String(data: data, encoding: .utf16BigEndian), !text.isEmpty {
            return text
        }

        return nil
    }
    #endif

    #if os(iOS)
    private func fetchIOSOpenAIAPIKey() -> String? {
        if let directString = UIPasteboard.general.string,
           let extracted = OpenAIAPIKeyExtractionPolicy.extract(from: directString) {
            return extracted
        }

        let candidates: [String] = [
            UTType.text.identifier,
            UTType.plainText.identifier,
            UTType.html.identifier,
            UTType.utf8PlainText.identifier,
            "public.text",
            "public.utf16-plain-text",
            "public.utf16-external-plain-text",
            UTType.utf16PlainText.identifier,
            UTType.rtf.identifier,
            "public.url",
            "public.url-name"
        ]

        for item in UIPasteboard.general.items {
            for value in item.values {
                if let extracted = extractOpenAIAPIKeyFromNestedClipboardValue(value) {
                    return extracted
                }
            }
        }

        for type in candidates {
            if let value = UIPasteboard.general.value(forPasteboardType: type),
               let extracted = extractOpenAIAPIKeyFromNestedClipboardValue(value) {
                return extracted
            }

            if let data = UIPasteboard.general.data(forPasteboardType: type),
               let dataText = String(data: data, encoding: .utf8),
               let extracted = OpenAIAPIKeyExtractionPolicy.extract(from: dataText) {
                return extracted
            }

            if let data = UIPasteboard.general.data(forPasteboardType: type),
               let extracted = extractOpenAIAPIKeyFromNestedData(data) {
                return extracted
            }
        }

        return nil
    }

    private func extractOpenAIAPIKey(fromClipboardValue value: Any) -> String? {
        if let valueString = value as? String,
           let extracted = OpenAIAPIKeyExtractionPolicy.extract(from: valueString) {
            return extracted
        }

        if let url = value as? URL,
           let extracted = OpenAIAPIKeyExtractionPolicy.extract(from: url.absoluteString) {
            return extracted
        }

        if let data = value as? Data {
            if let utf8Text = String(data: data, encoding: .utf8),
               let extracted = OpenAIAPIKeyExtractionPolicy.extract(from: utf8Text) {
                return extracted
            }

            if let utf16Text = String(data: data, encoding: .utf16LittleEndian),
               let extracted = OpenAIAPIKeyExtractionPolicy.extract(from: utf16Text) {
                return extracted
            }

            if let utf16Text = String(data: data, encoding: .utf16BigEndian),
               let extracted = OpenAIAPIKeyExtractionPolicy.extract(from: utf16Text) {
                return extracted
            }

            if let asciiText = String(data: data, encoding: .ascii),
               let extracted = OpenAIAPIKeyExtractionPolicy.extract(from: asciiText) {
                return extracted
            }
        }

        return nil
    }

    private func extractOpenAIAPIKeyFromNestedClipboardValue(_ value: Any) -> String? {
        if let found = extractOpenAIAPIKey(fromClipboardValue: value) {
            return found
        }

        if let arrayValue = value as? [Any] {
            for element in arrayValue {
                if let found = extractOpenAIAPIKeyFromNestedClipboardValue(element) {
                    return found
                }
            }
        }

        if let dictValue = value as? [String: Any] {
            for element in dictValue.values {
                if let found = extractOpenAIAPIKeyFromNestedClipboardValue(element) {
                    return found
                }
            }
        }

        return nil
    }

    private func extractOpenAIAPIKeyFromNestedData(_ data: Data) -> String? {
        let encodings: [String.Encoding] = [.utf8, .utf16LittleEndian, .utf16BigEndian, .ascii]

        for encoding in encodings {
            if let text = String(data: data, encoding: encoding),
               let extracted = OpenAIAPIKeyExtractionPolicy.extract(from: text) {
                return extracted
            }
        }

        return nil
    }
    #endif
}
