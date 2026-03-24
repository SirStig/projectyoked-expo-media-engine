const path = require('path');

/**
 * Use from your app metro.config.js when this package is linked (e.g. file:..).
 * Ensures one react / react-native / expo-modules-core so RN’s view config registry matches the renderer.
 *
 * @param {import('expo/metro-config').MetroConfig} config
 * @param {{ projectRoot: string }} options projectRoot must be your app directory (e.g. __dirname).
 */
function withMediaEngineMonorepoResolver(config, { projectRoot }) {
    const root = path.resolve(projectRoot);
    const nm = (name) => path.resolve(root, 'node_modules', name);
    config.resolver = config.resolver || {};
    config.resolver.extraNodeModules = {
        ...(config.resolver.extraNodeModules || {}),
        react: nm('react'),
        'react-native': nm('react-native'),
        'expo-modules-core': nm('expo-modules-core'),
    };
    config.resolver.disableHierarchicalLookup = true;
    return config;
}

module.exports = { withMediaEngineMonorepoResolver };
