
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
            
            // Delete existing file
            try? FileManager.default.removeItem(at: outputURL)
            
            let asset = AVAsset(url: videoURL)
            
            guard let exportSession = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
                throw NSError(domain: "MediaEngine", code: 1, userInfo: [NSLocalizedDescriptionKey: "Failed to create export session"])
            }
            
            exportSession.outputURL = outputURL
            exportSession.outputFileType = .m4a
            exportSession.timeRange = CMTimeRange(start: .zero, duration: asset.duration)
            
            await exportSession.export()
            
            if exportSession.status == .completed {
                return outputUri
            } else {
                throw NSError(domain: "MediaEngine", code: 2, userInfo: [NSLocalizedDescriptionKey: exportSession.error?.localizedDescription ?? "Unknown error"])
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
             
             // Map legacy config to new config structure
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
                        "volume": musicVolume // TODO: Handle volume in composeCompositeVideo
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
                        "uri": "text:\(text)", // Encoded content
                        "text": text,
                        "startTime": textStarts.indices.contains(i) ? textStarts[i] : 0.0,
                        "duration": textDurations.indices.contains(i) ? textDurations[i] : (duration > 0 ? duration : 10.0),
                        "x": textX.indices.contains(i) ? textX[i] : 0.5,
                        "y": textY.indices.contains(i) ? textY[i] : 0.5,
                        "color": textColors.indices.contains(i) ? textColors[i] : "#FFFFFF",
                        "fontSize": textSizes.indices.contains(i) ? textSizes[i] : 24.0,
                        "scale": 1.0 // Text size handles scale
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
             
             // Call new engine
             let advancedConfig: [String: Any] = [
                 "outputUri": outputPath,
                 "width": 1280, // Legacy default
                 "height": 720,
                 "frameRate": 30,
                 "videoBitrate": config["videoBitrate"] ?? config["bitrate"],
                 "audioBitrate": config["audioBitrate"],
                 "enablePassthrough": config["enablePassthrough"] ?? true,
                 "tracks": tracks
             ]
             
             // Recursively call the private implementation? 
             // Or extract implementation to a helper. 
             // Cannot call AsyncFunction from AsyncFunction easily.
             // Best to extract `composeVideoImpl` function.
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
            // Check if we can use Passthrough Preset (No re-encoding)
            var usePassthrough = false
            if enablePassthrough {
                // Criteria: 1 Video Track, 1 Clip, No Text/Overlay, No complex transforms
                let videoTracks = tracks.filter { ($0["type"] as? String) == "video" }
                let audioTracks = tracks.filter { ($0["type"] as? String) == "audio" }
                let textTracks = tracks.filter { ($0["type"] as? String) == "text" }
                
                if videoTracks.count == 1 && textTracks.isEmpty && audioTracks.isEmpty {
                     let vTrack = videoTracks[0]
                     let clips = vTrack["clips"] as? [[String: Any]] ?? []
                     if clips.count == 1 {
                         let clip = clips[0]
                         // Check transforms
                         let scale = clip["scale"] as? Double ?? 1.0
                         let rotation = clip["rotation"] as? Double ?? 0.0
                         let filter = clip["filter"] as? String
                         
                         if scale == 1.0 && rotation == 0.0 && filter == nil {
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
            parentLayer.isGeometryFlipped = true // AVFoundation coordinates
            
            var hasOverlays = false
            
            for trackData in tracks {
                let type = trackData["type"] as? String ?? "video"
                let clips = trackData["clips"] as? [[String: Any]] ?? []
                
                if type == "video" {
                    let compTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)
                    
                    for clip in clips {
                        guard let uri = clip["uri"] as? String,
                              let startTime = clip["startTime"] as? Double,
                              let duration = clip["duration"] as? Double else { continue }
                        
                        let videoURL = URL(fileURLWithPath: uri.replacingOccurrences(of: "file://", with: ""))
                        let asset = AVAsset(url: videoURL)
                        
                        if let assetTrack = try? await asset.loadTracks(withMediaType: .video).first {
                            let range = CMTimeRange(start: .zero, duration: CMTime(seconds: duration, preferredTimescale: 600))
                            let start = CMTime(seconds: startTime, preferredTimescale: 600)
                            
                            try? compTrack?.insertTimeRange(range, of: assetTrack, at: start)
                            
                            let layerInstruction = AVMutableVideoCompositionLayerInstruction(assetTrack: compTrack!)
                            
                            // ... (Existing Transform Logic) ...
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

                            layerInstruction.setTransform(finalTransform, at: start)
                            
                            let instruction = AVMutableVideoCompositionInstruction()
                            instruction.timeRange = CMTimeRange(start: start, duration: range.duration)
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
                         
                         let audioURL = URL(fileURLWithPath: uri.replacingOccurrences(of: "file://", with: ""))
                         let asset = AVAsset(url: audioURL)
                         if let assetTrack = try? await asset.loadTracks(withMediaType: .audio).first {
                             let range = CMTimeRange(start: .zero, duration: CMTime(seconds: duration, preferredTimescale: 600))
                             let start = CMTime(seconds: startTime, preferredTimescale: 600)
                             try? compTrack?.insertTimeRange(range, of: assetTrack, at: start)
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
                         let duration = clip["duration"] as? Double ?? 5.0
                         
                         let textLayer = CATextLayer()
                         textLayer.string = text
                         textLayer.fontSize = CGFloat(fontSize)
                         textLayer.foregroundColor = UIColor(hex: colorHex)?.cgColor ?? UIColor.white.cgColor
                         textLayer.alignmentMode = .center
                         textLayer.contentsScale = UIScreen.main.scale
                         
                         // Size and Pos
                         let w = CGFloat(width) * 0.8 // Max width?
                         let h = CGFloat(fontSize) * 1.5
                         
                         textLayer.frame = CGRect(
                             x: CGFloat(x) * CGFloat(width) - w/2,
                             y: CGFloat(y) * CGFloat(height) - h/2,
                             width: w,
                             height: h
                         )
                         
                         // Animation (Fade in/out)
                         textLayer.opacity = 0
                         
                         let animGroup = CAAnimationGroup()
                         let show = CABasicAnimation(keyPath: "opacity")
                         show.fromValue = 0; show.toValue = 1
                         show.beginTime = AVCoreAnimationBeginTimeAtZero + startTime
                         show.duration = 0.1
                         show.fillMode = .forwards
                         show.isRemovedOnCompletion = false
                         
                         let hide = CABasicAnimation(keyPath: "opacity")
                         hide.fromValue = 1; hide.toValue = 0
                         hide.beginTime = AVCoreAnimationBeginTimeAtZero + startTime + duration
                         hide.duration = 0.1
                         hide.fillMode = .forwards
                         hide.isRemovedOnCompletion = false
                         
                         animGroup.animations = [show, hide]
                         animGroup.duration = 9999
                         animGroup.beginTime = AVCoreAnimationBeginTimeAtZero
                         animGroup.fillMode = .forwards
                         animGroup.isRemovedOnCompletion = false
                         
                         textLayer.add(animGroup, forKey: "visibility")
                         parentLayer.addSublayer(textLayer)
                     }
                }
            }
            
            videoComposition.instructions = instructions
            if hasOverlays {
                videoComposition.animationTool = AVVideoCompositionCoreAnimationTool(postProcessingAsVideoLayer: videoLayer, in: parentLayer)
            }
            
            var presetName = AVAssetExportPresetHighestQuality
            // Map videoProfile/Bitrate to Presets if we can't do arbitrary? 
            // AVFoundation presets are usually "High", "Medium", "Low", "1280x720"...
            
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
            
            await exportSession.export()
             
            if exportSession.status == .completed {
                return outputUri
            } else {
                throw NSError(domain: "MediaEngine", code: 5, userInfo: [NSLocalizedDescriptionKey: exportSession.error?.localizedDescription ?? "Video Export Failed"])
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
