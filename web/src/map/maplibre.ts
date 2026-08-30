import maplibregl from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import type { Bounds, LngLat, MapAdapter, Padding, Ramp } from "./adapter";

const BAND_SOURCE = "reach";
const STATION_SOURCE = "reach-stations";
const CARTO = "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json";

const EMPTY: GeoJSON.FeatureCollection = { type: "FeatureCollection", features: [] };

/** 외부 타일이 막힌 환경에서도 도달권만은 보이게 하는 최소 스타일. */
const FALLBACK_STYLE: maplibregl.StyleSpecification = {
  version: 8,
  sources: {},
  layers: [{ id: "bg", type: "background", paint: { "background-color": "#eef1f5" } }],
};

/**
 * 라벨을 한글로 돌린다.
 *
 * CARTO 스타일은 줌 13 미만에서 {name_en} 을 쓴다. 초기 줌이 10 근처라 "SEOUL",
 * "GOYANG" 처럼 영문만 나왔다. 동네를 고르는 도구인데 지명이 영문이면 쓸모가 없다.
 */
function koreanizeLabels(style: maplibregl.StyleSpecification): void {
  const swap = (tf: unknown): unknown => {
    if (typeof tf === "string") return tf.replace(/\{name_en\}/g, "{name}");
    if (tf && typeof tf === "object" && "stops" in tf) {
      const t = tf as { stops: Array<[number, unknown]> };
      return { ...t, stops: t.stops.map(([z, v]) => [z, swap(v)] as [number, unknown]) };
    }
    return tf;
  };
  for (const layer of style.layers) {
    if (layer.type !== "symbol") continue;
    const layout = layer.layout as Record<string, unknown> | undefined;
    if (!layout || layout["text-field"] === undefined) continue;
    layout["text-field"] = swap(layout["text-field"]);
  }
}

/** 브이월드(국토교통부) 배경지도. 래스터라 심볼 레이어가 없어 라벨이 등시선에 덮인다. */
function vworldStyle(key: string): maplibregl.StyleSpecification {
  return {
    version: 8,
    sources: {
      vworld: {
        type: "raster",
        // 브이월드 WMTS 는 {z}/{y}/{x} 순서다. x,y 를 바꿔 쓰면 엉뚱한 타일이 온다.
        tiles: ["https://api.vworld.kr/req/wmts/1.0.0/" + key + "/Base/{z}/{y}/{x}.png"],
        tileSize: 256,
        attribution: '<a href="https://www.vworld.kr/">VWorld</a>',
      },
    },
    layers: [{ id: "vworld", type: "raster", source: "vworld" }],
  };
}

/**
 * 스타일을 지도 생성 **전에** 직접 받아본다.
 *
 * 예전에는 지도를 만들어 두고 isStyleLoaded() 가 8초 안에 참이 되는지로 판단했는데,
 * 이 함수는 스프라이트·폰트·타일이 전부 끝나야 참이 된다. 모바일에서는 쉽게 8초를
 * 넘겨서, 멀쩡히 로딩 중이던 배경지도를 폴백으로 갈아치워 버렸다.
 */
async function loadStyle(): Promise<{ style: maplibregl.StyleSpecification; ok: boolean }> {
  const vworldKey = import.meta.env.VITE_VWORLD_KEY as string | undefined;
  if (vworldKey) return { style: vworldStyle(vworldKey), ok: true };
  try {
    const res = await fetch(CARTO, { signal: AbortSignal.timeout(12_000) });
    if (!res.ok) throw new Error("HTTP " + res.status);
    const style = (await res.json()) as maplibregl.StyleSpecification;
    koreanizeLabels(style);
    return { style, ok: true };
  } catch (e) {
    console.warn("배경지도 스타일 로드 실패, 폴백 사용:", e);
    return { style: FALLBACK_STYLE, ok: false };
  }
}

export class MapLibreAdapter implements MapAdapter {
  readonly name = "CARTO";

  private constructor(
    private readonly map: maplibregl.Map,
    readonly basemapOk: boolean,
    private readonly originMarker: maplibregl.Marker,
  ) {}

