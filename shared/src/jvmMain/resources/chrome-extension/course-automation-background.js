// Additive protocol: uses the same local connection, without changing the existing tick/click path.
let courseTab = null;
let courseSession = null;
let courseSnapshotSequence = 0;
const courseDocuments = new Map();
let courseTop = null;
let handledSmyPageRequest = 0;
let smyDiscoveryTimer = null;
let handledSmyReloadRequest = 0;
let smyWakeTimer = null;
const smyInjectedAt = new Map(); // tabId -> when the course script was last injected
const SMY_ORIGIN = 'https://sagarmeinyog.com';
// The app's reload request is a timestamp. A worker that restarts mid-run must not replay an old one.
const SMY_RELOAD_FRESH_MILLIS = 15000;
const SMY_WAKE_MILLIS = 1000;
const SMY_INJECT_RETRY_MILLIS = 10000;

async function courseAuthorization(sender) {
  const current = command?.enabled ? command.courseAutomation : null;
  if (!current) { courseSession = null; courseTab = null; courseTop = null; courseDocuments.clear(); return null; }
  if (courseSession !== current.session) {
    courseSession = current.session; courseTab = null; courseTop = null; courseDocuments.clear();
  }
  const tab = sender.tab;
  if (!tab || tab.id !== await activeTabId()) return null;
  // MessageSender.url is available without adding the broad tabs permission.
  if ((sender.frameId ?? 0) === 0) {
    let url;
    try { url = new URL(sender.url); } catch { return null; }
    if (url.origin !== 'https://sagarmeinyog.com' || !/^\/dashboard(?:\/|$)/.test(url.pathname)) {
      courseTop = null;
      return null;
    }
    // On client-side navigation Chrome can keep MessageSender.url at the URL where the script
    // first loaded. Preserve the path reported by that same document's inventory instead.
    const document = sender.documentId || sender.url;
    const path = courseTop?.tab === tab.id && courseTop.document === document ? courseTop.path : url.pathname;
    courseTop = { tab: tab.id, document, path, at: Date.now() };
  }
  if (!courseTop || courseTop.tab !== tab.id || Date.now() - courseTop.at > 3500) return null;
  // `activeTabId()` already limits the command to the active tab in Chrome's last-focused
  // window. Do not require Chrome itself to be the OS-focused app: on Linux the AutoPilot
  // window becomes focused while it is showing live status, which otherwise prevents that
  // already-authorized course tab from receiving its next navigation command.
  if (courseTab !== null && courseTab !== tab.id) return null;
  courseTab = tab.id;
  return { ...current, path: courseTop.path };
}

async function courseReport(event) {
  const response = await fetchApp('/event', { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: JSON.stringify(event) }, EVENT_TIMEOUT_MILLIS);
  if (!response.ok) throw new Error('Course report refused');
}

function stopSmyDiscovery() {
  if (smyDiscoveryTimer !== null) clearInterval(smyDiscoveryTimer);
  smyDiscoveryTimer = null;
}

async function discoverSmyPage(request) {
  const current = command?.enabled ? command.courseAutomation : null;
  if (!current || current.findSmyPageRequest !== request) {
    stopSmyDiscovery();
    return false;
  }
  try {
    const tabs = await chrome.tabs.query({ url: 'https://sagarmeinyog.com/dashboard/*' });
    const tab = tabs.find(candidate => candidate.active) || tabs[0];
    if (!tab || tab.id === undefined || tab.windowId === undefined) return false;
    await chrome.tabs.update(tab.id, { active: true });
    await chrome.windows.update(tab.windowId, { focused: true });
    if (command?.courseAutomation?.session !== current.session) return false;
    courseSession = current.session;
    courseTab = tab.id;
    courseTop = null;
    courseDocuments.clear();
    stopSmyDiscovery();
    await courseReport({ type: 'smyPageFound', session: current.session, success: true });
    return true;
  } catch {
    return false;
  }
}

// A short code (letters only) saying why something failed; anything else is dropped.
function problemCode(value) {
  return typeof value === 'string' && /^[A-Za-z]{1,40}$/.test(value) ? value : null;
}

function isSmyTab(tab) {
  try { return !!tab && new URL(tab.url).origin === SMY_ORIGIN; } catch { return false; }
}

// The course tab this run is bound to, or else the active Sagar Mein Yog tab.
async function smyTab() {
  if (courseTab !== null) {
    try { const tab = await chrome.tabs.get(courseTab); if (isSmyTab(tab)) return tab; } catch {}
  }
  const tabs = await chrome.tabs.query({ url: `${SMY_ORIGIN}/*` });
  return tabs.find(tab => tab.active && isSmyTab(tab)) || tabs.find(isSmyTab) || null;
}

