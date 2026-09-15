// 출발지를 바꾸면 지도가 새 결과를 보여주는가 — 회귀 검사 (T-20, 2026-09-15)
//
//   node tools/check-map-follow.mjs                                   # 개발 서버(5173)
//   node tools/check-map-follow.mjs https://klaus9267.github.io/chulsegwon/   # 공개본
//
// 다섯 경로를 댄다: ① 타이핑+Enter ② 목록 클릭 ③ 방향키+Enter ④ 확대 후 역 점 클릭
// ⑤ 공유 링크로 새로 불러오기(기준). ①~④ 의 최종 카메라(레벨·중심)와 **화면 안에 보이는
// 동네 라벨 수**가 ⑤ 와 같아야 한다.
//
// ## 왜 이런 검사가 필요했나
//
// 출발지를 운정중앙·수원으로 바꾸면 카메라는 제자리로 가는데 **동네 라벨이 0개**였다.
// 라벨은 그 순간 화면 범위 안의 것만 만들고(kakao.ts thin), 다시 뽑는 시점이 줌 변경과
// 사용자 끌기뿐이었다. 결과를 옛 화면에서 그린 뒤 카메라가 옮겨가는데 줌이 9→9 로 같으면
// 아무것도 다시 안 뽑혔다. 고치기 전 코드에서 이 검사는 10건 중 6건 실패한다.
//
// ## 왜 헤드리스 Edge 인가
//
// 앱 안 브라우저 패널은 숨겨져 있어 requestAnimationFrame 이 **초당 0번** 돈다. 카카오맵
// 애니메이션이 멈춰서 거기서 본 증상은 믿을 수 없었다(실제로 그걸 원인으로 한 번 잘못 적었다).
// 헤드리스는 "보이는" 페이지라 초당 60프레임쯤 돈다.
//
// ⚠️ 카카오맵은 등록된 도메인에서만 뜬다 — localhost:5173 과 공개 도메인. 다른 포트는 안 된다.
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const PORT = 9334;
const BASE = process.argv[2] || 'http://localhost:5173/';
const profile = mkdtempSync(join(tmpdir(), 't20p-edge-'));
const edge = spawn(EDGE, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`,
  '--window-size=1400,900', '--no-first-run', '--disable-extensions', 'about:blank'], { stdio: 'ignore' });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let ws, seq = 0; const waiting = new Map();
const send = (method, params = {}) => new Promise((res) => { const id = ++seq; waiting.set(id, res); ws.send(JSON.stringify({ id, method, params })); });
async function ev(expression) {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.result?.exceptionDetails) {
    throw new Error((r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text || '').slice(0, 300));
  }
  return r.result.result.value;
}
async function waitStatus(prev) {
  for (let i = 0; i < 80; i++) {
    const s = await ev(`(document.getElementById('status')||{}).textContent||''`);
    if (s.includes('곳') && s !== prev) return s;
    await sleep(250);
  }
  throw new Error('결과가 안 나왔다');
}

// 로드가 끝난 뒤 감싼다(그 전에 감싸면 SDK 로드가 덮어쓴다). 지도 인스턴스는 앱이 그릴 때마다
// 부르는 overlay.setMap(지도) 와 카메라 메서드에서 잡는다. 호출 기록도 남긴다.
const CAPTURE = `(() => {
  const K = kakao.maps; window.__calls = []; window.__t0 = performance.now();
  const wrap = (P, names) => { for (const n of names) {
    const o = P[n]; if (typeof o !== 'function' || o.__w) continue;
    const f = function (...a) {
      if (this instanceof K.Map) window.__map = this; else if (a[0] instanceof K.Map) window.__map = a[0];
      if (this instanceof K.Map && n !== 'getLevel' && n !== 'getCenter' && n !== 'getBounds')
        window.__calls.push(n + '@' + Math.round(performance.now() - window.__t0));
      return o.apply(this, a); };
    f.__w = true; P[n] = f; } };
  wrap(K.Map.prototype, ['setBounds','setLevel','panTo','panBy','setCenter','getLevel','getCenter','getBounds','relayout']);
  wrap(K.CustomOverlay.prototype, ['setMap']);
  return 'ok';
})()`;

// 지도 화면(왼쪽 패널에 가린 부분 제외) 안에 들어온 동네 라벨 수 + 최종 카메라
const MEASURE = `(() => {
  const map = document.getElementById('map').getBoundingClientRect();
  const panel = document.getElementById('panel');
  const left = panel && !panel.classList.contains('collapsed') ? Math.max(map.left, panel.getBoundingClientRect().right) : map.left;
  const labels = Array.from(document.querySelectorAll('.dlabel'));
  const vis = labels.filter((el) => { const r = el.getBoundingClientRect(); const x = (r.left + r.right) / 2, y = (r.top + r.bottom) / 2;
    return x >= left && x <= map.right && y >= map.top && y <= map.bottom; }).length;
  const m = window.__map; const c = m.getCenter();
  const st = document.getElementById('status').textContent;
  return { origin: document.getElementById('origin').value, n: +(st.match(/(\\d+)곳/) || [])[1],
           visible: vis, level: m.getLevel(), center: [+c.getLat().toFixed(3), +c.getLng().toFixed(3)],
           calls: window.__calls.join(' ') };
})()`;

// 카메라를 안 건드리는 재그리기 — 지도 인스턴스만 잡으려는 것이다
const REDRAW = `(() => { const w = document.getElementById('walk'); w.dispatchEvent(new Event('input', { bubbles: true })); return 1; })()`;

async function load(hash) {
  await send('Page.navigate', { url: 'about:blank' }); await sleep(200);
  await send('Page.navigate', { url: BASE + (hash || '') });
  await waitStatus('');
  await sleep(2500);
  await ev(CAPTURE);
}
const shareHash = (name) => '#o=' + encodeURIComponent(name) + '&d=a&t=6&b=40&w=15&r=ONE&n=w';

async function baseline(name) {
  await load(shareHash(name));
  await ev(REDRAW); await sleep(1200);
  return ev(MEASURE);
}

async function act(kind, name) {
  const before = await ev(`document.getElementById('status').textContent`);
  await ev(`window.__calls = []; window.__t0 = performance.now(); 1`);
  const typeIn = `const i = document.getElementById('origin'); i.focus(); i.value = ${JSON.stringify(name)}; i.dispatchEvent(new Event('input', { bubbles: true }));`;
  if (kind === 'enter') {
    await ev(`(() => { ${typeIn} i.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })); return 1; })()`);
  } else if (kind === 'click') {
    await ev(`(() => { ${typeIn} const o = Array.from(document.querySelectorAll('#originList [role=option]')).find(e => e.offsetParent !== null);
      o.dispatchEvent(new MouseEvent('mousedown', { bubbles: true })); o.dispatchEvent(new MouseEvent('click', { bubbles: true })); return 1; })()`);
  } else if (kind === 'arrow') {
    // ↓ 한 번이면 두 번째 후보가 잡힌다(수원→수원시청). ↓↑ 로 첫 후보에 돌아와 방향키 경로를 탄다.
    await ev(`(() => { ${typeIn} i.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
      i.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true }));
      i.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })); return 1; })()`);
  } else if (kind === 'dot') {
    // 역 점은 지도가 자리를 잡은 뒤에 그려진다. 나타날 때까지 기다린다.
    for (let k = 0; k < 40; k++) {
      if (await ev(`!!Array.from(document.querySelectorAll('.kdot')).find(e => e.dataset.name === ${JSON.stringify(name)})`)) break;
      await sleep(250);
    }
    await ev(`(() => { const d = Array.from(document.querySelectorAll('.kdot')).find(e => e.dataset.name === ${JSON.stringify(name)});
      d.dispatchEvent(new MouseEvent('click', { bubbles: true })); return 1; })()`);
  }
  await waitStatus(before);
  await sleep(3000);                                  // 카카오 애니메이션이 끝날 시간
  return ev(MEASURE);
}

const verdict = (got, base) => {
  const near = Math.abs(got.center[0] - base.center[0]) < 0.02 && Math.abs(got.center[1] - base.center[1]) < 0.02;
  const ok = got.n === base.n && got.level === base.level && near && got.visible >= base.visible;
  return ok ? 'OK  ' : 'FAIL';
};

let fails = 0;
try {
  let list;
  for (let i = 0; i < 60; i++) { try { list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json(); break; } catch { await sleep(250); } }
  ws = new WebSocket(list.find((t) => t.type === 'page').webSocketDebuggerUrl);
  await new Promise((r) => (ws.onopen = r));
  ws.onmessage = (e) => { const m = JSON.parse(e.data); if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); } };
  await send('Page.enable'); await send('Runtime.enable');

  await load('');
  const env = await ev(`new Promise(r => { let n = 0; const t = performance.now(); (function f() { n++; if (performance.now() - t < 1000) requestAnimationFrame(f); else r(n); })(); })`);
  console.log('애니메이션 프레임 ' + env + '/초');

  const show = ({ calls, ...rest }) => JSON.stringify(rest) + (calls ? '  [' + calls + ']' : '');
  for (const name of ['운정중앙', '수원', '동탄']) {
    const base = await baseline(name);
    console.log(`\n── ${name}   ⑤ 공유 링크(기준) ${show(base)}`);
    for (const [kind, label] of [['enter', '① 타이핑+Enter '], ['click', '② 목록 클릭    '], ['arrow', '③ 방향키+Enter ']]) {
      await load('');
      const got = await act(kind, name);
      const v = verdict(got, base); if (v.trim() !== 'OK') fails++;
      console.log(`   ${v} ${label} ${show(got)}`);
    }
  }

  // ④ 지도에서 역 클릭 — 역 점은 레벨 6 이하에서만 그려진다. 사용자처럼 그 역 근처로 확대해서 누른다.
  //    강남 기준 가까운 300역만 점으로 찍히므로, 그 안에서 멀고 이름이 하나뿐인 역을 고른다.
  const target = await ev(`(async () => { const m = await fetch(new URL('data/manifest.json', location.href.split('#')[0])).then(r => r.json());
    const g = m.stations.find(s => s.name === '강남'); const cnt = new Map();
    for (const s of m.stations) cnt.set(s.name, (cnt.get(s.name) || 0) + 1);
    const arr = m.stations.map(s => ({ s, d: Math.hypot((s.lon - g.lon) * Math.cos(g.lat * Math.PI / 180), s.lat - g.lat) }))
      .sort((a, b) => a.d - b.d).slice(0, 300).filter(x => cnt.get(x.s.name) === 1);
    const t = arr[arr.length - 1].s; return { name: t.name, lat: t.lat, lon: t.lon }; })()`);
  const base = await baseline(target.name);
  await load('');
  await ev(REDRAW); await sleep(800);
  await ev(`(() => { window.__map.setLevel(5); window.__map.setCenter(new kakao.maps.LatLng(${target.lat}, ${target.lon})); return 1; })()`);
  await sleep(1500);
  const got = await act('dot', target.name);
  const v = verdict(got, base); if (v.trim() !== 'OK') fails++;
  console.log(`
── ${target.name}   ⑤ 공유 링크(기준) ${show(base)}`);
  console.log(`   ${v} ④ 확대 후 역 점 클릭 ${show(got)}`);
} catch (e) {
  console.log('!! ' + e.message); fails++;
} finally {
  try { ws?.close(); } catch {}
  edge.kill(); await sleep(500);
  try { rmSync(profile, { recursive: true, force: true }); } catch {}
  console.log(`\n실패 ${fails}건`);
  process.exitCode = fails ? 1 : 0;
}
