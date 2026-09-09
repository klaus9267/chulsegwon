# -*- coding: utf-8 -*-
"""GBIS 엑셀에서 **TAGO 가 안 주는 노선**만 뽑아 TAGO 모양의 JSONL 로 옮긴다.

**왜 필요한가.** TAGO(공공데이터포털 버스노선정보)에는 경기도 마을버스가 한 대도
없다. 인천은 13개를 주는데 경기는 0개다. 그런데 마을버스는 지하철역까지 데려다주는
바로 그 수단이라, 없으면 "역에서 먼 동네"가 통째로 도달 불가로 나온다.

GBIS(경기버스정보)가 신청 없이 내주는 엑셀 세 장에 그게 다 들어 있다. 확인한 것:

* **id 공간이 같다.** `ROUTE_ID 200000006` = TAGO `GGB200000006`
* **좌표가 같다.** 겹치는 정류장 29,452개의 중앙 차이가 0.0m 다
* **정류장 순서도 같다.** 겹치는 노선 2,156개 중 2,151개(99.8%)가 완전히 일치
* **GBIS 가 상위집합이다.** TAGO 2,157개 ⊂ GBIS 3,672개

그래서 새로 얻는 892개도 같은 자료로 보고 그대로 쓴다.

**출력이 TAGO 와 같은 모양인 이유.** `Gtfs.kt` 의 경기 로더를 디렉터리만 바꿔
한 번 더 부르면 끝나기 때문이다. 왕복 자르기·순환 판정·속도 모델을 다시 짜지 않는다.

⚠️ **엑셀이 65,000행마다 시트를 갈아탄다.** `sheet1` 만 읽으면 3,672개 노선 중
856개만 보이고, 그게 "경유정류소가 없다"는 잘못된 결론으로 이어진다.
[xlsx_read.all_rows] 를 쓸 것.
"""
import io
import json
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from xlsx_read import all_rows

# 유형 이름표. **서울 마을버스와 갈라놓는 것이 목적이다.**
#
# 처음엔 그냥 마을버스로 뒀다. 그런데 `Gtfs.SpeedModel` 은 유형 이름으로 속도 곡선을
# 고르고, 그 곡선은 서울 `sectSpd` 로만 배운다. 서울 마을버스 곡선은 10km 구간에서도
# 19km/h 가 상한이다 — 전 유형 최저이고 일반버스(35km/h)의 절반이다. 서울 마을버스는
# 좁은 골목과 언덕을 다니니 맞는 값이지만, 경기 마을버스는 교외 간선을 탄다.
#
# 이름을 가르면 그 곡선 대신 전역 곡선으로 떨어진다. 카카오 기준셋의 경기 마을버스
# 구간 94개로 잰 결과:
#
#   옛 이름표(서울 곡선)  편향 +115초 (+31%) · MAE 129초 · ±3분 77% · 표정속도 0.77배
#   새 이름표(전역 곡선)  편향   -4초        · MAE  77초 · ±3분 95% · 표정속도 0.95배
#
# 정차시간은 안 건드렸다. 유형별 정차를 넣어볼까 했는데, 같은 94개로 재보니 전역
# 59초가 이미 편향 0 이었다.
#
# ⚠️ 노선 전체를 훑는 카카오 길찾기 5개(고양 N001·075B, 김포 54, 용인 810, 하남 1)
# 로는 아직 평균 +12분 느리게 나온다. 구간 단위로는 안 보이고 30정류장 넘는 긴
# 교외 노선에서만 쌓이는 오차다. 표본이 5개뿐이라 여기에 맞춰 상수를 흔들지 않았다.
VILLAGE_TYPE = '마을버스(경기)'

SRC = 'data/raw/gbis'
DST = 'data/raw/gbis'
TAGO = 'data/raw/bus/routes.jsonl'

# 노선정보 열
R_GOV, R_OP, R_NO, R_ID = 1, 2, 3, 4
R_FROM, R_TO = 5, 7
R_HW_WEEK, R_HW_WEEKEND = 9, 10
R_UP_FIRST, R_UP_LAST, R_DN_FIRST, R_DN_LAST = 11, 12, 13, 14
# 경유정류소 열
S_ROUTE, S_ORD, S_STATION = 1, 3, 5
# 정류소 열. GPS_X/GPS_Y 는 도-분 표기(126°59.264′)이고, 십진도는 X/Y 다.
# 열 이름만 보고 GPS_* 를 쓰면 좌표가 지구 반대편으로 간다 — 실제로 그랬다.
T_NAME, T_LON, T_LAT, T_ID = 1, 6, 7, 8


def cell(row, i):
    return row[i].strip() if i < len(row) and row[i] else ''


def minutes(v):
    """`40분~50분` → 45, `30분` → 30, 빈 값 → None.

    범위면 중간을 쓴다. GBIS 가 범위로 주는 건 배차가 실제로 흔들린다는 뜻이라
    어느 쪽을 골라도 틀리는데, 중간이 한쪽으로 치우치지 않는다.
    """
    nums, cur = [], ''
    for ch in v:
        if ch.isdigit():
            cur += ch
        else:
            if cur:
                nums.append(int(cur))
            cur = ''
    if cur:
        nums.append(int(cur))
    nums = [n for n in nums if 0 < n <= 300]
    if not nums:
        return None
    return int(round(sum(nums) / len(nums)))


