@preconcurrency import AVFoundation
import Foundation
import XCTest
@testable import StudyMate

/// Safe offline acoustic contracts; no account, provider, microphone or writes.
final class VoiceTutorSileroTests: XCTestCase {
    private let frameDuration = VoiceTutorSileroSpeechScorer.frameDuration

    func testProbabilitySilenceAndShortSpikesDoNotStartSpeech() {
        var detector = enabledDetector()
        for _ in 0..<100 {
            XCTAssertNil(detector.process(speechProbability: 0.01, duration: frameDuration))
        }
        for _ in 0..<4 {
            XCTAssertNil(detector.process(speechProbability: 0.99, duration: frameDuration))
            XCTAssertNil(detector.process(speechProbability: 0.99, duration: frameDuration))
            XCTAssertNil(detector.process(speechProbability: 0.1, duration: frameDuration))
        }
        XCTAssertFalse(detector.isSpeaking)
        XCTAssertEqual(detector.sequence, 0)
    }

    func testProbabilityHysteresisStartsOnceAndStopsAfterFifteenQuietWindows() {
        var detector = enabledDetector()
        XCTAssertNil(detector.process(speechProbability: 0.5, duration: frameDuration))
        XCTAssertNil(detector.process(speechProbability: 0.5, duration: frameDuration))
        XCTAssertEqual(detector.process(speechProbability: 0.5, duration: frameDuration), event(.started, 1))
        for _ in 0..<30 {
            // The continuation band does not require repeatedly crossing onset.
            XCTAssertNil(detector.process(speechProbability: 0.35, duration: frameDuration))
        }
        for _ in 0..<14 {
            XCTAssertNil(detector.process(speechProbability: 0.349, duration: frameDuration))
        }
        XCTAssertTrue(detector.isSpeaking, "14 x 32 ms is only 448 ms")
        XCTAssertEqual(VoiceTutorLocalSpeechDetector.quietHoldDuration, 15 * frameDuration, accuracy: 0.000_001)
        XCTAssertEqual(detector.process(speechProbability: 0.349, duration: frameDuration), event(.stopped, 1))
        for _ in 0..<30 {
            XCTAssertNil(detector.process(speechProbability: 0, duration: frameDuration))
        }
        XCTAssertFalse(detector.isSpeaking)
        XCTAssertEqual(detector.sequence, 1)
    }

    func testProbabilityQuietGapResetsWhenSpeechReturnsAndSequencesStayPaired() {
        var detector = enabledDetector()
        XCTAssertEqual(feed(&detector, probability: 0.9, frames: 4), [event(.started, 1)])
        XCTAssertTrue(feed(&detector, probability: 0.1, frames: 13).isEmpty)
        XCTAssertTrue(feed(&detector, probability: 0.8, frames: 1).isEmpty)
        XCTAssertTrue(feed(&detector, probability: 0.1, frames: 14).isEmpty)
        XCTAssertEqual(feed(&detector, probability: 0.1, frames: 1), [event(.stopped, 1)])
        XCTAssertEqual(feed(&detector, probability: 0.9, frames: 3), [event(.started, 2)])
        XCTAssertEqual(feed(&detector, probability: 0.1, frames: 15), [event(.stopped, 2)])
    }

    func testProbabilityRepeatedWithinPhrasePausesNeverFinishContinuingSpeech() {
        var detector = enabledDetector()
        XCTAssertEqual(feed(&detector, probability: 0.9, frames: 3), [event(.started, 1)])
        for _ in 0..<60 {
            XCTAssertTrue(feed(&detector, probability: 0.1, frames: 14).isEmpty)
            // Quiet but genuine continuation resets the entire quiet window,
            // without needing another loud onset or a different transcript.
            XCTAssertNil(detector.process(speechProbability: 0.35, duration: frameDuration))
        }
        XCTAssertTrue(detector.isSpeaking)
        XCTAssertEqual(detector.sequence, 1)
        XCTAssertEqual(feed(&detector, probability: 0.1, frames: 15), [event(.stopped, 1)])
    }

    func testProbabilityInvalidValuesDoNotAdvanceOnsetOrQuietTimers() {
        var detector = enabledDetector()
        _ = feed(&detector, probability: 0.9, frames: 2)
        for probability in [Double.nan, .infinity, -.infinity, -0.01, 1.01] {
            XCTAssertNil(detector.process(speechProbability: probability, duration: frameDuration))
        }
        for duration in [Double.nan, .infinity, -.infinity, 0, -0.1, 0.251] {
            XCTAssertNil(detector.process(speechProbability: 0.9, duration: duration))
        }
        XCTAssertFalse(detector.isSpeaking)
        XCTAssertEqual(feed(&detector, probability: 0.9, frames: 1), [event(.started, 1)])
        _ = feed(&detector, probability: 0.1, frames: 14)
        for probability in [Double.nan, .infinity, -1, 2] {
            XCTAssertNil(detector.process(speechProbability: probability, duration: frameDuration))
        }
        XCTAssertTrue(detector.isSpeaking)
        XCTAssertEqual(feed(&detector, probability: 0.1, frames: 1), [event(.stopped, 1)])
    }

