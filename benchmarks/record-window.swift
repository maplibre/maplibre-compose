import AppKit
import AVFoundation
import Foundation
import ScreenCaptureKit

final class Frames: NSObject, SCStreamOutput {
  let writer: AVAssetWriter
  let input: AVAssetWriterInput
  var started = false
  var dropped = 0
  var received = 0
  init(url: URL, width: Int, height: Int) throws {
    writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
    input = AVAssetWriterInput(mediaType: .video, outputSettings: [
      AVVideoCodecKey: AVVideoCodecType.h264,
      AVVideoWidthKey: width,
      AVVideoHeightKey: height,
      AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 20_000_000],
    ])
    input.expectsMediaDataInRealTime = true
    writer.add(input)
    super.init()
  }

  func stream(
    _: SCStream,
    didOutputSampleBuffer sample: CMSampleBuffer,
    of outputType: SCStreamOutputType
  ) {
    received += 1
    guard outputType == .screen, sample.isValid,
          CMSampleBufferGetImageBuffer(sample) != nil,
          let attachments = CMSampleBufferGetSampleAttachmentsArray(
            sample,
            createIfNecessary: false
          ) as? [[SCStreamFrameInfo: Any]],
          let rawStatus = attachments.first?[.status] as? Int,
          SCFrameStatus(rawValue: rawStatus) == .complete else { return }
    if !started {
      guard writer.startWriting() else { return }
      writer
        .startSession(
          atSourceTime: CMSampleBufferGetPresentationTimeStamp(sample)
        )
      started = true
    }
    if input.isReadyForMoreMediaData {
      if !input.append(sample) {
        dropped += 1
      }
    } else {
      dropped += 1
    }
  }
}

@main
struct WindowRecorder {
  @MainActor
  static func main() {
    let app = NSApplication.shared
    app.setActivationPolicy(.prohibited)
    Task { @MainActor in
      do { try await record(); exit(0) }
      catch { fputs("\(error)\n", stderr); exit(1) }
    }
    app.run()
  }

  @MainActor
  static func record() async throws {
    let pid = pid_t(CommandLine.arguments[1])!
    let path = URL(fileURLWithPath: CommandLine.arguments[2])
    var selected: SCWindow?
    for _ in 0 ..< 150 {
      let content = try await SCShareableContent.excludingDesktopWindows(
        true,
        onScreenWindowsOnly: true
      )
      selected = content.windows
        .first { $0.owningApplication?.processID == pid && $0.frame.width > 300
        }
      if selected != nil {
        break
      }
      try await Task.sleep(nanoseconds: 100_000_000)
    }
    guard let window = selected else {
      throw NSError(
        domain: "benchmark",
        code: 1,
        userInfo: [NSLocalizedDescriptionKey: "Benchmark window did not appear"]
      )
    }
    let filter = SCContentFilter(desktopIndependentWindow: window)
    let config = SCStreamConfiguration()
    config
      .width = Int(filter.contentRect.width * CGFloat(filter.pointPixelScale))
    config
      .height = Int(filter.contentRect.height * CGFloat(filter.pointPixelScale))
    config.minimumFrameInterval = CMTime(value: 1, timescale: 60)
    config.queueDepth = 3
    config.showsCursor = false
    config.capturesAudio = false
    config.pixelFormat = kCVPixelFormatType_32BGRA
    let stream = SCStream(filter: filter, configuration: config, delegate: nil)
    let frames = try Frames(
      url: path,
      width: config.width,
      height: config.height
    )
    let queue = DispatchQueue(label: "map-sync.capture")
    try stream.addStreamOutput(frames, type: .screen, sampleHandlerQueue: queue)
    try await stream.startCapture()
    try await Task.sleep(nanoseconds: 60_000_000_000)
    try await stream.stopCapture()
    guard queue.sync(execute: { frames.started }) else { throw NSError(
      domain: "map-sync",
      code: 2,
      userInfo: [
        NSLocalizedDescriptionKey: "No complete screen frames arrived; received \(frames.received)",
      ]
    ) }
    queue.sync { frames.input.markAsFinished() }
    await frames.writer.finishWriting()
    if let error = frames.writer.error {
      throw error
    }
    print(
      "Captured \(config.width)x\(config.height); encoder dropped \(frames.dropped) frames"
    )
    guard frames.dropped == 0 else {
      throw NSError(
        domain: "benchmark",
        code: 3,
        userInfo: [NSLocalizedDescriptionKey: "Capture encoder dropped frames"]
      )
    }
  }
}
