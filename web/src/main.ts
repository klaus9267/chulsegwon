import maplibregl from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import { StationMatrixProvider } from "./provider";
import { buildReachGeoJSON, buildStationGeoJSON } from "./reach";
import type { Direction, Manifest } from "./types";

const REACH_SOURCE = "reach";
const STATION_SOURCE = "reach-stations";

/** 짧을수록 진하게. 마지막에 그려지는(=짧은) 밴드가 위로 올라온다. */
const RAMP: Array<[number, string]> = [
  [0, "#1a4d8f"], [10, "#2b6cb0"], [20, "#4a90c4"],
  [30, "#7fb3d5"], [45, "#aecfe4"], [60, "#d3e3f0"],
];

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;

const BASEMAP = "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json";

/**
 * 외부 타일이 막힌 환경에서도 도달권 자체는 보이게 하는 최소 스타일.
 * 배경지도가 없으면 형태만 보이지만, 아무것도 안 보이는 것보다 낫다.
 */
const FALLBACK_STYLE: maplibregl.StyleSpecification = {
  version: 8,
  sources: {},
  layers: [{ id: "bg", type: "background", paint: { "background-color": "#eef1f5" } }],
};

function stage(msg: string) {
  const el = document.getElementById("status");
  if (el) el.textContent = msg;
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

  // --- 지도 ---
  const map = new maplibregl.Map({
    container: "map",
    style: BASEMAP,
    center: [meta.stations[state.origin].lon, meta.stations[state.origin].lat],
    zoom: 10.2,
    attributionControl: { compact: true },
  });
  // load 구독은 지금 걸어야 한다. DOM 작업 뒤로 미루면 그 사이 이벤트가 지나가 영영 안 온다.
  const mapReady = map.once("load");
  map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
  map.addControl(new maplibregl.GeolocateControl({ trackUserLocation: false }), "top-right");

  // --- UI 채우기 ---
  const datalist = $("stations") as HTMLDataListElement;
  for (const s of [...meta.stations].sort((a, b) => a.name.localeCompare(b.name, "ko"))) {
    const opt = document.createElement("option");
    opt.value = s.name;
    opt.label = s.lines.join(", ");
    datalist.appendChild(opt);
  }
  ($("origin") as HTMLInputElement).value = meta.stations[state.origin].name;
  $("legend").innerHTML = RAMP.map(([, c]) => `<i style="background:${c}"></i>`).join("");
  $("warn").textContent = "⚠ " + meta.warning;

  const timeSlider = $("time") as HTMLInputElement;
  timeSlider.max = String(arriveSlots.length - 1);

  stage("지도 불러오는 중…");
  // 배경지도는 외부 CDN 이라 막히거나 느릴 수 있다. 실패해도 도달권은 그려야 한다.
  let basemapOk = true;
  map.once("error", () => { basemapOk = false; });
  // 스타일이 이미 로드된 뒤라면 load 는 다시 오지 않는다. 둘 다 대비한다.
  if (!map.isStyleLoaded()) {
    await Promise.race([
      mapReady,
      new Promise((r) => map.once("styledata", r)),
      new Promise((r) => setTimeout(r, 8000)),
    ]);
  }
  // 에러 이벤트 없이 조용히 안 오는 경우도 있다(외부 요청 차단 등). 시간 내 안 뜨면 폴백.
  if (!map.isStyleLoaded()) {
    void basemapOk;
    map.setStyle(FALLBACK_STYLE);
    await new Promise((r) => map.once("styledata", r));
    $("warn").textContent = "⚠ 배경지도를 불러오지 못해 도달권만 표시합니다. " + $("warn").textContent;
  }
  (window as unknown as { map: maplibregl.Map }).map = map;
  stage("도달권 계산 중…");
  installLayers(map);
  await render();

  // --- 이벤트 ---
  ($("origin") as HTMLInputElement).addEventListener("change", async (e) => {
    const name = (e.target as HTMLInputElement).value.trim();
    const found = findStation(meta, name);
    if (found === null) { setStatus(`'${name}' 역을 찾을 수 없습니다`); return; }
    state.origin = found;
    map.easeTo({ center: [meta.stations[found].lon, meta.stations[found].lat] });
    await render();
  });

  $("dirArrive").addEventListener("click", () => setDirection("ARRIVE_BY"));
  $("dirDepart").addEventListener("click", () => setDirection("DEPART_AT"));

  timeSlider.addEventListener("input", () => { state.timeIndex = +timeSlider.value; void render(); });
  ($("budget") as HTMLInputElement).addEventListener("input", (e) => {
    state.budget = +(e.target as HTMLInputElement).value; void render();
  });
  ($("walk") as HTMLInputElement).addEventListener("input", (e) => {
    state.walkCap = +(e.target as HTMLInputElement).value; void render();
  });

  map.on("click", STATION_SOURCE, (e) => {
    const f = e.features?.[0];
    if (!f) return;
    new maplibregl.Popup({ closeButton: false })
      .setLngLat(e.lngLat)
      .setHTML(`<b>${f.properties!.name}</b><br>${f.properties!.minutes}분`)
      .addTo(map);
  });
  map.on("mouseenter", STATION_SOURCE, () => (map.getCanvas().style.cursor = "pointer"));
  map.on("mouseleave", STATION_SOURCE, () => (map.getCanvas().style.cursor = ""));

  function setDirection(d: Direction) {
    state.direction = d;
    $("dirArrive").setAttribute("aria-pressed", String(d === "ARRIVE_BY"));
    $("dirDepart").setAttribute("aria-pressed", String(d === "DEPART_AT"));
    void render();
  }

  function setStatus(msg: string) { $("status").textContent = msg; }

  async function render() {
    const slots = state.direction === "ARRIVE_BY" ? arriveSlots : departSlots;
    const slot = slots[Math.min(state.timeIndex, slots.length - 1)];

    $("timeVal").textContent = slot.label.replace(/^(도착|출발) /, "");
    $("budgetVal").textContent = `${state.budget}분`;
    $("walkVal").textContent = state.walkCap === 0 ? "역만" : `${state.walkCap}분`;
    $("legendMax").textContent = `${state.budget}분`;

    const t0 = performance.now();
    const set = await provider.reachability(state.origin, slot.index);
    const within = set.stationsWithin(state.budget);

    (map.getSource(REACH_SOURCE) as maplibregl.GeoJSONSource).setData(
      buildReachGeoJSON(meta.stations, within, {
        budgetMinutes: state.budget,
        walkCapMinutes: state.walkCap,
      }),
    );
    (map.getSource(STATION_SOURCE) as maplibregl.GeoJSONSource).setData(
      buildStationGeoJSON(meta.stations, within),
    );

    const ms = Math.round(performance.now() - t0);
    const verb = state.direction === "ARRIVE_BY" ? "까지 도착" : "에 출발";
    setStatus(`${meta.stations[state.origin].name} ${slot.label.slice(3)}${verb} · 도달역 ${within.length}개 · ${ms}ms`);
  }
}

