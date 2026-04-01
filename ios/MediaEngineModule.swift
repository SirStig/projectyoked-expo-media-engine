
import ExpoModulesCore
@preconcurrency import AVFoundation
import CoreImage
import QuartzCore
import UIKit

extension AVAssetWriterInput: @retroactive @unchecked Sendable {}
extension AVAssetReaderTrackOutput: @retroactive @unchecked Sendable {}

// MARK: - CIFilter Custom Video Compositor

/// Custom AVVideoCompositionInstruction carrying per-clip filter/opacity data.
class MediaEngineInstruction: NSObject, AVVideoCompositionInstructionProtocol {
    var timeRange: CMTimeRange
    var enablePostProcessing: Bool = true
    var containsTweening: Bool = false
    var requiredSourceTrackIDs: [NSValue]?
    var passthroughTrackID: CMPersistentTrackID = kCMPersistentTrackID_Invalid

    let trackID: Int32
    let filterType: String
    let filterIntensity: Float
    let opacity: Float
    // Transition support
    let transitionTrackID: Int32    // 0 = no transition
    let transitionProgress: Float   // 0..1
    let transitionType: String

    init(timeRange: CMTimeRange,
         trackID: Int32,
         filterType: String = "none",
         filterIntensity: Float = 1.0,
         opacity: Float = 1.0,
         transitionTrackID: Int32 = 0,
         transitionProgress: Float = 0,
         transitionType: String = "crossfade") {
        self.timeRange = timeRange
        self.trackID = trackID
        self.filterType = filterType
        self.filterIntensity = filterIntensity
        self.opacity = opacity
        self.transitionTrackID = transitionTrackID
        self.transitionProgress = transitionProgress
        self.transitionType = transitionType
        self.containsTweening = transitionTrackID > 0
        var ids: [NSValue] = [NSNumber(value: trackID)]
        if transitionTrackID > 0 {
            ids.append(NSNumber(value: transitionTrackID))
        }
        self.requiredSourceTrackIDs = ids
    }
}

/// Custom compositor that applies CIFilters, opacity, and transitions per frame.
final class MediaEngineCompositor: NSObject, AVVideoCompositing {
    private static let pixelBufferAttributePairs: [String: any Sendable] = [
        kCVPixelBufferPixelFormatTypeKey as String: UInt32(kCVPixelFormatType_32BGRA),
        kCVPixelBufferIOSurfacePropertiesKey as String: [String: String](),
    ]

    var sourcePixelBufferAttributes: [String: any Sendable]? { Self.pixelBufferAttributePairs }
    var requiredPixelBufferAttributesForRenderContext: [String: any Sendable] { Self.pixelBufferAttributePairs }

    private let ciContext = CIContext(options: [.useSoftwareRenderer: false])

    func renderContextChanged(_ newContext: AVVideoCompositionRenderContext) {}
    func cancelAllPendingVideoCompositionRequests() {}

