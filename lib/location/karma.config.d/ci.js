// The CI runner is an isolated VM, so its launcher disables Chromium's unavailable sandbox.
// Replacing config.browsers here is why this module's JS tests still launch one Chromium
// even if useKarma lists more names.
if (process.env.CI && process.platform === "linux") {
  config.customLaunchers = {
    ChromeHeadlessCI: {
      base: "ChromeHeadless",
      flags: ["--no-sandbox"],
    },
  };
  config.browsers = ["ChromeHeadlessCI"];
}