function findStation(meta: Manifest, name: string): number | null {
  const exact = meta.stations.find((s) => s.name === name);
  if (exact) return exact.index;
  const partial = meta.stations.find((s) => s.name.startsWith(name));
  return partial ? partial.index : null;
}

function installLayers(map: maplibregl.Map) {
  const empty: GeoJSON.FeatureCollection = { type: "FeatureCollection", features: [] };
  map.addSource(REACH_SOURCE, { type: "geojson", data: empty });
  map.addSource(STATION_SOURCE, { type: "geojson", data: empty });

  // 라벨 아래에 깔아야 동네 이름이 보인다.
  const firstSymbol = map.getStyle().layers?.find((l) => l.type === "symbol")?.id;

  map.addLayer({
    id: "reach-fill",
    type: "fill",
    source: REACH_SOURCE,
    paint: {
      // fill-opacity 1 + 시간 내림차순 정렬 = 겹침이 어두워지지 않고 밴드처럼 보인다
      "fill-opacity": 0.85,
      "fill-color": [
        "step", ["get", "minutes"],
        RAMP[0][1],
        ...RAMP.slice(1).flatMap(([m, c]) => [m, c]),
      ] as maplibregl.DataDrivenPropertyValueSpecification<string>,
    },
  }, firstSymbol);

  map.addLayer({
    id: STATION_SOURCE,
    type: "circle",
    source: STATION_SOURCE,
    paint: {
      "circle-radius": ["interpolate", ["linear"], ["zoom"], 9, 2.2, 14, 5],
      "circle-color": "#ffffff",
      "circle-stroke-color": "#1a4d8f",
      "circle-stroke-width": 1.2,
      "circle-opacity": 0.9,
    },
  }, firstSymbol);
}

main().catch((e) => {
  console.error(e);
  document.getElementById("status")!.textContent = `오류: ${e.message}`;
});
