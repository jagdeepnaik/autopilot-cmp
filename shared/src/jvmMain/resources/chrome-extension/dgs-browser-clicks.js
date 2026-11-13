// Chrome input for the exact DGS reading request. No page evaluation, network capture or other tabs.
const DGS_CLICK_ORIGIN = 'https://dgsstcwlms.aduacademy.in';
const dgsBrowserRequests = new Set();
let dgsBrowserSession = null;
let dgsBrowserBusy = false;

function dgsReadingUrl(value,action) {
  try { const url=new URL(value); return url.origin===DGS_CLICK_ORIGIN && url.pathname===(action==='selectCourse'?'/form/studenthome':'/form/ebook'); } catch { return false; }
}
function dgsSameOrigin(value) { try {return new URL(value).origin===DGS_CLICK_ORIGIN;} catch {return false;} }
function dgsBrowserRequestAllowed(message,sender) {
  const current=command?.enabled ? command.dgs : null;
  return current?.browserClicks===true && (sender.frameId??0)===0 && sender.tab?.id===dgsBinding?.tab &&
    dgsBinding.session===current.session && dgsSameOrigin(sender.url) &&
    message.path===(message.action==='selectCourse'?'/form/studenthome':'/form/ebook') && ['selectCourse','next','first'].includes(message.action) &&
    (message.action!=='selectCourse' || (typeof current.courseId==='string' && current.courseId===message.courseId && current.courseLabel===message.courseLabel)) &&
    message.session===current.session && message.page===current.page && message.action===current.action &&
    typeof current.request==='string' && message.request===current.request;
}
async function dgsBrowserClick(message,sender) {
  if(!dgsBrowserRequestAllowed(message,sender) || !await dgsAuthorize(sender)) return null;
  if(dgsBrowserSession!==message.session) { dgsBrowserSession=message.session; dgsBrowserRequests.clear(); }
  if(dgsBrowserRequests.has(message.request) || dgsBrowserBusy) return null;
  dgsBrowserRequests.add(message.request);
  dgsBrowserBusy=true;
  const target={tabId:sender.tab.id};
  let attached=false,pressed=false,success=false,problem='failed';
  let point=null;
  const fresh=async()=>{
    if(!dgsBrowserRequestAllowed(message,sender) || !await dgsAuthorize(sender)) return false;
    const info=await chrome.debugger.sendCommand(target,'Target.getTargetInfo');
    return dgsReadingUrl(info?.targetInfo?.url,message.action);
  };
  try {
    if(!chrome.debugger?.attach) {problem='permission';return {accepted:false};}
    await chrome.debugger.attach(target,'1.3'); attached=true;
    if(!await fresh()) {problem='changed';return {accepted:false};}
    // Resolve again after Chrome's debugging indicator resizes the viewport.
    point=await chrome.tabs.sendMessage(target.tabId,{type:'dgsResolveClick',session:message.session,request:message.request,page:message.page,action:message.action,courseId:message.courseId,courseLabel:message.courseLabel,viewportHeight:message.viewportHeight},{frameId:0});
    if(!point || point.session!==message.session || point.request!==message.request || point.page!==message.page ||
      ![point.x,point.y,point.width,point.height].every(Number.isFinite) || point.width<=0 || point.height<=0 ||
      point.x<0 || point.y<0 || point.x>=point.width || point.y>=point.height || !await fresh()) {
      problem='changed';return {accepted:false};
    }
    await chrome.debugger.sendCommand(target,'Input.dispatchMouseEvent',{type:'mouseMoved',x:point.x,y:point.y,button:'none'});
    if(!await fresh()) {problem='changed';return {accepted:false};}
    await chrome.debugger.sendCommand(target,'Input.dispatchMouseEvent',{type:'mousePressed',x:point.x,y:point.y,button:'left',clickCount:1}); pressed=true;
    if(!await fresh()) {problem='changed';return {accepted:false};}
    await chrome.debugger.sendCommand(target,'Input.dispatchMouseEvent',{type:'mouseReleased',x:point.x,y:point.y,button:'left',clickCount:1}); pressed=false;
    success=true;problem=null;
    return {accepted:true};
  } catch(error) {
    const detail=String(error?.message || error);
    problem=/another debugger|already attached/i.test(detail)?'busy':/permission|not allowed|access denied/i.test(detail)?'permission':'failed';
    console.warn('[AutoPilot DGS click]',detail);
    return {accepted:false};
  } finally {
    if(pressed) {try {await chrome.debugger.sendCommand(target,'Input.dispatchMouseEvent',{type:'mouseReleased',x:-1,y:-1,button:'left',clickCount:1});} catch {}}
    if(attached) {try {await chrome.debugger.detach(target);} catch {}}
    dgsBrowserBusy=false;
    try {await fetch(`${BASE}/event`,{method:'POST',headers:{'Content-Type':'text/plain'},body:JSON.stringify({type:'dgsBrowserAction',session:message.session,request:message.request,success,problem})});} catch {}
  }
}
chrome.runtime.onMessage.addListener((message,sender,respond)=>{
  if(message.type!=='dgsBrowserClick') return false;
  dgsBrowserClick(message,sender).then(respond,()=>respond(null));
  return true;
});