    func startRequest(_ request: AVAsynchronousVideoCompositionRequest) {
        guard let instruction = request.videoCompositionInstruction as? MediaEngineInstruction,
              let srcBuffer = request.sourceFrame(byTrackID: instruction.trackID) else {
            request.finish(with: NSError(domain: "MediaEngineCompositor", code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Missing source frame"]))
            return
        }

        let renderCtx = request.renderContext
        var output = Self.scaleSourceToRenderSize(CIImage(cvPixelBuffer: srcBuffer), renderContext: renderCtx)

        // 1. Apply filter
        output = applyFilter(output, type: instruction.filterType, intensity: instruction.filterIntensity)

        // 2. Apply opacity via alpha multiply
        if instruction.opacity < 1.0 {
            output = output.applyingFilter("CIColorMatrix", parameters: [
                "inputAVector": CIVector(x: 0, y: 0, z: 0, w: CGFloat(instruction.opacity))
            ])
        }

        // 3. Apply transition if a second track is available (progress from timeline position)
        let transitionProgress: Float
        if instruction.transitionTrackID > 0 {
            let tr = instruction.timeRange
            let t = request.compositionTime
            let span = CMTimeGetSeconds(tr.duration)
            if span > 1e-9 {
                let p = CMTimeGetSeconds(CMTimeSubtract(t, tr.start)) / span
                transitionProgress = Float(min(1, max(0, p)))
            } else {
                transitionProgress = 0
            }
        } else {
            transitionProgress = instruction.transitionProgress
        }

        if instruction.transitionTrackID > 0,
           let transBuffer = request.sourceFrame(byTrackID: instruction.transitionTrackID) {
            let transImage = Self.scaleSourceToRenderSize(CIImage(cvPixelBuffer: transBuffer), renderContext: renderCtx)
            output = applyTransition(from: output, to: transImage,
                                     type: instruction.transitionType,
                                     progress: transitionProgress,
                                     size: renderCtx.size)
        }

        guard let outBuffer = request.renderContext.newPixelBuffer() else {
            request.finish(with: NSError(domain: "MediaEngineCompositor", code: 2,
                userInfo: [NSLocalizedDescriptionKey: "No output buffer"]))
            return
        }
        ciContext.render(output, to: outBuffer)
        request.finish(withComposedVideoFrame: outBuffer)
    }

    // MARK: Filter

    private func applyFilter(_ image: CIImage, type: String, intensity: Float) -> CIImage {
        let i = CGFloat(intensity)
        switch type {
        case "grayscale":
            guard let f = CIFilter(name: "CIPhotoEffectMono") else { return image }
            f.setValue(image, forKey: kCIInputImageKey)
            let mono = f.outputImage ?? image
            return image.applyingFilter("CIDissolveTransition", parameters: [
                "inputTargetImage": mono, "inputTime": i
            ])
        case "sepia":
            guard let f = CIFilter(name: "CISepiaTone") else { return image }
            f.setValue(image, forKey: kCIInputImageKey)
            f.setValue(i, forKey: kCIInputIntensityKey)
            return f.outputImage ?? image
        case "vignette":
            guard let f = CIFilter(name: "CIVignette") else { return image }
            f.setValue(image, forKey: kCIInputImageKey)
            f.setValue(i * 2.0, forKey: kCIInputIntensityKey)
            f.setValue(1.0, forKey: kCIInputRadiusKey)
            return f.outputImage ?? image
        case "invert":
            guard let f = CIFilter(name: "CIColorInvert") else { return image }
            f.setValue(image, forKey: kCIInputImageKey)
            let inv = f.outputImage ?? image
            return image.applyingFilter("CIDissolveTransition", parameters: [
                "inputTargetImage": inv, "inputTime": i
            ])
        case "brightness":
            guard let f = CIFilter(name: "CIColorControls") else { return image }
            f.setValue(image, forKey: kCIInputImageKey)
            f.setValue((i - 0.5) * 2.0, forKey: kCIInputBrightnessKey) // -1..1
            return f.outputImage ?? image
        case "contrast":
            guard let f = CIFilter(name: "CIColorControls") else { return image }
            f.setValue(image, forKey: kCIInputImageKey)
            f.setValue(i * 2.0, forKey: kCIInputContrastKey)            // 0..2 (1=normal)
            return f.outputImage ?? image
        case "saturation":
            guard let f = CIFilter(name: "CIColorControls") else { return image }
            f.setValue(image, forKey: kCIInputImageKey)
            f.setValue(i * 2.0, forKey: kCIInputSaturationKey)          // 0..2 (1=normal)
            return f.outputImage ?? image
        case "warm":
            guard let f = CIFilter(name: "CITemperatureAndTint") else { return image }
            f.setValue(image, forKey: kCIInputImageKey)
            f.setValue(CIVector(x: 6500, y: 0), forKey: "inputNeutral")
            f.setValue(CIVector(x: 6500 + i * 2000, y: 0), forKey: "inputTargetNeutral")
            return f.outputImage ?? image
        case "cool":
            guard let f = CIFilter(name: "CITemperatureAndTint") else { return image }
            f.setValue(image, forKey: kCIInputImageKey)
            f.setValue(CIVector(x: 6500, y: 0), forKey: "inputNeutral")
            f.setValue(CIVector(x: 6500 - i * 2000, y: 0), forKey: "inputTargetNeutral")
            return f.outputImage ?? image
        default:
            return image
        }
    }

    // MARK: Transitions

    private func applyTransition(from outgoing: CIImage, to incoming: CIImage,
                                  type: String, progress: Float, size: CGSize) -> CIImage {
        let p = CGFloat(progress)
        switch type {
        case "crossfade":
            return outgoing.applyingFilter("CIDissolveTransition", parameters: [
                "inputTargetImage": incoming, "inputTime": p
            ])
        case "fade":
            // Fade to black then back
            let black = CIImage(color: CIColor.black).cropped(to: outgoing.extent)
            if p < 0.5 {
                return outgoing.applyingFilter("CIDissolveTransition", parameters: [
                    "inputTargetImage": black, "inputTime": p * 2
                ])
            } else {
                return black.applyingFilter("CIDissolveTransition", parameters: [
                    "inputTargetImage": incoming, "inputTime": (p - 0.5) * 2
                ])
            }
        case "slide-left":
            let offset = CGAffineTransform(translationX: -size.width * p, y: 0)
            let inOffset = CGAffineTransform(translationX: size.width * (1 - p), y: 0)
            return outgoing.transformed(by: offset).composited(over: incoming.transformed(by: inOffset))
        case "slide-right":
            let offset = CGAffineTransform(translationX: size.width * p, y: 0)
            let inOffset = CGAffineTransform(translationX: -size.width * (1 - p), y: 0)
            return outgoing.transformed(by: offset).composited(over: incoming.transformed(by: inOffset))
        case "zoom-in":
            let scale = 1.0 + p * 0.3
            let scaleT = CGAffineTransform(scaleX: scale, y: scale)
                .translatedBy(x: -size.width * (scale - 1) / 2, y: -size.height * (scale - 1) / 2)
            return outgoing.transformed(by: scaleT)
                           .applyingFilter("CIDissolveTransition", parameters: [
                               "inputTargetImage": incoming, "inputTime": p
                           ])
        case "zoom-out":
            let scale = max(0.01, 1.0 - p * 0.3)
            let scaleT = CGAffineTransform(scaleX: scale, y: scale)
                .translatedBy(x: size.width * (1 - scale) / 2, y: size.height * (1 - scale) / 2)
            return outgoing.transformed(by: scaleT)
                           .applyingFilter("CIDissolveTransition", parameters: [
                               "inputTargetImage": incoming, "inputTime": p
                           ])
        default:
            return outgoing.applyingFilter("CIDissolveTransition", parameters: [
                "inputTargetImage": incoming, "inputTime": p
            ])
        }
    }

    private static func scaleSourceToRenderSize(_ image: CIImage, renderContext: AVVideoCompositionRenderContext) -> CIImage {
        let target = renderContext.size
        let w = image.extent.width
        let h = image.extent.height
        guard w > 1, h > 1 else { return image }
        let rw = target.width
        let rh = target.height
        let scale = max(rw / w, rh / h)
        let scaled = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let ext = scaled.extent
        let tx = (rw - ext.width) / 2 - ext.origin.x
        let ty = (rh - ext.height) / 2 - ext.origin.y
        return scaled.transformed(by: CGAffineTransform(translationX: tx, y: ty))
            .cropped(to: CGRect(origin: .zero, size: target))
    }
}

// MARK: - Heavy media serialization (Swift 6: NSLock is unavailable across await)

private actor MediaEngineHeavyMediaSerializer {
    static let shared = MediaEngineHeavyMediaSerializer()
    func run<T: Sendable>(_ body: @Sendable () async throws -> T) async rethrows -> T {
        try await body()
    }
}

private final class ComposeCompositeConfigBox: @unchecked Sendable {
    let config: [String: Any]
    init(_ config: [String: Any]) { self.config = config }
}

private final class CALayerCleanupTarget: @unchecked Sendable {
    let parent: CALayer
    init(parent: CALayer) { self.parent = parent }

    func removeAllSublayers() {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        let subs = parent.sublayers ?? []
        for layer in subs {
            layer.removeFromSuperlayer()
        }
        CATransaction.commit()
    }
}

// MARK: - Module

public class MediaEngineModule: Module {
    public func definition() -> ModuleDefinition {
        Name("MediaEngine")

        // MARK: - Audio Extraction
        AsyncFunction("extractAudio") { (videoUri: String, outputUri: String) -> String in
            let videoURL = URL(fileURLWithPath: videoUri.replacingOccurrences(of: "file://", with: ""))
            let outputURL = URL(fileURLWithPath: outputUri.replacingOccurrences(of: "file://", with: ""))

            try? FileManager.default.removeItem(at: outputURL)

            let asset = AVAsset(url: videoURL)

            guard let exportSession = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
                throw NSError(domain: "MediaEngine", code: 1, userInfo: [NSLocalizedDescriptionKey: "Failed to create export session for \(videoUri)"])
            }

            exportSession.outputURL = outputURL
            exportSession.outputFileType = .m4a
            exportSession.timeRange = CMTimeRange(start: .zero, duration: asset.duration)

            await exportSession.export()

            if exportSession.status == .completed {
                return outputUri
            } else {
                throw NSError(domain: "MediaEngine", code: 2, userInfo: [
                    NSLocalizedDescriptionKey: "Failed to extract audio from \(videoUri): \(exportSession.error?.localizedDescription ?? "Unknown error")"
                ])
            }
        }

        // MARK: - Waveform Generation
        AsyncFunction("getWaveform") { (audioUri: String, samples: Int) -> [Float] in
            let audioURL = URL(fileURLWithPath: audioUri.replacingOccurrences(of: "file://", with: ""))
            let file = try AVAudioFile(forReading: audioURL)
            let format = file.processingFormat
            let totalFrames = AVAudioFrameCount(file.length)

            // Stream in fixed-size chunks to avoid loading the entire file into RAM.
            // A 3-minute stereo 44.1kHz file is ~63MB as a contiguous float buffer.
            let chunkSize: AVAudioFrameCount = 8192
            guard let chunk = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: chunkSize) else {
                return []
            }

            let framesPerSample = max(1, Int(totalFrames) / samples)
            var result = [Float](repeating: 0, count: samples)
            var sampleIndex = 0
            var accumulated: Float = 0
            var accumulatedCount = 0
            var totalFramesRead = 0

            while totalFramesRead < Int(totalFrames) && sampleIndex < samples {
                let framesToRead = min(chunkSize, AVAudioFrameCount(Int(totalFrames) - totalFramesRead))
                do {
                    try file.read(into: chunk, frameCount: framesToRead)
                } catch {
                    break
                }
                let framesRead = Int(chunk.frameLength)
                if framesRead == 0 { break }
                totalFramesRead += framesRead

                guard let floatData = chunk.floatChannelData?[0] else { break }
                for i in 0..<framesRead {
                    let s = floatData[i]
                    accumulated += s * s
                    accumulatedCount += 1
                    if accumulatedCount >= framesPerSample {
                        let rms = sqrt(accumulated / Float(accumulatedCount))
                        result[sampleIndex] = min(1.0, rms * 5.0)
                        sampleIndex += 1
                        accumulated = 0
                        accumulatedCount = 0
                        if sampleIndex >= samples { break }
                    }
                }
            }

            // Flush any remaining partial window into the last bucket
            if accumulatedCount > 0 && sampleIndex < samples {
                let rms = sqrt(accumulated / Float(accumulatedCount))
                result[sampleIndex] = min(1.0, rms * 5.0)
            }

            return result
        }

        // MARK: - Video Composition (Unified)
        AsyncFunction("exportComposition") { (config: [String: Any]) -> String in
             guard let outputPath = config["outputPath"] as? String,
                   let videoPath = config["videoPath"] as? String else {
                 throw NSError(domain: "MediaEngine", code: 1, userInfo: [NSLocalizedDescriptionKey: "Missing required paths"])
             }

             let duration = config["duration"] as? Double ?? 10.0

             var tracks: [[String: Any]] = []

             // 1. Video Track
             tracks.append([
                "type": "video",
                "clips": [[
                    "uri": videoPath,
                    "startTime": 0.0,
                    "duration": duration > 0 ? duration : 9999.0,
                    "resizeMode": "cover"
                ]]
             ])

             // 2. Audio Track (Music)
             if let musicPath = config["musicPath"] as? String, !musicPath.isEmpty {
                 let musicVolume = config["musicVolume"] as? Double ?? 0.5
                 tracks.append([
                    "type": "audio",
                    "clips": [[
                        "uri": musicPath,
                        "startTime": 0.0,
                        "duration": duration > 0 ? duration : 9999.0,
                        "volume": musicVolume
                    ]]
                 ])
             }

             // 3. Text Overlays
             let textArray = config["textArray"] as? [String] ?? []
             let textX = config["textX"] as? [Double] ?? []
             let textY = config["textY"] as? [Double] ?? []
             let textColors = config["textColors"] as? [String] ?? []
             let textSizes = config["textSizes"] as? [Double] ?? []
             let textStarts = config["textStarts"] as? [Double] ?? []
             let textDurations = config["textDurations"] as? [Double] ?? []

             if !textArray.isEmpty {
                 var textClips: [[String: Any]] = []
                 for i in 0..<textArray.count {
                     let text = textArray[i]
                     let clip: [String: Any] = [
                        "uri": "text:\(text)",
                        "text": text,
                        "startTime": textStarts.indices.contains(i) ? textStarts[i] : 0.0,
                        "duration": textDurations.indices.contains(i) ? textDurations[i] : (duration > 0 ? duration : 10.0),
                        "x": textX.indices.contains(i) ? textX[i] : 0.5,
                        "y": textY.indices.contains(i) ? textY[i] : 0.5,
                        "color": textColors.indices.contains(i) ? textColors[i] : "#FFFFFF",
                        "fontSize": textSizes.indices.contains(i) ? textSizes[i] : 24.0,
                        "scale": 1.0
                     ]
                     textClips.append(clip)
                 }
                 tracks.append(["type": "text", "clips": textClips])
             }

             // 4. Emoji Overlays
             let emojiArray = config["emojiArray"] as? [String] ?? []
             let emojiX = config["emojiX"] as? [Double] ?? []
             let emojiY = config["emojiY"] as? [Double] ?? []
             let emojiSizes = config["emojiSizes"] as? [Double] ?? []
             let emojiStarts = config["emojiStarts"] as? [Double] ?? []
             let emojiDurations = config["emojiDurations"] as? [Double] ?? []

             if !emojiArray.isEmpty {
                 var emojiClips: [[String: Any]] = []
                 for i in 0..<emojiArray.count {
                     let emoji = emojiArray[i]
                     let clip: [String: Any] = [
                        "uri": "text:\(emoji)",
                        "text": emoji,
                        "startTime": emojiStarts.indices.contains(i) ? emojiStarts[i] : 0.0,
                        "duration": emojiDurations.indices.contains(i) ? emojiDurations[i] : (duration > 0 ? duration : 10.0),
                        "x": emojiX.indices.contains(i) ? emojiX[i] : 0.5,
                        "y": emojiY.indices.contains(i) ? emojiY[i] : 0.5,
                        "color": "#FFFFFF",
                        "fontSize": emojiSizes.indices.contains(i) ? emojiSizes[i] : 48.0,
                        "scale": 1.0
                     ]
                     emojiClips.append(clip)
                 }
                 tracks.append(["type": "text", "clips": emojiClips])
             }

             let videoBitrateForAdvanced: Any
             if let v = config["videoBitrate"] {
                 videoBitrateForAdvanced = v
             } else if let v = config["bitrate"] {
                 videoBitrateForAdvanced = v
             } else {
                 videoBitrateForAdvanced = NSNull()
             }
             let audioBitrateForAdvanced: Any
             if let v = config["audioBitrate"] {
                 audioBitrateForAdvanced = v
             } else {
                 audioBitrateForAdvanced = NSNull()
             }

             let advancedConfig: [String: Any] = [
                 "outputUri": outputPath,
                 "width": 1280,
                 "height": 720,
                 "frameRate": 30,
                 "videoBitrate": videoBitrateForAdvanced,
                 "audioBitrate": audioBitrateForAdvanced,
                 "enablePassthrough": config["enablePassthrough"] ?? true,
                 "tracks": tracks,
             ]

             return try await composeCompositeVideoImpl(advancedConfig)
        }

        // MARK: - Advanced Composite Video
        AsyncFunction("composeCompositeVideo") { (config: [String: Any]) -> String in
            return try await composeCompositeVideoImpl(config)
        }

        // MARK: - Utilities
        AsyncFunction("stitchVideos") { (videoPaths: [String], outputUri: String) -> String in
            try await MediaEngineHeavyMediaSerializer.shared.run {
                try await VideoStitcher.stitch(videoPaths: videoPaths, outputUri: outputUri)
            }
        }

        // MARK: - Video Compression
        AsyncFunction("compressVideo") { (config: [String: Any]) -> String in
            try await MediaEngineHeavyMediaSerializer.shared.run {
                try await Self.compressVideoBody(config)
            }
        }
    }

