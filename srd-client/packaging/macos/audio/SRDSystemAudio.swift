// srd-system-audio: captures this Mac's system (computer) audio and writes it to stdout as raw
// 48 kHz stereo signed 16-bit little-endian PCM, which is what the client's audio mixer reads.
//
// macOS has no loopback device of its own. ScreenCaptureKit (macOS 13+) can capture system audio
// without installing anything (no BlackHole), and it uses the Screen Recording permission the app
// already needs to share the screen. The client starts this helper from
// SecureRemoteDesktopClient.app/Contents/MacOS/ and stops it (or it exits when its stdout pipe
// closes, or when its parent process goes away).
//
// stderr carries one-line diagnostics; the exit code says why it stopped:
//   0 = stopped, 2 = macOS older than 13, 3 = no Screen Recording permission / no display,
//   4 = the capture stream stopped with an error.
//
// Built universal (arm64 + x86_64) by build/build_macos_native.sh.
import CoreMedia
import Foundation
import ScreenCaptureKit

func log(_ s: String) {
    FileHandle.standardError.write((s + "\n").data(using: .utf8)!)
}

let sampleRate = 48000
let stdout = FileHandle.standardOutput
let parent = getppid()

@available(macOS 13.0, *)
final class SystemAudioCapture: NSObject, SCStreamOutput, SCStreamDelegate {
    private var stream: SCStream?
    private let audioQueue = DispatchQueue(label: "srd.audio", qos: .userInteractive)
    private let screenQueue = DispatchQueue(label: "srd.screen", qos: .background)
    private var scratch = [Int16]()
    private var loggedFormat = false

    func start() async throws {
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
        guard let display = content.displays.first else {
            throw NSError(domain: "srd", code: 3, userInfo: [NSLocalizedDescriptionKey: "no display to attach to"])
        }
        let filter = SCContentFilter(display: display, excludingApplications: [], exceptingWindows: [])
        let cfg = SCStreamConfiguration()
        cfg.capturesAudio = true
        cfg.sampleRate = sampleRate
        cfg.channelCount = 2
        cfg.excludesCurrentProcessAudio = true
        // Video is not wanted, but a stream always has a screen track: keep it tiny and slow.
        cfg.width = 2
        cfg.height = 2
        cfg.minimumFrameInterval = CMTime(value: 1, timescale: 1)
        cfg.showsCursor = false
        cfg.queueDepth = 3
        let s = SCStream(filter: filter, configuration: cfg, delegate: self)
        try s.addStreamOutput(self, type: .screen, sampleHandlerQueue: screenQueue)
        try s.addStreamOutput(self, type: .audio, sampleHandlerQueue: audioQueue)
        try await s.startCapture()
        stream = s
        log("srd-system-audio: capturing system audio at \(sampleRate) Hz stereo")
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        log("srd-system-audio: stream stopped: \(error.localizedDescription)")
        exit(4)
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sb: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .audio, CMSampleBufferIsValid(sb), CMSampleBufferDataIsReady(sb) else { return }
        guard let fmt = CMSampleBufferGetFormatDescription(sb),
              let asbdPtr = CMAudioFormatDescriptionGetStreamBasicDescription(fmt) else { return }
        let asbd = asbdPtr.pointee
        let frames = CMSampleBufferGetNumSamples(sb)
        if frames <= 0 { return }

        var needed = 0
        CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sb, bufferListSizeNeededOut: &needed, bufferListOut: nil, bufferListSize: 0,
            blockBufferAllocator: nil, blockBufferMemoryAllocator: nil, flags: 0, blockBufferOut: nil)
        if needed <= 0 { return }
        let raw = UnsafeMutableRawPointer.allocate(byteCount: needed, alignment: 16)
        defer { raw.deallocate() }
        let abl = raw.bindMemory(to: AudioBufferList.self, capacity: 1)
        var block: CMBlockBuffer?
        let st = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sb, bufferListSizeNeededOut: nil, bufferListOut: abl, bufferListSize: needed,
            blockBufferAllocator: nil, blockBufferMemoryAllocator: nil,
            flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment, blockBufferOut: &block)
        if st != noErr { return }
        let buffers = UnsafeMutableAudioBufferListPointer(abl)

        let isFloat = asbd.mFormatFlags & kAudioFormatFlagIsFloat != 0
        let nonInterleaved = asbd.mFormatFlags & kAudioFormatFlagIsNonInterleaved != 0
        let channels = max(1, Int(asbd.mChannelsPerFrame))
        let bits = Int(asbd.mBitsPerChannel)
        if !loggedFormat {
            loggedFormat = true
            log("srd-system-audio: source format \(Int(asbd.mSampleRate)) Hz, \(channels) ch, \(bits)-bit "
                + (isFloat ? "float" : "int") + (nonInterleaved ? " planar" : " interleaved"))
        }
        if !(isFloat && bits == 32) && !(!isFloat && bits == 16) { return }

        // Sample (channel c, frame f) as a float in -1...1, whatever the layout.
        func sample(_ c: Int, _ f: Int) -> Float {
            let ch = min(c, channels - 1)
            let bufIndex = nonInterleaved ? ch : 0
            guard bufIndex < buffers.count, let data = buffers[bufIndex].mData else { return 0 }
            let idx = nonInterleaved ? f : f * channels + ch
            if isFloat { return data.assumingMemoryBound(to: Float.self)[idx] }
            return Float(data.assumingMemoryBound(to: Int16.self)[idx]) / 32768
        }

        let n = frames * 2
        if scratch.count < n { scratch = [Int16](repeating: 0, count: n) }
        for f in 0..<frames {
            for c in 0..<2 {
                let v = max(-1, min(1, sample(c, f)))
                scratch[f * 2 + c] = Int16(v * 32767).littleEndian
            }
        }
        scratch.withUnsafeBytes { bytes in
            stdout.write(Data(bytes: bytes.baseAddress!, count: n * 2))
        }
    }
}

// Exit when the client goes away even if the pipe stays open somehow.
let watchdog = DispatchSource.makeTimerSource(queue: .global())
watchdog.schedule(deadline: .now() + 2, repeating: 2)
watchdog.setEventHandler {
    if getppid() != parent { exit(0) }
}
watchdog.resume()
signal(SIGTERM) { _ in exit(0) }

if #available(macOS 13.0, *) {
    let capture = SystemAudioCapture()
    Task {
        do {
            try await capture.start()
        } catch {
            log("srd-system-audio: cannot capture system audio (Screen Recording permission?): \(error.localizedDescription)")
            exit(3)
        }
    }
    withExtendedLifetime(capture) { dispatchMain() }
} else {
    log("srd-system-audio: system audio capture needs macOS 13 or later")
    exit(2)
}
