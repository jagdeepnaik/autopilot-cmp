(() => {
  if (window.__autoPilotOceanTG) return;
  window.__autoPilotOceanTG = true;
  const documentId = crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random();
  const ids = new WeakMap(); let nextId = 0, session = null, last = '', lastSentAt = 0, handled = new Set();
  const clean = value => (value || '').replace(/\s+/g, ' ').trim().slice(0, 2000);
  const visible = node => { const box=node?.getBoundingClientRect?.(), style=node && getComputedStyle(node); return !!box && box.width>0 && box.height>0 && style.display!=='none' && style.visibility!=='hidden'; };
  const id = node => { if (!ids.has(node)) ids.set(node, String(++nextId)); return ids.get(node); };
  const text = node => clean(node?.innerText || node?.textContent);
  const button = title => [...document.querySelectorAll('[title],button,a')].find(n => visible(n) && (n.getAttribute('title')===title || text(n)===title));
  function reading() {
    const profileName = [...document.querySelectorAll('h1,h2,h3')].map(text).find(value => /^[A-Z][A-Z .'-]{3,}$/.test(value) && !/^(WELCOME|SEARCH|SCHEDULE|CONTINUE|UPDATED|POPULAR|COMPLETION)$/i.test(value)) || null;
    if (window === window.top && location.pathname === '/OLP/home') return {type:'oceanReading',session,document:documentId,top:true,path:location.pathname,profileName};
    const top = location.pathname.startsWith('/OLP/elearning/details/');
    if (window === window.top && location.pathname.startsWith('/OLP/search')) {
      const links = [...document.querySelectorAll('a[href*="/OLP/elearning/details/"]')].filter(visible);
      const results = links.map(node => { const value=text(node); const code=(value.match(/(?:No\.\s*)?(\d{3,5})\b/)||[])[1] || ''; return {id:code,title:id(node),status:value}; });
      return {type:'oceanReading',session,document:documentId,top:true,path:location.pathname,profileName,results};
    }
    if (top && window === window.top) {
      const rows = [...document.querySelectorAll('[data-test="ChapletRow"]')].filter(visible);
      const chapters = rows.map(row => { const heading=row.querySelector('h3'); const status=text(row).match(/Completed|Not attempted|In progress/i); return {id:id(row),title:text(heading),status:status?.[0]||'Not attempted'}; });
      const body=text(document.body); const code=(body.match(/Activity number\s*(\d{3,5})/i)||[])[1] || null;
      return {type:'oceanReading',session,document:documentId,top:true,path:location.pathname,profileName,courseTitle:text(document.querySelector('h1')),courseCode:code,chapters};
    }
    const finish = [...document.querySelectorAll('body *')].some(n => visible(n) && /You have now finished this chapter/i.test(text(n)));
    const heading = text(document.querySelector('h1')) || null;
    const counter = text(document.body).match(/(\d+)\s*\/\s*(\d+)/);
    const pause = button('Pause');
    const options = [...document.querySelectorAll('input[type=radio], [role=radio], label, button')].filter(n => visible(n) && /^(?:[A-D][.)]\s*)?[^]{2,}$/.test(text(n))).slice(0,8);
    const questionNode = [...document.querySelectorAll('p,h2,h3,h4,legend')].find(n => visible(n) && /\?$/.test(text(n)));
    const feedback = [...document.querySelectorAll('p,div')].map(text).find(t => /That'?s right|correct answer|incorrect/i.test(t));
    const correct = feedback && /That'?s right|correct answer/i.test(feedback) ? options.find(n => text(n).length>2) : null;
    const practice = questionNode && options.length >= 2 ? {text:text(questionNode),options:options.map(n=>({id:id(n),text:text(n),selected:n.matches(':checked,[aria-checked=true]')})),feedback:feedback||null,correctAnswer:correct?text(correct):null} : null;
    return {type:'oceanReading',session,document:documentId,top:false,chapterTitle:heading,slide:counter?Number(counter[1]):null,slideCount:counter?Number(counter[2]):null,media:pause?'playing':'none',finished:finish,practice};
  }
  function execute(action) {
    if (!action || action.document!==documentId || handled.has(action.request)) return;
    handled.add(action.request); let node=null;
    if (action.kind==='searchCourse') { location.assign('/OLP/search/library?query='+encodeURIComponent(action.target)); return; }
    if (action.kind==='openCourse') node=[...document.querySelectorAll('a[href*="/OLP/elearning/details/"]')].find(n=>id(n)===action.target);
    else if (action.kind==='openChapter') node=[...document.querySelectorAll('[data-test="ChapletRow"]')].find(n=>id(n)===action.target);
    else if (action.kind==='answerPractice') node=[...document.querySelectorAll('input[type=radio], [role=radio], label, button')].find(n=>id(n)===action.target);
    else if (action.kind==='returnToMenu') node=[...document.querySelectorAll('button,a')].find(n=>visible(n)&&/Return to the Menu/i.test(text(n)));
    else if (action.kind==='next') node=button('Next page');
    const success=!!node; if(node) node.click();
    chrome.runtime.sendMessage({type:'oceanAction',session,document:documentId,request:action.request,success});
  }
  // Keep asking briefly even when the page is unchanged: the app can publish the next action
  // just after it handles this page's previous reading.
  async function tick() { const hello=await chrome.runtime.sendMessage({type:'oceanHello'}).catch(()=>null); if(!hello?.session) { session=null; return; } session=hello.session; const value=reading(), encoded=JSON.stringify(value), now=Date.now(); if(encoded!==last || now-lastSentAt>=1500) { last=encoded; lastSentAt=now; const reply=await chrome.runtime.sendMessage({type:'oceanReading',session,document:documentId,reading:value}).catch(()=>null); if(reply?.session===session) execute(reply.action); } }
  setInterval(tick, 1000); tick();
})();
