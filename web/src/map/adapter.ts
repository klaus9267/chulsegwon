export interface LngLat {
  lon: number;
  lat: number;
}

export interface Bounds {
  west: number;
  south: number;
  east: number;
  north: number;
}

export interface Padding {
  top: number;
  right: number;
  bottom: number;
  left: number;
}

/** 시간 구간별 색. [분, 색] 오름차순. */
export type Ramp = Array<[number, string]>;

/**
 * 지도 구현을 갈아끼우기 위한 경계.
 *
 * 데이터 쪽에 ReachabilityProvider 를 둔 것과 같은 이유다. 배경지도를 MapLibre 에서
 * 카카오로 바꾼다고 도달권 계산·UI 코드까지 다시 쓸 이유가 없다.
 *
 * 카카오·네이버는 타일 엔드포인트가 아니라 자체 SDK 라 MapLibre 에 꽂을 수 없다.
 * 그래서 "타일 URL만 교체"가 아니라 이 정도 경계가 필요하다.
 */
export interface MapAdapter {
  /** 등시선 구간 폴리곤. `minutes` 속성으로 색을 정한다. */
  setBands(bands: GeoJSON.FeatureCollection, ramp: Ramp, budgetMinutes: number): void;
  /** 도달 역 점. 구현에 따라 생략할 수 있다(카카오는 객체 수가 부담이라 상한을 둔다). */
  setStations(stations: GeoJSON.FeatureCollection): void;
  /** 출발역 마커들. 맞벌이면 둘이다. */
  setOrigins(points: LngLat[]): void;
  /** 지정 범위가 다 보이도록. */
  fitBounds(bounds: Bounds, padding: Padding): void;
  /** 한 점으로 부드럽게 이동. */
  easeTo(at: LngLat, zoom: number): void;
  /** 지금 보고 있는 중심. 외부 서비스로 넘길 때 쓴다. */
  getCenter(): LngLat;
  /** 역을 클릭했을 때. 지도에서 바로 직장역을 바꾸기 위한 것. */
  onStationClick(handler: (stationIndex: number) => void): void;
  /** 배경지도를 실제로 띄웠는지. 실패 시 사용자에게 알린다. */
  readonly basemapOk: boolean;
  /** 지도 종류 표시용. */
  readonly name: string;
}

/** 예산에 비례해 색을 고른다. 예산이 바뀌면 구간 경계도 같이 움직인다. */
export function colorFor(minutes: number, ramp: Ramp, budgetMinutes: number): string {
  if (ramp.length === 0) return "#4a90c4";
  const ratio = budgetMinutes > 0 ? Math.min(minutes / budgetMinutes, 1) : 0;
  const idx = Math.min(Math.round(ratio * (ramp.length - 1)), ramp.length - 1);
  return ramp[idx][1];
}

/** MultiPolygon / Polygon 을 링 배열 목록으로 편다. 첫 링이 외곽, 나머지가 구멍. */
export function toRings(geometry: GeoJSON.Geometry): number[][][][] {
  if (geometry.type === "Polygon") return [geometry.coordinates];
  if (geometry.type === "MultiPolygon") return geometry.coordinates;
  return [];
}
