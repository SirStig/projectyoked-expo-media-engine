import { Asset } from 'expo-asset';
import * as FileSystem from 'expo-file-system/legacy';
import sha256 from 'js-sha256';

import manifest from './fixtures.manifest.json';
import { ensureDir } from './assertions.js';

const FIXTURES_DIR = `${FileSystem.documentDirectory}media-engine-fixtures`;

/**
 * SHA-256 hex of a local file (via fetch + arrayBuffer).
 * @param {string} fileUri
 * @returns {Promise<string>}
 */
async function sha256HexOfLocalFile(fileUri) {
  const res = await fetch(fileUri);
  if (!res.ok) {
    throw new Error(`Cannot read file for hash: ${fileUri} (${res.status})`);
  }
  const ab = await res.arrayBuffer();
  const digestBuf = sha256.arrayBuffer(ab);
  const bytes = new Uint8Array(digestBuf);
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

/**
 * @param {{ id: string, filename: string, url: string, sha256: string }} entry
 * @param {(msg: string) => void} [onProgress]
 * @returns {Promise<string>} file URI (with file://)
 */
async function ensureRemoteFixture(entry, onProgress) {
  await ensureDir(FIXTURES_DIR);
  const destUri = `${FIXTURES_DIR.replace(/\/?$/, '/')}${entry.filename}`;

  const info = await FileSystem.getInfoAsync(destUri);
  if (info.exists && typeof info.size === 'number' && info.size > 0) {
    onProgress?.(`Verifying ${entry.id}…`);
    try {
      const hex = await sha256HexOfLocalFile(destUri);
      if (hex.toLowerCase() === entry.sha256.toLowerCase()) {
        return destUri;
      }
    } catch {
      // re-download
    }
    await FileSystem.deleteAsync(destUri, { idempotent: true });
  }

  onProgress?.(`Downloading ${entry.id}…`);
  const result = await FileSystem.downloadAsync(entry.url, destUri);
  if (result.status !== 200) {
    throw new Error(`Download ${entry.id} failed: HTTP ${result.status}`);
  }

  onProgress?.(`Verifying ${entry.id}…`);
  const verifyUri = result.uri || destUri;
  const hex = await sha256HexOfLocalFile(verifyUri);
  if (hex.toLowerCase() !== entry.sha256.toLowerCase()) {
    await FileSystem.deleteAsync(destUri, { idempotent: true });
    throw new Error(
      `Checksum mismatch for ${entry.id}: expected ${entry.sha256}, got ${hex}`
    );
  }

  return verifyUri;
}

/**
 * Copy a bundled asset into the fixtures directory (stable path for tests).
 * @param {number} moduleId require() asset id
 * @param {string} destName
 * @param {(msg: string) => void} [onProgress]
 * @returns {Promise<string>} file URI
 */
async function copyBundledAsset(moduleId, destName, onProgress) {
  await ensureDir(FIXTURES_DIR);
  const destUri = `${FIXTURES_DIR.replace(/\/?$/, '/')}${destName}`;
  onProgress?.(`Preparing bundled ${destName}…`);
  const asset = Asset.fromModule(moduleId);
  await asset.downloadAsync();
  if (!asset.localUri) {
    throw new Error(`Bundled asset failed: ${destName}`);
  }
  const info = await FileSystem.getInfoAsync(destUri);
  if (info.exists) {
    await FileSystem.deleteAsync(destUri, { idempotent: true });
  }
  await FileSystem.copyAsync({ from: asset.localUri, to: destUri });
  return destUri;
}

/**
 * Load all manifest fixtures + bundled overlay image.
 * @param {{ onProgress?: (message: string) => void }} [options]
 * @returns {Promise<{ videoA: string, videoB: string, image: string }>}
 */
export async function loadFixtures(options = {}) {
  const { onProgress } = options;
  const entries = manifest.entries || [];
  const out = {};

  for (const entry of entries) {
    const uri = await ensureRemoteFixture(entry, onProgress);
    out[entry.id] = uri;
  }

  const imageModule = require('../assets/icon.png');
  out.image = await copyBundledAsset(imageModule, 'integration_overlay.png', onProgress);

  if (!out.videoA || !out.videoB || !out.image) {
    throw new Error('loadFixtures: missing videoA, videoB, or image');
  }

  return {
    videoA: out.videoA,
    videoB: out.videoB,
    image: out.image,
  };
}

export { manifest, FIXTURES_DIR };
