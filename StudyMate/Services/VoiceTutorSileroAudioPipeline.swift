#if os(iOS)
@preconcurrency import AVFoundation
import Foundation

/// Converts one continuous mono stream, not individually padded 10 ms packets.
/// It is confined to the acoustic worker and is replaced on a capture generation
/// or format change. Nothing here changes WebRTC's original audio buffers.
final class VoiceTutorSileroResampler {
    let inputSampleRate: Double
    private(set) var lastInputSnapshot: VoiceTutorSileroConversionInputSnapshot?
    private let inputFormat: AVAudioFormat
    private let outputFormat: AVAudioFormat
    private let converter: AVAudioConverter?

    init(inputSampleRate: Double) throws {
        guard inputSampleRate.isFinite, (8_000...192_000).contains(inputSampleRate),
              let inputFormat = AVAudioFormat(
                commonFormat: .pcmFormatFloat32, sampleRate: inputSampleRate,
                channels: 1, interleaved: false
              ),
              let outputFormat = AVAudioFormat(
                commonFormat: .pcmFormatFloat32,
                sampleRate: VoiceTutorSileroSpeechScorer.sampleRate,
                channels: 1, interleaved: false
              ) else { throw VoiceTutorSileroError.unsupportedSampleRate }
        self.inputSampleRate = inputSampleRate
        self.inputFormat = inputFormat
        self.outputFormat = outputFormat
        if inputSampleRate == VoiceTutorSileroSpeechScorer.sampleRate {
            converter = nil
        } else {
            guard let converter = AVAudioConverter(from: inputFormat, to: outputFormat) else {
                throw VoiceTutorSileroError.resamplingFailed
            }
            // Realtime input has no preceding/following buffer at its start.
            // Keep the converter's streaming filter state between callbacks.
            converter.primeMethod = .none
            self.converter = converter
        }
    }

    func convert(_ samples: [Float]) throws -> [Float] {
        guard !samples.isEmpty, samples.count <= 48_000,
              Double(samples.count) / inputSampleRate <= 0.25,
              samples.allSatisfy(\.isFinite) else {
            throw VoiceTutorSileroError.invalidAudio
        }
        guard let converter else { return samples }
        guard let input = AVAudioPCMBuffer(
            pcmFormat: inputFormat, frameCapacity: AVAudioFrameCount(samples.count)
        ), let channel = input.floatChannelData?[0] else {
            throw VoiceTutorSileroError.resamplingFailed
        }
        input.frameLength = AVAudioFrameCount(samples.count)
        samples.withUnsafeBufferPointer { source in
            if let address = source.baseAddress { channel.update(from: address, count: source.count) }
        }

        let expected = Int(ceil(Double(samples.count) * outputFormat.sampleRate / inputSampleRate))
        let capacity = AVAudioFrameCount(max(512, expected + 128))
        let inputProvider = VoiceTutorSileroConverterInput(input)
        var converted: [Float] = []
        converted.reserveCapacity(expected + 128)
        // Ordinarily one call returns inputRanDry. The small bound also drains
        // any buffered output without pretending each callback is end-of-stream.
        for _ in 0..<4 {
            guard let output = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: capacity) else {
                throw VoiceTutorSileroError.resamplingFailed
            }
            var error: NSError?
            let status = converter.convert(to: output, error: &error) { requestedPackets, inputStatus in
                inputProvider.next(requestedPackets: requestedPackets, status: inputStatus)
            }
            let inputSnapshot = inputProvider.snapshot()
            lastInputSnapshot = inputSnapshot
            guard error == nil, !inputSnapshot.failed, status != .error, status != .endOfStream,
                  let outputChannel = output.floatChannelData?[0] else {
                throw VoiceTutorSileroError.resamplingFailed
            }
            let count = Int(output.frameLength)
            converted.append(contentsOf: UnsafeBufferPointer(start: outputChannel, count: count))
            guard converted.count <= 8_192, converted.allSatisfy(\.isFinite) else {
                throw VoiceTutorSileroError.resamplingFailed
            }
            if status == .inputRanDry {
                // Never interpret an unfinished source packet as a quiet tail.
                guard inputSnapshot.remainingFrames == 0 else { throw VoiceTutorSileroError.resamplingFailed }
                return converted
            }
            guard count > 0 else { throw VoiceTutorSileroError.resamplingFailed }
        }
        throw VoiceTutorSileroError.resamplingFailed
    }
}

/// Bounded conversion metadata only, also available to offline regression
/// probes. It contains no audio, level, utterance text or device identity.
struct VoiceTutorSileroConversionInputSnapshot: Equatable, Sendable {
    let requestCount: Int
    let firstRequestedFrames: Int
    let largestRequestedFrames: Int
    let suppliedFrames: Int
    let remainingFrames: Int
    let failed: Bool
}