    private static func compressVideoBody(_ config: [String: Any]) async throws -> String {
            guard let inputUri = config["inputUri"] as? String,
                  let outputUri = config["outputUri"] as? String else {
                throw NSError(domain: "MediaEngine", code: 1, userInfo: [
                    NSLocalizedDescriptionKey: "compressVideo requires inputUri and outputUri"
                ])
            }

            let inputPath = inputUri.replacingOccurrences(of: "file://", with: "")
            let outputPath = outputUri.replacingOccurrences(of: "file://", with: "")
            let inputURL = URL(fileURLWithPath: inputPath)
            let outputURL = URL(fileURLWithPath: outputPath)

            try? FileManager.default.removeItem(at: outputURL)

            let asset = AVAsset(url: inputURL)

            guard let videoTrack = try? await asset.loadTracks(withMediaType: .video).first else {
                throw NSError(domain: "MediaEngine", code: 2, userInfo: [
                    NSLocalizedDescriptionKey: "No video track found in \(inputPath)"
                ])
            }

            // Resolve dimensions
            let naturalSize = (try? await videoTrack.load(.naturalSize)) ?? CGSize(width: 1280, height: 720)
            let preferredTransform = (try? await videoTrack.load(.preferredTransform)) ?? .identity
            let assetDuration = (try? await asset.load(.duration)) ?? .zero

            var targetWidth = naturalSize.width
            var targetHeight = naturalSize.height

            if let w = config["width"] as? Double { targetWidth = CGFloat(w) }
            if let h = config["height"] as? Double { targetHeight = CGFloat(h) }

            // Apply maxWidth / maxHeight constraints
            if let maxW = config["maxWidth"] as? Double, targetWidth > CGFloat(maxW) {
                let scale = CGFloat(maxW) / targetWidth
                targetWidth = CGFloat(maxW)
                targetHeight = (targetHeight * scale).rounded()
            }
            if let maxH = config["maxHeight"] as? Double, targetHeight > CGFloat(maxH) {
                let scale = CGFloat(maxH) / targetHeight
                targetHeight = (targetHeight * scale).rounded()
                targetWidth = (targetWidth * scale).rounded()
            }

            // Ensure dimensions are even (H.264 requirement)
            targetWidth = (targetWidth / 2).rounded() * 2
            targetHeight = (targetHeight / 2).rounded() * 2

            // Resolve bitrate
            let bitrate: Int
            if let explicitBitrate = config["bitrate"] as? Int {
                bitrate = explicitBitrate
            } else {
                switch config["quality"] as? String ?? "medium" {
                case "low":  bitrate = 1_000_000
                case "high": bitrate = 8_000_000
                default:     bitrate = 4_000_000
                }
            }
            let audioBitrate = config["audioBitrate"] as? Int ?? 128_000
            let frameRate = config["frameRate"] as? Int ?? 30
            let codec = config["codec"] as? String ?? "h264"
            let useHEVC = codec == "h265" || codec == "hevc"

            // Build video compression settings
            let codecType: AVVideoCodecType = useHEVC ? .hevc : .h264
            let compressionProps: [String: Any] = [
                AVVideoAverageBitRateKey: bitrate,
                AVVideoExpectedSourceFrameRateKey: frameRate,
                AVVideoMaxKeyFrameIntervalKey: frameRate * 2,
            ]
            let videoOutputSettings: [String: Any] = [
                AVVideoCodecKey: codecType.rawValue,
                AVVideoWidthKey: Int(targetWidth),
                AVVideoHeightKey: Int(targetHeight),
                AVVideoCompressionPropertiesKey: compressionProps,
            ]

            let audioOutputSettings: [String: Any] = [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: 44_100,
                AVNumberOfChannelsKey: 2,
                AVEncoderBitRateKey: audioBitrate,
            ]

            // Set up writer
            let writer = try AVAssetWriter(outputURL: outputURL, fileType: .mp4)

            let videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoOutputSettings)
            videoInput.transform = preferredTransform
            videoInput.expectsMediaDataInRealTime = false
            writer.add(videoInput)

