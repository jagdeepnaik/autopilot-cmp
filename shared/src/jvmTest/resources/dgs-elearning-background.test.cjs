const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');
const script=fs.readFileSync(path.join(__dirname,'../../jvmMain/resources/chrome-extension/dgs-elearning-background.js'),'utf8');
function fixture() {
  let listener; const reports=[];
  const context=vm.createContext({URL,Date,Map,command:{enabled:true,dgs:{session:'s',action:'read',playbackSpeed:2}},BASE:'http://localhost',activeTabId:async()=>1,chrome:{windows:{getLastFocused:async()=>({focused:true})},runtime:{onMessage:{addListener:f=>listener=f}}},fetch:async(_,options)=>{reports.push(JSON.parse(options.body));return {ok:true}}});
  vm.runInContext(script,context);
  const send=(m,s)=>new Promise(resolve=>{if(!listener(m,s,resolve))resolve(null)});
  const sender={tab:{id:1},frameId:0,url:'https://dgsstcwlms.aduacademy.in/form/ebook'};
  const tick={type:'dgsTick',source:'https://www.youtube.com/embed/example',reading:{page:'one',title:'Lesson',ready:true,media:'waiting'}};
  return {context,reports,send,sender,tick};
}
test('only chosen foreground DGS tab authorizes media; disabling clears it',async()=>{
 const f=fixture();
 assert.equal(await f.send(f.tick,{...f.sender,url:'https://example.com/form/ebook'}),null);
 assert.equal(await f.send(f.tick,{...f.sender,tab:{id:2}}),null);
 assert.equal(await f.send({type:'dgsMedia',state:'playing'},{...f.sender,frameId:2,url:'https://www.youtube.com/embed/example'}),null);
 assert.equal((await f.send(f.tick,f.sender)).session,'s');
 assert.equal((await f.send({type:'dgsMedia',state:'playing',speed:2},{...f.sender,frameId:2,url:'https://www.youtube.com/embed/example'})).play,true);
 assert.equal(await f.send({type:'dgsMedia',state:'ended'},{...f.sender,frameId:3,url:'https://www.youtube.com/embed/other'}),null);
 await f.send(f.tick,f.sender);assert.equal(f.reports.at(-1).media,'playing');assert.equal(f.reports.at(-1).speed,2);
 f.context.command.enabled=false;
 assert.equal(await f.send(f.tick,f.sender),null);
});
test('unfocused Chrome reports read-only status but refuses control',async()=>{
 const f=fixture();f.context.chrome.windows.getLastFocused=async()=>({focused:false});
 assert.equal(await f.send(f.tick,f.sender),null);assert.equal(f.reports.length,1);assert.equal(f.reports[0].focused,false);
});

test('course list may be read with app foreground but never returns an action',async()=>{
 const f=fixture();f.context.chrome.windows.getLastFocused=async()=>({focused:false});
 const response=await f.send({type:'dgsTick',reading:{page:'home',title:'DGS course list',ready:false,courses:[{id:'a',label:'Example course',available:true}]}},{...f.sender,url:'https://dgsstcwlms.aduacademy.in/form/studenthome'});
 assert.equal(response,null);assert.equal(f.reports.length,1);assert.equal(f.reports[0].focused,false);assert.equal(f.reports[0].courses[0].label,'Example course');
});

test('explicit course selection focuses the bound DGS tab once per user request',async()=>{
 const f=fixture();let active=1,focused=true,activations=0;
 f.context.activeTabId=async()=>active;f.context.chrome.windows.getLastFocused=async()=>({focused});
 f.context.chrome.tabs={update:async id=>{active=id;activations++}};
 f.context.chrome.windows.update=async()=>{focused=true};
 const home={type:'dgsTick',reading:{page:'home',title:'DGS course list',ready:false,courses:[{id:'a',label:'Example',available:true}]}};
 const sender={...f.sender,tab:{id:1,windowId:9},url:'https://dgsstcwlms.aduacademy.in/form/studenthome'};
 await f.send(home,sender);
 f.context.command.dgs={session:'s',action:'selectCourse',courseId:'a',courseLabel:'Example',page:'home',selectionRequest:1};
 active=2;focused=false;
 assert.equal((await f.send(home,sender)).action,'selectCourse');assert.equal(activations,1);
 await f.send(home,sender);assert.equal(activations,1);
 f.context.command.dgs.selectionRequest=2;await f.send(home,sender);assert.equal(activations,2);
});

test('SPA live home path authorizes course-list reading despite stale sender URL',async()=>{
 const f=fixture();f.context.chrome.windows.getLastFocused=async()=>({focused:false});
 const response=await f.send({type:'dgsTick',reading:{page:'home',path:'/form/studenthome',title:'DGS course list',ready:false,courses:[{id:'a',label:'Example',available:true}]}},f.sender);
 assert.equal(response,null);assert.equal(f.reports.length,1);assert.equal(f.reports[0].path,'/form/studenthome');
});

test('native clicks forward only exact foreground top-document app requests',async()=>{
 const f=fixture();await f.send(f.tick,f.sender);
 f.context.command.dgs={session:'s',action:'next',request:'r',page:'one',nativeClicks:true};
 const click={type:'dgsClick',session:'s',action:'next',request:'r',page:'one',path:'/form/ebook',x:200,y:300};
 assert.equal((await f.send(click,f.sender)).accepted,true);assert.equal(f.reports.at(-1).type,'dgsClick');
 const count=f.reports.length;
 assert.equal(await f.send({...click,request:'old'},f.sender),null);
 assert.equal(await f.send({...click,x:100001},f.sender),null);
 assert.equal(await f.send({...click,path:'/form/studenthome'},f.sender),null);
 assert.equal(await f.send(click,{...f.sender,frameId:2}),null);
 f.context.chrome.windows.getLastFocused=async()=>({focused:false});assert.equal(await f.send(click,f.sender),null);
 assert.equal(f.reports.length,count);
});