  static async create(
    containerId: string,
    initial: Bounds,
    padding: Padding,
  ): Promise<MapLibreAdapter> {
    const loaded = await loadStyle();
    const map = new maplibregl.Map({
      container: containerId,
      style: loaded.style,
      bounds: [
        [initial.west, initial.south],
        [initial.east, initial.north],
      ],
      fitBoundsOptions: { padding },
      attributionControl: { compact: true },
    });
    (window as unknown as { reachMap: maplibregl.Map }).reachMap = map;

    // MapLibre 는 스프라이트·폰트·타일 실패를 error 이벤트로만 알린다. 안 듣고 있으면
    // "스타일이 영영 안 끝난다"는 증상만 보이고 원인을 못 찾는다.
    map.on("error", (e) => console.warn("[map]", (e as { error?: Error }).error?.message ?? e));
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
    map.addControl(new maplibregl.GeolocateControl({ trackUserLocation: false }), "top-right");

    let ok = loaded.ok;
    if (!(await attachLayers(map))) {
      // 20초를 기다려도 스타일이 안 붙으면 그때만 최소 스타일로 내려간다.
      map.setStyle(FALLBACK_STYLE);
      await Promise.race([
        new Promise((r) => map.once("styledata", r)),
        new Promise((r) => setTimeout(r, 3000)),
      ]);
      await attachLayers(map, 5000);
      ok = false;
    }
    return new MapLibreAdapter(map, ok, new maplibregl.Marker({ color: "#e53e3e" }));
  }

  setBands(bands: GeoJSON.FeatureCollection, ramp: Ramp, budgetMinutes: number): void {
    const src = this.map.getSource(BAND_SOURCE) as maplibregl.GeoJSONSource | undefined;
    if (!src) return;
    // 예산이 바뀌면 구간 경계도 같이 움직인다. 색을 예산에 비례해 다시 건다.
    this.map.setPaintProperty("reach-fill", "fill-color", [
      "interpolate",
      ["linear"],
      ["get", "minutes"],
      ...ramp.flatMap(([, color], i) => [(budgetMinutes * i) / (ramp.length - 1), color]),
    ] as maplibregl.DataDrivenPropertyValueSpecification<string>);
    src.setData(bands);
  }

  setStations(stations: GeoJSON.FeatureCollection): void {
    const src = this.map.getSource(STATION_SOURCE) as maplibregl.GeoJSONSource | undefined;
    src?.setData(stations);
  }

  setOrigin(at: LngLat): void {
    this.originMarker.setLngLat([at.lon, at.lat]).addTo(this.map);
  }

  fitBounds(bounds: Bounds, padding: Padding): void {
    this.map.fitBounds(
      [
        [bounds.west, bounds.south],
        [bounds.east, bounds.north],
      ],
      { padding, duration: 700 },
    );
  }

  easeTo(at: LngLat, zoom: number): void {
    this.map.easeTo({ center: [at.lon, at.lat], zoom, duration: 700 });
  }

  /** 레이어가 붙었는지. 백그라운드 탭에서는 실패할 수 있어 호출부가 알아야 한다. */
  get ready(): boolean {
    return !!this.map.getSource(BAND_SOURCE);
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

/** 여러 번 불려도 안전해야 한다. 소스만 붙고 레이어에서 실패한 상태로 재진입할 수 있다. */
function installLayers(map: maplibregl.Map) {
  if (!map.getSource(BAND_SOURCE)) map.addSource(BAND_SOURCE, { type: "geojson", data: EMPTY });
  if (!map.getSource(STATION_SOURCE)) {
    map.addSource(STATION_SOURCE, { type: "geojson", data: EMPTY });
  }
  if (map.getLayer("reach-fill") && map.getLayer(STATION_SOURCE)) return;

  // 라벨 아래에 깔아야 동네 이름이 보인다. 어디가 어딘지 알아야 쓸모가 있다.
  const firstSymbol = map.getStyle().layers?.find((l) => l.type === "symbol")?.id;

  map.addLayer(
    {
      id: "reach-fill",
      type: "fill",
      source: BAND_SOURCE,
      paint: { "fill-opacity": 0.62, "fill-color": "#4a90c4" },
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
