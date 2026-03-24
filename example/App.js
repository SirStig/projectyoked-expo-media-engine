import { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import {
  StyleSheet,
  Text,
  View,
  Button,
  FlatList,
  ActivityIndicator,
  Alert,
  ScrollView,
  Platform,
} from 'react-native';
import Constants from 'expo-constants';
import * as FileSystem from 'expo-file-system/legacy';
import MediaEngine, { MediaEnginePreview } from '@projectyoked/expo-media-engine';
import { useVideoPlayer, VideoView } from 'expo-video';

import { loadFixtures } from './integration/loadFixtures.js';
import {
  integrationTests,
  filterTestsByPlatform,
  prepareOutputDir,
} from './integration/mediaEngineSuite.js';

const CLIP_SEC = 2.5;

const SimpleVideoPlayer = ({ uri, style }) => {
  const player = useVideoPlayer(uri, (player) => {
    player.loop = true;
    player.play();
  });

  return (
    <View style={style}>
      <VideoView
        style={{ flex: 1 }}
        player={player}
        fullscreenOptions={{ allowed: true }}
        allowPictureInPicture
      />
    </View>
  );
};

export default function App() {
  const [fixtureStatus, setFixtureStatus] = useState('');
  const [ready, setReady] = useState(false);
  const [fixtureError, setFixtureError] = useState(null);
  const [assets, setAssets] = useState(null);
  const [outputDir, setOutputDir] = useState(null);

  const [running, setRunning] = useState(false);
  const [results, setResults] = useState([]);
  const [suiteOutcome, setSuiteOutcome] = useState('idle');
  const [previewLoaded, setPreviewLoaded] = useState(false);

  const autoRun =
    Constants.expoConfig?.extra?.mediaEngineAutoRunIntegration === true;
  const autoRunStarted = useRef(false);

  const testsForPlatform = useMemo(
    () => filterTestsByPlatform(integrationTests, Platform.OS),
    []
  );

  const previewConfig = useMemo(() => {
    if (!assets?.videoA || !outputDir) return null;
    return {
      outputUri: `${outputDir}preview_placeholder.mp4`,
      width: 640,
      height: 360,
      frameRate: 30,
      tracks: [
        {
          type: 'video',
          clips: [{ uri: assets.videoA, startTime: 0, duration: CLIP_SEC }],
        },
      ],
    };
  }, [assets, outputDir]);

  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        setFixtureStatus('Preparing fixtures…');
        const loaded = await loadFixtures({
          onProgress: (msg) => {
            if (!cancelled) setFixtureStatus(msg);
          },
        });
        if (cancelled) return;

        const out = await prepareOutputDir();
        if (cancelled) return;

        setAssets(loaded);
        setOutputDir(out);
        setReady(true);
        setFixtureStatus('');
        setFixtureError(null);
      } catch (e) {
        if (!cancelled) {
          console.error(e);
          setFixtureError(e?.message ?? String(e));
          setFixtureStatus('');
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, []);

  const runSuite = useCallback(async () => {
    if (!ready || !assets || !outputDir || running) return;
    if (!MediaEngine.isAvailable()) {
      Alert.alert('MediaEngine', 'Native module not available. Use a dev build.');
      return;
    }

    setRunning(true);
    setSuiteOutcome('running');
    setPreviewLoaded(false);
    await new Promise((r) => setTimeout(r, 0));

    const ctx = {
      videoA: assets.videoA,
      videoB: assets.videoB,
      image: assets.image,
      outputDir,
      extractedM4a: null,
    };

    const initialResults = testsForPlatform.map((t) => ({
      id: t.id,
      name: t.name,
      status: 'pending',
      outputUri: null,
      error: null,
      duration: 0,
      skipped: false,
    }));
    setResults(initialResults);

    let hadHardFailure = false;

    for (let i = 0; i < testsForPlatform.length; i++) {
      const test = testsForPlatform[i];

      setResults((prev) =>
        prev.map((r, idx) => (idx === i ? { ...r, status: 'running' } : r))
      );

      const start = Date.now();
      try {
        const outputUri = await test.run(MediaEngine, ctx);
        const duration = Date.now() - start;
        setResults((prev) =>
          prev.map((r, idx) =>
            idx === i
              ? {
                  ...r,
                  status: 'success',
                  outputUri,
                  duration,
                  skipped: false,
                }
              : r
          )
        );
      } catch (e) {
        const duration = Date.now() - start;
        console.error(`Test ${test.name} failed`, e);
        if (test.optional) {
          setResults((prev) =>
            prev.map((r, idx) =>
              idx === i
                ? {
                    ...r,
                    status: 'skipped',
                    error: e?.message ?? String(e),
                    duration,
                    skipped: true,
                  }
                : r
            )
          );
        } else {
          hadHardFailure = true;
          setResults((prev) =>
            prev.map((r, idx) =>
              idx === i
                ? {
                    ...r,
                    status: 'error',
                    error: e?.message ?? String(e),
                    duration,
                  }
                : r
            )
          );
        }
      }

      await new Promise((r) => setTimeout(r, 300));
    }

    setRunning(false);
    setSuiteOutcome(hadHardFailure ? 'failed' : 'passed');
    if (!autoRun) {
      Alert.alert(
        'Suite complete',
        hadHardFailure ? 'One or more required tests failed.' : 'All required tests passed.'
      );
    }
  }, [ready, assets, outputDir, running, testsForPlatform, autoRun]);

  useEffect(() => {
    if (ready && autoRun && !autoRunStarted.current) {
      autoRunStarted.current = true;
      runSuite();
    }
  }, [ready, autoRun, runSuite]);

  const renderItem = ({ item }) => (
    <View style={styles.card}>
      <View style={styles.headerRow}>
        <Text
          style={[
            styles.testName,
            item.status === 'success' && styles.green,
            item.status === 'error' && styles.red,
            item.status === 'skipped' && styles.amber,
          ]}
        >
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
      {item.status === 'skipped' && (
        <Text style={styles.skipText}>skipped (optional): {item.error}</Text>
      )}

      {item.status === 'success' && item.outputUri && (
        <View style={styles.videoContainer}>
          <SimpleVideoPlayer uri={item.outputUri} style={styles.video} />
          <Text style={styles.path} numberOfLines={2}>
            {item.outputUri}
          </Text>
        </View>
      )}
    </View>
  );

  return (
    <View style={styles.container} testID="integration-app-root">
      <Text style={styles.title}>Media Engine Integration</Text>

      <View style={styles.controls}>
        {fixtureError ? (
          <Text style={styles.errorText}>Fixtures: {fixtureError}</Text>
        ) : !ready ? (
          <View>
            <ActivityIndicator />
            <Text style={{ textAlign: 'center', marginTop: 8 }}>
              {fixtureStatus || 'Loading…'}
            </Text>
          </View>
        ) : (
          <>
            <Text style={styles.subtle}>
              {MediaEngine.isAvailable() ? 'Native module loaded' : 'Native module missing'}
            </Text>
            <Button
              title={running ? 'Running…' : 'RUN ALL TESTS'}
              onPress={runSuite}
              disabled={running}
              color="#6200ea"
              testID="run-all-integration-tests"
            />
          </>
        )}
      </View>

      {ready && previewConfig && !running && (
        <ScrollView horizontal style={styles.previewRow}>
          <View style={styles.previewBox}>
            <Text style={styles.previewLabel}>MediaEnginePreview</Text>
            <View style={styles.previewFrame}>
              <MediaEnginePreview
                config={previewConfig}
                isPlaying={false}
                muted
                currentTime={0}
                onLoad={() => setPreviewLoaded(true)}
                onError={(ev) => {
                  console.warn('Preview error', ev?.nativeEvent?.message);
                }}
                style={StyleSheet.absoluteFill}
              />
            </View>
            {previewLoaded ? (
              <Text testID="integration-preview-loaded" style={styles.previewOk}>
                preview onLoad OK
              </Text>
            ) : (
              <Text style={styles.subtle}>waiting for onLoad…</Text>
            )}
          </View>
        </ScrollView>
      )}

      {suiteOutcome === 'passed' && (
        <View
          testID="integration-suite-complete"
          style={styles.sentinel}
          collapsable={false}
        />
      )}
      {suiteOutcome === 'failed' && (
        <View
          testID="integration-suite-failed"
          style={styles.sentinel}
          collapsable={false}
        />
      )}

      <FlatList
        data={results}
        renderItem={renderItem}
        keyExtractor={(item) => item.id}
        contentContainerStyle={styles.list}
        ListEmptyComponent={
          ready ? (
            <Text style={styles.subtle}>Run the suite to see results.</Text>
          ) : null
        }
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, paddingTop: 48, backgroundColor: '#f5f5f5' },
  title: { fontSize: 22, fontWeight: 'bold', textAlign: 'center', marginBottom: 12 },
  controls: { paddingHorizontal: 20, marginBottom: 12 },
  subtle: { color: '#666', textAlign: 'center', fontSize: 12, marginBottom: 8 },
  previewRow: { maxHeight: 220, marginBottom: 8 },
  previewBox: { width: 280, marginHorizontal: 12 },
  previewLabel: { fontSize: 12, fontWeight: '600', marginBottom: 4 },
  previewFrame: {
    height: 160,
    backgroundColor: '#111',
    borderRadius: 6,
    overflow: 'hidden',
  },
  previewOk: { fontSize: 11, color: 'green', marginTop: 4 },
  list: { paddingBottom: 48 },
  card: {
    backgroundColor: 'white',
    marginHorizontal: 15,
    marginBottom: 12,
    padding: 12,
    borderRadius: 10,
    elevation: 2,
  },
  headerRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  testName: { fontSize: 15, fontWeight: 'bold', flex: 1 },
  green: { color: 'green' },
  red: { color: 'red' },
  amber: { color: '#b8860b' },
  duration: { color: '#666', fontSize: 11 },
  errorText: { color: 'red', marginTop: 8, fontSize: 12 },
  skipText: { color: '#b8860b', marginTop: 8, fontSize: 12 },
  videoContainer: {
    marginTop: 8,
    height: 180,
    backgroundColor: '#000',
    borderRadius: 5,
    overflow: 'hidden',
  },
  video: { flex: 1 },
  path: { color: '#aaa', fontSize: 9, marginTop: 4, textAlign: 'center' },
  sentinel: { width: 2, height: 2, alignSelf: 'center' },
});
