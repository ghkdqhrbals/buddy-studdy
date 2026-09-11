import AVFoundation
import CoreMedia
import Speech
import XCTest
@testable import StudyMate

final class VoiceTutorInterruptionBoundaryTests: XCTestCase {
    private let voiced = VoiceTutorInterruptionAudioLevel(rms: 0.08, peak: 0.18)
    private let quiet = VoiceTutorInterruptionAudioLevel(rms: 0.0005, peak: 0.001)

    func testInterruptionWaitsThroughSoundAndUsesTheNextSustainedQuietGap() throws {
        var state = VoiceTutorInterruptionBoundaryState()
        state.responseStarted("spoken-answer")
        let token = try XCTUnwrap(state.request(responseID: "spoken-answer", at: 10))
        for tick in 1...10 {
            let now = 10 + Double(tick) * 0.01
            state.observe(level: voiced, duration: 0.01, at: now)
            guard case .wait = state.decision(for: token, now: now) else {
                return XCTFail("An ongoing sound should receive the bounded grace.")
            }
        }
        for tick in 11...13 {
            let now = 10 + Double(tick) * 0.01
            state.observe(level: quiet, duration: 0.01, at: now)
            guard case .wait = state.decision(for: token, now: now) else {
                return XCTFail("A gap shorter than 40 ms must not stop playout.")
            }
        }
        state.observe(level: quiet, duration: 0.01, at: 10.14)
        XCTAssertEqual(state.decision(for: token, now: 10.14), .quietGap,
                       "The first short word gap must stop without waiting for a longer phrase pause.")
    }

    func testBriefPhonemeGapAndLowRMSWithSharpPeaksDoNotReleaseTheWait() throws {
        var state = VoiceTutorInterruptionBoundaryState()
        state.responseStarted("answer")
        let token = try XCTUnwrap(state.request(responseID: "answer", at: 20))
        for tick in 1...3 { state.observe(level: quiet, duration: 0.01, at: 20 + Double(tick) * 0.01) }
        state.observe(level: voiced, duration: 0.01, at: 20.04)
        for tick in 5...7 { state.observe(level: quiet, duration: 0.01, at: 20 + Double(tick) * 0.01) }
        guard case .wait = state.decision(for: token, now: 20.07) else {
            return XCTFail("Separate short gaps cannot accumulate across speech.")
        }
        let consonant = VoiceTutorInterruptionAudioLevel(rms: 0.001, peak: 0.02)
        for tick in 8...20 { state.observe(level: consonant, duration: 0.01, at: 20 + Double(tick) * 0.01) }
        guard case .wait = state.decision(for: token, now: 20.20) else {
            return XCTFail("RMS alone cannot classify a quiet consonant as a pause.")
        }
    }

    func testContinuousSpeechMissingCallbacksAndInvalidPCMRespectTheHardDeadline() throws {
        let levels: [VoiceTutorInterruptionAudioLevel?] = [voiced, nil, .init(rms: .nan, peak: 1)]
        for level in levels {
            var state = VoiceTutorInterruptionBoundaryState()
            state.responseStarted("answer")
            let token = try XCTUnwrap(state.request(responseID: "answer", at: 30))
            for tick in 1...74 {
                let now = 30 + Double(tick) * 0.01
                state.observe(level: level, duration: 0.01, at: now)
                guard case .wait = state.decision(for: token, now: now) else {
                    return XCTFail("Unknown or ongoing audio must not invent a boundary.")
                }
            }
            XCTAssertEqual(state.decision(for: token, now: 30.75), .deadline)
            XCTAssertEqual(state.decision(for: token, now: 300), .deadline,
                "Continued callbacks cannot extend a user's interruption indefinitely.")
        }
    }

    func testAWordCanFinishBeyondTheFormerCutoffWithoutWaitingForTheWholeReply() throws {
        var state = VoiceTutorInterruptionBoundaryState()
        state.responseStarted("answer")
        let token = try XCTUnwrap(state.request(responseID: "answer", at: 60))
        for tick in 1...65 {
            let now = 60 + Double(tick) * 0.01
            state.observe(level: voiced, duration: 0.01, at: now)
            guard case .wait = state.decision(for: token, now: now) else {
                return XCTFail("The former 450 ms deadline must not cut this still-running word")
            }
        }
        for tick in 66...69 { state.observe(level: quiet, duration: 0.01, at: 60 + Double(tick) * 0.01) }
        XCTAssertEqual(state.decision(for: token, now: 60.69), .quietGap)
        XCTAssertLessThan(0.69, VoiceTutorInterruptionBoundaryState.maximumGraceSeconds)
    }

    func testResultArrivalAndLearnerInterruptionShareTheFirstWordDeadline() throws {
        var state = VoiceTutorInterruptionBoundaryState()
        state.responseStarted("waiting-notice")
        let learner = try XCTUnwrap(state.request(responseID: "waiting-notice", at: 100))
        let grading = try XCTUnwrap(state.request(responseID: "waiting-notice", at: 100.5))
        XCTAssertEqual(grading, learner, "A second interruption cannot buy the old speech another word.")
        XCTAssertEqual(state.decision(for: grading, now: 100.75), .deadline)
        XCTAssertNil(state.request(responseID: "waiting-notice", at: 99))
        state.responseStarted("grading-feedback")
        let next = try XCTUnwrap(state.request(responseID: "grading-feedback", at: 101))
        XCTAssertEqual(next.requestedAtUptime, 101, "The next response owns an independent deadline.")
    }

