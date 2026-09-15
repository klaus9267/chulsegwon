# -*- coding: utf-8 -*-
"""철도데이터포털 「도시광역철도운행정보」 xlsx → 우리가 읽는 CSV.

## 왜 파이썬인가

빌더(Kotlin)의 의존성은 Jackson 하나다. xlsx 를 읽자고 POI 를 들이면 전이 의존성이
한 무더기 따라온다. GBIS 엑셀도 같은 이유로 `tools/gbis_village.py` 가 푼다.
여기서는 **자르고 옮기기만** 하고, 역명을 우리 망에 붙이는 일은 Kotlin 이 한다
(망을 들고 있는 쪽이 거기라서).

## 무엇을 가져오나

코레일 광역철도 10개 노선 중 **서울교통공사 시각표가 안 덮는 5개**만:
수인분당선 · 경의중앙선 · 경춘선 · 서해선 · 경강선.

경부선·경인선·일산선·안산과천선은 각각 1·1·3·4호선이고 그건
`seoul-metro-timetable.csv` 가 이미 덮는다. 둘 다 넣으면 **열차가 두 배가 된다.**
동해선은 부산이라 범위 밖이다.

## 함정

- **시각이 엑셀 소수**다. `0.22916666…` = 하루의 22.9% = 05:30. 30초 단위.
- **역명이 5글자로 잘려 있다.** `강남구`(강남구청) · `남동인`(남동인더스파크) ·
  `평내호`(평내호평). 그리고 코레일 내부 표기가 섞인다 — `신판교`(판교) ·
  `경광주`(경기광주) · `신김포`(김포공항). 자르지 않고 그대로 넘긴다.
- `요일구분` 은 평일/휴일 둘뿐이다(토요일 없음). 평일만 쓴다.
"""
import io
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import xlsx_read  # noqa: E402

# 노선명 → 우리 노선 이름. 여기 없는 노선은 안 가져온다.
LINES = {
    '수인분당선': '수인분당',
    '경의중앙선': '경의중앙',
    '경춘선': '경춘',
    '서해선': '서해',
    '경강선': '경강',
}

SRC = os.path.join('data', 'raw', 'rail', 'kric-korail-wide.xlsx')
OUT = os.path.join('data', 'raw', 'rail', 'kric-wide.csv')


def sec(v):
    """엑셀 소수 → 하루 안의 초. 빈 칸은 None."""
    v = (v or '').strip()
    if not v:
        return None
    try:
        return int(round(float(v) * 86400))
    except ValueError:
        return None


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else SRC
    out = sys.argv[2] if len(sys.argv) > 2 else OUT
    rows = xlsx_read.all_rows(src)
    head = None
    idx = {}
    n = 0
    kept = 0
    lines_seen = {}
    with io.open(out, 'w', encoding='utf-8', newline='\n') as f:
        f.write('line,train,express,station,arr,dep\n')
        for r in rows:
            if head is None:
                if r and r[0] == '열차번호':
                    head = r
                    idx = {name: i for i, name in enumerate(head) if name}
                continue
            n += 1
            if len(r) <= idx['정거장출발시각']:
                continue
            if r[idx['요일구분']] != '평일':
                continue
            line = LINES.get(r[idx['노선명']])
            if not line:
                continue
            a = sec(r[idx['정거장도착시각']])
            d = sec(r[idx['정거장출발시각']])
            if a is None and d is None:
                continue
            st = r[idx['운행구간정거장']].strip()
            if not st:
                continue
            f.write('%s,%s,%s,%s,%s,%s\n' % (
                line, r[idx['열차번호']].strip(),
                '1' if r[idx['운행유형']] == '급행' else '0',
                st, a if a is not None else '', d if d is not None else ''))
            kept += 1
            lines_seen[line] = lines_seen.get(line, 0) + 1
    print('%s: %s행 중 %s행 (평일 · 대상 5개 노선)' % (os.path.basename(src), f'{n:,}', f'{kept:,}'))
    for k in sorted(lines_seen):
        print('   %-8s %s' % (k, f'{lines_seen[k]:,}'))
    print('-> %s' % out)


if __name__ == '__main__':
    # collect.ps1 이 UTF-8 로 읽는다. 파이프 출력의 기본값(CP949)으로 두면 로그 한글이 깨진다.
    sys.stdout.reconfigure(encoding='utf-8')
    main()
