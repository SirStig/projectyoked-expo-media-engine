import * as FileSystem from 'expo-file-system/legacy';

import { assertOutputFile, ensureDir } from './assertions.js';

const CLIP_SEC = 2.5;

/**
 * @param {string} dir
 * @param {string} name
 */
function joinUri(dir, name) {
  const base = dir.endsWith('/') ? dir : `${dir}/`;
  return `${base}${name}`;
}

/**
 * @param {import('react-native').PlatformOSType} os
 * @param {{ platforms?: 'all' | 'ios' | 'android', optional?: boolean }[]} tests
 */
export function filterTestsByPlatform(tests, os) {
  return tests.filter((t) => {
    const p = t.platforms ?? 'all';
    if (p === 'all') return true;
    return p === os;
  });
}

/**
 * @returns {Promise<string>}
 */
export async function prepareOutputDir() {
  const root = `${FileSystem.documentDirectory}media-engine-outputs`;
  await ensureDir(root);
  return root.endsWith('/') ? root : `${root}/`;
}

/**
 * @typedef {object} SuiteContext
 * @property {string} videoA
 * @property {string} videoB
 * @property {string} image
 * @property {string} outputDir
 * @property {string | null} extractedM4a
 */

/**
 * @param {typeof import('@projectyoked/expo-media-engine').default} MediaEngine
 * @param {SuiteContext} ctx
 */
function baseCompose(MediaEngine, ctx, id, partial) {
  const outputUri = joinUri(ctx.outputDir, `${id}.mp4`);
  return MediaEngine.composeCompositeVideo({
    outputUri,
    width: 640,
    height: 360,
    frameRate: 30,
    bitrate: 1_500_000,
    ...partial,
  });
}

const FILTER_TYPES = [
  'grayscale',
  'sepia',
  'vignette',
  'invert',
  'brightness',
  'contrast',
  'saturation',
  'warm',
  'cool',
];

const TRANSITION_CASES = [
  { id: 'crossfade', transition: 'crossfade' },
  { id: 'fade', transition: 'fade' },
  { id: 'slide_left', transition: 'slide-left' },
];