def clock(v):
    """`06:00` → `0600`. 자정 넘김은 `Gtfs.hmAfter` 가 알아서 24시로 민다."""
    d = ''.join(c for c in v if c.isdigit())
    return d[:4] if len(d) == 4 else None


def main():
    known = set()
    with io.open(TAGO, encoding='utf-8') as f:
        for line in f:
            rid = json.loads(line)['id']
            if rid.startswith('GGB'):
                known.add(rid[3:])

    stations = {}
    for r in all_rows(os.path.join(SRC, 'GGD_StationInfo_M.xlsx')):
        sid = cell(r, T_ID)
        if not sid or sid == 'STATION_ID':
            continue
        try:
            lat, lon = float(cell(r, T_LAT)), float(cell(r, T_LON))
        except ValueError:
            continue
        if not (32.0 < lat < 40.0 and 124.0 < lon < 132.0):
            continue
        stations[sid] = (cell(r, T_NAME) or sid, lat, lon)

    seq = defaultdict(list)
    for r in all_rows(os.path.join(SRC, 'GGD_RouteStationInfo_M.xlsx')):
        rid, sid = cell(r, S_ROUTE), cell(r, S_STATION)
        if not rid or not sid or rid == 'ROUTE_ID':
            continue
        try:
            seq[rid].append((int(cell(r, S_ORD)), sid))
        except ValueError:
            continue

    routes, details, stopsout = [], [], []
    seen = set()
    skipped_known = skipped_short = 0
    no_coord = 0
    seoul_ids = set()
    for r in all_rows(os.path.join(SRC, 'GGD_RouteInfo_M.xlsx')):
        rid = cell(r, R_ID)
        if not rid or rid == '노선ID' or rid in seen:
            continue
        seen.add(rid)
        if rid in known:
            skipped_known += 1
            continue

        ordered = sorted(seq.get(rid, []))
        stops = []
        for order, sid in ordered:
            if sid not in stations:
                no_coord += 1
                continue
            name, lat, lon = stations[sid]
            if sid.startswith('1') and len(sid) == 9:
                seoul_ids.add(sid)
            stops.append({'stop': 'GGB' + sid, 'name': name,
                          'ord': order, 'lat': lat, 'lon': lon, 'up': None})
        if len(stops) < 2:
            skipped_short += 1
            continue

        gov = cell(r, R_GOV)
        no = cell(r, R_NO) or rid
        first = clock(cell(r, R_UP_FIRST)) or clock(cell(r, R_DN_FIRST))
        last = clock(cell(r, R_UP_LAST)) or clock(cell(r, R_DN_LAST))
        hw = minutes(cell(r, R_HW_WEEK))
        hw_end = minutes(cell(r, R_HW_WEEKEND)) or hw

        # 기종점은 GBIS 가 주는 값을 쓴다. 순환 노선은 정류장 열의 처음과 끝이
        # 같은 자리라, 열에서 뽑으면 "초당고 → 초당고" 가 되어 노선을 못 알아본다.
        routes.append({'city': gov, 'id': 'GGB' + rid, 'no': no, 'type': VILLAGE_TYPE,
                       'from': cell(r, R_FROM) or stops[0]['name'],
                       'to': cell(r, R_TO) or stops[-1]['name']})
        details.append({'id': 'GGB' + rid, 'city': gov, 'no': no, 'type': VILLAGE_TYPE,
                        'first': first, 'last': last,
                        'headwayWeekday': hw, 'headwaySat': hw_end, 'headwaySun': hw_end})
        stopsout.append({'id': 'GGB' + rid, 'city': gov, 'stops': stops})

    def dump(name, rows):
        p = os.path.join(DST, name)
        with io.open(p, 'w', encoding='utf-8', newline='\n') as f:
            for o in rows:
                f.write(json.dumps(o, ensure_ascii=False) + '\n')
        return p

    dump('routes.jsonl', routes)
    dump('route-detail.jsonl', details)
    dump('route-stops.jsonl', stopsout)

    n = len(routes)
    print('GBIS 에만 있는 노선 %s개를 옮겼다 (TAGO 와 겹쳐 건너뛴 노선 %s개)'
          % (format(n, ','), format(skipped_known, ',')))
    print('  정류장 %s회 통과 · 노선당 중앙 %d개'
          % (format(sum(len(s['stops']) for s in stopsout), ','),
             sorted(len(s['stops']) for s in stopsout)[n // 2]))
    print('  배차를 아는 노선 %d (%.0f%%) · 첫차를 아는 노선 %d (%.0f%%)'
          % (sum(1 for d in details if d['headwayWeekday']),
             100.0 * sum(1 for d in details if d['headwayWeekday']) / n,
             sum(1 for d in details if d['first']),
             100.0 * sum(1 for d in details if d['first']) / n))
    print('  좌표가 없어 뺀 통과 %s회 · 정류장이 2개 미만이라 뺀 노선 %d개'
          % (format(no_coord, ','), skipped_short))
    if seoul_ids:
        print('  서울 id 정류장 %d개를 지난다 — 서울 피드와 같은 기둥이 두 번 들어간다.'
              ' 환승표가 이어준다' % len(seoul_ids))


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    main()
