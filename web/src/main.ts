import maplibregl from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import { StationMatrixProvider } from "./provider";
import { buildGridGeoJSON, buildStationGeoJSON } from "./grid";
import type { Direction, Manifest } from "./types";

const GRID_SOURCE = "reach";
const STATION_SOURCE = "reach-stations";
const BASEMAP = "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json";

/** 격자 한 칸 크기(m). 작을수록 곱지만 셀 수가 제곱으로 는다. */
const CELL_METERS = 400;

/** 처음 열었을 때 보일 범위. 서울 시가지가 들어와야 어디를 보고 있는지 안다. */
const SEOUL_BOUNDS: [[number, number], [number, number]] = [
  [126.76, 37.42],
  [127.19, 37.70],
];

/** 패널이 지도를 가린다. 좁은 화면은 아래를, 넓은 화면은 왼쪽을 비워둔다. */
function panelPadding() {
  return window.innerWidth <= 640
    ? { top: 24, right: 24, bottom: Math.round(window.innerHeight * 0.42), left: 24 }
    : { top: 24, right: 24, bottom: 24, left: 340 };
}

/** 짧을수록 진하게. 격자는 칸마다 한 번만 그려지므로 겹쳐서 어두워지지 않는다. */
const RAMP: Array<[number, string]> = [
  [0, "#12467f"],
  [10, "#2b6cb0"],
  [20, "#4a90c4"],
  [30, "#7fb3d5"],
  [45, "#aecfe4"],
  [60, "#d6e6f2"],
];

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;

function stage(msg: string) {
  const el = document.getElementById("status");
  if (el) el.textContent = msg;
}

/** 외부 타일이 막힌 환경에서도 도달권만은 보이게 하는 최소 스타일. */
const FALLBACK_STYLE: maplibregl.StyleSpecification = {
  version: 8,
  sources: {},
  layers: [{ id: "bg", type: "background", paint: { "background-color": "#eef1f5" } }],
};

/**
 * 스타일을 지도 생성 **전에** 직접 받아본다.
 *
 * 예전에는 지도를 만들어 두고 isStyleLoaded() 가 8초 안에 참이 되는지로 판단했는데,
 * 이 함수는 스프라이트·폰트·타일이 전부 끝나야 참이 된다. 모바일에서는 쉽게 8초를
 * 넘겨서, 멀쩡히 로딩 중이던 배경지도를 폴백으로 갈아치워 버렸다.
 * 스타일 JSON 을 받아보는 것으로 판단하면 그 경합이 아예 없다.
 */
