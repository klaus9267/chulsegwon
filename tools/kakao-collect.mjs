// 카카오 대중교통 길찾기에서 **검증 표본**을 뜬다. 우리 값을 맞추는 데 쓰는 게 아니라
// (그건 tools/reference-bus.json 의 구간 표본이 한다) "사람이 비교하는 대상과 얼마나
// 벌어져 있나"를 재는 기준자다. 설계는 docs/LOOP.md.
//
// **왜 브라우저인가.** 카카오는 대중교통 경로 공개 API 가 없다(docs/ENGINES.md).
// 자동차는 REST 가 있지만 대중교통은 화면뿐이다. 그래서 사람이 보는 화면을 그대로 본다.
//
// **예의를 코드로 박아둔다.** 기본 25건 · 한 건마다 20초 이상 쉼 · 연속 3번 실패하면 그날은 멈춤.
// 결과는 저장소에 안 올린다(data/verify/kakao/ 는 git 무시).
//
// **"지금 출발"뿐이다.** 그래서 수집 시각이 곧 출발 시각이고, 슬롯 이름을 실행 시각에서 짓는다.
// 08:05 에 돌면 "출발 08:00", 19:05 면 "출발 19:00".
//
//   node tools/kakao-collect.mjs [--n 25] [--gap 20] [--pool tools/od-pool.json]
//                                [--out data/verify/kakao] [--budget 60] [--include-frozen]
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const PORT = 9338;

const argv = process.argv.slice(2);
const arg = (k, d) => {
  const i = argv.indexOf('--' + k);
  return i >= 0 && argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : d;
};
const flag = (k) => argv.includes('--' + k);
const N = +arg('n', 25);
const GAP_SEC = +arg('gap', 20);
const POOL = arg('pool', 'tools/od-pool.json');
const OUT_DIR = arg('out', 'data/verify/kakao');
const BUDGET = +arg('budget', 60);
const INCLUDE_FROZEN = flag('include-frozen');
const VERBOSE = flag('verbose');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const pad = (n) => String(n).padStart(2, '0');

/** 이번 회차의 슬롯. 가장 가까운 정시로 내린다 — 08:05 수집이면 "출발 08:00". */
function slotOf(d) {
  return `출발 ${pad(d.getHours())}:${d.getMinutes() < 30 ? '00' : '30'}`;
}

// ── CDP 배선 ────────────────────────────────────────────────
let ws, seq = 0;
const waiting = new Map();
let loaded = null;                                  // 다음 load 이벤트를 기다리는 사람
const send = (method, params = {}) =>
  new Promise((res) => { const id = ++seq; waiting.set(id, res); ws.send(JSON.stringify({ id, method, params })); });

/**
 * ⚠️ **짧게 걸고 여러 번 시도한다.** 화면이 넘어가는 중에 평가를 걸면 실행 문맥이
 * 사라지면서 **응답이 영영 안 온다** — 한 번에 30초를 걸었더니 첫 건부터 전부
 * "응답 없음"으로 죽었다. 5초씩 세 번 물어보면 문맥이 새로 생긴 뒤에 걸린다.
 */
async function ev(expression, tries = 3, timeoutMs = 5000) {
  for (let i = 0; i < tries; i++) {
    const r = await Promise.race([
      send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }),
      sleep(timeoutMs).then(() => null),
    ]);
    if (!r) continue;
    if (r.result?.exceptionDetails) {
      throw new Error((r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text || '').slice(0, 200));
    }
    return r.result.result.value;
  }
  throw new Error('페이지가 응답을 안 한다');
}

/** 다음 load 이벤트까지. 안 오면 그냥 넘어간다 — 어차피 아래에서 화면을 확인한다. */
async function waitLoad(ms = 20000) {
  const p = new Promise((res) => { loaded = res; });
  await Promise.race([p, sleep(ms)]);
  loaded = null;
}

