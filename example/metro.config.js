const { getDefaultConfig } = require('expo/metro-config');
const path = require('path');
const { withMediaEngineMonorepoResolver } = require('../metro-preset');

const projectRoot = __dirname;
const packageRoot = path.resolve(projectRoot, '..');

let config = getDefaultConfig(projectRoot);

config.watchFolders = [packageRoot];
config.resolver.nodeModulesPaths = [
    path.resolve(projectRoot, 'node_modules'),
    path.resolve(packageRoot, 'node_modules'),
];

config = withMediaEngineMonorepoResolver(config, { projectRoot });

module.exports = config;
