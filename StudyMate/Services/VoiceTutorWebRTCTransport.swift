#if os(iOS)
import AVFoundation
import Foundation
import LiveKitWebRTC

struct VoiceTutorWebRTCBackendFailure: Equatable, Sendable {
    let code: String?
    let message: String?

    private struct Payload: Decodable {
        let code: String?
        let message: String?

        private enum CodingKeys: String, CodingKey {
            case errorCode
            case code
            case message
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            code = (try? container.decodeIfPresent(String.self, forKey: .errorCode))
                ?? (try? container.decodeIfPresent(String.self, forKey: .code))
            message = try? container.decodeIfPresent(String.self, forKey: .message)
        }
    }

    private struct Envelope: Decodable {
        let error: Payload
    }

    /// The SDP endpoint returns the regular BuddyStudy error envelope even
    /// though its success response is `application/sdp`. Keep only bounded,
    /// structured fields. The server message is diagnostic context and is
    /// deliberately never rendered or logged by the call UI.
    static func decode(from data: Data) -> Self? {
        guard !data.isEmpty, data.count <= 16_384 else { return nil }
        let decoder = JSONDecoder()
        let payload = (try? decoder.decode(Envelope.self, from: data).error)
            ?? (try? decoder.decode(Payload.self, from: data))
        guard let payload else { return nil }
        let code = safeCode(payload.code)
        let message = safeMessage(payload.message)
        guard code != nil || message != nil else { return nil }
        return Self(code: code, message: message)
    }

    private static func safeCode(_ value: String?) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.utf8.count <= 128,
              trimmed.unicodeScalars.allSatisfy({ scalar in
                  CharacterSet.alphanumerics.contains(scalar)
                      || scalar == "_" || scalar == "-" || scalar == "."
              }) else { return nil }
        return trimmed
    }

    private static func safeMessage(_ value: String?) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.utf8.count <= 512,
              trimmed.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) }) else {
            return nil
        }
        return trimmed
    }
}

enum VoiceTutorWebRTCError: Error {
    case peerConnectionCreationFailed
    case localTrackCreationFailed
    case offerCreationFailed
    case invalidSDPResponse
    case sdpExchangeFailed(statusCode: Int, backendFailure: VoiceTutorWebRTCBackendFailure?)
    case mediaConnectionFailed
    case mediaConnectionTimedOut
    case speechActivityUnavailable
    case audioProcessingDelegateInstallationFailed
}

enum VoiceTutorWebRTCMediaReadiness: Equatable {
    case waiting
    case connected
    case failed
    case timedOut
}

enum VoiceTutorLocalSpeechActivity: String, Equatable, Sendable {
    case started, stopped
}

struct VoiceTutorLocalSpeechEvent: Equatable, Sendable {
    let activity: VoiceTutorLocalSpeechActivity
    /// One utterance owns one sequence: start N, stop N, then start N + 1.
    let sequence: Int

    var messageType: String { "buddystudy.voice.input.speech.\(activity.rawValue)" }
}

enum VoiceTutorSpeechSampleScale: Equatable, Sendable {
    case normalizedFloat
    case webRTCFloatS16
}

/// A display/transport-independent acoustic boundary detector. Production uses
/// Silero probability, not amplitude, words or the tutor's speaking state. It
/// never changes microphone/tutor PCM or commits provider input itself.
struct VoiceTutorLocalSpeechDetector {
    /// Forty 32 ms Silero windows preserve the learner's turn across short
    /// thinking pauses and quiet word endings. Continuing speech resets this
    /// entire hold; the server still owns when a tutor response may begin.
    static let quietHoldDuration: TimeInterval = 1.28

    private(set) var isSpeaking = false
    private(set) var sequence = 0
    private var mediaReady = false
    private var muted = false
    private var closed = false
    private var onsetDuration: TimeInterval = 0
    private var silenceDuration: TimeInterval = 0
    private var noiseRMS = 0.001

    var isEnabled: Bool { mediaReady && !muted && !closed }

    mutating func updateGate(mediaReady: Bool, muted: Bool) -> VoiceTutorLocalSpeechEvent? {
        guard !closed else { return nil }
        let wasEnabled = isEnabled
        self.mediaReady = mediaReady
        self.muted = muted
        guard wasEnabled != isEnabled else { return nil }
        onsetDuration = 0
        silenceDuration = 0
        noiseRMS = 0.001
        guard !isEnabled, isSpeaking else { return nil }
        isSpeaking = false
        return VoiceTutorLocalSpeechEvent(activity: .stopped, sequence: sequence)
    }

    mutating func process(speechProbability: Double, duration: TimeInterval) -> VoiceTutorLocalSpeechEvent? {
        guard isEnabled, speechProbability.isFinite, (0...1).contains(speechProbability),
              duration.isFinite, duration > 0, duration <= 0.25 else { return nil }
        if isSpeaking {
            silenceDuration = speechProbability < 0.35 ? silenceDuration + duration : 0
            guard silenceDuration + 0.000_000_001 >= Self.quietHoldDuration else { return nil }
            isSpeaking = false
            onsetDuration = 0
            silenceDuration = 0
            return VoiceTutorLocalSpeechEvent(activity: .stopped, sequence: sequence)
        }
        guard speechProbability >= 0.5 else { onsetDuration = 0; return nil }
        onsetDuration += duration
        guard onsetDuration + 0.000_000_001 >= 0.08, sequence < Int.max else { return nil }
        sequence += 1
        isSpeaking = true
        onsetDuration = 0
        silenceDuration = 0
        return VoiceTutorLocalSpeechEvent(activity: .started, sequence: sequence)
    }

    /// Retained for earlier deterministic sample-scale/energy contracts. The
    /// live capture tap never calls this overload and has no RMS fallback.
    mutating func process(normalizedRMS: Double, duration: TimeInterval) -> VoiceTutorLocalSpeechEvent? {
        guard isEnabled, normalizedRMS.isFinite, (0...1).contains(normalizedRMS),
              duration.isFinite, duration > 0, duration <= 0.25 else { return nil }

        if isSpeaking {
            let releaseThreshold = max(0.003, noiseRMS * 1.8)
            silenceDuration = normalizedRMS < releaseThreshold ? silenceDuration + duration : 0
            guard silenceDuration + 0.000_000_001 >= Self.quietHoldDuration else { return nil }
            isSpeaking = false
            onsetDuration = 0
            silenceDuration = 0
            return VoiceTutorLocalSpeechEvent(activity: .stopped, sequence: sequence)
        }

        let onsetThreshold = max(0.006, noiseRMS * 3.5)
        if normalizedRMS >= onsetThreshold {
            onsetDuration += duration
            guard onsetDuration + 0.000_000_001 >= 0.08, sequence < Int.max else { return nil }
            sequence += 1
            isSpeaking = true
            onsetDuration = 0
            silenceDuration = 0
            return VoiceTutorLocalSpeechEvent(activity: .started, sequence: sequence)
        }

        onsetDuration = 0
        // Learn the floor only outside a candidate utterance. Speech must not
        // adapt its own threshold upward while the learner is still talking.
        let adaptation = 1 - exp(-duration / 2)
        noiseRMS = max(0.0001, min(0.02, noiseRMS + adaptation * (normalizedRMS - noiseRMS)))
        return nil
    }

    /// A native format discontinuity must not reuse recurrent evidence, but it
    /// also must not reuse an utterance sequence already sent to the backend.
    mutating func resetAcousticEvidence() -> VoiceTutorLocalSpeechEvent? {
        onsetDuration = 0
        silenceDuration = 0
        noiseRMS = 0.001
        guard isSpeaking else { return nil }
        isSpeaking = false
        return VoiceTutorLocalSpeechEvent(activity: .stopped, sequence: sequence)
    }

    mutating func close() {
        self = VoiceTutorLocalSpeechDetector()
        closed = true
    }

    mutating func reset() { self = VoiceTutorLocalSpeechDetector() }

    static func normalizedRMS(samples: [Float], scale: VoiceTutorSpeechSampleScale) -> Double? {
        samples.withUnsafeBufferPointer { normalizedRMS(samples: $0, scale: scale) }
    }

