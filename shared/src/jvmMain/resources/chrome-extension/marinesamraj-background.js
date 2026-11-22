let marineSamrajSession = null;
let marineSamrajTab = null;
let marineSamrajWaitingReason = '';

async function marineSamrajAuthorized(sender) {
  const current = command?.enabled ? command.marineSamraj : null;
  if (!current) { marineSamrajSession = null; marineSamrajTab = null; marineSamrajWaitingReason = 'Start the MarineSamraj routine in AutoPilot.'; return null; }
  if (marineSamrajSession !== current.session) { marineSamrajSession = current.session; marineSamrajTab = null; }
  const tab = sender.tab;
  if (!tab) return null;
  let url; try { url = new URL(sender.url); } catch { return null; }
  if (!['https://marinesamraj.com', 'https://www.marinesamraj.com'].includes(url.origin)) return null;
  const path = url.pathname.toLowerCase();
  if (!(path === '/ep_home' || path === '/sitemap.xml' || path.startsWith('/exam-prep/'))) return null;
  if (/\/(?:user|admin|notification|review-pipeline|support-the-creators)(?:\/|$)/.test(path) || (!current.includeBookmarks && url.searchParams.get('tabID')?.toUpperCase() === 'BOOKMARKS')) return null;
  if (marineSamrajTab === null) {
    // Starting this routine explicitly authorizes selecting one public collection tab.
    // Use its own window: lastFocusedWindow may be absent while another app is in front.
    marineSamrajTab = tab.id;
    try { await chrome.tabs.update(tab.id, {active:true}); }
    catch { marineSamrajTab = null; marineSamrajWaitingReason = 'Chrome could not select the public collection tab.'; return null; }
  }
  if (marineSamrajTab !== tab.id) { marineSamrajWaitingReason = 'This run is bound to another tab. Stop and restart with this tab active.'; return null; }
  const [selected] = await chrome.tabs.query({active:true,windowId:tab.windowId});
  if (selected?.id !== tab.id) { marineSamrajWaitingReason = 'Select this collection tab in its Chrome window.'; return null; }
  // Public collection is bound to this active tab, but does not require OS foreground focus.
  // It performs no assessment submissions, account edits or native mouse/keyboard input.
  marineSamrajWaitingReason = '';
  marineSamrajSession = current.session; marineSamrajTab = tab.id;
  return current;
}

chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (!['marineSamrajHello', 'marineSamrajReading', 'marineSamrajAction'].includes(message.type)) return false;
  (async () => {
    const current = await marineSamrajAuthorized(sender); if (!current) return message.type === 'marineSamrajHello' ? {reason:marineSamrajWaitingReason} : null;
    if (message.type === 'marineSamrajHello') return { session: current.session, clickDelayMillis: current.clickDelayMillis, includeBookmarks: current.includeBookmarks === true };
    if (message.session !== current.session) return null;
    if (message.type === 'marineSamrajAction') {
      const action = current.action;
      if (action && action.request === message.request && action.document === message.document) {
        await fetch(`${BASE}/event`, { method:'POST', headers:{'Content-Type':'text/plain'}, body:JSON.stringify({ type:'marineSamrajAction', session:current.session, request:message.request, success:message.success===true }) });
      }
      return null;
    }
    const reading = message.reading;
    if (!reading || reading.document !== message.document) return null;
    // Chrome may retain MessageSender.url from document creation during SPA navigation.
    // Keep the origin binding; the app validates the public path and document-bound action.
    let readingUrl; try { readingUrl = new URL(reading.url); } catch { return null; }
    if (reading.session !== current.session || readingUrl.origin !== new URL(sender.url).origin) return null;
    const path = readingUrl.pathname.toLowerCase();
    if (!(path === '/ep_home' || path === '/sitemap.xml' || path.startsWith('/exam-prep/')) || /\/(?:user|admin|notification|review-pipeline|support-the-creators)(?:\/|$)/.test(path) || (!current.includeBookmarks && readingUrl.searchParams.get('tabID')?.toUpperCase() === 'BOOKMARKS')) return null;
    const encoded = JSON.stringify(reading); if (encoded.length > 1000000) return null;
    const total = Math.ceil(encoded.length / 1000), snapshot = `${reading.document}:${Date.now()}`;
    for (let index = 0; index < total; index++) {
      const active = await marineSamrajAuthorized(sender);
      if (active?.session !== current.session) return null;
      const response = await fetch(`${BASE}/event`, { method:'POST', headers:{'Content-Type':'text/plain'}, body:JSON.stringify({type:'marineSamrajChunk',session:current.session,snapshot,index,total,chunk:encoded.slice(index*1000,(index+1)*1000)}) });
      if (!response.ok) return null;
    }
    const fresh = await marineSamrajAuthorized(sender);
    return fresh?.session === current.session ? { session:fresh.session, action:fresh.action } : null;
  })().then(respond, () => respond(null));
  return true;
});
