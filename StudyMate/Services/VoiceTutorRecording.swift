#if os(iOS)
import AVFoundation
import CryptoKit
import Foundation

struct VoiceTutorPCMFrame: @unchecked Sendable {
    var sampleRate: Double
    var channels: [[Float]]
    var frameCount: Int
    var capturedAtUptime: TimeInterval

    init(
        sampleRate: Double,
        channels: [[Float]],
        frameCount: Int,
        capturedAtUptime: TimeInterval
    ) {
        self.sampleRate = sampleRate
        self.channels = channels
        self.frameCount = frameCount
        self.capturedAtUptime = capturedAtUptime
    }

    init?(pcm16Mono data: Data, sampleRate: Double, capturedAtUptime: TimeInterval) {
        guard !data.isEmpty, data.count.isMultiple(of: MemoryLayout<Int16>.size) else {
            return nil
        }
        let count = data.count / MemoryLayout<Int16>.size
        var samples = [Float](repeating: 0, count: count)
        data.withUnsafeBytes { rawBuffer in
            let source = rawBuffer.bindMemory(to: Int16.self)
            for index in 0..<count {
                samples[index] = Float(source[index]) / Float(Int16.max)
            }
        }
        self.sampleRate = sampleRate
        channels = [samples]
        frameCount = count
        self.capturedAtUptime = capturedAtUptime
    }
}

struct VoiceTutorPendingRecording: Codable, Equatable, Sendable, Identifiable {
    var ownerUserID: Int64
    var sessionID: String
    var fileName: String
    var contentType: String
    var contentLength: Int64
    var sha256: String
    var durationMilliseconds: Int64
    var createdAt: Date

    var id: String { sessionID }
}

enum VoiceTutorRecordingError: Error {
    case invalidOwner
    case invalidSessionID
    case invalidManifest
    case lifecycleInvalidated
    case invalidAudioFormat
    case exportFailed
    case missingExport
    case purgeIncomplete
}

final class VoiceTutorSessionRecorder: @unchecked Sendable {
    enum Participant {
        case learner
        case tutor
    }

    private static let targetSampleRate = 48_000.0
    private static let encoderBitRate = 64_000

    private let sessionID: String
    private let ownerUserID: Int64
    private let lifecycleGeneration: UInt64
    private let queue = DispatchQueue(label: "io.github.ghkdqhrbals.StudyMate.voice-recording")
    private var sessionReadyAtUptime: TimeInterval?
    private var startedAtUptime: TimeInterval?
    private var stoppedAtUptime: TimeInterval?
    private let learnerURL: URL
    private let tutorURL: URL
    private let mixedURL: URL
    private var learnerFile: AVAudioFile?
    private var tutorFile: AVAudioFile?
    private var learnerFramePosition: AVAudioFramePosition = 0
    private var tutorFramePosition: AVAudioFramePosition = 0
    private var hasFinished = false
    private var recordingFailure: Error?

    init(
        sessionID: String,
        ownerUserID: Int64,
        lifecycleGeneration: UInt64 = VoiceTutorRecordingStore.lifecycleGeneration()
    ) throws {
        guard ownerUserID > 0 else {
            throw VoiceTutorRecordingError.invalidOwner
        }
        guard sessionID.count == 36, UUID(uuidString: sessionID) != nil else {
            throw VoiceTutorRecordingError.invalidSessionID
        }
        self.sessionID = sessionID
        self.ownerUserID = ownerUserID
        self.lifecycleGeneration = lifecycleGeneration
        guard VoiceTutorRecordingStore.isCurrentLifecycleGeneration(
            lifecycleGeneration,
            checkingPersistentMarker: true
        ) else {
            throw VoiceTutorRecordingError.lifecycleInvalidated
        }
        let directory = try VoiceTutorRecordingStore.recordingsDirectory()
        learnerURL = directory.appendingPathComponent("\(sessionID)-learner.m4a")
        tutorURL = directory.appendingPathComponent("\(sessionID)-tutor.m4a")
        mixedURL = directory.appendingPathComponent("\(sessionID).m4a")
        try VoiceTutorRecordingStore.removeUnfinalizedFiles(sessionID: sessionID)
        do {
            learnerFile = try Self.makeAudioFile(at: learnerURL)
            tutorFile = try Self.makeAudioFile(at: tutorURL)
            try VoiceTutorRecordingStore.protectOpenRecording(learnerURL)
            try VoiceTutorRecordingStore.protectOpenRecording(tutorURL)
            guard VoiceTutorRecordingStore.isCurrentLifecycleGeneration(
                lifecycleGeneration
            ) else {
                throw VoiceTutorRecordingError.lifecycleInvalidated
            }
        } catch {
            learnerFile = nil
            tutorFile = nil
            try? VoiceTutorRecordingStore.removeUnfinalizedFiles(sessionID: sessionID)
            throw error
        }
    }