    static func normalizedRMS(
        samples: UnsafeBufferPointer<Float>,
        scale: VoiceTutorSpeechSampleScale
    ) -> Double? {
        guard !samples.isEmpty else { return nil }
        // LKRTCAudioBuffer.rawBufferForChannel wraps AudioBuffer::channels().
        // The bundled m144 implementation stores FloatS16, not [-1, 1]. See
        // webrtc-sdk/webrtc/modules/audio_processing/audio_buffer.cc CopyFrom
        // and common_audio/include/audio_util.h FloatS16ToFloat. Never infer
        // the scale from amplitude: quiet native samples may themselves be < 1.
        let divisor: Double = scale == .webRTCFloatS16 ? 32_768 : 1
        var sum = 0.0
        for sample in samples {
            guard sample.isFinite else { return nil }
            let normalized = max(-1, min(1, Double(sample) / divisor))
            sum += normalized * normalized
        }
        return (sum / Double(samples.count)).squareRoot()
    }
}

private struct VoiceTutorUncheckedSendable<Value>: @unchecked Sendable {
    var value: Value
}

enum VoiceTutorAudioProcessingModuleFactory {
    static func make(
        captureDelegate: any LKRTCAudioCustomProcessingDelegate,
        renderDelegate: (any LKRTCAudioCustomProcessingDelegate)? = nil
    ) throws -> LKRTCDefaultAudioProcessingModule {
        let module = LKRTCDefaultAudioProcessingModule(
            config: nil,
            capturePostProcessingDelegate: nil,
            renderPreProcessingDelegate: nil
        )
        // LiveKitWebRTC 144.7559.14's native initWithDelegate: discards its
        // argument. The property setter installs the delegate and replays any
        // completed initialization, including the real processing sample rate.
        // Callers must retain these weak delegates for the module's lifetime.
        module.capturePostProcessingDelegate = captureDelegate
        module.renderPreProcessingDelegate = renderDelegate
        try validate(module, captureDelegate: captureDelegate, renderDelegate: renderDelegate)
        return module
    }

    static func validate(
        _ module: LKRTCDefaultAudioProcessingModule,
        captureDelegate: any LKRTCAudioCustomProcessingDelegate,
        renderDelegate: (any LKRTCAudioCustomProcessingDelegate)? = nil
    ) throws {
        guard module.capturePostProcessingDelegate === captureDelegate,
              module.renderPreProcessingDelegate === renderDelegate else {
            throw VoiceTutorWebRTCError.audioProcessingDelegateInstallationFailed
        }
    }
}

final class VoiceTutorAudioProcessingTap: NSObject, LKRTCAudioCustomProcessingDelegate,
    @unchecked Sendable {
    private let participant: VoiceTutorSessionRecorder.Participant
    private let recorder: VoiceTutorSessionRecorder
    private var sampleRate: Double = 48_000

    init(participant: VoiceTutorSessionRecorder.Participant, recorder: VoiceTutorSessionRecorder) {
        self.participant = participant
        self.recorder = recorder
    }

    func audioProcessingInitialize(sampleRate: Int, channels: Int) {
        self.sampleRate = Double(sampleRate)
    }

    func audioProcessingProcess(audioBuffer: LKRTCAudioBuffer) {
        let frameCount = Int(audioBuffer.frames)
        let channelCount = Int(audioBuffer.channels)
        guard sampleRate > 0, frameCount > 0, channelCount > 0 else { return }
        var channels: [[Float]] = []
        channels.reserveCapacity(channelCount)
        for channel in 0..<channelCount {
            // LKRTCAudioBuffer is only valid during this callback. Copy every
            // sample before returning. Its FloatS16 samples must be normalized
            // for the recorder's AVAudioPCMBuffer; native RTP remains untouched.
            let pointer = audioBuffer.rawBuffer(forChannel: channel)
            guard let samples = Self.normalizedRecordingSamples(
                UnsafeBufferPointer(start: pointer, count: frameCount)
            ) else { return }
            channels.append(samples)
        }
        recorder.append(
            VoiceTutorPCMFrame(
                sampleRate: sampleRate,
                channels: channels,
                frameCount: frameCount,
                capturedAtUptime: ProcessInfo.processInfo.systemUptime
            ),
            participant: participant
        )
    }

    static func normalizedRecordingSamples(_ samples: [Float]) -> [Float]? {
        samples.withUnsafeBufferPointer { normalizedRecordingSamples($0) }
    }

    static func normalizedRecordingSamples(_ samples: UnsafeBufferPointer<Float>) -> [Float]? {
        guard !samples.isEmpty else { return nil }
        var normalized: [Float] = []
        normalized.reserveCapacity(samples.count)
        for sample in samples {
            guard sample.isFinite else { return nil }
            normalized.append(max(-1, min(1, sample / 32_768)))
        }
        return normalized
    }

    func audioProcessingRelease() {}
}

enum VoiceTutorLocalSpeechCaptureDiagnostic: String, Equatable, Sendable {
    case initialized = "capture_initialized"
    case firstValidInputFrame = "capture_first_valid_input_frame"
}

/// Metadata only: no samples, energy measurements, audio, or transcript text.
struct VoiceTutorLocalSpeechCaptureSnapshot: Equatable, Sendable {
    let initializationCount: Int
    let processedBufferCount: Int
    let validInputBufferCount: Int
    let validInputFrameCount: Int
    let speechInferenceCount: Int
    let sampleRate: Double
    let inputChannelCount: Int
    let processingGeneration: UInt64
    let gateEnabled: Bool
    let isClosed: Bool
}

