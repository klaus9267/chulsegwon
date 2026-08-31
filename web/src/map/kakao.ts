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
    CustomOverlay: new (opts: Record<string, unknown>) => KakaoOverlay;
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
  getCenter(): KakaoLatLng;
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
  private stationClick: ((index: number) => void) | null = null;
  private originMarkers: KakaoOverlay[] = [];

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

    // 컨테이너 크기가 잡히기 전에 setBounds 를 부르면 배율이 엉뚱하게 나온다
    // (0 크기 기준으로 맞추려다 과하게 확대된다). 레이아웃을 한 번 갱신하고,
    // 다음 프레임에 맞춘다.
    map.relayout();
    await new Promise((r) => requestAnimationFrame(() => r(null)));
    map.relayout();
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
   * 역 점.
   *
   * `Circle` 을 쓰면 안 된다. 반경이 **미터** 라 확대할수록 화면에서 커져서,
   * 줌인하면 지도가 흰 덩어리로 덮인다. CustomOverlay 로 DOM 을 얹으면 크기가
   * 픽셀 고정이라 어느 배율에서도 같은 크기로 보인다.
   *
   * 상한을 두는 이유는 오버레이가 객체 하나씩이라서다. MapLibre 는 한 레이어로
   * GPU 가 그리지만 여기는 DOM 이므로 수백 개를 넘기면 팬·줌이 무거워진다.
   */
  setStations(stations: GeoJSON.FeatureCollection): void {
    for (const o of this.stationOverlays) o.setMap(null);
    this.stationOverlays = [];

    const MAX = 300;
    for (const f of stations.features.slice(0, MAX)) {
      const [lon, lat] = (f.geometry as GeoJSON.Point).coordinates;
      const p = f.properties as { name: string; index: number; interchange: number };

      const el = document.createElement("div");
      el.className = p.interchange ? "kdot kdot-ic" : "kdot";
      el.dataset.name = p.name;
      el.addEventListener("click", (e) => {
        e.stopPropagation();
        this.stationClick?.(p.index);
      });

      const overlay = new this.ns.maps.CustomOverlay({
        position: new this.ns.maps.LatLng(lat, lon),
        content: el,
        xAnchor: 0.5,
        yAnchor: 0.5,
        clickable: true,
        zIndex: 3,
      });
      overlay.setMap(this.map);
      this.stationOverlays.push(overlay);
    }
  }

  onStationClick(handler: (stationIndex: number) => void): void {
    this.stationClick = handler;
  }

  setOrigins(points: LngLat[]): void {
    for (const m of this.originMarkers) m.setMap(null);
    this.originMarkers = points.map((at) => {
      const m = new this.ns.maps.Marker({
        position: new this.ns.maps.LatLng(at.lat, at.lon),
      });
      m.setMap(this.map);
      return m;
    });
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

  getCenter(): LngLat {
    const c = this.map.getCenter();
    return { lon: c.getLng(), lat: c.getLat() };
  }
}