    func testRecentSilenceBeforeTheRequestCannotSkipTheNextBoundary() throws {
        var state = VoiceTutorInterruptionBoundaryState()
        state.responseStarted("answer")
        for tick in 1...9 { state.observe(level: quiet, duration: 0.01, at: 70 + Double(tick) * 0.01) }
        let token = try XCTUnwrap(state.request(responseID: "answer", at: 70.10))
        guard case .wait = state.decision(for: token, now: 70.10) else {
            return XCTFail("A fresh but earlier gap is not an observation after the user's request")
        }
        state.observe(level: voiced, duration: 0.01, at: 70.11)
        guard case .wait = state.decision(for: token, now: 70.11) else {
            return XCTFail("The current voiced sound must retain its boundary opportunity")
        }
    }

    func testGainFadeIsContinuousMonotonicAndEndsWithinItsShortBound() throws {
        var fade = VoiceTutorInterruptionFadeState()
        fade.responseStarted("answer")
        let token = try XCTUnwrap(fade.request(responseID: "answer", at: 80))
        XCTAssertEqual(fade.gain(for: token, at: 80), 1)
        var previous: Float = 1
        for tick in 1...10 {
            let gain = try XCTUnwrap(fade.gain(for: token, at: 80 + Double(tick) * 0.004))
            XCTAssertLessThanOrEqual(gain, previous)
            XCTAssertGreaterThanOrEqual(gain, 0)
            if tick < 10 { XCTAssertGreaterThan(gain, 0, "A fade must not immediately hard-mute the source") }
            previous = gain
        }
        XCTAssertEqual(try XCTUnwrap(fade.gain(for: token, at: 80.02)), 0.5, accuracy: 0.000_01)
        XCTAssertEqual(fade.gain(for: token, at: 80.05), 0)
        XCTAssertEqual(fade.currentGain(at: 100), 0, "Completed fading stays silent until the next response")
    }

    func testDuplicateResponseEventsCannotReopenAFadeAndOldTokensCannotMuteNewSpeech() throws {
        var fade = VoiceTutorInterruptionFadeState()
        fade.responseStarted("old")
        let old = try XCTUnwrap(fade.request(responseID: "old", at: 90))
        fade.responseStarted("old")
        XCTAssertEqual(try XCTUnwrap(fade.gain(for: old, at: 90.02)), 0.5, accuracy: 0.000_01)
        XCTAssertEqual(fade.request(responseID: "old", at: 90.02), old,
                       "Repeated interruption requests must not restart the ramp")
        fade.responseStarted("new")
        XCTAssertNil(fade.gain(for: old, at: 90.05))
        XCTAssertEqual(fade.currentGain(at: 90.05), 1)
        XCTAssertNil(fade.request(responseID: "old", at: 90.05))
        let next = try XCTUnwrap(fade.request(responseID: "new", at: 90.06))
        fade.invalidate()
        XCTAssertNil(fade.gain(for: next, at: 90.07))
        XCTAssertNil(fade.request(responseID: "new", at: 90.07))
    }

    func testOldQuietAudioAndRepeatedCallbacksCannotInventANewGap() throws {
        var state = VoiceTutorInterruptionBoundaryState()
        state.responseStarted("answer")
        for tick in 1...6 { state.observe(level: quiet, duration: 0.01, at: 40 + Double(tick) * 0.01) }
        let token = try XCTUnwrap(state.request(responseID: "answer", at: 41))
        guard case .wait = state.decision(for: token, now: 41) else {
            return XCTFail("A suspended renderer's earlier silence is not a current gap.")
        }
        state.observe(level: quiet, duration: 0.01, at: 41.01)
        for _ in 1...100 { state.observe(level: quiet, duration: 0.01, at: 41.01) }
        guard case .wait = state.decision(for: token, now: 41.01) else {
            return XCTFail("Duplicate callbacks must not count the same samples repeatedly.")
        }
        state.observe(level: nil, duration: 0.01, at: 41.02)
        state.observe(level: quiet, duration: .nan, at: 41.03)
        guard case .wait = state.decision(for: token, now: 41.03) else {
            return XCTFail("Invalid audio metadata must not become a silence observation.")
        }
    }

    func testReplacementInterruptionAndCloseInvalidateOnlyTheExactResponseWait() throws {
        var state = VoiceTutorInterruptionBoundaryState()
        state.responseStarted("old")
        let old = try XCTUnwrap(state.request(responseID: "old", at: 50))
        state.responseStarted("new")
        XCTAssertEqual(state.decision(for: old, now: 50.01), .superseded)
        let new = try XCTUnwrap(state.request(responseID: "new", at: 50.01))
        state.invalidate(responseID: "old")
        guard case .wait = state.decision(for: new, now: 50.02) else {
            return XCTFail("A delayed old-response interruption must not stop the replacement wait.")
        }
        state.invalidate(responseID: "new")
        XCTAssertEqual(state.decision(for: new, now: 50.03), .superseded)
        state.responseStarted("last")
        let last = try XCTUnwrap(state.request(responseID: "last", at: 51))
        state.invalidate()
        XCTAssertEqual(state.decision(for: last, now: 51.01), .superseded)
        XCTAssertNil(state.request(responseID: "last", at: 51.01))
    }

