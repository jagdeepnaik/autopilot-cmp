let marineSamrajSession = null;
let marineSamrajTab = null;

async function marineSamrajAuthorized(sender) {
  const current = command?.enabled ? command.marineSamraj : null;
  if (!current) { marineSamrajSession = null; marineSamrajTab = null; return null; }
  const tab = sender.tab;
  if (!tab || tab.id !== await activeTabId()) return null;
  let url; try { url = new URL(sender.url); } catch { return null; }
  if (!['https://marinesamraj.com', 'https://www.marinesamraj.com'].includes(url.origin)) return null;
  const focused = await chrome.windows.getLastFocused();
  if (!focused.focused || (marineSamrajTab !== null && marineSamrajTab !== tab.id)) return null;
  marineSamrajSession = current.session; marineSamrajTab = tab.id;
  return current;
}

chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (!['marineSamrajHello', 'marineSamrajReading', 'marineSamrajAction'].includes(message.type)) return false;
  (async () => {
    const current = await marineSamrajAuthorized(sender); if (!current) return null;
    if (message.type === 'marineSamrajHello') return { session: current.session };
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
    const encoded = JSON.stringify(reading); if (encoded.length > 16000) return null;
    await fetch(`${BASE}/event`, { method:'POST', headers:{'Content-Type':'text/plain'}, body:encoded });
    const fresh = await marineSamrajAuthorized(sender);
    return fresh?.session === current.session ? { session:fresh.session, action:fresh.action } : null;
  })().then(respond, () => respond(null));
  return true;
});
