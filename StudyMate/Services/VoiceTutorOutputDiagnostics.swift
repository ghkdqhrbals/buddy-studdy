#if os(iOS)
import AVFoundation
import Foundation
import LiveKitWebRTC

struct VoiceTutorOutputDiagnosticsSnapshot: Equatable, Sendable {
    var buffers = 0
    var frames = 0
    var maxRMS = 0.0
    var maxPeak = 0.0
    var mixerVolume: Float?
    var outputSampleRate = 0.0
    var outputChannelCount = 0
    var outputConnected = false
    var tapInstalled = false
}

/// Observes the final native mixer without retaining PCM or changing its graph.
/// A nonzero mixer output is evidence before hardware playback, not proof that
/// a physical speaker was audible. The device observer is weak; the transport
/// must retain this object until its peer and audio device have been closed.
final class VoiceTutorOutputDiagnostics: NSObject, LKRTCAudioDeviceModuleDelegate, @unchecked Sendable {
    private let graphLock = NSLock()
    private let stateLock = NSLock()
    private let callbackQueue = DispatchQueue(label: "com.buddystudy.voice.output-diagnostics")
    private let onFirstOutput: (@Sendable () -> Void)?
    // Accessed only under graphLock. The engine owns the node and tap.
    private weak var installedEngine: AVAudioEngine?
    private weak var installedNode: AVAudioMixerNode?
    // Accessed only under stateLock, including from the realtime tap callback.
    private var state = VoiceTutorOutputDiagnosticsSnapshot()
    private var generation: UInt64 = 0
    private var closed = false
    private var firstOutputObserved = false

    init(onFirstOutput: (@Sendable () -> Void)? = nil) {
        self.onFirstOutput = onFirstOutput
        super.init()
    }

    deinit { close() }

    func snapshot() -> VoiceTutorOutputDiagnosticsSnapshot {
        stateLock.lock()
        defer { stateLock.unlock() }
        return state
    }

    /// Call during transport teardown, outside its state locks. AVAudioEngine
    /// may wait for an in-flight tap, whose callback uses only stateLock.
    func close() {
        graphLock.lock()
        defer { graphLock.unlock() }
        stateLock.lock()
        closed = true
        stateLock.unlock()
        removeInstalledTap()
    }

    private func configureOutput(engine: AVAudioEngine, source: AVAudioNode, destination: AVAudioNode?) {
        graphLock.lock()
        defer { graphLock.unlock() }
        stateLock.lock()
        let isClosed = closed
        stateLock.unlock()
        guard !isClosed, let mixer = source as? AVAudioMixerNode,
              mixer === engine.mainMixerNode else { return }

        let hardware = engine.outputNode.outputFormat(forBus: 0)
        let format = mixer.outputFormat(forBus: 0)
        let connected = destination === engine.outputNode
            && engine.outputConnectionPoints(for: mixer, outputBus: 0)
                .contains { $0.node === engine.outputNode }
        stateLock.lock()
        state.mixerVolume = mixer.outputVolume.isFinite ? mixer.outputVolume : nil
        state.outputSampleRate = hardware.sampleRate.isFinite ? hardware.sampleRate : 0
        state.outputChannelCount = Int(hardware.channelCount)
        state.outputConnected = connected
        stateLock.unlock()

        // Repeated configuration of this exact graph must not install another
        // tap on a bus that we already own. A new graph invalidates late frames.
        if installedEngine === engine, installedNode === mixer { return }
        removeInstalledTap()
        guard connected, format.sampleRate.isFinite, format.sampleRate > 0,
              format.channelCount > 0 else { return }
        stateLock.lock()
        generation &+= 1
        let token = generation
        state.tapInstalled = true
        stateLock.unlock()
        installedEngine = engine
        installedNode = mixer
        mixer.installTap(onBus: 0, bufferSize: 1_024, format: nil) { [weak self] buffer, _ in
            self?.observe(buffer, generation: token)
        }
    }

    /// graphLock is held; never hold stateLock while removing a native tap.
    private func removeInstalledTap() {
        let node = installedNode
        installedNode = nil
        installedEngine = nil
        stateLock.lock()
        generation &+= 1
        state.tapInstalled = false
        stateLock.unlock()
        node?.removeTap(onBus: 0)
    }

