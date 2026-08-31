#if os(iOS)
@preconcurrency import CoreML
import Foundation

/// Fixed, metadata-safe failures. Underlying Core ML errors and audio never leave
/// the local scorer. There is deliberately no network/model-download fallback.
enum VoiceTutorSileroError: String, Error, Equatable, Sendable {
    case bundledModelMissing
    case remoteModelForbidden
    case modelLoadFailed
    case modelSchemaMismatch
    case modelLoadTimedOut
    case invalidAudio
    case unsupportedSampleRate
    case resamplingFailed
    case inferenceFailed
    case invalidModelOutput
    case inputQueueOverflow
    case processingTooSlow
}

/// Each instance belongs to one serial acoustic pipeline. This is speech/noise
/// probability, never meaningful-input, readiness, or end-of-turn judgment.
protocol VoiceTutorSpeechProbabilityScoring: AnyObject, Sendable {
    func probability(for samples: [Float]) throws -> Double
    func reset()
}

/// Direct Core ML wrapper for the reviewed MIT Silero v6 32 ms conversion.
/// No FluidAudio package, model hub, ONNX runtime or remote URL is needed.
/// Mutable recurrent state is used only by its owning serial pipeline.
final class VoiceTutorSileroSpeechScorer: VoiceTutorSpeechProbabilityScoring,
    @unchecked Sendable {
    static let modelResourceName = "silero-vad-unified-v6.0.0"
    static let sampleRate = 16_000.0
    static let frameSize = 512
    static let contextSize = 64
    static let stateSize = 128
    static let frameDuration = Double(frameSize) / sampleRate

    private let model: MLModel
    private let audio: MLMultiArray
    private let hidden: MLMultiArray
    private let cell: MLMultiArray
    private var context = [Float](repeating: 0, count: VoiceTutorSileroSpeechScorer.contextSize)

    static func bundledModelURL(in bundle: Bundle = .main) throws -> URL {
        guard let url = bundle.url(forResource: modelResourceName, withExtension: "mlmodelc") else {
            throw VoiceTutorSileroError.bundledModelMissing
        }
        try validateLocalModelURL(url)
        return url
    }

    static func validateLocalModelURL(_ url: URL) throws {
        guard url.isFileURL else { throw VoiceTutorSileroError.remoteModelForbidden }
        guard url.pathExtension == "mlmodelc" else { throw VoiceTutorSileroError.modelSchemaMismatch }
    }

    /// Called before SDP/provider negotiation, off the audio callback. The bound
    /// also covers Core ML's first prediction so a lazy graph warm-up cannot
    /// stall the live input queue. Cancellation/timeout discards a late model.
    static func prepareBundled(in bundle: Bundle = .main) async throws -> VoiceTutorSileroSpeechScorer {
        let url = try bundledModelURL(in: bundle)
        let completion = VoiceTutorSileroPreparation()
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            let scorer = try await withCheckedThrowingContinuation {
                (continuation: CheckedContinuation<VoiceTutorSileroSpeechScorer, Error>) in
                completion.install(continuation)
                DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + 5) { [weak completion] in
                    completion?.finish(.failure(VoiceTutorSileroError.modelLoadTimedOut))
                }
                DispatchQueue.global(qos: .userInitiated).async {
                    guard !completion.isFinished else { return }
                    do { completion.finish(.success(try loadLocalModel(at: url))) }
                    catch { completion.finish(.failure(error)) }
                }
            }
            try Task.checkCancellation()
            return scorer
        } onCancel: {
            completion.finish(.failure(CancellationError()))
        }
    }

    /// Synchronous entry for selected, offline model-contract tests. Production
    /// uses the bounded asynchronous preflight above, never the capture thread.
    static func loadLocalModel(at url: URL) throws -> VoiceTutorSileroSpeechScorer {
        try validateLocalModelURL(url)
        let configuration = MLModelConfiguration()
        configuration.computeUnits = .cpuOnly
        let model: MLModel
        do { model = try MLModel(contentsOf: url, configuration: configuration) }
        catch { throw VoiceTutorSileroError.modelLoadFailed }
        let scorer = try VoiceTutorSileroSpeechScorer(model: model)
        _ = try scorer.probability(for: [Float](repeating: 0, count: frameSize))
        scorer.reset()
        return scorer
    }

    private init(model: MLModel) throws {
        self.model = model
        try Self.validateSchema(model.modelDescription)
        do {
            audio = try MLMultiArray(shape: [1, NSNumber(value: Self.frameSize + Self.contextSize)], dataType: .float32)
            hidden = try MLMultiArray(shape: [1, NSNumber(value: Self.stateSize)], dataType: .float32)
            cell = try MLMultiArray(shape: [1, NSNumber(value: Self.stateSize)], dataType: .float32)
        } catch { throw VoiceTutorSileroError.modelLoadFailed }
        reset()
    }

    private static func validateSchema(_ description: MLModelDescription) throws {
        let inputs = ["audio_input": [1, 576], "hidden_state": [1, 128], "cell_state": [1, 128]]
        let outputs = ["vad_output": [1, 1, 1], "new_hidden_state": [1, 128], "new_cell_state": [1, 128]]
        for (name, shape) in inputs {
            guard let constraint = description.inputDescriptionsByName[name]?.multiArrayConstraint,
                  constraint.dataType == .float32, constraint.shape.map(\.intValue) == shape else {
                throw VoiceTutorSileroError.modelSchemaMismatch
            }
        }
        for (name, shape) in outputs {
            guard let constraint = description.outputDescriptionsByName[name]?.multiArrayConstraint,
                  constraint.dataType == .float32, constraint.shape.map(\.intValue) == shape else {
                throw VoiceTutorSileroError.modelSchemaMismatch
            }
        }
    }

    func probability(for samples: [Float]) throws -> Double {
        guard samples.count == Self.frameSize, samples.allSatisfy(\.isFinite) else {
            throw VoiceTutorSileroError.invalidAudio
        }
        return try autoreleasepool {
            for index in 0..<Self.contextSize { audio[index] = NSNumber(value: context[index]) }
            for index in samples.indices {
                // Resampler ringing can slightly exceed full scale. Preserve
                // amplitude otherwise; never normalize each chunk's loudness.
                audio[index + Self.contextSize] = NSNumber(value: max(-1, min(1, samples[index])))
            }
            let input: MLDictionaryFeatureProvider
            do {
                input = try MLDictionaryFeatureProvider(dictionary: [
                    "audio_input": audio, "hidden_state": hidden, "cell_state": cell
                ])
            } catch { throw VoiceTutorSileroError.modelSchemaMismatch }
            let output: any MLFeatureProvider
            do { output = try model.prediction(from: input) }
            catch { throw VoiceTutorSileroError.inferenceFailed }
            guard let probability = output.featureValue(for: "vad_output")?.multiArrayValue,
                  probability.dataType == .float32, probability.shape.map(\.intValue) == [1, 1, 1],
                  let nextHidden = output.featureValue(for: "new_hidden_state")?.multiArrayValue,
                  nextHidden.dataType == .float32, nextHidden.shape.map(\.intValue) == [1, 128],
                  let nextCell = output.featureValue(for: "new_cell_state")?.multiArrayValue,
                  nextCell.dataType == .float32, nextCell.shape.map(\.intValue) == [1, 128] else {
                throw VoiceTutorSileroError.invalidModelOutput
            }
            let value = probability[0].doubleValue
            guard value.isFinite, (0...1).contains(value) else {
                throw VoiceTutorSileroError.invalidModelOutput
            }
            // MLMultiArray subscripts honor strides; do not assume an output
            // tensor is contiguous just because the pinned shape is small.
            for index in 0..<Self.stateSize {
                guard nextHidden[index].doubleValue.isFinite, nextCell[index].doubleValue.isFinite else {
                    throw VoiceTutorSileroError.invalidModelOutput
                }
            }
            for index in 0..<Self.stateSize {
                hidden[index] = nextHidden[index]
                cell[index] = nextCell[index]
            }
            context = samples.suffix(Self.contextSize).map { max(-1, min(1, $0)) }
            return value
        }
    }

    func reset() {
        context = [Float](repeating: 0, count: Self.contextSize)
        for index in 0..<audio.count { audio[index] = 0 }
        for index in 0..<Self.stateSize {
            hidden[index] = 0
            cell[index] = 0
        }
    }
}

private final class VoiceTutorSileroPreparation: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<VoiceTutorSileroSpeechScorer, Error>?
    private var outcome: Result<VoiceTutorSileroSpeechScorer, Error>?

    var isFinished: Bool {
        lock.lock()
        defer { lock.unlock() }
        return outcome != nil
    }

    func install(_ continuation: CheckedContinuation<VoiceTutorSileroSpeechScorer, Error>) {
        lock.lock()
        let finished = outcome
        if finished == nil { self.continuation = continuation }
        lock.unlock()
        if let finished { continuation.resume(with: finished) }
    }

    func finish(_ outcome: Result<VoiceTutorSileroSpeechScorer, Error>) {
        lock.lock()
        guard self.outcome == nil else { lock.unlock(); return }
        self.outcome = outcome
        let pending = continuation
        continuation = nil
        lock.unlock()
        pending?.resume(with: outcome)
    }
}
#endif
