import { useState, useEffect } from 'react';
import { StyleSheet, Text, View, Button, FlatList, ActivityIndicator, Alert } from 'react-native';
import { Asset } from 'expo-asset';
import * as FileSystem from 'expo-file-system/legacy';
import MediaEngine from '@projectyoked/expo-media-engine';
import { useVideoPlayer, VideoView } from 'expo-video';

// --- Test Assets ---
const TEST_VIDEO_1 = require('./assets/test/Assisted_Squat.mov');
const TEST_VIDEO_2 = require('./assets/test/Assisted_Lunge.mov');
const TEST_IMAGE = require('./assets/icon.png');

const SimpleVideoPlayer = ({ uri, style }) => {
  const player = useVideoPlayer(uri, player => {
    player.loop = true;
    player.play();
  });

  return (
    <View style={style}>
      <VideoView style={{ flex: 1 }} player={player} fullscreenOptions={{ allowed: true }} allowPictureInPicture />
    </View>
  );
};

export default function App() {
  const [ready, setReady] = useState(false);
  const [running, setRunning] = useState(false);
  const [results, setResults] = useState([]);
  const [assets, setAssets] = useState({ video1: null, video2: null, image: null });

  // Load Assets on Mount
  useEffect(() => {
    loadAssets();
  }, []);

  const loadAssets = async () => {
    try {

      const setupFile = async (resource, name) => {
        const asset = Asset.fromModule(resource);
        await asset.downloadAsync();
        if (!asset.localUri) throw new Error("Asset failed to download");

        const dest = FileSystem.documentDirectory + name;
        const info = await FileSystem.getInfoAsync(dest);
        if (!info.exists) {
          await FileSystem.copyAsync({ from: asset.localUri, to: dest });
        } else {
          // Re-copy to ensure fresh
          await FileSystem.deleteAsync(dest);
          await FileSystem.copyAsync({ from: asset.localUri, to: dest });
        }

        // Verify
        const check = await FileSystem.getInfoAsync(dest);
        console.log(`Asset ${name} prepared at ${check.uri} (Exists: ${check.exists}, Size: ${check.size})`);
        return check.uri;
      };

      const v1Uri = await setupFile(TEST_VIDEO_1, 'video1.mov');
      const v2Uri = await setupFile(TEST_VIDEO_2, 'video2.mov');
      const imgUri = await setupFile(TEST_IMAGE, 'image.png');

      setAssets({ video1: v1Uri, video2: v2Uri, image: imgUri });
      setReady(true);
    } catch (e) {
      console.error(e);
      Alert.alert("Asset Error", "Failed to load test assets: " + e.message);
    }
  };

  const TESTS = [
    {
      id: 'passthrough',
      name: '1. Smart Passthrough (Fast)',
      run: async (assets) => {
        const config = {
          outputUri: assets.video1.replace('.mov', '_pass.mp4').replace('.mp4', '_pass.mp4'),
          width: 720, height: 1280, frameRate: 30, bitrate: 2000000,
          enablePassthrough: true,
          tracks: [{ type: 'video', clips: [{ uri: assets.video1, startTime: 0, duration: 3.0 }] }]
        };
        return await MediaEngine.composeCompositeVideo(config);
      }
    },
    {
      id: 'transcode',
      name: '2. Force Transcode (Base)',
      run: async (assets) => {
        const config = {
          outputUri: assets.video1.replace('.mov', '_trans.mp4').replace('.mp4', '_trans.mp4'),
          width: 720, height: 1280, frameRate: 30, bitrate: 1500000,
          enablePassthrough: false,
          tracks: [{ type: 'video', clips: [{ uri: assets.video1, startTime: 0, duration: 3.0 }] }]
        };
        return await MediaEngine.composeCompositeVideo(config);
      }
    },
    {
      id: 'filter_sepia',
      name: '3. Filter: Sepia',
      run: async (assets) => {
        const config = {
          outputUri: assets.video1.replace('.mov', '_sepia.mp4').replace('.mp4', '_sepia.mp4'),
          width: 720, height: 1280, frameRate: 30,
          tracks: [{ type: 'video', clips: [{ uri: assets.video1, startTime: 0, duration: 3.0, filter: 'sepia' }] }]
        };
        return await MediaEngine.composeCompositeVideo(config);
      }
    },
    {
      id: 'filter_gray',
      name: '4. Filter: Grayscale',
      run: async (assets) => {
        const config = {
          outputUri: assets.video1.replace('.mov', '_gray.mp4').replace('.mp4', '_gray.mp4'),
          width: 720, height: 1280, frameRate: 30,
          tracks: [{ type: 'video', clips: [{ uri: assets.video1, startTime: 0, duration: 3.0, filter: 'grayscale' }] }]
        };
        return await MediaEngine.composeCompositeVideo(config);
      }
    },
    {
      id: 'resize_contain',
      name: '5. Resize: Contain (Fit)',
      run: async (assets) => {
        const config = {
          outputUri: assets.video1.replace('.mov', '_contain.mp4').replace('.mp4', '_contain.mp4'),
          width: 720, height: 720, frameRate: 30,
          tracks: [{ type: 'video', clips: [{ uri: assets.video1, startTime: 0, duration: 3.0, resizeMode: 'contain' }] }]
        };
        return await MediaEngine.composeCompositeVideo(config);
      }
    },
    {
      id: 'resize_cover',
      name: '6. Resize: Cover (Fill)',
      run: async (assets) => {
        const config = {
          outputUri: assets.video1.replace('.mov', '_cover.mp4').replace('.mp4', '_cover.mp4'),
          width: 720, height: 720, frameRate: 30,
          tracks: [{ type: 'video', clips: [{ uri: assets.video1, startTime: 0, duration: 3.0, resizeMode: 'cover' }] }]
        };
        return await MediaEngine.composeCompositeVideo(config);
      }
    },
    {
      id: 'transform_rotate',
      name: '7. Transform: Rotate 90',
      run: async (assets) => {
        const config = {
          outputUri: assets.video1.replace('.mov', '_rot90.mp4').replace('.mp4', '_rot90.mp4'),
          width: 720, height: 1280, frameRate: 30,
          tracks: [{ type: 'video', clips: [{ uri: assets.video1, startTime: 0, duration: 3.0, rotation: 90 }] }]
        };
        return await MediaEngine.composeCompositeVideo(config);
      }
    },
    {
      id: 'transform_scale',
      name: '8. Transform: Scale 0.5',
      run: async (assets) => {
        const config = {
          outputUri: assets.video1.replace('.mov', '_scale.mp4').replace('.mp4', '_scale.mp4'),
          width: 720, height: 1280, frameRate: 30,
          tracks: [{ type: 'video', clips: [{ uri: assets.video1, startTime: 0, duration: 3.0, scale: 0.5 }] }]
        };
        return await MediaEngine.composeCompositeVideo(config);
      }
    },
    {
      id: 'overlay_text',
      name: '9. Overlay: Text',
      run: async (assets) => {
        const config = {
          outputUri: assets.video1.replace('.mov', '_text.mp4').replace('.mp4', '_text.mp4'),
          width: 720, height: 1280,
          tracks: [
            { type: 'video', clips: [{ uri: assets.video1, startTime: 0, duration: 3.0 }] },
            { type: 'text', clips: [{ uri: 'text:HELLO|#FF0000|80', startTime: 0.5, duration: 2.0, x: 0.5, y: 0.5 }] }
          ]
        };
        return await MediaEngine.composeCompositeVideo(config);
      }
    },
    {
      id: 'overlay_image',
      name: '10. Overlay: Image (PIP)',
      run: async (assets) => {
        const config = {
          outputUri: assets.video1.replace('.mov', '_img.mp4').replace('.mp4', '_img.mp4'),
          width: 720, height: 1280,
          tracks: [
            { type: 'video', clips: [{ uri: assets.video1, startTime: 0, duration: 3.0 }] },
            { type: 'video', clips: [{ uri: assets.image, startTime: 0.5, duration: 2.0, x: 0.8, y: 0.2, scale: 0.3 }] }
          ]
        };
        return await MediaEngine.composeCompositeVideo(config);
      }
    },
    {
      id: 'stitching',
      name: '11. Stitching (V1 + V2)',
      run: async (assets) => {
        const output = assets.video1.replace('.mov', '_stitched.mp4').replace('.mp4', '_stitched.mp4');
        const outputUri = await MediaEngine.stitchVideos([assets.video1, assets.video2], output);
        return outputUri;
      }
    }
  ];

  const runSuite = async () => {
    if (!ready || running) return;
    setRunning(true);

    // Initialize results
    const initialResults = TESTS.map(t => ({
      id: t.id,
      name: t.name,
      status: 'pending',
      outputUri: null,
      error: null,
      duration: 0
    }));
    setResults(initialResults);

    // Run sequentially
    for (let i = 0; i < TESTS.length; i++) {
      const test = TESTS[i];

      // Update status to running
      setResults(prev => prev.map((r, idx) => idx === i ? { ...r, status: 'running' } : r));

      const start = Date.now();
      try {
        const outputUri = await test.run(assets);
        const duration = Date.now() - start;

        // Success
        setResults(prev => prev.map((r, idx) => idx === i ? { ...r, status: 'success', outputUri, duration } : r));
      } catch (e) {
        const duration = Date.now() - start;
        console.error(`Test ${test.name} failed`, e);
        // Error
        setResults(prev => prev.map((r, idx) => idx === i ? { ...r, status: 'error', error: e.message, duration } : r));
      }

      // Small delay for UI
      await new Promise(r => setTimeout(r, 500));
    }
    setRunning(false);
    Alert.alert("Suite Complete", "All tests finished.");
  };

  const renderItem = ({ item }) => (
    <View style={styles.card}>
      <View style={styles.headerRow}>
        <Text style={[styles.testName, item.status === 'success' && styles.green, item.status === 'error' && styles.red]}>
          {item.name}
        </Text>
        {item.status === 'running' && <ActivityIndicator size="small" />}
        {item.status !== 'pending' && item.status !== 'running' && (
          <Text style={styles.duration}>{item.duration}ms</Text>
        )}
      </View>

      {item.status === 'error' && (
        <Text style={styles.errorText}>❌ {item.error}</Text>
      )}

      {item.status === 'success' && item.outputUri && (
        <View style={styles.videoContainer}>
          <SimpleVideoPlayer uri={item.outputUri} style={styles.video} />
          <Text style={styles.path}>{item.outputUri}</Text>
        </View>
      )}
    </View>
  );

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Media Engine Test Suite</Text>

      <View style={styles.controls}>
        {!ready ? (
          <Text style={{ textAlign: 'center' }}>Loading Assets...</Text>
        ) : (
          <Button title={running ? "Running Tests..." : "⚡ RUN ALL TESTS ⚡"} onPress={runSuite} disabled={running} color="#6200ea" />
        )}
      </View>

      <FlatList
        data={results}
        renderItem={renderItem}
        keyExtractor={item => item.id}
        contentContainerStyle={styles.list}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, paddingTop: 50, backgroundColor: '#f5f5f5' },
  title: { fontSize: 24, fontWeight: 'bold', textAlign: 'center', marginBottom: 20 },
  controls: { paddingHorizontal: 20, marginBottom: 10 },
  list: { paddingBottom: 50 },
  card: { backgroundColor: 'white', marginHorizontal: 15, marginBottom: 15, padding: 15, borderRadius: 10, elevation: 2 },
  headerRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  testName: { fontSize: 16, fontWeight: 'bold', flex: 1 },
  green: { color: 'green' },
  red: { color: 'red' },
  duration: { color: '#666', fontSize: 12 },
  errorText: { color: 'red', marginTop: 10, fontSize: 12 },
  videoContainer: { marginTop: 10, height: 200, backgroundColor: '#000', borderRadius: 5, overflow: 'hidden' },
  video: { flex: 1 },
  path: { color: '#aaa', fontSize: 10, marginTop: 5, textAlign: 'center' }
});