    func testReadinessMuteAndResetFenceProbabilityEvidence() {
        var detector = VoiceTutorLocalSpeechDetector()
        XCTAssertTrue(feed(&detector, probability: 0.99, frames: 10).isEmpty)
        XCTAssertNil(detector.updateGate(mediaReady: true, muted: false))
        XCTAssertEqual(feed(&detector, probability: 0.9, frames: 3), [event(.started, 1)])
        XCTAssertEqual(detector.updateGate(mediaReady: true, muted: true), event(.stopped, 1))
        XCTAssertNil(detector.updateGate(mediaReady: true, muted: true))
        XCTAssertTrue(feed(&detector, probability: 0.99, frames: 50).isEmpty)
        XCTAssertNil(detector.updateGate(mediaReady: true, muted: false))
        XCTAssertTrue(feed(&detector, probability: 0.9, frames: 2).isEmpty)
        XCTAssertEqual(feed(&detector, probability: 0.9, frames: 1), [event(.started, 2)])
        XCTAssertEqual(detector.updateGate(mediaReady: false, muted: false), event(.stopped, 2))
        detector.close()
        XCTAssertNil(detector.updateGate(mediaReady: true, muted: false))
        XCTAssertTrue(feed(&detector, probability: 0.9, frames: 10).isEmpty)
        detector.reset()
        XCTAssertEqual(detector.sequence, 0)
        XCTAssertFalse(detector.isEnabled)
        _ = detector.updateGate(mediaReady: true, muted: false)
        XCTAssertEqual(feed(&detector, probability: 0.9, frames: 3), [event(.started, 1)])
    }

    func testFormatDiscontinuityClearsEvidenceWithoutReusingAnUtteranceSequence() {
        var detector = enabledDetector()
        XCTAssertEqual(feed(&detector, probability: 0.9, frames: 3), [event(.started, 1)])
        XCTAssertEqual(detector.resetAcousticEvidence(), event(.stopped, 1))
        XCTAssertNil(detector.resetAcousticEvidence())
        XCTAssertTrue(detector.isEnabled)
        XCTAssertEqual(feed(&detector, probability: 0.9, frames: 3), [event(.started, 2)])
    }

    func testTutorPlaybackIsNotAnAcousticInputGate() {
        var detector = enabledDetector()
        var response = VoiceTutorWebRTCResponseState()
        response.responseStarted("synthetic-tutor-response")
        response.markOutputBufferStarted("synthetic-tutor-response")
        XCTAssertTrue(response.mayIndicateSpeaking)
        XCTAssertEqual(feed(&detector, probability: 0.9, frames: 3), [event(.started, 1)])
        XCTAssertEqual(feed(&detector, probability: 0.01, frames: 22), [event(.stopped, 1)])
        XCTAssertTrue(response.mayIndicateSpeaking, "Input classification cannot cancel or stop tutor output")
        XCTAssertTrue(detector.isEnabled)
    }

    func testNativeChannelOrRateReinitializationAdvancesAcousticGeneration() {
        let tap = VoiceTutorLocalSpeechCaptureTap(
            recordingTap: nil, speechScorer: SileroTestScorer(values: [0.9]),
            onActivity: { _ in XCTFail("Initialization alone cannot synthesize speech") }
        )
        defer { tap.close() }
        tap.audioProcessingInitialize(sampleRate: 48_000, channels: 1)
        tap.updateGate(mediaReady: true, muted: false)
        let initial = tap.snapshot()
        XCTAssertEqual(initial.inputChannelCount, 1)
        XCTAssertTrue(initial.gateEnabled)
        tap.audioProcessingInitialize(sampleRate: 48_000, channels: 1)
        XCTAssertEqual(tap.snapshot().processingGeneration, initial.processingGeneration)
        tap.audioProcessingInitialize(sampleRate: 48_000, channels: 2)
        XCTAssertEqual(tap.snapshot().inputChannelCount, 2)
        XCTAssertEqual(tap.snapshot().processingGeneration, initial.processingGeneration + 1)
        tap.audioProcessingInitialize(sampleRate: 16_000, channels: 2)
        XCTAssertEqual(tap.snapshot().sampleRate, 16_000)
        XCTAssertEqual(tap.snapshot().processingGeneration, initial.processingGeneration + 2)
        tap.close()
        let closed = tap.snapshot()
        XCTAssertEqual(closed.inputChannelCount, 0)
        tap.audioProcessingInitialize(sampleRate: 48_000, channels: 1)
        tap.updateGate(mediaReady: true, muted: false)
        XCTAssertEqual(tap.snapshot(), closed)
    }

    func testAssemblerKeepsTenMillisecondPacketBoundariesContinuous() throws {
        var assembler = VoiceTutorSileroFrameAssembler()
        let source = (0..<1_600).map { Float($0) / 2_000 }
        var frames: [[Float]] = []
        for start in stride(from: 0, to: source.count, by: 160) {
            frames.append(contentsOf: try assembler.append(Array(source[start..<(start + 160)])))
        }
        XCTAssertEqual(frames.count, 3)
        XCTAssertTrue(frames.allSatisfy { $0.count == 512 })
        XCTAssertEqual(frames.flatMap { $0 }, Array(source.prefix(1_536)))
        XCTAssertEqual(assembler.pendingSampleCount, 64)
        XCTAssertTrue(try assembler.append([]).isEmpty)
        XCTAssertEqual(assembler.pendingSampleCount, 64)
    }

