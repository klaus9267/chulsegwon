"""⚠️ 미완성 — 아직 운영에 쓰지 말 것.

Wikidata(CC0) 에서 수도권 전철 그래프를 만들어 GML 로 쓰려는 시도.

**확인된 것:** Wikidata 에는 2020년 GML 에 없는 최신 노선이 전부 있다.
GTX-A, 신림선, 별내선, 대곡소사선, 신분당선 신논현 연장, 서해선, 김포골드 —
검사한 7개 노선 모두 역이 빠짐없이 들어 있다. 신분당 연장을 넣으니 강남->여의도가
31분에서 19분으로 실제와 가깝게 바뀌었다.

**막힌 것:** Wikidata 의 한국 철도 인접 관계(P197)가 서비스 레벨을 섞어서 담고 있다.
경부선에는 KTX 급 인접(서울-광명-천안)과 완행 인접(서울-남영-용산)이 함께 들어 있어,
물리 노선을 운행계통에 병합하면 **지름길 간선**이 생긴다. 도달권이 크게 낙관적으로 나온다.

물리 노선을 빼면 지름길은 사라지지만 이번엔 역이 누락된다(수원·건대입구). 운행계통
라벨만으로는 커버리지가 모자라기 때문이다. 둘 사이에 깨끗한 지점이 없었다.

  노선별 차수 분포 (선로는 차수 2가 대부분이어야 정상):
    2호선   차수 {1:2, 2:47, 3:2}          ✅ 정상
    신분당   차수 {1:4, 2:12}               ✅ 정상
    1호선   차수 {1:6, 2:61, 3:16, 4:12, 5:2}   ❌ 지름길
    경의중앙 차수 {1:6, 2:35, 3:14, 4:10, 5:4, 6:1} ❌ 지름길

**다음 수를 정할 때 참고:**
  1. 노선별 수작업 정리 — 확실하지만 오래 걸린다
  2. 2020 GML 유지 + 신규 노선만 손으로 추가 (약 40역) — 통제 가능
  3. KTDB GTFS — 진짜 해법. 버스까지 한 번에 해결된다

사용법(실험용):
  python tools/build_graph.py out.gml
"""
_ORIGINAL_DOC = """

기존 2020년 GML 을 대체한다. 같은 포맷으로 내보내므로 builder 는 손대지 않는다.

왜 매핑 테이블이 필요한가:
  Wikidata 는 운행계통(수도권 전철 1호선)과 물리노선(경부선·경인선·경원선)을
  섞어서 붙여 놓는다. 그대로 쓰면 같은 구간에 중복 간선이 생기고, 화물선·기지선·
  고속철도까지 딸려 들어온다. 그래서 "무엇을 하나의 운행계통으로 볼 것인가"를
  손으로 정한다. 이 판단이 그래프 품질을 좌우한다.
"""
import csv, io, json, re, sys, math, collections, urllib.parse, urllib.request

ENDPOINT = "https://query.wikidata.org/sparql"
UA = "chulsegwon/0.1 (station graph build; https://github.com/klaus9267/chulsegwon)"

# 수도권 bbox. 경춘선 춘천·1호선 신창까지 담되 부산·대구는 뺀다.
BBOX = (126.3, 36.8, 127.9, 38.3)