    func testNativePCMMeasurementPreservesSamplesAndRejectsUnknownAudio() throws {
        for interleaved in [true, false] {
            let format = try XCTUnwrap(AVAudioFormat(
                commonFormat: .pcmFormatInt16, sampleRate: 48_000, channels: 2, interleaved: interleaved
            ))
            let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 480))
            buffer.frameLength = 480
            let channels = try XCTUnwrap(buffer.int16ChannelData)
            for channel in 0..<(interleaved ? 1 : 2) {
                for index in 0..<(interleaved ? 960 : 480) { channels[channel][index] = 16 }
            }
            let quietLevel = try XCTUnwrap(VoiceTutorRemoteAudioRenderer.interruptionAudioLevel(buffer))
            XCTAssertTrue(quietLevel.isQuiet)
            channels[interleaved ? 0 : 1][interleaved ? 959 : 479] = 2_000
            let peakLevel = try XCTUnwrap(VoiceTutorRemoteAudioRenderer.interruptionAudioLevel(buffer))
            XCTAssertFalse(peakLevel.isQuiet)
            XCTAssertEqual(peakLevel.peak, Double(2_000) / 32_768, accuracy: 1e-12)
            XCTAssertEqual(channels[0][0], 16, "Boundary inspection must never modify audible PCM.")
            XCTAssertEqual(channels[interleaved ? 0 : 1][interleaved ? 959 : 479], 2_000)
        }
        let floatFormat = try XCTUnwrap(AVAudioFormat(
            commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 1, interleaved: false
        ))
        let invalid = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: floatFormat, frameCapacity: 1))
        invalid.frameLength = 1
        invalid.floatChannelData?[0][0] = .nan
        XCTAssertNil(VoiceTutorRemoteAudioRenderer.interruptionAudioLevel(invalid))
        invalid.frameLength = 0
        XCTAssertNil(VoiceTutorRemoteAudioRenderer.interruptionAudioLevel(invalid))
    }

    func testServerFinishWordRequestRoundTripsOnlyItsExactResponseAndRequestFences() throws {
        let request = try XCTUnwrap(VoiceTutorFinishWordRequest(responseID: "resp_1", requestID: "request-2"))
        XCTAssertEqual(try VoiceTutorRealtimeEventParser.parse(text:
            #"{"type":"buddystudy.voice.response.finish_word","responseId":"resp_1","requestId":"request-2"}"#),
            .finishWordRequested(request))
        XCTAssertEqual(request.acknowledgementPayload, [
            "type": "buddystudy.voice.response.word_finished", "responseId": "resp_1", "requestId": "request-2"
        ])
        let malformedPayloads: [[String: Any]] = [
            ["responseId": "resp_1"], ["responseId": "resp_1", "requestId": ""],
            ["responseId": " ", "requestId": "request-2"],
            ["responseId": "resp_1", "requestId": String(repeating: "a", count: 192)],
            ["responseId": "resp_1", "requestId": 2],
            ["responseId": "resp_1", "requestId": "request-2", "recordId": "42"]
        ]
        for malformed in malformedPayloads {
            var payload = malformed
            payload["type"] = "buddystudy.voice.response.finish_word"
            XCTAssertEqual(try VoiceTutorRealtimeEventParser.parse(data: JSONSerialization.data(withJSONObject: payload)),
                           .ignored(type: "buddystudy.voice.response.finish_word"))
        }
    }

    func testWordFinishedAcknowledgementCannotUseAnotherConnectionAttempt() async throws {
        let transport = VoiceTutorWebSocketTransport()
        let request = try XCTUnwrap(VoiceTutorFinishWordRequest(responseID: "resp_1", requestID: "request-2"))
        do {
            try await transport.sendWordFinished(request, attemptID: UUID())
            XCTFail("A stale boundary task must not send an acknowledgement.")
        } catch {
            XCTAssertEqual(error as? VoiceTutorLocalSpeechDeliveryError, .staleAttempt)
        }
    }
}

/// A local, opt-in device capability probe. It never opens the microphone, requests
/// speech authorization, installs language assets, or contacts the application API.
final class VoiceTutorWordAlignmentCapabilityTests: XCTestCase {
    func testInstalledOnDeviceWordAlignmentCapabilities() async throws {
        guard ProcessInfo.processInfo.environment["BUDDYSTUDY_WORD_ALIGNMENT_PROBE"] == "1" else {
            throw XCTSkip("Opt in only when inspecting a connected iPhone's installed speech assets.")
        }
        guard #available(iOS 26.0, *) else {
            throw XCTSkip("SpeechAnalyzer requires iOS 26.")
        }

