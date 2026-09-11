// MapLibre GL JS refuses to start without a WebGL context, and a headless browser on a machine
// with no GPU has none unless it is told to rasterize in software.

config.customLaunchers = Object.assign({}, config.customLaunchers, {
  ChromeHeadlessWebGL: {
    base: "ChromeHeadless",
    flags: [
      "--use-gl=angle",
      "--use-angle=swiftshader",
      "--enable-unsafe-swiftshader",
      "--no-sandbox",
    ],
  },
  // Headless Firefox on Linux has no WebGL, so Linux runs a headed Firefox on the Xvfb display that
  // test:js provides.
  FirefoxWebGL: {
    base: process.platform === "linux" ? "Firefox" : "FirefoxHeadless",
    prefs: {
      "webgl.force-enabled": true,
      "webgl.disabled": false,
    },
  },
});
config.browsers = ["ChromeHeadlessWebGL", "FirefoxWebGL"];