    private func releaseOutput(engine: AVAudioEngine) {
        graphLock.lock()
        defer { graphLock.unlock() }
        guard installedEngine === engine else { return }
        removeInstalledTap()
        stateLock.lock()
        state.outputConnected = false
        stateLock.unlock()
    }

    private func observe(_ buffer: AVAudioPCMBuffer, generation token: UInt64) {
        guard buffer.frameLength > 0,
              let level = VoiceTutorRemoteAudioRenderer.interruptionAudioLevel(buffer) else { return }
        stateLock.lock()
        guard !closed, state.tapInstalled, generation == token else {
            stateLock.unlock()
            return
        }
        state.buffers += 1
        state.frames += Int(buffer.frameLength)
        state.maxRMS = max(state.maxRMS, level.rms)
        state.maxPeak = max(state.maxPeak, level.peak)
        // Diagnostic notification only: exclude tiny floating-point/CNG values.
        // This threshold never controls playback, VAD or response completion.
        let notify = !firstOutputObserved && (level.rms >= 0.001 || level.peak >= 0.004)
        if notify { firstOutputObserved = true }
        stateLock.unlock()
        guard notify else { return }
        callbackQueue.async { [weak self] in
            guard let self else { return }
            self.stateLock.lock()
            let mayNotify = !self.closed
            self.stateLock.unlock()
            if mayNotify { self.onFirstOutput?() }
        }
    }

    func audioDeviceModule(_ audioDeviceModule: LKRTCAudioDeviceModule,
                           didReceiveSpeechActivityEvent speechActivityEvent: LKRTCSpeechActivityEvent) {}

    func audioDeviceModule(_ audioDeviceModule: LKRTCAudioDeviceModule,
                           didCreateEngine engine: AVAudioEngine) -> Int { 0 }

    func audioDeviceModule(_ audioDeviceModule: LKRTCAudioDeviceModule,
                           willEnableEngine engine: AVAudioEngine, isPlayoutEnabled: Bool,
                           isRecordingEnabled: Bool, isVoiceProcessingEnabled: Bool) -> Int { 0 }

    func audioDeviceModule(_ audioDeviceModule: LKRTCAudioDeviceModule,
                           willStartEngine engine: AVAudioEngine, isPlayoutEnabled: Bool,
                           isRecordingEnabled: Bool) -> Int {
        if isPlayoutEnabled {
            configureOutput(engine: engine, source: engine.mainMixerNode, destination: engine.outputNode)
        }
        return 0
    }

    func audioDeviceModule(_ audioDeviceModule: LKRTCAudioDeviceModule,
                           didStopEngine engine: AVAudioEngine, isPlayoutEnabled: Bool,
                           isRecordingEnabled: Bool) -> Int { 0 }

    func audioDeviceModule(_ audioDeviceModule: LKRTCAudioDeviceModule,
                           didDisableEngine engine: AVAudioEngine, isPlayoutEnabled: Bool,
                           isRecordingEnabled: Bool) -> Int {
        if !isPlayoutEnabled { releaseOutput(engine: engine) }
        return 0
    }

    func audioDeviceModule(_ audioDeviceModule: LKRTCAudioDeviceModule,
                           willReleaseEngine engine: AVAudioEngine) -> Int {
        releaseOutput(engine: engine)
        return 0
    }

    func audioDeviceModule(_ audioDeviceModule: LKRTCAudioDeviceModule, engine: AVAudioEngine,
                           configureInputFromSource source: AVAudioNode?, toDestination destination: AVAudioNode,
                           format: AVAudioFormat, context: [AnyHashable: Any]) -> Int { 0 }

    func audioDeviceModule(_ audioDeviceModule: LKRTCAudioDeviceModule, engine: AVAudioEngine,
                           configureOutputFromSource source: AVAudioNode, toDestination destination: AVAudioNode?,
                           format: AVAudioFormat, context: [AnyHashable: Any]) -> Int {
        // This SDK callback follows its default source → main mixer → output
        // connection. Returning zero preserves that graph without replacing it.
        configureOutput(engine: engine, source: source, destination: destination)
        return 0
    }

    func audioDeviceModuleDidUpdateDevices(_ audioDeviceModule: LKRTCAudioDeviceModule) {}
}
#endif
