
import ExpoModulesCore
import AVFoundation
import UIKit

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
            let frameCount = UInt32(file.length)

            guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount) else {
                return []
            }

            try file.read(into: buffer)

            let _ = Int(format.channelCount)
            guard let floatData = buffer.floatChannelData?[0] else { return [] }

            let samplesPerPixel = Int(frameCount) / samples
            var result: [Float] = []

            for i in 0..<samples {
                let start = i * samplesPerPixel
                var rms: Float = 0
                for j in 0..<samplesPerPixel {
                    if start + j < Int(frameCount) {
                        let sample = floatData[start + j]
                        rms += sample * sample
                    }
                }
                rms = sqrt(rms / Float(samplesPerPixel))
                result.append(min(1.0, rms * 5.0))
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

             let advancedConfig: [String: Any] = [
                 "outputUri": outputPath,
                 "width": 1280,
                 "height": 720,
                 "frameRate": 30,
                 "videoBitrate": config["videoBitrate"] ?? config["bitrate"],
                 "audioBitrate": config["audioBitrate"],
                 "enablePassthrough": config["enablePassthrough"] ?? true,
                 "tracks": tracks
             ]

             return try await composeCompositeVideoImpl(advancedConfig)
        }

        // MARK: - Advanced Composite Video
        AsyncFunction("composeCompositeVideo") { (config: [String: Any]) -> String in
            return try await composeCompositeVideoImpl(config)
        }

        // MARK: - Utilities
        AsyncFunction("stitchVideos") { (videoPaths: [String], outputUri: String) -> String in
            return try await VideoStitcher.stitch(videoPaths: videoPaths, outputUri: outputUri)
        }

        // MARK: - Video Compression
        AsyncFunction("compressVideo") { (config: [String: Any]) -> String in
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
            } else {
                throw NSError(domain: "MediaEngine", code: 4, userInfo: [
                    NSLocalizedDescriptionKey: "Compression of \(inputPath) failed: \(writer.error?.localizedDescription ?? "unknown")"
                ])
            }
        }
    }

    private func composeCompositeVideoImpl(_ config: [String: Any]) async throws -> String {
            guard let outputUri = config["outputUri"] as? String else {
                throw NSError(domain: "MediaEngine", code: 1, userInfo: [NSLocalizedDescriptionKey: "Missing outputUri"])
            }

            let width = config["width"] as? Double ?? 1280
            let height = config["height"] as? Double ?? 720
            let frameRate = config["frameRate"] as? Int ?? 30

            let enablePassthrough = config["enablePassthrough"] as? Bool ?? true

            let tracks = config["tracks"] as? [[String: Any]] ?? []

            // --- Smart Passthrough Check ---
            var usePassthrough = false
            if enablePassthrough {
                let videoTracks = tracks.filter { ($0["type"] as? String) == "video" }
                let audioTracks = tracks.filter { ($0["type"] as? String) == "audio" }
                let textTracks = tracks.filter { ($0["type"] as? String) == "text" }

                if videoTracks.count == 1 && textTracks.isEmpty && audioTracks.isEmpty {
                     let vTrack = videoTracks[0]
                     let clips = vTrack["clips"] as? [[String: Any]] ?? []
                     if clips.count == 1 {
                         let clip = clips[0]
                         let scale = clip["scale"] as? Double ?? 1.0
                         let rotation = clip["rotation"] as? Double ?? 0.0
                         let filter = clip["filter"] as? String
                         let clipStart = clip["clipStart"] as? Double ?? 0.0
                         let clipEnd = clip["clipEnd"] as? Double ?? -1.0

                         if scale == 1.0 && rotation == 0.0 && filter == nil && clipStart == 0.0 && clipEnd < 0 {
                             usePassthrough = true
                         }
                     }
                }
            }

            let composition = AVMutableComposition()
            let videoComposition = AVMutableVideoComposition()
            videoComposition.renderSize = CGSize(width: width, height: height)
            videoComposition.frameDuration = CMTime(value: 1, timescale: CMTimeScale(frameRate))

            var instructions: [AVVideoCompositionInstruction] = []

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
                        let clipStartCM = CMTime(seconds: clipStart, preferredTimescale: 600)
                        let sourceRange: CMTimeRange
                        if clipEndVal > 0 {
                            let clipEndCM = CMTime(seconds: clipEndVal, preferredTimescale: 600)
                            sourceRange = CMTimeRange(start: clipStartCM, end: clipEndCM)
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
                                NSLocalizedDescriptionKey: "Failed to insert video track from \(videoPath): \(error.localizedDescription)"
                            ])
                        }

                        let layerInstruction = AVMutableVideoCompositionLayerInstruction(assetTrack: compTrack!)

                        let x = clip["x"] as? Double ?? 0.0
                        let y = clip["y"] as? Double ?? 0.0
                        let scale = clip["scale"] as? Double ?? 1.0
                        let rotation = clip["rotation"] as? Double ?? 0.0
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

                        let aspectTransform = CGAffineTransform(scaleX: scaleX, y: scaleY)
                        let scaledW = videoW * scaleX
                        let scaledH = videoH * scaleY
                        let dx = (targetW - scaledW) / 2
                        let dy = (targetH - scaledH) / 2
                        let finalTransform = aspectTransform.concatenating(CGAffineTransform(translationX: dx, y: dy))

                        layerInstruction.setTransform(finalTransform, at: insertAt)

                        let instruction = AVMutableVideoCompositionInstruction()
                        instruction.timeRange = CMTimeRange(start: insertAt, duration: sourceRange.duration)
                        instruction.layerInstructions = [layerInstruction]
                        instructions.append(instruction)
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

                        // Per-track volume with optional fade envelope
                        if let compTrack = compTrack {
                            let volume = Float(clip["volume"] as? Double ?? 1.0)
                            let fadeInDuration = clip["fadeInDuration"] as? Double ?? 0.0
                            let fadeOutDuration = clip["fadeOutDuration"] as? Double ?? 0.0

                            let params = AVMutableAudioMixInputParameters(track: compTrack)

                            if fadeInDuration > 0 {
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

                            if fadeOutDuration > 0 {
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
                        textLayer.contentsScale = UIScreen.main.scale
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

                        // Background layer
                        if let bgHex = clip["backgroundColor"] as? String {
                            let bgLayer = CALayer()
                            bgLayer.backgroundColor = UIColor(hex: bgHex)?.cgColor
                            bgLayer.cornerRadius = 4
                            bgLayer.frame = textLayer.frame.insetBy(dx: -8, dy: -4)
                            bgLayer.opacity = 0
                            addTimingAnimation(to: bgLayer, startTime: startTime, duration: clipDuration)
                            parentLayer.addSublayer(bgLayer)
                        }

                        textLayer.opacity = 0
                        addTimingAnimation(to: textLayer, startTime: startTime, duration: clipDuration)
                        parentLayer.addSublayer(textLayer)
                    }

                } else if type == "image" {
                    hasOverlays = true
                    for clip in clips {
                        guard let uri = clip["uri"] as? String else { continue }
                        let imagePath = uri.replacingOccurrences(of: "file://", with: "")
                        guard let image = UIImage(contentsOfFile: imagePath) else {
                            continue
                        }

                        let startTime = clip["startTime"] as? Double ?? 0.0
                        let clipDuration = clip["duration"] as? Double ?? 5.0
                        let x = clip["x"] as? Double ?? 0.5
                        let y = clip["y"] as? Double ?? 0.5
                        let scale = clip["scale"] as? Double ?? 1.0

                        let imgW = CGFloat(width) * CGFloat(scale)
                        let imgH = image.size.height / image.size.width * imgW

                        let imgLayer = CALayer()
                        imgLayer.contents = image.cgImage
                        imgLayer.contentsGravity = .resizeAspectFill
                        imgLayer.frame = CGRect(
                            x: CGFloat(x) * CGFloat(width) - imgW / 2,
                            y: CGFloat(y) * CGFloat(height) - imgH / 2,
                            width: imgW,
                            height: imgH
                        )
                        imgLayer.opacity = 0
                        addTimingAnimation(to: imgLayer, startTime: startTime, duration: clipDuration)
                        parentLayer.addSublayer(imgLayer)
                    }
                }
            }

            videoComposition.instructions = instructions
            if hasOverlays {
                videoComposition.animationTool = AVVideoCompositionCoreAnimationTool(
                    postProcessingAsVideoLayer: videoLayer,
                    in: parentLayer
                )
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

            guard let exportSession = AVAssetExportSession(asset: composition, presetName: presetName) else {
                throw NSError(domain: "MediaEngine", code: 4, userInfo: [NSLocalizedDescriptionKey: "Failed to create video export session"])
            }

            exportSession.outputURL = outputURL
            exportSession.outputFileType = .mp4

            if !usePassthrough {
                exportSession.videoComposition = videoComposition
            }

            // Apply audio mix if we have per-track volume params
            if !audioMixParams.isEmpty {
                let audioMix = AVMutableAudioMix()
                audioMix.inputParameters = audioMixParams
                exportSession.audioMix = audioMix
            }

            await exportSession.export()

            if exportSession.status == .completed {
                return outputUri
            } else {
                throw NSError(domain: "MediaEngine", code: 5, userInfo: [
                    NSLocalizedDescriptionKey: "Video export to \(outputUri) failed: \(exportSession.error?.localizedDescription ?? "Unknown error")"
                ])
            }
    }

    /// Helper to add a fade-in/fade-out animation that makes a layer visible only during [startTime, startTime+duration]
    private func addTimingAnimation(to layer: CALayer, startTime: Double, duration: Double) {
        let show = CABasicAnimation(keyPath: "opacity")
        show.fromValue = 0; show.toValue = 1
        show.beginTime = AVCoreAnimationBeginTimeAtZero + startTime
        show.duration = 0.05
        show.fillMode = .forwards
        show.isRemovedOnCompletion = false

        let hide = CABasicAnimation(keyPath: "opacity")
        hide.fromValue = 1; hide.toValue = 0
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
