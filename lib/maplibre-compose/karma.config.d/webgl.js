// MapLibre GL JS refuses to start without a WebGL context, and a headless browser on a machine
// with no GPU has none unless it is told to rasterize in software.
//
// This assignment is the suite's browser list. Gradle's useKarma block can name more
// launchers, but Karma only runs the names left in config.browsers after these files merge.

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