/**
 * 한 번에 다 하는 **되풀이해도 되는** 한 걸음: 리다이렉트를 기다리고, 대중교통 탭이
 * 안 눌렸으면 누르고, 결과가 있으면 읽는다.
 *
 * ⚠️ **나눠서 부르면 안 된다.** 딥링크는 `?map_type=TYPE_MAP...` 으로 한 번 더 넘어가는데,
 * 그 순간에 평가를 걸면 실행 문맥이 사라져 **응답이 영영 안 온다**. 실제로 "탭을 찾았다"
 * 직후의 클릭에서 매번 죽었다. 그래서 상태를 안 들고, 매번 지금 화면을 보고 판단한다.
 *
 * 결과 대안 하나가 `li.TransitRouteItem` 이고, 총 시간은 `.title .time`,
 * 도보·환승·요금·거리는 `.walkTime` 의 title 속성에 통째로 들어 있다.
 *
 * ⚠️ **대안 전부의 최솟값**을 취한다. 첫 줄이 최소가 아닐 때가 있다
 *    (09-09 표본 15건 중 3건, 최대 11분 차).
 */
const TICK = `(() => {
  if (document.readyState !== 'complete') return { stage: '로딩' };
  if (!/map_type=TYPE_MAP/.test(location.search)) return { stage: '이동중' };
  const items = [...document.querySelectorAll('li.TransitRouteItem')];
  if (!items.length) {
    const a = document.querySelector('a.transit');
    if (!a) return { stage: '길찾기없음' };
    if (!/transit-active/.test(a.className)) { a.click(); return { stage: '탭누름' }; }
    return { stage: '결과대기' };
  }
  const alts = items.map((li) => {
    const time = li.querySelector('.title .time');
    if (!time) return null;
    const hour = time.querySelector('.hourTxt') ? +(time.querySelector('.num')?.innerText || 0) : 0;
    const min = +(time.querySelector('.num.minute')?.innerText || 0);
    const title = li.querySelector('.walkTime')?.getAttribute('title') || '';
    const walk = +(title.match(/도보\\s*(\\d+)\\s*분/) || [0, 0])[1];
    const transfers = /환승없음/.test(title) ? 0 : +(title.match(/환승\\s*(\\d+)\\s*회/) || [0, 0])[1];
    const km = +(title.match(/([\\d.]+)\\s*km/) || [0, 0])[1];
    return { minutes: hour * 60 + min, walk, transfers, km };
  }).filter((a) => a && a.minutes > 0);
  if (!alts.length) return { stage: '결과대기' };
  const best = alts.reduce((a, b) => (b.minutes < a.minutes ? b : a));
  return { stage: '완료', alts: alts.length, best, all: alts.map((a) => a.minutes) };
})()`;

/**
 * 한 건. **마감 시각을 넘기면 포기한다** — 화면이 안 뜨는 건을 붙들고 있으면
 * 한 건에 10분씩 잡아먹는다(처음 짤 때 그랬다).
 */
async function collectOne(od, deadline) {
  const t0 = Date.now();
  const step = (m) => { if (VERBOSE) console.log(`      [${Date.now() - t0}ms] ${m}`); };
  const url = 'https://map.kakao.com/link/from/' +
    encodeURIComponent(`${od.origin},${od.olat},${od.olon}`) + '/to/' +
    encodeURIComponent(`${od.dong},${od.dlat},${od.dlon}`);
  step('navigate');
  // 떠날 때 붙잡는 처리를 미리 떼어낸다. 붙잡히면 브라우저가 대화상자를 띄우고
  // 그 순간부터 평가가 **영영 응답하지 않는다** (두 번째 건부터 전부 죽었다).
  await ev(`(() => { window.onbeforeunload = null; return 1; })()`, 1, 3000).catch(() => null);
  await send('Page.navigate', { url });
  await waitLoad(12000);
  step('load');

  let last = '무응답';
  while (Date.now() < deadline) {
    const got = await ev(TICK, 1, 4000).catch(() => null);
    if (got) {
      if (got.stage !== last) { step(got.stage); last = got.stage; }
      if (got.stage === '완료') return got;
    }
    await sleep(800);
  }
  throw new Error(`대중교통 경로가 안 나왔다 (${last})`);
}

// ── 본체 ────────────────────────────────────────────────────
const pool = JSON.parse(readFileSync(POOL, 'utf8'));
const usable = pool.ods.filter((o) => INCLUDE_FROZEN || !o.frozen);

// 오래 안 본 것부터 고른다. 마지막으로 뜬 날짜는 결과 파일들에서 읽는다.
const seen = new Map();
mkdirSync(OUT_DIR, { recursive: true });
const files = readdirSync(OUT_DIR).filter((f) => f.endsWith('.json')).sort();
for (const f of files) {
  try {
    const j = JSON.parse(readFileSync(join(OUT_DIR, f), 'utf8'));
    for (const o of j.ods || []) seen.set(o.id || `${o.origin}-${o.dong}`, j.collectedAt || f);
  } catch { /* 깨진 파일은 무시한다 */ }
}
const picked = usable
  .map((o) => ({ o, last: seen.get(o.id) || '' }))
  .sort((a, b) => (a.last < b.last ? -1 : a.last > b.last ? 1 : 0))
  .slice(0, N)
  .map((x) => x.o);

