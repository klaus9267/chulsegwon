# -*- coding: utf-8 -*-
"""도달권 데이터를 공개한다 — 릴리스 자산을 바꾸고 사이트를 다시 배포한다.

`collect.ps1` 이 매 회차 끝에 부른다. 사람 확인 없이 공개 사이트가 바뀐다
(T-03, 2026-09-15 결정 — 사이드 프로젝트라 사용자가 없다).

## 무엇을 지키나

확인 없이 나가는 만큼 **깨진 걸 내보내지 않는 것**이 이 스크립트의 전부다.

1. **데이터가 온전할 때만.** 역 수 = 행렬 파일 수, 동네 수 일치, 행렬 크기 동일.
   그리고 **매니페스트가 행렬보다 나중에 써졌는지** 본다. `DongMatrix` 는 행렬을 하나씩
   쓰고 매니페스트를 맨 끝에 쓴다. 빌드가 중간에 죽으면 파일 개수는 맞는데 앞쪽은 새 것,
   뒤쪽은 옛 것이 섞인다 — 개수만 세서는 못 잡는다.
2. **그 데이터를 만든 코드가 `main` 과 같을 때만.** 배포는 `main` 의 웹 코드로 빌드된다.
   `dev` 에서 매니페스트 형식을 바꾸고 아직 `main` 에 안 올렸으면, 옛 웹 코드가 새 데이터를
   받아 사이트가 깨진다. 다르면 **올리지도 배포하지도 않는다** — 그러면 "공개 자산은 늘
   `main` 코드로 만든 것"이 유지된다. `main` 에 올린 다음 회차에 나간다.
3. **데이터 먼저, 배포 나중**([ADR-12](../docs/HANDOFF.md)). 자산만 바꿔서는 사이트가 안 바뀌고,
   워크플로는 돌 때 자산을 받는다.
4. **바뀌었을 때만.** 데이터 파일 내용의 해시를 마지막으로 공개한 것과 댄다.
   배포가 **성공했을 때만** 해시를 기록한다 — 실패하면 다음 회차가 다시 시도한다.

    python tools/publish.py              # 평소
    python tools/publish.py --dry-run    # 검사만. 올리지 않는다
    python tools/publish.py --force      # 해시가 같아도 올린다
"""
import argparse
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import time
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, 'web', 'public', 'data')
OUT_DIR = os.path.join(ROOT, 'data', 'out', 'publish')
ASSET = 'chulsegwon-data.tgz'          # pages.yml 이 이 이름으로 받는다. 파일 이름이 곧 자산 이름이다
STAMP = os.path.join(ROOT, 'data', 'out', 'published.json')
TAG = 'data-latest'
WORKFLOW = 'pages.yml'
BRANCH = 'main'
# 데이터 형식과 그걸 읽는 코드. 여기가 main 과 다르면 공개하지 않는다.
CODE_PATHS = ['builder/src', 'web/src', 'web/index.html']


def say(msg):
    # 배치는 Windows PowerShell 5.1 이라 파이썬 출력이 CP949 로 넘어간다. em dash(—)는
    # CP949 에 없어서 **print 에서 UnicodeEncodeError 로 죽는다** — Git Bash 에서 시험할 땐
    # PYTHONIOENCODING=utf-8 을 붙여서 안 보였다. 로그용 문자로 바꿔 쓴다.
    print(msg.replace('—', '-'), flush=True)


def run(cmd, check=True, timeout=None):
    r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True,
                       encoding='utf-8', errors='replace', timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError('%s 실패 (%d): %s' % (' '.join(cmd[:3]), r.returncode,
                                                (r.stderr or r.stdout).strip()[:300]))
    return r


