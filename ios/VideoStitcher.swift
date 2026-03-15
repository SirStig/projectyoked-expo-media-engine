import Foundation
import AVFoundation

class VideoStitcher {
    static func stitch(videoPaths: [String], outputUri: String) async throws -> String {
        let composition = AVMutableComposition()

        let videoTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)
        let audioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)

        var currentTime = CMTime.zero

        for path in videoPaths {
            let cleanPath = path.replacingOccurrences(of: "file://", with: "")
            let url = URL(fileURLWithPath: cleanPath)
            let asset = AVAsset(url: url)

            let assetDuration: CMTime
            do {
                assetDuration = try await asset.load(.duration)
            } catch {
                throw NSError(domain: "VideoStitcher", code: 3, userInfo: [
                    NSLocalizedDescriptionKey: "Failed to load duration for \(cleanPath): \(error.localizedDescription)"
                ])
            }

            let range = CMTimeRange(start: .zero, duration: assetDuration)

            if let assetVideoTrack = try? await asset.loadTracks(withMediaType: .video).first {
                do {
                    try videoTrack?.insertTimeRange(range, of: assetVideoTrack, at: currentTime)
                } catch {
                    throw NSError(domain: "VideoStitcher", code: 4, userInfo: [
                        NSLocalizedDescriptionKey: "Failed to insert video track from \(cleanPath): \(error.localizedDescription)"
                    ])
                }
            } else {
                throw NSError(domain: "VideoStitcher", code: 5, userInfo: [
                    NSLocalizedDescriptionKey: "No video track found in \(cleanPath)"
                ])
            }

            // Audio is optional — silently skip if not present
            if let assetAudioTrack = try? await asset.loadTracks(withMediaType: .audio).first {
                try? audioTrack?.insertTimeRange(range, of: assetAudioTrack, at: currentTime)
            }

            currentTime = CMTimeAdd(currentTime, assetDuration)
        }

        guard let exportSession = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetPassthrough) else {
             throw NSError(domain: "VideoStitcher", code: 1, userInfo: [NSLocalizedDescriptionKey: "Failed to create export session for stitch output \(outputUri)"])
        }

        let outputURL = URL(fileURLWithPath: outputUri.replacingOccurrences(of: "file://", with: ""))
        try? FileManager.default.removeItem(at: outputURL)

        exportSession.outputURL = outputURL
        exportSession.outputFileType = .mp4

        await exportSession.export()

        if exportSession.status == .completed {
            return outputUri
        } else {
            throw NSError(domain: "VideoStitcher", code: 2, userInfo: [
                NSLocalizedDescriptionKey: "Stitch to \(outputUri) failed: \(exportSession.error?.localizedDescription ?? "Unknown error")"
            ])
        }
    }
}