/// Installed for every WebRTC call, even without optional recording consent.
/// Copies bounded mono input to an off-thread, local-only acoustic pipeline.
/// Original native media and the explicitly consented recorder stay unchanged.
final class VoiceTutorLocalSpeechCaptureTap: NSObject, LKRTCAudioCustomProcessingDelegate,
    @unchecked Sendable {
    private let lock = NSLock()
    private var detector = VoiceTutorLocalSpeechDetector()
    private var sampleRate: Double = 0
    private var inputChannelCount = 0
    private var isClosed = false
    private var initializationCount = 0
    private var processedBufferCount = 0
    private var validInputBufferCount = 0
    private var validInputFrameCount = 0
    private var speechInferenceCount = 0
    private var captureGeneration: UInt64 = 0
    private var didReportSpeechFailure = false
    private var didReportInitialization = false
    private var didReportValidInput = false
    private let recordingTap: VoiceTutorAudioProcessingTap?
    private var speechPipeline: VoiceTutorSileroAudioPipeline?
    private let failureDelivery = DispatchQueue(label: "com.buddystudy.voice.capture-failure")
    private let onActivity: @Sendable (VoiceTutorLocalSpeechEvent) -> Void
    private let onDiagnostic: (@Sendable (
        VoiceTutorLocalSpeechCaptureDiagnostic, VoiceTutorLocalSpeechCaptureSnapshot
    ) -> Void)?
    private let onFailure: (@Sendable (VoiceTutorSileroError) -> Void)?

    init(
        recordingTap: VoiceTutorAudioProcessingTap?,
        speechScorer: (any VoiceTutorSpeechProbabilityScoring)? = nil,
        onActivity: @escaping @Sendable (VoiceTutorLocalSpeechEvent) -> Void,
        onDiagnostic: (@Sendable (
            VoiceTutorLocalSpeechCaptureDiagnostic, VoiceTutorLocalSpeechCaptureSnapshot
        ) -> Void)? = nil,
        onFailure: (@Sendable (VoiceTutorSileroError) -> Void)? = nil
    ) {
        self.recordingTap = recordingTap
        self.onActivity = onActivity
        self.onDiagnostic = onDiagnostic
        self.onFailure = onFailure
        super.init()
        if let speechScorer {
            speechPipeline = VoiceTutorSileroAudioPipeline(
                scorer: speechScorer,
                onProbability: { [weak self] probability, generation in
                    self?.processSpeechProbability(probability, generation: generation)
                },
                onFailure: { [weak self] error, generation in
                    self?.speechProcessingFailed(error, generation: generation)
                }
            )
        }
    }

    func audioProcessingInitialize(sampleRate: Int, channels: Int) {
        lock.lock()
        guard !isClosed else { lock.unlock(); return }
        initializationCount += 1
        let nextSampleRate = sampleRate > 0 ? Double(sampleRate) : 0
        let nextChannelCount = channels > 0 ? channels : 0
        if self.sampleRate != nextSampleRate || inputChannelCount != nextChannelCount {
            if let event = detector.resetAcousticEvidence() { onActivity(event) }
            resetPipelineLocked()
        }
        self.sampleRate = nextSampleRate
        inputChannelCount = nextChannelCount
        let firstInitialization = !didReportInitialization && sampleRate > 0 && channels > 0
        if firstInitialization { didReportInitialization = true }
        let initialSnapshot = firstInitialization ? snapshotLocked() : nil
        lock.unlock()
        recordingTap?.audioProcessingInitialize(sampleRate: sampleRate, channels: channels)
        if let initialSnapshot { onDiagnostic?(.initialized, initialSnapshot) }
    }

    func audioProcessingProcess(audioBuffer: LKRTCAudioBuffer) {
        recordingTap?.audioProcessingProcess(audioBuffer: audioBuffer)
        var firstInputSnapshot: VoiceTutorLocalSpeechCaptureSnapshot?
        lock.lock()
        defer {
            lock.unlock()
            // At most one diagnostic per kind for this tap's entire lifetime.
            // Production only enqueues this metadata onto its diagnostic queue.
            if let firstInputSnapshot { onDiagnostic?(.firstValidInputFrame, firstInputSnapshot) }
        }
        guard !isClosed else { return }
        processedBufferCount += 1
        let frames = Int(audioBuffer.frames)
        let channels = Int(audioBuffer.channels)
        guard sampleRate > 0, frames > 0, frames <= 48_000,
              channels > 0, channels <= 32 else {
            if detector.isEnabled { failLocked(.invalidAudio) }
            return
        }
        let duration = Double(frames) / sampleRate
        guard duration.isFinite, duration > 0, duration <= 0.25 else {
            if detector.isEnabled { failLocked(.invalidAudio) }
            return
        }
        var strongestChannel = 0
        var strongestRMS = -1.0
        for channel in 0..<channels {
            let samples = UnsafeBufferPointer(start: audioBuffer.rawBuffer(forChannel: channel), count: frames)
            guard let level = VoiceTutorLocalSpeechDetector.normalizedRMS(
                samples: samples, scale: .webRTCFloatS16
            ) else {
                if detector.isEnabled { failLocked(.invalidAudio) }
                return
            }
            // RMS only selects a channel; it never classifies speech. A silent
            // secondary channel must not dilute genuine learner audio.
            if level > strongestRMS { strongestChannel = channel; strongestRMS = level }
        }
        validInputBufferCount += 1
        validInputFrameCount += frames
        if !didReportValidInput {
            didReportValidInput = true
            firstInputSnapshot = snapshotLocked()
        }
        guard detector.isEnabled else { return }
        // A scorer-less tap is supported for metadata-only native probes with
        // their gate closed. Live input must never silently fall back to RMS.
        guard let speechPipeline else { failLocked(.bundledModelMissing); return }
        let pointer = audioBuffer.rawBuffer(forChannel: strongestChannel)
        guard let samples = VoiceTutorAudioProcessingTap.normalizedRecordingSamples(
            UnsafeBufferPointer(start: pointer, count: frames)
        ) else { failLocked(.invalidAudio); return }
        speechPipeline.enqueue(samples: samples, sampleRate: sampleRate, generation: captureGeneration)
    }

    private func processSpeechProbability(_ probability: Double, generation: UInt64) {
        lock.lock()
        defer { lock.unlock() }
        guard !isClosed, !didReportSpeechFailure, detector.isEnabled,
              generation == captureGeneration else { return }
        speechInferenceCount += 1
        if let event = detector.process(
            speechProbability: probability, duration: VoiceTutorSileroSpeechScorer.frameDuration
        ) {
            // Nonblocking bounded-stream yield under the gate lock: mute cannot
            // reorder stop N before start N. No network or disk work runs here.
            onActivity(event)
        }
    }

    func updateGate(mediaReady: Bool, muted: Bool) {
        lock.lock()
        defer { lock.unlock() }
        guard !isClosed, !didReportSpeechFailure else { return }
        let wasEnabled = detector.isEnabled
        if let event = detector.updateGate(mediaReady: mediaReady, muted: muted) {
            onActivity(event)
        }
        if wasEnabled != detector.isEnabled { resetPipelineLocked() }
    }

    private func resetPipelineLocked() {
        captureGeneration &+= 1
        speechPipeline?.reset(generation: captureGeneration)
    }

    private func speechProcessingFailed(_ error: VoiceTutorSileroError, generation: UInt64) {
        lock.lock()
        defer { lock.unlock() }
        guard generation == captureGeneration else { return }
        failLocked(error)
    }

    private func failLocked(_ error: VoiceTutorSileroError) {
        guard !isClosed, !didReportSpeechFailure else { return }
        didReportSpeechFailure = true
        detector.close()
        speechPipeline?.close()
        failureDelivery.async { [weak self] in
            guard let self else { return }
            lock.lock()
            let shouldReport = !isClosed && didReportSpeechFailure
            lock.unlock()
            if shouldReport { onFailure?(error) }
        }
    }

    func close() {
        lock.lock()
        isClosed = true
        detector.close()
        speechPipeline?.close()
        sampleRate = 0
        inputChannelCount = 0
        lock.unlock()
    }

    func snapshot() -> VoiceTutorLocalSpeechCaptureSnapshot {
        lock.lock()
        defer { lock.unlock() }
        return snapshotLocked()
    }

    private func snapshotLocked() -> VoiceTutorLocalSpeechCaptureSnapshot {
        VoiceTutorLocalSpeechCaptureSnapshot(
            initializationCount: initializationCount,
            processedBufferCount: processedBufferCount,
            validInputBufferCount: validInputBufferCount,
            validInputFrameCount: validInputFrameCount,
            speechInferenceCount: speechInferenceCount,
            sampleRate: sampleRate,
            inputChannelCount: inputChannelCount,
            processingGeneration: captureGeneration,
            gateEnabled: detector.isEnabled,
            isClosed: isClosed
        )
    }

    func audioProcessingRelease() { recordingTap?.audioProcessingRelease() }
}

final class VoiceTutorRemoteAudioRenderer: NSObject, LKRTCAudioRenderer, @unchecked Sendable {
    var onRenderedPCM: (@Sendable (TimeInterval) -> Void)?
    var onRenderedBuffer: (@Sendable (Int, Bool, TimeInterval, TimeInterval) -> Void)?
    var onAcousticBuffer: (@Sendable (VoiceTutorInterruptionAudioLevel?, TimeInterval, TimeInterval) -> Void)?

    func render(pcmBuffer: AVAudioPCMBuffer) {
        guard pcmBuffer.frameLength > 0 else { return }
        let uptime = ProcessInfo.processInfo.systemUptime
        let sampleRate = pcmBuffer.format.sampleRate
        let duration = sampleRate.isFinite && sampleRate > 0
            ? TimeInterval(pcmBuffer.frameLength) / sampleRate
            : 0
        let containsAudio = Self.containsNonzeroSamples(pcmBuffer)
        onRenderedBuffer?(Int(pcmBuffer.frameLength), containsAudio, duration, uptime)
        onAcousticBuffer?(Self.interruptionAudioLevel(pcmBuffer), duration, uptime)
        // This callback is an activity/diagnostic hint only. NetEq may render
        // nonzero comfort/concealment noise even after server audio has ended;
        // neither nonzero PCM nor its absence is a response-completion fence.
        guard containsAudio else { return }
        onRenderedPCM?(uptime)
    }

    /// Inspect in-place without retaining audio. Unknown/non-finite samples are
    /// not silence. Both RMS and peak protect quiet consonants and brief peaks.
    static func interruptionAudioLevel(_ buffer: AVAudioPCMBuffer) -> VoiceTutorInterruptionAudioLevel? {
        let frames = Int(buffer.frameLength)
        let channels = Int(buffer.format.channelCount)
        guard frames > 0, channels > 0, channels <= 8 else { return nil }
        let interleaved = buffer.format.isInterleaved
        func measure<Sample>(
            _ channelData: UnsafePointer<UnsafeMutablePointer<Sample>>?,
            normalize: (Sample) -> Double
        ) -> VoiceTutorInterruptionAudioLevel? {
            guard let channelData else { return nil }
            let bufferCount = interleaved ? 1 : channels
            let samplesPerBuffer = frames * (interleaved ? channels : 1)
            var maximumRMS = 0.0
            var peak = 0.0
            for channel in 0..<bufferCount {
                var sumOfSquares = 0.0
                for index in 0..<samplesPerBuffer {
                    let sample = normalize(channelData[channel][index])
                    guard sample.isFinite else { return nil }
                    sumOfSquares += sample * sample
                    peak = max(peak, abs(sample))
                }
                maximumRMS = max(maximumRMS, sqrt(sumOfSquares / Double(samplesPerBuffer)))
            }
            return .init(rms: maximumRMS, peak: peak)
        }
        switch buffer.format.commonFormat {
        case .pcmFormatInt16: return measure(buffer.int16ChannelData) { Double($0) / 32_768 }
        case .pcmFormatInt32: return measure(buffer.int32ChannelData) { Double($0) / 2_147_483_648 }
        case .pcmFormatFloat32: return measure(buffer.floatChannelData) { Double($0) }
        default: return nil
        }
    }

