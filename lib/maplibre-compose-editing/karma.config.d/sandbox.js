// The CI runner runs Chromium as root, which refuses to start with its sandbox on.

config.customLaunchers = Object.assign({}, config.customLaunchers, {
  ChromeHeadlessNoSandbox: {
    base: "ChromeHeadless",
    flags: ["--no-sandbox"],
  },
});
config.browsers = ["ChromeHeadlessNoSandbox", "FirefoxHeadless"];