/// AVAudioConverter annotates its synchronous input block as Sendable. Own the
/// source cursor through a synchronized box, not a captured mutable variable.
private final class VoiceTutorSileroConverterInput: @unchecked Sendable {
    private let lock = NSLock()
    private let buffer: AVAudioPCMBuffer
    private var offset = 0
    private var currentSlice: AVAudioPCMBuffer?
    private var requestCount = 0
    private var firstRequestedFrames = 0
    private var largestRequestedFrames = 0
    private var failed = false

    init(_ buffer: AVAudioPCMBuffer) { self.buffer = buffer }

    func next(
        requestedPackets: AVAudioPacketCount,
        status: UnsafeMutablePointer<AVAudioConverterInputStatus>
    ) -> AVAudioBuffer? {
        lock.lock()
        defer { lock.unlock() }
        let requested = Int(requestedPackets)
        requestCount += 1
        if requestCount == 1 { firstRequestedFrames = requested }
        largestRequestedFrames = max(largestRequestedFrames, requested)
        let remaining = Int(buffer.frameLength) - offset
        guard !failed, remaining > 0 else { status.pointee = .noDataNow; return nil }
        guard requested > 0 else { failed = true; status.pointee = .noDataNow; return nil }
        // A large source cannot be marked wholly supplied after the converter's
        // first pull. The iPhone regression consumed only a 4096-frame prefix
        // of a once-only 4800/4410-frame source. Honor each request and retain
        // the unsupplied suffix; small native 10 ms buffers use the zero-copy
        // handoff below. Each individual pull stays explicitly bounded too.
        let count = min(remaining, min(requested, 4_096))
        let slice: AVAudioPCMBuffer
        if offset == 0, count == Int(buffer.frameLength) {
            slice = buffer
        } else {
            guard let next = AVAudioPCMBuffer(pcmFormat: buffer.format, frameCapacity: AVAudioFrameCount(count)),
                  let source = buffer.floatChannelData?[0], let target = next.floatChannelData?[0] else {
                failed = true
                status.pointee = .noDataNow
                return nil
            }
            next.frameLength = AVAudioFrameCount(count)
            target.update(from: source.advanced(by: offset), count: count)
            slice = next
        }
        // The returned storage must survive until the next callback or the
        // conversion returns, not just until this Swift method exits.
        currentSlice = slice
        offset += count
        status.pointee = .haveData
        return slice
    }

    func snapshot() -> VoiceTutorSileroConversionInputSnapshot {
        lock.lock()
        defer { lock.unlock() }
        return VoiceTutorSileroConversionInputSnapshot(
            requestCount: requestCount, firstRequestedFrames: firstRequestedFrames,
            largestRequestedFrames: largestRequestedFrames, suppliedFrames: offset,
            remainingFrames: Int(buffer.frameLength) - offset, failed: failed
        )
    }
}

/// No zero-padding between native callbacks: every new sample belongs to one
/// 512-sample inference window, with at most 511 samples retained in memory.
struct VoiceTutorSileroFrameAssembler {
    private(set) var pendingSampleCount = 0
    private var pending: [Float] = []

    mutating func append(_ samples: [Float]) throws -> [[Float]] {
        guard samples.count <= 8_192, samples.allSatisfy(\.isFinite) else {
            throw VoiceTutorSileroError.invalidAudio
        }
        pending.append(contentsOf: samples)
        let frameSize = VoiceTutorSileroSpeechScorer.frameSize
        let completeCount = pending.count / frameSize
        var frames: [[Float]] = []
        frames.reserveCapacity(completeCount)
        for index in 0..<completeCount {
            let start = index * frameSize
            frames.append(Array(pending[start..<(start + frameSize)]))
        }
        if completeCount > 0 { pending.removeFirst(completeCount * frameSize) }
        pendingSampleCount = pending.count
        return frames
    }

    mutating func reset() { pending.removeAll(keepingCapacity: true); pendingSampleCount = 0 }
}

struct VoiceTutorSileroPipelineSnapshot: Equatable, Sendable {
    let pendingBlocks: Int
    let pendingAudioSeconds: TimeInterval
    let isClosed: Bool
    let hasFailed: Bool
}

/// A single bounded mailbox/worker, not one Task per realtime callback. Native
/// capture only copies a small packet and enqueues it. Core ML, conversion and
/// recurrent state never run under the native capture lock or on its thread.
final class VoiceTutorSileroAudioPipeline: @unchecked Sendable {
    static let maximumPendingBlocks = 32
    static let maximumPendingAudioSeconds = 0.32
    static let maximumPacketAgeSeconds = 0.75
    static let maximumPredictionSeconds = 0.25