async function loadStyle(): Promise<{ style: maplibregl.StyleSpecification; ok: boolean }> {
  try {
    const res = await fetch(BASEMAP, { signal: AbortSignal.timeout(12_000) });
    if (!res.ok) throw new Error("HTTP " + res.status);
    return { style: await res.json(), ok: true };
  } catch (e) {
    console.warn("배경지도 스타일 로드 실패, 폴백 사용:", e);
    return { style: FALLBACK_STYLE, ok: false };
  }
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
  // 선언이 여기 있어야 한다. render() 는 함수 선언이라 호이스팅되지만 let 은 TDZ 라,
  // 아래에 두면 첫 render() 호출이 "Cannot access 'rendering' before initialization" 으로 죽는다.
  let rendering = false;
  let queued = false;
  /** 버튼을 누르기 전에는 아무것도 칠하지 않는다. */
  let hasRun = false;
  /** 첫 결과에만 화면을 맞춘다. 이후 슬라이더를 만질 때 지도가 튀면 성가시다. */
  let fitted = false;

  stage("지도 불러오는 중…");
  const loaded = await loadStyle();

  const map = new maplibregl.Map({
    container: "map",
    style: loaded.style,
    bounds: SEOUL_BOUNDS,
    fitBoundsOptions: { padding: panelPadding() },
    attributionControl: { compact: true },
  });
  const mapReady = map.once("load");
  // 콘솔에서 지도 상태를 들여다볼 수 있게. id="map" 때문에 window.map 은 div 라 이름을 달리 쓴다.
  (window as unknown as { reachMap: maplibregl.Map }).reachMap = map;

  // MapLibre 는 스프라이트·폰트·타일 로드 실패를 error 이벤트로만 알린다. 안 듣고 있으면
  // "스타일이 영영 안 끝난다"는 증상만 보이고 원인을 못 찾는다.
  const mapErrors: string[] = [];
  (window as unknown as { reachMapErrors: string[] }).reachMapErrors = mapErrors;
  map.on("error", (e) => {
    const msg = (e as { error?: Error }).error?.message ?? String(e);
    mapErrors.push(msg);
    console.warn("[map]", msg);
  });
  map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
  map.addControl(new maplibregl.GeolocateControl({ trackUserLocation: false }), "top-right");

  const datalist = $<HTMLDataListElement>("stations");
  for (const s of [...meta.stations].sort((a, b) => a.name.localeCompare(b.name, "ko"))) {
    const opt = document.createElement("option");
    opt.value = s.name;
    opt.label = s.lines.join(", ");
    datalist.appendChild(opt);
  }
  $<HTMLInputElement>("origin").value = meta.stations[state.origin].name;
  $("legend").innerHTML = RAMP.map(([, c]) => '<i style="background:' + c + '"></i>').join("");
  $("warn").textContent =
    (loaded.ok ? "" : "⚠ 배경지도를 불러오지 못해 도달권만 표시합니다. ") + "⚠ " + meta.warning;

  const timeSlider = $<HTMLInputElement>("time");
  timeSlider.max = String(arriveSlots.length - 1);

  void mapReady;
  if (!(await attachLayers(map))) {
    // 20초를 기다려도 스타일이 안 붙으면 그때만 최소 스타일로 내려간다.
    // 배경지도는 잃지만 도달권은 보인다.
    map.setStyle(FALLBACK_STYLE);
    // styledata 조차 안 오는 환경이 있다. 무한정 기다리면 화면이 영영 안 뜬다.
    await Promise.race([
      new Promise((r) => map.once("styledata", r)),
      new Promise((r) => setTimeout(r, 3000)),
    ]);
    await attachLayers(map, 5000);
    $("warn").textContent = "⚠ 배경지도를 불러오지 못해 도달권만 표시합니다. " + $("warn").textContent;
  }
  syncLabels();
  stage("직장 역과 시간을 정하고 [도달권 보기]를 누르세요");

  $("run").addEventListener("click", () => void run());

  $<HTMLInputElement>("origin").addEventListener("change", (e) => {
    const name = (e.target as HTMLInputElement).value.trim();
    if (name === "") return;
    if (findStation(meta, name) === null) {
      stage("'" + name + "' 역을 찾을 수 없습니다");
      return;
    }
    onInputChanged();
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

  // 백그라운드 탭에서 열리면 MapLibre 가 렌더 루프를 못 돌려 레이어가 안 붙는다.
  // 탭이 보이는 순간 다시 시도해야 사용자가 새로고침하지 않아도 살아난다.
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden && hasRun) void render();
  });

  map.on("click", STATION_SOURCE, (e) => {
    const f = e.features?.[0];
    if (!f) return;
    new maplibregl.Popup({ closeButton: false })
      .setLngLat(e.lngLat)
      .setHTML("<b>" + f.properties!.name + "</b><br>" + f.properties!.minutes + "분")
      .addTo(map);
  });

  function currentSlot() {
    const slots = state.direction === "ARRIVE_BY" ? arriveSlots : departSlots;
    return slots[Math.min(state.timeIndex, slots.length - 1)];
  }

  /** 라벨은 칠하지 않아도 항상 최신이어야 한다. 슬라이더를 움직였는데 숫자가 안 바뀌면 고장으로 보인다. */
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

  /** 버튼이 눌렸을 때만 계산이 시작된다. */
  async function run() {
    const name = $<HTMLInputElement>("origin").value.trim();
    const found = findStation(meta, name);
    if (found === null) {
      stage("'" + name + "' 역을 찾을 수 없습니다");
      return;
    }
    state.origin = found;
    hasRun = true;
    await render();
  }

  function setDirection(d: Direction) {
    state.direction = d;
    $("dirArrive").setAttribute("aria-pressed", String(d === "ARRIVE_BY"));
    $("dirDepart").setAttribute("aria-pressed", String(d === "DEPART_AT"));
    onInputChanged();
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
    // 레이어가 아직 안 붙었을 수 있다. 탭이 백그라운드면 MapLibre 가 렌더 루프를 안 돌려서
    // 스타일 로딩이 끝나지 않고, addSource 가 계속 거부된다. 그 상태로 setData 를 부르면
    // "Cannot read properties of undefined" 로 죽는다. 확인하고, 없으면 한 번 더 붙여본다.
    if (!map.getSource(GRID_SOURCE) || !map.getSource(STATION_SOURCE)) {
      await attachLayers(map, 3000);
    }
    const gridSource = map.getSource(GRID_SOURCE) as maplibregl.GeoJSONSource | undefined;
    const stationSource = map.getSource(STATION_SOURCE) as maplibregl.GeoJSONSource | undefined;
    if (!gridSource || !stationSource) {
      stage("지도 레이어를 붙이지 못했습니다 (탭이 백그라운드면 생길 수 있습니다)");
      return;
    }

    const slot = currentSlot();
    syncLabels();

    const t0 = performance.now();
    const set = await provider.reachability(state.origin, slot.index);
    const within = set.stationsWithin(state.budget);

    const grid = buildGridGeoJSON(meta.stations, within, {
      budgetMinutes: state.budget,
      walkCapMinutes: state.walkCap,
      cellMeters: CELL_METERS,
    });
    gridSource.setData(grid);
    stationSource.setData(buildStationGeoJSON(meta.stations, within));

    // 직장이 수원이면 서울 화면에는 결과가 거의 안 보인다. 첫 결과에 한 번만 맞춰준다.
    if (!fitted && grid.features.length > 0) {
      fitted = true;
      const b = new maplibregl.LngLatBounds();
      for (const f of grid.features) {
        const ring = (f.geometry as GeoJSON.Polygon).coordinates[0];
        b.extend(ring[0] as [number, number]);
        b.extend(ring[2] as [number, number]);
      }
      map.fitBounds(b, { padding: panelPadding(), duration: 700 });
    }

    const ms = Math.round(performance.now() - t0);
    const verb = state.direction === "ARRIVE_BY" ? "까지 도착" : "에 출발";
    stage(
      meta.stations[state.origin].name + " " + slot.label.slice(3) + verb +
      " · 도달역 " + within.length + "개 · 칸 " + grid.features.length + "개 · " + ms + "ms",
    );
  }
}

