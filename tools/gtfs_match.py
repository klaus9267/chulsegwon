# -*- coding: utf-8 -*-
"""카카오맵이 준 버스 구간을 우리 GTFS 에서 찾아 소요시간을 대조한다.

**왜 이게 되나.** 카카오 대중교통 길찾기는 경로를 leg 단위로 쪼개서 준다 —
`4312번, 강남역10번출구 승차, 구룡산입구 하차, 정류장 10개, 1607초` 같은 것.
그게 우리 `stop_times.txt` 가 담고 있는 것과 **정확히 같은 단위**라 바로 붙는다.
길찾기 한 번에 leg 이 10~16개씩 나오니 OD 서른 몇 개면 표본 수백 개가 된다.

**맞추는 열쇠는 셋.** 노선번호 · 승하차 정류장 이름 · 그 사이 정류장 수.
이름만으로는 안 된다 — 같은 이름의 정류장이 노선마다 여럿이고 방향도 둘이다.
정류장 수가 그걸 갈라준다.

**왜 카카오를 기준으로 삼나.** 카카오가 진리라서가 아니라, 우리가 만들려는 게
"실제로 그 시간에 갈 수 있는 동네"이고 카카오 숫자는 실제 운행에서 나온 값이라서다.
전수 대조는 어느 서비스든 약관 위반이라 하지 않는다 — 표본 수십 건이 상한이다.
"""
import csv
import io
import json
import re
import sys
import zipfile
from collections import defaultdict

sys.stdout.reconfigure(encoding='utf-8')

ZIP = 'data/out/gtfs-seoul-gyeonggi.zip'
SEOUL_ROUTES = 'data/raw/seoul-bus/routes.jsonl'
SPEED_DIR = 'data/raw/seoul-bus/speed'


def norm(s):
    """정류장 이름 정규화.

    ⚠️ 괄호 **안까지** 버린다. 처음엔 괄호 기호만 지웠는데 그러면
    카카오 `홍대입구역(중)` 과 우리 `홍대입구역(중앙)` 이 `…중` vs `…중앙` 으로
    갈려서 안 붙는다. 매칭 실패 189건 중 87건이 이것이었다 — 데이터 문제가 아니라
    대조기 문제였고, 그걸 안 갈랐으면 멀쩡한 노선을 결함으로 셀 뻔했다.
    """
    s = re.sub(r'\([^)]*\)', '', s or '')
    return re.sub(r'[\s.·,\-]', '', s)


def alts(s):
    """카카오가 `A정류장/B정류장` 처럼 후보를 묶어 줄 때가 있다. 각각을 따로 시도한다."""
    return [x for x in (norm(p) for p in (s or '').split('/')) if x]


def load_gtfs(zp=ZIP):
    z = zipfile.ZipFile(zp)

    def rd(n):
        return csv.DictReader(io.TextIOWrapper(z.open(n), encoding='utf-8'))

    stops = {r['stop_id']: r['stop_name'] for r in rd('stops.txt')}
    coord = {r['stop_id']: (float(r['stop_lat']), float(r['stop_lon'])) for r in rd('stops.txt')}
    routes = {r['route_id']: (r['route_short_name'], r['route_long_name']) for r in rd('routes.txt')}
    trip_route, trip_service = {}, {}
    for t in rd('trips.txt'):
        trip_route[t['trip_id']] = t['route_id']
        trip_service[t['trip_id']] = t['service_id']

    # 평일 운행만. 소요시간은 요일과 무관하고, 셋 다 읽으면 후보가 3배로 늘 뿐이다.
    seq = defaultdict(list)
    for r in rd('stop_times.txt'):
        t = r['trip_id']
        if trip_service.get(t) not in ('DAILY', 'WD'):
            continue
        h, m, s = r['departure_time'].split(':')
        seq[t].append((r['stop_id'], int(h) * 3600 + int(m) * 60 + int(s)))

    by_no = defaultdict(list)
    for t, sq in seq.items():
        rid = trip_route.get(t)
        no, typ = routes.get(rid, ('', ''))
        by_no[no].append((t, sq, typ, rid))
    return stops, coord, by_no


