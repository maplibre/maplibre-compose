// Kotlin 2.4.21 shares Wasm callback adapters by their erased JS signature. The Karma
// transformer callback can pick up a DOM Node return cast from kotlin-browser instead of
// kotlin-test's external interface. Apply the transformer entirely in JS, around Mocha,
// so kotlin-test uses its built-in Mocha adapter and keeps asynchronous tests awaitable.
(() => {
  const { describe, xdescribe, it, xit } = globalThis;
  const adapter = globalThis.kotlinTest.adapterTransformer({
    suite: (name, ignored, block) =>
      (ignored ? xdescribe : describe)(name, block),
    test: (name, ignored, block) => (ignored ? xit : it)(name, block),
  });
  globalThis.kotlinTest = undefined;
  globalThis.describe = (name, block) => adapter.suite(name, false, block);
  globalThis.xdescribe = (name, block) => adapter.suite(name, true, block);
  globalThis.it = (name, block) => adapter.test(name, false, block);
  globalThis.xit = (name, block) => adapter.test(name, true, block);
})();
