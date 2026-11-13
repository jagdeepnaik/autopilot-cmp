const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../../jvmMain/resources/chrome-extension/dgs-browser-clicks.js'),'utf8');
function fixture() {
 let listener;const calls=[],reports=[];
 const context={URL,Set,console,focused:true,targetUrl:'https://dgsstcwlms.aduacademy.in/form/ebook',
 command:{enabled:true,dgs:{session:'s',browserClicks:true,action:'next',request:'r',page:'p'}},
 dgsBinding:{session:'s',tab:1},BASE:'http://localhost',
 fetch:async(_,o)=>{reports.push(JSON.parse(o.body));return {ok:true}},
 chrome:{runtime:{onMessage:{addListener:f=>listener=f}},tabs:{sendMessage:async()=>({session:'s',request:'r',page:'p',x:30,y:40,width:800,height:600})},debugger:{
 attach:async target=>{calls.push(['attach',target]);},detach:async target=>calls.push(['detach',target]),
 sendCommand:async(target,method,params)=>{calls.push([method,params]);if(method==='Target.getTargetInfo')return {targetInfo:{url:context.targetUrl}};return {}}}},
 dgsAuthorize:async()=>context.focused&&context.command.enabled?context.command.dgs:null};
 vm.runInNewContext(source,context);
 const sender={tab:{id:1},frameId:0,url:'https://dgsstcwlms.aduacademy.in/form/ebook'};
 const message={type:'dgsBrowserClick',session:'s',request:'r',page:'p',action:'next',path:'/form/ebook',viewportHeight:600};
 const send=(m=message,s=sender)=>new Promise(resolve=>listener(m,s,resolve));
 return {context,calls,reports,sender,message,send};
}
test('exact pending DGS request clicks once and detaches immediately',async()=>{
 const f=fixture();assert.equal((await f.send()).accepted,true);
 assert.deepEqual(f.calls.filter(c=>c[0]==='Input.dispatchMouseEvent').map(c=>c[1].type),['mouseMoved','mousePressed','mouseReleased']);
 assert.equal(f.calls.at(-1)[0],'detach');assert.equal(f.reports[0].success,true);
 const n=f.calls.length;assert.equal(await f.send(),null);assert.equal(f.calls.length,n);
 assert.ok(f.calls.every(c=>['attach','detach','Input.dispatchMouseEvent','Target.getTargetInfo'].includes(c[0])));
});
test('wrong host frame tab session page request action and background cannot attach',async()=>{
 const f=fixture();
 for(const change of [{request:'old'},{session:'old'},{page:'other'},{action:'selectCourse'},{path:'/form/studenthome'}]) assert.equal(await f.send({...f.message,...change}),null);
 for(const change of [{url:'https://example.com/form/ebook'},{frameId:2},{tab:{id:2}}]) assert.equal(await f.send(f.message,{...f.sender,...change}),null);
 f.context.focused=false;assert.equal(await f.send(),null);assert.equal(f.calls.length,0);
});
test('cached sender home URL is permitted only after verifying actual e-book URL',async()=>{
 const f=fixture();assert.equal((await f.send(f.message,{...f.sender,url:'https://dgsstcwlms.aduacademy.in/form/studenthome'})).accepted,true);
});
test('target navigating away never receives input and still detaches',async()=>{
 const f=fixture();f.context.targetUrl='https://example.com/';
 assert.equal((await f.send()).accepted,false);assert.ok(!f.calls.some(c=>c[0]==='Input.dispatchMouseEvent'));assert.equal(f.calls.at(-1)[0],'detach');
});
test('stop between move and press cancels without pressing',async()=>{
 const f=fixture(),original=f.context.chrome.debugger.sendCommand;
 f.context.chrome.debugger.sendCommand=async(...args)=>{const result=await original(...args);if(args[2]?.type==='mouseMoved')f.context.command.enabled=false;return result;};
 assert.equal((await f.send()).accepted,false);assert.ok(!f.calls.some(c=>c[1]?.type==='mousePressed'));assert.equal(f.calls.at(-1)[0],'detach');
});
test('conflicting debugger reports busy and is never detached by this extension',async()=>{
 const f=fixture();f.context.chrome.debugger.attach=async()=>{throw Error('Another debugger is already attached')};
 assert.equal((await f.send()).accepted,false);assert.equal(f.reports[0].problem,'busy');assert.ok(!f.calls.some(c=>c[0]==='detach'));
});
test('stale element coordinates are refused and permission absence is reported',async()=>{
 const f=fixture();f.context.chrome.tabs.sendMessage=async()=>({session:'s',request:'r',page:'old',x:0,y:0,width:800,height:600});
 assert.equal((await f.send()).accepted,false);assert.ok(!f.calls.some(c=>c[0]==='Input.dispatchMouseEvent'));
 const missing=fixture();missing.context.chrome.debugger=undefined;assert.equal((await missing.send()).accepted,false);assert.equal(missing.reports[0].problem,'permission');
});

test('course click requires exact selected course and home route',async()=>{
 const f=fixture();Object.assign(f.context.command.dgs,{action:'selectCourse',courseId:'available',courseLabel:'Available course'});
 f.context.targetUrl='https://dgsstcwlms.aduacademy.in/form/studenthome';
 const m={...f.message,action:'selectCourse',path:'/form/studenthome',courseId:'available',courseLabel:'Available course'};
 assert.equal(await f.send({...m,courseId:'unavailable'}),null);
 assert.equal(await f.send({...m,courseLabel:'Another course'}),null);
 assert.equal(await f.send({...m,path:'/form/ebook'}),null);
 assert.equal((await f.send(m)).accepted,true);
 assert.equal(f.reports[0].success,true);assert.equal(f.calls.at(-1)[0],'detach');
});
