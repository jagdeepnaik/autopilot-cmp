(() => {
  'use strict';
  const ORIGIN='https://dgsstcwlms.aduacademy.in';
  const youtube=/(^|\.)youtube(?:-nocookie)?\.com$/.test(location.hostname) && location.pathname.startsWith('/embed/');
  if(!youtube && (window!==window.top || location.origin!==ORIGIN)) return;
  let busy=false, session=null, lastPage=null, lastAction=null;
  let lastScroll=0, lastError=null, lastDiagnostic=null;
  function diagnostic(message) { if(message!==lastDiagnostic) {lastDiagnostic=message;console.info("[AutoPilot DGS]",message);} }
  let ended=new WeakSet();
  let lastCommand=null;
  let observedCourseName='';
  if(!youtube) document.addEventListener('click',event=>{
    const node=event.target.closest?.('span[data-crs="1"]');
    if(node && node.closest('fieldset').querySelector('legend[data-act="1"]')) observedCourseName=node.textContent.trim().slice(0,500);
  },true);
  const originalRates=new Map();
  function restoreRates() { for(const [v,rate] of originalRates) { try { if(v.isConnected) v.playbackRate=rate; } catch {} } originalRates.clear(); }
  const visible=n=>{ if(!n) return false; const r=n.getBoundingClientRect(),s=n.ownerDocument.defaultView.getComputedStyle(n); return r.width>0 && r.height>0 && s.display!=='none' && s.visibility!=='hidden'; };
  function state(video) { return !video ? 'waiting' : video.ended ? 'ended' : video.error ? 'blocked' : video.paused ? 'needsPlay' : video.readyState<3 ? 'buffering' : 'playing'; }
  async function play(video,speed) {
    if(!video || video.ended) return;
    // Do not seek or adjust duration. Confirm the actual rate in the next reading.
    if(!originalRates.has(video)) originalRates.set(video,video.playbackRate);
    if(video.playbackRate!==speed) { try { video.playbackRate=speed; } catch {} }
    if(video.paused) { try { await video.play(); } catch {} }
  }
  async function clickControl(node,command,frame=null) {
    if(command?.action==='selectCourse') observedCourseName=command.courseLabel || '';
    if(command?.browserClicks) {
      await chrome.runtime.sendMessage({type:'dgsBrowserClick',session:command.session,request:command.request,page:command.page,action:command.action,courseId:command.courseId,courseLabel:command.courseLabel,path:location.pathname,viewportHeight:window.innerHeight});
      return;
    }
    if(command?.nativeClicks) {
      if(!node?.isConnected || (frame && !frame.isConnected)) return;
      const fresh=collect();
      if(fresh.reading.page!==command.page || (command.action!=='selectCourse' && !fresh.reading.ready)) return;
      if([...document.querySelectorAll('[role="dialog"],dialog[open],#frapopup,#frareport')].some(visible)) return;
      node.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});
      const r=node.getBoundingClientRect(), f=frame?.getBoundingClientRect();
      const clientX=r.left+r.width/2+(f?.left||0)+(frame?.clientLeft||0);
      const clientY=r.top+r.height/2+(f?.top||0)+(frame?.clientTop||0);
      if(clientX<0 || clientY<0 || clientX>window.innerWidth || clientY>window.innerHeight) return;
      const x=Math.round(window.screenX+(window.outerWidth-window.innerWidth)/2+clientX);
      const y=Math.round(window.screenY+window.outerHeight-window.innerHeight+clientY);
      await chrome.runtime.sendMessage({type:'dgsClick',session:command.session,request:command.request,page:command.page,action:command.action,courseId:command.courseId,courseLabel:command.courseLabel,path:location.pathname,x,y});
      return;
    }
    // DGS controls can listen to press/release as well as click. Follow the ordinary mouse sequence.
    const win=node.ownerDocument.defaultView;
    const rect=node.getBoundingClientRect();
    const details={bubbles:true,cancelable:true,view:win,button:0,detail:1,clientX:rect.left+rect.width/2,clientY:rect.top+rect.height/2,pointerType:"mouse"};
    for(const type of ['pointerdown','mousedown','pointerup','mouseup']) {
      if(!node.isConnected) return;
      const EventType=type.startsWith('pointer') ? win.PointerEvent : win.MouseEvent;
      node.dispatchEvent(new EventType(type,{...details,buttons:type.endsWith('down')?1:0}));
    }
    if(node.isConnected) node.dispatchEvent(new win.MouseEvent("click",{...details,buttons:0}));
  }
  function enabled(n) {
    if(!n) return null;
    if(n.hidden || n.getAttribute('data-show')==='0' || n.matches('[disabled],[aria-disabled="true"],.disabled')) return false;
    if(!visible(n)) return null;
    return !(n.hidden || n.getAttribute('data-show')==='0' || n.matches('[disabled],[aria-disabled="true"],.disabled') || n.ownerDocument.defaultView.getComputedStyle(n).pointerEvents==='none');
  }
  function hash(text) { let h=0; for(let i=0;i<text.length;i++) h=(h*31+text.charCodeAt(i))|0; return String(h); }
  function titleFallback(root) {const copy=root.cloneNode(true);copy.querySelectorAll('video,iframe,object,embed,[data-ctrl]').forEach(n=>n.remove());return copy.textContent;}
  function collect() {
    if(location.pathname==='/form/studenthome') {
      const courses=[...document.querySelectorAll('fieldset[data-bc] div[data-bcc][data-scc]')].slice(0,100).map(n=>({
        id:n.getAttribute('data-scc'),label:(n.querySelector('span[data-crs]')?.textContent || '').trim().slice(0,240),
        code:(n.closest('fieldset').querySelector('legend [data-des]')?.textContent || '').trim().slice(0,60),
        available:n.closest('fieldset').querySelector('legend')?.getAttribute('data-act')==='1' && n.querySelector('span[data-crs]')?.getAttribute('data-crs')==='1',
      }));
      return {reading:{page:hash(JSON.stringify(courses)),title:'DGS course list',ready:false,courses}};
    }
    if(location.pathname!=='/form/ebook') return {reading:{page:'',title:'',ready:false,problem:'Sign in and select your course on the DGS home page, then open E-BOOK. Assessments remain manual.'}};
    // The site writes new content into a blank iframe; its src and outer URL never identify a page.
    let doc;
    const ebook=document.querySelector('#fraebookcontent');
    if(!visible(ebook)) return {reading:{page:'',title:'',ready:false,problem:'Open E-BOOK to continue. Assessments remain manual.'}};
    try { doc=ebook?.contentDocument; } catch {}
    const root=doc?.querySelector('#divebook');
    if(!root || !visible(root)) return {reading:{page:'',title:'',ready:false,problem:'Waiting for the e-book. Complete any login or verification prompt yourself.'}};
    const overlay=[...document.querySelectorAll('[role="dialog"],dialog[open],#frapopup,#frareport')].some(visible);
    const next=doc.querySelector('#ninext'), previous=doc.querySelector('#niprevious');
    const text=root.innerText || '';
    const accountName=[...(document.querySelector('[data-username]')?.childNodes || [])].filter(n=>n.nodeType===3).map(n=>n.textContent).join(' ').trim().replace(/\s+/g,' ').slice(0,200);
    let courseId='';try {courseId=new URL(doc.URL).searchParams.get('pcc') || new URL(ebook.src).searchParams.get('pcc') || '';} catch {}
    const videos=[...root.querySelectorAll('video')].map(v=>({page:'',id:hash(v.getAttribute('src') || v.currentSrc || v.id),position:Number.isFinite(v.currentTime)?v.currentTime:0,duration:Number.isFinite(v.duration)?v.duration:null,ended:v.ended,speed:v.playbackRate}));
    const outlinePages=[...document.querySelectorAll('li[data-node="0"] > span[data-v]')];
    const selectedPage=outlinePages.find(n=>n.hasAttribute('data-sel'));
    const outlineIndex=selectedPage ? outlinePages.indexOf(selectedPage) : -1;
    // Player controls contain changing timestamps; they must never identify a new lesson.
    const identity=selectedPage?.getAttribute('data-v') || [...root.querySelectorAll('h1,h2,h3')].filter(visible).map(n=>n.textContent).join('|') || titleFallback(root);
    const page=hash(identity+'|'+[...root.querySelectorAll('iframe,video,source')].map(n=>n.getAttribute('src')).join('|'));
    const outline=[...document.querySelectorAll('span[title]')].map(n=>n.getAttribute('title')).filter(Boolean).join('|');
    const profile=(document.querySelector('header,[role="banner"]')?.innerText || '').replace(/Timeout\s+in\s+[^\n]+/gi,'');
    const course=outline || profile ? hash(outline+'|'+profile) : '';
    const title=(selectedPage?.getAttribute('title') || selectedPage?.textContent?.trim() || [...root.querySelectorAll('h1,h2,h3')].find(visible)?.textContent || text.split('\n').find(t=>t.trim()) || 'E-book page').trim().slice(0,240);
    const rect=root.getBoundingClientRect();
    const inView=n=> {const r=n.getBoundingClientRect();return visible(n)&&r.bottom>rect.top&&r.top<rect.bottom&&r.right>rect.left&&r.left<rect.right;};
    const pendingMedia=[...root.querySelectorAll('video,iframe,object,embed')].filter(n=>!ended.has(n) && !(n.tagName==='VIDEO' && n.ended));
    const atBottom=root.scrollTop+root.clientHeight>=root.scrollHeight-3;
    // Catch a player scrolled past manually or during layout changes, and never leave one unfinished.
    const media=pendingMedia.find(inView) || pendingMedia.find(n=>n.getBoundingClientRect().bottom<=rect.top) || (atBottom?pendingMedia[0]:null);
    let source=null, video=null, problem=overlay?'Close the DGS dialog or finish its verification to continue.':null;
    if(media && !visible(media)) problem='A video on this page is hidden. Open its player in Chrome before continuing.';
    if(media?.tagName==='VIDEO') video=media;
    else if(media?.tagName==='IFRAME') {
      try { video=media.contentDocument?.querySelector('video'); } catch {}
      if(!video) {
        const src=media.getAttribute('src') || '';
        if(/https:\/\/(?:www\.)?youtube(?:-nocookie)?\.com\/embed\//.test(src)) source=src;
        else problem='An embedded player needs manual review. Play it in Chrome; this routine cannot confirm its end.';
      }
    } else if(media) problem='This media player needs manual review; automatic scrolling is paused.';
    if(video?.ended) ended.add(media);
    return {doc,root,ebook,next,previous,media,video,source,reading:{page,title,course,accountName,courseId,courseName:observedCourseName,scroll:root.scrollTop,videos,ready:!problem,atBottom:atBottom && pendingMedia.length===0,next:outlineIndex>=0 ? outlineIndex<outlinePages.length-1 : enabled(next),previous:outlineIndex>=0 ? outlineIndex>0 : enabled(previous),first:outlinePages.length>0,media:source?'waiting':video?state(video):'none',speed:video?.playbackRate??null,problem}};
  }
  async function tick() {
    if(busy || !chrome.runtime?.id) return;
    busy=true;
    try {
      if(youtube) {
        const video=document.querySelector('video');
        const cmd=await chrome.runtime.sendMessage({type:'dgsMedia',state:state(video),speed:video?.playbackRate??null});
        if(!cmd) restoreRates();
        if(cmd?.play) {
          if(!video) document.querySelector('.ytp-large-play-button')?.click();
          else await play(video,cmd.speed);
        }
        return;
      }
      const value=collect();
      const cmd=await chrome.runtime.sendMessage({type:'dgsTick',reading:{...value.reading,path:location.pathname},source:value.source});
      lastCommand=cmd;
      diagnostic(cmd ? 'Command: '+cmd.action : 'Waiting for an authorized app command');
      if(cmd?.action==='selectCourse' && location.pathname==='/form/studenthome' && document.visibilityState==='visible' && cmd.page===value.reading.page) {
        const choice=value.reading.courses?.find(c=>c.id===cmd.courseId && c.label===cmd.courseLabel && c.available);
        const key=cmd.session+':'+cmd.selectionRequest+':'+cmd.courseId+':'+cmd.page;
        if(choice && lastAction!==key) {
          // Program legends are headings. The actual e-book opener is the course-name span.
          const row=[...document.querySelectorAll('fieldset[data-bc] div[data-bcc][data-scc]')].find(n=>n.getAttribute('data-scc')===choice.id);
          const target=row?.closest('fieldset').querySelector('legend[data-act="1"]') ? row.querySelector('span[data-crs="1"]') : null;
          if(target && visible(target) && target.textContent.trim()===choice.label) {lastAction=key;diagnostic('Clicking selected course link');await clickControl(target,cmd);} else diagnostic('Selected course link is no longer available');
        }
        return;
      }
      if(!cmd || !value.reading.ready || document.visibilityState!=='visible') { restoreRates(); lastScroll=0; return; }
      if(session!==cmd.session || lastPage!==value.reading.page) {session=cmd.session;lastPage=value.reading.page;lastAction=null;lastScroll=0;ended=new WeakSet();restoreRates();value.root.scrollTop=0;return;}
      if(cmd.page!==value.reading.page) return;
      if(value.source && cmd.media==='ended') ended.add(value.media);
      if(cmd.action==='read') {
        if(value.media) value.media.scrollIntoView({block:'nearest',inline:'nearest',behavior:'instant'});
        if(value.video && !value.video.ended) { await play(value.video,cmd.playbackSpeed); lastScroll=0; return; }
        if(value.media && !ended.has(value.media)) { lastScroll=0; return; }
        const now=performance.now(), delta=lastScroll?Math.min(700,now-lastScroll):0; lastScroll=now;
        value.root.scrollTop+=Math.max(0,delta)*Math.min(400,Math.max(10,cmd.pixelsPerSecond))/1000;
      } else {
        lastScroll=0;
        if(['next','previous','first'].includes(cmd.action) && lastAction!==cmd.request && value.reading.atBottom) {
          const target=cmd.action==='first' ? document.querySelector('li[data-node="0"] > span[data-v]') : cmd.action==='next'?value.next:value.previous;
          if(target && (cmd.action==='first' || enabled(target)===true)) {lastAction=cmd.request;await clickControl(target,cmd,cmd.action==='first'?null:value.ebook);}
        }
      }
    } catch(error) { const message=String(error?.message || error); if(message!==lastError) { lastError=message; console.warn("[AutoPilot DGS]",message); } } finally {busy=false;}
  }
  chrome.runtime.onMessage?.addListener((message,_sender,respond)=>{
    if(message.type!=='dgsResolveClick' || youtube || window!==window.top) return false;
    const cmd=lastCommand;
    if(message.action==='selectCourse') {
      if(!cmd?.browserClicks || cmd.action!=='selectCourse' || message.session!==cmd.session || message.request!==cmd.request || message.page!==cmd.page || message.courseId!==cmd.courseId || message.courseLabel!==cmd.courseLabel || location.pathname!=='/form/studenthome' || document.visibilityState!=='visible') {respond(null);return false;}
      const value=collect();
      const choice=value.reading.courses?.find(c=>c.available && c.id===cmd.courseId && c.label===cmd.courseLabel);
      const row=[...document.querySelectorAll('fieldset[data-bc] div[data-bcc][data-scc]')].find(n=>n.getAttribute('data-scc')===choice?.id);
      const node=row?.closest('fieldset').querySelector('legend[data-act="1"]') ? row.querySelector('span[data-crs="1"]') : null;
      if(value.reading.page!==cmd.page || !choice || !node || !visible(node) || node.textContent.trim()!==choice.label) {respond(null);return false;}
      node.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});
      const r=node.getBoundingClientRect();
      respond({session:cmd.session,request:cmd.request,page:cmd.page,x:r.left+r.width/2,y:r.top+r.height/2,width:window.innerWidth,height:window.innerHeight});
      return false;
    }
    if(!cmd?.browserClicks || message.session!==cmd.session || message.request!==cmd.request || message.page!==cmd.page || message.action!==cmd.action || !['next','first'].includes(message.action) || location.pathname!=='/form/ebook' || document.visibilityState!=='visible') {respond(null);return false;}
    const value=collect();
    if(!value.reading.ready || value.reading.page!==message.page || !['none','ended'].includes(value.reading.media)) {respond(null);return false;}
    if(!value.reading.atBottom) {
      const gap=value.root.scrollHeight-value.root.scrollTop-value.root.clientHeight;
      const resize=Math.max(0,(message.viewportHeight||0)-window.innerHeight);
      if(gap>0 && gap<=resize+3) value.root.scrollTop=value.root.scrollHeight-value.root.clientHeight;
      else {respond(null);return false;}
    }
    const first=message.action==='first';
    const node=first?document.querySelector('li[data-node="0"] > span[data-v]'):value.next;
    if(!node || !visible(node) || (!first && enabled(node)!==true)) {respond(null);return false;}
    node.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});
    const r=node.getBoundingClientRect(),frame=first?null:value.ebook,f=frame?.getBoundingClientRect();
    const x=r.left+r.width/2+(f?.left||0)+(frame?.clientLeft||0),y=r.top+r.height/2+(f?.top||0)+(frame?.clientTop||0);
    respond({session:message.session,request:message.request,page:message.page,x,y,width:window.innerWidth,height:window.innerHeight});
    return false;
  });
  setInterval(tick,500);
})();
