const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.resolve(__dirname, '../../../shared/src/jvmMain/resources/chrome-extension/marinesamraj-background.js'), 'utf8');
function fixture(focused = true, includeBookmarks = false, startFromHome = false) {
  let listener;
  const events = [];
  const context = {
    URL, Date, JSON,
    BASE:'http://127.0.0.1:47613', command:{enabled:true,marineSamraj:{session:'fixture',clickDelayMillis:4000,includeBookmarks,startFromHome}},
    activeTabId:async()=>7,
    chrome:{tabs:{update:async()=>{},query:async()=>[{id:7}]},windows:{getLastFocused:async()=>({focused})},runtime:{onMessage:{addListener:fn=>{listener=fn;}}}},
    fetch:async(url,options)=>{events.push(JSON.parse(options.body));return {ok:true};},
  };
  vm.runInNewContext(source, context);
  const sender = {tab:{id:7,windowId:1},url:'https://www.marinesamraj.com/EP_Home'};
  const send = (message, from=sender) => new Promise(resolve=>listener(message,from,resolve));
  return {events,send,sender};
}
test('same-origin SPA reading is accepted despite cached sender path and preserves long answer',async()=>{
  const f=fixture();
  const hello=await f.send({type:'marineSamrajHello'});
  assert.equal(hello.session,'fixture');
  assert.equal(hello.clickDelayMillis,4000);
  const reading={type:'marineSamrajReading',session:'fixture',document:'doc:2',url:'https://www.marinesamraj.com/exam-prep/question/one',answer:'Answer '.repeat(6000)};
  await f.send({type:'marineSamrajReading',session:'fixture',document:'doc:2',reading});
  assert.ok(f.events.length>16);
  assert.ok(f.events.every((event,index)=>event.type==='marineSamrajChunk'&&event.index===index&&event.chunk.length<=1000&&Buffer.byteLength(JSON.stringify(event))<16384));
  assert.deepEqual(JSON.parse(f.events.map(event=>event.chunk).join('')),reading);
});
test('a reading from a different origin is rejected',async()=>{
  const f=fixture();
  await f.send({type:'marineSamrajReading',session:'fixture',document:'doc',reading:{session:'fixture',document:'doc',url:'https://evil.example/exam-prep/question/one'}});
  assert.equal(f.events.length,0);
});
test('inactive tab receives no session or actions',async()=>{
  const f=fixture();
  await f.send({type:'marineSamrajHello'});
  const reply=await f.send({type:'marineSamrajHello'},{...f.sender,tab:{id:8,windowId:1}});
  assert.equal(reply.session,undefined);
  assert.match(reply.reason,/bound to another tab/);
  assert.equal(f.events.length,0);
});
test('selected public collection tab remains usable while another application is foreground',async()=>{
  const f=fixture(false);
  assert.equal((await f.send({type:'marineSamrajHello'})).session,'fixture');
});
test('private pages on the same origin cannot send collection readings',async()=>{
  const f=fixture();
  await f.send({type:'marineSamrajReading',session:'fixture',document:'doc',reading:{session:'fixture',document:'doc',url:'https://www.marinesamraj.com/user/profile'}});
  assert.equal(f.events.length,0);
});
test('bookmarks require explicit routine consent',async()=>{
  const f=fixture();
  const bookmark={session:'fixture',document:'bookmark',url:'https://www.marinesamraj.com/exam-prep/subject/example?tabID=BOOKMARKS'};
  await f.send({type:'marineSamrajReading',session:'fixture',document:'bookmark',reading:bookmark});
  assert.equal(f.events.length,0);
  const permitted=fixture(true,true);
  const hello=await permitted.send({type:'marineSamrajHello'});
  assert.equal(hello.includeBookmarks,true);
  await permitted.send({type:'marineSamrajReading',session:'fixture',document:'bookmark',reading:bookmark});
  assert.ok(permitted.events.length>0);
});

test('a home-first run may leave a bookmark without collecting it',async()=>{
  const f=fixture(false,false,true);
  const bookmark={session:'fixture',document:'bookmark',url:'https://www.marinesamraj.com/exam-prep/subject/one?tabID=BOOKMARKS'};
  const hello=await f.send({type:'marineSamrajHello'});
  assert.equal(hello.session,'fixture');
  await f.send({type:'marineSamrajReading',session:'fixture',document:'bookmark',reading:bookmark});
  assert.equal(f.events.length,1);
});