    private struct Packet {
        let samples: [Float]
        let sampleRate: Double
        let generation: UInt64
        let queuedAt: TimeInterval
        var duration: TimeInterval { Double(samples.count) / sampleRate }
    }

    private let lock = NSLock()
    private let worker = DispatchQueue(label: "com.buddystudy.voice.silero", qos: .userInitiated)
    private let failureDelivery = DispatchQueue(label: "com.buddystudy.voice.silero-failure")
    private let scorer: any VoiceTutorSpeechProbabilityScoring
    private let onProbability: @Sendable (Double, UInt64) -> Void
    private let onFailure: @Sendable (VoiceTutorSileroError, UInt64) -> Void
    private var packets: [Packet] = []
    private var pendingDuration: TimeInterval = 0
    private var generation: UInt64 = 0
    private var draining = false
    private var closed = false
    private var failed = false
    private var predictionSequence: UInt64 = 0
    private var activePrediction: UInt64?
    private var predictionStartedAt: TimeInterval = 0
    private var predictionWatchdog: DispatchSourceTimer?

    // Accessed only by `worker`, including resets and cleanup.
    private var workerGeneration: UInt64?
    private var resampler: VoiceTutorSileroResampler?
    private var assembler = VoiceTutorSileroFrameAssembler()

    init(
        scorer: any VoiceTutorSpeechProbabilityScoring,
        onProbability: @escaping @Sendable (Double, UInt64) -> Void,
        onFailure: @escaping @Sendable (VoiceTutorSileroError, UInt64) -> Void
    ) {
        self.scorer = scorer
        self.onProbability = onProbability
        self.onFailure = onFailure
        let watchdog = DispatchSource.makeTimerSource(queue: failureDelivery)
        watchdog.setEventHandler { [weak self] in self?.predictionDeadlineReached() }
        watchdog.schedule(deadline: .distantFuture)
        watchdog.resume()
        predictionWatchdog = watchdog
    }

    deinit { predictionWatchdog?.cancel() }

    func enqueue(samples: [Float], sampleRate: Double, generation: UInt64) {
        let error: VoiceTutorSileroError?
        if !sampleRate.isFinite || !(8_000...192_000).contains(sampleRate) {
            error = .unsupportedSampleRate
        } else if samples.isEmpty || samples.count > 48_000 ||
                    Double(samples.count) / sampleRate > 0.25 || !samples.allSatisfy(\.isFinite) {
            error = .invalidAudio
        } else { error = nil }
        let packet = Packet(samples: samples, sampleRate: sampleRate, generation: generation,
                            queuedAt: ProcessInfo.processInfo.systemUptime)
        lock.lock()
        guard !closed, !failed, generation == self.generation else { lock.unlock(); return }
        if let error {
            failLocked()
            lock.unlock()
            deliverFailure(error, generation: generation)
            return
        }
        guard packets.count < Self.maximumPendingBlocks,
              pendingDuration + packet.duration <= Self.maximumPendingAudioSeconds + 0.000_000_001 else {
            failLocked()
            lock.unlock()
            deliverFailure(.inputQueueOverflow, generation: generation)
            return
        }
        packets.append(packet)
        pendingDuration += packet.duration
        scheduleLocked()
        lock.unlock()
    }

    /// Gates and format changes advance the capture generation. In-flight old
    /// predictions may finish but cannot produce events in the new generation.
    func reset(generation: UInt64) {
        lock.lock()
        guard !closed else { lock.unlock(); return }
        self.generation = generation
        packets.removeAll(keepingCapacity: true)
        pendingDuration = 0
        failed = false
        activePrediction = nil
        predictionWatchdog?.schedule(deadline: .distantFuture)
        scheduleLocked()
        lock.unlock()
    }

    func close() {
        lock.lock()
        closed = true
        packets.removeAll(keepingCapacity: false)
        pendingDuration = 0
        activePrediction = nil
        predictionWatchdog?.cancel()
        scheduleLocked()
        lock.unlock()
    }

    func snapshot() -> VoiceTutorSileroPipelineSnapshot {
        lock.lock()
        defer { lock.unlock() }
        return VoiceTutorSileroPipelineSnapshot(
            pendingBlocks: packets.count, pendingAudioSeconds: pendingDuration,
            isClosed: closed, hasFailed: failed
        )
    }

    private func scheduleLocked() {
        guard !draining else { return }
        draining = true
        worker.async { [self] in drain() }
    }

    private func resetWorker(to generation: UInt64) {
        scorer.reset()
        resampler = nil
        assembler.reset()
        workerGeneration = generation
    }

