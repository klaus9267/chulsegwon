# -*- coding: utf-8 -*-
"""odcheck 결과 한 판을 지표 한 줄로 줄여 data/logs/accuracy.csv 에 쌓는다.

설계는 docs/LOOP.md. 읽는 법만 다시 적는다.

- **편향·MAE 는 우리 중앙값 − 카카오** 다. 카카오는 "지금 나가면"의 한 번이고 우리는
  그 시간대의 중앙값이라, 이 값이 0 이어야 맞는 건 아니다. **추세**를 본다.
- **net_wrong 이 진짜 결함이다.** 카카오가 우리 최솟값보다 빠르다는 건 어느 출발 분에도
  우리는 그 시간에 못 간다는 뜻 — 모르는 노선·급행·환승이 있다.
- 동결 표본(`frozen`)은 따로 센다. 루프가 그쪽을 못 보게 해서 과적합을 잡는 용도다.
"""
import csv
import io
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'data', 'logs', 'accuracy.csv')
HEAD = ['time', 'slot', 'samples', 'bias_min', 'mae_min', 'within3',
        'net_wrong', 'luck_only', 'we_optimistic', 'unreached',
        'frozen_n', 'frozen_bias', 'frozen_mae', 'source']


def median(v):
    s = sorted(v)
    return 0.0 if not s else float(s[len(s) // 2]) if len(s) % 2 else (s[len(s) // 2 - 1] + s[len(s) // 2]) / 2.0


def main():
    odcheck = sys.argv[1] if len(sys.argv) > 1 else 'data/out/verify/odcheck.csv'
    sample = sys.argv[2] if len(sys.argv) > 2 else ''

    frozen = {}
    slot = ''
    if sample and os.path.exists(sample):
        j = json.load(io.open(sample, encoding='utf-8'))
        slot = j.get('slot', '')
        for o in j.get('ods', []):
            frozen['%s|%s' % (o.get('origin'), o.get('dong'))] = bool(o.get('frozen'))

    rows = list(csv.DictReader(io.open(odcheck, encoding='utf-8')))
    if not rows:
        print('!! odcheck 결과가 비었다: %s' % odcheck)
        return 1

    diffs, fdiffs = [], []
    counts = {'망결손': 0, '배차운': 0, '우리낙관': 0, '도달못함': 0, '카카오없음': 0}
    for r in rows:
        counts[r['verdict']] = counts.get(r['verdict'], 0) + 1
        if r['verdict'] in ('도달못함', '카카오없음'):
            continue
        d = int(r['median']) - int(r['kakao'])
        if frozen.get('%s|%s' % (r['origin'], r['dong'])):
            fdiffs.append(d)
        else:
            diffs.append(d)

    def stat(v):
        if not v:
            return 0, 0.0, 0.0, 0.0
        bias = sum(v) / float(len(v))
        mae = sum(abs(x) for x in v) / float(len(v))
        within = 100.0 * sum(1 for x in v if abs(x) <= 3) / len(v)
        return len(v), bias, mae, within

    n, bias, mae, within = stat(diffs)
    fn, fbias, fmae, _ = stat(fdiffs)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    new = not os.path.exists(OUT)
    with io.open(OUT, 'a', encoding='utf-8', newline='') as f:
        w = csv.writer(f)
        if new:
            w.writerow(HEAD)
        w.writerow([
            __import__('datetime').datetime.now().strftime('%Y-%m-%d %H:%M'), slot, n,
            '%+.1f' % bias, '%.1f' % mae, '%.1f' % within,
            counts.get('망결손', 0), counts.get('배차운', 0), counts.get('우리낙관', 0),
            counts.get('도달못함', 0), fn, '%+.1f' % fbias, '%.1f' % fmae,
            os.path.basename(sample) or os.path.basename(odcheck),
        ])
    print('표본 %d (동결 %d) · 편향 %+.1f분 · MAE %.1f분 · ±3분 %.1f%%' % (n, fn, bias, mae, within))
    print('판정 — 망결손 %d · 배차운 %d · 우리낙관 %d%s' % (
        counts.get('망결손', 0), counts.get('배차운', 0), counts.get('우리낙관', 0),
        (' · 도달못함 %d' % counts['도달못함']) if counts.get('도달못함') else ''))
    print('-> %s' % OUT)
    return 0


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    sys.exit(main())
