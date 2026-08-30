import { StationMatrixProvider } from "./provider";
import { buildField, buildStationGeoJSON } from "./grid";
import { buildIsobandsGeoJSON, breaksFor } from "./contour";
import { createCombobox } from "./combobox";
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

/** 패널이 지도를 가린다. 좁은 화면은 아래를, 넓은 화면은 왼쪽을 비워둔다. */
function panelPadding() {
  return window.innerWidth <= 640
    ? { top: 24, right: 24, bottom: Math.round(window.innerHeight * 0.42), left: 24 }
    : { top: 24, right: 24, bottom: 24, left: 340 };
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
  const provider = await StationMatrixProvider.load("/data");
  const meta = provider.manifest();

  const arriveSlots = meta.slots.filter((s) => s.direction === "ARRIVE_BY");
  const departSlots = meta.slots.filter((s) => s.direction === "DEPART_AT");

  const state = {
    origin: findStation(meta, "강남") ?? 0,
    direction: "ARRIVE_BY" as Direction,
    timeIndex: 19,
    budget: 40,
    walkCap: 15,
  };

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
      showOrigin(idx, true);
      onInputChanged();
    },
  });
  originInput.value = meta.stations[state.origin].name;

  $("legend").innerHTML = RAMP.map(([, c]) => '<i style="background:' + c + '"></i>').join("");
  const timeSlider = $<HTMLInputElement>("time");
  timeSlider.max = String(arriveSlots.length - 1);
  syncLabels();
  stage("지도 불러오는 중…");

  $("run").addEventListener("click", () => void run());
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

  // 백그라운드 탭에서 열리면 지도가 렌더 루프를 못 돌려 레이어가 안 붙는다.
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden && hasRun) void render();
  });

  map = await createMap(SEOUL_BOUNDS);
  showOrigin(state.origin, false);
  $("warn").textContent =
    (map.basemapOk ? "" : "⚠ 배경지도를 불러오지 못해 도달권만 표시합니다. ") + "⚠ " + meta.warning;
  stage("직장 역과 시간을 정하고 [도달권 보기]를 누르세요 · 배경지도 " + map.name);

  /**
   * 고른 역을 마커로 찍는다. [move] 면 카메라도 옮긴다.
   *
   * 처음 로드할 때는 옮기지 않는다. 서울 전체가 보이는 초기 뷰를 유지해야
   * 어디를 보고 있는지 알 수 있다.
   */
  function showOrigin(index: number, move: boolean) {
    if (!map) return;
    const st = meta.stations[index];
    map.setOrigin({ lon: st.lon, lat: st.lat });
    // 역이 바뀌면 도달 범위도 달라진다. 다음 계산에서 화면을 다시 맞춰야 한다.
    fitted = false;
    if (move) map.easeTo({ lon: st.lon, lat: st.lat }, 12);
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
    $("legendMax").textContent = state.budget + "분";
  }

  function onInputChanged() {
    syncLabels();
    if (hasRun) void render();
    else stage("[도달권 보기]를 누르면 계산합니다");
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
    const within = set.stationsWithin(state.budget);

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

    // 직장이 수원이면 서울 화면에는 결과가 거의 안 보인다. 첫 결과에 한 번만 맞춰준다.
    if (!fitted && field) {
      fitted = true;
      map.fitBounds(
        {
          west: field.minLon,
          south: field.minLat,
          east: field.minLon + field.cols * field.dLon,
          north: field.minLat + field.rows * field.dLat,
        },
        panelPadding(),
      );
    }

    const ms = Math.round(performance.now() - t0);
    const verb = state.direction === "ARRIVE_BY" ? "까지 도착" : "에 출발";
    stage(
      meta.stations[state.origin].name + " " + slot.label.slice(3) + verb +
        " · 도달역 " + within.length + "개 · 등시선 " + bands.features.length + "구간 · " + ms + "ms",
    );
  }
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
