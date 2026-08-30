import type { Bounds, LngLat, MapAdapter, Padding, Ramp } from "./adapter";
import { colorFor, toRings } from "./adapter";

/**
 * 카카오 지도 SDK 를 쓸 수 있는 최소한의 타입.
 *
 * 공식 타입 패키지가 없어서 우리가 실제로 부르는 것만 선언한다. 전부 적으면
 * 유지보수만 늘고, 안 쓰는 API 의 시그니처가 틀려도 아무도 모른다.
 */
interface KakaoLatLng {
  getLat(): number;
  getLng(): number;
}
interface KakaoNS {
  maps: {
    load(cb: () => void): void;
    Map: new (el: HTMLElement, opts: { center: KakaoLatLng; level: number }) => KakaoMap;
    LatLng: new (lat: number, lng: number) => KakaoLatLng;
    LatLngBounds: new (sw?: KakaoLatLng, ne?: KakaoLatLng) => KakaoBounds;
    Polygon: new (opts: Record<string, unknown>) => KakaoOverlay;
    Circle: new (opts: Record<string, unknown>) => KakaoOverlay;
    Marker: new (opts: Record<string, unknown>) => KakaoOverlay;
    ZoomControl: new () => object;
    ControlPosition: { RIGHT: unknown };
  };
}
interface KakaoBounds {
  extend(p: KakaoLatLng): void;
  isEmpty(): boolean;
}
interface KakaoMap {
  setBounds(b: KakaoBounds, ...padding: number[]): void;
  panTo(p: KakaoLatLng): void;
  panBy(dx: number, dy: number): void;
  setLevel(level: number, opts?: { animate?: boolean }): void;
  addControl(c: object, pos: unknown): void;
  relayout(): void;
}
interface KakaoOverlay {
  setMap(map: KakaoMap | null): void;
}

declare global {
  interface Window {
    kakao?: KakaoNS;
  }
}

/** 카카오는 줌 레벨이 작을수록 확대다(1이 가장 가깝다). MapLibre 와 반대라 변환한다. */
function zoomToLevel(zoom: number): number {
  return Math.max(1, Math.min(14, Math.round(21 - zoom)));
}

