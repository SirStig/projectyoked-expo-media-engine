import { requireNativeViewManager } from 'expo-modules-core';
import { createElement, forwardRef, useRef, useImperativeHandle } from 'react';

/**
 * MediaEnginePreview
 *
 * A native view that renders the VIDEO and AUDIO tracks of a CompositionConfig
 * in real-time, using the same hardware-accelerated pipeline as the export engine.
 *
 * Design intent (CapCut-style editor):
 *   1. Render <MediaEnginePreview> for the video layer — filters, transitions,
 *      speed and opacity are all applied natively, matching the export exactly.
 *   2. Use `useCompositionOverlays(config, currentTime)` to get the active TEXT
 *      and IMAGE clips, then render them in Skia / Reanimated on top so the
 *      user can drag, resize, and rotate them with gesture handlers.
 *   3. The SAME `config` drives both layers and the final export — what you see
 *      is what you get.
 *
 * @see useCompositionOverlays
 */

let NativeView = null;
try {
    NativeView = requireNativeViewManager('MediaEngine');
} catch (_) {
    // Native module unavailable (web, test, storybook, etc.)
}

export const MediaEnginePreview = forwardRef(function MediaEnginePreview(props, ref) {
    const nativeRef = useRef(null);

    useImperativeHandle(ref, () => ({
        /** Seek to an absolute time in seconds (imperative alternative to the currentTime prop). */
        seekTo(seconds) {
            if (nativeRef.current && nativeRef.current.setNativeProps) {
                nativeRef.current.setNativeProps({ currentTime: seconds });
            }
        },
    }));

    if (!NativeView) {
        // Graceful fallback — renders nothing in environments without the native module
        return null;
    }

    return createElement(NativeView, { ...props, ref: nativeRef });
});