    func testAssemblerResetAndInvalidSamplesCannotLeakPreviousAttempt() throws {
        var assembler = VoiceTutorSileroFrameAssembler()
        XCTAssertTrue(try assembler.append([Float](repeating: 0.1, count: 511)).isEmpty)
        XCTAssertThrowsError(try assembler.append([.nan]))
        XCTAssertThrowsError(try assembler.append([.infinity]))
        XCTAssertThrowsError(try assembler.append([Float](repeating: 0, count: 8_193)))
        XCTAssertEqual(assembler.pendingSampleCount, 511)
        assembler.reset()
        XCTAssertEqual(assembler.pendingSampleCount, 0)
        XCTAssertEqual(try assembler.append([Float](repeating: 0.2, count: 512)), [[Float](repeating: 0.2, count: 512)])
    }

    func testSixteenKilohertzResamplerIsExactAndNeverAmplifiesQuietNativeInput() throws {
        let native = [Float](repeating: 0.25, count: 160)
        let normalized = try XCTUnwrap(VoiceTutorAudioProcessingTap.normalizedRecordingSamples(native))
        let converter = try VoiceTutorSileroResampler(inputSampleRate: 16_000)
        let converted = try converter.convert(normalized)
        XCTAssertEqual(converted, normalized)
        XCTAssertEqual(converted[0], 0.25 / 32_768, accuracy: 0.000_000_001)
        XCTAssertEqual(native, [Float](repeating: 0.25, count: 160), "Native samples are never rewritten")
    }

    func testStreamingResamplerMatchesSingleContinuousInputAtNativeRates() throws {
        for rate in [48_000.0, 44_100.0, 32_000.0, 8_000.0] {
            let count = Int(rate / 10)
            let samples = (0..<count).map { index in
                Float(sin(Double(index) * 2 * Double.pi * 437 / rate) * 0.1)
            }
            let wholeConverter = try VoiceTutorSileroResampler(inputSampleRate: rate)
            let whole = try wholeConverter.convert(samples)
            let supply = try XCTUnwrap(wholeConverter.lastInputSnapshot)
            XCTAssertEqual(supply.suppliedFrames, samples.count)
            XCTAssertEqual(supply.remainingFrames, 0)
            XCTAssertFalse(supply.failed)
            let streaming = try VoiceTutorSileroResampler(inputSampleRate: rate)
            let packetSize = Int(rate / 100)
            var packets: [Float] = []
            for start in stride(from: 0, to: samples.count, by: packetSize) {
                packets += try streaming.convert(Array(samples[start..<min(start + packetSize, samples.count)]))
            }
            XCTAssertEqual(packets.count, whole.count, "Rate \(Int(rate)); no lost or padded packets")
            XCTAssertEqual(packets.count, 1_600, "100 ms must stay 100 ms after conversion")
            XCTAssertEqual(whole.count, 1_600, "The single-buffer reference must consume its complete input")
            let maximumDifference = zip(packets, whole).map { abs($0 - $1) }.max() ?? 0
            XCTAssertLessThan(maximumDifference, 0.000_1, "Rate \(Int(rate)); streaming filter continuity")
        }
    }

    func testResamplerPreservesLargeInputSuffixAcrossMultipleNativePulls() throws {
        var metadata: [[String: Int]] = []
        defer {
            if let data = try? JSONSerialization.data(withJSONObject: metadata, options: [.sortedKeys]),
               let summary = String(data: data, encoding: .utf8) {
                let attachment = XCTAttachment(string: summary)
                attachment.name = "silero-resampler-input-pulls-metadata-only"
                attachment.lifetime = .keepAlways
                add(attachment)
            }
        }
        for rate in [8_000.0, 32_000.0, 44_100.0, 48_000.0, 96_000.0, 192_000.0] {
            let inputCount = Int(rate / 4)
            // A chirp makes an omitted/repeated suffix observable in waveform
            // comparison, not just in the expected total output frame count.
            let samples: [Float] = (0..<inputCount).map { index -> Float in
                let time = Double(index) / rate
                let phase = 180.0 * time + 400.0 * time * time
                return Float(sin(2.0 * Double.pi * phase) * 0.1)
            }
            let wholeConverter = try VoiceTutorSileroResampler(inputSampleRate: rate)
            let whole = try wholeConverter.convert(samples)
            let supply = try XCTUnwrap(wholeConverter.lastInputSnapshot)
            XCTAssertEqual(supply.suppliedFrames, inputCount)
            XCTAssertEqual(supply.remainingFrames, 0)
            XCTAssertFalse(supply.failed)
            if inputCount > 4_096 { XCTAssertGreaterThan(supply.requestCount, 1) }
            let streaming = try VoiceTutorSileroResampler(inputSampleRate: rate)
            let packetSize = Int(rate / 100)
            var packets: [Float] = []
            for start in stride(from: 0, to: inputCount, by: packetSize) {
                packets += try streaming.convert(Array(samples[start..<min(start + packetSize, inputCount)]))
            }
            metadata.append([
                "inputRate": Int(rate), "inputFrames": inputCount,
                "requestCount": supply.requestCount,
                "firstRequestedFrames": supply.firstRequestedFrames,
                "largestRequestedFrames": supply.largestRequestedFrames,
                "suppliedFrames": supply.suppliedFrames, "remainingFrames": supply.remainingFrames,
                "wholeOutputFrames": whole.count, "streamedOutputFrames": packets.count
            ])
            XCTAssertEqual(whole.count, 4_000, "250 ms source at rate \(Int(rate))")
            XCTAssertEqual(packets.count, 4_000)
            XCTAssertEqual(whole.count, packets.count)
            let maximumDifference = zip(whole, packets).map { abs($0 - $1) }.max() ?? 0
            XCTAssertLessThan(maximumDifference, 0.000_1, "No overwritten, omitted or duplicated input suffix")
        }
    }

