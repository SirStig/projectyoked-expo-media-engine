import { useState, useRef } from 'react';
import { StyleSheet, Text, View, Button, ScrollView, Image, ActivityIndicator, Alert } from 'react-native';
import * as ImagePicker from 'expo-image-picker';
import { Asset } from 'expo-asset';
import MediaEngine from '@projectyoked/expo-media-engine';
import { useVideoPlayer, VideoView } from 'expo-video';

// Require test assets directly
const TEST_VIDEO = require('./assets/test/4438080-hd_1920_1080_25fps.mp4');

// Helper component for playing video
const SimpleVideoPlayer = ({ uri, style }) => {
  const player = useVideoPlayer(uri, player => {
    player.loop = true;
    player.play();
  });

  return (
    <View style={style}>
      <VideoView style={{ flex: 1 }} player={player} allowsFullscreen allowPictureInPicture />
    </View>
  );
};

export default function App() {
  const [videos, setVideos] = useState([]);
  const [outputUri, setOutputUri] = useState(null);
  const [loading, setLoading] = useState(false);
  const [status, setStatus] = useState('Ready');

  const pickVideo = async () => {
    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ImagePicker.MediaTypeOptions.Videos,
      allowsEditing: false,
      quality: 1,
    });

    if (!result.canceled) {
      const asset = result.assets[0];
      setVideos([...videos, {
        uri: asset.uri,
        fileName: asset.fileName || 'Selected Video',
        duration: asset.duration
      }]);
    }
  };

  const loadTestAssets = async () => {
    try {
      setLoading(true);
      setStatus('Downloading test asset...');

      const asset = Asset.fromModule(TEST_VIDEO);
      await asset.downloadAsync();

      if (!asset.localUri) {
        throw new Error("Asset failed to download or has no local URI");
      }

      console.log('Test asset loaded:', asset.localUri);

      // Add twice to simulate stitching
      setVideos([
        { uri: asset.localUri, fileName: 'Test Video 1', duration: 5000 },
        { uri: asset.localUri, fileName: 'Test Video 2', duration: 5000 }
      ]);
      setStatus('Test videos loaded!');
    } catch (e) {
      console.error('Failed to load test assets', e);
      setStatus('Error loading assets: ' + e.message);
      Alert.alert("Error", "Failed to load test assets: " + e.message);
    } finally {
      setLoading(false);
    }
  };

  // ... (composeVideos function above)

  // Require test assets directly
  const TEST_VIDEO_1 = require('./assets/test/Assisted_Squat.mov');
  const TEST_VIDEO_2 = require('./assets/test/Assisted_Lunge.mov');
  const TEST_IMAGE_1 = require('./assets/icon.png');

  const autoTestAllFeatures = async () => {
    try {
      setLoading(true);
      setStatus("Starting Automated Test Suite...");

      // 1. Get Test Assets
      const asset1 = Asset.fromModule(TEST_VIDEO_1);
      await asset1.downloadAsync();
      const asset2 = Asset.fromModule(TEST_VIDEO_2);
      await asset2.downloadAsync();
      const imageAsset = Asset.fromModule(TEST_IMAGE_1);
      await imageAsset.downloadAsync();

      if (!asset1.localUri || !asset2.localUri) throw new Error("Assets failed to load");

      // --- TEST 1: COMPLEX COMPOSITION (Transcode) ---
      setStatus("Test 1/3: Complex Composition (Transcode)...");
      const config1 = {
        outputUri: asset1.localUri.replace('.mov', '_autotest_complex.mp4'),
        width: 720, height: 1280, frameRate: 30, bitrate: 4000000,
        enablePassthrough: false, // Explicitly force transcode logic
        tracks: [
          {
            type: 'video',
            clips: [
              { uri: asset1.localUri, startTime: 0, duration: 3.0, filter: 'sepia', resizeMode: 'cover' },
              { uri: imageAsset.localUri, startTime: 3.0, duration: 2.0, resizeMode: 'contain', scale: 0.9 },
              { uri: asset2.localUri, startTime: 5.0, duration: 3.0, filter: 'grayscale', resizeMode: 'contain', rotation: 90 }
            ]
          },
          {
            type: 'text',
            clips: [
              { uri: 'text:Auto Test', startTime: 0.5, duration: 2.0, x: 0.5, y: 0.2, fontSize: 60, color: '#FFFF00' }
            ]
          }
        ]
      };
      const t1Start = Date.now();
      await MediaEngine.composeCompositeVideo(config1);
      const t1Dur = Date.now() - t1Start;
      console.log(`Test 1 Complete: ${t1Dur}ms`);

      // --- TEST 2: PASSTHROUGH ---
      setStatus("Test 2/3: Smart Passthrough...");
      const config2 = {
        outputUri: asset1.localUri.replace('.mov', '_autotest_pass.mp4'),
        enablePassthrough: true, // Enable check
        tracks: [{ type: 'video', clips: [{ uri: asset1.localUri, startTime: 0, duration: 5.0 }] }]
      };
      const t2Start = Date.now();
      await MediaEngine.composeCompositeVideo(config2);
      const t2Dur = Date.now() - t2Start;
      console.log(`Test 2 Complete: ${t2Dur}ms`);

      // --- TEST 3: STITCHING (Segmented Processing Validation) ---
      setStatus("Test 3/3: Validation (Generate Parts & Stitch)...");

      // 3a. Generate Part 1 (0-2s)
      const part1Config = {
        outputUri: asset1.localUri.replace('.mov', '_part1.mp4'),
        width: 720, height: 1280, frameRate: 30, bitrate: 2000000,
        tracks: [{ type: 'video', clips: [{ uri: asset1.localUri, startTime: 0, duration: 2.0, resizeMode: 'cover' }] }]
      };
      await MediaEngine.composeCompositeVideo(part1Config);

      // 3b. Generate Part 2 (2-4s)
      const part2Config = {
        outputUri: asset1.localUri.replace('.mov', '_part2.mp4'),
        width: 720, height: 1280, frameRate: 30, bitrate: 2000000,
        tracks: [{ type: 'video', clips: [{ uri: asset1.localUri, startTime: 2.0, duration: 2.0, resizeMode: 'cover' }] }]
      };
      await MediaEngine.composeCompositeVideo(part2Config);

      // 3c. Stitch Parts
      const stitchOut = asset1.localUri.replace('.mov', '_autotest_final.mp4');
      const t3Start = Date.now();
      await MediaEngine.stitchVideos([part1Config.outputUri, part2Config.outputUri], stitchOut);
      const t3Dur = Date.now() - t3Start;
      console.log(`Test 3 Complete: ${t3Dur}ms`);

      // Done
      setOutputUri(stitchOut); // Show stitched result
      const totalTime = t1Dur + t2Dur + t3Dur;
      setStatus(`✅ ALL TESTS PASSED! (${totalTime}ms)`);
      Alert.alert("Suite Passed",
        `1. Complex: ${t1Dur}ms\n2. Passthrough: ${t2Dur}ms\n3. Stitch: ${t3Dur}ms\n\nTotal: ${totalTime}ms`);

    } catch (e) {
      console.error("AutoTest Failed:", e);
      setStatus(`❌ Test Failed: ${e.message}`);
      Alert.alert("Test Failed", e.message);
    } finally {
      setLoading(false);
    }
  };

  // Re-adding composeVideos which was accidentally overwritten
  const composeVideos = async (filterType) => {
    if (videos.length === 0) {
      setStatus('Please select at least one video');
      return;
    }

    setLoading(true);
    setOutputUri(null); // Clear previous output
    setStatus('Composing...');
    try {
      // Create a track with consecutive clips
      let currentTime = 0;
      const clips = videos.map((v) => {
        // Default duration if missing (e.g. from picker sometimes)
        const durationSec = v.duration ? v.duration / 1000.0 : 5.0;

        const clip = {
          uri: v.uri,
          startTime: currentTime,
          duration: durationSec,
          filter: filterType,
          transition: null
        };
        currentTime += durationSec;
        return clip;
      });

      const tracks = [
        {
          type: 'video',
          clips: clips
        }
      ];

      // Add a sample text overlay if we have video
      if (clips.length > 0) {
        tracks.push({
          type: 'text',
          clips: [
            {
              uri: 'text:Hello World',
              startTime: 0,
              duration: 3.0,
              filter: null,
              transition: null,
              x: 0.5, y: 0.5, fontSize: 48, color: '#FFFFFF'
            }
          ]
        });
      }

      const config = {
        outputUri: videos[0].uri.replace('.mp4', '_composition.mp4'),
        width: 720,
        height: 1280,
        frameRate: 30,
        bitrate: 2000000,
        tracks: tracks
      };

      console.log('Starting composition with config:', JSON.stringify(config, null, 2));
      const result = await MediaEngine.composeCompositeVideo(config);
      console.log('Composition success:', result);
      setOutputUri(result);
      setStatus('Success!');
      Alert.alert("Success", "Video composed successfully!\n" + result);
    } catch (e) {
      console.error(e);
      setStatus('Error: ' + e.message);
      Alert.alert("Error", "Composition Failed: " + e.message);
    } finally {
      setLoading(false);
    }
  };

  // --- New Advanced Tests ---

  const testPassthrough = async () => {
    if (videos.length === 0) { Alert.alert("Error", "Load test assets first"); return; }
    try {
      setLoading(true);
      setStatus("Testing Smart Passthrough...");

      const config = {
        outputUri: videos[0].uri.replace('.mp4', '_passthrough.mp4').replace('.mov', '_passthrough.mp4'),
        width: 1280, height: 720, frameRate: 30,
        enablePassthrough: true,
        tracks: [{ type: 'video', clips: [{ uri: videos[0].uri, startTime: 0, duration: 5.0 }] }]
      };

      const start = Date.now();
      const result = await MediaEngine.composeCompositeVideo(config);
      const time = Date.now() - start;

      setStatus(`Passthrough Complete: ${time}ms`);
      setOutputUri(result);
      Alert.alert("Pass", `Passthrough Output generated in ${time}ms`);
    } catch (e) {
      Alert.alert("Error", e.message);
    } finally { setLoading(false); }
  };

  const testTranscode = async () => {
    if (videos.length === 0) { Alert.alert("Error", "Load test assets first"); return; }
    try {
      setLoading(true);
      setStatus("Testing Forced Transcode...");

      const config = {
        outputUri: videos[0].uri.replace('.mp4', '_transcode.mp4').replace('.mov', '_transcode.mp4'),
        width: 1280, height: 720, frameRate: 30,
        enablePassthrough: false, // FORCE TRANSCODE
        videoBitrate: 1000000, // Low bitrate test
        tracks: [{ type: 'video', clips: [{ uri: videos[0].uri, startTime: 0, duration: 5.0 }] }]
      };

      const start = Date.now();
      const result = await MediaEngine.composeCompositeVideo(config);
      const time = Date.now() - start;

      setStatus(`Transcode Complete: ${time}ms`);
      setOutputUri(result);
      Alert.alert("Pass", `Transcode Output generated in ${time}ms`);
    } catch (e) {
      Alert.alert("Error", e.message);
    } finally { setLoading(false); }
  };

  const testStitching = async () => {
    if (videos.length < 2) { Alert.alert("Error", "Need at least 2 videos"); return; }
    try {
      setLoading(true);
      setStatus("Testing Video Stitching...");

      // We will stitching video 1 and video 2 directly
      // In a real scenario, these would be the output of parallel composition
      const inputs = [videos[0].uri, videos[1].uri];
      const output = videos[0].uri.replace('.mp4', '_stitched.mp4').replace('.mov', '_stitched.mp4');

      const start = Date.now();
      const result = await MediaEngine.stitchVideos(inputs, output);
      const time = Date.now() - start;

      setStatus(`Stitch Complete: ${time}ms`);
      setOutputUri(result);
      Alert.alert("Pass", `Stitched 2 videos in ${time}ms`);
    } catch (e) {
      console.error(e);
      setStatus("Stitch Failed: " + e.message);
      Alert.alert("Error", e.message);
    } finally { setLoading(false); }
  };

  return (
    <View style={styles.container}>
      <Text style={styles.header}>Composite Media Engine Test</Text>

      <ScrollView style={styles.scroll}>
        <View style={styles.row}>
          <Button title="Pick Video" onPress={pickVideo} />
          <Button title="Load Test Data" onPress={loadTestAssets} color="orange" />
        </View>

        <View style={{ marginTop: 10 }}>
          <Button title="⚡ RUN FULL AUTO-TEST ⚡" onPress={autoTestAllFeatures} color="#6200ea" />
        </View>

        <View style={styles.list}>
          <Text style={styles.sectionHeader}>Input Videos:</Text>
          {videos.length === 0 ? (
            <Text style={styles.emptyText}>No videos selected.</Text>
          ) : (
            videos.map((v, i) => (
              <View key={i} style={styles.videoItem}>
                <Text style={styles.itemTitle}>{i + 1}. {v.fileName}</Text>
                <SimpleVideoPlayer uri={v.uri} style={styles.thumbnailVideo} />
                <Text style={styles.itemMeta}>{Math.round(v.duration / 1000)}s</Text>
              </View>
            ))
          )}
        </View>

        {videos.length > 0 && (
          <Button title="Clear Videos" onPress={() => setVideos([])} color="red" />
        )}

        <View style={styles.spacer} />

        <Button title="Info: Features" onPress={() => {
          Alert.alert("Features",
            "- Multi-clip concatenation\n" +
            "- OpenGL Rendering\n" +
            "- Text Overlays (Auto-added)\n" +
            "- Filters (Sepia, Grayscale)\n" +
            "\nComing Soon:\n- Transitions\n- AI Effects\n- Live Preview"
          );
        }} />
        <View style={styles.spacer} />

        <View style={styles.controls}>
          <Button
            title="Compose (Sepia)"
            onPress={() => composeVideos('sepia')}
            disabled={loading || videos.length === 0}
          />
          <View style={styles.spacerSmall} />
          <Button
            title="Compose (Grayscale)"
            onPress={() => composeVideos('grayscale')}
            disabled={loading || videos.length === 0}
          />
          <View style={styles.spacerSmall} />
          <Button
            title="Compose (Normal)"
            onPress={() => composeVideos(null)}
            disabled={loading || videos.length === 0}
          />
        </View>

        <Text style={styles.sectionHeader}>Advanced Tests (New Features)</Text>
        <View style={styles.controls}>
          <Button title="Test Passthrough (Fast)" onPress={testPassthrough} color="#2e7d32" />
          <View style={styles.spacerSmall} />
          <Button title="Test Transcode (Slow, 1mbps)" onPress={testTranscode} color="#c62828" />
          <View style={styles.spacerSmall} />
          <Button title="Test Stitching (Join V1+V2)" onPress={testStitching} color="#0277bd" />
        </View>

        {loading && <ActivityIndicator size="large" color="#0000ff" style={{ marginTop: 20 }} />}

        <Text style={styles.status}>{status}</Text>

        {outputUri && (
          <View style={styles.outputContainer}>
            <Text style={styles.success}>Output Generated:</Text>
            <SimpleVideoPlayer uri={outputUri} style={styles.outputVideo} />
            <Text style={styles.pathText}>{outputUri}</Text>
          </View>
        )}

        <View style={styles.spacer} />
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#fff',
    paddingTop: 50,
    paddingHorizontal: 20,
  },
  header: {
    fontSize: 20,
    fontWeight: 'bold',
    marginBottom: 20,
    textAlign: 'center',
  },
  scroll: {
    flex: 1,
  },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 10
  },
  list: {
    marginVertical: 10,
    padding: 10,
    backgroundColor: '#f0f0f0',
    borderRadius: 5
  },
  videoItem: {
    marginBottom: 15,
    backgroundColor: '#fff',
    padding: 10,
    borderRadius: 5
  },
  thumbnailVideo: {
    width: '100%',
    height: 150,
    backgroundColor: 'black',
    marginVertical: 5
  },
  itemTitle: {
    fontWeight: 'bold'
  },
  itemMeta: {
    fontSize: 12,
    color: '#666'
  },
  sectionHeader: {
    fontWeight: 'bold',
    marginBottom: 5,
    fontSize: 16
  },
  emptyText: {
    fontStyle: 'italic',
    color: '#888'
  },
  spacer: {
    height: 20,
  },
  spacerSmall: {
    height: 10
  },
  controls: {
    marginTop: 10
  },
  status: {
    marginTop: 20,
    textAlign: 'center',
    color: '#333',
    fontWeight: 'bold'
  },
  success: {
    marginTop: 10,
    textAlign: 'center',
    color: 'green',
    fontSize: 16,
    fontWeight: 'bold',
    marginBottom: 5
  },
  outputContainer: {
    marginTop: 20,
    alignItems: 'center'
  },
  outputVideo: {
    width: '100%',
    height: 300,
    backgroundColor: 'black'
  },
  pathText: {
    fontSize: 10,
    color: '#aaa',
    marginTop: 5
  }
});