# ── 1) 데이터가 온전한가 ────────────────────────────────────────
def check_data():
    """문제가 있으면 이유 문자열, 없으면 None 과 요약."""
    mf = os.path.join(DATA, 'manifest.json')
    if not os.path.exists(mf):
        return '매니페스트가 없다', None
    m = json.load(io.open(mf, encoding='utf-8'))
    stations = len(m.get('stations', []))
    dongs = len(m.get('dongs', []))
    mdir = os.path.join(DATA, 'matrix')
    bins = [f for f in os.listdir(mdir) if f.endswith('.bin')] if os.path.isdir(mdir) else []
    if len(bins) != stations:
        return '행렬 %d개 ≠ 역 %d개' % (len(bins), stations), None
    idx = sorted(int(f[:-4]) for f in bins if f[:-4].isdigit())
    if idx != list(range(stations)):
        return '행렬 색인이 0..%d 로 이어지지 않는다' % (stations - 1), None
    paths = [os.path.join(mdir, f) for f in bins]
    sizes = {os.path.getsize(p) for p in paths}
    if len(sizes) != 1:
        return '행렬 크기가 제각각이다 %s' % sorted(sizes)[:4], None
    newest = max(os.path.getmtime(p) for p in paths)
    # 매니페스트가 맨 마지막에 써진다. 행렬이 더 새것이면 빌드가 끝나지 않은 것이다.
    if os.path.getmtime(mf) + 1 < newest:
        return '매니페스트보다 새 행렬이 있다 — 빌드가 끝나지 않았다', None
    dj = os.path.join(DATA, 'dongs.json')
    if not os.path.exists(dj):
        return 'dongs.json 이 없다', None
    d = json.load(io.open(dj, encoding='utf-8'))
    dd = d['dongs'] if isinstance(d, dict) else d
    if len(dd) != dongs:
        return 'dongs.json %d개 ≠ 매니페스트 동네 %d개' % (len(dd), dongs), None
    for need in ('rentals.json', 'amenities.json'):
        if not os.path.exists(os.path.join(DATA, need)):
            return '%s 가 없다' % need, None
    return None, {'stations': stations, 'dongs': dongs, 'matrix_bytes': sizes.pop(),
                  'built': datetime.fromtimestamp(os.path.getmtime(mf)).strftime('%m-%d %H:%M')}


def data_hash():
    h = hashlib.sha256()
    for base, _, files in sorted(os.walk(DATA)):
        for f in sorted(files):
            p = os.path.join(base, f)
            h.update(os.path.relpath(p, DATA).replace(os.sep, '/').encode('utf-8'))
            with open(p, 'rb') as fh:
                for chunk in iter(lambda: fh.read(1 << 20), b''):
                    h.update(chunk)
    return h.hexdigest()


# ── 2) 코드가 main 과 같은가 ────────────────────────────────────
def code_matches(base_ref):
    run(['git', 'fetch', '--quiet', 'origin', BRANCH], timeout=120)
    r = run(['git', 'diff', '--quiet', base_ref, '--'] + CODE_PATHS, check=False)
    if r.returncode == 0:
        return True, None
    names = run(['git', 'diff', '--name-only', base_ref, '--'] + CODE_PATHS, check=False).stdout.split()
    return False, names


def repo_slug():
    url = run(['git', 'remote', 'get-url', 'origin']).stdout.strip()
    m = re.search(r'github\.com[:/](.+?)(?:\.git)?$', url)
    if not m:
        raise RuntimeError('origin 이 GitHub 가 아니다: ' + url)
    return m.group(1)


# ── 3) 묶고 · 올리고 · 배포 ─────────────────────────────────────
def pack(expect_stations):
    os.makedirs(OUT_DIR, exist_ok=True)
    out = os.path.join(OUT_DIR, ASSET)
    tmp = out + '.tmp'
    # tarfile 은 경로 구분자를 / 로 바꿔 넣는다. Windows tar 처럼 C: 를 원격 호스트로
    # 읽는 일도 없다(ADR-12).
    with tarfile.open(tmp, 'w:gz') as t:
        t.add(DATA, arcname='data')
    # 푼 쪽에서 다시 센다. 워크플로가 보는 게 이거다.
    with tarfile.open(tmp, 'r:gz') as t:
        names = t.getnames()
    mats = sum(1 for n in names if n.startswith('data/matrix/') and n.endswith('.bin'))
    if 'data/manifest.json' not in names or mats != expect_stations:
        os.remove(tmp)
        raise RuntimeError('묶은 파일이 이상하다 (manifest %s · 행렬 %d/%d)' % (
            'data/manifest.json' in names, mats, expect_stations))
    if os.path.exists(out):
        # 되돌릴 일에 대비해 하나 전 것을 남긴다.
        shutil.copyfile(out, os.path.join(OUT_DIR, 'chulsegwon-data.prev.tgz'))
    os.replace(tmp, out)
    return out