const STATIC_TESTS = [
  {
    id: 'fixtures_on_disk',
    name: 'Fixtures: remote videos + bundled image',
    platforms: 'all',
    run: async (_MediaEngine, ctx) => {
      await assertOutputFile(ctx.videoA, 500_000);
      await assertOutputFile(ctx.videoB, 500_000);
      await assertOutputFile(ctx.image, 100);
      return ctx.videoA;
    },
  },
  {
    id: 'extract_audio',
    name: 'extractAudio',
    platforms: 'all',
    run: async (MediaEngine, ctx) => {
      const out = joinUri(ctx.outputDir, 'extracted.m4a');
      await FileSystem.deleteAsync(out, { idempotent: true });
      const r = await MediaEngine.extractAudio(ctx.videoA, out);
      ctx.extractedM4a = r;
      await assertOutputFile(r, 500);
      return r;
    },
  },
  {
    id: 'get_waveform',
    name: 'getWaveform',
    platforms: 'all',
    run: async (MediaEngine, ctx) => {
      if (!ctx.extractedM4a) {
        throw new Error('getWaveform requires extractAudio first');
      }
      const w = await MediaEngine.getWaveform(ctx.extractedM4a, 64);
      if (!Array.isArray(w) || w.length !== 64) {
        throw new Error(`getWaveform expected 64 samples, got ${w?.length}`);
      }
      return ctx.extractedM4a;
    },
  },
  {
    id: 'export_composition_basic',
    name: 'exportComposition (basic)',
    platforms: 'all',
    run: async (MediaEngine, ctx) => {
      const outputPath = joinUri(ctx.outputDir, 'legacy_basic.mp4');
      await FileSystem.deleteAsync(outputPath, { idempotent: true });
      const r = await MediaEngine.exportComposition({
        videoPath: ctx.videoA,
        outputPath,
        duration: CLIP_SEC,
        quality: 'low',
      });
      await assertOutputFile(r, 10_000);
      return r;
    },
  },
  {
    id: 'export_composition_text_emoji_music',
    name: 'exportComposition (text + emoji + music)',
    platforms: 'all',
    run: async (MediaEngine, ctx) => {
      if (!ctx.extractedM4a) throw new Error('needs extracted m4a');
      const outputPath = joinUri(ctx.outputDir, 'legacy_full.mp4');
      await FileSystem.deleteAsync(outputPath, { idempotent: true });
      const r = await MediaEngine.exportComposition({
        videoPath: ctx.videoA,
        outputPath,
        duration: CLIP_SEC,
        quality: 'medium',
        textArray: ['Hi'],
        textX: [0.5],
        textY: [0.4],
        textColors: ['#00FF00'],
        textSizes: [28],
        textStarts: [0.2],
        textDurations: [1.5],
        emojiArray: ['🎬'],
        emojiX: [0.5],
        emojiY: [0.6],
        emojiSizes: [40],
        emojiStarts: [0.3],
        emojiDurations: [1.0],
        musicPath: ctx.extractedM4a,
        musicVolume: 0.15,
        originalVolume: 0.85,
      });
      await assertOutputFile(r, 10_000);
      return r;
    },
  },
  {
    id: 'compose_passthrough',
    name: 'composeCompositeVideo passthrough',
    platforms: 'all',
    run: async (MediaEngine, ctx) => {
      const r = await baseCompose(MediaEngine, ctx, 'pass', {
        enablePassthrough: true,
        tracks: [
          {
            type: 'video',
            clips: [{ uri: ctx.videoA, startTime: 0, duration: CLIP_SEC }],
          },
        ],
      });
      await assertOutputFile(r, 5000);
      return r;
    },
  },
  {
    id: 'compose_transcode',
    name: 'composeCompositeVideo transcode',
    platforms: 'all',
    run: async (MediaEngine, ctx) => {
      const r = await baseCompose(MediaEngine, ctx, 'trans', {
        enablePassthrough: false,
        tracks: [
          {
            type: 'video',
            clips: [{ uri: ctx.videoA, startTime: 0, duration: CLIP_SEC }],
          },
        ],
      });
      await assertOutputFile(r, 5000);
      return r;
    },
  },
  {
    id: 'compose_video_profile_high',
    name: 'composeCompositeVideo videoProfile high',
    platforms: 'all',
    run: async (MediaEngine, ctx) => {
      const r = await baseCompose(MediaEngine, ctx, 'vprof', {
        enablePassthrough: false,
        videoProfile: 'high',
        tracks: [
          {
            type: 'video',
            clips: [
              {
                uri: ctx.videoA,
                startTime: 0,
                duration: CLIP_SEC,
                filter: 'contrast',
                filterIntensity: 0.9,
              },
            ],
          },
        ],
      });
      await assertOutputFile(r, 5000);
      return r;
    },
  },
  {
    id: 'compose_resize_stretch',
    name: 'compose resizeMode stretch',
    platforms: 'all',
    run: async (MediaEngine, ctx) => {
      const r = await baseCompose(MediaEngine, ctx, 'stretch', {
        tracks: [
          {
            type: 'video',
            clips: [
              {
                uri: ctx.videoA,
                startTime: 0,
                duration: CLIP_SEC,
                resizeMode: 'stretch',
              },
            ],
          },
        ],
      });
      await assertOutputFile(r, 5000);
      return r;
    },
  },
  {
    id: 'compose_transforms',
    name: 'compose rotation + scale + opacity',
    platforms: 'all',
    run: async (MediaEngine, ctx) => {
      const r = await baseCompose(MediaEngine, ctx, 'xform', {
        tracks: [
          {
            type: 'video',
            clips: [
              {
                uri: ctx.videoA,
                startTime: 0,
                duration: CLIP_SEC,
                rotation: 90,
                scale: 0.85,
                opacity: 0.95,
              },
            ],
          },
        ],
      });
      await assertOutputFile(r, 5000);
      return r;
    },
  },
  {
    id: 'compose_text_track_style',
    name: 'compose text track + textStyle',
    platforms: 'all',
    run: async (MediaEngine, ctx) => {
      const r = await baseCompose(MediaEngine, ctx, 'textstyle', {
        tracks: [
          {
            type: 'video',
            clips: [{ uri: ctx.videoA, startTime: 0, duration: CLIP_SEC }],
          },
          {
            type: 'text',
            clips: [
              {
                uri: 'text:styled',
                text: 'Styled',
                startTime: 0.3,
                duration: 1.5,
                x: 0.5,
                y: 0.45,
                textStyle: {
                  color: '#FFEE00',
                  fontSize: 36,
                  fontWeight: 'bold',
                  shadowColor: '#000000',
                  shadowRadius: 3,
                },
              },
            ],
          },
        ],
      });
      await assertOutputFile(r, 5000);
      return r;
    },
  },
  {
    id: 'compose_image_track',
    name: 'compose image track',
    platforms: 'all',
    run: async (MediaEngine, ctx) => {
      const r = await baseCompose(MediaEngine, ctx, 'imgtrack', {
        tracks: [
          {
            type: 'video',
            clips: [{ uri: ctx.videoA, startTime: 0, duration: CLIP_SEC }],
          },
          {
            type: 'image',
            clips: [
              {
                uri: ctx.image,
                startTime: 0.4,
                duration: 1.2,
                x: 0.75,
                y: 0.25,
                scale: 0.35,
              },
            ],
          },
        ],
      });
      await assertOutputFile(r, 5000);
      return r;
    },
  },
  {
    id: 'compose_pip_video',
    name: 'compose PIP (second video clip)',
    platforms: 'all',
    run: async (MediaEngine, ctx) => {
      const r = await baseCompose(MediaEngine, ctx, 'pip', {
        tracks: [
          {
            type: 'video',
            clips: [{ uri: ctx.videoA, startTime: 0, duration: CLIP_SEC }],
          },
          {
            type: 'video',
            clips: [
              {
                uri: ctx.image,
                startTime: 0.5,
                duration: 1.0,
                x: 0.85,
                y: 0.2,
                scale: 0.25,
              },
            ],
          },
        ],
      });
      await assertOutputFile(r, 5000);
      return r;
    },
  },
  {
    id: 'compose_audio_track',
    name: 'compose audio track',
    platforms: 'all',
    run: async (MediaEngine, ctx) => {
      if (!ctx.extractedM4a) throw new Error('needs extracted m4a');
      const r = await baseCompose(MediaEngine, ctx, 'audiotrk', {
        tracks: [
          {
            type: 'video',
            clips: [{ uri: ctx.videoA, startTime: 0, duration: CLIP_SEC }],
          },
          {
            type: 'audio',
            clips: [
              {
                uri: ctx.extractedM4a,
                startTime: 0,
                duration: CLIP_SEC,
                volume: 0.35,
              },
            ],
          },
        ],
      });
      await assertOutputFile(r, 5000);
      return r;
    },
  },
  {
    id: 'compose_fades_flat',
    name: 'compose clip fadeIn / fadeOut (flat fields)',
    platforms: 'all',
    run: async (MediaEngine, ctx) => {
      const r = await baseCompose(MediaEngine, ctx, 'fades', {
        tracks: [
          {
            type: 'video',
            clips: [{ uri: ctx.videoA, startTime: 0, duration: CLIP_SEC }],
          },
          {
            type: 'audio',
            clips: [
              {
                uri: ctx.extractedM4a,
                startTime: 0,
                duration: CLIP_SEC,
                volume: 1,
                fadeInDuration: 0.4,
                fadeOutDuration: 0.4,
              },
            ],
          },
        ],
      });
      await assertOutputFile(r, 5000);
      return r;
    },
  },
  {
    id: 'compose_volume_keyframes',
    name: 'compose volumeEnvelope keyframes',
    platforms: 'all',
    run: async (MediaEngine, ctx) => {
      if (!ctx.extractedM4a) throw new Error('needs extracted m4a');
      const r = await baseCompose(MediaEngine, ctx, 'volkf', {
        tracks: [
          {
            type: 'video',
            clips: [{ uri: ctx.videoA, startTime: 0, duration: CLIP_SEC }],
          },
          {
            type: 'audio',
            clips: [
              {
                uri: ctx.extractedM4a,
                startTime: 0,
                duration: CLIP_SEC,
                volume: 1,
                volumeEnvelope: {
                  keyframes: [
                    { time: 0, volume: 0.1 },
                    { time: 0.5, volume: 1 },
                    { time: CLIP_SEC, volume: 0.2 },
                  ],
                },
              },
            ],
          },
        ],
      });
      await assertOutputFile(r, 5000);
      return r;
    },
  },
  {
    id: 'compose_speed',
    name: 'compose clip speed 1.5x',
    platforms: 'all',
    run: async (MediaEngine, ctx) => {
      const r = await baseCompose(MediaEngine, ctx, 'speed', {
        tracks: [
          {
            type: 'video',
            clips: [
              {
                uri: ctx.videoA,
                startTime: 0,
                duration: CLIP_SEC,
                speed: 1.5,
              },
            ],
          },
        ],
      });
      await assertOutputFile(r, 5000);
      return r;
    },
  },
  {
    id: 'compose_trim',
    name: 'compose clipStart / clipEnd trim',
    platforms: 'all',
    run: async (MediaEngine, ctx) => {
      const r = await baseCompose(MediaEngine, ctx, 'trim', {
        tracks: [
          {
            type: 'video',
            clips: [
              {
                uri: ctx.videoA,
                startTime: 0,
                duration: 1.8,
                clipStart: 0.25,
                clipEnd: 2.5,
              },
            ],
          },
        ],
      });
      await assertOutputFile(r, 5000);
      return r;
    },
  },
  {
    id: 'compose_animations',
    name: 'compose clip animations keyframes',
    platforms: 'all',
    run: async (MediaEngine, ctx) => {
      const r = await baseCompose(MediaEngine, ctx, 'anim', {
        tracks: [
          {
            type: 'video',
            clips: [
              {
                uri: ctx.videoA,
                startTime: 0,
                duration: CLIP_SEC,
                animations: {
                  x: [
                    { time: 0, value: 0.45 },
                    { time: CLIP_SEC, value: 0.55 },
                  ],
                  opacity: [
                    { time: 0, value: 0.7 },
                    { time: CLIP_SEC, value: 1 },
                  ],
                },
              },
            ],
          },
        ],
      });
      await assertOutputFile(r, 5000);
      return r;
    },
  },
  {
    id: 'stitch_videos',
    name: 'stitchVideos',
    platforms: 'all',
    run: async (MediaEngine, ctx) => {
      const out = joinUri(ctx.outputDir, 'stitched.mp4');
      await FileSystem.deleteAsync(out, { idempotent: true });
      const r = await MediaEngine.stitchVideos(
        [ctx.videoA, ctx.videoB],
        out
      );
      await assertOutputFile(r, 500_000);
      return r;
    },
  },
  {
    id: 'compress_quality',
    name: 'compressVideo quality low',
    platforms: 'all',
    run: async (MediaEngine, ctx) => {
      const out = joinUri(ctx.outputDir, 'compressed_low.mp4');
      await FileSystem.deleteAsync(out, { idempotent: true });
      const r = await MediaEngine.compressVideo({
        inputUri: ctx.videoA,
        outputUri: out,
        quality: 'low',
      });
      await assertOutputFile(r, 5000);
      return r;
    },
  },
  {
    id: 'compress_bitrate_dims',
    name: 'compressVideo bitrate + max dimensions',
    platforms: 'all',
    run: async (MediaEngine, ctx) => {
      const out = joinUri(ctx.outputDir, 'compressed_bd.mp4');
      await FileSystem.deleteAsync(out, { idempotent: true });
      const r = await MediaEngine.compressVideo({
        inputUri: ctx.videoA,
        outputUri: out,
        bitrate: 800_000,
        maxWidth: 480,
        maxHeight: 270,
        frameRate: 24,
        audioBitrate: 96_000,
      });
      await assertOutputFile(r, 5000);
      return r;
    },
  },
  {
    id: 'compress_h264',
    name: 'compressVideo codec h264',
    platforms: 'all',
    run: async (MediaEngine, ctx) => {
      const out = joinUri(ctx.outputDir, 'compressed_h264.mp4');
      await FileSystem.deleteAsync(out, { idempotent: true });
      const r = await MediaEngine.compressVideo({
        inputUri: ctx.videoA,
        outputUri: out,
        codec: 'h264',
        quality: 'medium',
      });
      await assertOutputFile(r, 5000);
      return r;
    },
  },
  {
    id: 'compress_h265',
    name: 'compressVideo codec h265 (optional)',
    platforms: 'all',
    optional: true,
    run: async (MediaEngine, ctx) => {
      const out = joinUri(ctx.outputDir, 'compressed_h265.mp4');
      await FileSystem.deleteAsync(out, { idempotent: true });
      const r = await MediaEngine.compressVideo({
        inputUri: ctx.videoA,
        outputUri: out,
        codec: 'h265',
        quality: 'low',
      });
      await assertOutputFile(r, 3000);
      return r;
    },
  },
];