// Puts the course script back into a tab whose copy is gone (the extension was reloaded or updated,
// which cuts off the scripts already in open tabs). Without it only a page refresh brought it back.
async function injectCourseScript(tabId, session) {
  const last = smyInjectedAt.get(tabId) ?? 0;
  if (Date.now() - last < SMY_INJECT_RETRY_MILLIS || !chrome.scripting?.executeScript) return;
  smyInjectedAt.set(tabId, Date.now());
  let success = false, problem = null;
  try {
    await chrome.scripting.executeScript({ target: { tabId, allFrames: true }, files: ['course-automation.js'] });
    success = true;
  } catch {
    // Another site's frame (a video player) can refuse; the page itself is what matters.
    try { await chrome.scripting.executeScript({ target: { tabId }, files: ['course-automation.js'] }); success = true; }
    catch { problem = 'injectFailed'; }
  }
  if (session) courseReport({ type: 'smyScriptInjected', session, success, problem }).catch(() => {});
}

// While a run is on, the worker wakes the course tab every second. Chrome slows a hidden or covered
// tab's own timers to once a minute, which made runs look hung; a message isn't slowed. A tab that
// doesn't answer has lost its script, so it gets a new copy. These calls also keep this worker alive.
let smyWakeInFlight = false;
async function wakeSmyTab() {
  const course = command?.enabled ? command.courseAutomation : null;
  if (!course) { stopSmyWake(); return; }
  if (smyWakeInFlight) return; // A busy tab answers late; don't pile up wake-ups.
  smyWakeInFlight = true;
  try {
    const tab = await smyTab();
    if (!tab || tab.id === undefined || !tab.active || tab.status !== 'complete') return;
    try {
      // A frozen tab may never answer; give up on this wake-up after 5 seconds.
      let timer;
      await Promise.race([chrome.tabs.sendMessage(tab.id, { type: 'courseWake' }),
        new Promise(resolve => { timer = setTimeout(resolve, 5000); })]).finally(() => clearTimeout(timer));
    } catch (error) {
      if (/Receiving end does not exist/i.test(String(error?.message ?? error))) await injectCourseScript(tab.id, course.session);
    }
  } catch {} finally { smyWakeInFlight = false; }
}

function stopSmyWake() {
  if (smyWakeTimer !== null) clearInterval(smyWakeTimer);
  smyWakeTimer = null;
}

// The app asks for this when a run has made no progress for a while: what the user used to do by hand.
async function reloadSmyPage(course, request) {
  let success = false, problem = null;
  try {
    const tab = await smyTab();
    if (!tab || tab.id === undefined) problem = 'noTab';
    else if (!tab.active) problem = 'inactiveTab'; // Never take over a tab the user moved away from.
    else {
      courseTop = null;
      courseDocuments.clear();
      await chrome.tabs.reload(tab.id);
      success = true;
    }
  } catch { problem = 'reloadFailed'; }
  if (command?.courseAutomation?.session !== course.session) return;
  try { await courseReport({ type: 'smyPageReloaded', session: course.session, request, success, problem }); } catch {}
}

function applySmyReloadRequest(course) {
  const request = Number(course.reloadPageRequest ?? 0);
  if (!request || request <= handledSmyReloadRequest) return;
  handledSmyReloadRequest = request;
  if (Math.abs(Date.now() - request) > SMY_RELOAD_FRESH_MILLIS) return;
  reloadSmyPage(course, request);
}

function applySmyPageRequest(next) {
  const course = next?.enabled ? next.courseAutomation : null;
  if (!course) { stopSmyDiscovery(); stopSmyWake(); return; }
  if (smyWakeTimer === null) smyWakeTimer = setInterval(wakeSmyTab, SMY_WAKE_MILLIS);
  applySmyReloadRequest(course);
  const request = Number(course.findSmyPageRequest ?? 0);
  if (request <= handledSmyPageRequest) return;
  handledSmyPageRequest = request;
  stopSmyDiscovery();
  discoverSmyPage(request).then(found => {
    if (found || !command?.enabled || command.courseAutomation?.findSmyPageRequest !== request) return;
    smyDiscoveryTimer = setInterval(() => { discoverSmyPage(request); }, 1500);
  });
}