    func testResamplerRejectsInvalidRatesAndFrames() throws {
        for rate in [Double.nan, .infinity, -1, 0, 7_999, 192_001] {
            XCTAssertThrowsError(try VoiceTutorSileroResampler(inputSampleRate: rate))
        }
        let converter = try VoiceTutorSileroResampler(inputSampleRate: 16_000)
        XCTAssertThrowsError(try converter.convert([]))
        XCTAssertThrowsError(try converter.convert([.nan]))
        XCTAssertThrowsError(try converter.convert([.infinity]))
        XCTAssertThrowsError(try converter.convert([Float](repeating: 0, count: 4_001)))
    }

    func testBoundedPipelineDeliversOrderedProbabilityFromNormalizedNativeSamples() {
        let scored = expectation(description: "four ordered Silero frames")
        scored.expectedFulfillmentCount = 4
        let outputs = SileroTestLog<Double>()
        let scorer = SileroTestScorer(values: [0.1, 0.2, 0.3, 0.4])
        let pipeline = VoiceTutorSileroAudioPipeline(scorer: scorer, onProbability: { probability, generation in
            XCTAssertEqual(generation, 0)
            outputs.append(probability)
            scored.fulfill()
        }, onFailure: { error, _ in XCTFail("Unexpected fixed pipeline error: \(error.rawValue)") })
        defer { pipeline.close() }
        let normalized = [Float](repeating: 0.25 / 32_768, count: 512)
        for _ in 0..<4 { pipeline.enqueue(samples: normalized, sampleRate: 16_000, generation: 0) }
        wait(for: [scored], timeout: 2)
        XCTAssertEqual(outputs.values, [0.1, 0.2, 0.3, 0.4])
        XCTAssertEqual(scorer.frames, [[Float]](repeating: normalized, count: 4))
        XCTAssertLessThanOrEqual(pipeline.snapshot().pendingBlocks, VoiceTutorSileroAudioPipeline.maximumPendingBlocks)
    }

    func testPipelineResetDiscardsInFlightOldAttemptAndPartialFrames() {
        let entered = expectation(description: "old generation is in inference")
        let current = expectation(description: "new generation result")
        let release = DispatchSemaphore(value: 0)
        let generations = SileroTestLog<UInt64>()
        let scorer = SileroTestScorer(values: [0.9], firstPrediction: {
            entered.fulfill()
            _ = release.wait(timeout: .now() + 2)
        })
        let pipeline = VoiceTutorSileroAudioPipeline(scorer: scorer, onProbability: { _, generation in
            generations.append(generation)
            current.fulfill()
        }, onFailure: { error, _ in XCTFail("Unexpected fixed pipeline error: \(error.rawValue)") })
        defer { release.signal(); pipeline.close() }
        pipeline.enqueue(samples: [Float](repeating: 0.1, count: 512), sampleRate: 16_000, generation: 0)
        wait(for: [entered], timeout: 1)
        pipeline.enqueue(samples: [Float](repeating: 0.1, count: 160), sampleRate: 16_000, generation: 0)
        pipeline.reset(generation: 1)
        pipeline.enqueue(samples: [Float](repeating: 0.9, count: 512), sampleRate: 16_000, generation: 0)
        pipeline.enqueue(samples: [Float](repeating: 0.2, count: 512), sampleRate: 16_000, generation: 1)
        release.signal()
        wait(for: [current], timeout: 1)
        XCTAssertEqual(generations.values, [1])
        XCTAssertEqual(scorer.frames.last, [Float](repeating: 0.2, count: 512))
        XCTAssertGreaterThanOrEqual(scorer.resetCount, 2)
    }