def find_run(slug, since):
    """방금 dispatch 한 실행을 찾는다. 목록에 뜨기까지 몇 초 걸린다."""
    for _ in range(24):
        r = run(['gh', 'run', 'list', '--repo', slug, '--workflow', WORKFLOW,
                 '--event', 'workflow_dispatch', '--limit', '5',
                 '--json', 'databaseId,createdAt'], check=False)
        try:
            for x in json.loads(r.stdout or '[]'):
                t = datetime.strptime(x['createdAt'], '%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=timezone.utc)
                if t.timestamp() >= since - 5:
                    return x['databaseId']
        except (ValueError, KeyError):
            pass
        time.sleep(5)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('--force', action='store_true')
    ap.add_argument('--base-ref', default='origin/' + BRANCH,
                    help='코드를 대볼 기준. 검사 경로를 시험할 때만 바꾼다')
    a = ap.parse_args()

    for tool in ('git', 'gh'):
        if not shutil.which(tool):
            say('!! %s 가 PATH 에 없다 — 공개를 건너뛴다' % tool)
            return 1

    why, info = check_data()
    if why:
        say('· 공개 건너뜀 — 데이터가 온전하지 않다: ' + why)
        return 0
    say('   데이터: 역 %(stations)d · 동네 %(dongs)d · 행렬 %(matrix_bytes)d바이트 · 빌드 %(built)s' % info)

    digest = data_hash()
    prev = {}
    if os.path.exists(STAMP):
        try:
            prev = json.load(io.open(STAMP, encoding='utf-8'))
        except ValueError:
            prev = {}
    if prev.get('hash') == digest and not a.force:
        say('· 공개 건너뜀 — %s 에 올린 것과 같다' % prev.get('at', '?'))
        return 0

    same, diff = code_matches(a.base_ref)
    if not same:
        say('· 공개 건너뜀 — 이 데이터를 만든 코드가 %s 와 다르다 (%d개 파일). '
            'main 에 올리면 다음 회차에 나간다' % (a.base_ref, len(diff)))
        for n in diff[:5]:
            say('     ' + n)
        return 0

    slug = repo_slug()
    if a.dry_run:
        say('· [dry-run] 올릴 준비 됨 — %s · 해시 %s…' % (slug, digest[:12]))
        return 0

    out = pack(info['stations'])
    mb = os.path.getsize(out) / 1e6
    say('   묶음 %.1fMB' % mb)

    run(['gh', 'release', 'upload', TAG, out, '--clobber', '--repo', slug], timeout=600)
    say('   자산 교체: %s/%s' % (TAG, ASSET))

    since = time.time()
    run(['gh', 'workflow', 'run', WORKFLOW, '--ref', BRANCH, '--repo', slug], timeout=60)
    rid = find_run(slug, since)
    if rid is None:
        say('!! 배포 실행을 못 찾았다 — 자산은 바뀌었다. 다음 main 푸시 때 나간다')
        return 1
    say('   배포 실행 %s — 기다린다' % rid)
    w = run(['gh', 'run', 'watch', str(rid), '--repo', slug, '--exit-status', '--interval', '15'],
            check=False, timeout=1200)
    if w.returncode != 0:
        say('!! 배포 실패 (실행 %s) — 해시를 기록하지 않는다. 다음 회차가 다시 시도한다' % rid)
        return 1

    # 워크플로가 실제로 받은 행렬 수를 로그에서 확인한다. "성공"인데 옛 자산이면 여기서 걸린다.
    log = run(['gh', 'run', 'view', str(rid), '--repo', slug, '--log'], check=False, timeout=120).stdout
    got = re.search(r'matrix (\d+) 개', log)
    if not got or int(got.group(1)) != info['stations']:
        say('!! 배포는 성공했는데 워크플로가 받은 행렬이 %s개다 (기대 %d) — 해시를 기록하지 않는다' % (
            got.group(1) if got else '?', info['stations']))
        return 1

    json.dump({'hash': digest, 'at': datetime.now().strftime('%Y-%m-%d %H:%M'), 'run': rid,
               'asset_mb': round(mb, 1), 'stations': info['stations'], 'dongs': info['dongs']},
              io.open(STAMP, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    say('   공개 완료 — 실행 %s · 워크플로가 받은 행렬 %s개 · https://%s.github.io/%s/' % (
        rid, got.group(1), slug.split('/')[0], slug.split('/')[1]))
    return 0


if __name__ == '__main__':
    # 그래도 못 찍는 글자가 남으면 죽지 말고 ? 로 찍는다. 공개 여부가 로그 글자 하나에 걸리면 안 된다.
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors='replace')
        except (AttributeError, ValueError):
            pass
    try:
        sys.exit(main())
    except Exception as e:                      # 배치 로그에 한 줄로 남기고 다음 단계로
        say('!! 공개 실패: %s' % e)
        sys.exit(1)
