#if os(iOS)
@preconcurrency import AVFoundation
import Foundation

enum VoiceTutorAudioSessionInterruption {
    static func began(_ notification: Notification) -> Bool {
        guard notification.name == AVAudioSession.interruptionNotification,
              let rawValue = (notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? NSNumber)?
                .uintValue,
              let type = AVAudioSession.InterruptionType(rawValue: rawValue) else {
            return false
        }
        return type == .began
    }
}

private final class VoiceTutorCaptureGate: @unchecked Sendable {
    private let lock = NSLock()
    private var muted = false

    func setMuted(_ value: Bool) {
        lock.lock()
        muted = value
        lock.unlock()
    }

    func allowsCapture() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return !muted
    }
}

private final class VoiceTutorConverterInputProvider: @unchecked Sendable {
    private let lock = NSLock()
    private let inputBuffer: AVAudioPCMBuffer
    private var didProvideInput = false

    init(inputBuffer: AVAudioPCMBuffer) {
        self.inputBuffer = inputBuffer
    }

    func next(
        status: UnsafeMutablePointer<AVAudioConverterInputStatus>
    ) -> AVAudioBuffer? {
        lock.lock()
        defer { lock.unlock() }
        if didProvideInput {
            status.pointee = .noDataNow
            return nil
        }
        didProvideInput = true
        status.pointee = .haveData
        return inputBuffer
    }
}

private final class VoiceTutorInputConverter: @unchecked Sendable {
    private let converter: AVAudioConverter
    private let outputFormat: AVAudioFormat

    init?(inputFormat: AVAudioFormat, outputFormat: AVAudioFormat) {
        guard let converter = AVAudioConverter(from: inputFormat, to: outputFormat) else {
            return nil
        }
        self.converter = converter
        self.outputFormat = outputFormat
    }

    func convert(_ inputBuffer: AVAudioPCMBuffer) -> Data? {
        let ratio = outputFormat.sampleRate / inputBuffer.format.sampleRate
        let frameCapacity = AVAudioFrameCount(
            max(1, ceil(Double(inputBuffer.frameLength) * ratio) + 1)
        )
        guard let outputBuffer = AVAudioPCMBuffer(
            pcmFormat: outputFormat,
            frameCapacity: frameCapacity
        ) else {
            return nil
        }

        let inputProvider = VoiceTutorConverterInputProvider(inputBuffer: inputBuffer)
        var conversionError: NSError?
        let status = converter.convert(to: outputBuffer, error: &conversionError) { _, inputStatus in
            inputProvider.next(status: inputStatus)
        }
        guard conversionError == nil,
              status != .error,
              outputBuffer.frameLength > 0 else {
            return nil
        }

        let audioBuffer = outputBuffer.audioBufferList.pointee.mBuffers
        guard let bytes = audioBuffer.mData,
              audioBuffer.mDataByteSize > 0 else {
            return nil
        }
        return Data(bytes: bytes, count: Int(audioBuffer.mDataByteSize))
    }
}

struct VoiceTutorPlaybackCompletionState {
    private var pendingBufferCounts: [String: Int] = [:]
    private var sealedResponseIDs: Set<String> = []

    mutating func recordScheduled(responseID: String) {
        pendingBufferCounts[responseID, default: 0] += 1
    }

    mutating func recordPlayed(responseID: String) -> Bool {
        guard let pending = pendingBufferCounts[responseID], pending > 0 else {
            return false
        }
        if pending == 1 {
            pendingBufferCounts.removeValue(forKey: responseID)
        } else {
            pendingBufferCounts[responseID] = pending - 1
        }
        return completeIfReady(responseID: responseID)
    }

    mutating func seal(responseID: String) -> Bool {
        sealedResponseIDs.insert(responseID)
        return completeIfReady(responseID: responseID)
    }

    mutating func reset() {
        pendingBufferCounts.removeAll()
        sealedResponseIDs.removeAll()
    }

    private mutating func completeIfReady(responseID: String) -> Bool {
        guard sealedResponseIDs.contains(responseID), pendingBufferCounts[responseID] == nil else {
            return false
        }
        sealedResponseIDs.remove(responseID)
        return true
    }
}

@MainActor
final class VoiceTutorAudioEngine {
    enum AudioError: Error {
        case microphonePermissionDenied
        case unsupportedInputFormat
        case invalidOutputAudio
    }

    static let sampleRate: Double = 24_000
    static let channelCount: AVAudioChannelCount = 1

    private let engine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()
    private let captureGate = VoiceTutorCaptureGate()
    private let networkFormat: AVAudioFormat
    private var inputConverter: VoiceTutorInputConverter?
    private var inputTapInstalled = false
    private var interruptionObserver: NSObjectProtocol?
    private var playbackCompletionState = VoiceTutorPlaybackCompletionState()
    private var onPlaybackCompleted: (@Sendable (String) -> Void)?
    private(set) var isRunning = false
    private(set) var isMuted = false

