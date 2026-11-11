// Independent of content.js: inventory and document-bound actions for Course automation only.
(() => {
  if (window.__courseAutomation) return;
  window.__courseAutomation = true;
  // Some embedded/non-secure frames expose getRandomValues but not randomUUID.
  // This is an opaque document identity, never a credential or a site/session identifier.
  const random = globalThis.crypto;
  if (!random?.getRandomValues && !random?.randomUUID) return;
  const documentId = typeof random.randomUUID === 'function' ? random.randomUUID() :
    Array.from(random.getRandomValues(new Uint8Array(16)), value => value.toString(16).padStart(2, '0')).join('');
  const ids = new WeakMap();
  let nextId = 0;
  let session = null;
  let handled = new Set();
  let elements = new Map();
  const originalSpeeds = new Map();
  function restoreSpeeds() {
    for (const [video, speed] of originalSpeeds) {
      try { if (video.isConnected) video.playbackRate = speed; } catch {}
    }
    originalSpeeds.clear();
  }
  function applySpeed(speed) {
    if (![1, 1.5, 2].includes(speed)) speed = 1;
    for (const video of elements.values()) {
      if (video.tagName !== 'VIDEO' || !video.isConnected || video.loop || (video.muted && video.autoplay) || video.duration === Infinity) continue;
      try {
        if (!originalSpeeds.has(video)) originalSpeeds.set(video, video.playbackRate);
        if (video.playbackRate !== speed) video.playbackRate = speed;
      } catch {} // Unsupported playback rates must not stop the routine.
    }
    for (const video of originalSpeeds.keys()) if (!video.isConnected) originalSpeeds.delete(video);
  }
  const controls = 'button,a,summary,[onclick],[role="button"],[role="link"],[aria-expanded],input[type="button"],input[type="submit"],video,h1,h2,h3,h4,[role="status"]';
  const clean = (text, limit = 240) => (text || '').replace(/\s+/g, ' ').trim().slice(0, limit);
  function safeText(element, limit = 1000) {
    const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
    let text = '', node;
    while ((node = walker.nextNode()) && text.length < limit * 2) {
      if (!node.parentElement?.closest('input,textarea,select,[contenteditable],script,style')) text += ' ' + node.textContent;
    }
    return clean(text, limit);
  }
  const label = (element) => clean(element.getAttribute('aria-label') || element.getAttribute('title') || (element.tagName === 'INPUT' ? '' : safeText(element, 240)));
  const optionLabel = (node) => clean(node.getAttribute('aria-label') ||
    (node.labels?.length ? safeText(node.labels[0], 2000) : '') ||
    (node.id ? safeText(document.querySelector(`label[for="${CSS.escape(node.id)}"]`) || node, 2000) : safeText(node, 2000)), 2000);
  const identify = (node) => { if (!ids.has(node)) ids.set(node, String(++nextId)); elements.set(ids.get(node), node); return ids.get(node); };
  function assessmentInventory() {
    if (window !== window.top || location.origin !== 'https://sagarmeinyog.com') return null;
    const radios = [...document.querySelectorAll('input[type="radio"],[role="radio"]')].filter(visible);
    const headings = [...document.querySelectorAll('h1,h2,h3,h4,[role="heading"],[role="status"]')].filter(visible);
    const assessmentHeading = headings.find((node) => /^(?:(?:course|start)\s+)?assessment(?:\s+results?)?$/i.test(safeText(node))) ||
      headings.find((node) => /^Course Not Fully Viewed$/i.test(safeText(node)) &&
        safeText(node.closest('main') || document.body, 10000).includes("You can still start the assessment if you're ready."));
    const liveTest = /^\/dashboard\/courses\/[A-Za-z0-9_-]+\/test\/[A-Za-z0-9_-]+$/.test(location.pathname);
    if (!assessmentHeading && !liveTest && !/\/assessment(?:\/|$)/i.test(location.pathname)) return null;
    const root = assessmentHeading?.closest('[role="dialog"],form,main') || document.querySelector('main') || document.body;
    const moduleLink = [...document.querySelectorAll('a[href]')].find((node) => {
      try { const url = new URL(node.getAttribute('href'), location.href); return url.origin === location.origin && /^\/dashboard\/courses\/[A-Za-z0-9_-]+$/.test(url.pathname); } catch { return false; }
    });
    const moduleTitle = liveTest ? [...document.querySelectorAll('div,h1,h2,h3')].find((node) =>
      node.childElementCount === 0 && visible(node) && /^.+\s+Assessment$/i.test(safeText(node, 240))) : null;
    const resultRoute = /^\/dashboard\/courses\/[A-Za-z0-9_-]+\/assessment\/result\/[A-Za-z0-9_-]+$/.test(location.pathname);
    const resultText = safeText(root, 10000);
    // During result-page loading the breadcrumb temporarily renders the internal course id.
    // The cleared-result copy contains the stable course title, which is what file records use.
    const resultModule = resultRoute ? resultText.match(/You've scored\s+[\d.]+\s*%\s+(.+?)\s+CLEARED\b/i)?.[1]?.trim() : null;
    const module = resultModule || (moduleLink ? safeText(moduleLink, 240) : moduleTitle ? safeText(moduleTitle, 240).replace(/\s+Assessment$/i, '') : '');
    const statuses = [...root.querySelectorAll('h1,h2,h3,h4,[role="status"]')].filter(visible).map((node) => safeText(node));
    // The certificate preview replaces the result content visually, but stays on the same
    // passed-result route. Its download button is therefore an unambiguous continuation of the
    // certificate handoff.
    const modalDownload = [...document.querySelectorAll('button,input[type="submit"]')]
      .filter(visible).find((node) => /^download pdf$/i.test(label(node)));
    const clearedResult = resultRoute && [...root.querySelectorAll('div,span,p')].some((node) => visible(node) && /^CLEARED$/.test(safeText(node))) && /Well done — you've successfully passed with a score above 75%!/.test(resultText);
    const failedResult = resultRoute && [...root.querySelectorAll('div,span,p')].some((node) => visible(node) && /^NOT CLEARED$/.test(safeText(node))) &&
      safeText(root, 10000).includes('You need to score higher than 75% to pass this course.');
    const result = clearedResult || statuses.some((text) => /^(?:assessment passed[.!]?|you (?:have )?(?:successfully )?passed(?: the assessment)?[.!]?|congratulations[!,]? you passed(?: the assessment)?!?)$/i.test(text)) ? 'passed' :
      failedResult || statuses.some((text) => /^(?:assessment failed|you (?:did not|didn't) pass(?: the assessment)?[.!]?)$/i.test(text)) ? 'failed' :
      modalDownload ? 'passed' : null;
    const groups = new Map();
    for (const radio of radios.filter((node) => root.contains(node))) {
      const group = radio.closest('[role="radiogroup"],fieldset') || (radio.name ? root.querySelector(`input[type="radio"][name="${CSS.escape(radio.name)}"]`)?.parentElement?.parentElement : null);
      if (!group) return { module, questions: [], result };
      if (!groups.has(group)) groups.set(group, []);
      groups.get(group).push(radio);
    }
    const questions = [];
    for (const [group, options] of groups) {
      const labelledBy = group.getAttribute('aria-labelledby');
      const title = labelledBy ? document.getElementById(labelledBy) : group.querySelector('legend');
      const container = group.parentElement;
      let fallback = container?.querySelector('h1,h2,h3,h4,p,[data-question]');
      if (!fallback && liveTest && /^\(Select one answer\)$/i.test(group.previousElementSibling?.textContent?.trim() || '') &&
          /^Question\s+\d+$/i.test(group.previousElementSibling?.previousElementSibling?.previousElementSibling?.textContent?.trim() || '')) {
        fallback = group.previousElementSibling.previousElementSibling;
      }
      const text = clean(group.getAttribute('aria-label') || (title ? safeText(title, 2000) : '') || (fallback ? safeText(fallback, 2000) : ''), 2000);
      questions.push({ id: identify(group) + ':' + (options[0].name || text), text, options: options.map((node) => ({ id: identify(node), text: optionLabel(node),
        selected: node.checked === true || node.getAttribute('aria-checked') === 'true',
        enabled: !node.matches(':disabled') && !node.closest('[inert],[aria-disabled="true"]'), visible: true })) });
    }
    const buttons = [...root.querySelectorAll('button,input[type="submit"]')].filter(visible);
    // The certificate preview is rendered in a portal outside <main>. Its Download PDF button
    // therefore must be found from the document rather than from the assessment result root.
    const documentButtons = [...document.querySelectorAll('button,input[type="submit"]')].filter(visible);
    const next = buttons.find((node) => /^(?:next|next question)$/i.test(label(node)));
    const submit = buttons.find((node) => /^(?:submit|submit assessment|submit answers|end test)$/i.test(label(node)));
    const start = buttons.find((node) => /^start assessment$/i.test(label(node)));
    const retry = result === 'failed' ? buttons.find((node) => /^re-attempt$/i.test(label(node))) : null;
    const certificate = result === 'passed' ?
      documentButtons.find((node) => /^download pdf$/i.test(label(node))) ||
      buttons.find((node) => /^view certificate$/i.test(label(node))) : null;
    const certificateClose = result === 'passed' && modalDownload ?
      documentButtons.find((node) => /^close$/i.test(label(node))) : null;
    const confirmSubmit = liveTest && [...root.querySelectorAll('div,p')].some((node) => visible(node) && safeText(node) === 'Your answers will be submitted. Are you sure you want to end the test?')
      ? buttons.filter((node) => /^end test$/i.test(label(node))).at(-1) : null;
    const countText = liveTest ? [...root.querySelectorAll('div')].find((node) => node.childElementCount === 0 && visible(node) && /^\d+\s+out of\s+\d+$/i.test(node.textContent.trim()))?.textContent : null;
    const totalQuestions = countText ? Number(countText.trim().match(/out of\s+(\d+)$/i)[1]) : null;
    return { module, questions, result, next: next ? identify(next) : null, submit: submit ? identify(submit) : null,
      start: start ? identify(start) : null, totalQuestions, certificate: certificate ? identify(certificate) : null,
      certificateClose: certificateClose ? identify(certificateClose) : null, confirmSubmit: confirmSubmit ? identify(confirmSubmit) : null, retry: retry ? identify(retry) : null };
  }
  function visible(element) {
    const box = element.getBoundingClientRect(), style = getComputedStyle(element);
    return box.width > 0 && box.height > 0 && style.display !== 'none' && style.visibility !== 'hidden';
  }
  function context(element) {
    // The status and button live in their own cell on the real course page. Read the whole row,
    // including its part number, instead of assigning every lesson the same status-only context.
    const row = element.closest('tr,[role="row"]');
    if (row) return safeText(row);
    let node = element;
    for (let i = 0; i < 8 && node && node !== document.body; i++, node = node.parentElement) {
      const text = safeText(node);
      if (/min\.?\s*pass/i.test(text) && /duration/i.test(text) && node.querySelectorAll('h1,h2,h3,h4').length === 1) return text;
      if (node === element && element.hasAttribute('aria-expanded') && /not started|in progress|completed/i.test(text)) return text;
      if (/part\s*:\s*\d+/i.test(text) && /not started|in progress|completed/i.test(text)
          && [...node.querySelectorAll('button,a,[role="button"]')].filter((control) => /^(?:(?:re)?watch|resume) video$/i.test(label(control))).length === 1) return text;
    }
    return safeText(element, 500);
  }
  function profileName() {
    if (window !== window.top || location.origin !== 'https://sagarmeinyog.com') return null;
    const names = [...document.querySelectorAll('button[aria-haspopup="menu"] > span')]
      .filter(visible).map(n => safeText(n, 200)).filter(Boolean);
    return names.length === 1 ? names[0] : null;
  }
  function courseTitle(element) {
    for (let n = element, i = 0; n && n !== document.body && i < 8; n = n.parentElement, i++) {
      const headings = n.querySelectorAll('h1,h2,h3,h4');
      if (headings.length === 1 && /min\.?\s*pass/i.test(safeText(n)) && /duration/i.test(safeText(n))) return safeText(headings[0], 500);
    }
    return null;
  }
  function progressInventory() {
    if (window !== window.top || location.origin !== 'https://sagarmeinyog.com' || location.pathname !== '/dashboard/progress') return null;
    const main = document.querySelector('main');
    const table = main?.querySelector('table');
    const text = main?.innerText || '';
    const page = text.match(/Page\s+(\d+)\s+of\s+(\d+)/i), total = text.match(/(\d+)\s+total entries/i);
    const headers = [...(table?.querySelectorAll('thead th') || [])].map(n => safeText(n.querySelector(':scope > div > div') || n, 120));
    const rows = [...(table?.querySelectorAll('tbody tr') || [])].map(row => [...row.querySelectorAll('td')].map(n => safeText(n, 1000)))
      .filter(cells => cells.length === headers.length && cells.some(Boolean))
      .map(cells => Object.fromEntries(headers.map((header, i) => [header, cells[i]])));
    const find = selector => { const n = [...(main?.querySelectorAll(selector) || [])].map(svg => svg.closest('button')).find(button => button && visible(button) && !button.disabled); return n ? ids.get(n) || null : null; };
    const combos = [...(main?.querySelectorAll('[role="combobox"]') || [])].filter(visible).map(n => safeText(n));
    const sizeControl = [...(main?.querySelectorAll('[role="combobox"]') || [])].find(n =>
      n.previousElementSibling?.tagName === 'P' && safeText(n.previousElementSibling) === 'Rows per page');
    const sizeList = sizeControl?.getAttribute('aria-controls') ? document.getElementById(sizeControl.getAttribute('aria-controls')) : null;
    const pageSizeOptions = [...(sizeList?.querySelectorAll('[role="option"]') || [])].filter(n => visible(n) && n.getAttribute('aria-disabled') !== 'true')
      .map(n => ({ id: ids.get(n), value: Number(safeText(n)) })).filter(option => option.id && Number.isInteger(option.value) && option.value > 0);
    const filtersClear = [...(table?.querySelectorAll('input') || [])].every(n => !n.value);
    const required = ['Course Name','Test Title','Test Type','Attempt Number','Started At','Completed At','Passed','Percentage'];
    const missingColumns = headers.length > 0 && required.some(name => !headers.includes(name));
    return { ready: !!page && !!total && !missingColumns && headers.includes('Course Name') && !/Loading\.\.\./i.test(text),
      error: missingColumns ? 'Use Columns to show Course Name, Test Title, Test Type, Attempt Number, Started At, Completed At, Passed and Percentage before checking progress.' : /server is busy|failed to load|something went wrong/i.test(text) ? 'The website could not load progress. Retry in Chrome; saved history is unchanged.' : null,
      page: page ? Number(page[1]) : 0, pages: page ? Math.max(1,Number(page[2])) : 0, total: total ? Number(total[1]) : 0,
      pageSize: sizeControl && /^\d+$/.test(safeText(sizeControl)) ? Number(safeText(sizeControl)) : null,
      pageSizeControl: sizeControl ? ids.get(sizeControl) || null : null,
      pageSizeOpen: sizeControl?.getAttribute('aria-expanded') === 'true', pageSizeOptions,
      rows, first: find('svg.lucide-chevrons-left') || find('svg.lucide-chevron-left'), next: find('svg.lucide-chevron-right'),
      unfiltered: combos.includes('All Time') && combos.filter(v => !['All Time','All'].includes(v) && !/^\d+$/.test(v)).length === 0 && filtersClear };
  }
  function inventory() {
    const roots = [document], result = [];
    elements = new Map();
    for (let i = 0; i < roots.length; i++) {
      for (const node of roots[i].querySelectorAll('*')) if (node.shadowRoot) roots.push(node.shadowRoot);
      for (const node of roots[i].querySelectorAll(controls + (location.pathname === '/dashboard/progress' ? ',[role="option"]' : ''))) {
        if (node.closest('input:not([type="button"]):not([type="submit"]),textarea,select,[contenteditable]')) continue;
        if (!ids.has(node)) ids.set(node, String(++nextId));
        const id = ids.get(node);
        elements.set(id, node);
        const tag = node.tagName.toLowerCase();
        let href = null;
        if (node.hasAttribute('href')) {
          try { const url = new URL(node.getAttribute('href'), location.href); if (url.origin === 'https://sagarmeinyog.com' && /^\/dashboard(?:\/|$)/.test(url.pathname)) href = url.pathname; } catch {}
        }
        result.push({ id, tag, label: label(node), context: tag === 'video' ? '' : context(node), href,
          visible: visible(node), enabled: !node.matches(':disabled') && !node.closest('[inert],[aria-disabled="true"]') && getComputedStyle(node).pointerEvents !== 'none',
          expanded: node.hasAttribute('aria-expanded') ? node.getAttribute('aria-expanded') === 'true' : null,
          svg: !!node.querySelector('svg'), courseTitle: courseTitle(node),
          ...(tag === 'video' ? { paused: node.paused, ended: node.ended, mediaPosition: Number.isFinite(node.currentTime) ? node.currentTime : null, decorative: node.loop || (node.muted && node.autoplay) || node.duration === Infinity } : {}),
        });
        if (result.length >= 2000) return { items: result, truncated: true };
      }
    }
    return { items: result, truncated: false };
  }
  async function execute(action) {
    // The background already verifies the active tab and focused Chrome window. document.hasFocus()
    // is false while Chrome's address bar is selected, but DOM clicks remain correctly targeted.
    if (!action || action.document !== documentId || handled.has(action.request)) return;
    handled.add(action.request); // At most once, even when delivery or acknowledgement fails.
    const node = elements.get(action.element);
    let success = false;
    if (node?.isConnected && visible(node) && !node.matches(':disabled') && !node.closest('[inert],[aria-disabled="true"]')) {
      if (action.kind === 'play' && node.tagName === 'VIDEO') {
        node.scrollIntoView({ block: 'center' });
        try { await node.play(); success = true; } catch {}
      } else if ((action.kind === 'click' && !/quiz|assessment|exam|submit|delete|purchase/i.test(label(node))) || progressActionAllowed(action, node) || assessmentActionAllowed(action, node)) {
        node.scrollIntoView({ block: 'center' });
        // A radio click does not navigate away. Verify that the site actually selected the
        // requested answer before acknowledging it; otherwise the Kotlin runner would wait for
        // a change that never happened and appear to have answered the question.
        if (action.kind === 'assessmentAnswer') {
          try { node.click(); } catch {}
          const assessment = assessmentInventory();
          success = assessment?.questions.some((question) => question.options.some((option) =>
            option.id === action.element && option.selected)) === true;
          await chrome.runtime.sendMessage({ type: 'courseAction', session, document: documentId, request: action.request, success });
          return;
        }
        // Report before clicking, because navigation can destroy this document immediately.
        await chrome.runtime.sendMessage({ type: 'courseAction', session, document: documentId, request: action.request, success: true });
        const stillAuthorized = await chrome.runtime.sendMessage({ type: 'courseHello' });
        if (stillAuthorized?.session !== session || !node.isConnected) return;
        node.click();
        // Radix Select opens on pointer-down/keyboard, not a programmatic click. Keep this fallback
        // restricted to the validated progress selector/options; never synthesize keys for a test.
        if (action.kind === 'progressPageSize' && node.isConnected && visible(node)) {
          const trigger = node.matches('[role="combobox"]');
          if (!trigger || node.getAttribute('aria-expanded') !== 'true') {
            node.focus();
            const key = trigger ? 'ArrowDown' : 'Enter';
            node.dispatchEvent(new KeyboardEvent('keydown', { key, code: key, bubbles: true, cancelable: true }));
          }
        }
        return;
      }
    }
    await chrome.runtime.sendMessage({ type: 'courseAction', session, document: documentId, request: action.request, success });
  }
  function progressActionAllowed(action, node) {
    if (action.kind !== 'progressPageSize' || window !== window.top || location.origin !== 'https://sagarmeinyog.com' || location.pathname !== '/dashboard/progress') return false;
    if (!action.expectedLabel || label(node) !== action.expectedLabel) return false;
    const progress = progressInventory();
    return (progress?.pageSizeControl === action.element && node.matches('[role="combobox"]')) ||
      progress?.pageSizeOptions.some(option => option.id === action.element && String(option.value) === action.expectedLabel) === true;
  }
  function assessmentActionAllowed(action, node) {
    if (window !== window.top || location.origin !== 'https://sagarmeinyog.com') return false;
    const actual = action.kind === 'assessmentAnswer' ? optionLabel(node) : label(node);
    if (!action.expectedLabel || actual !== action.expectedLabel) return false;
    // The preview portal can hide the assessment result DOM. This is still safe to authorize:
    // only the exact Download PDF control on a concrete assessment-result route may continue a
    // certificate action that the app already initiated from a passed result.
    if (action.kind === 'certificateDownload' && /^download pdf$/i.test(actual)) {
      return node.matches('button,input[type="submit"]') &&
        /^\/dashboard\/courses\/[A-Za-z0-9_-]+\/assessment\/result\/[A-Za-z0-9_-]+$/.test(location.pathname);
    }
    if (action.kind === 'certificateClose' && /^close$/i.test(actual)) {
      const download = [...document.querySelectorAll('button,input[type="submit"]')].filter(visible)
        .some((button) => /^download pdf$/i.test(label(button)));
      return node.matches('button') && download &&
        /^\/dashboard\/courses\/[A-Za-z0-9_-]+\/assessment\/result\/[A-Za-z0-9_-]+$/.test(location.pathname);
    }
    if (action.kind === 'assessmentOpen') return node.matches('button,a') && /^assessment$/i.test(actual);
    const assessment = assessmentInventory();
    if (!assessment) return false;
    if (action.kind === 'assessmentAnswer') return assessment.questions.some((q) => q.options.some((o) => o.id === action.element && o.enabled && !o.selected));
    if (action.kind === 'assessmentNext') return assessment.next === action.element;
    if (action.kind === 'assessmentStart') return assessment.start === action.element && /^start assessment$/i.test(actual);
    if (action.kind === 'assessmentSubmit') return assessment.submit === action.element;
    if (action.kind === 'assessmentConfirmSubmit') return assessment.confirmSubmit === action.element && /^end test$/i.test(actual);
    if (action.kind === 'certificateDownload') return assessment.result === 'passed' && assessment.certificate === action.element && /^(?:view certificate|download pdf)$/i.test(actual);
    if (action.kind === 'certificateClose') return assessment.result === 'passed' && assessment.certificateClose === action.element && /^close$/i.test(actual);
    if (action.kind === 'assessmentRetry') return assessment.result === 'failed' && assessment.retry === action.element && /^re-attempt$/i.test(actual);
    if (action.kind === 'assessmentReturn') return node.matches('a,button') && !/submit|retry|retake|quiz|exam/i.test(actual);
    return false;
  }
  async function tick() {
    try {
      const authorization = await chrome.runtime.sendMessage({ type: 'courseHello' });
      if (authorization?.session) {
        if (session !== authorization.session) { restoreSpeeds(); session = authorization.session; handled = new Set(); }
        const { items, truncated } = inventory();
        const assessment = assessmentInventory();
        const snapshot = { session, document: documentId, path: location.pathname, top: window === window.top,
          ready: document.readyState === 'complete', truncated, elements: items, assessment, profileName: profileName(), progress: progressInventory() };
        const response = await chrome.runtime.sendMessage({ type: 'courseInventory', snapshot });
        if (response?.session === session) { applySpeed(response.playbackSpeed); await execute(response.action); }
      } else { restoreSpeeds(); session = null; }
    } catch { if (!chrome.runtime?.id) return; }
    setTimeout(tick, session ? 1000 : 2000);
  }
  tick();
})();
