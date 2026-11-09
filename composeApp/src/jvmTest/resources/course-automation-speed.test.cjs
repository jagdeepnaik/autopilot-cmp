const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../jvmMain/resources/chrome-extension/course-automation.js'), 'utf8');

test('selected speed applies to current and subsequent videos without seeking, and restores on stop', async () => {
  let scheduled, enabled = true, speed = 2, videos = [];
  const video = () => ({
    tagName: 'VIDEO', isConnected: true, playbackRate: 1, paused: false, ended: false,
    duration: 30, currentTime: 12, loop: false, muted: false, autoplay: false,
    closest: () => null, matches: () => false, hasAttribute: () => false,
    getAttribute: () => null, querySelector: () => null,
    getBoundingClientRect: () => ({ width: 100, height: 100 }),
  });
  const first = video(); videos = [first];
  const context = {
    window: {}, crypto: { randomUUID: () => 'doc' }, WeakMap, Map, URL,
    NodeFilter: { SHOW_TEXT: 4 },
    document: { readyState: 'complete', querySelectorAll: () => videos, createTreeWalker: () => ({ nextNode: () => null }) },
    location: { pathname: '/dashboard/courses/abc/def' },
    getComputedStyle: () => ({ display: 'block', visibility: 'visible', pointerEvents: 'auto' }),
    setTimeout: (callback) => { scheduled = callback; },
    chrome: { runtime: { id: 'extension', sendMessage: async (message) => !enabled ? null :
      message.type === 'courseHello' ? { session: 'run' } : { session: 'run', playbackSpeed: speed } } },
  };
  async function settle() { for (let i = 0; i < 12; i++) await Promise.resolve(); }
  vm.runInNewContext(source, context); await settle();
  assert.equal(first.playbackRate, 2);
  assert.equal(first.currentTime, 12);
  assert.equal(first.ended, false);
  const second = video(); first.isConnected = false; videos = [second];
  await scheduled(); assert.equal(second.playbackRate, 2);
  speed = 1.5; await scheduled(); assert.equal(second.playbackRate, 1.5);
  speed = 99; await scheduled(); assert.equal(second.playbackRate, 1);
  speed = 2; await scheduled(); enabled = false; await scheduled();
  assert.equal(second.playbackRate, 1);
  assert.equal(second.currentTime, 12);
});