        let available = SpeechTranscriber.isAvailable
        let supported = try await WordAlignmentProbeDeadline.value("speech-supported-locales") { await SpeechTranscriber.supportedLocales }
        let installed = try await WordAlignmentProbeDeadline.value("speech-installed-locales") { await SpeechTranscriber.installedLocales }
        var localeReports: [[String: Any]] = []
        for identifier in ["ko-KR", "en-US"] {
            let equivalent = try await WordAlignmentProbeDeadline.value("speech-equivalent-locale") { await SpeechTranscriber.supportedLocale(equivalentTo: Locale(identifier: identifier)) }
            let normalized = equivalent?.identifier.replacingOccurrences(of: "_", with: "-")
            let hasInstalledAsset = normalized.map { expected in
                installed.contains { $0.identifier.replacingOccurrences(of: "_", with: "-") == expected }
            } ?? false
            var report: [String: Any] = [
                "requestedLocale": identifier,
                "supported": equivalent != nil,
                "installed": hasInstalledAsset,
                "compatibleFormats": [[String: Any]]()
            ]
            if let equivalent {
                report["equivalentLocale"] = equivalent.identifier
                let transcriber = SpeechTranscriber(locale: equivalent, preset: .timeIndexedProgressiveTranscription)
                let formats = try await WordAlignmentProbeDeadline.value("compatible-formats") { await transcriber.availableCompatibleAudioFormats }
                report["compatibleFormats"] = formats.map {
                    ["sampleRate": $0.sampleRate, "channels": $0.channelCount,
                     "commonFormat": $0.commonFormat.rawValue, "interleaved": $0.isInterleaved] as [String: Any]
                }
            }
            localeReports.append(report)
        }

        let dictationSupported = try await WordAlignmentProbeDeadline.value("dictation-supported-locales") { await DictationTranscriber.supportedLocales }
        let dictationInstalled = try await WordAlignmentProbeDeadline.value("dictation-installed-locales") { await DictationTranscriber.installedLocales }
        var dictationReports: [[String: Any]] = []
        for identifier in ["ko-KR", "en-US"] {
            let equivalent = try await WordAlignmentProbeDeadline.value("dictation-equivalent-locale") { await DictationTranscriber.supportedLocale(equivalentTo: Locale(identifier: identifier)) }
            let hasInstalledAsset = equivalent.map { locale in
                dictationInstalled.contains {
                    $0.identifier.replacingOccurrences(of: "_", with: "-") == locale.identifier.replacingOccurrences(of: "_", with: "-")
                }
            } ?? false
            var report: [String: Any] = ["requestedLocale": identifier, "supported": equivalent != nil,
                                         "installed": hasInstalledAsset, "compatibleFormats": [[String: Any]]()]
            if let equivalent {
                report["equivalentLocale"] = equivalent.identifier
                let transcriber = DictationTranscriber(locale: equivalent, preset: .timeIndexedLongDictation)
                let formats = try await WordAlignmentProbeDeadline.value("compatible-formats") { await transcriber.availableCompatibleAudioFormats }
                report["compatibleFormats"] = formats.map {
                    ["sampleRate": $0.sampleRate, "channels": $0.channelCount,
                     "commonFormat": $0.commonFormat.rawValue, "interleaved": $0.isInterleaved] as [String: Any]
                }
            }
            dictationReports.append(report)
        }
        let data = try JSONSerialization.data(withJSONObject: [
            "dictationSupportedLocaleCount": dictationSupported.count,
            "dictationInstalledLocaleCount": dictationInstalled.count,
            "dictationLocales": dictationReports,
            "speechTranscriberAvailable": available,
            "supportedLocaleCount": supported.count,
            "installedLocaleCount": installed.count,
            "locales": localeReports,
            "assetInstallationRequested": false,
            "microphoneOpened": false,
            "speechAuthorizationRequested": false
        ], options: [.sortedKeys, .prettyPrinted])
        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.json")
        attachment.name = "voice-word-alignment-installed-capabilities"
        attachment.lifetime = .keepAlways
        add(attachment)
        print("BUDDYSTUDY_WORD_ALIGNMENT_CAPABILITIES " + String(decoding: data, as: UTF8.self))
    }
}

