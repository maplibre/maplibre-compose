// The map draws controls that load their artwork and labels as Compose resources, which the
// browser fetches over HTTP from /composeResources. Gradle assembles that directory next to the
// test bundle, but Karma serves nothing it is not told about.

config.files = config.files || [];
config.files.push({
  pattern: config.basePath + "/kotlin/composeResources/**",
  included: false,
  served: true,
  watched: false,
});

config.proxies = config.proxies || {};
config.proxies["/composeResources/"] = "/base/kotlin/composeResources/";