def load_measured():
    """구간 속도를 실제로 재본 적 있는 노선번호 집합.

    이게 중요한 이유: 우리 소요시간은 두 출처가 섞여 있다. 실측(서울 일부)과
    학습한 곡선(나머지). 둘의 오차가 같은 방향으로 같은 크기면 원인은 공통이고,
    다르면 원인이 따로다. 그 구분 없이는 무엇을 고쳐야 할지 알 수 없다.
    """
    import glob
    import os
    if not os.path.isdir(SPEED_DIR):
        return set()
    ids = set()
    for f in glob.glob(os.path.join(SPEED_DIR, '*.jsonl')):
        for line in io.open(f, encoding='utf-8'):
            if line.strip():
                ids.add(json.loads(line)['id'])
    nos = set()
    for line in io.open(SEOUL_ROUTES, encoding='utf-8'):
        r = json.loads(line)
        if r['id'] in ids:
            nos.add(r['no'])
    return nos


def haversine(a, b):
    import math
    R = 6371000.0
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = math.radians(b[0] - a[0]), math.radians(b[1] - a[1])
    return 2 * R * math.asin(math.sqrt(
        math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2))


def find(by_no, stops, coord, no, frm, to, nstops=None):
    """(노선번호, 승차, 하차) 를 찾아 (정류장수, 소요초, 노선유형, 직선거리합) 을 준다.

    직선거리합을 같이 주는 이유: 우리가 경기 구간 거리를 좌표 직선거리 × 1.01 로
    쓰고 있는데, 그 1.01 은 **서울** 구간에서 잰 값이다. 카카오가 주는 실제 도로거리와
    견주면 경기에서도 1.01 이 맞는지 처음으로 확인할 수 있다."""
    fs, ts = set(alts(frm)), set(alts(to))
    cands = []
    for t, sq, typ, rid in by_no.get(no, []):
        names = [norm(stops.get(sid, '')) for sid, _ in sq]
        # ⚠️ 승차 정류장이 한 운행에 여러 번 나올 수 있다 — 순환·왕복 노선이 그렇다.
        # 첫 번째만 보면 "한 바퀴 돌아서 가는" 조합을 골라 소요시간이 몇 배로 부푼다.
        # 실제로 그것 때문에 17번이 카카오 400초 vs 우리 4110초 로 나왔었다.
        # 데이터가 아니라 대조기가 틀린 것이었다. 모든 짝을 만들고 아래에서 고른다.
        for i, v in enumerate(names):
            if v not in fs:
                continue
            for j in range(i + 1, len(names)):
                if names[j] in ts:
                    gm = sum(haversine(coord[sq[k][0]], coord[sq[k + 1][0]]) for k in range(i, j))
                    cands.append((j - i + 1, sq[j][1] - sq[i][1], typ, gm))
                    break
    if not cands:
        return None, ('노선없음' if no not in by_no else '구간없음')
    if nstops:
        # 정류장 수가 카카오와 같은 후보를 고른다. 세는 방식이 1 차이날 수 있어
        # ±1 까지 같은 급으로 보고, 그 안에서는 가장 짧은 것을 고른다.
        best = min(cands, key=lambda c: (abs(c[0] - nstops) > 1, abs(c[0] - nstops), c[1]))
        return best, None
    return min(cands, key=lambda c: c[0]), None


def fit(legs):
    """카카오 leg 들에서 `초 = a×미터 + b×정류장수 + c` 를 최소제곱으로 푼다.

    a 의 역수가 주행속도고, b 가 정류장 하나당 붙는 시간이다. 우리 모델이
    쓰는 값(속도 곡선, DWELL_SEC=20)과 직접 견줄 수 있는 형태로 만드는 것이다.
    외부 의존 없이 3×3 정규방정식을 손으로 푼다.
    """
    n = len(legs)
    if n < 8:
        return None
    X = [(l['m'], l['n'], 1.0) for l in legs]
    y = [l['sec'] for l in legs]
    A = [[sum(X[k][i] * X[k][j] for k in range(n)) for j in range(3)] for i in range(3)]
    b = [sum(X[k][i] * y[k] for k in range(n)) for i in range(3)]
    for i in range(3):                       # 가우스 소거
        p = max(range(i, 3), key=lambda r: abs(A[r][i]))
        A[i], A[p] = A[p], A[i]
        b[i], b[p] = b[p], b[i]
        if abs(A[i][i]) < 1e-12:
            return None
        for r in range(3):
            if r == i:
                continue
            f = A[r][i] / A[i][i]
            for c in range(i, 3):
                A[r][c] -= f * A[i][c]
            b[r] -= f * b[i]
    sol = [b[i] / A[i][i] for i in range(3)]
    return {'kmh': 3.6 / sol[0] if sol[0] > 0 else None, 'dwell': sol[1], 'const': sol[2]}