/// Keeps the timestamp experiment separate from production playback. All audio is
/// a fixed Korean fixture rendered into memory/file output by Apple's local TTS.
/// Neither microphone capture nor any asset-download API is used by this harness.
final class VoiceTutorWordAlignmentLatencyTests: XCTestCase {
    @MainActor
    func testInstalledKoreanEngineWordTimestampArrivalAtRealtimePace() async throws {
        guard ProcessInfo.processInfo.environment["BUDDYSTUDY_WORD_ALIGNMENT_PROBE"] == "1" else {
            throw XCTSkip("Opt in to the local synthetic word-timestamp experiment.")
        }
        guard #available(iOS 26.0, *) else { throw XCTSkip("SpeechAnalyzer requires iOS 26.") }
        executionTimeAllowance = 120
        let requested = Locale(identifier: "ko-KR")
        let speechLocale = try await WordAlignmentProbeDeadline.value("speech-korean-locale") { await SpeechTranscriber.supportedLocale(equivalentTo: requested) }
        let dictationLocale = try await WordAlignmentProbeDeadline.value("dictation-korean-locale") { await DictationTranscriber.supportedLocale(equivalentTo: requested) }
        let speechInstalled = try await WordAlignmentProbeDeadline.value("speech-installed-locales") { await SpeechTranscriber.installedLocales }
        let dictationInstalled = try await WordAlignmentProbeDeadline.value("dictation-installed-locales") { await DictationTranscriber.installedLocales }
        func installed(_ locale: Locale?, in locales: [Locale]) -> Bool {
            guard let locale else { return false }
            return locales.contains {
                $0.identifier.replacingOccurrences(of: "_", with: "-") == locale.identifier.replacingOccurrences(of: "_", with: "-")
            }
        }
        var engines: [WordAlignmentProbeEngine] = []
        let periodicOnly = ProcessInfo.processInfo.environment["BUDDYSTUDY_WORD_ALIGNMENT_FINALIZE_PROBE"] == "1"
        if !periodicOnly, SpeechTranscriber.isAvailable, let speechLocale, installed(speechLocale, in: speechInstalled) {
            engines.append(.speech(SpeechTranscriber(locale: speechLocale, preset: .timeIndexedProgressiveTranscription)))
        }
        if let dictationLocale, installed(dictationLocale, in: dictationInstalled) {
            if periodicOnly {
                engines.append(.periodicDictation(DictationTranscriber(
                    locale: dictationLocale, contentHints: [], transcriptionOptions: [],
                    reportingOptions: [.volatileResults], attributeOptions: [.audioTimeRange]
                )))
            } else {
                engines.append(.dictation(DictationTranscriber(
                    locale: dictationLocale, contentHints: [], transcriptionOptions: [],
                    reportingOptions: [.volatileResults], attributeOptions: [.audioTimeRange]
                )))
                engines.append(.frequentDictation(DictationTranscriber(
                    locale: dictationLocale, contentHints: [], transcriptionOptions: [],
                    reportingOptions: [.volatileResults, .frequentFinalization], attributeOptions: [.audioTimeRange]
                )))
            }
        }
        attach([
            "locale": "ko-KR", "speechSupported": speechLocale != nil,
            "speechInstalled": installed(speechLocale, in: speechInstalled),
            "dictationSupported": dictationLocale != nil,
            "dictationInstalled": installed(dictationLocale, in: dictationInstalled),
            "runnableEngines": engines.map(\.name), "modelDownloadsRequested": false,
            "microphoneOpened": false, "audioSource": "fixed-local-apple-tts-fixture"
        ], name: "voice-word-alignment-latency-availability")
        guard !engines.isEmpty else {
            throw XCTSkip("No supported Korean transcriber has installed assets; no model was downloaded.")
        }
        guard let voice = AVSpeechSynthesisVoice.speechVoices().first(where: {
            $0.language.replacingOccurrences(of: "_", with: "-") == "ko-KR"
        }) else { throw XCTSkip("No installed Korean Apple synthesis voice; no voice was downloaded.") }
        let url = try await WordAlignmentProbeSynthesis.render(voice: voice)
        defer { try? FileManager.default.removeItem(at: url) }
        if ProcessInfo.processInfo.environment["BUDDYSTUDY_WORD_ALIGNMENT_EXPORT_FIXTURE"] == "1" {
            let directory = try FileManager.default.url(for: .documentDirectory, in: .userDomainMask,
                                                         appropriateFor: nil, create: true)
            let destination = directory.appendingPathComponent("word-alignment-fixed-korean.caf")
            if FileManager.default.fileExists(atPath: destination.path) { try FileManager.default.removeItem(at: destination) }
            try FileManager.default.copyItem(at: url, to: destination)
            attach(["fixtureExported": true, "filename": destination.lastPathComponent,
                    "source": "fixed-local-apple-tts-fixture", "analysisStarted": false],
                   name: "voice-word-alignment-fixture-export")
            return
        }
        for engine in engines {
            do {
                let report = try await WordAlignmentProbeRunner.run(engine: engine, fixtureURL: url)
                let data = try JSONEncoder().encode(report)
                let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.json")
                attachment.name = "voice-word-alignment-latency-\(engine.name)"
                attachment.lifetime = .keepAlways
                add(attachment)
                print("BUDDYSTUDY_WORD_ALIGNMENT_LATENCY " + String(decoding: data, as: UTF8.self))
                XCTAssertFalse(report.timedOut, "A stalled local engine cannot establish a usable lookahead bound.")
                XCTAssertGreaterThan(report.timedRuns.count, 0, "No word-time attributes were observed.")
            } catch {
                let nsError = error as NSError
                attach(["engine": engine.name, "errorDomain": nsError.domain, "errorCode": nsError.code],
                       name: "voice-word-alignment-latency-\(engine.name)-error")
                XCTFail("Local \(engine.name) probe failed: \(nsError.domain) / \(nsError.code)")
            }
        }
    }

    private func attach(_ metadata: [String: Any], name: String) {
        guard let data = try? JSONSerialization.data(withJSONObject: metadata, options: [.sortedKeys]) else { return }
        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.json")
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        print("BUDDYSTUDY_WORD_ALIGNMENT_LATENCY_METADATA " + String(decoding: data, as: UTF8.self))
    }
}

@available(iOS 26.0, *)
private enum WordAlignmentProbeEngine: Sendable {
    case speech(SpeechTranscriber)
    case dictation(DictationTranscriber)
    case frequentDictation(DictationTranscriber)
    case periodicDictation(DictationTranscriber)

    var usesPeriodicFinalization: Bool {
        if case .periodicDictation = self { return true }
        return false
    }