    static func containsNonzeroSamples(_ buffer: AVAudioPCMBuffer) -> Bool {
        let frames = Int(buffer.frameLength)
        let channels = Int(buffer.format.channelCount)
        guard frames > 0, channels > 0 else { return false }
        let interleaved = buffer.format.isInterleaved
        func containsAudio<Sample: Numeric>(
            _ channelData: UnsafePointer<UnsafeMutablePointer<Sample>>?
        ) -> Bool {
            // Preserve uninspectable audio rather than treating it as silence.
            guard let channelData else { return true }
            let bufferCount = interleaved ? 1 : channels
            let samplesPerBuffer = frames * (interleaved ? channels : 1)
            for channel in 0..<bufferCount {
                for sample in 0..<samplesPerBuffer where channelData[channel][sample] != 0 {
                    return true
                }
            }
            return false
        }
        // Keep quiet samples observable; this inspection never changes samples
        // or controls the continuous remote track's playback.
        switch buffer.format.commonFormat {
        case .pcmFormatInt16: return containsAudio(buffer.int16ChannelData)
        case .pcmFormatInt32: return containsAudio(buffer.int32ChannelData)
        case .pcmFormatFloat32: return containsAudio(buffer.floatChannelData)
        default: return true
        }
    }
}

/// A bounded snapshot of the active Core Audio output queue. WebRTC exposes
/// aggregate receiver statistics, but neither those statistics nor
/// `LKRTCAudioRenderer` identify the final sample of one response on a
/// continuous RTP track. These device values are therefore used only after
/// the provider has stopped the exact spoken-end response. The snapshot does
/// not claim that Core Audio or the physical speaker exposes an exact drain
/// acknowledgement.
struct VoiceTutorLocalPlayoutDrainProfile: Equatable, Sendable {
    static let maximumOutputLatencySeconds: TimeInterval = 1
    static let maximumIOBufferDurationSeconds: TimeInterval = 0.25
    static let maximumRenderQuantumSeconds: TimeInterval = 0.25

    let outputLatencySeconds: TimeInterval
    let outputIOBufferDurationSeconds: TimeInterval

    init(outputLatencySeconds: TimeInterval, outputIOBufferDurationSeconds: TimeInterval) {
        self.outputLatencySeconds = Self.bounded(
            outputLatencySeconds,
            maximum: Self.maximumOutputLatencySeconds
        )
        self.outputIOBufferDurationSeconds = Self.bounded(
            outputIOBufferDurationSeconds,
            maximum: Self.maximumIOBufferDurationSeconds
        )
    }

    func queueDrainSeconds(renderQuantumSeconds: TimeInterval) -> TimeInterval {
        outputLatencySeconds + outputIOBufferDurationSeconds + Self.bounded(
            renderQuantumSeconds,
            maximum: Self.maximumRenderQuantumSeconds
        )
    }

    private static func bounded(_ value: TimeInterval, maximum: TimeInterval) -> TimeInterval {
        guard value.isFinite, value > 0 else { return 0 }
        return min(value, maximum)
    }
}

/// Identifies one server-completed response without treating the remote RTP
/// track's continuous comfort-noise callbacks as response boundaries.
struct VoiceTutorLocalPlayoutTailToken: Equatable, Sendable {
    let responseID: String
    let generation: UInt64
    let providerStoppedAtUptime: TimeInterval
    let drainProfile: VoiceTutorLocalPlayoutDrainProfile
}

/// A small deterministic state machine around the native renderer. The provider
/// can say its output buffer stopped before NetEq/Core Audio has rendered the
/// last packet on this device. There is no public WebRTC "speaker drained" API,
/// and silence is not usable because the RTP track emits concealment/comfort
/// noise. Instead, preserve the exact response generation and, only when the
/// server later verifies a spoken terminal response, keep the output path alive
/// through a hard provider-stop-based network-tail window plus the measured
/// Core Audio queue.
/// This is deliberately not an acoustic proof: a packet arriving outside the
/// bounded window cannot be attributed to this response by the SDK.
struct VoiceTutorLocalPlayoutTailState: Equatable {
    static let spokenEndNetworkTailWindowSeconds: TimeInterval = 1.25

    private(set) var generation: UInt64 = 0
    private(set) var activeResponseID: String?
    private(set) var sealedToken: VoiceTutorLocalPlayoutTailToken?
    private(set) var maximumTailRenderQuantumSeconds: TimeInterval = 0

    mutating func responseStarted(_ responseID: String?) {
        guard let responseID, !responseID.isEmpty, activeResponseID != responseID else { return }
        generation &+= 1
        activeResponseID = responseID
        sealedToken = nil
        maximumTailRenderQuantumSeconds = 0
    }

    mutating func rendered(at uptime: TimeInterval, duration: TimeInterval = 0) {
        guard uptime.isFinite, uptime >= 0,
              let token = sealedToken,
              token.generation == generation,
              uptime >= token.providerStoppedAtUptime else { return }
        let networkTailDeadline = token.providerStoppedAtUptime
            + Self.spokenEndNetworkTailWindowSeconds
        if uptime <= networkTailDeadline, duration.isFinite, duration > 0 {
            maximumTailRenderQuantumSeconds = max(
                maximumTailRenderQuantumSeconds,
                min(duration, VoiceTutorLocalPlayoutDrainProfile.maximumRenderQuantumSeconds)
            )
        }
    }

    mutating func responseCompleted(
        _ responseID: String,
        at uptime: TimeInterval,
        drainProfile: VoiceTutorLocalPlayoutDrainProfile = .init(
            outputLatencySeconds: 0,
            outputIOBufferDurationSeconds: 0
        )
    ) -> VoiceTutorLocalPlayoutTailToken? {
        guard !responseID.isEmpty, uptime.isFinite, uptime >= 0,
              activeResponseID == responseID else { return nil }
        let token = VoiceTutorLocalPlayoutTailToken(
            responseID: responseID,
            generation: generation,
            providerStoppedAtUptime: uptime,
            drainProfile: drainProfile
        )
        activeResponseID = nil
        sealedToken = token
        maximumTailRenderQuantumSeconds = 0
        return token
    }

    @discardableResult
    mutating func abandonResponse(_ responseID: String) -> Bool {
        guard !responseID.isEmpty, activeResponseID == responseID else { return false }
        invalidate()
        return true
    }

    /// Returns nil when the exact generation is drained, superseded, or its
    /// bounded fallback has elapsed. A positive value is safe to sleep/poll.
    func remainingWait(
        for token: VoiceTutorLocalPlayoutTailToken,
        now: TimeInterval
    ) -> TimeInterval? {
        guard now.isFinite,
              sealedToken == token,
              generation == token.generation else { return nil }
        // The first post-stop callback can be continuous comfort noise. It must
        // neither shorten nor extend the fence. Anchor the hard network window
        // to provider stop, then drain only the bounded measured device queue.
        let renderDeadline = token.providerStoppedAtUptime
            + Self.spokenEndNetworkTailWindowSeconds
            + token.drainProfile.queueDrainSeconds(
                renderQuantumSeconds: maximumTailRenderQuantumSeconds
            )
        return now < renderDeadline ? max(0, renderDeadline - now) : nil
    }

    mutating func invalidate() {
        generation &+= 1
        activeResponseID = nil
        sealedToken = nil
        maximumTailRenderQuantumSeconds = 0
    }
}