# Wikidata 노선 라벨 -> 우리 운행계통 이름.
# 연장선(하남선·별내선·진접선·일산선…)은 본선에 합친다. 실제로 직결 운행하므로
# 따로 두면 경계에서 끊긴 것처럼 계산된다.
LINE_MAP = {
    "수도권 전철 1호선": "1호선", "서울 지하철 1호선": "1호선",

    "서울 지하철 2호선": "2호선",
    "수도권 전철 3호선": "3호선", "서울 지하철 3호선": "3호선", "일산선": "3호선",
    "수도권 전철 4호선": "4호선", "서울 지하철 4호선": "4호선",
    "과천선": "4호선", "안산선": "4호선", "진접선": "4호선",
    "수도권 전철 5호선": "5호선", "하남선": "5호선",
    "서울 지하철 6호선": "6호선",
    "서울 지하철 7호선": "7호선",
    "수도권 전철 8호선": "8호선", "별내선": "8호선",
    "서울 지하철 9호선": "9호선",
    "수도권 전철 수인·분당선": "수인분당", "분당선": "수인분당", "수인선": "수인분당",
    # 경의중앙선은 Wikidata 에 운행계통이 없고 물리노선으로만 쪼개져 있다.
    "경의선": "경의중앙", "수도권 전철 경의선": "경의중앙",
    "용산선": "경의중앙",
    "중앙선": "경의중앙", "수도권 전철 중앙선": "경의중앙",
    "경춘선": "경춘",
    "인천국제공항철도": "공항철도",
    "신분당선": "신분당",
    "인천 도시철도 1호선": "인천1",
    "인천 도시철도 2호선": "인천2",
    "의정부 경전철": "의정부",
    "용인 경전철": "용인에버라인",
    "서울 경전철 우이신설선": "우이신설",
    "서울 경전철 신림선": "신림",
    "김포골드라인": "김포골드",
    "서해선": "서해",
    "경강선": "경강",
    "수도권 광역급행철도 A노선": "GTX-A",
    # 운행계통 라벨이 따로 붙은 소수 케이스
    "수도권 전철 경의·중앙선": "경의중앙",
    "수도권 전철 경강선": "경강",
    "수도권 전철 서해선": "서해",
    "응암 순환": "6호선",
}
# 명시적으로 제외: 고속철도·화물/기지선·미개통·운행중단·수도권 밖 간선
EXCLUDE = {
    "경부고속철도", "수서평택고속선", "중부내륙선", "충북선", "호남선", "장항선",
    "평부선", "평택선", "망우선", "교외선", "숙성기지선", "용유차량기지선",
    "서울 경전철 난곡선", "인천공항 자기부상철도", "무궁화호", "ITX-새마을",
    # 급행은 정차역이 달라 본선과 같이 넣으면 중복 간선이 된다. 별도 모델링 전까지 제외.
    "서울 지하철 9호선 급행",
    "금강산선", "남부화물기지선",
    "신안산선",  # 미개통
    # 물리 노선은 쓰지 않는다. KTX 급 인접과 완행 인접이 섞여 있어 지름길 간선이 생긴다.
    # (서울-광명-천안 같은 간선이 1호선에 들어오면 도달권이 크게 낙관적으로 나온다)
    "경부선", "경인선", "경원선", "병점기지선",
}


def sparql(query):
    url = ENDPOINT + "?" + urllib.parse.urlencode({"query": query})
    req = urllib.request.Request(url, headers={"Accept": "text/csv", "User-Agent": UA})
    with urllib.request.urlopen(req, timeout=180) as r:
        return list(csv.DictReader(io.StringIO(r.read().decode("utf-8"))))


def point(s):
    m = re.match(r"Point\(([\d.\-]+) ([\d.\-]+)\)", s or "")
    return (float(m.group(1)), float(m.group(2))) if m else None


def in_bbox(p):
    return p and BBOX[0] <= p[0] <= BBOX[2] and BBOX[1] <= p[1] <= BBOX[3]


def norm(label):
    """역 이름 정리. Wikidata 는 '강남역' 처럼 '역'이 붙어 있다."""
    n = (label or "").strip()
    # "강남역" -> "강남". 단 "서울역" 은 그 자체가 역 이름이라 떼면 안 된다.
    if n == "서울역":
        return n
    n = re.sub(r"역$", "", n)
    return n