    var name: String {
        switch self {
        case .speech: "speech-transcriber"
        case .dictation: "dictation-transcriber"
        case .frequentDictation: "dictation-frequent-finalization"
        case .periodicDictation: "dictation-periodic-finalization-500ms-with-500ms-context"
        }
    }

    var module: any SpeechModule {
        switch self {
        case .speech(let value): value
        case .dictation(let value), .frequentDictation(let value), .periodicDictation(let value): value
        }
    }

    func collect(into timeline: WordAlignmentProbeTimeline) async throws {
        switch self {
        case .speech(let transcriber):
            for try await result in transcriber.results {
                let receivedAt = ProcessInfo.processInfo.systemUptime
                await timeline.record(result.text, isFinal: result.isFinal, receivedAt: receivedAt)
            }
        case .dictation(let transcriber), .frequentDictation(let transcriber), .periodicDictation(let transcriber):
            for try await result in transcriber.results {
                let receivedAt = ProcessInfo.processInfo.systemUptime
                await timeline.record(result.text, isFinal: result.isFinal, receivedAt: receivedAt)
            }
        }
    }
}

private struct WordAlignmentProbeReport: Codable, Sendable {
    struct ExplicitFinalization: Codable, Sendable {
        let throughSeconds: Double
        let requestedSeconds: Double
        let returnedSeconds: Double
    }
    struct TimedRun: Codable, Sendable {
        let text: String
        let startSeconds: Double
        let endSeconds: Double
        let receivedSeconds: Double
        let inputThroughSeconds: Double
        let delayAfterWordEndSeconds: Double
        let isFinal: Bool
        let whitespaceDelimitedWordCount: Int
    }
    let engine: String
    let sampleRate: Double
    let chunkMilliseconds: Int
    let sourceDurationSeconds: Double
    let postrollSilenceSeconds: Double
    let timedOut: Bool
    let timedRuns: [TimedRun]
    let explicitFinalizations: [ExplicitFinalization]
    let fixedSyntheticSourceText: String
    let firstObservedTimedRunCount: Int
    let firstObservedTimedRunsWithinOneSecond: Int
    let firstObservedMaximumDelaySeconds: Double?
    let finalTimedRunCount: Int
    let finalTimedRunsWithinOneSecond: Int
    let finalMaximumDelaySeconds: Double?
    let note: String
}

@available(iOS 26.0, *)
private actor WordAlignmentProbeTimeline {
    private var beganAt = ProcessInfo.processInfo.systemUptime
    private var inputThroughSeconds: Double = 0
    private var timedOut = false
    private var runs: [WordAlignmentProbeReport.TimedRun] = []
    private var finalizations: [WordAlignmentProbeReport.ExplicitFinalization] = []

    func begin(at uptime: Double) { beganAt = uptime }
    func submitted(through seconds: Double) { inputThroughSeconds = seconds }
    func timeout() { timedOut = true }
    func submittedThrough() -> Double { inputThroughSeconds }
    func recordFinalization(through: Double, requestedAt: Double, returnedAt: Double) {
        finalizations.append(.init(throughSeconds: through, requestedSeconds: requestedAt - beganAt,
                                   returnedSeconds: returnedAt - beganAt))
    }

    func record(_ text: AttributedString, isFinal: Bool, receivedAt: Double) {
        for run in text.runs {
            guard let range = run.audioTimeRange else { continue }
            let start = range.start.seconds
            let end = range.end.seconds
            guard start.isFinite, end.isFinite else { continue }
            let words = String(text[run.range].characters)
            guard !words.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { continue }
            runs.append(.init(text: words, startSeconds: start, endSeconds: end,
                              receivedSeconds: receivedAt - beganAt, inputThroughSeconds: inputThroughSeconds,
                              delayAfterWordEndSeconds: receivedAt - beganAt - end, isFinal: isFinal,
                              whitespaceDelimitedWordCount: words.split(whereSeparator: \.isWhitespace).count))
        }
    }

    func report(engine: String, format: AVAudioFormat, sourceDuration: Double) -> WordAlignmentProbeReport {
        // This conservative key keeps timing revisions visible rather than presenting
        // changing provisional offsets as stable, recognized word boundaries.
        var seen = Set<String>()
        let first = runs.filter { run in
            seen.insert("\(run.text)|\(run.startSeconds)|\(run.endSeconds)").inserted
        }
        let final = runs.filter(\.isFinal)
        return .init(engine: engine, sampleRate: format.sampleRate, chunkMilliseconds: 10,
                     sourceDurationSeconds: sourceDuration, postrollSilenceSeconds: 2,
                     timedOut: timedOut, timedRuns: runs, explicitFinalizations: finalizations,
                     fixedSyntheticSourceText: WordAlignmentProbeSynthesis.fixtureText,
                     firstObservedTimedRunCount: first.count,
                     firstObservedTimedRunsWithinOneSecond: first.filter { $0.delayAfterWordEndSeconds <= 1 }.count,
                     firstObservedMaximumDelaySeconds: first.map(\.delayAfterWordEndSeconds).max(),
                     finalTimedRunCount: final.count,
                     finalTimedRunsWithinOneSecond: final.filter { $0.delayAfterWordEndSeconds <= 1 }.count,
                     finalMaximumDelaySeconds: final.map(\.delayAfterWordEndSeconds).max(),
                     note: "Synthetic Apple Korean speech, paced 10 ms PCM. Provisional word offsets may change. This measures availability/latency, not an acoustic guarantee for OpenAI speech.")
    }
}

