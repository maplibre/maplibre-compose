// A test that hosts a real map waits on real work, none of which is on the test clock. Two seconds
// is Mocha's default and is not enough for any of it.

config.client = config.client || {};
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 60000 });

// Karma hears nothing between two tests, so its own 30s idle timeout has to outlast Mocha's.
config.browserNoActivityTimeout = 120000;
