// Independent DGS channel. No account/session APIs; only visible e-book controls and media.
let dgsBinding = null;
let dgsFocusedSelection = null;
const dgsMedia = new Map();
function dgsSource(value) {
  try { const u=new URL(value); return /(^|\.)youtube(?:-nocookie)?\.com$/.test(u.hostname) ? u.pathname.split('/').pop() : u.href; } catch { return null; }
}
async function dgsAuthorize(sender, readCourseList=false) {
  const current=command?.enabled ? command.dgs : null;
  if(!current) { dgsBinding=null; dgsMedia.clear(); return null; }
  if(dgsBinding?.session!==current.session) { dgsBinding={session:current.session,tab:null,at:0,source:null}; dgsMedia.clear(); }
  if(!sender.tab || sender.tab.id!==await activeTabId()) return null;
  const win=await chrome.windows.getLastFocused();
  if((!win.focused && !readCourseList) || (dgsBinding.tab!==null && dgsBinding.tab!==sender.tab.id)) return null;
  return current;
}
chrome.runtime.onMessage.addListener((message,sender,respond)=>{
  if(!['dgsTick','dgsMedia','dgsClick'].includes(message.type)) return false;
  (async()=>{
    const url=new URL(sender.url);
    // Reading the course list must work while the app popup is in front. Actions still require focus.
    const readCourseList=message.type==='dgsTick' && (sender.frameId??0)===0 && url.origin==='https://dgsstcwlms.aduacademy.in' && ['/form/studenthome','/form/ebook'].includes(message.reading?.path || url.pathname);
    const selection=command?.enabled ? command.dgs : null;
    if(readCourseList && selection?.action==='selectCourse' && dgsBinding?.session===selection.session && dgsBinding.tab===sender.tab?.id && selection.page===message.reading.page && message.reading.courses.some(c=>c.id===selection.courseId && c.label===selection.courseLabel && c.available)) {
      const key=selection.session+':'+selection.selectionRequest;
      if(dgsFocusedSelection!==key) {
        dgsFocusedSelection=key;
        await chrome.tabs.update(sender.tab.id,{active:true});
        await chrome.windows.update(sender.tab.windowId,{focused:true});
      }
    }
    const current=await dgsAuthorize(sender,readCourseList); if(!current) return null;
    if(message.type==='dgsClick') {
      if((sender.frameId??0)!==0 || url.origin!=='https://dgsstcwlms.aduacademy.in' || sender.tab.id!==dgsBinding.tab || message.session!==current.session || !current.nativeClicks || message.request!==current.request || message.action!==current.action || message.page!==current.page || !['selectCourse','next','previous','first'].includes(message.action) || message.path!==(message.action==='selectCourse'?'/form/studenthome':'/form/ebook') || !Number.isInteger(message.x) || !Number.isInteger(message.y) || Math.abs(message.x)>100000 || Math.abs(message.y)>100000) return null;
      await fetch(`${BASE}/event`,{method:'POST',headers:{'Content-Type':'text/plain'},body:JSON.stringify({type:'dgsClick',session:current.session,request:message.request,action:message.action,page:message.page,x:message.x,y:message.y})});
      return {accepted:true};
    }
    if(message.type==='dgsMedia') {
      if(!/(^|\.)youtube(?:-nocookie)?\.com$/.test(url.hostname) || !url.pathname.startsWith('/embed/') || Date.now()-dgsBinding.at>2500 || dgsBinding.tab!==sender.tab.id) return null;
      const source=dgsSource(sender.url);
      if(source!==dgsBinding.source) return null;
      dgsMedia.set(source,{state:message.state,speed:message.speed,at:Date.now()});
      return {session:current.session,play:current.action==='read',speed:current.playbackSpeed};
    }
    if((sender.frameId??0)!==0 || url.origin!=='https://dgsstcwlms.aduacademy.in') return null;
    const reading=message.reading;
    if(!reading || typeof reading.page!=='string' || reading.page.length>128 || typeof reading.title!=='string' || reading.title.length>240) return null;
    dgsBinding.tab=sender.tab.id; dgsBinding.at=Date.now(); dgsBinding.source=dgsSource(message.source);
    const media=dgsMedia.get(dgsBinding.source);
    // State is reported by the actual media frame; never assume a timer means a video ended.
    if(dgsBinding.source) {
      reading.media=media && Date.now()-media.at<2500 ? media.state : 'waiting';
      reading.speed=media && Date.now()-media.at<2500 ? media.speed : null;
    }
    await fetch(`${BASE}/event`,{method:'POST',headers:{'Content-Type':'text/plain'},body:JSON.stringify({...reading,type:'dgsReading',session:current.session,focused:(await chrome.windows.getLastFocused()).focused})});
    const fresh=await dgsAuthorize(sender);
    return fresh?.session===current.session ? {...fresh,media:reading.media} : null;
  })().then(respond,error=>{ console.warn("[AutoPilot DGS bridge]",String(error?.message || error)); respond(null); });
  return true;
});
