// MapLibre GL JS refuses to start without a WebGL context, and a headless browser on a machine
// with no GPU has none unless it is told to rasterize in software.

config.customLaunchers = {
  ChromeHeadlessWebGL: {
    base: "ChromeHeadless",
    flags: [
      "--use-gl=angle",
      "--use-angle=swiftshader",
      "--enable-unsafe-swiftshader",
      "--no-sandbox",
    ],
  },
};
config.browsers = ["ChromeHeadlessWebGL"];