    func testPipelineCloseRejectsLateProbabilityAndFurtherInput() {
        let entered = expectation(description: "inference started")
        let released = expectation(description: "inference returned")
        let release = DispatchSemaphore(value: 0)
        let results = SileroTestLog<Double>()
        let failures = SileroTestLog<VoiceTutorSileroError>()
        let scorer = SileroTestScorer(values: [0.9], firstPrediction: {
            entered.fulfill()
            _ = release.wait(timeout: .now() + 2)
            released.fulfill()
        })
        let pipeline = VoiceTutorSileroAudioPipeline(scorer: scorer, onProbability: { value, _ in
            results.append(value)
        }, onFailure: { error, _ in failures.append(error) })
        defer { release.signal(); pipeline.close() }
        pipeline.enqueue(samples: [Float](repeating: 0, count: 512), sampleRate: 16_000, generation: 0)
        wait(for: [entered], timeout: 1)
        pipeline.close()
        pipeline.reset(generation: 1)
        pipeline.enqueue(samples: [Float](repeating: 0, count: 512), sampleRate: 16_000, generation: 1)
        release.signal()
        wait(for: [released], timeout: 1)
        XCTAssertTrue(pipeline.snapshot().isClosed)
        XCTAssertEqual(pipeline.snapshot().pendingBlocks, 0)
        XCTAssertTrue(results.values.isEmpty)
        XCTAssertTrue(failures.values.isEmpty)
    }

    func testQueueOverflowFailsExplicitlyInsteadOfDroppingSpeechBoundaries() {
        let entered = expectation(description: "worker is busy")
        let failed = expectation(description: "bounded queue failure")
        let release = DispatchSemaphore(value: 0)
        let failures = SileroTestLog<VoiceTutorSileroError>()
        let scorer = SileroTestScorer(values: [0.9], firstPrediction: {
            entered.fulfill()
            _ = release.wait(timeout: .now() + 2)
        })
        let pipeline = VoiceTutorSileroAudioPipeline(scorer: scorer, onProbability: { _, _ in
            XCTFail("An overflowed pipeline must not publish a stale boundary")
        }, onFailure: { error, _ in failures.append(error); failed.fulfill() })
        defer { release.signal(); pipeline.close() }
        pipeline.enqueue(samples: [Float](repeating: 0, count: 512), sampleRate: 16_000, generation: 0)
        wait(for: [entered], timeout: 1)
        for _ in 0..<40 {
            pipeline.enqueue(samples: [Float](repeating: 0, count: 160), sampleRate: 16_000, generation: 0)
        }
        wait(for: [failed], timeout: 1)
        XCTAssertEqual(failures.values, [.inputQueueOverflow])
        XCTAssertTrue(pipeline.snapshot().hasFailed)
        XCTAssertEqual(pipeline.snapshot().pendingBlocks, 0)
        XCTAssertEqual(pipeline.snapshot().pendingAudioSeconds, 0)
    }

    func testPredictionWatchdogFailsEvenWithoutAnyFurtherInput() {
        let failed = expectation(description: "independent inference watchdog")
        let release = DispatchSemaphore(value: 0)
        let failures = SileroTestLog<VoiceTutorSileroError>()
        let scorer = SileroTestScorer(values: [0.9], firstPrediction: {
            _ = release.wait(timeout: .now() + 2)
        })
        let pipeline = VoiceTutorSileroAudioPipeline(scorer: scorer, onProbability: { _, _ in
            XCTFail("A timed-out inference must not emit speech")
        }, onFailure: { error, _ in failures.append(error); failed.fulfill() })
        defer { release.signal(); pipeline.close() }
        pipeline.enqueue(samples: [Float](repeating: 0, count: 512), sampleRate: 16_000, generation: 0)
        wait(for: [failed], timeout: 1.5)
        XCTAssertEqual(failures.values, [.processingTooSlow])
        XCTAssertTrue(pipeline.snapshot().hasFailed)
    }

    func testInvalidModelProbabilityAndInputFailWithoutRMSFallback() {
        for value in [Double.nan, .infinity, -0.1, 1.1] {
            let failed = expectation(description: "invalid probability fails")
            let errors = SileroTestLog<VoiceTutorSileroError>()
            let pipeline = VoiceTutorSileroAudioPipeline(
                scorer: SileroTestScorer(values: [value]),
                onProbability: { _, _ in XCTFail("Invalid model output cannot become a speech event") },
                onFailure: { error, _ in errors.append(error); failed.fulfill() }
            )
            pipeline.enqueue(samples: [Float](repeating: 0.9, count: 512), sampleRate: 16_000, generation: 0)
            wait(for: [failed], timeout: 1)
            pipeline.close()
            XCTAssertEqual(errors.values, [.invalidModelOutput])
        }
        let failed = expectation(description: "invalid samples fail")
        let errors = SileroTestLog<VoiceTutorSileroError>()
        let pipeline = VoiceTutorSileroAudioPipeline(
            scorer: SileroTestScorer(values: [0.9]),
            onProbability: { _, _ in XCTFail("Nonfinite input cannot become speech") },
            onFailure: { error, _ in errors.append(error); failed.fulfill() }
        )
        defer { pipeline.close() }
        pipeline.enqueue(samples: [.nan], sampleRate: 16_000, generation: 0)
        wait(for: [failed], timeout: 1)
        XCTAssertEqual(errors.values, [.invalidAudio])
    }