            var audioInput: AVAssetWriterInput? = nil
            if let _ = try? await asset.loadTracks(withMediaType: .audio).first {
                let ai = AVAssetWriterInput(mediaType: .audio, outputSettings: audioOutputSettings)
                ai.expectsMediaDataInRealTime = false
                writer.add(ai)
                audioInput = ai
            }

            // Set up reader
            let reader = try AVAssetReader(asset: asset)
            reader.timeRange = CMTimeRange(start: .zero, duration: assetDuration)

            let videoOutputSettings_read: [String: Any] = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            ]
            let videoReaderOutput = AVAssetReaderTrackOutput(track: videoTrack, outputSettings: videoOutputSettings_read)
            videoReaderOutput.alwaysCopiesSampleData = false
            reader.add(videoReaderOutput)

            var audioReaderOutput: AVAssetReaderTrackOutput? = nil
            if let audioTrack = try? await asset.loadTracks(withMediaType: .audio).first {
                let aro = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: [
                    AVFormatIDKey: kAudioFormatLinearPCM,
                    AVLinearPCMIsNonInterleaved: false,
                    AVLinearPCMBitDepthKey: 16,
                    AVLinearPCMIsFloatKey: false,
                ])
                aro.alwaysCopiesSampleData = false
                reader.add(aro)
                audioReaderOutput = aro
            }

            guard reader.startReading() else {
                throw NSError(domain: "MediaEngine", code: 3, userInfo: [
                    NSLocalizedDescriptionKey: "Failed to start reading \(inputPath): \(reader.error?.localizedDescription ?? "unknown")"
                ])
            }
            writer.startWriting()
            writer.startSession(atSourceTime: .zero)

            // Process with async continuation
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                let videoQueue = DispatchQueue(label: "me.compress.video", qos: .userInitiated)
                let audioQueue = DispatchQueue(label: "me.compress.audio", qos: .userInitiated)
                let group = DispatchGroup()

                group.enter()
                videoInput.requestMediaDataWhenReady(on: videoQueue) {
                    while videoInput.isReadyForMoreMediaData {
                        if let sample = videoReaderOutput.copyNextSampleBuffer() {
                            videoInput.append(sample)
                        } else {
                            videoInput.markAsFinished()
                            group.leave()
                            return
                        }
                    }
                }

                if let audioInput = audioInput, let audioReaderOutput = audioReaderOutput {
                    group.enter()
                    audioInput.requestMediaDataWhenReady(on: audioQueue) {
                        while audioInput.isReadyForMoreMediaData {
                            if let sample = audioReaderOutput.copyNextSampleBuffer() {
                                audioInput.append(sample)
                            } else {
                                audioInput.markAsFinished()
                                group.leave()
                                return
                            }
                        }
                    }
                }

                group.notify(queue: .main) {
                    continuation.resume()
                }
            }

            await writer.finishWriting()
            reader.cancelReading()

        if writer.status == .completed {
            return outputUri
        }
        throw NSError(domain: "MediaEngine", code: 4, userInfo: [
            NSLocalizedDescriptionKey: "Compression of \(inputPath) failed: \(writer.error?.localizedDescription ?? "unknown")"
        ])
    }

    private static func videoClipsTimelineOverlap(_ clips: [[String: Any]]) -> Bool {
        var intervals: [(Double, Double)] = []
        for clip in clips {
            guard let start = clip["startTime"] as? Double,
                  let dur = clip["duration"] as? Double else { continue }
            intervals.append((start, start + dur))
        }
        guard intervals.count >= 2 else { return false }
        for i in 0..<(intervals.count - 1) where intervals[i + 1].0 < intervals[i].1 - 1e-4 {
            return true
        }
        return false
    }

    private static func scaleInsertedVideoToTimelineDuration(
        composition: AVMutableComposition,
        insertAt: CMTime,
        insertedSourceDuration: CMTime,
        timelineDurationSeconds: Double
    ) {
        let insertedRange = CMTimeRange(start: insertAt, duration: insertedSourceDuration)
        let target = CMTime(seconds: timelineDurationSeconds, preferredTimescale: 600)
        if CMTimeCompare(insertedRange.duration, target) != 0 {
            composition.scaleTimeRange(insertedRange, toDuration: target)
        }
    }

    /// Overlapping clips each get their own composition track; segment instructions avoid overlapping time ranges.
    private static func insertOverlappingVideoClipsAndInstructions(
        clips: [[String: Any]],
        composition: AVMutableComposition
    ) async throws -> [any AVVideoCompositionInstructionProtocol] {
        struct ClipSlot {
            let start: Double
            let end: Double
            let trackID: CMPersistentTrackID
            let clip: [String: Any]
        }

        var slots: [ClipSlot] = []

        for clip in clips {
            guard let uri = clip["uri"] as? String,
                  let startTime = clip["startTime"] as? Double,
                  let duration = clip["duration"] as? Double else { continue }

            let videoPath = uri.replacingOccurrences(of: "file://", with: "")
            let videoURL = URL(fileURLWithPath: videoPath)
            let asset = AVAsset(url: videoURL)

            guard let assetTrack = try? await asset.loadTracks(withMediaType: .video).first,
                  let compTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else {
                continue
            }

            let clipStart = clip["clipStart"] as? Double ?? 0.0
            let clipEndVal = clip["clipEnd"] as? Double ?? -1.0
            let speed = max(0.01, clip["speed"] as? Double ?? 1.0)
            let clipStartCM = CMTime(seconds: clipStart, preferredTimescale: 600)
            let assetDuration = (try? await asset.load(.duration)) ?? CMTime(seconds: duration * speed, preferredTimescale: 600)

            let sourceRange: CMTimeRange
            if clipEndVal > 0 {
                let clipEndCM = CMTime(seconds: clipEndVal * speed, preferredTimescale: 600)
                sourceRange = CMTimeRange(start: clipStartCM, end: clipEndCM)
            } else {
                let remaining = CMTimeSubtract(assetDuration, clipStartCM)
                let requestedSource = CMTime(seconds: duration * speed, preferredTimescale: 600)
                sourceRange = CMTimeRange(start: clipStartCM, duration: CMTimeMinimum(remaining, requestedSource))
            }

            let insertAt = CMTime(seconds: startTime, preferredTimescale: 600)

            do {
                try compTrack.insertTimeRange(sourceRange, of: assetTrack, at: insertAt)
            } catch {
                throw NSError(domain: "MediaEngine", code: 3, userInfo: [
                    NSLocalizedDescriptionKey: "Failed to insert video track from \(videoPath): \(error.localizedDescription)"
                ])
            }

            Self.scaleInsertedVideoToTimelineDuration(
                composition: composition,
                insertAt: insertAt,
                insertedSourceDuration: sourceRange.duration,
                timelineDurationSeconds: duration
            )

            slots.append(ClipSlot(
                start: startTime,
                end: startTime + duration,
                trackID: compTrack.trackID,
                clip: clip
            ))
        }

        guard !slots.isEmpty else { return [] }

        var boundarySet = Set<Double>()
        for s in slots {
            boundarySet.insert(s.start)
            boundarySet.insert(s.end)
        }
        let boundaries = boundarySet.sorted()

        var result: [any AVVideoCompositionInstructionProtocol] = []
        for i in 0..<(boundaries.count - 1) {
            let t0 = boundaries[i]
            let t1 = boundaries[i + 1]
            if t1 - t0 < 1e-6 { continue }

            let mid = (t0 + t1) / 2
            let activeIndices = slots.indices.filter { slots[$0].start <= mid && mid < slots[$0].end }

            if activeIndices.count == 1 {
                let idx = activeIndices[0]
                let s = slots[idx]
                let filterType = s.clip["filter"] as? String ?? "none"
                let filterIntensity = Float(s.clip["filterIntensity"] as? Double ?? 1.0)
                let opacity = Float(s.clip["opacity"] as? Double ?? 1.0)
                result.append(MediaEngineInstruction(
                    timeRange: CMTimeRange(
                        start: CMTime(seconds: t0, preferredTimescale: 600),
                        duration: CMTime(seconds: t1 - t0, preferredTimescale: 600)
                    ),
                    trackID: s.trackID,
                    filterType: filterType,
                    filterIntensity: filterIntensity,
                    opacity: opacity
                ))
            } else if activeIndices.count >= 2 {
                let sortedIdx = activeIndices.sorted()
                guard sortedIdx.count == 2, sortedIdx[1] == sortedIdx[0] + 1 else { continue }
                let outIdx = sortedIdx[0]
                let inIdx = sortedIdx[1]
                let outgoing = slots[outIdx]
                let incoming = slots[inIdx]
                let transType = outgoing.clip["transition"] as? String ?? "crossfade"
                let filterType = outgoing.clip["filter"] as? String ?? "none"
                let filterIntensity = Float(outgoing.clip["filterIntensity"] as? Double ?? 1.0)
                let opacity = Float(outgoing.clip["opacity"] as? Double ?? 1.0)
                result.append(MediaEngineInstruction(
                    timeRange: CMTimeRange(
                        start: CMTime(seconds: t0, preferredTimescale: 600),
                        duration: CMTime(seconds: t1 - t0, preferredTimescale: 600)
                    ),
                    trackID: outgoing.trackID,
                    filterType: filterType,
                    filterIntensity: filterIntensity,
                    opacity: opacity,
                    transitionTrackID: incoming.trackID,
                    transitionProgress: 0,
                    transitionType: transType
                ))
            }
        }

        return result
    }

    private func composeCompositeVideoImpl(_ config: [String: Any]) async throws -> String {
        let box = ComposeCompositeConfigBox(config)
        return try await MediaEngineHeavyMediaSerializer.shared.run {
            try await Self.composeCompositeVideoImplBody(box.config)
        }
    }

    private static func composeCompositeVideoImplBody(_ config: [String: Any]) async throws -> String {
            guard let outputUri = config["outputUri"] as? String else {
                throw NSError(domain: "MediaEngine", code: 1, userInfo: [NSLocalizedDescriptionKey: "Missing outputUri"])
            }

            let width = config["width"] as? Double ?? 1280
            let height = config["height"] as? Double ?? 720
            let frameRate = config["frameRate"] as? Int ?? 30
#if targetEnvironment(simulator)
            let overlayContentsScale: CGFloat = 1.0
#else
            let overlayContentsScale = await MainActor.run { CGFloat(UIScreen.main.scale) }
#endif

            let enablePassthrough = config["enablePassthrough"] as? Bool ?? true

            let tracks = config["tracks"] as? [[String: Any]] ?? []

            // --- Smart Passthrough Check ---
            var usePassthrough = false
            if enablePassthrough {
                let videoTracks = tracks.filter { ($0["type"] as? String) == "video" }
                let audioTracks = tracks.filter { ($0["type"] as? String) == "audio" }
                let textTracks = tracks.filter { ($0["type"] as? String) == "text" }
                let imageTracks = tracks.filter { ($0["type"] as? String) == "image" }

                if videoTracks.count == 1 && textTracks.isEmpty && audioTracks.isEmpty && imageTracks.isEmpty {
                     let vTrack = videoTracks[0]
                     let clips = vTrack["clips"] as? [[String: Any]] ?? []
                     if clips.count == 1 {
                         let clip = clips[0]
                         let scale      = clip["scale"]      as? Double ?? 1.0
                         let rotation   = clip["rotation"]   as? Double ?? 0.0
                         let filter     = clip["filter"]     as? String
                         let clipStart  = clip["clipStart"]  as? Double ?? 0.0
                         let clipEnd    = clip["clipEnd"]    as? Double ?? -1.0
                         let x          = clip["x"]          as? Double ?? 0.0
                         let y          = clip["y"]          as? Double ?? 0.0
                         let resizeMode = clip["resizeMode"] as? String ?? "cover"
                         let opacity    = clip["opacity"]    as? Double ?? 1.0
                         let speed      = clip["speed"]      as? Double ?? 1.0

                         if scale == 1.0
                             && rotation == 0.0
                             && filter == nil
                             && clipStart == 0.0
                             && clipEnd < 0
                             && x == 0.0
                             && y == 0.0
                             && resizeMode == "cover"
                             && opacity >= 1.0
                             && speed == 1.0 {
                             usePassthrough = true
                         }
                     }
                }
            }

            let composition = AVMutableComposition()
            let videoComposition = AVMutableVideoComposition()
            videoComposition.renderSize = CGSize(width: width, height: height)
            videoComposition.frameDuration = CMTime(value: 1, timescale: CMTimeScale(frameRate))

            var instructions: [any AVVideoCompositionInstructionProtocol] = []
            var overlapCompositorVideo = false

            let outputURL = URL(fileURLWithPath: outputUri.replacingOccurrences(of: "file://", with: ""))
            try? FileManager.default.removeItem(at: outputURL)

            // Animation Layer Setup (for Overlay)
            let parentLayer = CALayer()
            let videoLayer = CALayer()
            parentLayer.frame = CGRect(x: 0, y: 0, width: width, height: height)
            videoLayer.frame = CGRect(x: 0, y: 0, width: width, height: height)
            parentLayer.addSublayer(videoLayer)
            parentLayer.isGeometryFlipped = true

            var hasOverlays = false

            // Collect audio mix parameters
            var audioMixParams: [AVMutableAudioMixInputParameters] = []

            for trackData in tracks {
                let type = trackData["type"] as? String ?? "video"
                let clips = trackData["clips"] as? [[String: Any]] ?? []

                if type == "video" {
                    if Self.videoClipsTimelineOverlap(clips) {
                        let overlapInstr = try await Self.insertOverlappingVideoClipsAndInstructions(
                            clips: clips,
                            composition: composition
                        )
                        instructions.append(contentsOf: overlapInstr)
                        overlapCompositorVideo = true
                    } else {
                    let compTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)

                    for clip in clips {
                        guard let uri = clip["uri"] as? String,
                              let startTime = clip["startTime"] as? Double,
                              let duration = clip["duration"] as? Double else { continue }

                        let videoPath = uri.replacingOccurrences(of: "file://", with: "")
                        let videoURL = URL(fileURLWithPath: videoPath)
                        let asset = AVAsset(url: videoURL)

                        guard let assetTrack = try? await asset.loadTracks(withMediaType: .video).first else {
                            continue
                        }

                        // Trimming: compute source range from clipStart/clipEnd
                        let clipStart = clip["clipStart"] as? Double ?? 0.0
                        let clipEndVal = clip["clipEnd"] as? Double ?? -1.0
                        let speed = max(0.01, clip["speed"] as? Double ?? 1.0)
                        let clipStartCM = CMTime(seconds: clipStart, preferredTimescale: 600)
                        let assetDuration = (try? await asset.load(.duration)) ?? CMTime(seconds: duration * speed, preferredTimescale: 600)

                        let sourceRange: CMTimeRange
                        if clipEndVal > 0 {
                            let clipEndCM = CMTime(seconds: clipEndVal * speed, preferredTimescale: 600)
                            sourceRange = CMTimeRange(start: clipStartCM, end: clipEndCM)
                        } else {
                            // Consume `duration * speed` worth of source content
                            let remaining = CMTimeSubtract(assetDuration, clipStartCM)
                            let requestedSource = CMTime(seconds: duration * speed, preferredTimescale: 600)
                            sourceRange = CMTimeRange(start: clipStartCM, duration: CMTimeMinimum(remaining, requestedSource))
                        }

                        let insertAt = CMTime(seconds: startTime, preferredTimescale: 600)

                        do {
                            try compTrack?.insertTimeRange(sourceRange, of: assetTrack, at: insertAt)
                        } catch {
                            throw NSError(domain: "MediaEngine", code: 3, userInfo: [
                                NSLocalizedDescriptionKey: "Failed to insert video track from \(videoPath): \(error.localizedDescription)"
                            ])
                        }

                        Self.scaleInsertedVideoToTimelineDuration(
                            composition: composition,
                            insertAt: insertAt,
                            insertedSourceDuration: sourceRange.duration,
                            timelineDurationSeconds: duration
                        )

                        let layerInstruction = AVMutableVideoCompositionLayerInstruction(assetTrack: compTrack!)

                        let x = clip["x"] as? Double ?? 0.0
                        let y = clip["y"] as? Double ?? 0.0
                        let scale = clip["scale"] as? Double ?? 1.0
                        let userRotation = clip["rotation"] as? Double ?? 0.0
                        let opacity = Float(clip["opacity"] as? Double ?? 1.0)
                        let resizeMode = clip["resizeMode"] as? String ?? "cover"

                        let naturalSize = try? await assetTrack.load(.naturalSize)
                        let assetSize = naturalSize ?? CGSize(width: 1280, height: 720)

                        let targetW = CGFloat(width)
                        let targetH = CGFloat(height)
                        let videoW = assetSize.width
                        let videoH = assetSize.height

                        var scaleX: CGFloat = 1.0
                        var scaleY: CGFloat = 1.0

                        if resizeMode == "cover" {
                            let widthRatio = targetW / videoW
                            let heightRatio = targetH / videoH
                            let ratio = max(widthRatio, heightRatio)
                            scaleX = ratio; scaleY = ratio
                        } else if resizeMode == "contain" {
                            let widthRatio = targetW / videoW
                            let heightRatio = targetH / videoH
                            let ratio = min(widthRatio, heightRatio)
                            scaleX = ratio; scaleY = ratio
                        } else {
                            scaleX = targetW / videoW; scaleY = targetH / videoH
                        }

                        // Build transform: scale → rotate → translate to center → user offset
                        var t = CGAffineTransform(scaleX: scaleX * CGFloat(scale), y: scaleY * CGFloat(scale))
                        if abs(userRotation) > 0.001 {
                            t = t.rotated(by: CGFloat(userRotation * .pi / 180))
                        }
                        let scaledW = videoW * scaleX * CGFloat(scale)
                        let scaledH = videoH * scaleY * CGFloat(scale)
                        let dx = (targetW - scaledW) / 2 + CGFloat(x) * targetW
                        let dy = (targetH - scaledH) / 2 + CGFloat(y) * targetH
                        t = t.translatedBy(x: dx / (scaleX * CGFloat(scale)), y: dy / (scaleY * CGFloat(scale)))

                        layerInstruction.setTransform(t, at: insertAt)

                        // Opacity
                        if opacity < 1.0 {
                            layerInstruction.setOpacity(opacity, at: insertAt)
                        }

                        let instruction = AVMutableVideoCompositionInstruction()
                        instruction.timeRange = CMTimeRange(start: insertAt, duration: CMTime(seconds: duration, preferredTimescale: 600))
                        instruction.layerInstructions = [layerInstruction]
                        instructions.append(instruction)
                    }
                    }

                } else if type == "audio" {
                    let compTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)
                    for clip in clips {
                        guard let uri = clip["uri"] as? String,
                              let startTime = clip["startTime"] as? Double,
                              let duration = clip["duration"] as? Double else { continue }

                        let audioPath = uri.replacingOccurrences(of: "file://", with: "")
                        let audioURL = URL(fileURLWithPath: audioPath)
                        let asset = AVAsset(url: audioURL)

                        guard let assetTrack = try? await asset.loadTracks(withMediaType: .audio).first else {
                            continue
                        }

                        let clipStart = clip["clipStart"] as? Double ?? 0.0
                        let clipEndVal = clip["clipEnd"] as? Double ?? -1.0
                        let clipStartCM = CMTime(seconds: clipStart, preferredTimescale: 600)
                        let sourceRange: CMTimeRange
                        if clipEndVal > 0 {
                            sourceRange = CMTimeRange(start: clipStartCM, end: CMTime(seconds: clipEndVal, preferredTimescale: 600))
                        } else {
                            let assetDuration = (try? await asset.load(.duration)) ?? CMTime(seconds: duration, preferredTimescale: 600)
                            let remaining = CMTimeSubtract(assetDuration, clipStartCM)
                            let requestedDuration = CMTime(seconds: duration, preferredTimescale: 600)
                            sourceRange = CMTimeRange(start: clipStartCM, duration: CMTimeMinimum(remaining, requestedDuration))
                        }

                        let insertAt = CMTime(seconds: startTime, preferredTimescale: 600)

                        do {
                            try compTrack?.insertTimeRange(sourceRange, of: assetTrack, at: insertAt)
                        } catch {
                            throw NSError(domain: "MediaEngine", code: 3, userInfo: [
                                NSLocalizedDescriptionKey: "Failed to insert audio track from \(audioPath): \(error.localizedDescription)"
                            ])
                        }

                        // Per-track volume with keyframes, fade envelope, or flat volume
                        if let compTrack = compTrack {
                            let volume = Float(clip["volume"] as? Double ?? 1.0)
                            let fadeInDuration = clip["fadeInDuration"] as? Double ?? 0.0
                            let fadeOutDuration = clip["fadeOutDuration"] as? Double ?? 0.0
                            let params = AVMutableAudioMixInputParameters(track: compTrack)

                            // Keyframe-based volume automation
                            let envelope = clip["volumeEnvelope"] as? [String: Any]
                            let keyframes = envelope?["keyframes"] as? [[String: Any]] ?? []

                            if !keyframes.isEmpty {
                                // Sort and apply a volume ramp between each consecutive pair
                                let sorted = keyframes
                                    .compactMap { kf -> (Double, Float)? in
                                        guard let t = kf["time"] as? Double,
                                              let v = kf["volume"] as? Double else { return nil }
                                        return (t, Float(v) * volume)
                                    }
                                    .sorted { $0.0 < $1.0 }
                                for i in 0..<sorted.count {
                                    let (kfTime, kfVol) = sorted[i]
                                    let absTime = CMTimeAdd(insertAt, CMTime(seconds: kfTime, preferredTimescale: 600))
                                    if i + 1 < sorted.count {
                                        let (nextTime, nextVol) = sorted[i + 1]
                                        params.setVolumeRamp(
                                            fromStartVolume: kfVol,
                                            toEndVolume: nextVol,
                                            timeRange: CMTimeRange(
                                                start: absTime,
                                                duration: CMTime(seconds: nextTime - kfTime, preferredTimescale: 600)
                                            )
                                        )
                                    } else {
                                        params.setVolume(kfVol, at: absTime)
                                    }
                                }
                            } else if fadeInDuration > 0 {
                                params.setVolumeRamp(
                                    fromStartVolume: 0,
                                    toEndVolume: volume,
                                    timeRange: CMTimeRange(
                                        start: insertAt,
                                        duration: CMTime(seconds: fadeInDuration, preferredTimescale: 600)
                                    )
                                )
                            } else {
                                params.setVolume(volume, at: insertAt)
                            }

                            if fadeOutDuration > 0 && keyframes.isEmpty {
                                let fadeOutStart = CMTimeSubtract(
                                    CMTimeAdd(insertAt, sourceRange.duration),
                                    CMTime(seconds: fadeOutDuration, preferredTimescale: 600)
                                )
                                params.setVolumeRamp(
                                    fromStartVolume: volume,
                                    toEndVolume: 0,
                                    timeRange: CMTimeRange(
                                        start: fadeOutStart,
                                        duration: CMTime(seconds: fadeOutDuration, preferredTimescale: 600)
                                    )
                                )
                            }

                            audioMixParams.append(params)
                        }
                    }

                } else if type == "text" {
                    hasOverlays = true
                    for clip in clips {
                        let text = clip["text"] as? String ?? ""
                        let fontSize = clip["fontSize"] as? Double ?? 24.0
                        let x = clip["x"] as? Double ?? 0.5
                        let y = clip["y"] as? Double ?? 0.5
                        let colorHex = clip["color"] as? String ?? "#FFFFFF"
                        let startTime = clip["startTime"] as? Double ?? 0.0
                        let clipDuration = clip["duration"] as? Double ?? 5.0

                        let clipOpacity = clip["opacity"] as? Float ?? 1.0

                        // Rich text style
                        let shadowColorHex = clip["shadowColor"] as? String
                        let shadowRadius = clip["shadowRadius"] as? Double ?? 0.0
                        let shadowOffsetX = clip["shadowOffsetX"] as? Double ?? 0.0
                        let shadowOffsetY = clip["shadowOffsetY"] as? Double ?? 0.0

                        let textLayer = CATextLayer()
                        textLayer.string = text
                        textLayer.fontSize = CGFloat(fontSize)
                        textLayer.foregroundColor = UIColor(hex: colorHex)?.cgColor ?? UIColor.white.cgColor
                        textLayer.alignmentMode = .center
                        textLayer.contentsScale = overlayContentsScale
                        textLayer.isWrapped = true

                        let w = CGFloat(width) * 0.8
                        let h = CGFloat(fontSize) * 1.5

                        textLayer.frame = CGRect(
                            x: CGFloat(x) * CGFloat(width) - w / 2,
                            y: CGFloat(y) * CGFloat(height) - h / 2,
                            width: w,
                            height: h
                        )

                        // Shadow via layer properties
                        if let shadowHex = shadowColorHex, shadowRadius > 0 {
                            textLayer.shadowColor = UIColor(hex: shadowHex)?.cgColor
                            textLayer.shadowRadius = CGFloat(shadowRadius)
                            textLayer.shadowOpacity = 1.0
                            textLayer.shadowOffset = CGSize(width: shadowOffsetX, height: shadowOffsetY)
                        }

                        // Stroke via NSAttributedString
                        let strokeWidth = clip["strokeWidth"] as? Double ?? 0.0
                        if strokeWidth > 0, let strokeHex = clip["strokeColor"] as? String {
                            let strokeColor = UIColor(hex: strokeHex) ?? .black
                            let strokeAttr: [NSAttributedString.Key: Any] = [
                                .strokeColor: strokeColor,
                                .strokeWidth: -strokeWidth,  // negative = fill + stroke
                                .foregroundColor: UIColor(hex: colorHex) ?? .white,
                                .font: UIFont.systemFont(ofSize: CGFloat(fontSize))
                            ]
                            textLayer.string = NSAttributedString(string: text, attributes: strokeAttr)
                        }

                        // Background layer
                        let bgPadding = clip["backgroundPadding"] as? Double ?? 8.0
                        if let bgHex = clip["backgroundColor"] as? String {
                            let bgLayer = CALayer()
                            bgLayer.contentsScale = overlayContentsScale
                            bgLayer.backgroundColor = UIColor(hex: bgHex)?.cgColor
                            bgLayer.cornerRadius = 4
                            bgLayer.frame = textLayer.frame.insetBy(dx: -bgPadding, dy: -bgPadding / 2)
                            bgLayer.opacity = 0
                            Self.addTimingAnimation(to: bgLayer, startTime: startTime, duration: clipDuration, maxOpacity: clipOpacity)
                            parentLayer.addSublayer(bgLayer)
                        }

                        textLayer.opacity = 0
                        Self.addTimingAnimation(to: textLayer, startTime: startTime, duration: clipDuration, maxOpacity: clipOpacity)
                        parentLayer.addSublayer(textLayer)
                    }

                } else if type == "image" {
                    hasOverlays = true
                    for clip in clips {
                        guard let uri = clip["uri"] as? String else { continue }
                        let imagePath = uri.replacingOccurrences(of: "file://", with: "")
                        guard let image = UIImage(contentsOfFile: imagePath) else { continue }

                        let startTime = clip["startTime"] as? Double ?? 0.0
                        let clipDuration = clip["duration"] as? Double ?? 5.0
                        let x = clip["x"] as? Double ?? 0.5
                        let y = clip["y"] as? Double ?? 0.5
                        let scale = clip["scale"] as? Double ?? 1.0
                        let rotation = clip["rotation"] as? Double ?? 0.0
                        let clipOpacity = clip["opacity"] as? Float ?? 1.0

                        let imgW = CGFloat(width) * CGFloat(scale)
                        let imgH = image.size.height / image.size.width * imgW

                        let imgLayer = CALayer()
                        imgLayer.contents = image.cgImage
                        imgLayer.contentsScale = overlayContentsScale
                        imgLayer.contentsGravity = .resizeAspectFill
                        imgLayer.frame = CGRect(
                            x: CGFloat(x) * CGFloat(width) - imgW / 2,
                            y: CGFloat(y) * CGFloat(height) - imgH / 2,
                            width: imgW,
                            height: imgH
                        )
                        if abs(rotation) > 0.001 {
                            imgLayer.transform = CATransform3DMakeRotation(
                                CGFloat(rotation * .pi / 180), 0, 0, 1
                            )
                        }
                        imgLayer.opacity = 0
                        Self.addTimingAnimation(to: imgLayer, startTime: startTime, duration: clipDuration, maxOpacity: clipOpacity)
                        parentLayer.addSublayer(imgLayer)
                    }
                }
            }

            videoComposition.instructions = instructions

            let hasFilterOrLowOpacity = tracks.contains { track in
                let t = track["type"] as? String ?? ""
                guard t == "video" else { return false }
                let vClips = track["clips"] as? [[String: Any]] ?? []
                return vClips.contains { clip in
                    (clip["filter"] as? String) != nil || (clip["opacity"] as? Double ?? 1.0) < 0.999
                }
            }
            let needsCompositor = !usePassthrough && (hasFilterOrLowOpacity || overlapCompositorVideo)
            if needsCompositor {
                videoComposition.customVideoCompositorClass = MediaEngineCompositor.self
                if hasFilterOrLowOpacity && !overlapCompositorVideo {
                    var customInstructions: [any AVVideoCompositionInstructionProtocol] = []
                    for trackData in tracks where (trackData["type"] as? String) == "video" {
                        let tClips = trackData["clips"] as? [[String: Any]] ?? []
                        for clip in tClips {
                            guard let startTime = clip["startTime"] as? Double,
                                  let dur = clip["duration"] as? Double else { continue }
                            let filterType = clip["filter"] as? String ?? "none"
                            let filterIntensity = Float(clip["filterIntensity"] as? Double ?? 1.0)
                            let opacity = Float(clip["opacity"] as? Double ?? 1.0)
                            let trackID = composition.tracks(withMediaType: .video).first?.trackID ?? 1
                            let instr = MediaEngineInstruction(
                                timeRange: CMTimeRange(
                                    start: CMTime(seconds: startTime, preferredTimescale: 600),
                                    duration: CMTime(seconds: dur, preferredTimescale: 600)
                                ),
                                trackID: trackID,
                                filterType: filterType,
                                filterIntensity: filterIntensity,
                                opacity: opacity
                            )
                            customInstructions.append(instr)
                        }
                    }
                    videoComposition.instructions = customInstructions
                }
            }

            // Select export preset based on requested bitrate (coarse mapping)
            let requestedBitrate = config["videoBitrate"] as? Int ?? config["bitrate"] as? Int ?? 0
            var presetName: String
            if requestedBitrate > 0 && requestedBitrate <= 2_000_000 {
                presetName = AVAssetExportPreset1280x720
            } else if requestedBitrate > 0 && requestedBitrate <= 5_000_000 {
                presetName = AVAssetExportPreset1920x1080
            } else {
                presetName = AVAssetExportPresetHighestQuality
            }

            if usePassthrough {
                presetName = AVAssetExportPresetPassthrough
            }

            let needsOverlaySecondPass = hasOverlays && !usePassthrough

            if needsOverlaySecondPass {
                let tempURL = FileManager.default.temporaryDirectory
                    .appendingPathComponent("me-compose-pass1-\(UUID().uuidString).mp4")
                try? FileManager.default.removeItem(at: tempURL)
                defer { try? FileManager.default.removeItem(at: tempURL) }

                guard let pass1 = AVAssetExportSession(asset: composition, presetName: presetName) else {
                    throw NSError(domain: "MediaEngine", code: 4, userInfo: [
                        NSLocalizedDescriptionKey: "Failed to create pass-1 export session",
                    ])
                }
                pass1.outputURL = tempURL
                pass1.outputFileType = .mp4
                pass1.videoComposition = videoComposition
                if !audioMixParams.isEmpty {
                    let audioMix = AVMutableAudioMix()
                    audioMix.inputParameters = audioMixParams
                    pass1.audioMix = audioMix
                }
                await pass1.export()
                guard pass1.status == .completed else {
                    throw NSError(domain: "MediaEngine", code: 5, userInfo: [
                        NSLocalizedDescriptionKey: "Video pass-1 export failed: \(pass1.error?.localizedDescription ?? "Unknown error")",
                    ])
                }

                let pass1Asset = AVAsset(url: tempURL)
                let pass1Duration = try await pass1Asset.load(.duration)
                guard let srcVideo = try await pass1Asset.loadTracks(withMediaType: .video).first else {
                    throw NSError(domain: "MediaEngine", code: 5, userInfo: [
                        NSLocalizedDescriptionKey: "Pass-1 output has no video track",
                    ])
                }

                let composition2 = AVMutableComposition()
                guard let vComp2 = composition2.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else {
                    throw NSError(domain: "MediaEngine", code: 4, userInfo: [
                        NSLocalizedDescriptionKey: "Failed to add pass-2 video track",
                    ])
                }
                try vComp2.insertTimeRange(CMTimeRange(start: .zero, duration: pass1Duration), of: srcVideo, at: .zero)

                for srcAudio in try await pass1Asset.loadTracks(withMediaType: .audio) {
                    guard let aComp = composition2.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
                        continue
                    }
                    try aComp.insertTimeRange(CMTimeRange(start: .zero, duration: pass1Duration), of: srcAudio, at: .zero)
                }

                let videoComposition2 = AVMutableVideoComposition()
                videoComposition2.renderSize = CGSize(width: width, height: height)
                videoComposition2.frameDuration = CMTime(value: 1, timescale: CMTimeScale(frameRate))
                let layerInstruction2 = AVMutableVideoCompositionLayerInstruction(assetTrack: vComp2)
                let preferredTransform = try await srcVideo.load(.preferredTransform)
                layerInstruction2.setTransform(preferredTransform, at: .zero)
                let instruction2 = AVMutableVideoCompositionInstruction()
                instruction2.timeRange = CMTimeRange(start: .zero, duration: pass1Duration)
                instruction2.layerInstructions = [layerInstruction2]
                videoComposition2.instructions = [instruction2]
                videoComposition2.animationTool = AVVideoCompositionCoreAnimationTool(
                    postProcessingAsVideoLayer: videoLayer,
                    in: parentLayer
                )

                guard let pass2 = AVAssetExportSession(asset: composition2, presetName: presetName) else {
                    throw NSError(domain: "MediaEngine", code: 4, userInfo: [
                        NSLocalizedDescriptionKey: "Failed to create pass-2 export session",
                    ])
                }
                pass2.outputURL = outputURL
                pass2.outputFileType = .mp4
                pass2.videoComposition = videoComposition2

                await pass2.export()

                if hasOverlays {
                    let cleanup = CALayerCleanupTarget(parent: parentLayer)
                    await MainActor.run { cleanup.removeAllSublayers() }
                }

                guard pass2.status == .completed else {
                    throw NSError(domain: "MediaEngine", code: 5, userInfo: [
                        NSLocalizedDescriptionKey: "Video export to \(outputUri) failed: \(pass2.error?.localizedDescription ?? "Unknown error")",
                    ])
                }
                return outputUri
            }

            if hasOverlays {
                videoComposition.animationTool = AVVideoCompositionCoreAnimationTool(
                    postProcessingAsVideoLayer: videoLayer,
                    in: parentLayer
                )
            }

            guard let exportSession = AVAssetExportSession(asset: composition, presetName: presetName) else {
                throw NSError(domain: "MediaEngine", code: 4, userInfo: [NSLocalizedDescriptionKey: "Failed to create video export session"])
            }

            exportSession.outputURL = outputURL
            exportSession.outputFileType = .mp4

            if !usePassthrough {
                exportSession.videoComposition = videoComposition
            }

            if !audioMixParams.isEmpty {
                let audioMix = AVMutableAudioMix()
                audioMix.inputParameters = audioMixParams
                exportSession.audioMix = audioMix
            }

            await exportSession.export()

            if hasOverlays {
                let cleanup = CALayerCleanupTarget(parent: parentLayer)
                await MainActor.run { cleanup.removeAllSublayers() }
            }

            if exportSession.status == .completed {
                return outputUri
            }
            throw NSError(domain: "MediaEngine", code: 5, userInfo: [
                NSLocalizedDescriptionKey: "Video export to \(outputUri) failed: \(exportSession.error?.localizedDescription ?? "Unknown error")",
            ])
    }

    /// Add a Core Animation visibility window so a layer is visible only during [startTime, startTime+duration].
    /// maxOpacity controls peak opacity (for clips with opacity < 1).
    private static func addTimingAnimation(to layer: CALayer, startTime: Double, duration: Double, maxOpacity: Float = 1.0) {
        let show = CABasicAnimation(keyPath: "opacity")
        show.fromValue = 0; show.toValue = maxOpacity
        show.beginTime = AVCoreAnimationBeginTimeAtZero + startTime
        show.duration = 0.05
        show.fillMode = .forwards
        show.isRemovedOnCompletion = false

        let hide = CABasicAnimation(keyPath: "opacity")
        hide.fromValue = maxOpacity; hide.toValue = 0
        hide.beginTime = AVCoreAnimationBeginTimeAtZero + startTime + duration
        hide.duration = 0.05
        hide.fillMode = .forwards
        hide.isRemovedOnCompletion = false

        let animGroup = CAAnimationGroup()
        animGroup.animations = [show, hide]
        animGroup.duration = 9999
        animGroup.beginTime = AVCoreAnimationBeginTimeAtZero
        animGroup.fillMode = .forwards
        animGroup.isRemovedOnCompletion = false

        layer.add(animGroup, forKey: "visibility")
    }
}

