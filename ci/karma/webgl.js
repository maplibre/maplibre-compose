config.set({
  client: { mocha: { timeout: 60000 } },
});

// Run after Kotlin's Karma adapter and before the Wasm test module registers its tests.
config.files.splice(1, 0, {
  pattern: "kotlin/wasm-adapter.js",
  included: true,
  served: true,
  watched: false,
});

config.customLaunchers.ChromeHeadlessNoSandbox.flags.push(
  "--use-gl=angle",
  "--use-angle=swiftshader",
  "--enable-unsafe-swiftshader",
);

if (
  require("node:fs").existsSync(
    require("node:path").join(config.basePath, "kotlin/composeResources"),
  )
) {
  config.proxies["/composeResources/"] = "/base/kotlin/composeResources/";
  config.files.push({
    pattern: "kotlin/composeResources/**/*",
    included: false,
    served: true,
    watched: false,
  });
}
