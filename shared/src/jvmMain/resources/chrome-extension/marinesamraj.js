(() => {
  if (window.__autoPilotMarineSamraj) return;
  window.__autoPilotMarineSamraj = true;
  const documentId = crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random();
  let session = null, last = '', lastSentAt = 0; const handled = new Set();
  const clean = value => (value || '').replace(/\s+/g, ' ').trim().slice(0, 12000);
  const visible = node => { const box=node?.getBoundingClientRect?.(), style=node && getComputedStyle(node); return !!box && box.width>0 && box.height>0 && style.display!=='none' && style.visibility!=='hidden'; };
  const text = node => clean(node?.innerText || node?.textContent);
  const permitted = value => { try { const url=new URL(value,location.href), path=url.pathname.toLowerCase(); return ['https://marinesamraj.com','https://www.marinesamraj.com'].includes(url.origin) && (path.startsWith('/exam-prep/') || path==='/ep_home') && !['/user/','/admin/','/notification/','/review-pipeline/','/support-the-creators/'].some(part=>path.startsWith(part)); } catch { return false; } };
  function reading() {
    const links = [...document.querySelectorAll('a[href]')].filter(visible).map(node => new URL(node.href, location.href).href).filter(permitted).slice(0, 250);
    const headings = [...document.querySelectorAll('h1,h2,h3,h4,p')].filter(visible).map(text);
    const question = headings.find(value => value.length >= 12 && !/^(question|answer|exam preparation|marine samraj)$/i.test(value)) || null;
    const answerHeading = [...document.querySelectorAll('h1,h2,h3,h4')].find(node => /^answer$/i.test(text(node)));
    const body = text(document.querySelector('main') || document.body);
    const afterAnswer = answerHeading ? body.slice(body.indexOf(text(answerHeading)) + text(answerHeading).length) : '';
    const answer = clean(afterAnswer.split(/(?:Answer history|Related questions|Question paper|©)/i)[0]) || null;
    const crumbs = [...document.querySelectorAll('a,button,h1,h2,h3')].filter(visible).map(text).filter(value => /written|orals|motor|general|electrics|safety/i.test(value));
    return {type:'marineSamrajReading',session,document:documentId,url:location.href,question,answer,module:clean(crumbs.slice(0,3).join(' · ')) || 'Public questions',links};
  }
  function execute(action) {
    if (!action || action.document!==documentId || handled.has(action.request) || !permitted(action.url)) return;
    handled.add(action.request); location.assign(action.url);
    chrome.runtime.sendMessage({type:'marineSamrajAction',session,document:documentId,request:action.request,success:true});
  }
  async function tick() { const hello=await chrome.runtime.sendMessage({type:'marineSamrajHello'}).catch(()=>null); if(!hello?.session) {session=null;return;} session=hello.session; const value=reading(), encoded=JSON.stringify(value), now=Date.now(); if(encoded!==last || now-lastSentAt>=1500) {last=encoded;lastSentAt=now;const reply=await chrome.runtime.sendMessage({type:'marineSamrajReading',session,document:documentId,reading:value}).catch(()=>null);if(reply?.session===session) execute(reply.action);} }
  setInterval(tick,1000); tick();
})();
