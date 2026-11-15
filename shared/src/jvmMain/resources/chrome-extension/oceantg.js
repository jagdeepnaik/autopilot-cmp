(() => {
  if (window.__autoPilotOceanTG) return;
  window.__autoPilotOceanTG = true;
  const documentId = crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random();
  const ids = new WeakMap(); let nextId = 0, session = null, last = '', handled = new Set();
  const clean = value => (value || '').replace(/\s+/g, ' ').trim().slice(0, 2000);
  const visible = node => { const box=node?.getBoundingClientRect?.(), style=node && getComputedStyle(node); return !!box && box.width>0 && box.height>0 && style.display!=='none' && style.visibility!=='hidden'; };
  const id = node => { if (!ids.has(node)) ids.set(node, String(++nextId)); return ids.get(node); };
  const text = node => clean(node?.innerText || node?.textContent);
  const button = title => [...document.querySelectorAll('[title],button,a')].find(n => visible(n) && (n.getAttribute('title')===title || text(n)===title));
  function reading() {
    const top = location.pathname.startsWith('/OLP/elearning/details/');
    if (top && window === window.top) {
      const heads = [...document.querySelectorAll('h3')].filter(visible);
      const chapters = heads.map(node => { let parent=node; for(let i=0;i<5&&parent;i++,parent=parent.parentElement) { const t=text(parent); if (/Completed|Not attempted|In progress/i.test(t)) return {id:id(node),title:text(node),status:(t.match(/Completed|Not attempted|In progress/i)||['Not attempted'])[0]}; } return {id:id(node),title:text(node),status:'Not attempted'}; });
      return {type:'oceanReading',session,document:documentId,top:true,courseTitle:text(document.querySelector('h1')),chapters};
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
    if (action.kind==='openChapter') node=[...document.querySelectorAll('h3')].find(n=>id(n)===action.target);
    else if (action.kind==='answerPractice') node=[...document.querySelectorAll('input[type=radio], [role=radio], label, button')].find(n=>id(n)===action.target);
    else if (action.kind==='returnToMenu') node=[...document.querySelectorAll('button,a')].find(n=>visible(n)&&/Return to the Menu/i.test(text(n)));
    else if (action.kind==='next') node=button('Next page');
    const success=!!node; if(node) node.click();
    chrome.runtime.sendMessage({type:'oceanAction',session,document:documentId,request:action.request,success});
  }
  async function tick() { const hello=await chrome.runtime.sendMessage({type:'oceanHello'}).catch(()=>null); if(!hello?.session) { session=null; return; } session=hello.session; const value=reading(), encoded=JSON.stringify(value); if(encoded!==last) { last=encoded; const reply=await chrome.runtime.sendMessage({type:'oceanReading',session,document:documentId,reading:value}).catch(()=>null); if(reply?.session===session) execute(reply.action); } }
  setInterval(tick, 1000); tick();
})();
