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
  FirefoxWebGL: {
    base: "FirefoxHeadless",
    prefs: {
      "webgl.force-enabled": true,
      "webgl.disabled": false,
      "webgl.forbid-software": false,
      "gfx.webrender.software": true,
    },
  },
});
config.browsers = ["ChromeHeadlessWebGL", "FirefoxWebGL"];
