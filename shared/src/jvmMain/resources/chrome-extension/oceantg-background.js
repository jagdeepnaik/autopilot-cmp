let oceanSession = null;
let oceanTab = null;
async function oceanAuthorized(sender) {
  const current = command?.enabled ? command.oceanTG : null;
  if (!current) { oceanSession = null; oceanTab = null; return null; }
  const tab = sender.tab;
  if (!tab || tab.id !== await activeTabId()) return null;
  let url; try { url = new URL(sender.url); } catch { return null; }
  if (url.origin !== 'https://app1.oceantg.com') return null;
  const focused = await chrome.windows.getLastFocused();
  if (!focused.focused || (oceanTab !== null && oceanTab !== tab.id)) return null;
  oceanSession = current.session; oceanTab = tab.id;
  return current;
}
chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (!['oceanHello', 'oceanReading', 'oceanAction'].includes(message.type)) return false;
  (async () => {
    const current = await oceanAuthorized(sender); if (!current) return null;
    if (message.type === 'oceanHello') return { session: current.session };
    if (message.session !== current.session) return null;
    if (message.type === 'oceanAction') {
      const action = current.action;
      if (action && action.request === message.request && action.document === message.document) {
        await fetch(`${BASE}/event`, { method:'POST', headers:{'Content-Type':'text/plain'}, body:JSON.stringify({ type:'oceanAction', session:current.session, request:message.request, success:message.success===true }) });
      }
      return null;
    }
    const reading = message.reading;
    if (!reading || reading.document !== message.document) return null;
    const encoded = JSON.stringify(reading); if (encoded.length > 16000) return null;
    await fetch(`${BASE}/event`, { method:'POST', headers:{'Content-Type':'text/plain'}, body:encoded });
    const fresh = await oceanAuthorized(sender);
    return fresh?.session === current.session ? { session:fresh.session, action:fresh.action } : null;
  })().then(respond, () => respond(null));
  return true;
});
