package com.projectyoked.mediaengine

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class MediaEnginePreviewModule : Module() {
    override fun definition() = ModuleDefinition {
        Name("MediaEnginePreview")

        View(MediaEnginePreviewView::class) {
            Events("onLoad", "onTimeUpdate", "onPlaybackEnded", "onError")

            Prop("config") { view: MediaEnginePreviewView, config: Map<String, Any?> ->
                view.updateConfig(config)
            }
            Prop("isPlaying") { view: MediaEnginePreviewView, playing: Boolean ->
                view.setPlaying(playing)
            }
            Prop("muted") { view: MediaEnginePreviewView, muted: Boolean ->
                view.setMuted(muted)
            }
            Prop("currentTime") { view: MediaEnginePreviewView, seconds: Double ->
                view.setCurrentTime(seconds)
            }
        }
    }
}