async function courseBrowserPlay(message, sender) {
  const authorized = await courseAuthorization(sender);
  const action = authorized?.action;
  const frame = sender.frameId ?? 0;
  // This is deliberately narrower than the DGS browser-click helper: it can only click the
  // centre of the exact paused HTML video currently selected by the active course action.
  if (!authorized || frame !== 0 || message.session !== authorized.session ||
    !action || action.kind !== 'play' || action.request !== message.request ||
    action.document !== message.document || action.element !== message.element ||
    courseDocuments.get(frame) !== message.document || !chrome.debugger?.attach) return null;
  const target = { tabId: sender.tab.id };
  let attached = false;
  try {
    await chrome.debugger.attach(target, '1.3');
    attached = true;
    const fresh = await courseAuthorization(sender);
    if (!fresh || fresh.session !== authorized.session || fresh.action?.request !== action.request) return null;
    const point = await chrome.tabs.sendMessage(target.tabId, {
      type: 'courseResolvePlay', session: authorized.session, document: action.document, element: action.element,
    }, { frameId: 0 });
    if (!point || ![point.x, point.y, point.width, point.height].every(Number.isFinite) ||
      point.width <= 0 || point.height <= 0 || point.x < 0 || point.y < 0 || point.x >= point.width || point.y >= point.height) return null;
    await chrome.debugger.sendCommand(target, 'Input.dispatchMouseEvent', { type: 'mouseMoved', x: point.x, y: point.y, button: 'none' });
    await chrome.debugger.sendCommand(target, 'Input.dispatchMouseEvent', { type: 'mousePressed', x: point.x, y: point.y, button: 'left', clickCount: 1 });
    await chrome.debugger.sendCommand(target, 'Input.dispatchMouseEvent', { type: 'mouseReleased', x: point.x, y: point.y, button: 'left', clickCount: 1 });
    return { accepted: true };
  } catch {
    return null;
  } finally {
    if (attached) { try { await chrome.debugger.detach(target); } catch {} }
  }
}

chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (!['courseHello', 'courseInventory', 'courseAction', 'courseBrowserPlay'].includes(message.type)) return false;
  (async () => {
    const authorized = await courseAuthorization(sender);
    if (!authorized) return null;
    const frame = sender.frameId ?? 0;
    if (message.type === 'courseBrowserPlay') return courseBrowserPlay(message, sender);
    if (message.type === 'courseHello') return { session: authorized.session };
    if (message.session && message.session !== authorized.session) return null;
    if (message.type === 'courseAction') {
      const action = authorized.action;
      if (action && action.request === message.request && action.document === message.document && courseDocuments.get(frame) === message.document) {
        await courseReport({ type: 'courseAction', session: authorized.session, request: message.request, success: message.success === true, problem: problemCode(message.problem) });
      }
      return null;
    }
    const snapshot = message.snapshot;
    if (!snapshot || snapshot.session !== authorized.session || typeof snapshot.document !== 'string') return null;
    snapshot.top = frame === 0;
    if (snapshot.top) {
      if (typeof snapshot.path !== 'string' || !/^\/dashboard(?:\/|$)/.test(snapshot.path)) {
        courseTop = null;
        return null;
      }
      courseTop.path = snapshot.path;
    } else snapshot.path = authorized.path;
    // Other-origin players expose video state only; their navigation controls are out of scope.
    let sameSite = false;
    try { sameSite = new URL(sender.url).origin === 'https://sagarmeinyog.com'; } catch {}
    if (!sameSite) { snapshot.elements = snapshot.elements.filter((element) => element.tag === 'video'); delete snapshot.assessment; }
    if (frame !== 0) { delete snapshot.assessment; delete snapshot.profileName; delete snapshot.progress; }
    courseDocuments.set(frame, snapshot.document);
    const encoded = JSON.stringify(snapshot);
    if (encoded.length > 2000000) return null;
    const snapshotId = `${authorized.session}:${++courseSnapshotSequence}`;
    // 1000 UTF-16 characters stay below 16 KiB even after JSON escaping and UTF-8 encoding.
    const total = Math.ceil(encoded.length / 1000);
    for (let index = 0; index < total; index++) {
      if (command?.courseAutomation?.session !== authorized.session || !command?.enabled) return null;
      await courseReport({ type: 'courseChunk', session: authorized.session, snapshot: snapshotId, index, total, chunk: encoded.slice(index * 1000, (index + 1) * 1000) });
    }
    const fresh = await courseAuthorization(sender);
    return fresh && fresh.session === authorized.session ? { session: fresh.session, action: fresh.action, playbackSpeed: fresh.playbackSpeed } : null;
  })().then(respond, () => respond(null));
  return true;
});

// After an install, update or reload, open course tabs still run the old, cut-off script.
chrome.runtime.onInstalled.addListener(async () => {
  try {
    const tabs = await chrome.tabs.query({ url: `${SMY_ORIGIN}/*` });
    for (const tab of tabs) if (tab.id !== undefined && tab.status === 'complete') injectCourseScript(tab.id, null);
  } catch {}
});
