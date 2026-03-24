import * as FileSystem from 'expo-file-system/legacy';

const DEFAULT_MIN_BYTES = 512;

/**
 * @param {string} uri
 * @param {number} [minBytes]
 * @returns {Promise<void>}
 */
export async function assertOutputFile(uri, minBytes = DEFAULT_MIN_BYTES) {
  const path = uri.startsWith('file://') ? uri.slice(7) : uri;
  const info = await FileSystem.getInfoAsync(path);
  if (!info.exists) {
    throw new Error(`Expected output file missing: ${uri}`);
  }
  if (typeof info.size === 'number' && info.size < minBytes) {
    throw new Error(`Expected output at least ${minBytes} bytes, got ${info.size}: ${uri}`);
  }
}

/**
 * @param {string} dirUri
 * @returns {Promise<string>} file URI with trailing slash removed for consistency
 */
export async function ensureDir(dirUri) {
  const info = await FileSystem.getInfoAsync(dirUri);
  if (!info.exists) {
    await FileSystem.makeDirectoryAsync(dirUri, { intermediates: true });
  }
  const u = dirUri.endsWith('/') ? dirUri : `${dirUri}/`;
  return u;
}