@available(iOS 26.0, *)
private enum WordAlignmentProbeRunner {
    static func run(engine: WordAlignmentProbeEngine, fixtureURL: URL) async throws -> WordAlignmentProbeReport {
        let analyzer = SpeechAnalyzer(modules: [engine.module])
        guard let format = try await WordAlignmentProbeDeadline.value("analyzer-compatible-format", operation: {
            await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [engine.module])
        }) else {
            throw WordAlignmentProbeError.noCompatibleFormat
        }
        let source = try convertedFixture(url: fixtureURL, format: format)
        let sourceDuration = Double(source.frameLength) / format.sampleRate
        let timeline = WordAlignmentProbeTimeline()
        let timeout = Task {
            try await Task.sleep(for: .seconds(sourceDuration + 20))
            await timeline.timeout()
            await analyzer.cancelAndFinishNow()
        }
        defer { timeout.cancel() }
        do {
            try await analyzer.prepareToAnalyze(in: format)
            let collected = Task { try await engine.collect(into: timeline) }
            let (stream, continuation) = AsyncStream<AnalyzerInput>.makeStream()
            defer { continuation.finish(); collected.cancel() }
            try await analyzer.start(inputSequence: stream)
            let beganAt = ProcessInfo.processInfo.systemUptime
            await timeline.begin(at: beganAt)
            let periodicFinalizer = Task {
                guard engine.usesPeriodicFinalization else { return }
                var step = 1
                var lastThrough: Double = 0
                while !Task.isCancelled {
                    let wait = beganAt + Double(step) * 0.5 - ProcessInfo.processInfo.systemUptime
                    do {
                        if wait > 0 { try await Task.sleep(for: .seconds(wait)) }
                        try Task.checkCancellation()
                        let submitted = await timeline.submittedThrough()
                        let through = max(0, submitted - 0.5)
                        if through > lastThrough {
                            let requestedAt = ProcessInfo.processInfo.systemUptime
                            try await analyzer.finalize(through: CMTime(seconds: through, preferredTimescale: 16_000))
                            await timeline.recordFinalization(through: through, requestedAt: requestedAt,
                                                               returnedAt: ProcessInfo.processInfo.systemUptime)
                            lastThrough = through
                        }
                    } catch { return }
                    step += 1
                }
            }
            defer { periodicFinalizer.cancel() }
            let framesPerChunk = max(1, Int(format.sampleRate / 100))
            let totalFrames = Int(source.frameLength) + Int(format.sampleRate * 2)
            var offset = 0
            while offset < totalFrames {
                try Task.checkCancellation()
                let count = min(framesPerChunk, totalFrames - offset)
                let inputThrough = Double(offset + count) / format.sampleRate
                let wait = beganAt + inputThrough - ProcessInfo.processInfo.systemUptime
                if wait > 0 { try await Task.sleep(for: .seconds(wait)) }
                let buffer = try chunk(source: source, offset: offset, frames: count)
                await timeline.submitted(through: inputThrough)
                continuation.yield(AnalyzerInput(buffer: buffer,
                    bufferStartTime: CMTime(value: Int64(offset), timescale: Int32(format.sampleRate))))
                offset += count
            }
            continuation.finish()
            periodicFinalizer.cancel()
            await periodicFinalizer.value
            try await analyzer.finalizeAndFinishThroughEndOfInput()
            try await collected.value
            return await timeline.report(engine: engine.name, format: format, sourceDuration: sourceDuration)
        } catch {
            await analyzer.cancelAndFinishNow()
            throw error
        }
    }

    private static func convertedFixture(url: URL, format: AVAudioFormat) throws -> AVAudioPCMBuffer {
        let file = try AVAudioFile(forReading: url)
        guard let source = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length)),
              let converter = AVAudioConverter(from: file.processingFormat, to: format) else {
            throw WordAlignmentProbeError.noCompatibleFormat
        }
        try file.read(into: source)
        let capacity = AVAudioFrameCount(ceil(Double(source.frameLength) * format.sampleRate / source.format.sampleRate) + 1024)
        guard let output = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else {
            throw WordAlignmentProbeError.noCompatibleFormat
        }
        let input = WordAlignmentProbeConverterInput(source)
        var error: NSError?
        let status = converter.convert(to: output, error: &error) { _, outStatus in
            if let buffer = input.take() { outStatus.pointee = .haveData; return buffer }
            outStatus.pointee = .endOfStream
            return nil
        }
        if let error { throw error }
        guard status != .error, output.frameLength > 0 else { throw WordAlignmentProbeError.emptySynthesis }
        return output
    }

    private static func chunk(source: AVAudioPCMBuffer, offset: Int, frames: Int) throws -> AVAudioPCMBuffer {
        guard let output = AVAudioPCMBuffer(pcmFormat: source.format, frameCapacity: AVAudioFrameCount(frames)) else {
            throw WordAlignmentProbeError.noCompatibleFormat
        }
        output.frameLength = AVAudioFrameCount(frames)
        let inputs = UnsafeMutableAudioBufferListPointer(source.mutableAudioBufferList)
        let outputs = UnsafeMutableAudioBufferListPointer(output.mutableAudioBufferList)
        let bytesPerFrame = Int(source.format.streamDescription.pointee.mBytesPerFrame)
        let availableFrames = min(frames, max(0, Int(source.frameLength) - offset))
        for index in outputs.indices {
            guard let target = outputs[index].mData else { continue }
            memset(target, 0, frames * bytesPerFrame)
            if availableFrames > 0, let input = inputs[index].mData {
                memcpy(target, input.advanced(by: offset * bytesPerFrame), availableFrames * bytesPerFrame)
            }
        }
        return output
    }
}

