# transit-reach

수도권 대중교통 **출퇴근 도달권(isochrone)** 으로 살 곳을 좁히는 도구.

## 왜 이런 구조인가

런타임에 경로탐색 엔진을 돌리지 않는다. 입력 공간이 작기 때문이다 —
목적지는 전철역 621개, 시간대는 운행시간 10분 단위 115개 × 방향 2개.
전부 미리 계산해 정적 파일로 뿌리면 서버가 필요 없다.

threshold(40분/50분)는 계산에서 뺐다. 저장하는 건 폴리곤이 아니라
**"각 역까지 몇 분"** 벡터라, 사용자가 슬라이더를 움직여도 네트워크 호출이 0이다.

```
builder/ (Kotlin CLI)          web/ (Vite + MapLibre)
  시간표 → 그래프 → 행렬  ──→  matrix/{역}.bin ──→ 도달권 렌더
       월 1회 수동 실행              약 140KB/역        슬라이더 6ms
```

## 실행

```bash
# 1) 행렬 생성 (약 3초)
./gradlew :builder:run --args="--gml data/raw/metro_graph.gml --out web/public/data --step-min 10"

# 2) 웹
cd web && npm install && npm run dev
```

진단:

```bash
./gradlew :builder:run --args="--mode diag --gml data/raw/metro_graph.gml --explain 강남>홍대입구 --at 08:00"
```

## ⚠️ 현재 데이터의 한계

| 항목 | 지금 | 교체 대상 |
|---|---|---|
| 역·노선 | 2020년 GML (GTX-A·신림선·별내선 등 없음) | KTDB GTFS |
| 역간 소요시간 | 좌표 거리 ÷ 표정속도 추정 | KTDB GTFS |
| 배차간격 | 시간대별 추정표 | KTDB GTFS |
| 버스·마을버스 | ❌ 없음 | Level 2 (R5) |
| 도보 반경 | 직선거리 원 (한강 무시) | Level 2 (OSM 도보망) |

`TimetableSource` 구현체를 추가하는 것으로 교체된다. 탐색·직렬화·프론트는 그대로다.

## 라이선스·출처

- 역 그래프: [stripe2933/SeoulMetropolitanSubway](https://github.com/stripe2933/SeoulMetropolitanSubway) (MIT)
- 배경지도: CARTO Positron / OpenStreetMap contributors