def pct(v, q):
    v = sorted(v)
    return v[min(len(v) - 1, int(len(v) * q))]


def main():
    stops, coord, by_no = load_gtfs()
    measured_nos = load_measured()
    raw = json.load(io.open(sys.argv[1], encoding='utf-8'))
    legs = raw['legs'] if isinstance(raw, dict) else raw

    rows = []
    seen = set()
    for lg in legs:
        for name in lg['v'].split('/'):
            name = re.sub(r'\(.*\)$', '', name).strip()      # `8641(평일)`, `N64(심야)`
            if not name:
                continue
            key = (name, norm(lg['f']), norm(lg['t']))
            if key in seen:                                   # 같은 구간이 여러 경로에 나온다
                continue
            seen.add(key)
            hit, why = find(by_no, stops, coord, name, lg['f'], lg['t'], lg.get('n'))
            if hit is None and re.search(r'[A-Z]$', name):
                # 카카오는 갈래 노선에 `110A` 처럼 글자를 붙인다. 우리 원본이 `110` 이면 못 붙는다.
                hit, why = find(by_no, stops, coord, name[:-1], lg['f'], lg['t'], lg.get('n'))
            rows.append({
                'no': name, 'od': lg.get('od', ''), 'from': lg['f'], 'to': lg['t'],
                'kakao_n': lg.get('n'), 'kakao_sec': lg['sec'], 'kakao_m': lg['m'],
                'ours_n': hit[0] if hit else None,
                'ours_sec': hit[1] if hit else None,
                'type': hit[2] if hit else None,
                'ours_m': round(hit[3]) if hit else None,
                'src': ('실측' if name in measured_nos else '추정'),
                'why': why,
            })

    json.dump(rows, io.open(sys.argv[2], 'w', encoding='utf-8'), ensure_ascii=False, indent=1)

    ok = [r for r in rows if r['ours_sec']]
    print('구간 %d개 · 우리 데이터에서 찾음 %d개 (%.0f%%)'
          % (len(rows), len(ok), 100.0 * len(ok) / max(1, len(rows))))
    miss = defaultdict(int)
    for r in rows:
        if not r['ours_sec']:
            miss[r['why']] += 1
    if miss:
        print('  못 찾은 이유: ' + ' · '.join('%s %d' % (k, v) for k, v in miss.items()))

    if not ok:
        return

    def report(label, sub):
        if len(sub) < 3:
            return
        d = [r['ours_sec'] - r['kakao_sec'] for r in sub]
        rel = [(r['ours_sec'] - r['kakao_sec']) * 100.0 / r['kakao_sec'] for r in sub]
        within = sum(1 for x in d if abs(x) <= 180)
        print('  %-10s n=%-4d 편향 %+5.0f초 (%+5.1f%%) · 중앙 %+5.0f초 · MAE %4.0f초 · ±3분 %3.0f%%'
              % (label, len(sub), sum(d) / len(d), sum(rel) / len(rel),
                 pct(d, 0.5), sum(abs(x) for x in d) / len(d), 100.0 * within / len(sub)))

    print()
    print('우리 − 카카오:')
    report('전체', ok)
    report('실측노선', [r for r in ok if r['src'] == '실측'])
    report('추정노선', [r for r in ok if r['src'] == '추정'])
    for t in sorted({r['type'] for r in ok if r['type']}):
        report(t, [r for r in ok if r['type'] == t])

    print()
    # 표정속도 = 정차·신호를 포함한 실효 속도. 거리 ÷ 시간이라 해석이 흔들리지 않는다.
    # (3변수 회귀도 해봤는데 우리 시간이 거리보다 정류장 수에 지배돼 116km/h 같은
    #  퇴화한 해가 나온다. 그 자체가 "우리 모델이 거리를 거의 안 본다"는 증거다.)
    print('표정속도 (구간거리 ÷ 소요시간, 정차 포함):')
    print('  %-12s %6s %6s %6s   %s' % ('', '카카오', '우리', '비', 'n'))
    def speeds(label, sub_):
        sub_ = [r for r in sub_ if r['kakao_m'] > 300]
        if len(sub_) < 3:
            return
        kv = pct([r['kakao_m'] / r['kakao_sec'] * 3.6 for r in sub_], 0.5)
        ov = pct([r['kakao_m'] / r['ours_sec'] * 3.6 for r in sub_], 0.5)
        print('  %-12s %5.1f  %5.1f  %5.2f   %d' % (label, kv, ov, ov / kv, len(sub_)))
    speeds('전체', ok)
    speeds('실측노선', [r for r in ok if r['src'] == '실측'])
    speeds('추정노선', [r for r in ok if r['src'] == '추정'])
    for t in sorted({r['type'] for r in ok if r['type']}):
        speeds(t, [r for r in ok if r['type'] == t])

    k = fit([{'m': r['kakao_m'], 'n': r['kakao_n'], 'sec': r['kakao_sec']} for r in ok])
    if k and k['kmh']:
        print('  → 카카오를 회귀하면 주행 %.0f km/h + 정류장당 %+.0f초. 우리는 정류장당 20초를 쓴다.'
              % (k['kmh'], k['dwell']))

    print()
    print('구간 거리 — 카카오 도로거리 ÷ 우리 직선거리 (경기는 이 비를 1.01 로 쓰고 있다):')
    for label, sub_ in (('서울 실측', [r for r in ok if r['src'] == '실측']),
                        ('서울·경기 추정', [r for r in ok if r['src'] == '추정'])):
        v = [r['kakao_m'] / r['ours_m'] for r in sub_ if r['ours_m'] and r['ours_m'] > 200]
        if len(v) >= 3:
            print('  %-12s n=%-4d 중앙 %.2f배 · 25%% %.2f · 75%% %.2f'
                  % (label, len(v), pct(v, 0.5), pct(v, 0.25), pct(v, 0.75)))
    for t in sorted({r['type'] for r in ok if r['type']}):
        v = [r['kakao_m'] / r['ours_m'] for r in ok if r['type'] == t and r['ours_m'] and r['ours_m'] > 200]
        if len(v) >= 5:
            print('  %-12s n=%-4d 중앙 %.2f배' % (t, len(v), pct(v, 0.5)))

    if '--fit' in sys.argv:
        import datetime
        refit(ok, datetime.datetime.now().strftime('%Y-%m-%d %H:%M'))

    print()
    print('가장 크게 어긋난 구간:')
    for r in sorted(ok, key=lambda r: -abs(r['ours_sec'] - r['kakao_sec']))[:12]:
        d = r['ours_sec'] - r['kakao_sec']
        print('  %-8s %-6s %-16s→%-16s 카카오 %4d초 · 우리 %4d초 · %+5d초 (%+4.0f%%) %s'
              % (r['no'], r['src'], r['from'][:14], r['to'][:14],
                 r['kakao_sec'], r['ours_sec'], d, d * 100.0 / r['kakao_sec'], r['od']))