const FILTER_TESTS = FILTER_TYPES.map((filter) => ({
  id: `filter_${filter}`,
  name: `compose filter: ${filter}`,
  platforms: 'all',
  run: async (MediaEngine, ctx) => {
    const r = await baseCompose(MediaEngine, ctx, `flt_${filter}`, {
      tracks: [
        {
          type: 'video',
          clips: [
            {
              uri: ctx.videoA,
              startTime: 0,
              duration: CLIP_SEC,
              filter,
              filterIntensity: filter === 'invert' ? 1 : 0.85,
            },
          ],
        },
      ],
    });
    await assertOutputFile(r, 5000);
    return r;
  },
}));

const TRANSITION_TESTS = TRANSITION_CASES.map(({ id, transition }) => ({
  id: `trans_${id}`,
  name: `compose transition: ${transition}`,
  platforms: 'all',
  run: async (MediaEngine, ctx) => {
    const overlap = 0.45;
    const r = await baseCompose(MediaEngine, ctx, `tr_${id}`, {
      tracks: [
        {
          type: 'video',
          clips: [
            {
              uri: ctx.videoA,
              startTime: 0,
              duration: CLIP_SEC,
              transition,
              transitionDuration: overlap,
            },
            {
              uri: ctx.videoB,
              startTime: CLIP_SEC - overlap,
              duration: CLIP_SEC,
            },
          ],
        },
      ],
    });
    await assertOutputFile(r, 10_000);
    return r;
  },
}));