/// Stops an interrupted answer at the remote source gain while WebRTC keeps
/// consuming its RTP queue. Only a different accepted response can reopen it;
/// delayed events for interrupted responses must never replay their audio.
struct VoiceTutorLocalPlayoutInterruptionState: Equatable {
    private(set) var activeResponseID: String?
    private(set) var isMuted = false
    private var observedResponseIDs: [String] = []
    private var interruptedResponseIDs: [String] = []

    @discardableResult
    mutating func responseStarted(_ responseID: String?) -> Bool {
        guard let responseID, !responseID.isEmpty,
              !interruptedResponseIDs.contains(responseID),
              activeResponseID == responseID || !observedResponseIDs.contains(responseID) else { return false }
        Self.remember(responseID, in: &observedResponseIDs)
        activeResponseID = responseID
        isMuted = false
        return true
    }

    @discardableResult
    mutating func interruptResponse(_ responseID: String) -> Bool {
        guard !responseID.isEmpty,
              activeResponseID == nil || activeResponseID == responseID
                || !observedResponseIDs.contains(responseID) else { return false }
        // The server can cancel a response before its first audible event has
        // announced that ID to iOS. Remember that unseen ID as muted too.
        Self.remember(responseID, in: &observedResponseIDs)
        Self.remember(responseID, in: &interruptedResponseIDs)
        activeResponseID = responseID
        isMuted = true
        return true
    }

    private static func remember(_ responseID: String, in responseIDs: inout [String]) {
        guard !responseIDs.contains(responseID) else { return }
        responseIDs.append(responseID)
        if responseIDs.count > 1_024 {
            responseIDs.removeFirst(responseIDs.count - 1_024)
        }
    }
}

final class VoiceTutorWebRTCTransport: NSObject, @unchecked Sendable {
    var onRenderedPCM: (@Sendable (TimeInterval) -> Void)? {
        didSet { remoteRenderer.onRenderedPCM = onRenderedPCM }
    }
    var onInterruption: (@Sendable () -> Void)?
    var onConnectionFailure: (@Sendable () -> Void)?
    var onLocalSpeechActivity: (@Sendable (VoiceTutorLocalSpeechEvent) -> Void)?
    var onDiagnostic: (@Sendable (String) -> Void)?

    private let networkSession: URLSession
    private let diagnosticQueue = DispatchQueue(label: "com.buddystudy.voice.media-diagnostics")
    private let diagnosticLock = NSLock()
    private let createdUptime = ProcessInfo.processInfo.systemUptime
    private var renderedBufferCount = 0
    private var renderedFrameCount = 0
    private var nonzeroBufferCount = 0
    private var localPlayoutTailState = VoiceTutorLocalPlayoutTailState()
    private let audioSessionOwnerID = UUID()
    private let remoteRenderer = VoiceTutorRemoteAudioRenderer()
    private var captureTap: VoiceTutorLocalSpeechCaptureTap?
    private var renderTap: VoiceTutorAudioProcessingTap?
    private var audioProcessingModule: LKRTCDefaultAudioProcessingModule?
    private var factory: LKRTCPeerConnectionFactory?
    private var peerConnection: LKRTCPeerConnection?
    private var localAudioTrack: LKRTCAudioTrack?
    private var remoteAudioTrack: LKRTCAudioTrack?
    private var localPlayoutInterruptionState = VoiceTutorLocalPlayoutInterruptionState()
    private var interruptionBoundaryState = VoiceTutorInterruptionBoundaryState()
    private var interruptionObserver: NSObjectProtocol?
    private let stateLock = NSLock()
    private var isClosed = false
    private var sessionMediaReady = false
    private var microphoneMuted = false
    private var microphoneInputClosed = false
    private var preparedSpeechScorer: VoiceTutorSileroSpeechScorer?
    private static let audioSessionOwnershipLock = NSLock()
    private nonisolated(unsafe) static var activeAudioSessionOwnerID: UUID?