    func testModelLoadingRejectsRemoteURLsBeforeAnyNetworkOperation() throws {
        for url in ["https://example.invalid/silero.mlmodelc", "http://example.invalid/model", "wss://example.invalid/"] {
            let remote = try XCTUnwrap(URL(string: url))
            XCTAssertThrowsError(try VoiceTutorSileroSpeechScorer.validateLocalModelURL(remote)) {
                XCTAssertEqual($0 as? VoiceTutorSileroError, .remoteModelForbidden)
            }
            XCTAssertThrowsError(try VoiceTutorSileroSpeechScorer.loadLocalModel(at: remote)) {
                XCTAssertEqual($0 as? VoiceTutorSileroError, .remoteModelForbidden)
            }
        }
        XCTAssertThrowsError(try VoiceTutorSileroSpeechScorer.validateLocalModelURL(
            URL(fileURLWithPath: "/synthetic/model.mlpackage")
        )) { XCTAssertEqual($0 as? VoiceTutorSileroError, .modelSchemaMismatch) }
    }

    func testMissingBundledModelFailsPreflightWithoutDownloadFallback() async {
        do {
            // The test bundle intentionally contains no duplicate model. Only
            // the iOS application bundles and precompiles the reviewed artifact.
            _ = try await VoiceTutorSileroSpeechScorer.prepareBundled(in: Bundle(for: Self.self))
            XCTFail("A missing model must fail before session/provider allocation")
        } catch {
            XCTAssertEqual(error as? VoiceTutorSileroError, .bundledModelMissing)
        }
    }

    func testBundledModelLoadsOfflineAndSilenceDoesNotBecomeSpeech() async throws {
        let url = try VoiceTutorSileroSpeechScorer.bundledModelURL()
        XCTAssertTrue(url.isFileURL)
        XCTAssertEqual(url.pathExtension, "mlmodelc")
        let licenseURL = try XCTUnwrap(Bundle.main.url(forResource: "SileroVAD-LICENSE", withExtension: "txt"))
        let license = try String(contentsOf: licenseURL, encoding: .utf8)
        XCTAssertTrue(license.contains("MIT License"))
        XCTAssertTrue(license.contains("Silero Team"))
        let scorer = try await VoiceTutorSileroSpeechScorer.prepareBundled()
        var detector = enabledDetector()
        for _ in 0..<64 {
            let probability = try scorer.probability(for: [Float](repeating: 0, count: 512))
            XCTAssertTrue(probability.isFinite && (0...1).contains(probability))
            XCTAssertNil(detector.process(speechProbability: probability, duration: frameDuration))
        }
        XCTAssertFalse(detector.isSpeaking)
    }

    func testBundledModelStateResetIsDeterministicAndInvalidFramesCannotPoisonIt() async throws {
        let scorer = try await VoiceTutorSileroSpeechScorer.prepareBundled()
        let zero = [Float](repeating: 0, count: 512)
        let baseline = try scorer.probability(for: zero)
        scorer.reset()
        XCTAssertThrowsError(try scorer.probability(for: [Float](repeating: 0, count: 511)))
        XCTAssertThrowsError(try scorer.probability(for: [Float](repeating: .nan, count: 512)))
        XCTAssertThrowsError(try scorer.probability(for: [Float](repeating: .infinity, count: 512)))
        XCTAssertEqual(try scorer.probability(for: zero), baseline, accuracy: 0.000_001)
        let noise = syntheticNoise(frameCount: 12)
        scorer.reset()
        let firstPass = try noise.map { try scorer.probability(for: $0) }
        scorer.reset()
        let secondPass = try noise.map { try scorer.probability(for: $0) }
        for (first, second) in zip(firstPass, secondPass) {
            XCTAssertEqual(first, second, accuracy: 0.000_001)
        }
    }

    func testBundledModelRejectsDeterministicWhiteNoiseAsNonspeech() async throws {
        let scorer = try await VoiceTutorSileroSpeechScorer.prepareBundled()
        var detector = enabledDetector()
        var starts = 0
        for frame in syntheticNoise(frameCount: 64) {
            let probability = try scorer.probability(for: frame)
            if detector.process(speechProbability: probability, duration: frameDuration)?.activity == .started {
                starts += 1
            }
        }
        XCTAssertEqual(starts, 0, "This fixed noise fixture is acoustic nonspeech, not a semantic rule")
    }

    private func enabledDetector() -> VoiceTutorLocalSpeechDetector {
        var detector = VoiceTutorLocalSpeechDetector()
        _ = detector.updateGate(mediaReady: true, muted: false)
        return detector
    }

    private func event(_ activity: VoiceTutorLocalSpeechActivity, _ sequence: Int) -> VoiceTutorLocalSpeechEvent {
        VoiceTutorLocalSpeechEvent(activity: activity, sequence: sequence)
    }

    private func feed(
        _ detector: inout VoiceTutorLocalSpeechDetector, probability: Double, frames: Int
    ) -> [VoiceTutorLocalSpeechEvent] {
        (0..<frames).compactMap { _ in detector.process(speechProbability: probability, duration: frameDuration) }
    }

