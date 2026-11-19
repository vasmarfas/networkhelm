// tests that wait on the real clock, as the rates of the VPN panel do, need more than the 2 s mocha gives a test
config.client = config.client || {};
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 10000 });
