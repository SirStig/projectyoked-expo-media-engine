const { getDefaultConfig } = require('expo/metro-config');
const path = require('path');

const projectRoot = __dirname;
const packageRoot = path.resolve(projectRoot, '..');

const config = getDefaultConfig(projectRoot);

// 1. Watch the local package directory so changes trigger updates
config.watchFolders = [packageRoot];

// 2. Resolve modules from the example's node_modules first, then the package's
config.resolver.nodeModulesPaths = [
    path.resolve(projectRoot, 'node_modules'),
    path.resolve(packageRoot, 'node_modules'),
];

module.exports = config;