    func append(_ frame: VoiceTutorPCMFrame, participant: Participant) {
        queue.async { [weak self] in
            self?.write(frame, participant: participant)
        }
    }

    func markSessionReady(atUptime uptime: TimeInterval = ProcessInfo.processInfo.systemUptime) {
        queue.async { [weak self] in
            guard let self, !self.hasFinished, self.sessionReadyAtUptime == nil else { return }
            self.sessionReadyAtUptime = uptime
        }
    }

    func stopAcceptingFrames(atUptime uptime: TimeInterval = ProcessInfo.processInfo.systemUptime) {
        queue.async { [weak self] in
            guard let self, !self.hasFinished else { return }
            self.stoppedAtUptime = min(self.stoppedAtUptime ?? uptime, uptime)
        }
    }

    func finish() async throws -> VoiceTutorPendingRecording {
        try await withCheckedThrowingContinuation { continuation in
            queue.async { [weak self] in
                guard let self else {
                    continuation.resume(throwing: CancellationError())
                    return
                }
                guard !hasFinished else {
                    continuation.resume(throwing: VoiceTutorRecordingError.exportFailed)
                    return
                }
                hasFinished = true
                do {
                    if let recordingFailure {
                        throw recordingFailure
                    }
                    let finalPosition = max(learnerFramePosition, tutorFramePosition)
                    try pad(to: finalPosition, participant: .learner)
                    try pad(to: finalPosition, participant: .tutor)
                    learnerFile = nil
                    tutorFile = nil
                    let durationMilliseconds = Int64(
                        (Double(finalPosition) / Self.targetSampleRate * 1_000).rounded()
                    )
                    Task {
                        do {
                            try await self.exportMixedRecording()
                            try VoiceTutorRecordingStore.protectAndExcludeFromBackup(self.mixedURL)
                            let attributes = try FileManager.default.attributesOfItem(
                                atPath: self.mixedURL.path
                            )
                            let contentLength = (attributes[.size] as? NSNumber)?.int64Value ?? 0
                            let pending = VoiceTutorPendingRecording(
                                ownerUserID: self.ownerUserID,
                                sessionID: self.sessionID,
                                fileName: self.mixedURL.lastPathComponent,
                                contentType: "audio/mp4",
                                contentLength: contentLength,
                                sha256: try Self.sha256(of: self.mixedURL),
                                durationMilliseconds: max(0, durationMilliseconds),
                                createdAt: Date()
                            )
                            try await VoiceTutorRecordingStore.shared.save(
                                pending,
                                lifecycleGeneration: self.lifecycleGeneration
                            )
                            try? FileManager.default.removeItem(at: self.learnerURL)
                            try? FileManager.default.removeItem(at: self.tutorURL)
                            continuation.resume(returning: pending)
                        } catch {
                            continuation.resume(
                                throwing: self.cleanupAfterFailure(originalError: error)
                            )
                        }
                    }
                } catch {
                    continuation.resume(
                        throwing: cleanupAfterFailure(originalError: error)
                    )
                }
            }
        }
    }

