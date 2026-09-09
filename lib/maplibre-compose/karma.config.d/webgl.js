// MapLibre GL JS refuses to start without a WebGL context, and a headless browser on a machine
// with no GPU has none unless it is told to rasterize in software.
//
// Firefox headless also refuses WebGL (https://bugzil.la/1375585), so this launcher is headed and
// test:js gives it a display with Xvfb on Linux.

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
  FirefoxWebGL: {
    base: "Firefox",
    prefs: {
      "webgl.force-enabled": true,
      "webgl.disabled": false,
    },
  },
});
config.browsers = ["ChromeHeadlessWebGL", "FirefoxWebGL"];
