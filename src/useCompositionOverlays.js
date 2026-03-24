import { useMemo } from 'react';

import { resolveKeyframe } from './overlayKeyframes.js';

/**
 * useCompositionOverlays
 *
 * Returns the set of TEXT and IMAGE clips that are active at `currentTime`,
 * with all transform and style properties resolved — ready for the caller to
 * render via Skia, Reanimated, or plain React Native views.
 *
 * This hook is the JS counterpart to the native preview view: the native layer
 * renders video (and applies hardware-accelerated filters/transitions), while
 * this hook gives the app everything it needs to render interactive overlays
 * in the same normalized coordinate space used by the export engine.
 *
 * Coordinate system (matches export):
 *   x, y    — normalized 0..1 of the composition canvas (0,0 = top-left)
 *   scale   — multiplier (1.0 = original size)
 *   rotation— degrees, clockwise
 *
 * @param {import('./index.d').CompositionConfig} config
 * @param {number} currentTime  — playback position in seconds
 * @returns {ActiveOverlay[]}
 */
export function useCompositionOverlays(config, currentTime) {
    return useMemo(() => {
        if (!config?.tracks) return [];

        const overlays = [];

        config.tracks.forEach((track, trackIndex) => {
            const type = track.type;
            if (type !== 'text' && type !== 'image') return;

            track.clips.forEach((clip, clipIndex) => {
                const start = clip.startTime ?? 0;
                const end   = start + (clip.duration ?? 0);

                if (currentTime < start || currentTime >= end) return;

                // Local time within this clip (for animation keyframe interpolation)
                const localTime = currentTime - start;

                // Resolve keyframe-animated transforms (if animations defined)
                const anim      = clip.animations ?? {};
                const x         = resolveKeyframe(anim.x,        localTime, clip.x        ?? 0.5);
                const y         = resolveKeyframe(anim.y,        localTime, clip.y        ?? 0.5);
                const scale     = resolveKeyframe(anim.scale,    localTime, clip.scale    ?? 1.0);
                const rotation  = resolveKeyframe(anim.rotation, localTime, clip.rotation ?? 0);
                const opacity   = resolveKeyframe(anim.opacity,  localTime, clip.opacity  ?? 1.0);

                /** @type {ActiveOverlay} */
                const overlay = {
                    id:         `track-${trackIndex}-clip-${clipIndex}`,
                    trackIndex,
                    clipIndex,
                    type,
                    // Transforms (normalized / degrees / multiplier)
                    x,
                    y,
                    scale,
                    rotation,
                    opacity,
                    // Timing
                    startTime:  start,
                    duration:   clip.duration ?? 0,
                    localTime,
                    // Text-specific
                    text:       clip.text,
                    textStyle:  clip.textStyle,
                    // Flat text aliases (legacy support)
                    color:      clip.textStyle?.color  ?? clip.color  ?? '#FFFFFF',
                    fontSize:   clip.textStyle?.fontSize ?? clip.fontSize ?? 40,
                    fontWeight: clip.textStyle?.fontWeight ?? 'normal',
                    backgroundColor:   clip.textStyle?.backgroundColor,
                    backgroundPadding: clip.textStyle?.backgroundPadding ?? 8,
                    shadowColor:   clip.textStyle?.shadowColor,
                    shadowRadius:  clip.textStyle?.shadowRadius ?? 0,
                    shadowOffsetX: clip.textStyle?.shadowOffsetX ?? 0,
                    shadowOffsetY: clip.textStyle?.shadowOffsetY ?? 0,
                    strokeColor: clip.textStyle?.strokeColor,
                    strokeWidth: clip.textStyle?.strokeWidth ?? 0,
                    // Image-specific
                    uri: clip.uri,
                };

                overlays.push(overlay);
            });
        });

        // Sort by track index so higher tracks render on top
        return overlays.sort((a, b) => a.trackIndex - b.trackIndex);
    }, [config, currentTime]);
}
