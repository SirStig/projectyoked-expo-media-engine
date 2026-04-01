import ExpoModulesCore
import AVFoundation
import CoreImage
import UIKit

/**
 * MediaEnginePreviewView
 *
 * An ExpoView that plays a CompositionConfig in real-time using AVPlayer.
 *
 * Architecture:
 *   - Native layer: renders VIDEO + AUDIO tracks through the same
 *     AVMutableComposition + MediaEngineCompositor pipeline used for export,
 *     so the preview is frame-accurate including filters, transitions, and opacity.
 *   - JS layer: TEXT and IMAGE clips are intentionally excluded from the native
 *     composition here; the `useCompositionOverlays` JS hook supplies those so
 *     Skia / RN gesture layers can handle interactive positioning.
 *
 * Props (matched in `MediaEnginePreviewModule` in MediaEngineModule.swift):
 *   config       — CompositionConfig dict (same shape as composeCompositeVideo)
 *   isPlaying    — Bool: play / pause
 *   muted        — Bool
 *   currentTime  — Double (seconds): controlled seek when paused
 *
 * Events:
 *   onLoad          { duration: number }
 *   onTimeUpdate    { currentTime: number }   — fires ~30 fps while playing
 *   onPlaybackEnded {}
 *   onError         { message: string }
 */
public class MediaEnginePreviewView: ExpoView {

    // ── Events ────────────────────────────────────────────────────────────────
    let onLoad          = EventDispatcher()
    let onTimeUpdate    = EventDispatcher()
    let onPlaybackEnded = EventDispatcher()
    let onError         = EventDispatcher()

    // ── AVPlayer ──────────────────────────────────────────────────────────────
    private var player:       AVPlayer?
    private var playerLayer:  AVPlayerLayer?
    private var timeObserver: Any?
    private var endObserver:  Any?

    // ─────────────────────────────────────────────────────────────────────────
    // Construction
    // ─────────────────────────────────────────────────────────────────────────

    public required init(appContext: AppContext? = nil) {
        super.init(appContext: appContext)
        backgroundColor = .black
    }

    public override func layoutSubviews() {
        super.layoutSubviews()
        playerLayer?.frame = bounds
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API (called from module Prop handlers)
    // ─────────────────────────────────────────────────────────────────────────

    func updateConfig(_ configDict: [String: Any]) {
        teardown()

        guard let (composition, videoComposition) = buildComposition(from: configDict) else {
            onError(["message": "Failed to build preview composition"])
            return
        }

        let item = AVPlayerItem(asset: composition)
        if let vc = videoComposition { item.videoComposition = vc }

        let newPlayer = AVPlayer(playerItem: item)
        newPlayer.isMuted = false   // respect muted prop later
        player = newPlayer

        let layer = AVPlayerLayer(player: newPlayer)
        layer.videoGravity = .resizeAspect
        layer.frame = bounds
        self.layer.addSublayer(layer)
        playerLayer = layer

        // ~30 fps time observer
        let interval = CMTime(value: 1, timescale: 30)
        timeObserver = newPlayer.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] t in
            self?.onTimeUpdate(["currentTime": CMTimeGetSeconds(t)])
        }

