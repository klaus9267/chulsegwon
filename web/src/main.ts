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

  stage("지도 불러오는 중…");
  const loaded = await loadStyle();

  const map = new maplibregl.Map({
    container: "map",
    style: loaded.style,
    center: [meta.stations[state.origin].lon, meta.stations[state.origin].lat],
    zoom: 10.2,
    attributionControl: { compact: true },
  });
  const mapReady = map.once("load");
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
  await render();

  $<HTMLInputElement>("origin").addEventListener("change", (e) => {
    const name = (e.target as HTMLInputElement).value.trim();
    const found = findStation(meta, name);
    if (found === null) {
      stage(name + " 역을 찾을 수 없습니다");
      return;
    }
    state.origin = found;
    map.easeTo({ center: [meta.stations[found].lon, meta.stations[found].lat] });
    void render();
  });

  $("dirArrive").addEventListener("click", () => setDirection("ARRIVE_BY"));
  $("dirDepart").addEventListener("click", () => setDirection("DEPART_AT"));
  timeSlider.addEventListener("input", () => {
    state.timeIndex = +timeSlider.value;
    void render();
  });
  $<HTMLInputElement>("budget").addEventListener("input", (e) => {
    state.budget = +(e.target as HTMLInputElement).value;
    void render();
  });
  $<HTMLInputElement>("walk").addEventListener("input", (e) => {
    state.walkCap = +(e.target as HTMLInputElement).value;
    void render();
  });

  map.on("click", STATION_SOURCE, (e) => {
    const f = e.features?.[0];
    if (!f) return;
    new maplibregl.Popup({ closeButton: false })
      .setLngLat(e.lngLat)
      .setHTML("<b>" + f.properties!.name + "</b><br>" + f.properties!.minutes + "분")
      .addTo(map);
  });

  function setDirection(d: Direction) {
    state.direction = d;
    $("dirArrive").setAttribute("aria-pressed", String(d === "ARRIVE_BY"));
    $("dirDepart").setAttribute("aria-pressed", String(d === "DEPART_AT"));
    void render();
  }

  // 슬라이더를 끌면 입력이 쏟아진다. 그리는 중이면 마지막 요청 하나만 남긴다.
  let rendering = false;
  let queued = false;
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
    const slots = state.direction === "ARRIVE_BY" ? arriveSlots : departSlots;
    const slot = slots[Math.min(state.timeIndex, slots.length - 1)];

    $("timeVal").textContent = slot.label.replace(/^(도착|출발) /, "");
    $("budgetVal").textContent = state.budget + "분";
    $("walkVal").textContent = state.walkCap === 0 ? "역만" : state.walkCap + "분";
    $("legendMax").textContent = state.budget + "분";

    const t0 = performance.now();
    const set = await provider.reachability(state.origin, slot.index);
    const within = set.stationsWithin(state.budget);

    const grid = buildGridGeoJSON(meta.stations, within, {
      budgetMinutes: state.budget,
      walkCapMinutes: state.walkCap,
      cellMeters: CELL_METERS,
    });
    (map.getSource(GRID_SOURCE) as maplibregl.GeoJSONSource).setData(grid);
    (map.getSource(STATION_SOURCE) as maplibregl.GeoJSONSource).setData(
      buildStationGeoJSON(meta.stations, within),
    );

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