    private func cleanupAfterFailure(originalError: Error) -> Error {
        learnerFile = nil
        tutorFile = nil
        do {
            try VoiceTutorRecordingStore.removeUnfinalizedFiles(sessionID: sessionID)
            return originalError
        } catch {
            return error
        }
    }

    private func write(_ frame: VoiceTutorPCMFrame, participant: Participant) {
        guard !hasFinished else { return }
        guard VoiceTutorRecordingStore.isCurrentLifecycleGeneration(
            lifecycleGeneration
        ) else {
            recordingFailure = VoiceTutorRecordingError.lifecycleInvalidated
            learnerFile = nil
            tutorFile = nil
            try? VoiceTutorRecordingStore.removeUnfinalizedFiles(sessionID: sessionID)
            return
        }
        guard let sessionReadyAtUptime,
              frame.capturedAtUptime >= sessionReadyAtUptime,
              stoppedAtUptime.map({ frame.capturedAtUptime <= $0 }) ?? true,
              frame.frameCount > 0,
              !frame.channels.isEmpty,
              frame.sampleRate > 0,
              let buffer = Self.monoBuffer(from: frame) else {
            return
        }
        do {
            // SDP/control setup is not metered recording time. The first PCM
            // accepted after session.ready owns the shared timeline origin;
            // delayed pre-ready frames are rejected by their capture timestamp.
            let startedAtUptime = self.startedAtUptime ?? frame.capturedAtUptime
            self.startedAtUptime = startedAtUptime
            let desiredPosition = AVAudioFramePosition(
                max(0, frame.capturedAtUptime - startedAtUptime) * Self.targetSampleRate
            )
            let currentPosition = participant == .learner
                ? learnerFramePosition
                : tutorFramePosition
            if desiredPosition > currentPosition {
                try pad(to: desiredPosition, participant: participant)
            }
            switch participant {
            case .learner:
                try learnerFile?.write(from: buffer)
                learnerFramePosition += AVAudioFramePosition(buffer.frameLength)
            case .tutor:
                try tutorFile?.write(from: buffer)
                tutorFramePosition += AVAudioFramePosition(buffer.frameLength)
            }
        } catch {
            recordingFailure = error
        }
    }

