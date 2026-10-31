/* Run Vite at :5178 and a separate headless Chrome CDP at :9348.
 * Browser checks use substitute API/image/SDK data; no production data is changed. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const out = path.join(require('node:os').tmpdir(), 'zipick-floor-plan-review');
fs.mkdirSync(out, { recursive: true });
let ws, sequence = 0;
let holdImages = false;
const heldImages = [];
const pending = new Map(), errors = [], results = [];
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
function send(method, params = {}) { return new Promise((resolve, reject) => { const id = ++sequence; pending.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params })); }); }
async function ev(expression) { const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true }); if (r.exceptionDetails) throw Error(JSON.stringify(r.exceptionDetails)); return r.result.value; }
async function wait(expression) { for (let i = 0; i < 80; i++) { if (await ev(expression)) return; await pause(50); } throw Error('Timeout: ' + expression); }
async function check(name, expression) { assert.ok(await ev(expression), name); results.push(name); }
const gallery = `document.querySelector('[aria-label="평면도·도면 파일 목록"]')`;
const track = gallery;
const thumbs = `document.querySelectorAll('button[aria-haspopup="dialog"][aria-label$="크게 보기"]')`;
async function click(selector) { await ev(`document.querySelector(${JSON.stringify(selector)}).click()`); await pause(80); }
async function key(key, shift = false) { const windowsVirtualKeyCode = { Escape: 27, Tab: 9, ArrowLeft: 37, ArrowRight: 39 }[key]; await send('Input.dispatchKeyEvent', { type: 'keyDown', key, code: key, windowsVirtualKeyCode, modifiers: shift ? 8 : 0 }); await send('Input.dispatchKeyEvent', { type: 'keyUp', key, code: key, windowsVirtualKeyCode, modifiers: 0 }); await pause(80); }
async function go(id = 202) { await send('Page.navigate', { url: 'http://127.0.0.1:5178/map?housing=' + id }); await wait('!!document.querySelector(".housing-detail__title")'); await pause(100); }
const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="400" height="600"><rect width="400" height="600" fill="white"/><path d="M30 30H370V570H30Z M30 250H250V570 M250 30V150H370" fill="none" stroke="#516077" stroke-width="4"/><text x="70" y="110" font-size="20">TEST FLOOR PLAN</text></svg>';
const pins = [200, 201, 202].map(housingId => ({ housingId, name: '검증 주택 ' + housingId, noticeId: housingId, noticeTitle: '검증 공고', address: '서울특별시 검증 주소', province: '서울특별시', district: '강남구', latitude: 37.5, longitude: 127, status: 'OPEN' }));
const media = [
  { mediaId: 1, mediaType: 'FLOOR_PLAN', fileName: '긴 평면도 이름과 실제 데이터의 줄바꿈을 확인하는 검증용 파일.png', sourceUrl: 'https://example.test/plan.png' },
  { mediaId: 2, mediaType: 'SITE_PLAN', fileName: '이미지 실패 검증.png', sourceUrl: 'https://example.test/broken.png' },
  { mediaId: 3, mediaType: 'FLOOR_PLAN', fileName: '도면.pdf', sourceUrl: 'https://example.test/plan.pdf' },
];
const attachments = [
  { attachmentId: 4, attachmentType: 'FLOOR_PLAN', fileName: null, sourceUrl: 'https://example.test/attachment.png' },
  { attachmentId: 5, attachmentType: 'FLOOR_PLAN', fileName: '첨부.pdf', sourceUrl: 'https://example.test/attachment.pdf' },
];
async function respond(event) {
  const p = new URL(event.request.url).pathname;
  let data = [], responseCode = 200, mime = 'application/json', body;
  if (p.endsWith('/content')) { if (holdImages) await new Promise(resolve => heldImages.push(resolve)); mime = 'image/svg+xml'; body = svg; if (p.endsWith('/media/2/content')) { responseCode = 404; body = ''; } }
  else if (p === '/api/auth/reissue') data = { accessToken: 'test-only' };
  else if (p === '/api/users/me') data = { userId: 1, email: 'test@example.test' };
  else if (p === '/api/v1/map/housings') data = { total: pins.length, housings: pins };
  else if (p.endsWith('/relax-suggestions')) data = { currentCount: 1, suggestions: [] };
  else if (p === '/api/v1/recommendations') data = { houses: [{ houseId: 202, supplyId: 1, announcementId: 202, title: '검증 공고', housingName: '검증 추천 주택', address: '검증 주소', status: 'OPEN', complete: true, eligibility: 'PASS', itemScores: { cost: 0.9 }, reasons: [], lat: 37.5, lng: 127 }], incomplete: [], baseWeights: { cost: 1 }, priorities: { cost: 3 }, total: 1, candidateTotal: 1, excluded: 0 };
  else if (/\/housings\/\d+$/.test(p)) {
    const id = Number(p.split('/').pop());
    data = { ...pins.find(pin => pin.housingId === id), latitude: null, longitude: null, supplies: [], media: id === 200 ? [] : id === 201 ? media.slice(0, 1) : media, attachments: id === 202 ? attachments : [] };
  } else if (p.endsWith('/infra')) data = { status: 'FRESH', categories: [] };
  else if (p.endsWith('/preferences')) data = { regions: [], housingTypes: [], rentalTypes: [] };
  else if (p.endsWith('/profiles/me')) data = { birthDate: '1998-01-01', householdSize: 1, homeless: true };
  body ??= JSON.stringify({ success: true, data });
  await send('Fetch.fulfillRequest', { requestId: event.requestId, responseCode, responseHeaders: [{ name: 'Content-Type', value: mime }], body: Buffer.from(body).toString('base64') });
}
(async () => {
  const tab = await (await fetch('http://127.0.0.1:9348/json/new?about:blank', { method: 'PUT' })).json();
  ws = new WebSocket(tab.webSocketDebuggerUrl); await new Promise(resolve => ws.addEventListener('open', resolve, { once: true }));
  ws.addEventListener('message', event => { const m = JSON.parse(event.data); if (m.id) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p?.reject(Error(JSON.stringify(m.error))) : p?.resolve(m.result); } else if (m.method === 'Fetch.requestPaused') respond(m.params).catch(e => errors.push(e.stack)); else if (m.method === 'Runtime.exceptionThrown') errors.push(m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text); });
  await send('Page.enable'); await send('Runtime.enable');
  await send('Fetch.enable', { patterns: [{ urlPattern: '*/api/*', resourceType: 'XHR' }, { urlPattern: '*/api/*', resourceType: 'Image' }] });
  const fixtureSource = fs.readFileSync(path.join(__dirname, 'verify-map-filter.cjs'), 'utf8');
  const sdk = JSON.parse(fixtureSource.match(/^const SDK_STUB = (".*");$/m)[1]);
  await send('Page.addScriptToEvaluateOnNewDocument', { source: sdk });
  await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
  for (const width of [1440, 1536, 390, 320]) {
    await send('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: false });
    await go();
    await check(width + ': five ordered files, three image previews', `${track}.children.length===5 && ${thumbs}.length===3`);
    await check(width + ': no horizontal page overflow', 'document.documentElement.scrollWidth<=innerWidth');
    await check(width + ': thumbnail contains full image box', `(()=>{const i=${track}.querySelector('img'),a=i.getBoundingClientRect(),b=i.parentElement.getBoundingClientRect();return a.height<=b.height && a.width<=b.width})()`);
    await check(width + ': desktop panel width unchanged', 'innerWidth<1100 || document.querySelector(".map-aside").getBoundingClientRect().width===400');
    await check(width + ': PDF retains source link', `${track}.querySelectorAll('a[target="_blank"]').length===2`);
    await click('button[aria-label="다음 평면도 카드"]');
    await check(width + ': carousel moves', `${track}.scrollLeft>0`);
    await ev(`${thumbs}[2].click()`); await wait('!!document.querySelector("dialog[open]")');
    await check(width + ': clicked third image opens', 'document.querySelector("dialog[open]").textContent.includes("3 / 3")');
    await key('ArrowLeft');
    await wait('document.querySelector("dialog[open]").textContent.includes("이미지를 불러오지 못했어요")');
    await check(width + ': broken image remains navigable', 'document.querySelector("dialog[open]").textContent.includes("2 / 3")');
    await key('ArrowLeft'); await check(width + ': keyboard previous', 'document.querySelector("dialog[open]").textContent.includes("1 / 3")');
    await key('ArrowRight'); await check(width + ': keyboard next', 'document.querySelector("dialog[open]").textContent.includes("2 / 3")');
    await ev('document.querySelector("dialog[open] button").focus()'); await key('Tab', true);
    await check(width + ': focus wraps inside dialog', 'document.activeElement===document.querySelector("dialog[open] a")');
    await check(width + ': background scroll locked', 'document.body.style.overflow==="hidden"');
    const rect = await ev('(()=>{const d=document.querySelector("dialog[open]"),i=d.querySelector("img"),r=d.getBoundingClientRect();return {within:r.left>=0&&r.right<=innerWidth&&r.top>=0&&r.bottom<=innerHeight}})()');
    assert.ok(rect.within); results.push(width + ': modal fits viewport');
    await key('Escape'); await wait('!document.querySelector("dialog[open]")');
    await check(width + ': focus restored and scroll unlocked', `document.activeElement===${thumbs}[2] && document.body.style.overflow!=="hidden"`);
    await ev(`${thumbs}[0].click()`); await wait('!!document.querySelector("dialog[open]")');
    const screenshot = await send('Page.captureScreenshot', { format: 'png' }); fs.writeFileSync(path.join(out, width + '-modal.png'), Buffer.from(screenshot.data, 'base64'));
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: 1, y: 1, button: 'left', clickCount: 1 }); await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: 1, y: 1, button: 'left', clickCount: 1 }); await wait('!document.querySelector("dialog[open]")'); results.push(width + ': backdrop closes');
    await go(200); await check(width + ': empty section stays hidden', `!${gallery}`);
    await go(201); await check(width + ': one file hides carousel arrows', `!document.querySelector('button[aria-label="다음 평면도 카드"]')`);
    await ev(`${thumbs}[0].click()`); await wait('!!document.querySelector("dialog[open]")');
    await check(width + ': one image opens without modal arrows', `!document.querySelector('button[aria-label="다음 평면도 이미지"]')`);
    // Client-side URL change reuses MapPage; the keyed gallery must unmount and release its dialog.
    await ev('history.pushState(null,"","/map?housing=200");dispatchEvent(new PopStateEvent("popstate"))');
    await wait('document.querySelector(".housing-detail__title")?.textContent.includes("200")');
    await check(width + ': housing change resets dialog and scroll lock', '!document.querySelector("dialog[open]") && document.body.style.overflow!=="hidden"');
  }
  await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 900, deviceScaleFactor: 1, mobile: true });
  await send('Network.enable'); await send('Network.setCacheDisabled', { cacheDisabled: true });
  holdImages = true;
  await go();
  await check('loading state visible without blocking cards', `${track}.querySelector('[aria-busy="true"]')!==null && ${thumbs}.length===3`);
  holdImages = false; heldImages.splice(0).forEach(resolve => resolve());
  await wait(`${track}.querySelectorAll('[aria-busy="true"]').length===0`);
  await ev(`${track}.scrollIntoView({block:'center'});${track}.scrollLeft=0`);
  const bounds = await ev(`(()=>{const r=${track}.getBoundingClientRect();return{x:r.x+r.width/2,y:r.y+r.height/2}})()`);
  await send('Input.dispatchMouseEvent', { type: 'mouseWheel', x: bounds.x, y: bounds.y, deltaX: 150, deltaY: 0 }); await pause(300);
  await check('horizontal mouse wheel scrolls carousel', `${track}.scrollLeft>0`);
  await ev(`${track}.scrollLeft=0`); await pause(100);
  await send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 1 });
  await send('Input.synthesizeScrollGesture', { x: bounds.x, y: bounds.y, xDistance: -180, yDistance: 0, gestureSourceType: 'touch', speed: 400 }); await pause(300);
  await check('touch swipe scrolls carousel', `${track}.scrollLeft>0`);
  await go(); await ev('window.__mockMap.setLevel(1)');
  await wait('!!document.querySelector(".map-canvas img[title]")'); await click('.map-canvas img[title]');
  await wait('document.querySelectorAll(".location-list__card").length===3');
  await check('same location list still opens', 'document.querySelector(".map-side__summary").textContent.includes("3개")');
  await click('.location-list__card:last-of-type button'); await wait(`!!${track}`);
  await click('.map-aside__back'); await wait('document.querySelectorAll(".location-list__card").length===3'); results.push('list to detail and back preserved');
  await send('Page.navigate', { url: 'http://127.0.0.1:5178/map?mode=recommended' });
  await wait('!!document.querySelector(".rec-item__main")');
  await check('recommendation score remains 90', 'document.querySelector(".rec-item__score").textContent.includes("90")');
  await click('.rec-item__main'); await wait(`!!${track}`);
  await check('recommended detail retains score with gallery', 'document.querySelector(".map-score").textContent.includes("90")');
  await ev(`${thumbs}[0].click()`); await wait('!!document.querySelector("dialog[open]")'); await key('Escape'); await click('.map-aside__back');
  await wait('!!document.querySelector(".rec-item__main")'); results.push('recommended detail gallery and return preserved');
  for (const width of [1440, 1536, 390, 320]) {
    await send('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: false });
    await go(); await ev(`${track}.scrollIntoView({block:'center'})`);
    const screenshot = await send('Page.captureScreenshot', { format: 'png' }); fs.writeFileSync(path.join(out, width + '-carousel.png'), Buffer.from(screenshot.data, 'base64'));
  }
  assert.deepEqual(errors, []);
  fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify(results, null, 2));
  console.log(`PASS ${results.length} browser checks; mock API/images/SDK. Artifacts: ${out}`);
  await send('Page.close'); ws.close();
})().catch(async e => { console.error(e, errors); try { console.error(await ev('document.body.innerText')); } catch {} ws?.close(); process.exitCode = 1; });
