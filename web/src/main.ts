import { StationMatrixProvider } from "./provider";
import { buildField, buildStationGeoJSON } from "./grid";
import { buildIsobandsGeoJSON, breaksFor } from "./contour";
import { createCombobox } from "./combobox";
import { formatManwon } from "./complexes";
import { buildRentalGeoJSON, filterRentals, loadRentals } from "./rentals";
import type { Rental } from "./rentals";
import { AMENITIES, amenityRanks, loadAmenities, passes, thresholds } from "./amenities";
import type { AmenityMap } from "./amenities";
import { copyText, decodeState, encodeState } from "./share";
import { ROOM_LABEL, buildDongGeoJSON, dongKey, filterDongs, loadDongs } from "./dongs";
import { priceLabel } from "./dongs";
import type { Dong, DongPick, RoomStat, RoomType, Tenure } from "./dongs";
import type { Bounds, MapAdapter, Ramp } from "./map/adapter";
import { MapLibreAdapter } from "./map/maplibre";
import { KakaoAdapter } from "./map/kakao";
import type { Direction, Manifest } from "./types";

/**
 * 등시선을 뽑을 격자 해상도(m).
 *
 * 400m 면 도보 4분(반경 300m)일 때 역마다 칸 하나만 걸려 블록처럼 흩어진다.
 * 칸이 너무 많아지면 buildField 가 알아서 키운다.
 */
const CELL_METERS = 200;

/** 처음 열었을 때 보일 범위. 서울 시가지가 들어와야 어디를 보고 있는지 안다. */
/** 이 배율부터 개별 건물을 보여준다. 지도 어댑터의 값과 맞춰 둔다. */
const BUILDING_LEVEL = 4;

/**
 * 건물 데이터를 미리 받기 시작하는 배율.
 *
 * 보여줄 배율에 도달해서야 받기 시작하면 12MB 를 기다리는 몇 초 동안 빈 지도를 본다.
 * 두 단계 먼저 시작하면 도착했을 때 이미 준비돼 있다. 확대는 대개 연속 동작이라
 * 이 정도 예측은 거의 맞는다.
 */
const BUILDING_PREFETCH_LEVEL = BUILDING_LEVEL + 2;

const SEOUL_BOUNDS: Bounds = { west: 126.76, south: 37.42, east: 127.19, north: 37.7 };

/** 짧을수록 진하게. */
const RAMP: Ramp = [
  [0, "#12467f"],
  [10, "#2b6cb0"],
  [20, "#4a90c4"],
  [30, "#7fb3d5"],
  [45, "#aecfe4"],
  [60, "#d6e6f2"],
];

/**
 * GML 의 노선 코드를 사람이 읽는 이름으로.
 *
 * 원본이 "K", "B", "SH" 같은 코드라 그대로는 못 읽는다. 환승역 조합으로 대조해
 * 확인했다 — 서울역(1,4,A,K), 김포공항(5,9,KP,A), 초지(4,B,SH), 신설동(1,2,W).
 */
const LINE_NAMES: Record<string, string> = {
  K: "경의중앙", B: "수인분당", G: "경춘", A: "공항철도", S: "신분당",
  I: "인천1", I2: "인천2", U: "의정부", W: "우이신설", E: "용인에버라인",
  KK: "경강", KP: "김포골드", SH: "서해",
};

function lineName(code: string): string {
  if (LINE_NAMES[code]) return LINE_NAMES[code];
  return /^\d+$/.test(code) ? code + "호선" : code;
}

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;

function stage(msg: string) {
  const el = document.getElementById("status");
  if (el) el.textContent = msg;
}

/** 값이 바뀔 때 잠깐 색을 준다. 반응이 있었다는 신호. */
let bumpTimer = 0;
function bump(id: string) {
  const el = document.getElementById(id);
  if (!el) return;
  el.classList.add("bump");
  window.clearTimeout(bumpTimer);
  bumpTimer = window.setTimeout(() => el.classList.remove("bump"), 260);
}

/**
 * 패널이 지도를 가린다. 좁은 화면은 아래를, 넓은 화면은 왼쪽을 비워둔다.
 *
 * 모바일 시트는 접힘/펼침에 따라 높이가 두 배 넘게 달라진다. 고정값을 쓰면 접었을 때
 * 결과가 화면 위쪽에 몰리고, 펼쳤을 때는 시트 뒤에 숨는다. 실제 높이를 읽는다.
 */
function panelPadding() {
  if (window.innerWidth > 640) return { top: 24, right: 24, bottom: 24, left: 340 };
  const h = document.getElementById("panel")?.getBoundingClientRect().height ?? 0;
  return {
    top: 24,
    right: 24,
    bottom: Math.min(Math.round(h + 16), Math.round(window.innerHeight * 0.6)),
    left: 24,
  };
}

