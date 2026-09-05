// Map rendering and network work run outside the test clock and can exceed Mocha's 2s default.

config.client = config.client || {};
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 60000 });

// Keep Karma's idle timeout longer than Mocha's per-test timeout.
config.browserNoActivityTimeout = 120000;
// GL JS 6 workers stay busy after a map closes; the default 2s ping window then
// reports a disconnect even when every test passed.
config.browserDisconnectTimeout = 30000;
