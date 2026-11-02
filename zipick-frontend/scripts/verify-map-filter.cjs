/* Node 22+ / existing headless Chrome CDP; no browser or test packages required.
 * Run Vite, launch Chrome with a separate --remote-debugging-port=9341 profile,
 * then node scripts/verify-map-filter.cjs [--live-api].
 * --live-api uses public development API data; authentication and SDK remain substitutes.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const base = process.env.ZIPICK_TEST_BASE_URL || 'http://127.0.0.1:5173';
const cdp = process.env.ZIPICK_CDP_URL || 'http://127.0.0.1:9341';
const live = process.argv.includes('--live-api');
const probeSdk = process.argv.includes('--probe-sdk');
const motion = process.argv.includes('--motion');
const legacy = process.env.ZIPICK_MAP_BASELINE ? JSON.parse(fs.readFileSync(process.env.ZIPICK_MAP_BASELINE, 'utf8')).snapshots : null;
const out = path.join(os.tmpdir(), 'zipick-map-filter-drawer-review');
fs.mkdirSync(out, { recursive: true });
const SDK_STUB = "// Browser verification substitute. This is never shipped with the application.\n(() => {\n const listeners=new WeakMap();\n const fire=(o,n)=>{for(const cb of listeners.get(o)?.[n]||[])cb();};\n class LatLng{constructor(lat,lng){this.lat=lat;this.lng=lng;}getLat(){return this.lat;}getLng(){return this.lng;}}\n class Bounds{constructor(){this.sw=new LatLng(Infinity,Infinity);this.ne=new LatLng(-Infinity,-Infinity);}extend(p){this.sw=new LatLng(Math.min(this.sw.lat,p.lat),Math.min(this.sw.lng,p.lng));this.ne=new LatLng(Math.max(this.ne.lat,p.lat),Math.max(this.ne.lng,p.lng));}getSouthWest(){return this.sw;}getNorthEast(){return this.ne;}}\n class MapView{\n  constructor(el,{center,level}){this.el=el;this.center=center;this.level=level;this.max=13;window.__mockMap=this;this.calls=[];el.style.background='#e9eef3';const note=document.createElement('span');note.textContent='대체 SDK 검증 화면';note.style.cssText='position:absolute;right:12px;bottom:12px;color:#516077;font-size:11px';el.appendChild(note);}\n  changed(){fire(this,'center_changed');fire(this,'zoom_changed');fire(this,'idle');}\n  getLevel(){return this.level;}setLevel(l){this.calls.push(['setLevel',l]);this.level=Math.min(this.max,l);this.changed();}\n  setMaxLevel(l){this.max=l;}getCenter(){return this.center;}setCenter(p){this.center=p;this.changed();}\n  panTo(p){this.center=p;this.changed();}relayout(){this.calls.push(['relayout']);}\n  setBounds(b){this.calls.push(['setBounds']);this.center=new LatLng((b.sw.lat+b.ne.lat)/2,(b.sw.lng+b.ne.lng)/2);this.level=12;this.changed();}\n  getBounds(){const d=2**(this.level-10);const b=new Bounds();b.extend(new LatLng(this.center.lat-d,this.center.lng-d));b.extend(new LatLng(this.center.lat+d,this.center.lng+d));return b;}\n }\n class Overlay{constructor(o){this.o=o;this.el=o.content;this.el.style.cssText+=';position:absolute;left:'+((o.position.lng-125)*80)+'px;top:'+((39-o.position.lat)*60)+'px';}setMap(m){this.el.remove();if(m)m.el.appendChild(this.el);}setZIndex(z){this.el.style.zIndex=z;}}\n class Marker{constructor(o){this.o=o;this.position=o.position;this.el=document.createElement('img');this.el.src=o.image?.src||'';this.el.title=o.title||'';this.el.style.cssText='position:absolute;left:'+((o.position.lng-125)*80)+'px;top:'+((39-o.position.lat)*60)+'px';this.el.onclick=()=>fire(this,'click');}setMap(m){this.el.remove();if(m)m.el.appendChild(this.el);}setImage(i){this.el.src=i.src;}setZIndex(z){this.el.style.zIndex=z;}getPosition(){return this.position;}}\n class Image{constructor(src){this.src=src;}}\n const event={addListener(o,n,f){let l=listeners.get(o);if(!l){l={};listeners.set(o,l);} (l[n]??=[]).push(f);},removeListener(o,n,f){const l=listeners.get(o);if(l?.[n])l[n]=l[n].filter(v=>v!==f);}};\n window.kakao={maps:{load:cb=>cb(),Map:MapView,LatLng,LatLngBounds:Bounds,CustomOverlay:Overlay,Marker,MarkerImage:Image,Size:class{},Point:class{},event}};\n})();\n\r\n";
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const pending = new Map(), requests = [], errors = [], passed = [];
let ws, sequence = 0, failNext = false, hold = false, release = null;
const pins = [
  { housingId: 101, name: '검증 예정 주택', province: '서울특별시', district: '강남구', latitude: 37.49, longitude: 127.02, status: 'UPCOMING' },
  { housingId: 102, name: '검증 접수 주택', province: '서울특별시', district: '강남구', latitude: 37.5, longitude: 127.03, status: 'OPEN' },
  { housingId: 103, name: '검증 부산 주택', province: '부산광역시', district: '해운대구', latitude: 35.16, longitude: 129.16, status: 'OPEN' },
].map(p => ({ ...p, noticeId: p.housingId, noticeTitle: p.name, address: p.province + ' ' + p.district, agencyName: 'LH' }));
const options = {
  statuses: { UPCOMING: 1, OPEN: 2 }, housingTypes: { APARTMENT: 3, OFFICETEL: 0 }, rentalTypes: { HAPPY: 3 },
  agencies: [{ code: 'LH', name: '한국토지주택공사', count: 3 }, { code: 'LONG', name: '긴 기관명 줄바꿈 검증을 위한 주택도시개발공사 지역사업본부', count: 1 }],
  regions: [
    { key: '서울특별시 강남구', province: '서울특별시', district: '강남구', count: 2 },
    { key: '부산광역시 해운대구', province: '부산광역시', district: '해운대구', count: 1 },
    { key: '경기도 부천시', province: '경기도', district: '부천시', count: 7 },
    { key: '경기도', province: '경기도', district: null, count: 10 },
    { key: '경기도 부천시 소사구', province: '경기도', district: '부천시 소사구', count: 3 },
  ],
};
function send(method, params = {}) { return new Promise((resolve, reject) => { const id = ++sequence; pending.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params })); }); }
async function ev(expression) { const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true }); if (r.exceptionDetails) throw Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text); return r.result?.value; }
async function wait(expression) { for (let i = 0; i < 120; i++) { if (await ev(expression)) return; await pause(75); } throw Error('Timeout: ' + expression); }
async function check(name, fn) { await fn(); passed.push(name); console.log('PASS ' + name); }
async function click(expression) { const point = await ev('(()=>{const e=(' + expression + ');if(!e)throw Error("Missing click target");e.scrollIntoView({block:"nearest"});const r=e.getBoundingClientRect();return{x:r.x+r.width/2,y:r.y+r.height/2}})()'); await send('Input.dispatchMouseEvent', { type: 'mouseMoved', ...point }); await send('Input.dispatchMouseEvent', { type: 'mousePressed', button: 'left', clickCount: 1, ...point }); await send('Input.dispatchMouseEvent', { type: 'mouseReleased', button: 'left', clickCount: 1, ...point }); await pause(90); }
const element = selector => 'document.querySelector(' + JSON.stringify(selector) + ')';
const textButton = (label, scope = 'dialog[open]') => '[...document.querySelectorAll(' + JSON.stringify(scope + ' button') + ')].find(e=>e.textContent.trim()===' + JSON.stringify(label) + ')';
const field = label => '[...document.querySelectorAll("dialog[open] fieldset")].find(e=>e.querySelector(":scope > legend")?.textContent===' + JSON.stringify(label) + ')';
async function input(label, value) { await ev('(()=>{const e=document.querySelector(' + JSON.stringify('dialog[open] input[aria-label="' + label + '"]') + ');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,"value").set.call(e,' + JSON.stringify(value) + ');e.dispatchEvent(new Event("input",{bubbles:true}));})()'); }
async function escape() { await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 }); await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 }); await wait('!document.querySelector("dialog[open]")'); }
async function go(route = '/map') { await send('Page.navigate', { url: base + route }); await wait('location.pathname==="/map"&&!!document.querySelector(".map-toolbar")'); await wait('!document.querySelector(".map-toolbar__count").textContent.includes("불러오는")'); await pause(250); }
async function open() { await click(element('.map-filter__trigger')); await wait('!!document.querySelector("dialog[open]")&&!!document.querySelector("dialog[open] input[type=search]")'); await pause(120); }
async function apply() { await click(textButton('적용하기')); await wait('!document.querySelector("dialog[open]")'); await pause(100); }
const mapRequests = () => requests.filter(r => r.path === '/api/v1/map/housings');
const geometry = '(()=>{const b=s=>{const r=document.querySelector(s).getBoundingClientRect();return{x:r.x,y:r.y,w:r.width,h:r.height}};return{map:b(".map-shell"),panel:b(".map-aside"),toolbar:b(".map-toolbar"),main:b("main"),overflow:document.documentElement.scrollWidth>innerWidth}})()';
async function shot(name) { const r = await send('Page.captureScreenshot', { format: 'png' }); fs.writeFileSync(path.join(out, name + '.png'), Buffer.from(r.data, 'base64')); }
async function respond(event) {
  const url = new URL(event.request.url), p = url.pathname;
  requests.push({ path: p, query: url.search, method: event.request.method });
  if (live && (p === '/api/v1/map/housings' || p === '/api/v1/houses/filter-options')) return send('Fetch.continueRequest', { requestId: event.requestId });
  let data = [], code = 200;
  if (p === '/api/auth/reissue') data = { accessToken: 'map-test-token' };
  else if (p === '/api/users/me') data = { userId: 71, email: 'filter-test@example.test' };
  else if (p === '/api/v1/profiles/me') data = { birthDate: '1998-04-21', householdSize: 1, homeless: true };
  else if (p === '/api/v1/profiles/me/preferences') data = { regions: [{ code: '전라남도 미집계지역', name: '미집계지역', rank: 1 }], housingTypes: [], rentalTypes: [] };
  else if (p === '/api/v1/houses/filter-options') data = options;
  else if (p === '/api/v1/map/housings') {
    if (hold) await new Promise(resolve => { release = resolve; });
    if (failNext) { code = 500; failNext = false; }
    const statuses = (url.searchParams.get('statuses') || 'UPCOMING,OPEN').split(',');
    const regions = (url.searchParams.get('regions') || '').split(',').filter(Boolean);
    const housings = pins.filter(p => statuses.includes(p.status) && (!regions.length || regions.some(r => p.address === r || p.address.startsWith(r + ' '))));
    data = { total: housings.length, housings };
  } else if (p.startsWith('/api/v1/map/housings/')) data = { ...pins[0], supplies: [], attachments: [], media: [], latitude: null, longitude: null };
  else if (p === '/api/v1/recommendations') data = { houses: [], incomplete: [], baseWeights: {}, priorities: {}, candidateTotal: 0, excluded: 0 };
  else if (p.endsWith('/relax-suggestions')) data = { suggestions: [] };
  const body = JSON.stringify({ success: code < 400, data: code < 400 ? data : null, error: code < 400 ? null : { code: 'TEST_FAILURE', message: '검증용 적용 실패' } });
  await send('Fetch.fulfillRequest', { requestId: event.requestId, responseCode: code, responseHeaders: [{ name: 'Content-Type', value: 'application/json' }], body: Buffer.from(body).toString('base64') });
}

(async () => {
  const tab = await (await fetch(cdp + '/json/new?about:blank', { method: 'PUT' })).json();
  ws = new WebSocket(tab.webSocketDebuggerUrl); await new Promise(resolve => ws.addEventListener('open', resolve, { once: true }));
  ws.addEventListener('message', async event => { const message = JSON.parse(event.data); if (message.id) { const p = pending.get(message.id); pending.delete(message.id); message.error ? p?.reject(Error(JSON.stringify(message.error))) : p?.resolve(message.result); } else if (message.method === 'Fetch.requestPaused') { try { await respond(message.params); } catch (e) { errors.push(e.stack); } } else if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails.exception?.description || message.params.exceptionDetails.text); });
  await send('Page.enable'); await send('Page.bringToFront'); await send('Runtime.enable'); await send('Fetch.enable', { patterns: [{ urlPattern: '*/api/*', resourceType: 'XHR' }] });
  if (!probeSdk) await send('Page.addScriptToEvaluateOnNewDocument', { source: SDK_STUB });
  await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
  if (probeSdk) {
    await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false }); await go(); await wait('!!document.querySelector(".map-controls,.map-message--error")');
    const result = await ev('({ready:!!document.querySelector(".map-controls"),error:document.querySelector(".map-message--error")?.textContent,count:document.querySelector(".map-toolbar__count").textContent})');
    await shot('actual-sdk-probe'); fs.writeFileSync(path.join(out, 'actual-sdk-probe.json'), JSON.stringify(result, null, 2)); console.log('ACTUAL SDK ' + JSON.stringify(result)); await send('Page.close'); ws.close(); return;
  }
  if (motion) {
    const traces = {};
    const sample = action => '(async()=>{const start=performance.now(),frames=[];' + action + ';while(performance.now()-start<360){await new Promise(requestAnimationFrame);const d=document.querySelector("dialog"),s=getComputedStyle(d),b=getComputedStyle(d,"::backdrop");frames.push({time:performance.now()-start,open:d.open,state:d.dataset.state,form:!!d.querySelector("form"),opacity:Number(s.opacity),x:new DOMMatrixReadOnly(s.transform).m41,backdrop:Number(b.opacity),duration:s.transitionDuration,easing:s.transitionTimingFunction});}return frames})()';
    await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'no-preference' }] });
    for (const width of [1440, 390, 320]) {
      await send('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: false }); await go(); const before = await ev(geometry), n = mapRequests().length;
      await check(width + ' 220ms enter/exit, retained content, overlay fade and unchanged layout', async () => {
        const enter = await ev(sample('document.querySelector(".map-filter__trigger").click()')); traces[width + '-enter'] = enter;
        assert(enter.some(f => f.opacity > 0 && f.opacity < .99 && f.x > 0 && f.x <= 18)); assert(enter.some(f => f.backdrop > 0 && f.backdrop < .99)); assert.equal(enter.at(-1).opacity, 1); assert.equal(enter.at(-1).x, 0); assert.equal(enter.at(-1).backdrop, 1); assert(enter.at(-1).duration.includes('0.22s')); assert(enter.at(-1).easing.includes('cubic-bezier'));
        assert.deepEqual(await ev(geometry), before); await shot('motion-open-' + width);
        const exit = await ev(sample('document.querySelector("dialog[open] button[aria-label=\\"필터 닫기\\"]").click()')); traces[width + '-exit'] = exit;
        assert(exit.some(f => f.open && f.form && f.opacity > 0 && f.opacity < .99 && f.x > 0)); assert(exit.some(f => f.backdrop > 0 && f.backdrop < .99)); const closed = exit.find(f => !f.open); assert(closed && closed.time >= 170 && closed.time < 340); assert(!exit.at(-1).form); assert.equal(exit.at(-1).opacity, 0); assert.deepEqual(await ev(geometry), before); assert.equal(mapRequests().length, n); assert(await ev('document.activeElement===document.querySelector(".map-filter__trigger")'));
      });
      await check(width + ' rapid reopen cancels stale close and discards unsubmitted draft', async () => {
        await ev('(async()=>{const sleep=ms=>new Promise(r=>setTimeout(r,ms)),trigger=document.querySelector(".map-filter__trigger");for(let i=0;i<6;i++){trigger.click();await sleep(35);document.querySelector("dialog button[aria-label=\\"필터 닫기\\"]").click();await sleep(35);}trigger.click();await sleep(360)})()');
        assert.equal(await ev('document.querySelectorAll("dialog[open]").length'), 1); assert.equal(await ev('document.querySelector("dialog").dataset.state'), 'open'); assert.equal(await ev('getComputedStyle(document.querySelector("dialog")).opacity'), '1');
        await input('보증금 최소', '1200'); const search = await ev('location.search');
        await ev('(async()=>{const e=document.querySelector("dialog[open] input[type=search]");Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,"value").set.call(e,"강남");e.dispatchEvent(new Event("input",{bubbles:true}));document.querySelector("dialog").dispatchEvent(new KeyboardEvent("keydown",{key:"Escape",bubbles:true}));await new Promise(r=>setTimeout(r,35));document.querySelector(".map-filter__trigger").click();await new Promise(r=>setTimeout(r,360))})()');
        assert.equal(await ev(element('dialog[open] input[aria-label="보증금 최소"]') + '.value'), ''); assert.equal(await ev('document.querySelector("dialog[open] input[type=search]").value'), ''); assert(await ev('document.activeElement===document.querySelector("dialog[open] button[aria-label=\\"필터 닫기\\"]")')); assert.equal(await ev('location.search'), search); assert.equal(mapRequests().length, n); await escape(); assert.equal(await ev('document.body.style.overflow'), '');
      });
    }
    await check('outside click fades out before native close and leaves no modal lock', async () => {
      await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false }); await go(); await open(); await pause(230);
      await send('Input.dispatchMouseEvent', { type: 'mousePressed', button: 'left', clickCount: 1, x: 100, y: 200 }); await send('Input.dispatchMouseEvent', { type: 'mouseReleased', button: 'left', clickCount: 1, x: 100, y: 200 });
      await wait('document.querySelector("dialog").dataset.state==="closing"'); assert(await ev('document.querySelector("dialog").open&&!!document.querySelector("dialog form")')); await wait('!document.querySelector("dialog[open]")'); assert.equal(await ev('document.body.style.overflow'), '');
    });
    await check('reduced motion opens/closes immediately and changing preference cancels an exit', async () => {
      await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] }); await open(); assert.equal(await ev('getComputedStyle(document.querySelector("dialog")).transitionDuration'), '0s');
      const exit = await ev(sample('document.querySelector("dialog[open] button[aria-label=\\"필터 닫기\\"]").click()')); assert(exit.find(f => !f.open).time < 100);
      await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'no-preference' }] }); await open(); await pause(230); await ev('document.querySelector("dialog").dispatchEvent(new KeyboardEvent("keydown",{key:"Escape",bubbles:true}))'); await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] }); await wait('!document.querySelector("dialog[open]")'); assert.equal(await ev('document.body.style.overflow'), '');
    });
    assert.deepEqual(errors, []); fs.writeFileSync(path.join(out, 'motion-results.json'), JSON.stringify({ passed, traces, errors, sdk: 'substitute', api: 'substitute' }, null, 2)); console.log('MOTION PASS ' + passed.length); await send('Page.close'); ws.close(); return;
  }
  for (const width of [1440, 1536, 390, 320]) {
    await send('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: false });
    await go(); const before = await ev(geometry), requestCount = mapRequests().length;
    await check(width + ' closed layout, modal geometry, internal scroll and footer', async () => {
      if (legacy) for (const [name, oldName] of [['map', 'map'], ['panel', 'aside'], ['toolbar', 'toolbar'], ['main', 'main']]) { const b = legacy['all-' + width][oldName]; assert.deepEqual(before[name], { x: b.x, y: b.y, w: b.width, h: b.height }); }
      await shot((live ? 'live-' : '') + 'closed-' + width); await open();
      assert.deepEqual(await ev(geometry), before);
      const g = await ev('(()=>{const d=document.querySelector("dialog[open]"),r=d.getBoundingClientRect(),f=d.querySelector("footer").getBoundingClientRect();return{width:r.width,right:r.right,height:r.height,footerBottom:f.bottom,overflow:d.scrollWidth>d.clientWidth}})()');
      assert.equal(g.width, Math.min(540, width)); assert.equal(g.right, width); assert.equal(g.height, 900); assert(g.footerBottom <= 900); assert(!g.overflow); assert.equal(mapRequests().length, requestCount);
      await shot((live ? 'live-' : '') + 'open-' + width); await click(element('dialog[open] input[type=search]')); await escape(); assert.deepEqual(await ev(geometry), before);
    });
    for (const status of ['UPCOMING', 'OPEN', 'CLOSED']) {
      await check(width + ' legacy ' + status + ' is removed without losing other filters or selection', async () => {
        const startCount = mapRequests().length;
        await go('/map?statuses=' + status + '&depositMax=9999&housing=101&keep=legacy');
        await wait('!new URLSearchParams(location.search).has("statuses")');
        let q = new URLSearchParams(await ev('location.search'));
        assert.equal(q.get('depositMax'), '9999'); assert.equal(q.get('housing'), '101'); assert.equal(q.get('keep'), 'legacy');
        assert.equal(mapRequests().length, startCount + 1); assert.equal(new URLSearchParams(mapRequests().at(-1).query).get('statuses'), null);
        const oldSearch = await ev('location.search'), oldCount = mapRequests().length, oldPins = await ev('document.querySelector(".map-count").textContent');
        await open(); assert.equal(await ev('[...document.querySelectorAll("dialog legend")].some(e=>e.textContent==="모집 상태")'), false);
        await input('보증금 최소', '100'); assert.equal(await ev('location.search'), oldSearch); assert.equal(mapRequests().length, oldCount); assert.equal(await ev('document.querySelector(".map-count").textContent'), oldPins);
        await escape(); await open(); assert.equal(await ev(element('dialog[open] input[aria-label="보증금 최소"]') + '.value'), ''); await input('보증금 최소', '100'); await apply();
        q = new URLSearchParams(await ev('location.search')); assert.equal(q.get('statuses'), null); assert.equal(q.get('depositMin'), '100'); assert.equal(q.get('housing'), '101'); assert.equal(q.get('keep'), 'legacy');
        assert.equal(new URLSearchParams(mapRequests().at(-1).query).get('statuses'), null);
        const r = live ? await fetch(base + '/api/v1/map/housings' + mapRequests().at(-1).query) : null;
        const expected = live ? (await r.json()).data.total : pins.length;
        assert.equal(await ev('document.querySelector(".map-toolbar__count strong").textContent'), expected.toLocaleString('ko-KR') + '곳');
        if (expected) assert((await ev('document.querySelector(".map-count").textContent')).startsWith('주택 ' + expected + '곳'));
        else assert((await ev('document.querySelector(".map-message").textContent')).includes('조건에 맞는 공급주택이 없습니다'));
        if (!live) assert.equal(await ev('[...document.querySelectorAll(".map-cluster__count")].reduce((sum,e)=>sum+Number(e.textContent),0)+document.querySelectorAll(".map-canvas img").length'), expected);
        assert.equal(mapRequests().length, oldCount + 1);
      });
    }
  }
  if (!live) {
    await check('selected mobile panel and existing scrollbar stay unchanged while opening', async () => {
      for (const width of [390, 320]) {
        await send('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: false }); await go('/map?housing=101'); await wait('!!document.querySelector(".housing-detail__body")');
        const before = await ev(geometry); await ev('void(window.__savedMap=window.__mockMap)'); await open(); assert.deepEqual(await ev(geometry), before); assert(await ev('window.__savedMap===window.__mockMap'));
        const r = await ev('document.querySelector("dialog[open]").getBoundingClientRect().toJSON()'); assert.equal(r.width, width); assert.equal(r.right, width); assert(await ev('document.documentElement.scrollWidth<=document.documentElement.clientWidth')); await shot('selected-open-' + width); await escape(); assert.deepEqual(await ev(geometry), before);
      }
    });
    await check('region search, multiple selection, no invented counts, cancellation and URL restoration', async () => {
      await go('/map?regions=' + encodeURIComponent('경기도 부천시 소사구') + '&housing=101'); await open();
      assert(await ev('!!(' + element('dialog[open] button[aria-label="경기도 부천시 소사구 선택 해제"]') + ')'));
      const provinces = await ev('[...' + 'document.querySelectorAll(' + JSON.stringify('[aria-label="시·도 선택"] button') + ')].map(e=>({name:e.querySelector("span").textContent,spans:e.children.length,text:e.textContent}))'); assert(provinces.filter(p => p.name !== '경기도').every(p => p.spans === 1)); assert(provinces.some(p => p.text === '경기도10'));
      const districtButtons = 'document.querySelectorAll(' + JSON.stringify('[aria-label="시·군·구 선택"] button') + ')';
      const districtTexts = await ev('[...' + districtButtons + '].map(e=>e.textContent)'); assert(districtTexts.includes('부천시7')); assert(districtTexts.includes('부천시 소사구3'));
      await ev('(()=>{const e=document.querySelector("dialog[open] input[type=search]");Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,"value").set.call(e,"소사");e.dispatchEvent(new Event("input",{bubbles:true}))})()'); assert.equal(await ev(districtButtons + '.length'), 1);
      await click(element('[aria-label="시·군·구 선택"] button')); await click(textButton('서울특별시')); await click(element('[aria-label="시·군·구 선택"] button:last-of-type')); await apply();
      let q = new URLSearchParams(await ev('location.search')); assert.equal(q.get('regions'), '서울특별시 강남구'); assert.equal(q.get('housing'), '101');
      await open(); await click(textButton('부산광역시')); await click(element('[aria-label="시·군·구 선택"] button:last-of-type')); await click(textButton('초기화')); assert.equal(new URLSearchParams(await ev('location.search')).get('regions'), '서울특별시 강남구'); await escape(); await open(); assert(await ev('!!(' + element('dialog[open] button[aria-label="서울특별시 강남구 선택 해제"]') + ')')); await click(textButton('초기화')); await apply(); assert.equal(new URLSearchParams(await ev('location.search')).get('regions'), null); assert.equal(new URLSearchParams(await ev('location.search')).get('housing'), '101');
    });
    await check('numeric validation, units and draft-only typing', async () => {
      await go(); await open(); const n = mapRequests().length; await input('보증금 최소', '2000'); await input('보증금 최대', '1000'); await click(textButton('적용하기')); await wait('!!document.querySelector("dialog[open] [role=alert]")'); assert.equal(mapRequests().length, n); await input('보증금 최소', '0'); await input('전용면적 최소', '20.5'); await apply(); const q = new URLSearchParams(await ev('location.search')); assert.equal(q.get('depositMin'), '0'); assert.equal(q.get('depositMax'), '1000'); assert.equal(q.get('areaMin'), '20.5');
    });
    await check('parent/child region keys, unrestricted multiple selection and count alignment', async () => {
      await go(); await open();
      const n = mapRequests().length;
      await click(textButton('서울특별시')); await click(element('[aria-label="시·군·구 선택"] button:last-of-type'));
      await click(textButton('부산광역시')); await click(element('[aria-label="시·군·구 선택"] button:last-of-type'));
      await click('[...document.querySelectorAll(' + JSON.stringify('[aria-label="시·도 선택"] button') + ')].find(e=>e.querySelector("span").textContent==="경기도")');
      await click('[...document.querySelectorAll(' + JSON.stringify('[aria-label="시·군·구 선택"] button') + ')].find(e=>e.querySelector("span").textContent==="부천시")');
      await click('[...document.querySelectorAll(' + JSON.stringify('[aria-label="시·군·구 선택"] button') + ')].find(e=>e.querySelector("span").textContent==="부천시 소사구")');
      assert.equal(await ev('document.querySelector(' + JSON.stringify('[aria-label="선택한 지역"]') + ').children.length'), 4);
      const alignment = await ev('[...document.querySelectorAll(' + JSON.stringify('[aria-label="시·군·구 선택"] button') + ')].filter(e=>e.children.length===2).map(e=>{const n=e.children[0].getBoundingClientRect(),q=e.children[1].getBoundingClientRect(),b=e.getBoundingClientRect();return n.right<=q.left&&q.right<=b.right})'); assert(alignment.every(Boolean));
      assert.equal(mapRequests().length, n); await apply(); const regions = new URLSearchParams(await ev('location.search')).get('regions').split(','); assert.deepEqual(regions, ['서울특별시 강남구', '부산광역시 해운대구', '경기도 부천시', '경기도 부천시 소사구']);
    });
    await check('all existing options, disabled empty chips and selected empty-chip removal', async () => {
      await go('/map?housingTypes=OFFICETEL'); await open();
      assert.equal(await ev('(' + field('주택 유형') + ').querySelectorAll("button").length'), 5); assert.equal(await ev('(' + field('공공임대 유형') + ').querySelectorAll("button").length'), 9);
      assert(await ev('(' + field('주택 유형') + ').querySelector("button[aria-pressed=true]").disabled===false'));
      await click('(' + field('주택 유형') + ').querySelector("button[aria-pressed=true]")');
      assert(await ev('[...(' + field('주택 유형') + ').querySelectorAll("button")].some(e=>e.textContent.startsWith("오피스텔")&&e.disabled)'));
      assert(await ev('document.querySelector("dialog[open]").scrollWidth<=document.querySelector("dialog[open]").clientWidth')); await escape();
    });
    await check('native modal focus containment, ESC restoration, backdrop cancel and no-change close', async () => {
      await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false }); await go(); await open(); const n = mapRequests().length;
      for (let i = 0; i < 40; i++) { await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9 }); await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9 }); assert(await ev('document.querySelector("dialog[open]").contains(document.activeElement)')); }
      await escape(); assert(await ev('document.activeElement===document.querySelector(".map-filter__trigger")')); await open(); await input('보증금 최소', '1000'); await send('Input.dispatchMouseEvent', { type: 'mousePressed', button: 'left', clickCount: 1, x: 100, y: 200 }); await send('Input.dispatchMouseEvent', { type: 'mouseReleased', button: 'left', clickCount: 1, x: 100, y: 200 }); await wait('!document.querySelector("dialog[open]")'); await open(); await click(element('dialog[open] input[type=search]')); await ev('(()=>{const e=document.querySelector("dialog[open] input[type=search]");Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,"value").set.call(e,"강남");e.dispatchEvent(new Event("input",{bubbles:true}))})()'); await escape(); await open(); await apply(); assert.equal(mapRequests().length, n);
    });
    await check('failed apply retains URL, map and draft; pending prevents duplicate requests', async () => {
      await go(); await open(); await input('보증금 최소', '1000'); const search = await ev('location.search'), map = await ev('document.querySelector(".map-count").textContent'), n = mapRequests().length; failNext = true; hold = true; await click(textButton('적용하기')); await wait('document.querySelector("dialog[open] button[type=submit]").disabled'); assert.equal(await ev('location.search'), search); await ev('document.querySelector("dialog[open] form").requestSubmit()'); hold = false; release(); await wait('!!document.querySelector("dialog[open] [role=alert]")'); assert.equal(await ev('location.search'), search); assert.equal(await ev('document.querySelector(".map-count").textContent'), map); assert.equal(mapRequests().length, n + 1); await apply(); assert.equal(new URLSearchParams(await ev('location.search')).get('depositMin'), '1000'); assert.equal(new URLSearchParams(await ev('location.search')).get('statuses'), null);
    });
    await check('pending apply cannot navigate back after leaving the map', async () => {
      await go(); await open(); await input('보증금 최소', '1000'); hold = true; await click(textButton('적용하기')); await wait('document.querySelector("dialog[open] button[type=submit]").disabled'); await ev(element('header a[href="/landing"]') + '.click()'); await wait('location.pathname==="/landing"'); hold = false; release(); await pause(300); assert.equal(await ev('location.pathname'), '/landing'); assert.equal(await ev('document.body.style.overflow'), '');
    });
    await check('recommendation mode retains actions and excludes exploration filters', async () => {
      await go('/map?mode=recommended&depositMax=5000'); await wait('!!document.querySelector(".map-toolbar__links")'); assert.equal(await ev('!!document.querySelector(".map-filter__trigger,dialog")'), false); assert.deepEqual(await ev('[...document.querySelectorAll(".map-toolbar__links a")].map(e=>e.getAttribute("href"))'), ['/onboarding', '/tune']); const r = requests.filter(r => r.path === '/api/v1/recommendations').at(-1); assert.equal(new URLSearchParams(r.query).get('depositMax'), null);
    });
  } else {
    await check('live API region counts and filtering retain the default public scope', async () => {
      const data = (await (await fetch(base + '/api/v1/houses/filter-options')).json()).data;
      const region = data.regions.find(r => r.district);
      assert(region); await go('/map?statuses=UPCOMING'); await open();
      await click('[...document.querySelectorAll(' + JSON.stringify('[aria-label="시·도 선택"] button') + ')].find(e=>e.querySelector("span").textContent===' + JSON.stringify(region.province) + ')');
      const row = '[...document.querySelectorAll(' + JSON.stringify('[aria-label="시·군·구 선택"] button') + ')].find(e=>e.querySelector("span").textContent===' + JSON.stringify(region.district) + ')';
      assert.equal(await ev('(' + row + ').children[1].textContent'), region.count.toLocaleString('ko-KR')); await click(row); await apply();
      const q = new URLSearchParams(await ev('location.search')); assert.equal(q.get('regions'), region.key); assert.equal(q.get('statuses'), null);
      const response = (await (await fetch(base + '/api/v1/map/housings?' + q)).json()).data; assert.equal(await ev('document.querySelector(".map-toolbar__count strong").textContent'), response.total.toLocaleString('ko-KR') + '곳');
    });
  }
  assert.deepEqual(errors, []);
  fs.writeFileSync(path.join(out, live ? 'live-api-results.json' : 'regression-results.json'), JSON.stringify({ api: live ? 'development' : 'substitute', sdk: 'substitute', passed, errors, requests }, null, 2));
  console.log('TOTAL PASS ' + passed.length + '; SDK substitute; API ' + (live ? 'development' : 'substitute'));
  await send('Page.close'); ws.close();
})().catch(error => { console.error(error.stack); console.error(errors); ws?.close(); process.exitCode = 1; });
