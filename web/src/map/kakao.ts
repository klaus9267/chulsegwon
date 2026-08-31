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
    event: {
    addListener(target: unknown, type: string, handler: () => void): void;
  };
  ControlPosition: { RIGHT: unknown };
  };
}
interface KakaoBounds {
  extend(p: KakaoLatLng): void;
  isEmpty(): boolean;
}
interface KakaoMap {
  getLevel(): number;
  getCenter(): { getLat(): number; getLng(): number };
  getBounds(): {
    getSouthWest(): { getLat(): number; getLng(): number };
    getNorthEast(): { getLat(): number; getLng(): number };
  };
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
/** rAF 를 기다리되, 백그라운드 탭처럼 rAF 가 안 오는 상황에서도 반드시 풀린다. */
function nextFrame(timeoutMs = 60): Promise<void> {
  return new Promise((resolve) => {
    let done = false;
    const finish = () => {
      if (done) return;
      done = true;
      resolve();
    };
    requestAnimationFrame(finish);
    setTimeout(finish, timeoutMs);
  });
}

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
  private complexOverlays: KakaoOverlay[] = [];
  private originMarkers: KakaoOverlay[] = [];

  /** 컨테이너 크기가 0 이라 미뤄둔 화면 맞추기. 크기가 생기면 실행한다. */
  private pendingFit: { bounds: Bounds; padding: Padding } | null = null;

  private dongFeatures: GeoJSON.Feature[] = [];
  private dongOverlays: KakaoOverlay[] = [];
  private dongClick?: (key: string) => void;