    init() {
        networkFormat = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: Self.sampleRate,
            channels: Self.channelCount,
            interleaved: true
        )!
        engine.attach(playerNode)
    }

    static func requestMicrophonePermission() async -> Bool {
        switch AVAudioApplication.shared.recordPermission {
        case .granted:
            return true
        case .denied:
            return false
        case .undetermined:
            return await withCheckedContinuation { continuation in
                AVAudioApplication.requestRecordPermission { granted in
                    continuation.resume(returning: granted)
                }
            }
        @unknown default:
            return false
        }
    }

    func start(
        onAudioChunk: @escaping @Sendable (Data) -> Void,
        onPlaybackCompleted: @escaping @Sendable (String) -> Void,
        onInterruption: @escaping @Sendable () -> Void
    ) async throws {
        guard !isRunning else {
            return
        }
        guard await Self.requestMicrophonePermission() else {
            throw AudioError.microphonePermissionDenied
        }
        playbackCompletionState.reset()
        self.onPlaybackCompleted = onPlaybackCompleted

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(
            .playAndRecord,
            mode: .voiceChat,
            options: [.defaultToSpeaker, .allowBluetoothHFP]
        )
        try session.setPreferredIOBufferDuration(0.02)
        try session.setActive(true, options: .notifyOthersOnDeactivation)
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: session,
            queue: .main
        ) { notification in
            if VoiceTutorAudioSessionInterruption.began(notification) {
                onInterruption()
            }
        }

        let inputNode = engine.inputNode
        let inputFormat = inputNode.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0,
              inputFormat.channelCount > 0,
              let converter = VoiceTutorInputConverter(
                inputFormat: inputFormat,
                outputFormat: networkFormat
              ) else {
            try? session.setActive(false, options: .notifyOthersOnDeactivation)
            throw AudioError.unsupportedInputFormat
        }
        inputConverter = converter
        captureGate.setMuted(isMuted)

        engine.connect(playerNode, to: engine.mainMixerNode, format: networkFormat)
        inputNode.installTap(onBus: 0, bufferSize: 960, format: inputFormat) { [captureGate] buffer, _ in
            guard captureGate.allowsCapture(),
                  let data = converter.convert(buffer),
                  !data.isEmpty else {
                return
            }
            onAudioChunk(data)
        }
        inputTapInstalled = true

        engine.prepare()
        do {
            try engine.start()
            playerNode.play()
            isRunning = true
        } catch {
            stop()
            throw error
        }
    }

    func setMuted(_ muted: Bool) {
        isMuted = muted
        captureGate.setMuted(muted)
    }

    func playPCM24(_ delta: VoiceTutorRealtimeAudioDelta) throws {
        let data = delta.audio
        guard isRunning, !data.isEmpty, data.count.isMultiple(of: MemoryLayout<Int16>.size) else {
            throw AudioError.invalidOutputAudio
        }
        let frameCount = AVAudioFrameCount(data.count / MemoryLayout<Int16>.size)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: networkFormat, frameCapacity: frameCount) else {
            throw AudioError.invalidOutputAudio
        }
        buffer.frameLength = frameCount
        let destinationBuffer = buffer.mutableAudioBufferList.pointee.mBuffers
        guard let destination = destinationBuffer.mData else {
            throw AudioError.invalidOutputAudio
        }
        data.copyBytes(to: destination.assumingMemoryBound(to: UInt8.self), count: data.count)
        buffer.mutableAudioBufferList.pointee.mBuffers.mDataByteSize = UInt32(data.count)
        if let responseID = delta.responseID {
            playbackCompletionState.recordScheduled(responseID: responseID)
            playerNode.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [weak self] _ in
                Task { @MainActor [weak self] in
                    self?.recordPlayedBuffer(responseID: responseID)
                }
            }
        } else {
            playerNode.scheduleBuffer(buffer)
        }
        if !playerNode.isPlaying {
            playerNode.play()
        }
    }

    func finishResponseAudio(responseID: String) {
        if playbackCompletionState.seal(responseID: responseID) {
            onPlaybackCompleted?(responseID)
        }
    }

    private func recordPlayedBuffer(responseID: String) {
        if playbackCompletionState.recordPlayed(responseID: responseID) {
            onPlaybackCompleted?(responseID)
        }
    }

    func stop() {
        if let interruptionObserver {
            NotificationCenter.default.removeObserver(interruptionObserver)
            self.interruptionObserver = nil
        }
        if inputTapInstalled {
            engine.inputNode.removeTap(onBus: 0)
            inputTapInstalled = false
        }
        playerNode.stop()
        playbackCompletionState.reset()
        onPlaybackCompleted = nil
        engine.stop()
        inputConverter = nil
        isRunning = false
        isMuted = false
        captureGate.setMuted(false)
        try? AVAudioSession.sharedInstance().setActive(
            false,
            options: .notifyOthersOnDeactivation
        )
    }
}
#endif