    private func syntheticNoise(frameCount: Int) -> [[Float]] {
        var state: UInt64 = 0x5349_4C45_524F
        return (0..<frameCount).map { _ in
            (0..<512).map { _ in
                state = state &* 6_364_136_223_846_793_005 &+ 1
                let unit = Double(state >> 40) / Double(1 << 24)
                return Float((unit * 2 - 1) * 0.05)
            }
        }
    }
}

private final class SileroTestLog<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [Value] = []
    var values: [Value] { lock.lock(); defer { lock.unlock() }; return storage }
    func append(_ value: Value) { lock.lock(); storage.append(value); lock.unlock() }
}

private final class SileroTestScorer: VoiceTutorSpeechProbabilityScoring, @unchecked Sendable {
    private let lock = NSLock()
    private let values: [Double]
    private let firstPrediction: (@Sendable () -> Void)?
    private var callCount = 0
    private var storedFrames: [[Float]] = []
    private var resets = 0

    init(values: [Double], firstPrediction: (@Sendable () -> Void)? = nil) {
        self.values = values
        self.firstPrediction = firstPrediction
    }
    var frames: [[Float]] { lock.lock(); defer { lock.unlock() }; return storedFrames }
    var resetCount: Int { lock.lock(); defer { lock.unlock() }; return resets }
    func probability(for samples: [Float]) throws -> Double {
        lock.lock()
        let index = callCount
        callCount += 1
        storedFrames.append(samples)
        let probability = values[min(index, values.count - 1)]
        lock.unlock()
        if index == 0 { firstPrediction?() }
        return probability
    }
    func reset() { lock.lock(); resets += 1; lock.unlock() }
}

extension VoiceTutorSileroTests {
    /// Explicit, offline acoustic-quality probe. It writes synthesized system
    /// speech into memory, never plays it, opens a microphone or contacts the
    /// app/provider. It is not a substitute for real conversational validation.
    @MainActor
    func testOptInBundledSileroRecognizesSyntheticKoreanSpeechIncludingHesitation() async throws {
        guard ProcessInfo.processInfo.environment["BUDDYSTUDY_SILERO_SYNTHETIC_VOICE_TEST"] == "1" else {
            throw XCTSkip("Opt-in only: set TEST_RUNNER_BUDDYSTUDY_SILERO_SYNTHETIC_VOICE_TEST=1")
        }
        guard let voice = AVSpeechSynthesisVoice.speechVoices().first(where: {
            $0.language == "ko-KR" && !$0.voiceTraits.contains(.isPersonalVoice)
        }) else {
            throw XCTSkip("No installed non-personal Korean system voice; acoustic speech quality was not verified")
        }
        let scorer = try await VoiceTutorSileroSpeechScorer.prepareBundled()
        let cases = [
            (id: "short-affirmative", text: "응."),
            (id: "short-negative", text: "아니."),
            (id: "voiced-hesitation", text: "음…"),
            (id: "readiness", text: "학습을 시작할게."),
            (id: "topic-question", text: "레디스에서 키가 만료되면 어떻게 돼?")
        ]
        var rows: [[String: Any]] = []
        defer {
            let report: [String: Any] = [
                "model": "bundled-silero-v6-32ms", "source": "non-personal-system-synthesis",
                "microphone": false, "playback": false, "provider": false,
                "recordingFiles": false, "semanticJudgment": false, "cases": rows
            ]
            if let data = try? JSONSerialization.data(withJSONObject: report, options: [.sortedKeys]),
               let summary = String(data: data, encoding: .utf8) {
                let attachment = XCTAttachment(string: summary)
                attachment.name = "silero-synthetic-acoustic-metadata-only"
                attachment.lifetime = .keepAlways
                add(attachment)
            }
        }
        for example in cases {
            let clip: SileroSyntheticClip
            do { clip = try await renderSyntheticSpeech(example.text, voice: voice) }
            catch SileroSyntheticError.noAudio {
                throw XCTSkip("Installed Korean voice returned no synthetic frames; acoustic speech quality was not verified")
            }
            scorer.reset()
            let resampler = try VoiceTutorSileroResampler(inputSampleRate: clip.sampleRate)
            var assembler = VoiceTutorSileroFrameAssembler()
            var detector = enabledDetector()
            var events: [VoiceTutorLocalSpeechEvent] = []
            var latencies: [Double] = []
            // A real continuous pre-roll/tail advances recurrent state. There
            // is no phrase-specific threshold, word rule, padding per callback
            // or direct injection of an expected speech probability.
            let input = [Float](repeating: 0, count: Int(clip.sampleRate * 0.2)) + clip.samples +
                [Float](repeating: 0, count: Int(clip.sampleRate * 1.28))
            let packetSize = max(1, Int(clip.sampleRate / 100))
            for start in stride(from: 0, to: input.count, by: packetSize) {
                try Task.checkCancellation()
                let converted = try resampler.convert(Array(input[start..<min(start + packetSize, input.count)]))
                for frame in try assembler.append(converted) {
                    let started = ProcessInfo.processInfo.systemUptime
                    let probability = try scorer.probability(for: frame)
                    latencies.append((ProcessInfo.processInfo.systemUptime - started) * 1_000)
                    if let event = detector.process(speechProbability: probability, duration: frameDuration) {
                        events.append(event)
                    }
                }
            }
            let started = events.contains { $0.activity == .started }
            let stopped = events.last?.activity == .stopped
            let sorted = latencies.sorted()
            let p95 = sorted.isEmpty ? 0 : sorted[min(sorted.count - 1, Int(Double(sorted.count) * 0.95))]
            rows.append([
                "case": example.id, "syntheticFrames": clip.samples.count,
                "sampleRate": Int(clip.sampleRate), "predictions": latencies.count,
                "speechStarted": started, "speechStopped": stopped,
                "p95PredictionMs": p95, "maxPredictionMs": latencies.max() ?? 0
            ])
            XCTAssertTrue(started, "\(example.id) must be acoustic speech; meaningfulness belongs to the backend")
            XCTAssertTrue(stopped, "\(example.id) must release after its continuous silent tail")
            XCTAssertFalse(detector.isSpeaking)
            XCTAssertLessThan(latencies.max() ?? .infinity,
                              VoiceTutorSileroAudioPipeline.maximumPredictionSeconds * 1_000)
        }
    }

