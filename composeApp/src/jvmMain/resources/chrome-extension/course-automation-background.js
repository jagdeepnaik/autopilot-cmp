// Additive protocol: uses the same local connection, without changing the existing tick/click path.
let courseTab = null;
let courseSession = null;
let courseSnapshotSequence = 0;
const courseDocuments = new Map();
let courseTop = null;

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
  const window = await chrome.windows.getLastFocused();
  if (!window.focused || (courseTab !== null && courseTab !== tab.id)) return null;
  courseTab = tab.id;
  return { ...current, path: courseTop.path };
}

async function courseReport(event) {
  const response = await fetch(`${BASE}/event`, { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: JSON.stringify(event) });
  if (!response.ok) throw new Error('Course report refused');
}

chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (!['courseHello', 'courseInventory', 'courseAction'].includes(message.type)) return false;
  (async () => {
    const authorized = await courseAuthorization(sender);
    if (!authorized) return null;
    const frame = sender.frameId ?? 0;
    if (message.type === 'courseHello') return { session: authorized.session };
    if (message.session && message.session !== authorized.session) return null;
    if (message.type === 'courseAction') {
      const action = authorized.action;
      if (action && action.request === message.request && action.document === message.document && courseDocuments.get(frame) === message.document) {
        await courseReport({ type: 'courseAction', session: authorized.session, request: message.request, success: message.success === true });
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