# ── 보정 ────────────────────────────────────────────────────

CALIB = 'data/calibration.json'

# 흔들림을 무시하는 폭. 이보다 작게 움직이면 값을 바꾸지 않는다.
DEADBAND_SEC = 4
DEADBAND_DETOUR = 0.01
DEADBAND_FACTOR = 0.05
DETOUR_DEFAULT = 1.07
LONG_SEG_M = 1500.0
LONG_FACTOR_DEFAULT = 1.25


def current_calibration():
    try:
        return json.load(io.open(CALIB, encoding='utf-8'))
    except Exception:
        return {'dwellSec': 58, 'detour': 1.07}


def refit(ok, stamp):
    """카카오 기준값에 맞춰 보정값을 다시 계산하고 파일에 쓴다.

    **왜 여기서 하나.** 이 파이프라인은 사람 없이 돈다. 수집 스케줄이 하루 다섯 번
    속도 스냅샷을 찍는데, 표본이 바뀌면 정류장 통과 비용도 따라 바뀌어야 한다.
    그걸 코드 상수로 두면 사람이 고쳐야 하고, 그러면 안 고쳐진다.

    **맞추는 건 하나뿐이다.** 주행 배율과 정류장 비용은 이 자료로 구분되지 않는다
    (MAE 지형이 대각선 골짜기다). 그래서 **잰 값인 순항 속도는 건드리지 않고**
    재본 적 없는 정류장 통과 비용만 움직인다. 낮 스냅샷이 쌓여 순항 속도가 제 값을
    찾으면 이 값은 저절로 내려가야 한다 — 안 내려가면 다른 데가 틀린 것이다.

    **OD 를 반으로 갈라** 한쪽으로 맞추고 다른 쪽으로 평가한다. 같은 자료로 맞추고
    같은 자료로 자랑하면 아무것도 검증한 게 아니다.
    """
    cur = current_calibration()
    dw = int(cur.get('dwellSec', 58))

    # 구간거리 보정: 카카오 도로거리 ÷ 우리 직선거리. 직접 재는 값이라 맞추지 않고 잰다.
    ratios = [r['kakao_m'] / r['ours_m'] for r in ok if r['ours_m'] and r['ours_m'] > 300]
    detour = round(pct(ratios, 0.5), 3) if len(ratios) >= 30 else cur.get('detour', 1.07)

    ods = sorted({r['od'] for r in ok})
    train = {o for i, o in enumerate(ods) if i % 2 == 0}
    tr = [r for r in ok if r['od'] in train]
    te = [r for r in ok if r['od'] not in train]
    if len(tr) < 50 or len(te) < 50:
        print('  표본이 얇아 보정을 다시 맞추지 않는다 (학습 %d · 평가 %d)' % (len(tr), len(te)))
        return None

    fac = float(cur.get('longSegmentSpeedFactor', LONG_FACTOR_DEFAULT))

    def spacing(r):
        return r['kakao_m'] / max(1, r['ours_n'] - 1)

    def ride(r):
        """지금 모델의 순수 주행 시간. 긴 구간 배율은 이미 들어가 있으므로 되돌린다."""
        v = max(1.0, r['ours_sec'] - (r['ours_n'] - 1) * dw)
        return v * fac if spacing(r) >= LONG_SEG_M else v

    def stats(data, b, f):
        d = []
        for r in data:
            v = ride(r)
            if spacing(r) >= LONG_SEG_M:
                v /= f
            d.append(v + b * (r['ours_n'] - 1) - r['kakao_sec'])
        n = len(d)
        return (sum(d) / n, sum(abs(x) for x in d) / n,
                100.0 * sum(1 for x in d if abs(x)<= 180) / n)

    # 긴 구간 속도 배율. **이 칸에서만 속도가 식별된다** — 정류장이 몇 개 없어
    # 거리 항이 지배하기 때문이다. 짧은 구간에서는 거리와 정류장 수가 거의 비례해서
    # "속도"와 "정류장당 시간"이 서로를 흉내낼 수 있고, 그래서 하나만 맞춘다.
    longs = [r for r in tr if spacing(r) >= LONG_SEG_M]
    if len(longs) >= 20:
        newfac = min([round(1.0 + 0.05 * i, 2) for i in range(0, 41)],
                     key=lambda f: stats(longs, dw, f)[1])
    else:
        newfac = fac

    best = min(range(0, 181), key=lambda b: stats(tr, b, newfac)[1])

    # 불감대. 값을 뽑을 때마다 1~2초씩 흔들리는데(반올림 때문이다) 그때마다
    # GTFS 를 다시 만들면 스케줄이 헛돈다. 의미 있게 움직였을 때만 바꾼다.
    if abs(best - dw) < DEADBAND_SEC:
        best = dw
    if abs(detour - cur.get('detour', DETOUR_DEFAULT)) < DEADBAND_DETOUR:
        detour = cur.get('detour', DETOUR_DEFAULT)
    if abs(newfac - fac) < DEADBAND_FACTOR:
        newfac = fac

    bias, mae, within = stats(te, best, newfac)
    note = ('카카오 구간 %d개 · %s · 평가셋 편향 %+.0f초 · MAE %.0f초 · ±3분 %.0f%%'
            % (len(ok), stamp, bias, mae, within))
    out = {'dwellSec': best, 'detour': detour,
           'longSegmentMeters': LONG_SEG_M, 'longSegmentSpeedFactor': newfac,
           'note': note, 'fittedAt': stamp, 'sample': len(ok),
           'holdout': {'bias': round(bias), 'mae': round(mae), 'within3min': round(within, 1)}}
    json.dump(out, io.open(CALIB, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print()
    print('보정 갱신 → %s' % CALIB)
    print('  정류장 통과 %d초 (이전 %d초) · 구간거리 ×%.3f · %dm↑ 속도 ×%.2f (이전 ×%.2f)'
          % (best, dw, detour, LONG_SEG_M, newfac, fac))
    print('  평가셋(맞추는 데 안 쓴 OD 절반): 편향 %+.0f초 · MAE %.0f초 · ±3분 이내 %.0f%%'
          % (bias, mae, within))
    print('CALIBRATION %d %.3f %+0.f %.0f %.1f %.2f' % (best, detour, bias, mae, within, newfac))
    return out


if __name__ == '__main__':
    main()
