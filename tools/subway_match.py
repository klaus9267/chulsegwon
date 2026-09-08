# -*- coding: utf-8 -*-
"""카카오맵이 준 지하철 구간을 우리 지하철 GTFS 와 대조한다.

버스 대조기([gtfs_match.py])와 같은 발상이다. 카카오 대중교통 길찾기는 경로를
leg 으로 쪼개 주고, 지하철 leg 은 `2호선 · 강남 승차 · 선릉 하차 · 2구간 · 210초`
형태다. 우리 `stop_times.txt` 와 같은 단위라 바로 붙는다.

**왜 따로 검증하나.** 지하철 도달권은 카카오맵 18쌍 대조로 편향 −0.9분까지
맞춰놨다고 적혀 있는데, 그건 **문앞-문앞** 값이다. ADR-29 의 반박 검증이
그 집계가 대기항(+3.3분)과 주행속도(−12.2%)를 상쇄해 감춘다고 지적했다.
즉 **승차 구간 시간 자체는 아직 검증된 적이 없다.** 이 파일이 그걸 잰다.

**덤으로 재는 것.** 우리 역 그래프는 2020년 자료라 GTX-A·신림선·대곡소사선·
8호선 연장·신분당선 신사 연장이 빠져 있다. 카카오가 그 노선으로 안내하는 leg 은
여기서 '노선없음'으로 떨어지므로, **그 부채의 실제 크기가 숫자로 나온다.**
"""
import csv
import io
import json
import re
import sys
import zipfile
from collections import defaultdict

sys.stdout.reconfigure(encoding='utf-8')

ZIP = 'data/out/gtfs-subway.zip'

# 카카오 노선명 → 우리 route_short_name.
# 우리 코드는 GML 원본이 쓰던 것이라 뜻이 안 보인다. 역 목록으로 확인해 붙였다.
LINE = {
    '1호선': '1', '2호선': '2', '3호선': '3', '4호선': '4', '5호선': '5',
    '6호선': '6', '7호선': '7', '8호선': '8', '9호선': '9',
    '공항철도': 'A', '수인분당선': 'B', '분당선': 'B', '용인경전철': 'E', '에버라인': 'E',
    '경춘선': 'G', '인천1호선': 'I', '인천2호선': 'I2', '경의중앙선': 'K', '경의선': 'K',
    '중앙선': 'K', '경강선': 'KK', '김포골드라인': 'KP', '김포도시철도': 'KP',
    '신분당선': 'S', '서해선': 'SH', '의정부경전철': 'U', '우이신설선': 'W',
}


def line_code(v):
    """`9호선일반`, `수인분당선급행` 처럼 등급이 붙어 온다. 긴 이름부터 맞춰본다."""
    v = (v or '').strip()
    for k in sorted(LINE, key=len, reverse=True):
        if v.startswith(k):
            return LINE[k]
    return None


def norm(s):
    """`강남역`·`강남` 을 같게 본다. 괄호 안은 버린다."""
    s = re.sub(r'\([^)]*\)', '', s or '')
    s = re.sub(r'[\s.·,\-]', '', s)
    return s[:-1] if s.endswith('역') and len(s) > 2 else s


def load(zp=ZIP):
    z = zipfile.ZipFile(zp)

    def rd(n):
        return csv.DictReader(io.TextIOWrapper(z.open(n), encoding='utf-8'))

    # 승강장 이름은 "강남 2" 형태다. 역 이름만 떼어 쓴다.
    station = {}
    for r in rd('stops.txt'):
        if r['location_type'] != '0':
            continue
        station[r['stop_id']] = norm(r['stop_name'].rsplit(' ', 1)[0])
    routes = {r['route_id']: r['route_short_name'] for r in rd('routes.txt')}
    trip_route = {t['trip_id']: t['route_id'] for t in rd('trips.txt')}
    seq = defaultdict(list)
    for r in rd('stop_times.txt'):
        h, m, s = r['departure_time'].split(':')
        seq[r['trip_id']].append((r['stop_id'], int(h) * 3600 + int(m) * 60 + int(s)))
    by_line = defaultdict(list)
    for t, sq in seq.items():
        by_line[routes.get(trip_route.get(t), '')].append(sq)
    return station, by_line


