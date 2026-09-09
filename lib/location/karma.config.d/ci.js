// The CI runner is an isolated VM, so its launcher disables Chromium's unavailable sandbox.
if (process.env.CI && process.platform === "linux") {
  config.customLaunchers = Object.assign({}, config.customLaunchers, {
    ChromeHeadlessCI: {
      base: "ChromeHeadless",
      flags: ["--no-sandbox"],
    },
  });
  config.browsers = ["ChromeHeadlessCI", "FirefoxHeadless"];
}