function loadSdk(appKey: string, timeoutMs = 12_000): Promise<KakaoNS> {
  return new Promise((resolve, reject) => {
    if (window.kakao?.maps?.load) {
      window.kakao.maps.load(() => resolve(window.kakao as KakaoNS));
      return;
    }
    const script = document.createElement("script");
    // autoload=false 로 받아야 load() 시점을 우리가 정할 수 있다.
    script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${appKey}&autoload=false`;
    const timer = setTimeout(() => reject(new Error("카카오 SDK 로드 시간 초과")), timeoutMs);
    script.onload = () => {
      clearTimeout(timer);
      const ns = window.kakao;
      if (!ns?.maps?.load) {
        reject(new Error("카카오 SDK 를 받았지만 maps 네임스페이스가 없다"));
        return;
      }
      ns.maps.load(() => resolve(ns));
    };
    script.onerror = () => {
      clearTimeout(timer);
      // 키가 틀렸거나 도메인이 등록 안 됐을 때 여기로 온다.
      reject(new Error("카카오 SDK 로드 실패 — JavaScript 키와 등록된 도메인을 확인할 것"));
    };
    document.head.appendChild(script);
  });
}

/**
 * 카카오 지도 어댑터.
 *
 * 등시선을 네이티브 Polygon 객체로 그린다. 격자(2,022칸)를 그대로 넘겼다면 불가능했겠지만,
 * 스무딩 후 조각이 40개 수준이라 부담이 없다. 캔버스 오버레이를 직접 만들 필요가 없어졌다.
 */
export class KakaoAdapter implements MapAdapter {
  readonly name = "카카오맵";
  readonly basemapOk = true;

  private bandOverlays: KakaoOverlay[] = [];
  private stationOverlays: KakaoOverlay[] = [];
  private originMarker: KakaoOverlay | null = null;

  private constructor(
    private readonly ns: KakaoNS,
    private readonly map: KakaoMap,
  ) {}

  static async create(
    container: HTMLElement,
    appKey: string,
    initial: Bounds,
    padding: Padding,
  ): Promise<KakaoAdapter> {
    const ns = await loadSdk(appKey);
    const centerLat = (initial.south + initial.north) / 2;
    const centerLon = (initial.west + initial.east) / 2;
    const map = new ns.maps.Map(container, {
      center: new ns.maps.LatLng(centerLat, centerLon),
      level: 8,
    });
    map.addControl(new ns.maps.ZoomControl(), ns.maps.ControlPosition.RIGHT);
    const adapter = new KakaoAdapter(ns, map);
    adapter.fitBounds(initial, padding);
    return adapter;
  }

  setBands(bands: GeoJSON.FeatureCollection, ramp: Ramp, budgetMinutes: number): void {
    for (const o of this.bandOverlays) o.setMap(null);
    this.bandOverlays = [];

    // 시간이 긴 구간부터 그려야 짧은(진한) 구간이 위로 온다.
    const sorted = [...bands.features].sort(
      (a, b) =>
        Number((b.properties as { minutes: number }).minutes) -
        Number((a.properties as { minutes: number }).minutes),
    );

    for (const f of sorted) {
      const minutes = Number((f.properties as { minutes: number }).minutes);
      const color = colorFor(minutes, ramp, budgetMinutes);
      for (const rings of toRings(f.geometry)) {
        // 첫 링이 외곽, 나머지가 구멍. 카카오는 경로 배열을 그대로 받는다.
        const path = rings.map((ring) =>
          ring.map(([lon, lat]) => new this.ns.maps.LatLng(lat, lon)),
        );
        const polygon = new this.ns.maps.Polygon({
          path,
          // 채우기만으로는 인접한 색이 뭉개진다. 얇고 옅은 경계선으로 구간을 읽히게 한다.
          strokeWeight: 1,
          strokeColor: "#0f3d6e",
          strokeOpacity: 0.3,
          fillColor: color,
          fillOpacity: 0.55,
        });
        polygon.setMap(this.map);
        this.bandOverlays.push(polygon);
      }
    }
  }

  /**
   * 역 점은 상한을 둔다.
   *
   * MapLibre 는 점 수백 개를 한 레이어로 GPU 에서 그리지만, 카카오는 오버레이가
   * 객체 하나씩이라 300개를 얹으면 팬·줌이 눈에 띄게 무거워진다.
   */
  setStations(stations: GeoJSON.FeatureCollection): void {
    for (const o of this.stationOverlays) o.setMap(null);
    this.stationOverlays = [];

    const MAX = 200;
    const feats = stations.features.slice(0, MAX);
    for (const f of feats) {
      const [lon, lat] = (f.geometry as GeoJSON.Point).coordinates;
      const dot = new this.ns.maps.Circle({
        center: new this.ns.maps.LatLng(lat, lon),
        radius: 90,
        strokeWeight: 1,
        strokeColor: "#12467f",
        strokeOpacity: 0.9,
        fillColor: "#ffffff",
        fillOpacity: 0.9,
      });
      dot.setMap(this.map);
      this.stationOverlays.push(dot);
    }
  }

  setOrigin(at: LngLat): void {
    const pos = new this.ns.maps.LatLng(at.lat, at.lon);
    if (this.originMarker) this.originMarker.setMap(null);
    this.originMarker = new this.ns.maps.Marker({ position: pos });
    this.originMarker.setMap(this.map);
  }

  /**
   * 패딩을 setBounds 에 그대로 넘기면 안 된다.
   *
   * 카카오는 줌 레벨이 정수라 딱 맞는 배율이 없으면 한 단계 밖으로 반올림한다.
   * 여기에 왼쪽 패딩 340px(가로의 37%)까지 얹으면 한 단계를 통째로 더 잃어서,
   * 서울 도달권을 보려는데 파주~평택이 나온다.
   *
   * 작은 여백으로 배율만 맞추고, 패널을 피하는 건 픽셀 단위 이동으로 처리한다.
   * 배율 손실 없이 같은 결과가 나온다.
   */
  fitBounds(bounds: Bounds, padding: Padding): void {
    const b = new this.ns.maps.LatLngBounds();
    b.extend(new this.ns.maps.LatLng(bounds.south, bounds.west));
    b.extend(new this.ns.maps.LatLng(bounds.north, bounds.east));
    if (b.isEmpty()) return;

    const EDGE = 24;
    this.map.setBounds(b, EDGE, EDGE, EDGE, EDGE);

    // 내용이 오른쪽으로 가려면 지도 중심이 왼쪽으로 가야 한다(부호 반대).
    const dx = -(padding.left - padding.right) / 2;
    const dy = (padding.bottom - padding.top) / 2;
    if (Math.abs(dx) > 4 || Math.abs(dy) > 4) this.map.panBy(dx, dy);
  }

  easeTo(at: LngLat, zoom: number): void {
    this.map.setLevel(zoomToLevel(zoom), { animate: true });
    this.map.panTo(new this.ns.maps.LatLng(at.lat, at.lon));
  }
}
