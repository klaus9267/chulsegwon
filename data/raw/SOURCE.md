# metro_graph.gml

- 출처: https://github.com/stripe2933/SeoulMetropolitanSubway (MIT)
- 기준: 2020-12
- 내용: 수도권 전철 (역, 노선) 노드 741개 / 인접 간선 863개, 좌표 및 환승역 플래그 포함
- 없는 것: 역간 소요시간, 시간표 → `TimetableBuilder` 가 거리에서 추정
- ⚠️ 2020년 기준이라 GTX-A, 신림선, 대곡소사선, 별내선, 8호선 연장 등이 빠져 있다.
  KTDB GTFS 수령 시 이 파일과 `GmlLoader` 를 `GtfsSource` 로 교체할 것.