/**
 * 카카오 키가 있으면 카카오맵, 없거나 실패하면 MapLibre.
 *
 * 키가 틀리거나 도메인이 등록 안 되면 script onerror 로 온다. 그때 지도 없는
 * 화면을 보여주느니 MapLibre 로 내려가는 게 낫다.
 */
async function createMap(initial: Bounds): Promise<MapAdapter> {
  const kakaoKey = import.meta.env.VITE_KAKAO_KEY as string | undefined;
  if (kakaoKey) {
    try {
      return await KakaoAdapter.create($("map"), kakaoKey, initial, panelPadding());
    } catch (e) {
      console.warn("카카오맵을 못 띄워 MapLibre 로 대체합니다:", e);
    }
  }
  return MapLibreAdapter.create("map", initial, panelPadding());
}

async function main() {
  stage("데이터 목록 불러오는 중…");
  // GitHub Pages 프로젝트 사이트는 /chulsegwon/ 하위로 서빙된다. 절대경로로 "/data" 를
  // 부르면 배포판에서 404 가 난다. BASE_URL 은 dev 에서 "/", 빌드 시 --base 값이 된다.
  const provider = await StationMatrixProvider.load(import.meta.env.BASE_URL + "data");
  const meta = provider.manifest();

  const arriveSlots = meta.slots.filter((s) => s.direction === "ARRIVE_BY");
  const departSlots = meta.slots.filter((s) => s.direction === "DEPART_AT");

  const state = {
    origin: findStation(meta, "강남") ?? 0,
    /** 맞벌이용 두 번째 직장. null 이면 한 명 기준. */
    origin2: null as number | null,
    direction: "ARRIVE_BY" as Direction,
    timeIndex: 19,
    budget: 40,
    walkCap: 15,
    /** 찾는 방 크기. 실거래가에 방 개수가 없어 전용면적으로 나눈 구간이다. */
    room: "ONE" as RoomType,
    /** 자취는 월세가 실제 기준이라 기본값이 월세다. */
    tenure: "WOLSE" as Tenure,
    /** 상한. 월세면 만원/월, 전세면 만원. 0 이면 제한 없음. */
    cap: 0,
    /** 켜둔 편의시설 조건. 비어 있으면 안 거른다. */
    amenities: new Set<string>(),
    /** 추천 목록 정렬. 점수를 하나로 합치지 않는 이유는 가중치가 임의라서다. */
    sort: "commute" as "commute" | "price" | "amenity",
  };

  /**
   * 슬라이더 눈금을 실제 시장 구간에 맞춘다.
   *
   * 월세는 5만원 단위(원룸 대부분이 40~80만), 전세는 2천만원 단위다. 같은 슬라이더로
   * 두 값을 받되 해석만 바꾼다. 눈금이 시장 폭과 맞지 않으면 슬라이더 한 칸이
   * 의미 없는 변화가 되거나, 반대로 원하는 값을 못 고른다.
   */
  const capStep = () => (state.tenure === "WOLSE" ? 5 : 2000);
  const capValue = (tick: number) => (tick === 0 ? 0 : tick * capStep());

  /**
   * 개별 건물 실거래. 확대했을 때만 쓰이므로 그때 받는다.
   *
   * 12MB 다. 동네를 고르다 마는 사람에게 처음부터 물릴 이유가 없고, 광역 배율에서는
   * 화면에 그릴 수도 없다. 한 번 받으면 메모리에 남아 확대·축소를 오가도 다시 받지 않는다.
   */
  let rentals: Rental[] = [];
  let rentalsRequested = false;
  function ensureRentals(level: number) {
    if (rentalsRequested || level > BUILDING_PREFETCH_LEVEL) return;
    rentalsRequested = true;
    void loadRentals(import.meta.env.BASE_URL + "data/").then((list) => {
      rentals = list;
      if (list.length > 0) void render();
    });
  }

  let amenityData: AmenityMap = {};
  let amenityTh: Record<string, number> = {};
  let amenityRank = new Map<string, number>();
  void loadAmenities(import.meta.env.BASE_URL + "data/").then((data) => {
    if (Object.keys(data).length === 0) return;
    amenityData = data;
    initAmenities();
  });

  /**
   * 기준값은 **후보가 될 수 있는 동네**들 사이에서 뽑는다.
   *
   * 원룸 거래가 100건도 안 되는 동네는 우리가 애초에 보여주지 않는다. 그런 곳까지
   * 모집단에 넣으면 "편의점 5곳 이상" 같은, 서울에서는 아무것도 거르지 못하는
   * 기준이 나온다.
   */
  function initAmenities() {
    if (Object.keys(amenityData).length === 0 || dongs.length === 0) return;
    const eligible = new Set(
      dongs.filter((d) => d.deals >= 100).map((d) => dongKey(d)),
    );
    amenityTh = thresholds(amenityData, eligible);
    amenityRank = amenityRanks(amenityData);
    buildAmenityChips();
    void render();
  }

  // 동네 시세. 이쪽이 자취 타겟의 주 데이터다.
  /** 열려 있는 동네 상세. 조건을 바꿔도 같은 동네를 계속 보고 있게 한다. */
  let openDong: string | null = null;
  let dongs: Dong[] = [];
  const dongByKey = new Map<string, Dong>();
  /** 마지막 계산에서 각 동까지 걸린 시간. 상세 카드가 다시 계산할 이유가 없다. */
  let dongMinutes = new Map<string, number>();
  void loadDongs(import.meta.env.BASE_URL + "data/").then((list) => {
    dongs = list;
    for (const d of list) dongByKey.set(dongKey(d), d);
    if (list.length > 0) void render();
    initAmenities();
  });

  // 슬라이더를 끌면 입력이 쏟아진다. 그리는 중이면 마지막 요청 하나만 남긴다.
  // 선언이 여기 있어야 한다. render() 는 호이스팅되지만 let 은 TDZ 라, 아래에 두면
  // 첫 호출이 "Cannot access 'rendering' before initialization" 으로 죽는다.
  let rendering = false;
  let queued = false;
  let hasRun = false;
  let fitted = false;

  // 지도보다 UI 를 먼저 세운다. 배경지도가 느려도 역 선택은 바로 되어야 한다.
  // map 을 const 로 아래에 두면 onSelect 가 그 전에 불릴 때 TDZ 로 죽는다.
  let map: MapAdapter | null = null;

  // 같은 이름의 다른 역이 있다(5호선 양평 vs 경의중앙선 양평, 약 53km 거리).
  const nameCount = new Map<string, number>();
  for (const st of meta.stations) nameCount.set(st.name, (nameCount.get(st.name) ?? 0) + 1);

  const displayToIndex = new Map<string, number>();
  const comboOptions = [...meta.stations]
    .sort((a, b) => a.name.localeCompare(b.name, "ko"))
    .map((st) => {
      const value =
        (nameCount.get(st.name) ?? 0) > 1 ? st.name + " (" + lineName(st.lines[0]) + ")" : st.name;
      displayToIndex.set(value, st.index);
      return { value, hint: st.lines.map(lineName).join(" · ") };
    });

  /** 콤보박스에 보여줄 표시값. 같은 이름의 역이 있으면 노선이 붙은 쪽이다. */
  const indexToDisplay = new Map<number, string>();
  for (const [display, idx] of displayToIndex) indexToDisplay.set(idx, display);
  const comboLabelFor = (i: number) => indexToDisplay.get(i) ?? meta.stations[i].name;

  const originInput = $<HTMLInputElement>("origin");
  createCombobox({
    input: originInput,
    toggle: $("originToggle"),
    list: $("originList"),
    options: comboOptions,
    onSelect: (value) => {
      const idx = displayToIndex.get(value);
      if (idx === undefined) return;
      state.origin = idx;
      showOrigins(idx);
      onInputChanged();
    },
  });
  originInput.value = meta.stations[state.origin].name;

  const origin2Input = $<HTMLInputElement>("origin2");
  createCombobox({
    input: origin2Input,
    toggle: $("origin2Toggle"),
    list: $("origin2List"),
    options: comboOptions,
    onSelect: (value) => {
      const idx = displayToIndex.get(value);
      if (idx === undefined) return;
      state.origin2 = idx;
      $("origin2Clear").hidden = false;
      showOrigins();
      onInputChanged();
    },
  });
  $("origin2Clear").addEventListener("click", () => {
    state.origin2 = null;
    origin2Input.value = "";
    $("origin2Clear").hidden = true;
    showOrigins();
    onInputChanged();
  });

  const timeSlider = $<HTMLInputElement>("time");
  timeSlider.max = String(arriveSlots.length - 1);

  applyShared();
  syncLabels();
  stage("지도 불러오는 중…");

  $("run").addEventListener("click", () => void run());

  // 모바일에서는 조건보다 지도가 먼저 보여야 한다. 접은 채로 시작하고, 손잡이로 편다.
  const panel = $("panel");
  if (window.innerWidth <= 640) panel.classList.add("collapsed");
  $("grab").addEventListener("click", () => {
    const open = panel.classList.toggle("collapsed") === false;
    $("grab").setAttribute("aria-expanded", String(open));
    $("grab").setAttribute("aria-label", open ? "조건 접기" : "조건 펼치기");
  });

  $("rank").addEventListener("click", (e) => {
    const li = (e.target as HTMLElement).closest("li");
    if (!li || !li.dataset.key) return;
    openDong = li.dataset.key;
    showDetail(openDong);
    // 목록에서 고른 동네가 지도 어디인지 바로 안 보이면 목록과 지도가 따로 논다.
    map?.easeTo({ lon: +li.dataset.lon!, lat: +li.dataset.lat! }, 13);
  });

  $("sortSeg").addEventListener("click", (e) => {
    const b = (e.target as HTMLElement).closest("button");
    if (!b) return;
    state.sort = b.dataset.sort as typeof state.sort;
    syncSeg("sortSeg", "sort", state.sort);
    void render();
  });

  $("share").addEventListener("click", () => {
    const btn = $("share");
    void copyText(location.href).then((ok) => {
      btn.textContent = ok ? "복사됨" : "복사 실패";
      btn.classList.toggle("done", ok);
      window.setTimeout(() => {
        btn.textContent = "링크 복사";
        btn.classList.remove("done");
      }, 1800);
    });
  });

  $("dirArrive").addEventListener("click", () => setDirection("ARRIVE_BY"));
  $("dirDepart").addEventListener("click", () => setDirection("DEPART_AT"));
  timeSlider.addEventListener("input", () => {
    state.timeIndex = +timeSlider.value;
    onInputChanged();
  });
  $<HTMLInputElement>("budget").addEventListener("input", (e) => {
    state.budget = +(e.target as HTMLInputElement).value;
    onInputChanged();
  });
  $<HTMLInputElement>("walk").addEventListener("input", (e) => {
    state.walkCap = +(e.target as HTMLInputElement).value;
    onInputChanged();
  });
  $<HTMLInputElement>("cap").addEventListener("input", (e) => {
    state.cap = capValue(+(e.target as HTMLInputElement).value);
    onInputChanged();
  });

  // 방 종류·전월세는 세그먼트다. 슬라이더와 달리 값이 세 개뿐이라 한 번에 다 보이는
  // 편이 낫고, 무엇을 고를 수 있는지가 조작 전에 드러난다.
  $("roomSeg").addEventListener("click", (e) => {
    const b = (e.target as HTMLElement).closest("button");
    if (!b) return;
    state.room = b.dataset.room as RoomType;
    syncSeg("roomSeg", "room", state.room);
    onInputChanged();
  });
  $("tenureSeg").addEventListener("click", (e) => {
    const b = (e.target as HTMLElement).closest("button");
    if (!b) return;
    state.tenure = b.dataset.tenure as Tenure;
    syncSeg("tenureSeg", "tenure", state.tenure);
    // 눈금의 의미가 바뀌므로 상한을 리셋한다. 월세 60만이 전세 60만이 되면 안 된다.
    state.cap = 0;
    $<HTMLInputElement>("cap").value = "0";
    onInputChanged();
  });

  // 백그라운드 탭에서 열리면 지도가 렌더 루프를 못 돌려 레이어가 안 붙는다.
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden && hasRun) void render();
  });

  map = await createMap(SEOUL_BOUNDS);

  // 지도에서 역을 눌러 직장을 바꾼다. 콤보박스로 이름을 치는 것보다,
  // 도달권을 보다가 "여기서 다니면 어떻지?" 하고 바로 눌러보는 흐름이 자연스럽다.
  // 확대하면 개별 건물이 필요해진다. 그 시점에 받는다.
  map.onZoom((level) => ensureRentals(level));

  map.onDongClick((key) => {
    openDong = key;
    showDetail(key);
  });

  map.onStationClick((index) => {
    state.origin = index;
    originInput.value = comboLabelFor(index);
    showOrigins();
    void render();
  });

  showOrigins();
  // 무엇을 보고 있는지 밝힌다. 시세는 실거래가지 호가가 아니고, 우리는 매물을
  // 갖고 있지 않다. 그 경계를 흐리면 사용자가 없는 방을 찾으러 간다.
  $("warn").textContent =
    (map.basemapOk ? "" : "⚠ 배경지도를 불러오지 못해 도달권만 표시합니다. ") +
    "⚠ " + meta.warning +
    " 시세는 최근 6개월 실거래가의 중위값이며(보증금 1,000만원 기준으로 환산), " +
    "호가나 실매물이 아닙니다.";

  // 빈 지도로 시작하면 이 도구가 뭘 하는지 안 보인다. 기본값(강남·08:40·40분)으로
  // 한 번 그려두고, 그 뒤로는 슬라이더가 즉시 반영된다.
  hasRun = true;
  await render();

  /**
   * 고른 역을 마커로 찍는다. [move] 면 카메라도 옮긴다.
   *
   * 처음 로드할 때는 옮기지 않는다. 서울 전체가 보이는 초기 뷰를 유지해야
   * 어디를 보고 있는지 알 수 있다.
   */
  /**
   * 출발역 마커를 다시 찍는다. 맞벌이면 둘이다.
   *
   * [moveTo] 를 주면 그 역으로 카메라도 옮긴다. 처음 로드할 때는 옮기지 않는다 —
   * 서울 전체가 보이는 초기 뷰를 유지해야 어디를 보고 있는지 알 수 있다.
   */
  function showOrigins(moveTo?: number) {
    if (!map) return;
    const idxs = [state.origin, ...(state.origin2 === null ? [] : [state.origin2])];
    map.setOrigins(idxs.map((i) => ({ lon: meta.stations[i].lon, lat: meta.stations[i].lat })));
    // 출발지가 바뀌면 도달 범위도 달라진다. 다음 계산에서 화면을 다시 맞춰야 한다.
    fitted = false;
    if (moveTo !== undefined) {
      const st = meta.stations[moveTo];
      map.easeTo({ lon: st.lon, lat: st.lat }, 12);
    }
  }

  function currentSlot() {
    const slots = state.direction === "ARRIVE_BY" ? arriveSlots : departSlots;
    return slots[Math.min(state.timeIndex, slots.length - 1)];
  }

  /** 라벨은 칠하지 않아도 항상 최신이어야 한다. 숫자가 안 바뀌면 고장으로 보인다. */
  function syncLabels() {
    const slot = currentSlot();
    $("timeVal").textContent = slot.label.replace(/^(도착|출발) /, "");
    $("budgetVal").textContent = state.budget + "분";
    $("walkVal").textContent = state.walkCap === 0 ? "역만" : state.walkCap + "분";
    // 접어둔 설정이 기본값이 아니면 접힌 채로도 값이 보여야 한다.
    $("moreHint").textContent =
      "기준 " + slot.label.replace(/^(도착|출발) /, "") +
      " · 도보 " + (state.walkCap === 0 ? "안 함" : state.walkCap + "분");
    $("capLabel").textContent = state.tenure === "WOLSE" ? "월세 상한" : "전세 상한";
    $("capVal").textContent =
      state.cap === 0
        ? "제한 없음"
        : state.tenure === "WOLSE"
          ? "월 " + state.cap + "만"
          : formatManwon(state.cap) + " 이하";

    // 색이 무슨 뜻인지 숫자로 보여준다. "가까움 / 40분" 만으로는 각 색이 몇 분인지 알 수 없다.
    // 구간 경계는 예산에 비례하므로 예산이 바뀌면 라벨도 같이 바뀌어야 한다.
    const step = state.budget / RAMP.length;
    $("legend").innerHTML = RAMP.map(
      ([, color], i) =>
        '<span class="band"><i style="background:' + color + '"></i><b>' +
        Math.round(i * step) + "</b></span>",
    ).join("");
    $("legendLabels").textContent =
      "색 = 통근 시간(분) · 최대 " + state.budget + "분" +
      (state.tenure === "WOLSE" ? " · 월세는 보증금 1,000만원 기준" : "");
  }

  function onInputChanged() {
    syncLabels();
    syncUrl();
    void render();
  }

  /**
   * 동네 상세.
   *
   * 지도의 라벨은 지금 고른 조건(원룸·월세) 하나만 보여준다. 그런데 실제로 방을
   * 구할 때는 "여기 투룸은 얼마인데?"가 바로 다음 질문이라, 카드에서는 세 종류를
   * 다 펼친다. 조건을 바꿔가며 지도를 다시 보게 만드는 것보다 낫다.
   */
  /**
   * 편의시설 칩.
   *
   * 데이터가 실제로 있을 때만 만든다. 눌러도 아무 일이 없는 조작 장치는
   * "고장난 앱"으로 읽히고, 그다음부터는 다른 조작도 못 믿게 된다.
   */
  function buildAmenityChips() {
    const box = $("amenityChips");
    box.innerHTML = "";
    for (const a of AMENITIES) {
      const b = document.createElement("button");
      b.type = "button";
      b.dataset.code = a.code;
      b.setAttribute("aria-pressed", String(state.amenities.has(a.code)));
      const th = amenityTh[a.code] ?? 1;
      b.textContent =
        a.mode === "presence"
          ? a.label + " 있음"
          : a.mode === "distance"
            ? a.label + " " + (th / 1000).toFixed(1).replace(/\.0$/, "") + "km 이내"
            : a.label + " " + th + "곳+";
      b.addEventListener("click", () => {
        if (state.amenities.has(a.code)) state.amenities.delete(a.code);
        else state.amenities.add(a.code);
        b.setAttribute("aria-pressed", String(state.amenities.has(a.code)));
        onInputChanged();
      });
      box.appendChild(b);
    }
    $("amenityStep").hidden = false;
  }

  /**
   * 추천 목록.
   *
   * 점수 하나로 합치지 않는다. 통근·시세·편의를 섞으려면 가중치를 정해야 하는데
   * 그 가중치는 우리가 아니라 사용자마다 다르다. 대신 **무엇을 우선할지 고르게** 하고,
   * 그 기준으로만 줄을 세운다. 왜 이 순서인지 사용자가 설명할 수 있어야 한다.
   */
  function renderRank(picks: DongPick[]) {
    const el = $("rank");
    if (picks.length === 0) {
      el.innerHTML = '<li class="empty">조건에 맞는 동네가 없습니다</li>';
      return;
    }
    const sorted = [...picks];
    if (state.sort === "commute") sorted.sort((a, b) => a.minutes - b.minutes);
    else if (state.sort === "price") {
      const v = (p: DongPick) => (state.tenure === "JEONSE" ? p.deposit : p.monthly);
      sorted.sort((a, b) => v(a) - v(b));
    } else {
      sorted.sort(
        (a, b) =>
          (amenityRank.get(dongKey(b.d)) ?? 0) - (amenityRank.get(dongKey(a.d)) ?? 0),
      );
    }

    el.innerHTML = sorted
      .slice(0, 5)
      .map(
        (p) =>
          `<li data-key="${dongKey(p.d)}" data-lon="${p.d.lon}" data-lat="${p.d.lat}">` +
          `<span class="nm">${p.d.name}</span>` +
          `<span class="mt">${Math.round(p.minutes)}분</span>` +
          `<span class="pr">${priceLabel(p, state.tenure)}</span></li>`,
      )
      .join("");
  }

  /** 상세 카드에 쓸 출발지 이름. 맞벌이면 둘 다 적는다. */
  function originLabel(): string {
    const a = meta.stations[state.origin].name;
    return state.origin2 === null
      ? a + "까지"
      : a + " · " + meta.stations[state.origin2].name + "까지 각각";
  }

  function showDetail(key: string) {
    const d = dongByKey.get(key);
    const el = $("detail");
    if (!d) {
      el.hidden = true;
      return;
    }
    const minutes = dongMinutes.get(key);
    const rows = (["ONE", "TWO", "THREE"] as RoomType[])
      .map((rt) => {
        const s2: RoomStat | undefined = d.rooms[rt];
        const label = ROOM_LABEL[rt];
        if (!s2 || s2.n < 10) {
          return `<tr><td>${label}</td><td class="none">거래 적음</td><td></td></tr>`;
        }
        const price =
          state.tenure === "JEONSE"
            ? s2.jeonse === null
              ? '<span class="none">전세 없음</span>'
              : formatManwon(s2.jeonse)
            : s2.deposit === null || s2.monthly === null
              ? '<span class="none">월세 없음</span>'
              : `${s2.deposit.toLocaleString()}/${s2.monthly}`;
        return `<tr><td>${label}</td><td>${price}</td><td>${s2.n.toLocaleString()}건</td></tr>`;
      })
      .join("");

    // 매물은 우리가 가지고 있지 않다. 시세로 동네를 좁혔으면 실제 방은 그쪽에서 본다.
    const naver =
      "https://new.land.naver.com/houses?ms=" +
      d.lat.toFixed(6) + "," + d.lon.toFixed(6) + ",15" +
      "&a=VL:DDDGG:JWJT:OPST&e=RETAIL";

    el.innerHTML =
      `<div class="dtop"><div><h3>${d.name}</h3><div class="gu">${d.gu}</div></div>` +
      `<button class="close" type="button" aria-label="닫기">✕</button></div>` +
      (minutes === undefined
        ? ""
        : `<div class="commute">${originLabel()} ${minutes}분</div>`) +
      `<div class="unit">${state.tenure === "JEONSE" ? "전세 보증금" : "보증금 / 월세 (만원)"}</div>` +
      `<table>${rows}</table>` +
      `<a class="go" href="${naver}" target="_blank" rel="noopener">이 동네 매물 보기 →</a>`;
    el.hidden = false;
    el.querySelector(".close")?.addEventListener("click", () => {
      openDong = null;
      el.hidden = true;
    });
  }

  /** 링크로 들어왔으면 그 조건으로 시작한다. 못 읽는 값은 조용히 무시한다. */
  function applyShared() {
    const q = decodeState(location.hash);
    if (Object.keys(q).length === 0) return;

    const byName = (name: string) => findStation(meta, name);
    if (q.origin !== undefined) {
      const i = byName(q.origin);
      if (i !== null) state.origin = i;
    }
    if (q.origin2) {
      const i = byName(q.origin2);
      if (i !== null) {
        state.origin2 = i;
        origin2Input.value = comboLabelFor(i);
        $("origin2Clear").hidden = false;
      }
    }
    if (q.direction) state.direction = q.direction;
    if (q.timeIndex !== undefined) state.timeIndex = q.timeIndex;
    if (q.budget !== undefined) state.budget = q.budget;
    if (q.walkCap !== undefined) state.walkCap = q.walkCap;
    if (q.room) state.room = q.room;
    if (q.tenure) state.tenure = q.tenure;
    if (q.cap !== undefined) state.cap = q.cap;
    if (q.amenities) state.amenities = new Set(q.amenities);

    originInput.value = comboLabelFor(state.origin);
    timeSlider.value = String(state.timeIndex);
    $<HTMLInputElement>("budget").value = String(state.budget);
    $<HTMLInputElement>("walk").value = String(state.walkCap);
    $<HTMLInputElement>("cap").value = String(state.cap === 0 ? 0 : state.cap / capStep());
    syncSeg("roomSeg", "room", state.room);
    syncSeg("tenureSeg", "tenure", state.tenure);
    $("dirArrive").setAttribute("aria-pressed", String(state.direction === "ARRIVE_BY"));
    $("dirDepart").setAttribute("aria-pressed", String(state.direction === "DEPART_AT"));
  }

  /**
   * 주소창을 항상 지금 조건과 맞춰 둔다.
   *
   * `pushState` 가 아니라 `replaceState` 다. 슬라이더를 한 칸 옮길 때마다 히스토리가
   * 쌓이면 뒤로가기가 수십 번 눌러야 빠져나가는 함정이 된다.
   */
  function syncUrl() {
    const q = encodeState({
      origin: meta.stations[state.origin].name,
      origin2: state.origin2 === null ? null : meta.stations[state.origin2].name,
      direction: state.direction,
      timeIndex: state.timeIndex,
      budget: state.budget,
      walkCap: state.walkCap,
      room: state.room,
      tenure: state.tenure,
      cap: state.cap,
      amenities: [...state.amenities],
    });
    history.replaceState(null, "", "#" + q);
  }

  /** 세그먼트에서 고른 항목만 눌린 상태로. */
  function syncSeg(segId: string, attr: string, value: string) {
    for (const b of $(segId).querySelectorAll("button")) {
      b.setAttribute("aria-pressed", String(b.dataset[attr] === value));
    }
  }

  function setDirection(d: Direction) {
    state.direction = d;
    $("dirArrive").setAttribute("aria-pressed", String(d === "ARRIVE_BY"));
    $("dirDepart").setAttribute("aria-pressed", String(d === "DEPART_AT"));
    onInputChanged();
  }

  /** 목록에서 고른 표시값이 먼저다. 직접 타이핑한 경우에는 이름으로 찾는다. */
  function resolveOrigin(text: string): number | null {
    const exact = displayToIndex.get(text.trim());
    return exact !== undefined ? exact : findStation(meta, text);
  }

  async function run() {
    const name = originInput.value.trim();
    const found = resolveOrigin(name);
    if (found === null) {
      stage("'" + name + "' 역을 찾을 수 없습니다");
      return;
    }
    state.origin = found;
    hasRun = true;
    await render();
  }

  async function render(): Promise<void> {
    if (rendering) {
      queued = true;
      return;
    }
    rendering = true;
    try {
      await draw();
    } finally {
      rendering = false;
      if (queued) {
        queued = false;
        void render();
      }
    }
  }

  async function draw() {
    const slot = currentSlot();
    syncLabels();

    const t0 = performance.now();
    const set = await provider.reachability(state.origin, slot.index);
    let within = set.stationsWithin(state.budget);

    // 맞벌이: 두 직장 모두에서 예산 안에 드는 역만 남긴다.
    // 각 역의 값은 둘 중 **더 오래 걸리는 쪽**이다. 두 사람 다 그 시간 안에
    // 닿아야 하므로 max 가 맞다. 행렬을 하나 더 읽고 배열을 훑는 게 전부라
    // 라우팅을 다시 돌리는 것과 비교가 안 되게 싸다.
    if (state.origin2 !== null) {
      const set2 = await provider.reachability(state.origin2, slot.index);
      const both: Array<[number, number]> = [];
      for (const [i] of within) {
        const b = set2.minutesToStation(i);
        if (b === null) continue;
        const a = set.minutesToStation(i);
        if (a === null) continue;
        const worst = Math.max(a, b);
        if (worst <= state.budget) both.push([i, worst]);
      }
      within = both;
    }

    const field = buildField(meta.stations, within, {
      budgetMinutes: state.budget,
      walkCapMinutes: state.walkCap,
      cellMeters: CELL_METERS,
    });
    const bands = field
      ? buildIsobandsGeoJSON(field, breaksFor(state.budget, RAMP.length))
      : ({ type: "FeatureCollection", features: [] } as GeoJSON.FeatureCollection);

    if (!map) {
      stage("지도를 준비하는 중입니다");
      return;
    }
    map.setBands(bands, RAMP, state.budget);
    map.setStations(buildStationGeoJSON(meta.stations, within));

    // 도달권 안 + 예산 이내. 폴리곤 검사 없이 스칼라 필드를 찍어보면 O(1) 이다.
    const buildings = field
      ? filterRentals(rentals, field, {
          room: state.room,
          tenure: state.tenure,
          cap: state.cap,
          budgetMinutes: state.budget,
        })
      : [];
    map.setComplexes(buildRentalGeoJSON(buildings, state.tenure));

    const dongPicks = (
      field
        ? filterDongs(dongs, field, {
            room: state.room,
            tenure: state.tenure,
            budgetMinutes: state.budget,
            cap: state.cap,
          })
        : []
    ).filter((p) => passes(amenityData[dongKey(p.d)], state.amenities, amenityTh));
    map.setDongs(buildDongGeoJSON(dongPicks, state.tenure));
    dongMinutes = new Map(dongPicks.map((p) => [dongKey(p.d), Math.round(p.minutes)]));
    renderRank(dongPicks);
    if (openDong) showDetail(openDong);

    // 직장이 수원이면 서울 화면에는 결과가 거의 안 보인다. 첫 결과에 한 번만 맞춰준다.
    if (!fitted && field) {
      fitted = true;
      map.fitBounds(coreBounds(meta, within, state.walkCap), panelPadding());
    }

    const ms = Math.round(performance.now() - t0);
    const verb = state.direction === "ARRIVE_BY" ? "까지 도착" : "에 출발";
    const who =
      state.origin2 === null
        ? meta.stations[state.origin].name
        : meta.stations[state.origin].name + " + " + meta.stations[state.origin2].name;
    // 헤드라인은 사용자가 방금 한 질문의 답이어야 한다. 조건을 좁히면 이 숫자가
    // 줄어드는 게 보여야 조작에 의미가 생긴다.
    const head =
      dongPicks.length > 0
        ? `${ROOM_LABEL[state.room]} 살 만한 동네 ${dongPicks.length}곳`
        : dongs.length > 0
          ? "조건에 맞는 동네가 없습니다"
          : `역 ${within.length}개 도달`;
    const capText =
      state.cap === 0
        ? ""
        : state.tenure === "WOLSE"
          ? ` · 월세 ${state.cap}만 이하`
          : ` · 전세 ${formatManwon(state.cap)} 이하`;
    const amenityText =
      state.amenities.size === 0
        ? ""
        : " · " +
          AMENITIES.filter((a) => state.amenities.has(a.code))
            .map((a) => a.label)
            .join("·");
    const detail =
      `${who} ${slot.label.slice(3)}${verb} · ${state.budget}분 이내` +
      capText + amenityText + ` · ${ms}ms`;
    const el = document.getElementById("status");
    if (el) el.innerHTML = `${head}<br><span class="dim">${detail}</span>`;

    // 값이 바뀌었다는 걸 눈으로 알리는 짧은 강조. 슬라이더를 움직였는데
    // 화면 어딘가가 반응하지 않으면 멈춘 것처럼 보인다.
    bump("budgetVal");
  }
}

