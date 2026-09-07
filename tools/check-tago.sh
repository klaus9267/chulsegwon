#!/usr/bin/env bash
# TAGO 활용신청이 승인됐는지, 그리고 GTFS 에 필요한 필드가 실제로 오는지 확인한다.
#
# 승인 직후에 돌려서 "신청은 됐는데 원하는 필드가 없다"를 일찍 발견하는 게 목적이다.
# 실거래가 때 스키마를 가정했다가 뒤집힌 적이 있어서(HANDOFF §7-1 ①) 먼저 본다.
#
#   ./tools/check-tago.sh
#
# 오류 메시지로 두 경우가 갈린다:
#   SERVICE_KEY_IS_NOT_REGISTERED  → 경로는 맞고 활용신청만 안 된 것
#   NO_OPENAPI_SERVICE             → 경로가 틀린 것
set -u
[ -f .env ] && { set -a; . ./.env; set +a; }
: "${DATA_GO_KR_KEY:?루트 .env 에 DATA_GO_KR_KEY 가 필요하다}"

CITY=${CITY:-11}   # 서울
B="https://apis.data.go.kr/1613000"
ok=0; need_apply=0; bad_path=0

probe() {  # 이름 URL 확인할필드...
  local name="$1" url="$2"; shift 2
  local body; body=$(curl -s --max-time 25 "$url")

  if printf '%s' "$body" | grep -q 'SERVICE_KEY_IS_NOT_REGISTERED'; then
    printf '  ✕ %-14s 활용신청 필요\n' "$name"; need_apply=$((need_apply+1)); return 1
  fi
  if printf '%s' "$body" | grep -q 'NO_OPENAPI_SERVICE'; then
    printf '  ? %-14s 경로가 틀림 (활용가이드에서 확인 필요)\n' "$name"; bad_path=$((bad_path+1)); return 1
  fi
  if printf '%s' "$body" | grep -q 'LIMITED_NUMBER_OF_SERVICE_REQUESTS'; then
    printf '  ! %-14s 일일 한도 초과 (신청은 되어 있음)\n' "$name"; ok=$((ok+1)); return 0
  fi
  if ! printf '%s' "$body" | grep -q '<resultCode>0*0<\|"resultCode":"0*0"'; then
    printf '  ✕ %-14s 응답 이상: %s\n' "$name" "$(printf '%s' "$body" | tr -d '\n' | head -c 80)"
    return 1
  fi

  local missing=""
  for f in "$@"; do
    printf '%s' "$body" | grep -q "<$f>" || missing="$missing $f"
  done
  if [ -n "$missing" ]; then
    printf '  ! %-14s 응답은 오는데 필드 없음:%s\n' "$name" "$missing"
  else
    printf '  ✓ %-14s %s\n' "$name" "$*"
  fi
  ok=$((ok+1)); return 0
}

echo "TAGO 상태 (cityCode=$CITY)"
echo

# ── GTFS routes/trips/frequencies/calendar 의 재료 ──────────────
probe "버스노선정보" \
  "$B/BusRouteInfoInqireService/getRouteNoList?serviceKey=$DATA_GO_KR_KEY&cityCode=$CITY&numOfRows=1&pageNo=1" \
  routeid routeno

# ── GTFS stops.txt ─────────────────────────────────────────────
probe "버스정류소정보" \
  "$B/BusSttnInfoInqireService/getSttnNoList?serviceKey=$DATA_GO_KR_KEY&cityCode=$CITY&numOfRows=1&pageNo=1" \
  nodeid nodenm gpslati gpslong

# ── GTFS stop_times.txt 의 재료. nodeord 가 핵심이다 ────────────
ROUTE=$(curl -s --max-time 25 \
  "$B/BusRouteInfoInqireService/getRouteNoList?serviceKey=$DATA_GO_KR_KEY&cityCode=$CITY&numOfRows=1&pageNo=1" \
  | sed -n 's:.*<routeid>\([^<]*\)</routeid>.*:\1:p' | head -1)
if [ -n "$ROUTE" ]; then
  probe "버스위치정보" \
    "$B/BusLcInfoInqireService/getRouteAcctoBusLcList?serviceKey=$DATA_GO_KR_KEY&cityCode=$CITY&routeId=$ROUTE&numOfRows=3&pageNo=1" \
    vehicleno nodeord nodeid
  probe "노선별정류소" \
    "$B/BusRouteInfoInqireService/getRouteAcctoThrghSttnList?serviceKey=$DATA_GO_KR_KEY&cityCode=$CITY&routeId=$ROUTE&numOfRows=3&pageNo=1" \
    nodeid nodeord
else
  echo "  - 버스위치정보   노선 id 를 못 얻어 건너뜀 (노선정보부터 승인되어야 한다)"
fi

# ── 교차 검증용(선택) ──────────────────────────────────────────
probe "버스도착정보" \
  "$B/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList?serviceKey=$DATA_GO_KR_KEY&cityCode=$CITY&nodeId=DGB7011001800&numOfRows=1&pageNo=1" \
  arrtime routeno

# ── 지하철: 정확한 서비스 경로가 문서(활용가이드 docx)에만 있다.
#    후보를 훑어보고, 다 틀리면 그렇게 말한다. 추측을 사실처럼 두지 않는다. ──
found=""
for svc in SubwayInfoService SubwayInfoInqireService SubwaySttnInfoInqireService \
           TagoSubwayInfoService SubwayInfoInqireSvc; do
  r=$(curl -s --max-time 15 \
    "$B/$svc/getKwrdFndSubwaySttnList?serviceKey=$DATA_GO_KR_KEY&subwayStationName=%EA%B0%95%EB%82%A8&numOfRows=1&pageNo=1")
  printf '%s' "$r" | grep -q 'NO_OPENAPI_SERVICE' || { found="$svc"; break; }
done
if [ -n "$found" ]; then
  probe "지하철정보" \
    "$B/$found/getKwrdFndSubwaySttnList?serviceKey=$DATA_GO_KR_KEY&subwayStationName=%EA%B0%95%EB%82%A8&numOfRows=1&pageNo=1" \
    subwayStationId subwayStationName
  echo "     └ 서비스 경로: $found"
else
  printf '  ? %-14s 후보 경로 전부 실패. data.go.kr 15098554 의\n' "지하철정보"
  echo "                   [오픈API활용가이드] docx 에서 요청주소를 확인해 이 스크립트에 넣을 것"
  bad_path=$((bad_path+1))
fi

echo
echo "통과 $ok · 신청필요 $need_apply · 경로확인필요 $bad_path"
[ "$need_apply" -eq 0 ] || cat <<'MSG'

→ 활용신청: https://www.data.go.kr 로그인 후 아래 데이터셋에서 [활용신청]
   15098529 버스노선정보 · 15098534 버스정류소정보 · 15098533 버스위치정보
   15098554 지하철정보  · 15098530 버스도착정보(선택)
MSG
[ "$need_apply" -eq 0 ] && [ "$bad_path" -eq 0 ]