def main():
    print("[1/4] 역·노선 조회")
    stations = sparql("""SELECT ?s ?sLabel ?coord ?lineLabel WHERE {
      ?s wdt:P31/wdt:P279* wd:Q55488 ; wdt:P17 wd:Q884 ; wdt:P625 ?coord ; wdt:P81 ?line .
      SERVICE wikibase:label { bd:serviceParam wikibase:language "ko". } }""")

    print("[2/4] 인접 관계 조회")
    adjacency = sparql("""SELECT ?s ?adj ?lineLabel WHERE {
      ?s wdt:P31/wdt:P279* wd:Q55488 ; wdt:P17 wd:Q884 ; wdt:P625 ?c .
      ?s p:P197 ?st . ?st ps:P197 ?adj . OPTIONAL { ?st pq:P81 ?line . }
      SERVICE wikibase:label { bd:serviceParam wikibase:language "ko". } }""")

    # qid -> (이름, 좌표), qid -> 우리 노선 집합
    info, lines_of = {}, collections.defaultdict(set)
    unmapped = collections.Counter()
    for r in stations:
        p = point(r.get("coord"))
        if not in_bbox(p):
            continue
        qid = r["s"].rsplit("/", 1)[-1]
        info[qid] = (norm(r.get("sLabel")), p)
        lab = (r.get("lineLabel") or "").strip()
        if lab in EXCLUDE:
            continue
        our = LINE_MAP.get(lab)
        if our:
            lines_of[qid].add(our)
        elif lab and not lab.startswith("Q"):
            unmapped[lab] += 1

    print(f"      역 {len(info)}개, 노선 붙은 역 {len(lines_of)}개")
    if unmapped:
        print("      ⚠ 매핑 안 된 라벨:", dict(unmapped.most_common(8)))

    print("[3/4] 노선별 인접 간선 구성")
    edges = set()          # (qidA, qidB, 노선)  A<B 정규화
    inferred = ambiguous = 0
    for r in adjacency:
        a = r["s"].rsplit("/", 1)[-1]
        b = r["adj"].rsplit("/", 1)[-1]
        if a not in info or b not in info:
            continue
        lab = (r.get("lineLabel") or "").strip()
        if lab in EXCLUDE:
            continue
        our = LINE_MAP.get(lab)
        if not our:
            # 노선 한정자가 없거나 매핑 밖이면 양쪽 역의 노선 교집합으로 추론한다.
            shared = lines_of[a] & lines_of[b]
            if len(shared) == 1:
                our = next(iter(shared)); inferred += 1
            else:
                ambiguous += 1
                continue
        lo, hi = (a, b) if a < b else (b, a)
        edges.add((lo, hi, our))
    print(f"      간선 {len(edges)}개 (추론 {inferred}, 모호해서 버림 {ambiguous})")

    # 간선에 쓰인 노선을 역에도 반영 (P81 이 빠진 역 보완)
    for a, b, ln in edges:
        lines_of[a].add(ln); lines_of[b].add(ln)

    print("[4/4] GML 작성")
    # (역, 노선) 노드
    node_id, nodes = {}, []
    for qid in sorted(lines_of):
        if qid not in info:
            continue
        name, (lon, lat) = info[qid]
        for ln in sorted(lines_of[qid]):
            node_id[(qid, ln)] = len(nodes)
            nodes.append((name, ln, lon, lat, qid))

    out = ["graph ["]
    for i, (name, ln, lon, lat, qid) in enumerate(nodes):
        out += ["  node [", f"    id {i}", f'    label "{qid}"', f'    line_no "{ln}"',
                f'    station_name "{name}"', f"    pos {lon}", f"    pos {lat}",
                "    is_interchange 0", "  ]"]
    track = 0
    for a, b, ln in sorted(edges):
        ia, ib = node_id.get((a, ln)), node_id.get((b, ln))
        if ia is None or ib is None:
            continue
        out += ["  edge [", f"    source {ia}", f"    target {ib}", f'    line_no "{ln}"', "  ]"]
        track += 1
    # 같은 역의 노선 간 환승 간선 (line_no 를 비워 GmlLoader 가 선로와 구분한다)
    transfer = 0
    for qid in lines_of:
        ls = sorted(lines_of[qid])
        for i in range(len(ls)):
            for j in range(i + 1, len(ls)):
                ia, ib = node_id.get((qid, ls[i])), node_id.get((qid, ls[j]))
                if ia is None or ib is None:
                    continue
                out += ["  edge [", f"    source {ia}", f"    target {ib}", '    line_no ""', "  ]"]
                transfer += 1
    out.append("]")

    path = sys.argv[1] if len(sys.argv) > 1 else "data/raw/metro_graph_2026.gml"
    io.open(path, "w", encoding="utf-8").write("\n".join(out) + "\n")
    print(f"  승강장 {len(nodes)} / 선로 {track} / 환승 {transfer}")
    print(f"  -> {path}")


main()