        // End-of-playback observer
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object:  item,
            queue:  .main
        ) { [weak self] _ in
            self?.onPlaybackEnded([:])
        }

        let durationSec = CMTimeGetSeconds(composition.duration)
        onLoad(["duration": durationSec.isFinite ? durationSec : 0.0])
    }

    func setPlaying(_ playing: Bool) {
        if playing { player?.play() } else { player?.pause() }
    }

    func setMuted(_ muted: Bool) {
        player?.isMuted = muted
    }

    func setCurrentTime(_ seconds: Double) {
        let t = CMTime(seconds: seconds, preferredTimescale: 600)
        // Use a small tolerance (100 ms) for fast seeking; zero-tolerance is expensive
        // and causes the player to stall when called frequently during playback.
        let tol = CMTime(seconds: 0.1, preferredTimescale: 600)
        player?.seek(to: t, toleranceBefore: tol, toleranceAfter: tol)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    public override func removeFromSuperview() {
        teardown()
        super.removeFromSuperview()
    }

    private func teardown() {
        player?.pause()
        if let obs = timeObserver { player?.removeTimeObserver(obs); timeObserver = nil }
        if let obs = endObserver  { NotificationCenter.default.removeObserver(obs); endObserver = nil }
        playerLayer?.removeFromSuperlayer()
        playerLayer = nil
        player      = nil
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Composition builder
    // Renders VIDEO + AUDIO tracks only. TEXT/IMAGE clips live in the JS layer.
    // ─────────────────────────────────────────────────────────────────────────

    private func buildComposition(from configDict: [String: Any])
        -> (AVMutableComposition, AVVideoComposition?)? {

        let tracks = configDict["tracks"] as? [[String: Any]] ?? []
        let videoTracks = tracks.filter { ($0["type"] as? String) == "video" }
        let audioTracks = tracks.filter { ($0["type"] as? String) == "audio" }

        guard !videoTracks.isEmpty else { return nil }

        let composition  = AVMutableComposition()
        // Standard-path instructions (AVMutableVideoCompositionLayerInstruction handles transforms)
        var instructions = [AVMutableVideoCompositionInstruction]()
        // Compositor-path instructions (MediaEngineInstruction carries filter/opacity data)
        var compositorInstructions = [MediaEngineInstruction]()

        var needsCompositor = false
        var trackID: CMPersistentTrackID = 1

        let width  = configDict["width"]  as? Int ?? 1280
        let height = configDict["height"] as? Int ?? 720

        // ── Add video clips ───────────────────────────────────────────────────
        for track in videoTracks {
            let clips = track["clips"] as? [[String: Any]] ?? []
            for clip in clips {
                guard let uriStr = clip["uri"] as? String,
                      let url    = URL(string: uriStr) else { continue }

                let asset      = AVURLAsset(url: url)
                let startTime  = clip["startTime"] as? Double ?? 0.0
                let duration   = clip["duration"]  as? Double ?? 0.0
                let clipStart  = clip["clipStart"] as? Double ?? 0.0
                let speed      = clip["speed"]     as? Double ?? 1.0
                let opacity    = Float(clip["opacity"] as? Double ?? 1.0)
                let filterType = clip["filter"]    as? String ?? "none"
                let filterIntensity = Float(clip["filterIntensity"] as? Double ?? 1.0)

                // Spatial transform props
                let x          = clip["x"]          as? Double ?? 0.0
                let y          = clip["y"]          as? Double ?? 0.0
                let scale      = clip["scale"]      as? Double ?? 1.0
                let userRotation = clip["rotation"] as? Double ?? 0.0
                let resizeMode = clip["resizeMode"] as? String ?? "cover"

                guard let assetVideoTrack = asset.tracks(withMediaType: .video).first else { continue }

                // Effective source range (speed-adjusted)
                let sourceDur = duration * speed
                let sourceRange = CMTimeRange(
                    start:    CMTime(seconds: clipStart, preferredTimescale: 600),
                    duration: CMTime(seconds: sourceDur, preferredTimescale: 600)
                )

                let compTrack: AVMutableCompositionTrack
                if let existing = composition.tracks(withMediaType: .video).first(where: { $0.trackID == trackID }) {
                    compTrack = existing
                } else {
                    guard let t = composition.addMutableTrack(withMediaType: .video, preferredTrackID: trackID) else { continue }
                    compTrack = t
                }

                let insertAt = CMTime(seconds: startTime, preferredTimescale: 600)
                try? compTrack.insertTimeRange(sourceRange, of: assetVideoTrack, at: insertAt)

                // Speed: scale the inserted range
                if abs(speed - 1.0) > 0.001 {
                    let insertedRange = CMTimeRange(
                        start:    insertAt,
                        duration: CMTime(seconds: sourceDur, preferredTimescale: 600)
                    )
                    composition.scaleTimeRange(
                        insertedRange,
                        toDuration: CMTime(seconds: duration, preferredTimescale: 600)
                    )
                }

                // ── Build layer instruction with full spatial transform ────────
                let clipDuration = CMTime(seconds: duration, preferredTimescale: 600)
                let instructionTimeRange = CMTimeRange(start: insertAt, duration: clipDuration)

                let layerInstruction = AVMutableVideoCompositionLayerInstruction(assetTrack: compTrack)

                // Apply preferredTransform (handles device rotation, e.g. portrait videos)
                let prefTransform = assetVideoTrack.preferredTransform
                let naturalSize   = assetVideoTrack.naturalSize
                // Detect portrait rotation by checking if the transform swaps width/height
                let isPortrait = abs(prefTransform.b) == 1.0 || abs(prefTransform.c) == 1.0
                let videoW = isPortrait ? naturalSize.height : naturalSize.width
                let videoH = isPortrait ? naturalSize.width  : naturalSize.height
                let targetW = CGFloat(width)
                let targetH = CGFloat(height)

                // Compute fill scale based on resizeMode
                var scaleX: CGFloat
                var scaleY: CGFloat
                switch resizeMode {
                case "contain":
                    let r = min(targetW / videoW, targetH / videoH)
                    scaleX = r; scaleY = r
                case "stretch":
                    scaleX = targetW / videoW; scaleY = targetH / videoH
                default: // "cover"
                    let r = max(targetW / videoW, targetH / videoH)
                    scaleX = r; scaleY = r
                }

                // Build transform: preferredTransform → resize → user scale → user rotation → center + offset
                var t = prefTransform
                    .concatenating(CGAffineTransform(scaleX: scaleX * CGFloat(scale),
                                                      y: scaleY * CGFloat(scale)))
                if abs(userRotation) > 0.001 {
                    t = t.rotated(by: CGFloat(userRotation * .pi / 180.0))
                }
                let scaledW = videoW * scaleX * CGFloat(scale)
                let scaledH = videoH * scaleY * CGFloat(scale)
                let dx = (targetW - scaledW) / 2.0 + CGFloat(x) * targetW
                let dy = (targetH - scaledH) / 2.0 + CGFloat(y) * targetH
                t = t.translatedBy(x: dx / (scaleX * CGFloat(scale)),
                                   y: dy / (scaleY * CGFloat(scale)))
                layerInstruction.setTransform(t, at: insertAt)

                if opacity < 0.999 {
                    layerInstruction.setOpacity(opacity, at: insertAt)
                    needsCompositor = true
                }

                // Standard-path instruction
                let instruction = AVMutableVideoCompositionInstruction()
                instruction.timeRange = instructionTimeRange
                instruction.layerInstructions = [layerInstruction]
                instructions.append(instruction)

                // Compositor-path instruction (carries filter + opacity for MediaEngineCompositor)
                let meInstruction = MediaEngineInstruction(
                    timeRange:        instructionTimeRange,
                    trackID:          trackID,
                    filterType:       filterType,
                    filterIntensity:  filterIntensity,
                    opacity:          opacity
                )
                compositorInstructions.append(meInstruction)

                if filterType != "none" { needsCompositor = true }

                trackID += 1
            }
        }

        // ── Add audio clips ───────────────────────────────────────────────────
        for track in audioTracks {
            let clips = track["clips"] as? [[String: Any]] ?? []
            for clip in clips {
                guard let uriStr  = clip["uri"] as? String,
                      let url     = URL(string: uriStr),
                      let assetAT = AVURLAsset(url: url).tracks(withMediaType: .audio).first else { continue }

                let startTime = clip["startTime"] as? Double ?? 0.0
                let duration  = clip["duration"]  as? Double ?? 0.0
                let clipStart = clip["clipStart"]  as? Double ?? 0.0

                guard let compAudio = composition.addMutableTrack(
                    withMediaType: .audio, preferredTrackID: trackID) else { continue }

                let sourceRange = CMTimeRange(
                    start:    CMTime(seconds: clipStart,  preferredTimescale: 600),
                    duration: CMTime(seconds: duration,   preferredTimescale: 600)
                )
                try? compAudio.insertTimeRange(
                    sourceRange, of: assetAT,
                    at: CMTime(seconds: startTime, preferredTimescale: 600))

                trackID += 1
            }
        }

        // ── Build AVVideoComposition ──────────────────────────────────────────
        var videoComposition: AVVideoComposition?

        if needsCompositor {
            // Full custom compositor path — uses MediaEngineInstruction so that
            // MediaEngineCompositor.startRequest can cast successfully.
            let vc = AVMutableVideoComposition()
            vc.customVideoCompositorClass = MediaEngineCompositor.self
            vc.renderSize = CGSize(width: width, height: height)
            vc.frameDuration = CMTime(value: 1, timescale: 30)
            vc.instructions = compositorInstructions
            videoComposition = vc
        } else if !instructions.isEmpty {
            // Standard path (no filters, no custom compositor) — spatial transforms
            // are already baked into the AVMutableVideoCompositionLayerInstruction.
            let vc = AVMutableVideoComposition()
            vc.renderSize = CGSize(width: width, height: height)
            vc.frameDuration = CMTime(value: 1, timescale: 30)
            vc.instructions = instructions
            videoComposition = vc
        }

        return (composition, videoComposition)
    }
}
