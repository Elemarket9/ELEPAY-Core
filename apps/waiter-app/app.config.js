const fs = require("node:fs");
const path = require("node:path");

// Local per-app Firebase Android config; credential-free builds still work.
module.exports = ({ config }) => {
  const googleServicesFile = path.join(__dirname, "google-services.json");
  return {
    ...config,
    plugins: [...(config.plugins || []), ["expo-notifications", { color: "#0A5838" }]],
    android: {
      ...config.android,
      ...(fs.existsSync(googleServicesFile) ? { googleServicesFile: "./google-services.json" } : {}),
    },
  };
};