private enum WordAlignmentProbeError: Error {
    case noCompatibleFormat
    case emptySynthesis
    case synthesisTimeout
}

private final class WordAlignmentProbeConverterInput: @unchecked Sendable {
    private let lock = NSLock()
    private var buffer: AVAudioPCMBuffer?
    init(_ buffer: AVAudioPCMBuffer) { self.buffer = buffer }
    func take() -> AVAudioPCMBuffer? {
        lock.withLock { defer { buffer = nil }; return buffer }
    }
}

private final class WordAlignmentProbeFileWriter: @unchecked Sendable {
    private let lock = NSLock()
    private let url: URL
    private var file: AVAudioFile?
    private var continuation: CheckedContinuation<URL, any Error>?
    init(url: URL, continuation: CheckedContinuation<URL, any Error>) {
        self.url = url
        self.continuation = continuation
    }
    func receive(_ audio: AVAudioBuffer) {
        lock.withLock {
            guard continuation != nil else { return }
            guard let pcm = audio as? AVAudioPCMBuffer else { finishLocked(.failure(WordAlignmentProbeError.emptySynthesis)); return }
            if pcm.frameLength == 0 {
                let result: Result<URL, any Error> = file == nil ? .failure(WordAlignmentProbeError.emptySynthesis) : .success(url)
                finishLocked(result)
                return
            }
            do {
                if file == nil {
                    file = try AVAudioFile(forWriting: url, settings: pcm.format.settings,
                                           commonFormat: pcm.format.commonFormat, interleaved: pcm.format.isInterleaved)
                }
                try file?.write(from: pcm)
            } catch { finishLocked(.failure(error)) }
        }
    }
    func fail(_ error: any Error) { lock.withLock { finishLocked(.failure(error)) } }
    private func finishLocked(_ result: Result<URL, any Error>) {
        file = nil
        let current = continuation
        continuation = nil
        current?.resume(with: result)
    }
}

private enum WordAlignmentProbeSynthesis {
    static let fixtureText = "분산 시스템에서는 서비스 사이의 연결이 중요합니다. 서킷 브레이커는 장애가 생겼을 때 요청을 잠시 멈춥니다. 지금 말하고 있는 단어를 끝내고 다음 답변을 들어 보겠습니다."

    @MainActor
    static func render(voice: AVSpeechSynthesisVoice) async throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("voice-word-probe-\(UUID().uuidString).caf")
        let synthesizer = AVSpeechSynthesizer()
        let utterance = AVSpeechUtterance(string: fixtureText)
        utterance.voice = voice
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate
        var timeout: Task<Void, Never>?
        defer { timeout?.cancel() }
        do {
            return try await withCheckedThrowingContinuation { continuation in
                let writer = WordAlignmentProbeFileWriter(url: url, continuation: continuation)
                timeout = Task { @MainActor in
                    do { try await Task.sleep(for: .seconds(20)) } catch { return }
                    writer.fail(WordAlignmentProbeError.synthesisTimeout)
                    synthesizer.stopSpeaking(at: .immediate)
                }
                synthesizer.write(utterance) { buffer in writer.receive(buffer) }
            }
        } catch {
            try? FileManager.default.removeItem(at: url)
            throw error
        }
    }
}


/// The framework query can outlive its caller when a Simulator speech service is
/// unavailable. A one-shot continuation bounds the probe without waiting for the
/// framework task to acknowledge cancellation or initiating model installation.
private enum WordAlignmentProbeDeadline {
    static func value<Value: Sendable>(
        _ query: String, operation: @escaping @Sendable () async -> Value
    ) async throws -> Value {
        try await withCheckedThrowingContinuation { continuation in
            let completion = WordAlignmentProbeOneShot(continuation)
            Task { completion.resolve(.success(await operation())) }
            Task {
                try await Task.sleep(for: .seconds(3))
                let skipped = XCTSkip("Local speech capability query \(query) did not return within 3 seconds; assets were not installed.")
                if completion.resolve(.failure(skipped)) {
                    print("BUDDYSTUDY_WORD_ALIGNMENT_QUERY_TIMEOUT \(query)")
                }
            }
        }
    }
}

private final class WordAlignmentProbeOneShot<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Value, any Error>?
    init(_ continuation: CheckedContinuation<Value, any Error>) { self.continuation = continuation }
    @discardableResult
    func resolve(_ result: Result<Value, any Error>) -> Bool {
        let current = lock.withLock {
            let current = continuation
            continuation = nil
            return current
        }
        current?.resume(with: result)
        return current != nil
    }
}
