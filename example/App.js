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
      if (!asset1.localUri) throw new Error("Test Asset 1 failed to load");

      const asset2 = Asset.fromModule(TEST_VIDEO_2);
      await asset2.downloadAsync();
      if (!asset2.localUri) throw new Error("Test Asset 2 failed to load");

      const imageAsset = Asset.fromModule(TEST_IMAGE_1);
      await imageAsset.downloadAsync();
      if (!imageAsset.localUri) throw new Error("Test Image failed to load");

      // 2. Define Complex Config (Video + Audio Mixing + Text + Scale)
      const config = {
        outputUri: asset1.localUri.replace('.mov', '_autotest_full.mp4'),
        width: 720,
        height: 1280, // Portrait as per likely video orientation
        frameRate: 30,
        bitrate: 4000000,
        tracks: [
          {
            type: 'video',
            clips: [
              // 0-3s: Video 1
              { uri: asset1.localUri, startTime: 0, duration: 3.0, filter: 'sepia', resizeMode: 'cover' },
              // 3-5s: Image (Test Image Support) - 2 seconds
              { uri: imageAsset.localUri, startTime: 3.0, duration: 2.0, resizeMode: 'contain', scale: 0.9 },
              // 5-8s: Video 2 (Rotated)
              { uri: asset2.localUri, startTime: 5.0, duration: 3.0, filter: 'grayscale', resizeMode: 'contain', scale: 0.8, rotation: 90 }
            ]
          },
          {
            type: 'text',
            clips: [
              { uri: 'text:Auto Test', text: 'Auto Test', startTime: 0.5, duration: 2.0, x: 0.5, y: 0.2, fontSize: 60, color: '#FFFF00' },
              { uri: 'text:🔥💪', text: '🔥💪', startTime: 3.5, duration: 2.0, x: 0.5, y: 0.5, fontSize: 100, color: '#FFFFFF', scale: 1.2 }
            ]
          },
          // Audio from videos should be implicit if type='video', but checking if explicit 'audio' track works for mixing
          {
            type: 'audio',
            clips: [
              { uri: asset1.localUri, startTime: 0, duration: 3.0, volume: 1.0 },
              // No audio for image
              { uri: asset2.localUri, startTime: 5.0, duration: 3.0, volume: 1.0 }
            ]
          }
        ]
      };

      setStatus("Running Full Integration Test...");
      console.log("AutoTest Config:", JSON.stringify(config));

      const startTime = Date.now();
      const result = await MediaEngine.composeCompositeVideo(config);
      const endTime = Date.now();

      console.log("AutoTest Output:", result);

      if (result) {
        setOutputUri(result);
        setStatus(`✅ Test Passed! (${endTime - startTime}ms)\nOutput: ${result.split('/').pop()}`);
        Alert.alert("Test Passed", `Full feature set verified.\nRender Time: ${endTime - startTime}ms`);
      } else {
        throw new Error("No output URI returned");
      }

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
