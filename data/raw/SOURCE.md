# metro_graph.gml

- 출처: https://github.com/stripe2933/SeoulMetropolitanSubway (MIT)
- 기준: 2020-12
- 내용: 수도권 전철 (역, 노선) 노드 741개 / 인접 간선 863개, 좌표 및 환승역 플래그 포함
- 없는 것: 역간 소요시간, 시간표 → `TimetableBuilder` 가 거리에서 추정
- ⚠️ 2020년 기준이라 GTX-A, 신림선, 대곡소사선, 별내선, 8호선 연장 등이 빠져 있다.
  KTDB GTFS 수령 시 이 파일과 `GmlLoader` 를 `GtfsSource` 로 교체할 것.


# GBIS 경기버스정보 — 노선·경유정류소·정류소 엑셀

- 받는 곳 (신청·키 없이 바로 받아짐, 2026-09-09 확인)
  - 노선정보    https://www.gbis.go.kr/gbis2014/openFile.do?fileName=GGD_RouteInfo_M.xlsx
  - 노선경유정류소 https://www.gbis.go.kr/gbis2014/openFile.do?fileName=GGD_RouteStationInfo_M.xlsx
  - 정류소정보   https://www.gbis.go.kr/gbis2014/openFile.do?fileName=GGD_StationInfo_M.xlsx
- 받는 이유: **TAGO 에 경기도 마을버스가 한 대도 없다.** 인천은 13개를 주는데 경기는 0개다.
  GBIS 는 3,672개 노선을 주고 그 중 892개가 TAGO 에 없다 (TAGO 2,157 ⊂ GBIS 3,672).
- id 공간이 TAGO 와 같다. `ROUTE_ID 200000006` = TAGO `GGB200000006`.
- 대조로 확인한 것 (겹치는 부분)
  - 정류장 좌표: 29,452개의 차이 중앙 **0.0m** (100%가 10m 안)
  - 정류장 순서: 노선 2,156개 중 **2,151개(99.8%)** 가 완전 일치
  - `(미정차)`·`(가상)` 형상점이 **0건** — TAGO 가 30,173행 섞어 보내던 그 문제가 없다
- ⚠️ **엑셀이 65,000행마다 시트를 갈아탄다.** `sheet1` 만 읽으면 노선 3,672개 중 856개만
  보이고, 그게 "경유정류소가 없다"는 잘못된 결론으로 이어진다. `xlsx_read.all_rows` 를 쓸 것.
- ⚠️ 좌표는 `X`/`Y` 열이 WGS84 십진도다. `GPS_X`/`GPS_Y` 는 도-분 표기(126°59.264′)라
  그대로 위경도로 읽으면 지구 반대편으로 간다. `MAP_X`/`MAP_Y` 는 TM 이다.