const RESIZE_TESTS = [
  {
    id: 'resize_contain',
    name: 'compose resizeMode contain',
    run: async (MediaEngine, ctx) => {
      const r = await baseCompose(MediaEngine, ctx, 'contain', {
        tracks: [
          {
            type: 'video',
            clips: [
              {
                uri: ctx.videoA,
                startTime: 0,
                duration: CLIP_SEC,
                resizeMode: 'contain',
              },
            ],
          },
        ],
      });
      await assertOutputFile(r, 5000);
      return r;
    },
  },
  {
    id: 'resize_cover',
    name: 'compose resizeMode cover',
    run: async (MediaEngine, ctx) => {
      const r = await baseCompose(MediaEngine, ctx, 'cover', {
        tracks: [
          {
            type: 'video',
            clips: [
              {
                uri: ctx.videoA,
                startTime: 0,
                duration: CLIP_SEC,
                resizeMode: 'cover',
              },
            ],
          },
        ],
      });
      await assertOutputFile(r, 5000);
      return r;
    },
  },
].map((t) => ({ ...t, platforms: 'all' }));

export const integrationTests = [
  ...STATIC_TESTS,
  ...FILTER_TESTS,
  ...RESIZE_TESTS,
  ...TRANSITION_TESTS,
];

</think>
Fixing suite composition: removing the erroneous slice and merging tests in the correct order.

<｜tool▁calls▁begin｜><｜tool▁call▁begin｜>
StrReplace