const startedAt = new Date();
const slot = slotOf(startedAt);
console.log(`카카오 표본 ${picked.length}건 · ${slot} · 간격 ${GAP_SEC}초` +
  (INCLUDE_FROZEN ? ' · 동결 표본 포함' : ''));

const profile = mkdtempSync(join(tmpdir(), 'kakao-collect-'));
const edge = spawn(EDGE, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`,
  '--window-size=1400,900', '--no-first-run', '--disable-extensions', 'about:blank'], { stdio: 'ignore' });

const rows = [];
let fails = 0, streak = 0;
try {
  let list;
  for (let i = 0; i < 60; i++) {
    try { list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json(); break; } catch { await sleep(250); }
  }
  ws = new WebSocket(list.find((t) => t.type === 'page').webSocketDebuggerUrl);
  await new Promise((r) => (ws.onopen = r));
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); return; }
    if (m.method === 'Page.loadEventFired' && loaded) loaded();
    // 대화상자가 뜨면 렌더러가 멈춘다 — 무조건 확인을 눌러 흘려보낸다.
    if (m.method === 'Page.javascriptDialogOpening') {
      ws.send(JSON.stringify({ id: ++seq, method: 'Page.handleJavaScriptDialog', params: { accept: true } }));
    }
  };
  await send('Page.enable'); await send('Runtime.enable');

  for (const [i, od] of picked.entries()) {
    try {
      const got = await collectOne(od, Date.now() + 60000);
      rows.push({
        id: od.id, origin: od.origin, oi: od.oi, band: od.band,
        dong: od.dong, gu: od.gu, di: od.di,
        olat: od.olat, olon: od.olon, dlat: od.dlat, dlon: od.dlon,
        frozen: !!od.frozen,
        ours: 0,                                  // odcheck 가 직접 다시 계산한다
        kakao: got.best.minutes, kakaoWalk: got.best.walk,
        kakaoTransfers: got.best.transfers, kakaoKm: got.best.km,
        alts: got.alts, altMinutes: got.all,
        at: new Date().toISOString(),
      });
      streak = 0;
      console.log(`   ${i + 1}/${picked.length} ${od.origin}→${od.dong} ${got.best.minutes}분 ` +
        `(대안 ${got.alts}개 · 도보 ${got.best.walk}분 · 환승 ${got.best.transfers}회)`);
    } catch (e) {
      fails++; streak++;
      console.log(`   ${i + 1}/${picked.length} ${od.origin}→${od.dong} !! ${e.message}`);
      // 연속 실패는 차단이나 화면 개편 신호다. 계속 두드리지 않는다.
      if (streak >= 3) { console.log('   연속 3번 실패 — 오늘은 여기서 멈춘다'); break; }
    }
    if (i < picked.length - 1) await sleep(GAP_SEC * 1000);
  }
} catch (e) {
  console.log('!! ' + e.message);
} finally {
  try { ws?.close(); } catch { /* 이미 닫혔다 */ }
  edge.kill(); await sleep(400);
  try { rmSync(profile, { recursive: true, force: true }); } catch { /* 지워졌다 */ }
}

if (!rows.length) { console.log('모은 게 없다 — 파일을 안 쓴다'); process.exitCode = 1; }
else {
  const stamp = `${startedAt.getFullYear()}${pad(startedAt.getMonth() + 1)}${pad(startedAt.getDate())}-${pad(startedAt.getHours())}${pad(startedAt.getMinutes())}`;
  const out = join(OUT_DIR, `${stamp}.json`);
  writeFileSync(out, JSON.stringify({
    slot, budget: BUDGET,
    collectedAt: startedAt.toISOString(),
    kakaoNote: '카카오 PC 길찾기 "지금 출발". 대안 전부의 최솟값. tools/kakao-collect.mjs',
    ods: rows,
  }, null, 1), 'utf8');
  console.log(`-> ${out} · ${rows.length}건${fails ? ` (실패 ${fails})` : ''}`);
}