/**
 * 화면을 맞출 범위.
 *
 * 도달 격자 전체에 맞추면 **가장 먼 역 하나가 배율을 정한다.** 45분이면 경춘선
 * 끝자락 한 역이 잡히는데, 그것 때문에 서울 도심이 화면의 1/5 로 줄어든다.
 * 세로로 긴 휴대폰에서는 더 심하다 — 가로를 맞추느라 세로가 두 배로 벌어져서
 * 개성부터 천안까지 나온다.
 *
 * 그래서 도달 역의 5~95 분위 범위에 맞춘다. 바깥 10%를 잘라내면 대부분의 답이
 * 있는 곳이 화면을 채우고, 잘린 부분도 조금만 밀면 보인다. 화면은 "전부"가 아니라
 * "어디를 봐야 하는지"를 보여줘야 한다.
 */
function coreBounds(
  meta: Manifest,
  within: Array<[number, number]>,
  walkCapMinutes: number,
): Bounds {
  const lons: number[] = [];
  const lats: number[] = [];
  for (const [i] of within) {
    const st = meta.stations[i];
    lons.push(st.lon);
    lats.push(st.lat);
  }
  if (lons.length === 0) return SEOUL_BOUNDS;
  lons.sort((a, b) => a - b);
  lats.sort((a, b) => a - b);
  const q = (v: number[], p: number) => v[Math.min(v.length - 1, Math.floor(v.length * p))];

  // 역에서 걸어갈 수 있는 만큼은 더 보여줘야 한다. 도보 1분에 80m 로 잡는다.
  const padMeters = walkCapMinutes * 80 + 1500;
  const padLat = padMeters / 111_000;
  const padLon = padMeters / (111_000 * Math.cos((q(lats, 0.5) * Math.PI) / 180));
  return {
    west: q(lons, 0.05) - padLon,
    east: q(lons, 0.95) + padLon,
    south: q(lats, 0.05) - padLat,
    north: q(lats, 0.95) + padLat,
  };
}

function findStation(meta: Manifest, name: string): number | null {
  const exact = meta.stations.find((s) => s.name === name);
  if (exact) return exact.index;
  const partial = meta.stations.find((s) => s.name.startsWith(name));
  return partial ? partial.index : null;
}

async function boot() {
  await main();
}

boot().catch((e) => {
  console.error(e);
  stage("오류: " + e.message);
});
