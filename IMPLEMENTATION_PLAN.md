# Media Engine Implementation Plan ("The CapCut Standard")

## Goal
Build a professional-grade, non-linear video editing engine for React Native that matches the feature set and robustness of industry leaders like CapCut.

## Core Architecture

### Render Loop ("The Heartbeat")
- **Architecture**: Feed-and-Drain Loop.
- **Why**: Decouples input (decoding) from output (rendering). Eliminates "starvation" where the encoder waits for the decoder or vice versa.
- **Logic**:
    1.  Calculates `timelineTime`.
    2.  Finds all active clips at this time.
    3.  Feeds `MediaCodec` decoders with input packets.
    4.  Drains `MediaCodec` output buffers to `SurfaceTextures`.
    5.  Draws `SurfaceTextures` to OpenGL Framebuffer using `TextureRenderer`.
    6.  Composes layers (Video -> Images -> Text -> Overlay).
    7.  Swaps buffers to Encoder Surface.

### Data Model
Input: `CompositionConfig` (JSON)
- `width`, `height`, `fps`, `bitrate`
- `tracks`: Array of Track objects.
    - `type`: 'video', 'audio', 'text', 'image'
    - `clips`: Array of Clip objects.
        - `uri`: Source file.
        - `startTime`, `duration`: Timeline placement.
        - `clipStart`, `clipEnd`: Trimming (Source time).
        - `resizeMode`: 'cover', 'contain', 'stretch'.
        - `transform`: `{ x, y, scale, rotation }`.
        - `effects`: Array of filters/transitions.

---

## detailed Roadmap

### Phase 1: Core Rendering & Geometry (Status: IN PROGRESS)
**Goal**: Videos render correctly without distortion.
- [x] **TextureRenderer**: Implement ES 2.0 renderer with MVP Matrix support.
- [x] **MVP Matrix Logic**: Calculate aspect-ratio preserving scaling matrices.
- [x] **Transformations**: Support `x`, `y`, `scale`, `rotation`, and `resizeMode`.
- [x] **Optimization**: Burst-mode feed-and-drain loop.
- [ ] **Verification**: Confirm "black box" and "squished" issues are resolved.
- [ ] **Static Images**: Implement loading images into GL Textures and rendering them like 0-fps video frames.

### Phase 2: Advanced Composition & Editing
**Goal**: Complex timeline editing features.
- [ ] **Trimming**: 
    -   Implement `clipStart` and `clipEnd` properties.
    -   Modify `extractor.seekTo` logic to account for `clipStart` offset.
- [ ] **Multi-Track / PIP**:
    -   Allow rendering multiple video clips simultaneously.
    -   *Challenge*: Managing multiple hardware decoders (Android limit usually ~4-8).
    -   *Fallback*: Software decoding or sequentially rendering to FBOs if hardware limit hit.
- [ ] **Z-Ordering**: 
    -   Render tracks from index 0 -> N (Painter's algorithm).
    -   Support "Bring to Front" / "Send to Back".

### Phase 3: The Audio Engine
**Goal**: Pro-level audio mixing.
- [ ] **Audio Extraction**:
    -   Use `MediaExtractor` to pull AAC/MP3 samples.
    -   Decode to PCM using `MediaCodec`.
- [ ] **Mixing**:
    -   Sum PCM samples from multiple tracks.
    -   Apply volume envelopes (fades, keyframes).
    -   Handle sample rate mismatches (Resampling to 44.1kHz or 48kHz).
- [ ] **Waveform Data**:
    -   Generate JSON array of amplitude peaks for UI visualization.

### Phase 4: Visual Effects (Shaders)
**Goal**: "CapCut" style flair.
- [ ] **Shader Library**:
    -   Common filters: LUT (Look Up Table) support (Standard .cube files).
    -   Effects: Blur, Glitch, Aberration, Vignette.
- [ ] **Transitions**:
    -   GLSL Transitions (Dissolve, Wipe, Slide).
    -   Requires rendering *previous* and *next* clip simultaneously to FBOs, then mixing.
- [ ] **Masking**:
    -   Alpha channel masking (Circle, Standard Shapes).
    -   Chroma Key (Green Screen).

### Phase 5: Overlays & Text
**Goal**: High quality titling.
- [ ] **Text Engine**:
    -   Render Android `TextView` / `Canvas` to Bitmap.
    -   Upload Bitmap to OpenGL Texture.
    -   Support custom fonts, shadows, borders, background colors.
- [ ] **Stickers/Emojis**: 
    -   Static image overlays.
    -   Animated WebP/GIF support (requires frame extraction).

### Phase 6: Export & Performance
**Goal**: Production reliability.
- [ ] **Configuration**:
    -   User-defined export constraints (e.g., "1080p for Instagram", "4K for YouTube").
- [ ] **Smart Caching**:
    -   Don't re-decode frames if timeline paused (for Live Preview).
- [ ] **Error Handling**:
    -   Graceful skipping of corrupt frames.
    -   Detailed error reporting to JS layer.

## Technical Standards ("No Cut Corners")

1.  **Aspect Ratio**: ALWAYS respected. Never stretch unless explicitly asked.
2.  **Color Space**: Standardize on Rec.709 (SDR) for now. Convert HDR inputs to SDR to prevent washed-out colors.
3.  **Frame Accuracy**: Sync audio and video perfectly. Drop video frames if rendering is too slow (preview), but NEVER drop frames during export.
4.  **Memory**:
    -   Aggressively release `MediaCodec` buffers.
    -   Recycle Bitmaps immediately after texture upload.
    -   Monitor native heap size.
