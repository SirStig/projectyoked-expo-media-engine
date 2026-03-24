const { expo } = require('./app.json');

module.exports = () => ({
  expo: {
    ...expo,
    extra: {
      ...(expo.extra ?? {}),
      mediaEngineAutoRunIntegration: process.env.MEDIA_ENGINE_AUTO_RUN === '1',
    },
  },
});