    override init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.waitsForConnectivity = true
        configuration.timeoutIntervalForRequest = 30
        networkSession = URLSession(configuration: configuration)
        super.init()
        remoteRenderer.onRenderedPCM = { [weak self] uptime in
            self?.onRenderedPCM?(uptime)
        }
        remoteRenderer.onRenderedBuffer = { [weak self] frames, containsAudio, duration, uptime in
            self?.recordRenderedBuffer(
                frames: frames,
                containsAudio: containsAudio,
                duration: duration,
                uptime: uptime
            )
        }
        remoteRenderer.onAcousticBuffer = { [weak self] level, duration, uptime in
            guard let self else { return }
            self.diagnosticLock.lock()
            self.interruptionBoundaryState.observe(level: level, duration: duration, at: uptime)
            self.diagnosticLock.unlock()
        }
    }

    /// The view model can preflight the local model before reserving an app
    /// session. One fresh recurrent scorer is consumed by exactly this attempt.
    convenience init(preparedSpeechScorer: VoiceTutorSileroSpeechScorer) {
        self.init()
        self.preparedSpeechScorer = preparedSpeechScorer
    }

    deinit {
        stateLock.lock()
        let observer = interruptionObserver
        interruptionObserver = nil
        stateLock.unlock()
        if let observer {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    func connect(
        sdpRequest: URLRequest,
        recorder: VoiceTutorSessionRecorder?
    ) async throws {
        guard let onLocalSpeechActivity else { throw VoiceTutorWebRTCError.speechActivityUnavailable }
        try ensureOpen()
        let speechScorer: VoiceTutorSileroSpeechScorer
        do {
            if let prepared = takePreparedSpeechScorer() { speechScorer = prepared }
            else { speechScorer = try await VoiceTutorSileroSpeechScorer.prepareBundled() }
            try ensureOpen()
        } catch {
            if let failure = error as? VoiceTutorSileroError {
                emitMediaDiagnostic("speech_model_preflight_failed_\(failure.rawValue)")
            }
            throw error
        }
        emitMediaDiagnostic("speech_model_ready_silero_v6")
        try configureAudioSession()
        try installInterruptionObserver()
        _ = LKRTCInitializeSSL()
        try ensureOpen()

        let nextCaptureTap = VoiceTutorLocalSpeechCaptureTap(
            recordingTap: recorder.map { VoiceTutorAudioProcessingTap(participant: .learner, recorder: $0) },
            speechScorer: speechScorer,
            onActivity: onLocalSpeechActivity,
            onDiagnostic: { [weak self] event, snapshot in
                self?.recordCaptureDiagnostic(event, snapshot: snapshot)
            },
            onFailure: { [weak self] error in
                self?.recordSpeechProcessingFailure(error)
            }
        )
        let nextRenderTap = recorder.map {
            VoiceTutorAudioProcessingTap(participant: .tutor, recorder: $0)
        }
        let processingModule = try VoiceTutorAudioProcessingModuleFactory.make(
            captureDelegate: nextCaptureTap,
            renderDelegate: nextRenderTap
        )
        let factory = LKRTCPeerConnectionFactory(
            audioDeviceModuleType: .audioEngine,
            bypassVoiceProcessing: false,
            encoderFactory: nil,
            decoderFactory: nil,
            audioProcessingModule: processingModule
        )
        try ensureOpen()

        let configuration = LKRTCConfiguration()
        configuration.sdpSemantics = .unifiedPlan
        let constraints = LKRTCMediaConstraints(
            mandatoryConstraints: ["OfferToReceiveAudio": "true"],
            optionalConstraints: nil
        )
        guard let peer = factory.peerConnection(
            with: configuration,
            constraints: constraints,
            delegate: self
        ) else {
            throw VoiceTutorWebRTCError.peerConnectionCreationFailed
        }
        var resourcesInstalled = false
        defer {
            if !resourcesInstalled {
                peer.delegate = nil
                peer.close()
            }
        }
        try ensureOpen()

        let source = factory.audioSource(with: nil)
        let localTrack = factory.audioTrack(with: source, trackId: "buddystudy-microphone")
        guard peer.add(localTrack, streamIds: ["buddystudy-voice"]) != nil else {
            throw VoiceTutorWebRTCError.localTrackCreationFailed
        }
        try installConnectionResources(
            captureTap: nextCaptureTap,
            renderTap: nextRenderTap,
            processingModule: processingModule,
            factory: factory,
            peerConnection: peer,
            localAudioTrack: localTrack
        )
        resourcesInstalled = true

        let offer = try await createOffer(peer: peer, constraints: constraints)
        try ensureOpen()
        // Keep a validated value snapshot before handing the description to
        // WebRTC. Teardown must never turn a later SDP read into an empty POST.
        let offerSDP = try Self.validatedOfferSDP(offer.sdp)
        try await setLocalDescription(offer, peer: peer)
        try ensureOpen()
        let answerSDP = try await exchangeSDP(offerSDP, request: sdpRequest)
        try ensureOpen()
        let answer = LKRTCSessionDescription(type: .answer, sdp: answerSDP)
        try await setRemoteDescription(answer, peer: peer)
        try ensureOpen()
        attachRemoteAudioTrackIfPresent(on: peer)
        // SDP installation is not media readiness. Open the backend control
        // socket only after ICE + DTLS are connected so its opening tutor turn
        // cannot run ahead of the iPhone's media path.
        try await waitForMediaConnection(peer: peer)
        emitMediaDiagnostic("media_connected")
    }

    private func takePreparedSpeechScorer() -> VoiceTutorSileroSpeechScorer? {
        stateLock.lock()
        defer { stateLock.unlock() }
        let prepared = preparedSpeechScorer
        preparedSpeechScorer = nil
        return prepared
    }

    @discardableResult
    func setMuted(_ muted: Bool) -> Bool {
        stateLock.lock()
        guard !isClosed, !microphoneInputClosed || muted else {
            stateLock.unlock()
            return false
        }
        microphoneMuted = muted
        captureTap?.updateGate(mediaReady: sessionMediaReady, muted: muted)
        let peerFactory = factory
        stateLock.unlock()
        guard let device = peerFactory?.audioDeviceModule else { return false }
        // Pausing must verify the native capture state, not merely the Silero
        // gate. Never touch the tutor track or stop the continuous output engine.
        return device.setMicrophoneMuted(muted) == 0 && device.isMicrophoneMuted == muted
    }

    /// Permanently closes only this call's learner-input path while preserving
    /// the remote tutor track. Disabling the sender track is the fail-closed
    /// boundary when the native audio-device mute operation is unavailable or
    /// fails during a terminal server transition.
    @discardableResult
    func closeMicrophoneInput() -> Bool {
        stateLock.lock()
        guard !isClosed else { stateLock.unlock(); return true }
        microphoneInputClosed = true
        microphoneMuted = true
        sessionMediaReady = false
        captureTap?.updateGate(mediaReady: false, muted: true)
        let track = localAudioTrack
        let device = factory?.audioDeviceModule
        stateLock.unlock()

        track?.isEnabled = false
        _ = device?.setMicrophoneMuted(true)
        return track?.isEnabled != true
    }

    func setSessionMediaReady() {
        stateLock.lock()
        guard !isClosed, !microphoneInputClosed else { stateLock.unlock(); return }
        sessionMediaReady = true
        captureTap?.updateGate(mediaReady: true, muted: microphoneMuted)
        let track = localAudioTrack
        stateLock.unlock()
        track?.isEnabled = true
    }

    /// Binds native renderer evidence to the exact provider response generation.
    /// The backend admits this replacement only after clearing interrupted
    /// output. Restoring source gain keeps the same continuous RTP track alive.
    func beginLocalPlayoutResponse(responseID: String?) {
        stateLock.lock()
        guard !isClosed, localPlayoutInterruptionState.responseStarted(responseID) else {
            stateLock.unlock()
            return
        }
        remoteAudioTrack?.source.volume = 1
        diagnosticLock.lock()
        localPlayoutTailState.responseStarted(responseID)
        interruptionBoundaryState.responseStarted(responseID)
        diagnosticLock.unlock()
        stateLock.unlock()
    }

    /// Silence the old answer immediately without pausing the renderer or its
    /// queue. Muting a source gain still consumes buffered packets, so resuming
    /// a later answer cannot replay an audio queue held by a stopped engine.
    @discardableResult
    func interruptLocalPlayoutResponse(responseID: String) -> Bool {
        stateLock.lock()
        guard !isClosed, localPlayoutInterruptionState.interruptResponse(responseID) else {
            stateLock.unlock()
            return false
        }
        remoteAudioTrack?.source.volume = 0
        diagnosticLock.lock()
        _ = localPlayoutTailState.abandonResponse(responseID)
        interruptionBoundaryState.invalidate(responseID: responseID)
        diagnosticLock.unlock()
        stateLock.unlock()
        emitMediaDiagnostic("tutor_playout_interrupted")
        return true
    }

    /// Call before the ordered learner-start/pause control is sent. Microphone
    /// capture and native RTP playout continue during this bounded grace. No
    /// delayed cancel is retained after this call: the caller still owns the
    /// exact attempt and interruption operation.
    func waitForInterruptionBoundary(responseID: String) async {
        let requestedAt = ProcessInfo.processInfo.systemUptime
        guard let token = interruptionBoundaryToken(responseID: responseID, at: requestedAt) else { return }
        while !Task.isCancelled {
            let now = ProcessInfo.processInfo.systemUptime
            switch interruptionBoundaryDecision(for: token, now: now) {
            case .wait(let remaining):
                do {
                    try await Task.sleep(nanoseconds: UInt64(min(remaining, 0.01) * 1_000_000_000))
                } catch { return }
            case .quietGap:
                emitMediaDiagnostic("interruption_boundary_quiet_gap")
                return
            case .deadline:
                emitMediaDiagnostic("interruption_boundary_deadline")
                return
            case .superseded:
                return
            }
        }
    }

    private func interruptionBoundaryToken(
        responseID: String, at uptime: TimeInterval
    ) -> VoiceTutorInterruptionBoundaryToken? {
        diagnosticLock.lock()
        defer { diagnosticLock.unlock() }
        return interruptionBoundaryState.request(responseID: responseID, at: uptime)
    }

    private func interruptionBoundaryDecision(
        for token: VoiceTutorInterruptionBoundaryToken, now: TimeInterval
    ) -> VoiceTutorInterruptionBoundaryState.Decision {
        diagnosticLock.lock()
        defer { diagnosticLock.unlock() }
        return interruptionBoundaryState.decision(for: token, now: now)
    }

    func sealLocalPlayoutResponse(responseID: String) -> VoiceTutorLocalPlayoutTailToken? {
        let audioSession = AVAudioSession.sharedInstance()
        let drainProfile = VoiceTutorLocalPlayoutDrainProfile(
            outputLatencySeconds: audioSession.outputLatency,
            outputIOBufferDurationSeconds: audioSession.ioBufferDuration
        )
        diagnosticLock.lock()
        let token = localPlayoutTailState.responseCompleted(
            responseID,
            at: ProcessInfo.processInfo.systemUptime,
            drainProfile: drainProfile
        )
        diagnosticLock.unlock()
        return token
    }

    /// Invalidates only response-attribution state. The continuous WebRTC
    /// output track remains connected and is never muted, cleared, or stopped.
    @discardableResult
    func abandonLocalPlayoutResponse(responseID: String) -> Bool {
        diagnosticLock.lock()
        let abandoned = localPlayoutTailState.abandonResponse(responseID)
        interruptionBoundaryState.invalidate(responseID: responseID)
        diagnosticLock.unlock()
        return abandoned
    }

    /// Keeps native playout alive for an exact, already server-completed response.
    /// A new response, transport close, task cancellation, or the hard bound ends
    /// the wait. No audio content or energy threshold participates in the fence.
    func waitForLocalPlayoutTail(_ token: VoiceTutorLocalPlayoutTailToken) async {
        while !Task.isCancelled {
            let now = ProcessInfo.processInfo.systemUptime
            let remaining = localPlayoutTailRemainingWait(for: token, now: now)
            guard let remaining, remaining > 0 else { return }
            let sleepSeconds = min(remaining, 0.02)
            do {
                try await Task.sleep(
                    nanoseconds: UInt64((sleepSeconds * 1_000_000_000).rounded(.up))
                )
            } catch {
                return
            }
        }
    }

    private func localPlayoutTailRemainingWait(
        for token: VoiceTutorLocalPlayoutTailToken,
        now: TimeInterval
    ) -> TimeInterval? {
        diagnosticLock.lock()
        defer { diagnosticLock.unlock() }
        return localPlayoutTailState.remainingWait(for: token, now: now)
    }

    func close() {
        emitMediaDiagnostic("media_close_requested")
        let remoteTrack: LKRTCAudioTrack?
        let peer: LKRTCPeerConnection?
        let localTrack: LKRTCAudioTrack?
        let peerFactory: LKRTCPeerConnectionFactory?
        let processingModule: LKRTCDefaultAudioProcessingModule?
        let nextCaptureTap: VoiceTutorLocalSpeechCaptureTap?
        let nextRenderTap: VoiceTutorAudioProcessingTap?
        let observer: NSObjectProtocol?
        let shouldDeactivateAudioSession: Bool

        diagnosticLock.lock()
        localPlayoutTailState.invalidate()
        interruptionBoundaryState.invalidate()
        diagnosticLock.unlock()

        Self.audioSessionOwnershipLock.lock()
        stateLock.lock()
        isClosed = true
        sessionMediaReady = false
        microphoneMuted = true
        microphoneInputClosed = true
        preparedSpeechScorer = nil
        captureTap?.close()
        shouldDeactivateAudioSession = Self.activeAudioSessionOwnerID == audioSessionOwnerID
        if shouldDeactivateAudioSession {
            Self.activeAudioSessionOwnerID = nil
        }
        remoteTrack = remoteAudioTrack
        remoteAudioTrack = nil
        peer = peerConnection
        peerConnection = nil
        localTrack = localAudioTrack
        localAudioTrack = nil
        peerFactory = factory
        factory = nil
        processingModule = audioProcessingModule
        audioProcessingModule = nil
        nextCaptureTap = captureTap
        captureTap = nil
        nextRenderTap = renderTap
        renderTap = nil
        observer = interruptionObserver
        interruptionObserver = nil
        stateLock.unlock()
        if shouldDeactivateAudioSession {
            try? AVAudioSession.sharedInstance().setActive(
                false,
                options: [.notifyOthersOnDeactivation]
            )
        }
        Self.audioSessionOwnershipLock.unlock()

        withExtendedLifetime(
            (localTrack, peerFactory, processingModule, nextCaptureTap, nextRenderTap)
        ) {
            remoteTrack?.remove(remoteRenderer)
            peer?.delegate = nil
            peer?.close()
        }
        if let observer {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    private func configureAudioSession() throws {
        Self.audioSessionOwnershipLock.lock()
        defer { Self.audioSessionOwnershipLock.unlock() }
        stateLock.lock()
        defer { stateLock.unlock() }
        if isClosed {
            throw CancellationError()
        }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(
            .playAndRecord,
            mode: .voiceChat,
            options: [.defaultToSpeaker, .allowBluetoothHFP]
        )
        try session.setPreferredIOBufferDuration(0.01)
        try session.setActive(true)
        Self.activeAudioSessionOwnerID = audioSessionOwnerID
    }

    private func installInterruptionObserver() throws {
        let observer = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance(),
            queue: nil
        ) { [weak self] notification in
            guard VoiceTutorAudioSessionInterruption.began(notification) else {
                return
            }
            self?.onInterruption?()
        }
        stateLock.lock()
        guard !isClosed else {
            stateLock.unlock()
            NotificationCenter.default.removeObserver(observer)
            throw CancellationError()
        }
        let previousObserver = interruptionObserver
        interruptionObserver = observer
        stateLock.unlock()
        if let previousObserver {
            NotificationCenter.default.removeObserver(previousObserver)
        }
    }

    private func installConnectionResources(
        captureTap: VoiceTutorLocalSpeechCaptureTap?,
        renderTap: VoiceTutorAudioProcessingTap?,
        processingModule: LKRTCDefaultAudioProcessingModule,
        factory: LKRTCPeerConnectionFactory,
        peerConnection: LKRTCPeerConnection,
        localAudioTrack: LKRTCAudioTrack
    ) throws {
        stateLock.lock()
        guard !isClosed, self.peerConnection == nil, self.factory == nil else {
            stateLock.unlock()
            throw CancellationError()
        }
        localAudioTrack.isEnabled = sessionMediaReady && !microphoneInputClosed
        self.captureTap = captureTap
        captureTap?.updateGate(mediaReady: sessionMediaReady, muted: microphoneMuted)
        self.renderTap = renderTap
        audioProcessingModule = processingModule
        self.factory = factory
        self.peerConnection = peerConnection
        self.localAudioTrack = localAudioTrack
        stateLock.unlock()
    }

    private func createOffer(
        peer: LKRTCPeerConnection,
        constraints: LKRTCMediaConstraints
    ) async throws -> LKRTCSessionDescription {
        let boxed: VoiceTutorUncheckedSendable<LKRTCSessionDescription> = try await
            withCheckedThrowingContinuation { continuation in
            peer.offer(for: constraints) { description, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let description {
                    continuation.resume(
                        returning: VoiceTutorUncheckedSendable(value: description)
                    )
                } else {
                    continuation.resume(
                        throwing: VoiceTutorWebRTCError.offerCreationFailed
                    )
                }
            }
        }
        return boxed.value
    }

    private func setLocalDescription(
        _ description: LKRTCSessionDescription,
        peer: LKRTCPeerConnection
    ) async throws {
        try await withCheckedThrowingContinuation {
            (continuation: CheckedContinuation<Void, Error>) in
            peer.setLocalDescription(description) { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume(returning: ()) }
            }
        }
    }

    private func setRemoteDescription(
        _ description: LKRTCSessionDescription,
        peer: LKRTCPeerConnection
    ) async throws {
        try await withCheckedThrowingContinuation {
            (continuation: CheckedContinuation<Void, Error>) in
            peer.setRemoteDescription(description) { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume(returning: ()) }
            }
        }
    }

    private func exchangeSDP(_ offer: String, request: URLRequest) async throws -> String {
        let request = try Self.sdpExchangeRequest(offer: offer, authenticatedRequest: request)
        try ensureOpen()
        let (data, response) = try await networkSession.data(for: request)
        guard let response = response as? HTTPURLResponse else {
            throw VoiceTutorWebRTCError.invalidSDPResponse
        }
        guard (200..<300).contains(response.statusCode) else {
            throw VoiceTutorWebRTCError.sdpExchangeFailed(
                statusCode: response.statusCode,
                backendFailure: VoiceTutorWebRTCBackendFailure.decode(from: data)
            )
        }
        guard let answer = String(data: data, encoding: .utf8),
              answer.contains("v=0"),
              answer.utf8.count <= 65_536 else {
            throw VoiceTutorWebRTCError.invalidSDPResponse
        }
        return answer
    }

    private func waitForMediaConnection(peer: LKRTCPeerConnection) async throws {
        let startedAt = ProcessInfo.processInfo.systemUptime
        while true {
            try ensureOpen()
            switch Self.mediaReadiness(
                state: peer.connectionState,
                elapsedSeconds: ProcessInfo.processInfo.systemUptime - startedAt
            ) {
            case .connected:
                try ensureOpen()
                return
            case .failed:
                emitMediaDiagnostic("media_connection_failed")
                throw VoiceTutorWebRTCError.mediaConnectionFailed
            case .timedOut:
                emitMediaDiagnostic("media_connection_timed_out")
                throw VoiceTutorWebRTCError.mediaConnectionTimedOut
            case .waiting:
                // Cancellation wakes immediately; close() is observed within
                // one short poll, without retaining delegate continuations.
                try await Task.sleep(nanoseconds: 50_000_000)
            }
        }
    }

    static func mediaReadiness(
        state: LKRTCPeerConnectionState,
        elapsedSeconds: TimeInterval,
        timeoutSeconds: TimeInterval = 15
    ) -> VoiceTutorWebRTCMediaReadiness {
        if state == .connected { return .connected }
        if state == .failed || state == .closed { return .failed }
        if elapsedSeconds >= timeoutSeconds { return .timedOut }
        return .waiting
    }

    static func validatedOfferSDP(_ offer: String) throws -> String {
        let snapshot = String(decoding: offer.utf8, as: UTF8.self)
        let lines = snapshot.split(whereSeparator: \.isNewline).map(String.init)
        let media = lines.filter { $0.hasPrefix("m=") }
        guard snapshot.utf8.count <= 65_536,
              lines.first == "v=0",
              media.count == 1,
              let audio = media.first,
              audio.hasPrefix("m=audio "),
              !lines.contains(where: { $0.hasPrefix("a=sctp-") || $0.hasPrefix("a=sctpmap:") }) else {
            throw VoiceTutorWebRTCError.offerCreationFailed
        }
        return snapshot
    }

    static func sdpExchangeRequest(offer: String, authenticatedRequest: URLRequest) throws -> URLRequest {
        let offer = try validatedOfferSDP(offer)
        var request = VoiceTutorTurnProtocol.addingCapability(to: authenticatedRequest)
        request.httpMethod = "POST"
        request.setValue("application/sdp", forHTTPHeaderField: "Content-Type")
        request.setValue("application/sdp", forHTTPHeaderField: "Accept")
        request.setValue(nil, forHTTPHeaderField: "Content-Length")
        request.httpBodyStream = nil
        request.httpBody = Data(offer.utf8)
        return request
    }

    private func attachRemoteAudioTrackIfPresent(on peer: LKRTCPeerConnection) {
        for receiver in peer.receivers {
            if let track = receiver.track as? LKRTCAudioTrack {
                attachRemoteAudioTrack(track)
                return
            }
        }
    }

    private func attachRemoteAudioTrack(_ track: LKRTCAudioTrack) {
        stateLock.lock()
        defer { stateLock.unlock() }
        guard !isClosed, remoteAudioTrack !== track else { return }
        remoteAudioTrack?.remove(remoteRenderer)
        remoteAudioTrack = track
        track.source.volume = localPlayoutInterruptionState.isMuted ? 0 : 1
        track.add(remoteRenderer)
    }

    private func ensureOpen() throws {
        try Task.checkCancellation()
        stateLock.lock()
        let closed = isClosed
        stateLock.unlock()
        if closed {
            throw CancellationError()
        }
    }

    private func recordRenderedBuffer(
        frames: Int,
        containsAudio: Bool,
        duration: TimeInterval,
        uptime: TimeInterval
    ) {
        diagnosticLock.lock()
        renderedBufferCount += 1
        renderedFrameCount += frames
        if containsAudio { nonzeroBufferCount += 1 }
        localPlayoutTailState.rendered(at: uptime, duration: duration)
        let firstBuffer = renderedBufferCount == 1
        let firstNonzero = containsAudio && nonzeroBufferCount == 1
        diagnosticLock.unlock()
        guard firstBuffer || firstNonzero else { return }
        // Track removal may wait for this callback while holding stateLock.
        // Keep even snapshot collection off the render thread to avoid inversion.
        diagnosticQueue.async { [weak self] in
            if firstBuffer { self?.emitMediaDiagnostic("first_rendered_buffer", uptime: uptime) }
            if firstNonzero { self?.emitMediaDiagnostic("first_nonzero_render", uptime: uptime) }
        }
    }

    private func recordCaptureDiagnostic(
        _ event: VoiceTutorLocalSpeechCaptureDiagnostic,
        snapshot: VoiceTutorLocalSpeechCaptureSnapshot
    ) {
        let uptime = ProcessInfo.processInfo.systemUptime
        // Initialization can precede installation of the transport's resources.
        // Preserve that callback's metadata, without taking stateLock or touching
        // ADM getters, logging, or networking from WebRTC's processing thread.
        diagnosticQueue.async { [weak self] in
            self?.emitMediaDiagnostic(event.rawValue, uptime: uptime, captureSnapshot: snapshot)
        }
    }

    private func recordSpeechProcessingFailure(_ error: VoiceTutorSileroError) {
        // Capture/pipeline errors arrive on a non-realtime failure queue. Keep
        // ADM diagnostics and application callbacks off that queue as well.
        diagnosticQueue.async { [weak self] in
            guard let self else { return }
            stateLock.lock()
            let failureCallback = isClosed ? nil : onConnectionFailure
            stateLock.unlock()
            guard let failureCallback else { return }
            emitMediaDiagnostic("speech_model_failed_\(error.rawValue)")
            failureCallback()
        }
    }

    private func emitMediaDiagnostic(
        _ event: String,
        uptime: TimeInterval = ProcessInfo.processInfo.systemUptime,
        captureSnapshot: VoiceTutorLocalSpeechCaptureSnapshot? = nil
    ) {
        stateLock.lock()
        guard !isClosed, let callback = onDiagnostic else {
            stateLock.unlock()
            return
        }
        diagnosticLock.lock()
        let context = VoiceTutorUncheckedSendable(value: (
            factory: factory,
            track: remoteAudioTrack,
            buffers: renderedBufferCount,
            frames: renderedFrameCount,
            nonzero: nonzeroBufferCount,
            capture: captureSnapshot ?? captureTap?.snapshot()
        ))
        diagnosticLock.unlock()
        stateLock.unlock()
        let elapsedMs = Int(max(0, uptime - createdUptime) * 1_000)
        let session = AVAudioSession.sharedInstance()
        let category = session.category.rawValue
        let mode = session.mode.rawValue
        let ports = session.currentRoute.outputs.map { $0.portType.rawValue }.sorted().joined(separator: ",")
        let zeroVolume = session.outputVolume == 0
        // ADM getters synchronously visit WebRTC's worker thread. Never call
        // them from its realtime render callback or while holding stateLock.
        diagnosticQueue.async {
            let snapshot = context.value
            let device = snapshot.factory?.audioDeviceModule
            func flag(_ value: Bool?) -> String { value.map { $0 ? "1" : "0" } ?? "unknown" }
            var fields = [
                "event=\(event)", "elapsedMs=\(elapsedMs)",
                "buffers=\(snapshot.buffers)", "frames=\(snapshot.frames)", "nonzeroBuffers=\(snapshot.nonzero)",
                "admSnapshot=async", "playoutInitialized=\(flag(device?.isPlayoutInitialized))",
                "playing=\(flag(device?.isPlaying))", "engineRunning=\(flag(device?.isEngineRunning))",
                "remoteEnabled=\(flag(snapshot.track?.isEnabled))",
                "category=\(category)", "mode=\(mode)",
                "ports=\(ports.isEmpty ? "none" : ports)", "zeroVolume=\(zeroVolume ? 1 : 0)"
            ]
            if let capture = snapshot.capture {
                fields += [
                    "captureInitializations=\(capture.initializationCount)",
                    "captureBuffers=\(capture.processedBufferCount)",
                    "captureValidBuffers=\(capture.validInputBufferCount)",
                    "captureFrames=\(capture.validInputFrameCount)",
                    "captureSpeechInferences=\(capture.speechInferenceCount)",
                    "captureSampleRate=\(Int(capture.sampleRate))",
                    "captureChannels=\(capture.inputChannelCount)",
                    "captureGeneration=\(capture.processingGeneration)",
                    "captureGate=\(capture.gateEnabled ? 1 : 0)",
                    "captureClosed=\(capture.isClosed ? 1 : 0)"
                ]
            }
            callback(fields.joined(separator: " "))
        }
    }

}