    private func pad(to target: AVAudioFramePosition, participant: Participant) throws {
        var current = participant == .learner ? learnerFramePosition : tutorFramePosition
        guard target > current else { return }
        guard let format = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: Self.targetSampleRate,
            channels: 1,
            interleaved: false
        ) else {
            throw VoiceTutorRecordingError.invalidAudioFormat
        }
        while current < target {
            let count = AVAudioFrameCount(min(4_800, target - current))
            guard let silence = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: count) else {
                throw VoiceTutorRecordingError.invalidAudioFormat
            }
            silence.frameLength = count
            silence.floatChannelData?[0].initialize(repeating: 0, count: Int(count))
            switch participant {
            case .learner:
                try learnerFile?.write(from: silence)
                learnerFramePosition += AVAudioFramePosition(count)
                current = learnerFramePosition
            case .tutor:
                try tutorFile?.write(from: silence)
                tutorFramePosition += AVAudioFramePosition(count)
                current = tutorFramePosition
            }
        }
    }

    private static func makeAudioFile(at url: URL) throws -> AVAudioFile {
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: targetSampleRate,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: encoderBitRate
        ]
        return try AVAudioFile(
            forWriting: url,
            settings: settings,
            commonFormat: .pcmFormatFloat32,
            interleaved: false
        )
    }

    private static func monoBuffer(from frame: VoiceTutorPCMFrame) -> AVAudioPCMBuffer? {
        let outputCount = max(
            1,
            Int((Double(frame.frameCount) * targetSampleRate / frame.sampleRate).rounded())
        )
        guard let format = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: targetSampleRate,
            channels: 1,
            interleaved: false
        ), let output = AVAudioPCMBuffer(
            pcmFormat: format,
            frameCapacity: AVAudioFrameCount(outputCount)
        ), let destination = output.floatChannelData?[0] else {
            return nil
        }
        output.frameLength = AVAudioFrameCount(outputCount)
        let usableChannels = frame.channels.filter { $0.count >= frame.frameCount }
        guard !usableChannels.isEmpty else { return nil }
        for index in 0..<outputCount {
            let sourcePosition = min(
                Double(frame.frameCount - 1),
                Double(index) * frame.sampleRate / targetSampleRate
            )
            let lower = Int(sourcePosition)
            let upper = min(frame.frameCount - 1, lower + 1)
            let fraction = Float(sourcePosition - Double(lower))
            var sample: Float = 0
            for channel in usableChannels {
                sample += channel[lower] + (channel[upper] - channel[lower]) * fraction
            }
            destination[index] = sample / Float(usableChannels.count)
        }
        return output
    }

    private func exportMixedRecording() async throws {
        let composition = AVMutableComposition()
        var mixParameters: [AVMutableAudioMixInputParameters] = []
        for url in [learnerURL, tutorURL] {
            let asset = AVURLAsset(url: url)
            guard let sourceTrack = try await asset.loadTracks(withMediaType: .audio).first,
                  let track = composition.addMutableTrack(
                    withMediaType: .audio,
                    preferredTrackID: kCMPersistentTrackID_Invalid
                  ) else {
                continue
            }
            let duration = try await asset.load(.duration)
            try track.insertTimeRange(
                CMTimeRange(start: .zero, duration: duration),
                of: sourceTrack,
                at: .zero
            )
            let parameters = AVMutableAudioMixInputParameters(track: track)
            parameters.setVolume(0.5, at: .zero)
            mixParameters.append(parameters)
        }
        guard !composition.tracks(withMediaType: .audio).isEmpty,
              let export = AVAssetExportSession(
                asset: composition,
                presetName: AVAssetExportPresetAppleM4A
              ) else {
            throw VoiceTutorRecordingError.missingExport
        }
        export.outputURL = mixedURL
        export.outputFileType = .m4a
        export.shouldOptimizeForNetworkUse = true
        let audioMix = AVMutableAudioMix()
        audioMix.inputParameters = mixParameters
        export.audioMix = audioMix
        await withCheckedContinuation { continuation in
            export.exportAsynchronously {
                continuation.resume()
            }
        }
        guard export.status == .completed else {
            throw export.error ?? VoiceTutorRecordingError.exportFailed
        }
    }

    private static func sha256(of url: URL) throws -> String {
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        var hasher = SHA256()
        while true {
            let chunk = try file.read(upToCount: 1_048_576) ?? Data()
            guard !chunk.isEmpty else { break }
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

actor VoiceTutorRecordingStore {
    static let shared = VoiceTutorRecordingStore()
    private static let orphanCleanupAge: TimeInterval = 2 * 60 * 60
    private static let pendingRetryAge: TimeInterval = 30 * 24 * 60 * 60
    static let purgePendingMarkerFileName = ".voice-tutor-purge-pending"
    private static let lifecycleLock = NSLock()
    private nonisolated(unsafe) static var activeLifecycleGeneration: UInt64 = 0
    private nonisolated(unsafe) static var isPurgePendingInMemory = false

    static func lifecycleGeneration() -> UInt64 {
        lifecycleLock.lock()
        defer { lifecycleLock.unlock() }
        try? refreshPurgePendingFenceLocked()
        return activeLifecycleGeneration
    }

    static func isCurrentLifecycleGeneration(
        _ generation: UInt64,
        checkingPersistentMarker: Bool = false
    ) -> Bool {
        lifecycleLock.lock()
        defer { lifecycleLock.unlock() }
        if checkingPersistentMarker {
            do {
                try refreshPurgePendingFenceLocked()
            } catch {
                return false
            }
        }
        return generation == activeLifecycleGeneration && !isPurgePendingInMemory
    }

    static func recordingsDirectory() throws -> URL {
        let base = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = base.appendingPathComponent("VoiceTutorRecordings", isDirectory: true)
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.complete]
        )
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableDirectory = directory
        try mutableDirectory.setResourceValues(values)
        return directory
    }

    static func protectAndExcludeFromBackup(_ url: URL) throws {
        try FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.complete],
            ofItemAtPath: url.path
        )
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableURL = url
        try mutableURL.setResourceValues(values)
    }

    static func protectOpenRecording(_ url: URL) throws {
        try FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.completeUnlessOpen],
            ofItemAtPath: url.path
        )
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableURL = url
        try mutableURL.setResourceValues(values)
    }

    static func removeUnfinalizedFiles(sessionID: String) throws {
        guard sessionID.count == 36, UUID(uuidString: sessionID) != nil else {
            throw VoiceTutorRecordingError.invalidSessionID
        }
        let directory = try recordingsDirectory()
        let urls = [
            directory.appendingPathComponent("\(sessionID)-learner.m4a"),
            directory.appendingPathComponent("\(sessionID)-tutor.m4a"),
            directory.appendingPathComponent("\(sessionID).m4a"),
            directory.appendingPathComponent("\(sessionID).json")
        ]
        var firstError: Error?
        for url in urls where FileManager.default.fileExists(atPath: url.path) {
            do {
                try FileManager.default.removeItem(at: url)
            } catch {
                if firstError == nil {
                    firstError = error
                }
            }
        }
        if let firstError {
            throw firstError
        }
    }

    static func purgeAll() throws {
        lifecycleLock.lock()
        defer { lifecycleLock.unlock() }
        // Even a protected/temporarily unavailable directory must invalidate
        // exporters in this process. Cleanup will retry installing the durable
        // marker before attempting deletion once storage is available again.
        invalidateLifecycleLocked()
        try installPurgePendingMarkerLocked()
        let directory = try recordingsDirectory()
        try finishPendingPurgeLocked(in: directory)
    }

    static func purgePendingMarkerURL() throws -> URL {
        let base = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        // Purge intent contains no audio or account data. Keep it in a sibling
        // directory accessible after first unlock, so a background 401 while
        // the device is locked can persist deletion intent before touching the
        // fully protected media directory.
        let directory = base.appendingPathComponent("VoiceTutorRecordingState", isDirectory: true)
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableDirectory = directory
        try mutableDirectory.setResourceValues(values)
        return directory.appendingPathComponent(purgePendingMarkerFileName)
    }

    static func validate(_ pending: VoiceTutorPendingRecording) throws {
        guard pending.ownerUserID > 0,
              pending.sessionID.count == 36,
              UUID(uuidString: pending.sessionID) != nil,
              pending.fileName == "\(pending.sessionID).m4a",
              pending.contentType == "audio/mp4",
              pending.contentLength > 0,
              pending.contentLength <= 536_870_912,
              pending.sha256.range(
                of: "^[0-9a-f]{64}$",
                options: .regularExpression
              ) != nil,
              pending.durationMilliseconds > 0,
              pending.durationMilliseconds <= 3_605_000 else {
            throw VoiceTutorRecordingError.invalidManifest
        }
    }

    func save(
        _ pending: VoiceTutorPendingRecording,
        lifecycleGeneration: UInt64
    ) throws {
        Self.lifecycleLock.lock()
        defer { Self.lifecycleLock.unlock() }
        try Self.refreshPurgePendingFenceLocked()
        guard lifecycleGeneration == Self.activeLifecycleGeneration,
              !Self.isPurgePendingInMemory else {
            throw VoiceTutorRecordingError.lifecycleInvalidated
        }
        try Self.validate(pending)
        let directory = try Self.recordingsDirectory()
        let url = directory.appendingPathComponent("\(pending.sessionID).json")
        let data = try JSONEncoder().encode(pending)
        try data.write(to: url, options: [.atomic, .completeFileProtection])
        try Self.protectAndExcludeFromBackup(url)
    }

    func pendingRecordings(ownerUserID: Int64) throws -> [VoiceTutorPendingRecording] {
        guard ownerUserID > 0 else {
            throw VoiceTutorRecordingError.invalidOwner
        }
        Self.lifecycleLock.lock()
        defer { Self.lifecycleLock.unlock() }
        try Self.refreshPurgePendingFenceLocked()
        if Self.isPurgePendingInMemory {
            try Self.installPurgePendingMarkerLocked()
            try Self.finishPendingPurgeLocked(in: Self.recordingsDirectory())
            return []
        }
        let directory = try Self.recordingsDirectory()
        let now = Date()
        let retryCutoff = now.addingTimeInterval(-Self.pendingRetryAge)
        let contents = try FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.contentModificationDateKey, .creationDateKey]
        )
        let pending = contents
            .filter { $0.pathExtension.lowercased() == "json" }
            .compactMap { url -> VoiceTutorPendingRecording? in
            guard let data = try? Data(contentsOf: url),
                  let pending = try? JSONDecoder().decode(
                    VoiceTutorPendingRecording.self,
                    from: data
                  ), url.deletingPathExtension().lastPathComponent == pending.sessionID,
                  (try? Self.validate(pending)) != nil,
                  pending.ownerUserID == ownerUserID,
                  pending.createdAt >= retryCutoff,
                  pending.createdAt <= now.addingTimeInterval(5 * 60),
                  FileManager.default.fileExists(
                    atPath: directory.appendingPathComponent(pending.fileName).path
                  ) else {
                return nil
            }
            return pending
        }
        try cleanupOrphanedFiles(
            contents,
            preserving: Set(
                pending.flatMap { ["\($0.sessionID).json", $0.fileName] }
            ),
            olderThan: now.addingTimeInterval(-Self.orphanCleanupAge)
        )
        return pending.sorted { $0.createdAt < $1.createdAt }
    }

    func cleanupExpiredAndOrphaned(now: Date = Date()) throws {
        Self.lifecycleLock.lock()
        defer { Self.lifecycleLock.unlock() }
        try Self.refreshPurgePendingFenceLocked()
        if Self.isPurgePendingInMemory {
            try Self.installPurgePendingMarkerLocked()
            try Self.finishPendingPurgeLocked(in: Self.recordingsDirectory())
            return
        }
        let directory = try Self.recordingsDirectory()
        let retryCutoff = now.addingTimeInterval(-Self.pendingRetryAge)
        let futureCutoff = now.addingTimeInterval(5 * 60)
        var contents = try FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.contentModificationDateKey, .creationDateKey]
        )
        var retainedFileNames = Set<String>()
        var firstError: Error?

        for manifestURL in contents where manifestURL.pathExtension.lowercased() == "json" {
            guard let data = try? Data(contentsOf: manifestURL),
                  let pending = try? JSONDecoder().decode(
                    VoiceTutorPendingRecording.self,
                    from: data
                  ), manifestURL.deletingPathExtension().lastPathComponent == pending.sessionID,
                  (try? Self.validate(pending)) != nil else {
                continue
            }
            let mediaURL = directory.appendingPathComponent(pending.fileName)
            if pending.createdAt >= retryCutoff,
               pending.createdAt <= futureCutoff,
               FileManager.default.fileExists(atPath: mediaURL.path) {
                retainedFileNames.insert(manifestURL.lastPathComponent)
                retainedFileNames.insert(mediaURL.lastPathComponent)
                continue
            }
            // A valid manifest carries its own authoritative creation time, so
            // expired media can be removed immediately instead of waiting for
            // the generic orphan-age fallback.
            for url in [mediaURL, manifestURL] where FileManager.default.fileExists(atPath: url.path) {
                do {
                    try FileManager.default.removeItem(at: url)
                } catch {
                    if firstError == nil { firstError = error }
                }
            }
        }

        contents = try FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.contentModificationDateKey, .creationDateKey]
        )
        do {
            try cleanupOrphanedFiles(
                contents,
                preserving: retainedFileNames,
                olderThan: now.addingTimeInterval(-Self.orphanCleanupAge)
            )
        } catch {
            if firstError == nil { firstError = error }
        }
        if let firstError { throw firstError }
    }

    func mediaURL(for pending: VoiceTutorPendingRecording) throws -> URL {
        Self.lifecycleLock.lock()
        defer { Self.lifecycleLock.unlock() }
        try Self.validate(pending)
        try Self.refreshPurgePendingFenceLocked()
        guard !Self.isPurgePendingInMemory else {
            throw VoiceTutorRecordingError.lifecycleInvalidated
        }
        return try Self.recordingsDirectory().appendingPathComponent(pending.fileName)
    }

    func remove(_ pending: VoiceTutorPendingRecording) throws {
        try Self.validate(pending)
        let directory = try Self.recordingsDirectory()
        let media = directory.appendingPathComponent(pending.fileName)
        let manifest = directory.appendingPathComponent("\(pending.sessionID).json")
        if FileManager.default.fileExists(atPath: media.path) {
            try FileManager.default.removeItem(at: media)
        }
        if FileManager.default.fileExists(atPath: manifest.path) {
            try FileManager.default.removeItem(at: manifest)
        }
    }

    private func cleanupOrphanedFiles(
        _ contents: [URL],
        preserving retainedFileNames: Set<String>,
        olderThan cutoff: Date
    ) throws {
        var firstError: Error?
        for url in contents {
            let fileName = url.lastPathComponent
            guard !retainedFileNames.contains(fileName),
                  url.pathExtension.lowercased() == "m4a"
                    || url.pathExtension.lowercased() == "json" else {
                continue
            }
            let values = try? url.resourceValues(
                forKeys: [.contentModificationDateKey, .creationDateKey]
            )
            let lastChangedAt = values?.contentModificationDate
                ?? values?.creationDate
                ?? .distantPast
            guard lastChangedAt <= cutoff else { continue }
            do {
                try FileManager.default.removeItem(at: url)
            } catch {
                if firstError == nil {
                    firstError = error
                }
            }
        }
        if let firstError {
            throw firstError
        }
    }

    private static func installPurgePendingMarkerLocked() throws {
        let markerURL = try purgePendingMarkerURL()
        if !FileManager.default.fileExists(atPath: markerURL.path) {
            let marker = Data("voice-tutor-recording-purge-v1\n".utf8)
            try marker.write(to: markerURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }

        // Once the durable marker exists, no recorder from either the old or a
        // newly signed-in account may publish another manifest until deletion
        // has been confirmed. Keep this fence raised even if a later metadata
        // or deletion operation fails.
        try FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: markerURL.path
        )
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableMarkerURL = markerURL
        try mutableMarkerURL.setResourceValues(values)
        let handle = try FileHandle(forWritingTo: markerURL)
        defer { try? handle.close() }
        try handle.synchronize()
    }

    private static func finishPendingPurgeLocked(in directory: URL) throws {
        let markerURL = try purgePendingMarkerURL()
        var firstError: Error?
        let contents = try FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil
        )
        for url in contents {
            do {
                try FileManager.default.removeItem(at: url)
            } catch {
                if firstError == nil {
                    firstError = error
                }
            }
        }

        let remainingArtifacts = try FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil
        )
        if let firstError {
            throw firstError
        }
        guard remainingArtifacts.isEmpty else {
            throw VoiceTutorRecordingError.purgeIncomplete
        }

        if FileManager.default.fileExists(atPath: markerURL.path) {
            try FileManager.default.removeItem(at: markerURL)
        }
        guard !FileManager.default.fileExists(atPath: markerURL.path) else {
            throw VoiceTutorRecordingError.purgeIncomplete
        }
        isPurgePendingInMemory = false
    }

    private static func refreshPurgePendingFenceLocked() throws {
        let markerURL = try purgePendingMarkerURL()
        guard !isPurgePendingInMemory,
              FileManager.default.fileExists(atPath: markerURL.path) else { return }
        invalidateLifecycleLocked()
    }

    private static func invalidateLifecycleLocked() {
        isPurgePendingInMemory = true
        activeLifecycleGeneration &+= 1
    }
}

#endif