    private func drain() {
        while true {
            lock.lock()
            let currentGeneration = generation
            if workerGeneration != currentGeneration {
                lock.unlock()
                resetWorker(to: currentGeneration)
                continue
            }
            if closed || failed {
                draining = false
                lock.unlock()
                resetWorker(to: currentGeneration)
                return
            }
            guard !packets.isEmpty else { draining = false; lock.unlock(); return }
            let packet = packets.removeFirst()
            pendingDuration = max(0, pendingDuration - packet.duration)
            lock.unlock()
            guard isCurrent(packet.generation) else { continue }
            do {
                guard ProcessInfo.processInfo.systemUptime - packet.queuedAt <= Self.maximumPacketAgeSeconds else {
                    throw VoiceTutorSileroError.processingTooSlow
                }
                if resampler?.inputSampleRate != packet.sampleRate {
                    // Capture normally advances its generation on reinit, but
                    // retain this format fence for direct/injected input too.
                    resetWorker(to: packet.generation)
                    resampler = try VoiceTutorSileroResampler(inputSampleRate: packet.sampleRate)
                }
                guard let resampler else { throw VoiceTutorSileroError.resamplingFailed }
                let converted = try resampler.convert(packet.samples)
                let frames = try assembler.append(converted)
                for frame in frames {
                    guard isCurrent(packet.generation) else { break }
                    let startedAt = ProcessInfo.processInfo.systemUptime
                    let prediction = beginPrediction(generation: packet.generation)
                    let probability: Double
                    do { probability = try scorer.probability(for: frame) }
                    catch { endPrediction(prediction); throw error }
                    endPrediction(prediction)
                    guard ProcessInfo.processInfo.systemUptime - startedAt <= Self.maximumPredictionSeconds else {
                        throw VoiceTutorSileroError.processingTooSlow
                    }
                    guard probability.isFinite, (0...1).contains(probability) else {
                        throw VoiceTutorSileroError.invalidModelOutput
                    }
                    guard isCurrent(packet.generation) else { break }
                    // No mailbox lock is held while capture checks its own
                    // generation/gate. That second check closes reset races.
                    onProbability(probability, packet.generation)
                }
            } catch {
                fail(error as? VoiceTutorSileroError ?? .inferenceFailed, generation: packet.generation)
            }
        }
    }

    private func isCurrent(_ generation: UInt64) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return !closed && !failed && self.generation == generation
    }

    private func failLocked() {
        failed = true
        activePrediction = nil
        predictionWatchdog?.schedule(deadline: .distantFuture)
        packets.removeAll(keepingCapacity: false)
        pendingDuration = 0
        scheduleLocked()
    }

    private func fail(_ error: VoiceTutorSileroError, generation: UInt64) {
        lock.lock()
        guard !closed, !failed, self.generation == generation else { lock.unlock(); return }
        failLocked()
        lock.unlock()
        deliverFailure(error, generation: generation)
    }

    private func beginPrediction(generation: UInt64) -> UInt64 {
        lock.lock()
        predictionSequence &+= 1
        let identifier = predictionSequence
        if !closed && !failed && self.generation == generation {
            activePrediction = identifier
            predictionStartedAt = ProcessInfo.processInfo.systemUptime
            predictionWatchdog?.schedule(deadline: .now() + Self.maximumPredictionSeconds)
        }
        lock.unlock()
        return identifier
    }

    private func endPrediction(_ identifier: UInt64) {
        lock.lock()
        if activePrediction == identifier {
            activePrediction = nil
            predictionWatchdog?.schedule(deadline: .distantFuture)
        }
        lock.unlock()
    }

    private func predictionDeadlineReached() {
        // Exactly one reusable timer, independent of additional input: even the
        // final prediction before silence/route loss cannot hang indefinitely.
        lock.lock()
        guard !closed, !failed, activePrediction != nil else { lock.unlock(); return }
        let elapsed = ProcessInfo.processInfo.systemUptime - predictionStartedAt
        guard elapsed >= Self.maximumPredictionSeconds else {
            predictionWatchdog?.schedule(deadline: .now() + Self.maximumPredictionSeconds - elapsed)
            lock.unlock()
            return
        }
        let failedGeneration = generation
        failLocked()
        lock.unlock()
        deliverFailure(.processingTooSlow, generation: failedGeneration)
    }

    private func deliverFailure(_ error: VoiceTutorSileroError, generation: UInt64) {
        // This queue is independent of inference, so an overloaded or stalled
        // worker cannot also strand the error that tells the call to terminate.
        failureDelivery.async { [weak self] in
            guard let self else { return }
            lock.lock()
            let shouldDeliver = !closed && failed && self.generation == generation
            lock.unlock()
            if shouldDeliver { onFailure(error, generation) }
        }
    }
}
#endif
