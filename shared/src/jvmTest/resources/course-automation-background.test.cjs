const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname, '../../jvmMain/resources/chrome-extension/course-automation-background.js'), 'utf8');
function fixture() {
  const reports = [];
  let listener;
  const context = {
    command: { enabled: true, courseAutomation: { session: 'run' } },
    focused: true, active: 1, URL, Date, Map, BASE: 'http://127.0.0.1:47613',
    fetch: async (_url, options) => { reports.push(options.body); return { ok: true }; },
    chrome: {
      runtime: { onMessage: { addListener: (value) => { listener = value; } } },
      windows: { getLastFocused: async () => ({ focused: context.focused }) },
    },
    activeTabId: async () => context.active,
  };
  vm.runInNewContext(source, context);
  const sender = { tab: { id: 1 }, frameId: 0, url: 'https://sagarmeinyog.com/dashboard/courses/abc' };
  const send = (message, from = sender) => new Promise(resolve => listener(message, from, resolve));
  const snapshot = { session: 'run', document: 'doc', path: '/dashboard/courses/abc', elements: [{ id: 'watch', tag: 'button', label: 'Watch video' }] };
  return { context, reports, sender, send, snapshot };
}

test('authorized inventory response carries the selected playback speed', async () => {
  const { context, send, snapshot } = fixture();
  context.command.courseAutomation.playbackSpeed = 2;
  assert.equal((await send({ type: 'courseInventory', snapshot })).playbackSpeed, 2);
});

test('inventory needs no tabs URL permission, stays local and never exceeds the bridge event limit', async () => {
  const { send, reports, snapshot } = fixture();
  snapshot.elements[0].context = '\u0000\\"😀'.repeat(1000);
  assert.equal((await send({ type: 'courseHello' })).session, 'run');
  await send({ type: 'courseInventory', snapshot });
  assert.ok(reports.length > 1);
  assert.ok(reports.every(body => Buffer.byteLength(body) < 16 * 1024));
  const restored = JSON.parse(reports.map(body => JSON.parse(body).chunk).join(''));
  assert.equal(restored.elements[0].context, snapshot.elements[0].context);
});

test('stopped, background, other-tab and other-site messages cannot report or receive actions', async () => {
  const { context, reports, send, sender, snapshot } = fixture();
  await send({ type: 'courseHello' });
  context.focused = false;
  assert.equal(await send({ type: 'courseInventory', snapshot }), null);
  context.focused = true;
  context.active = 2;
  assert.equal(await send({ type: 'courseHello' }, { ...sender, tab: { id: 2 } }), null);
  context.active = 1;
  assert.equal(await send({ type: 'courseHello' }, { ...sender, url: 'https://example.com/dashboard' }), null);
  context.command.enabled = false;
  assert.equal(await send({ type: 'courseInventory', snapshot }), null);
  assert.equal(reports.length, 0);
});

test('acknowledgement must match the active session, request and registered document', async () => {
  const { context, reports, send, snapshot } = fixture();
  await send({ type: 'courseInventory', snapshot });
  reports.length = 0;
  context.command.courseAutomation.action = { document: 'doc', request: 3, element: 'watch', kind: 'click' };
  const ack = { type: 'courseAction', session: 'run', document: 'doc', request: 3, success: true };
  await send({ ...ack, session: 'old' });
  await send({ ...ack, request: 2 });
  await send({ ...ack, document: 'old-doc' });
  assert.equal(reports.length, 0);
  await send(ack);
  assert.equal(reports.length, 1);
  assert.equal(JSON.parse(reports[0]).request, 3);
});

test('client-side navigation uses the live document path even when Chrome retains the original sender URL', async () => {
  const { send, sender, snapshot, reports } = fixture();
  const initialSender = { ...sender, documentId: 'same-document', url: 'https://sagarmeinyog.com/dashboard' };
  await send({ type: 'courseHello' }, initialSender);
  await send({ type: 'courseInventory', snapshot }, initialSender);
  let restored = JSON.parse(reports.map(body => JSON.parse(body).chunk).join(''));
  assert.equal(restored.path, '/dashboard/courses/abc');
  reports.length = 0;
  await send({ type: 'courseHello' }, initialSender);
  const player = { ...initialSender, frameId: 9, url: 'https://player.example.com/embed' };
  await send({ type: 'courseInventory', snapshot: { ...snapshot, path: '/embed', elements: [{ id: 'video', tag: 'video' }] } }, player);
  restored = JSON.parse(reports.map(body => JSON.parse(body).chunk).join(''));
  assert.equal(restored.path, '/dashboard/courses/abc');
  assert.equal(restored.top, false);
});

test('an embedded player cannot authorize the tab and its non-video controls are excluded', async () => {
  const { send, sender, snapshot, reports } = fixture();
  const player = { ...sender, frameId: 7, url: 'https://player.example.com/embed' };
  assert.equal(await send({ type: 'courseHello' }, player), null);
  await send({ type: 'courseHello' });
  snapshot.elements.push({ id: 'video', tag: 'video' });
  snapshot.assessment = { module: 'forged', result: 'passed', questions: [] };
  await send({ type: 'courseInventory', snapshot }, player);
  const restored = JSON.parse(reports.map(body => JSON.parse(body).chunk).join(''));
  assert.deepEqual(restored.elements.map(element => element.tag), ['video']);
  assert.equal(restored.top, false);
  assert.equal(restored.assessment, undefined);
});

test('profile identity and progress rows are accepted only from the top page', async () => {
  const { send, sender, snapshot, reports } = fixture();
  snapshot.profileName = 'User'; snapshot.progress = { page: 1, rows: [{ course: 'Example' }] };
  await send({ type: 'courseInventory', snapshot });
  let restored = JSON.parse(reports.map(body => JSON.parse(body).chunk).join(''));
  assert.equal(restored.profileName, 'User');
  reports.length = 0;
  await send({ type: 'courseInventory', snapshot }, { ...sender, frameId: 2 });
  restored = JSON.parse(reports.map(body => JSON.parse(body).chunk).join(''));
  assert.equal(restored.profileName, undefined);
  assert.equal(restored.progress, undefined);
});
