const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const http=require('node:http');
const {spawn}=require('node:child_process');
const vm=require('node:vm');
const chromePath=process.env.AUTOPILOT_LIVE_CHROME_TEST;
for(const withVideos of [false,true]) test(withVideos?'changing video timestamps keep one page and all videos finish before trusted Next':'isolated Chrome scrolls the collector and sends trusted Next input',{skip:!chromePath,timeout:25000},async()=>{
 const folder=fs.mkdtempSync(path.join(os.tmpdir(),'dgs-trusted-'));
 let child,ws,command,reading,clicked=0;const titles=new Set();const pages=new Set();let completedVideos=null;const listeners=[];const calls=[];
 const wait=async(fn)=>{const until=Date.now()+18000;while(!fn()){if(Date.now()>until)throw Error('Browser fixture timed out');await new Promise(r=>setTimeout(r,40));}};
 const server=http.createServer(async(req,res)=>{
  let body='';for await(const c of req)body+=c;
  const route=req.url.split('?')[0];let result='';res.setHeader('Content-Type','text/html');
  if(route==='/collector.js'){res.setHeader('Content-Type','text/javascript');result=fs.readFileSync(path.join(__dirname,'../../jvmMain/resources/chrome-extension/dgs-elearning.js'),'utf8').replaceAll('https://dgsstcwlms.aduacademy.in',origin);}
  else if(route==='/blank')result='<html><body></body></html>';
  else if(route==='/reading'){
   reading=JSON.parse(body);titles.add(reading.title);if(!clicked)pages.add(reading.page);command={session:'s',page:reading.page,action:clicked?'pause':reading.atBottom?'next':'read',request:'r',pixelsPerSecond:1000,playbackSpeed:1,browserClicks:true};result=JSON.stringify(command);res.setHeader('Content-Type','application/json');
  }else if(route==='/click'){
   const message=JSON.parse(body);result=JSON.stringify(await context.dgsBrowserClick(message,sender));res.setHeader('Content-Type','application/json');
  }else if(route==='/trusted'){completedVideos=JSON.parse(body||'null');clicked++;result='ok';} else if(route==='/error'){console.error(body);result='ok';}
  else result=`<ul><li data-node="0"><span data-v="p1" data-sel>Introduction</span></li><li data-node="0"><span data-v="p2">Next topic</span></li></ul><iframe id="fraebookcontent" src="/blank" style="width:800px;height:550px"></iframe><script>
   const oldWarn=console.warn;console.warn=(...a)=>{oldWarn(...a);fetch('/error',{method:'POST',body:a.join(' ')})};window.onerror=(message,source,line)=>fetch('/error',{method:'POST',body:message+' '+line});const handlers=[];window.chrome={runtime:{id:'fixture',onMessage:{addListener:f=>handlers.push(f)},sendMessage:async m=>{if(m.type==='dgsBrowserClick')return(await fetch('/click',{method:'POST',body:JSON.stringify(m)})).json();return(await fetch('/reading',{method:'POST',body:JSON.stringify(m.reading)})).json()}}};
   window.resolveDgs=m=>new Promise(r=>{for(const f of handlers)f(m,{},r)});
   const frame=document.querySelector('iframe');frame.onload=()=>{const d=frame.contentDocument;d.body.innerHTML='<li id="ninext" data-show="1">Next</li><div id="divebook" style="height:400px;overflow:auto"><h3 style="display:none">Hidden quiz instructions</h3><h1>Article heading</h1><div style="height:800px">Read to the first video</div>${withVideos?'<video style="width:400px;height:160px"></video><div data-ctrl>00:00</div><div style="height:700px">Read to second video</div><video style="width:400px;height:160px"></video><div data-ctrl>00:00</div>':''}<div style="height:400px">Finish reading</div></div>';for(const v of d.querySelectorAll('video')){let finished=false,paused=true;Object.defineProperties(v,{ended:{get:()=>finished},paused:{get:()=>paused},readyState:{get:()=>4}});v.play=async()=>{if(!paused)return;paused=false;let ticks=0;const timer=setInterval(()=>{v.nextElementSibling.textContent='00:'+String(++ticks);if(ticks===5){finished=true;paused=true;clearInterval(timer)}},200);};}d.querySelector('#ninext').onclick=e=>{if(!e.isTrusted)return;document.querySelector('[data-sel]').removeAttribute('data-sel');document.querySelector('[data-v=p2]').setAttribute('data-sel','');fetch('/trusted',{method:'POST',body:JSON.stringify([...d.querySelectorAll('video')].map(v=>v.ended))});};};</script><script src="/collector.js"></script>`;
  res.end(result);
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const origin=`http://127.0.0.1:${server.address().port}`;
 let context;const sender={tab:{id:1},frameId:0,url:`${origin}/form/ebook`};
 try{
  child=spawn(chromePath,['--headless=new','--no-first-run',`--user-data-dir=${folder}`,'--remote-debugging-port=0',`${origin}/form/ebook`],{stdio:'ignore'});
  await wait(()=>fs.existsSync(path.join(folder,'DevToolsActivePort')));
  const port=fs.readFileSync(path.join(folder,'DevToolsActivePort'),'utf8').split('\n')[0];
  let target;
  for(let i=0;i<100&&!target;i++){target=(await(await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find(t=>t.url===`${origin}/form/ebook`);if(!target)await new Promise(r=>setTimeout(r,30));}
  assert.ok(target);ws=new WebSocket(target.webSocketDebuggerUrl);await new Promise((resolve,reject)=>{ws.onopen=resolve;ws.onerror=reject});
  let sequence=0;const pending=new Map();ws.onmessage=e=>{const m=JSON.parse(e.data);if(m.id){const p=pending.get(m.id);pending.delete(m.id);m.error?p?.reject(Error(m.error.message)):p?.resolve(m.result)}};
  const cdp=(method,params={})=>new Promise((resolve,reject)=>{const id=++sequence;pending.set(id,{resolve,reject});ws.send(JSON.stringify({id,method,params}))});
  context=vm.createContext({URL,Set,console,BASE:'fixture',get command(){return {enabled:true,dgs:command}},dgsBinding:{tab:1,session:'s'},dgsAuthorize:async()=>true,fetch:async()=>({ok:true}),chrome:{runtime:{onMessage:{addListener:f=>listeners.push(f)}},debugger:{attach:async()=>calls.push('attach'),detach:async()=>calls.push('detach'),sendCommand:async(_,method,params)=>{calls.push(method);return cdp(method,params)}},tabs:{sendMessage:async(_,message)=>{const response=await cdp('Runtime.evaluate',{expression:`window.resolveDgs(${JSON.stringify(message)})`,awaitPromise:true,returnByValue:true});return response.result.value}}}});
  vm.runInContext(fs.readFileSync(path.join(__dirname,'../../jvmMain/resources/chrome-extension/dgs-browser-clicks.js'),'utf8').replaceAll('https://dgsstcwlms.aduacademy.in',origin),context);
  try {await wait(()=>clicked===1&&calls.includes('detach'));} catch(e) {console.error({reading,command,calls});console.error(await cdp('Runtime.evaluate',{expression:'JSON.stringify({text:document.body.innerText,runtime:window.chrome.runtime,origin:location.origin,scripts:[...document.scripts].map(s=>s.src)})',returnByValue:true}));throw e;}
  assert.ok(reading.atBottom||clicked===1);assert.equal(clicked,1);assert.equal(pages.size,1,'Playback UI must not change page identity');if(withVideos)assert.deepEqual(completedVideos,[true,true]);assert.ok(titles.has('Introduction'));assert.equal(titles.has('Hidden quiz instructions'),false);assert.equal(calls.filter(c=>c==='Input.dispatchMouseEvent').length,3);assert.equal(calls.at(-1),'detach');
 }finally{ws?.close();child?.kill('SIGKILL');await new Promise(r=>server.close(r));fs.rmSync(folder,{recursive:true,force:true});}
});