extension VoiceTutorWebRTCTransport: LKRTCPeerConnectionDelegate {
    func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didChange stateChanged: LKRTCSignalingState
    ) {}

    func peerConnection(_ peerConnection: LKRTCPeerConnection, didAdd stream: LKRTCMediaStream) {
        if let track = stream.audioTracks.first {
            attachRemoteAudioTrack(track)
        }
    }

    func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didRemove stream: LKRTCMediaStream
    ) {}

    func peerConnectionShouldNegotiate(_ peerConnection: LKRTCPeerConnection) {}

    func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didChange newState: LKRTCIceConnectionState
    ) {
        stateLock.lock()
        let closed = isClosed
        stateLock.unlock()
        if !closed, newState == .failed {
            emitMediaDiagnostic("ice_failed")
            onConnectionFailure?()
        }
    }

    func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didChange newState: LKRTCIceGatheringState
    ) {}

    func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didGenerate candidate: LKRTCIceCandidate
    ) {}

    func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didRemove candidates: [LKRTCIceCandidate]
    ) {}

    func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didOpen dataChannel: LKRTCDataChannel
    ) {
        // BuddyStudy uses an authenticated sideband control socket. Reject any
        // provider-opened data channel so the offer stays audio-only and no
        // second policy-control path can appear.
        dataChannel.close()
    }

    func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didAdd rtpReceiver: LKRTCRtpReceiver,
        streams mediaStreams: [LKRTCMediaStream]
    ) {
        if let track = rtpReceiver.track as? LKRTCAudioTrack {
            attachRemoteAudioTrack(track)
        }
    }
}
#endif