  private constructor(
    private readonly ns: KakaoNS,
    private readonly map: KakaoMap,
    private readonly container: HTMLElement,
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
    const adapter = new KakaoAdapter(ns, map, container);
    // 개발 중 콘솔에서 지도 상태를 직접 볼 수 있어야 배율·레이아웃 문제를 재현한다.
    if (import.meta.env.DEV) (window as unknown as Record<string, unknown>).__kmap = map;

    // 컨테이너 크기가 잡히기 전에 setBounds 를 부르면 배율이 엉뚱하게 나온다
    // (0 크기 기준으로 맞추려다 과하게 확대된다). 레이아웃을 한 번 갱신하고,
    // 다음 프레임에 맞춘다.
    //
    // ⚠️ rAF 만 기다리면 안 된다. 백그라운드 탭에서는 rAF 가 아예 안 불려서
    // 여기서 영원히 멈춘다. 링크를 새 탭으로 열어두고 나중에 보는 흐름은 흔하고,
    // 그때 지도가 초기 배율 그대로 굳는다. 타임아웃과 경쟁시켜 반드시 진행시킨다.
    map.relayout();
    await nextFrame();
    map.relayout();
    adapter.fitBounds(initial, padding);

    // 창 크기가 바뀌면 카카오는 스스로 갱신하지 않는다. 모바일에서 주소창이
    // 접히기만 해도 지도 절반이 회색으로 남는다.
    // 배율이 바뀌면 무엇을 보여줄지가 달라진다. 멀리서 역 점 수백 개는 잡음이고,
    // 가까이서 동 이름만 있으면 정보가 부족하다.
    ns.maps.event.addListener(map, "zoom_changed", () => adapter.applyTier());
    // 보이는 범위를 기준으로 라벨을 솎으므로, 이동해도 다시 뽑아야 한다.
    ns.maps.event.addListener(map, "dragend", () => adapter.renderDongs());

    if (typeof ResizeObserver !== "undefined") {
      new ResizeObserver(() => adapter.onResized()).observe(container);
    }
    // 숨은 탭에서 열렸다면 크기가 0 이었을 수 있다. 보이는 순간 한 번 더 맞춘다.
    document.addEventListener("visibilitychange", () => {
      if (!document.hidden) adapter.onResized();
    });
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

    // 도달권의 바깥 테두리는 이 화면에서 가장 중요한 선이다. "여기까지"를 말하는
    // 유일한 경계라서, 안쪽 구간 경계와 같은 굵기로 그리면 묻힌다.
    const outer = sorted.length > 0
      ? Number((sorted[0].properties as { minutes: number }).minutes)
      : -1;

    for (const f of sorted) {
      const minutes = Number((f.properties as { minutes: number }).minutes);
      const isOuter = minutes === outer;
      const color = colorFor(minutes, ramp, budgetMinutes);
      for (const rings of toRings(f.geometry)) {
        // 첫 링이 외곽, 나머지가 구멍. 카카오는 경로 배열을 그대로 받는다.
        const path = rings.map((ring) =>
          ring.map(([lon, lat]) => new this.ns.maps.LatLng(lat, lon)),
        );
        const polygon = new this.ns.maps.Polygon({
          path,
          // 채우기만으로는 인접한 색이 뭉개진다. 얇고 옅은 경계선으로 구간을 읽히게 한다.
          strokeWeight: isOuter ? 2.5 : 1,
          strokeColor: "#0f3d6e",
          strokeOpacity: isOuter ? 0.55 : 0.28,
          fillColor: color,
          fillOpacity: 0.58,
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
    this.applyTier();
  }

  onDongClick(handler: (key: string) => void): void {
    this.dongClick = handler;
  }

  onStationClick(handler: (stationIndex: number) => void): void {
    this.stationClick = handler;
  }

  /**
   * 단지 점. 역보다 훨씬 많으므로 더 작고 가볍게 그린다.
   * DOM 오버레이라 개수 상한이 필요하다 — 거래가 많은 순으로 잘린다.
   */
  setComplexes(complexes: GeoJSON.FeatureCollection): void {
    for (const o of this.complexOverlays) o.setMap(null);
    this.complexOverlays = [];
    const MAX = 400;
    for (const f of complexes.features.slice(0, MAX)) {
      const [lon, lat] = (f.geometry as GeoJSON.Point).coordinates;
      const p = f.properties as { name: string; jeonse: number | null };
      const el = document.createElement("div");
      el.className = "cdot";
      el.dataset.name = p.name + (p.jeonse ? " · 전세 " + (p.jeonse / 10000).toFixed(1) + "억" : "");
      const overlay = new this.ns.maps.CustomOverlay({
        position: new this.ns.maps.LatLng(lat, lon),
        content: el,
        xAnchor: 0.5,
        yAnchor: 0.5,
        zIndex: 2,
      });
      overlay.setMap(this.map);
      this.complexOverlays.push(overlay);
    }
    this.applyTier();
  }

  setDongs(dongs: GeoJSON.FeatureCollection): void {
    this.dongFeatures = dongs.features;
    this.renderDongs();
  }

  /**
   * 배율에 따라 무엇을 보여줄지.
   *
   * 카카오 level 은 작을수록 확대다. 세 단계로 나눈 근거:
   *
   * - **광역(≥9)**: 지금 답해야 할 질문은 "어느 동네냐"다. 역 점 수백 개는 등시선을
   *   덮어버리는 잡음일 뿐이라 지운다. 동 시세만 남긴다.
   * - **시(6~8)**: 동네를 좁혔다. 어느 역 근처인지가 궁금해지므로 역을 켠다.
   * - **동네(≤5)**: 특정 골목을 본다. 동 하나가 통째로 화면이라 동 평균은 의미가
   *   없고, 개별 건물 실거래가 답이 된다.
   */
  private applyTier(): void {
    const level = this.map.getLevel();
    // 동 라벨이 보이는 배율에서는 역 점이 라벨과 무게를 다툰다. 라벨이 사라지는
    // 지점부터 역을 켠다.
    const showStations = level <= 6;
    const showComplexes = level <= 5;
    for (const o of this.stationOverlays) o.setMap(showStations ? this.map : null);
    for (const o of this.complexOverlays) o.setMap(showComplexes ? this.map : null);
    this.renderDongs();
  }

  /**
   * 동 라벨.
   *
   * 겹치면 못 읽는다. 화면 밖의 것까지 다 그리면 DOM 이 수백 개로 불어난다.
   * 그래서 **격자로 솎아낸다** — 배율에 비례한 칸을 만들고 칸마다 거래가 가장 많은
   * 동 하나만 남긴다. 거래량 순으로 남기므로 솎여도 대표성이 유지된다.
   */
  renderDongs(): void {
    for (const o of this.dongOverlays) o.setMap(null);
    this.dongOverlays = [];
    const level = this.map.getLevel();
    if (level <= 4 || this.dongFeatures.length === 0) return;

    // 배율과 화면 크기를 따로 추정하지 않는다. **지금 보이는 범위**를 라벨 하나가
    // 차지하는 크기로 나누면 둘이 한 번에 반영된다. 배율 공식은 카카오가 축척을
    // 바꾸면 틀리지만, 이 방식은 화면이 실제로 어떻든 스스로 맞는다.
    const b = this.map.getBounds();
    const sw = b.getSouthWest();
    const ne = b.getNorthEast();
    const spanLon = Math.abs(ne.getLng() - sw.getLng()) || 0.4;
    const spanLat = Math.abs(ne.getLat() - sw.getLat()) || 0.3;
    const w = this.container.clientWidth || 800;
    const h = this.container.clientHeight || 600;
    // 라벨 크기는 화면 폭에 따라 다르다(좁으면 CSS 로 줄인다). 간격도 같이 줄인다.
    const narrow = w < 640;
    const cols = Math.max(2, Math.floor(w / (narrow ? 74 : 96)));
    const rows = Math.max(2, Math.floor(h / (narrow ? 48 : 58)));
    const cellLon = spanLon / cols;
    const cellLat = spanLat / rows;

    // 격자에 담아 칸마다 하나씩 남기면 칸 경계 바로 양옆에 있는 둘은 여전히 붙는다.
    // 거래가 많은 동부터 놓되, 이미 놓은 라벨과 한 칸 이상 떨어졌을 때만 받는다.
    // 최소 간격이 보장되고, 밀도가 높은 곳에서는 대표성이 큰 동이 남는다.
    const MAX_LABELS = this.container.clientWidth < 640 ? 18 : 48;
    const byN = [...this.dongFeatures].sort(
      (a, b) => Number((b.properties as { n: number }).n) - Number((a.properties as { n: number }).n),
    );
    const shown: GeoJSON.Feature[] = [];
    for (const f of byN) {
      if (shown.length >= MAX_LABELS) break;
      const [lon, lat] = (f.geometry as GeoJSON.Point).coordinates;
      if (lon < sw.getLng() || lon > ne.getLng() || lat < sw.getLat() || lat > ne.getLat()) continue;
      let clear = true;
      for (const g of shown) {
        const [gl, ga] = (g.geometry as GeoJSON.Point).coordinates;
        if (Math.abs(gl - lon) < cellLon && Math.abs(ga - lat) < cellLat) {
          clear = false;
          break;
        }
      }
      if (clear) shown.push(f);
    }

    for (const f of shown) {
      const [lon, lat] = (f.geometry as GeoJSON.Point).coordinates;
      const p = f.properties as {
        name: string; price: string; minutes: number; key: string;
      };
      const el = document.createElement("div");
      el.className = "dlabel";
      el.innerHTML =
        '<b>' + p.name + '</b><span>' + p.price + '</span>' +
        '<i>' + p.minutes + '분</i>';
      el.addEventListener("click", (e) => {
        e.stopPropagation();
        this.dongClick?.(p.key);
      });
      const overlay = new this.ns.maps.CustomOverlay({
        position: new this.ns.maps.LatLng(lat, lon),
        content: el,
        xAnchor: 0.5,
        yAnchor: 0.5,
        clickable: true,
        zIndex: 4,
      });
      overlay.setMap(this.map);
      this.dongOverlays.push(overlay);
    }
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
  /** 크기가 생겼다. 다시 그리고, 미뤄둔 맞추기가 있으면 지금 한다. */
  private onResized(): void {
    this.map.relayout();
    const pending = this.pendingFit;
    if (pending && this.container.clientWidth > 0) {
      this.pendingFit = null;
      this.fitBounds(pending.bounds, pending.padding);
    }
  }

  fitBounds(bounds: Bounds, padding: Padding): void {
    // 숨은 탭에서 열리면 컨테이너가 0×0 이라 배율 계산이 무의미하다. 그대로 맞추면
    // 최대로 축소된 채 굳어서, 나중에 탭을 봐도 바다만 보인다. 크기가 생길 때까지 미룬다.
    if (this.container.clientWidth === 0 || this.container.clientHeight === 0) {
      this.pendingFit = { bounds, padding };
      return;
    }
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