/**
 * 스타일이 준비될 때까지 기다렸다가 레이어를 얹는다.
 *
 * `load` 는 타일까지 전부 받아야 발생하고, `addLayer` 는 스타일이 덜 로드됐으면
 * "Style is not done loading" 으로 거부한다. 전에는 타임아웃이 지나면 스타일 자체를
 * 폴백으로 갈아치웠는데, 그게 모바일에서 멀쩡히 로딩 중이던 배경지도를 날리는
 * 원인이었다. 갈아치우지 말고 붙을 때까지 다시 시도하는 게 맞다.
 */
async function attachLayers(map: maplibregl.Map, timeoutMs = 20_000): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      installLayers(map);
      return true;
    } catch {
      await Promise.race([
        new Promise((r) => map.once("styledata", r)),
        new Promise((r) => setTimeout(r, 500)),
      ]);
    }
  }
  return false;
}

function findStation(meta: Manifest, name: string): number | null {
  const exact = meta.stations.find((s) => s.name === name);
  if (exact) return exact.index;
  const partial = meta.stations.find((s) => s.name.startsWith(name));
  return partial ? partial.index : null;
}

/**
 * 여러 번 불려도 안전해야 한다. [attachLayers] 가 재시도하는데, 소스만 붙고
 * 레이어에서 실패한 상태로 다시 들어오면 "source already exists" 로 깨진다.
 */
function installLayers(map: maplibregl.Map) {
  const empty: GeoJSON.FeatureCollection = { type: "FeatureCollection", features: [] };
  if (!map.getSource(GRID_SOURCE)) map.addSource(GRID_SOURCE, { type: "geojson", data: empty });
  if (!map.getSource(STATION_SOURCE)) map.addSource(STATION_SOURCE, { type: "geojson", data: empty });
  if (map.getLayer("reach-fill") && map.getLayer(STATION_SOURCE)) return;

  // 라벨 아래에 깔아야 동네 이름이 보인다. 어디가 어딘지 알아야 쓸모가 있다.
  const firstSymbol = map.getStyle().layers?.find((l) => l.type === "symbol")?.id;

  map.addLayer(
    {
      id: "reach-fill",
      type: "fill",
      source: GRID_SOURCE,
      paint: {
        // 격자는 칸당 한 번만 그려지므로 반투명이어도 겹쳐서 어두워지지 않는다.
        "fill-opacity": 0.6,
        "fill-color": [
          "step",
          ["get", "minutes"],
          RAMP[0][1],
          ...RAMP.slice(1).flatMap(([m, c]) => [m, c]),
        ] as maplibregl.DataDrivenPropertyValueSpecification<string>,
      },
    },
    firstSymbol,
  );

  map.addLayer(
    {
      id: STATION_SOURCE,
      type: "circle",
      source: STATION_SOURCE,
      paint: {
        "circle-radius": ["interpolate", ["linear"], ["zoom"], 9, 1.8, 14, 4.5],
        "circle-color": "#ffffff",
        "circle-stroke-color": "#12467f",
        "circle-stroke-width": 1,
        "circle-opacity": 0.95,
      },
    },
    firstSymbol,
  );
}

main().catch((e) => {
  console.error(e);
  stage("오류: " + e.message);
});