public class MediaEnginePreviewModule: Module {
    public func definition() -> ModuleDefinition {
        Name("MediaEnginePreview")

        View(MediaEnginePreviewView.self) {
            Events("onLoad", "onTimeUpdate", "onPlaybackEnded", "onError")

            Prop("config") { (view: MediaEnginePreviewView, config: [String: Any]) in
                view.updateConfig(config)
            }
            Prop("isPlaying") { (view: MediaEnginePreviewView, playing: Bool) in
                view.setPlaying(playing)
            }
            Prop("muted") { (view: MediaEnginePreviewView, muted: Bool) in
                view.setMuted(muted)
            }
            Prop("currentTime") { (view: MediaEnginePreviewView, seconds: Double) in
                view.setCurrentTime(seconds)
            }
        }
    }
}

// Helper extension to parse hex colors
extension UIColor {
    convenience init?(hex: String) {
        var hexSanitized = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        hexSanitized = hexSanitized.replacingOccurrences(of: "#", with: "")

        var rgb: UInt64 = 0
        guard Scanner(string: hexSanitized).scanHexInt64(&rgb) else { return nil }

        self.init(
            red: CGFloat((rgb & 0xFF0000) >> 16) / 255.0,
            green: CGFloat((rgb & 0x00FF00) >> 8) / 255.0,
            blue: CGFloat(rgb & 0x0000FF) / 255.0,
            alpha: 1.0
        )
    }
}
