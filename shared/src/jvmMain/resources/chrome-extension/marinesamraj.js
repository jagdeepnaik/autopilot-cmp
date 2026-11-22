(() => {
  if (window.__autoPilotMarineSamraj) return;
  window.__autoPilotMarineSamraj = true;
  const baseDocument = crypto.randomUUID();
  let session = null, route = '', revision = 0, routeAt = Date.now(), last = '', lastAt = 0, stable = '', stableAt = 0, busy = false;
  const handled = new Set(), targets = new Map();
  let lastStatus = '';
  function status(value) {
    if (lastStatus === value) return;
    lastStatus = value;
    document.documentElement.setAttribute('data-autopilot-marine-status', value);
    console.debug('AutoPilot MarineSamraj:', value);
  }
  const clean = value => (value || '').replace(/\u00a0/g, ' ').trim();
  const text = node => clean(node?.innerText || node?.textContent);
  const visible = node => {
    const box = node?.getBoundingClientRect?.(), style = node && getComputedStyle(node);
    return !!box && box.width > 0 && box.height > 0 && style.display !== 'none' && style.visibility !== 'hidden' && !node.closest('[aria-hidden="true"],[hidden]');
  };
  let includeBookmarks = false;
  const permitted = value => {
    try {
      const url = new URL(value, location.href), path = url.pathname.toLowerCase();
      return ['https://marinesamraj.com', 'https://www.marinesamraj.com'].includes(url.origin) &&
        (path === '/ep_home' || path === '/sitemap.xml' || path.startsWith('/exam-prep/')) &&
        !/%|\\|(?:^|\/)\.\.(?:\/|$)/.test(url.pathname) &&
        !/\/(?:user|admin|notification|review-pipeline|support-the-creators)(?:\/|$)/.test(path) &&
        (includeBookmarks || url.searchParams.get('tabID')?.toUpperCase() !== 'BOOKMARKS');
    } catch { return false; }
  };
  const documentId = () => `${baseDocument}:${revision}`;
  function refreshRoute() {
    if (route === location.href) return;
    route = location.href; revision++; routeAt = Date.now(); stable = ''; stableAt = Date.now(); targets.clear(); handled.clear(); last = '';
  }
  function reading() {
    refreshRoute();
    const sitemap = location.pathname.toLowerCase() === '/sitemap.xml';
    const anchors = [...document.querySelectorAll('a[href]')].filter(visible);
    const links = [...new Set([
      ...anchors.map(node => node.href),
      ...(sitemap ? [...document.getElementsByTagName('loc')].map(node => text(node)) : ['https://marinesamraj.com/sitemap.xml']),
    ].filter(permitted))];
    const answerRegion = [...document.querySelectorAll('section[aria-label="Answer"]')].find(visible);
    const questionPage = /\/exam-prep\/question\//i.test(location.pathname);
    const notePage = /\/exam-prep\/note\//i.test(location.pathname);
    const headings = [...document.querySelectorAll('h1,h2,h3,h4')].filter(visible);
    const heading = headings.filter(node => !node.closest('section[aria-label="Answer"],.ql-editor'))
      .map(text).filter(value => value && !/^(Question|Subject|Question Paper|Exam Preparation|Q\d+)$/i.test(value)).at(-1);
    let question = questionPage ? heading || null : null;
    const editor = answerRegion?.querySelector('.ql-editor') || (notePage ? [...document.querySelectorAll('.ql-editor')].find(visible) : null);
    if (notePage && heading) question = `Study note: ${heading}`;
    let answer = editor ? text(editor) : null;
    // Collect content media, excluding unrelated author avatars and scripts.
    if (editor) {
      const media = [...editor.querySelectorAll('img[src],a[href]')].map(node => {
        try {
          const url = new URL(node.getAttribute('src') || node.getAttribute('href'), location.href);
          if (url.protocol !== 'https:' || !['marinesamraj.com','www.marinesamraj.com','assets.marinesamraj.com'].includes(url.hostname)) return null;
          return `[${node.tagName === 'IMG' ? 'Diagram' : 'Attachment'}](${url.href})`;
        } catch { return null; }
      }).filter(Boolean);
      if (media.length) answer = [answer, ...new Set(media)].filter(Boolean).join('\n\n');
    }
    const subject = anchors.filter(node => /\/exam-prep\/subject\//.test(node.href)).map(text).filter(Boolean).at(-1);
    const tab = new URL(location.href).searchParams.get('tabID');
    if (tab === 'DIAGRAMS') {
      const diagrams = [...document.querySelectorAll('img[src]')].filter(visible).map(node => {
        try { const url = new URL(node.src); return url.protocol === 'https:' && url.hostname === 'assets.marinesamraj.com' ? `[Diagram](${url.href})` : null; }
        catch { return null; }
      }).filter(Boolean);
      if (diagrams.length) {
        question = `Study diagrams: ${headings.map(text).filter(value => value !== 'Subject').at(-1) || subject || location.pathname}`;
        answer = [...new Set(diagrams)].join('\n\n');
      }
    }
    // Keep the page that supplied this record alongside editor-scoped image/attachment links.
    // It gives later JSON export a stable origin without scraping author widgets or avatars.
    if (answer) answer = `${answer}\n\n[Source page](${location.href})`;
    const module = `MarineSamraj · ${subject || 'Study references'}${tab ? ` · ${tab}` : ''}`;
    const controls = [];
    targets.clear();
    const clickable = [...document.querySelectorAll('[tabindex="0"],button')].filter(node => visible(node) && node.getAttribute('aria-disabled') !== 'true' && !node.disabled);
    const next = questionPage ? clickable.find(node => /^NEXT(?:\s|[\uE000-\uF8FF])*$/.test(text(node))) : null;
    const candidates = next ? [next] : (!questionPage && !answerRegion ? clickable.filter(node => /^Q\d+\s/.test(text(node))) : []);
    for (const node of candidates.slice(0, 250)) {
      const key = `${next ? location.pathname : 'question'}:${text(node).replace(/\s+/g, ' ')}`;
      const id = `marine:${revision}:${controls.length}`;
      targets.set(id, node); controls.push({id, key});
    }
    const isLoading = [...document.querySelectorAll('[role="progressbar"],[aria-busy="true"]')].some(visible);
    const signature = JSON.stringify([question, answer, links, controls.map(node => node.key)]);
    if (signature !== stable) { stable = signature; stableAt = Date.now(); }
    // Subject tabs share one path and differ only by tabID. They are real public hierarchy
    // pages (Papers, Topics, Notes, Diagrams and Q Bank), so they must make the page ready
    // and be queued instead of leaving the collector indefinitely in its loading state.
    const contentLinks = links.filter(link => {
      const candidate = new URL(link);
      return candidate.href !== location.href &&
        (/\/exam-prep\/(subject|topic|qp|note|question)\//.test(candidate.pathname) || candidate.searchParams.has('tabID'));
    });
    const empty = [...document.querySelectorAll('div,p')].filter(visible).some(node => /^(No (questions|notes|diagrams|papers|topics|results)( found| available| yet)?\.?|Nothing here yet)$/i.test(text(node)));
    const hasContent = sitemap ? links.length > 0 : questionPage ? !!question && (!!editor || empty) :
      notePage ? !!question && !!editor : contentLinks.length > 0 || controls.length > 0 || !!answer || empty;
    const ready = hasContent && !isLoading && Date.now() - stableAt >= 2000;
    const error = (answer?.length > 200000 || links.length > 1000) ? 'Collection paused: this public page exceeds the safe message limit. No content was silently truncated.' : null;
    return {type:'marineSamrajReading',session,document:documentId(),url:location.href,question,answer,module,links:links.slice(0,1000),controls,ready,error};
  }
  async function execute(action) {
    if (!action || action.document !== documentId() || handled.has(action.request) || !permitted(location.href)) return;
    if (!action.control && !permitted(action.url)) return;
    const target = action.control ? targets.get(action.control) : null;
    if (action.control && (!target || !visible(target))) return;
    handled.add(action.request);
    // Acknowledge before a full-page navigation destroys the current document.
    await chrome.runtime.sendMessage({type:'marineSamrajAction',session,document:documentId(),request:action.request,success:true});
    const delay = Math.max(0, Math.min(60_000, Number((await chrome.runtime.sendMessage({type:'marineSamrajHello'}))?.clickDelayMillis || 0)));
    if (delay) await new Promise(resolve => setTimeout(resolve, delay));
    if (session === null || !permitted(location.href)) return;
    if (target) target.click(); else location.assign(action.url);
  }
  async function tick() {
    if (busy) return;
    busy = true;
    try {
      const hello = await chrome.runtime.sendMessage({type:'marineSamrajHello'});
      if (!hello?.session) {session = null; status(hello?.reason || 'The collection helper did not authorize this page.'); return;}
      if (session !== hello.session) {handled.clear(); last = '';}
      session = hello.session; includeBookmarks = hello.includeBookmarks === true;
      const value = reading(), encoded = JSON.stringify(value), now = Date.now();
      status(value.error || (value.ready ? 'Public page ready; sending to AutoPilot.' : 'Waiting for public page content to settle.'));
      if (encoded === last && now - lastAt < 2000) return;
      last = encoded; lastAt = now;
      const reply = await chrome.runtime.sendMessage({type:'marineSamrajReading',session,document:documentId(),reading:value});
      if (reply?.reason) status(reply.reason);
      if (reply?.session === session) await execute(reply.action);
    } catch (error) {
      if (!chrome.runtime?.id || /context invalidated/i.test(String(error))) clearInterval(timer);
      else status('Collection connection error. Refresh this tab and check the app connection.');
    }
    finally {busy = false;}
  }
  const timer = setInterval(tick, 1000); tick();
})();