    @MainActor
    private func renderSyntheticSpeech(
        _ text: String, voice: AVSpeechSynthesisVoice
    ) async throws -> SileroSyntheticClip {
        let synthesizer = AVSpeechSynthesizer()
        let collector = SileroSyntheticCollector()
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = voice
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate
        let deadline = Task { @MainActor in
            do { try await Task.sleep(nanoseconds: 6_000_000_000) }
            catch { return }
            collector.finish(.failure(SileroSyntheticError.timedOut))
            synthesizer.stopSpeaking(at: .immediate)
        }
        defer {
            deadline.cancel()
            synthesizer.stopSpeaking(at: .immediate)
        }
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                collector.install(continuation)
                synthesizer.write(utterance) { buffer in collector.consume(buffer) }
            }
        } onCancel: {
            collector.finish(.failure(CancellationError()))
        }
    }
}

private struct SileroSyntheticClip: Sendable {
    let sampleRate: Double
    let samples: [Float]
}

private enum SileroSyntheticError: Error {
    case noAudio, unsupportedFormat, audioTooLong, timedOut
}

private final class SileroSyntheticCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var sampleRate: Double = 0
    private var samples: [Float] = []
    private var continuation: CheckedContinuation<SileroSyntheticClip, Error>?
    private var result: Result<SileroSyntheticClip, Error>?

    func install(_ continuation: CheckedContinuation<SileroSyntheticClip, Error>) {
        lock.lock()
        let finished = result
        if finished == nil { self.continuation = continuation }
        lock.unlock()
        if let finished { continuation.resume(with: finished) }
    }

    func consume(_ buffer: AVAudioBuffer) {
        guard let pcm = buffer as? AVAudioPCMBuffer else {
            finish(.failure(SileroSyntheticError.unsupportedFormat))
            return
        }
        let frames = Int(pcm.frameLength)
        if frames == 0 {
            lock.lock()
            let clip = SileroSyntheticClip(sampleRate: sampleRate, samples: samples)
            lock.unlock()
            finish(clip.samples.isEmpty ? .failure(SileroSyntheticError.noAudio) : .success(clip))
            return
        }
        let rate = pcm.format.sampleRate
        let channels = Int(pcm.format.channelCount)
        guard rate.isFinite, (8_000...192_000).contains(rate), channels > 0, channels <= 2 else {
            finish(.failure(SileroSyntheticError.unsupportedFormat))
            return
        }
        let stride = pcm.format.isInterleaved ? channels : 1
        var copied: [Float] = []
        copied.reserveCapacity(frames)
        if let data = pcm.floatChannelData?[0] {
            for index in 0..<frames { copied.append(data[index * stride]) }
        } else if let data = pcm.int16ChannelData?[0] {
            for index in 0..<frames { copied.append(Float(data[index * stride]) / 32_768) }
        } else {
            finish(.failure(SileroSyntheticError.unsupportedFormat))
            return
        }
        guard copied.allSatisfy(\.isFinite) else {
            finish(.failure(SileroSyntheticError.unsupportedFormat))
            return
        }
        lock.lock()
        guard result == nil else { lock.unlock(); return }
        guard sampleRate == 0 || sampleRate == rate else {
            lock.unlock()
            finish(.failure(SileroSyntheticError.unsupportedFormat))
            return
        }
        guard samples.count + copied.count <= Int(rate * 6) else {
            lock.unlock()
            finish(.failure(SileroSyntheticError.audioTooLong))
            return
        }
        sampleRate = rate
        samples.append(contentsOf: copied)
        lock.unlock()
    }

    func finish(_ result: Result<SileroSyntheticClip, Error>) {
        lock.lock()
        guard self.result == nil else { lock.unlock(); return }
        self.result = result
        samples.removeAll(keepingCapacity: false)
        let pending = continuation
        continuation = nil
        lock.unlock()
        pending?.resume(with: result)
    }
}