def find(by_line, station, code, frm, to, nhops=None):
    """가장 짧은 (승차, 하차) 짝을 고른다. 순환선은 같은 역이 두 번 나온다."""
    nf, nt = norm(frm), norm(to)
    best = None
    for sq in by_line.get(code, []):
        names = [station.get(sid, '') for sid, _ in sq]
        for i, v in enumerate(names):
            if v != nf:
                continue
            for j in range(i + 1, len(names)):
                if names[j] == nt:
                    cand = (j - i, sq[j][1] - sq[i][1])
                    if best is None or cand[0] < best[0]:
                        best = cand
                    break
    return best


def pct(v, q):
    v = sorted(v)
    return v[min(len(v) - 1, int(len(v) * q))]


def main():
    station, by_line = load()
    raw = json.load(io.open(sys.argv[1], encoding='utf-8'))
    legs = raw['legs'] if isinstance(raw, dict) else raw

    rows = []
    seen = set()
    unknown = defaultdict(int)
    for lg in legs:
        for name in lg['v'].split('/'):
            code = line_code(name)
            if code is None:
                unknown[name.strip()] += 1
                continue
            key = (code, norm(lg['f']), norm(lg['t']))
            if key in seen:
                continue
            seen.add(key)
            hit = find(by_line, station, code, lg['f'], lg['t'], lg.get('n'))
            rows.append({
                'line': name, 'code': code, 'od': lg.get('od', ''),
                'from': lg['f'], 'to': lg['t'],
                'kakao_n': lg.get('n'), 'kakao_sec': lg['sec'], 'kakao_m': lg['m'],
                'ours_n': hit[0] if hit else None,
                'ours_sec': hit[1] if hit else None,
                'why': None if hit else ('노선없음' if code not in by_line else '구간없음'),
            })

    json.dump(rows, io.open(sys.argv[2], 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    ok = [r for r in rows if r['ours_sec']]
    print('구간 %d개 · 우리 데이터에서 찾음 %d개 (%.0f%%)'
          % (len(rows), len(ok), 100.0 * len(ok) / max(1, len(rows))))

    if unknown:
        print()
        print('우리 망에 아예 없는 노선 (2020년 그래프의 부채):')
        for k, v in sorted(unknown.items(), key=lambda x: -x[1]):
            print('  %-16s %d개 구간' % (k, v))

    miss = defaultdict(list)
    for r in rows:
        if not r['ours_sec']:
            miss[r['why']].append('%s %s→%s' % (r['line'], r['from'], r['to']))
    for k, v in miss.items():
        print()
        print('%s %d건: %s' % (k, len(v), ' · '.join(v[:6])))

    if not ok:
        return

    def report(label, sub):
        if len(sub) < 3:
            return
        d = [r['ours_sec'] - r['kakao_sec'] for r in sub]
        rel = [(r['ours_sec'] - r['kakao_sec']) * 100.0 / r['kakao_sec'] for r in sub]
        within = sum(1 for x in d if abs(x) <= 180)
        print('  %-12s n=%-4d 편향 %+5.0f초 (%+5.1f%%) · 중앙 %+5.0f초 · MAE %4.0f초 · ±3분 %3.0f%%'
              % (label, len(sub), sum(d) / len(d), sum(rel) / len(rel),
                 pct(d, 0.5), sum(abs(x) for x in d) / len(d), 100.0 * within / len(sub)))

    print()
    print('우리 − 카카오 (승차 구간만, 도보·대기 제외):')
    report('전체', ok)
    for c in sorted({r['code'] for r in ok}):
        report(c, [r for r in ok if r['code'] == c])

    print()
    print('표정속도 (구간거리 ÷ 소요시간):')
    v = [r for r in ok if r['kakao_m'] > 500]
    if v:
        print('  카카오 %.1f · 우리 %.1f km/h  (n=%d)'
              % (pct([r['kakao_m'] / r['kakao_sec'] * 3.6 for r in v], 0.5),
                 pct([r['kakao_m'] / r['ours_sec'] * 3.6 for r in v], 0.5), len(v)))

    print()
    print('가장 크게 어긋난 구간:')
    for r in sorted(ok, key=lambda r: -abs(r['ours_sec'] - r['kakao_sec']))[:12]:
        d = r['ours_sec'] - r['kakao_sec']
        print('  %-10s %-12s→%-12s 카카오 %4d초/%2d · 우리 %4d초/%2d · %+5d초 (%+4.0f%%)'
              % (r['line'], r['from'][:10], r['to'][:10], r['kakao_sec'], r['kakao_n'],
                 r['ours_sec'], r['ours_n'], d, d * 100.0 / r['kakao_sec']))


if __name__ == '__main__':
    main()
