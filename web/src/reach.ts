import type { StationMeta } from "./types";

/** 보행속도 4.5km/h. 지도상 직선거리 기준이라 실제 도보망보다 낙관적이다. */
const WALK_MPS = 1.25;

/**
 * 남은 시간을 전부 도보로 환산하면 안 된다.
 *
 * 목표 40분에 8분 걸리는 역이면 남은 32분이 생기는데, 그대로 쓰면 반경 2.4km 라는
 * 비현실적인 원이 나온다. 실제로 역에서 걸어서 집을 찾는 범위는 12~15분이 상한이다.
 */
export function walkRadiusMeters(
  remainingMinutes: number,
  capMinutes: number,
): number {
  return Math.max(0, Math.min(remainingMinutes, capMinutes)) * 60 * WALK_MPS;
}

/** 위경도 기준 원을 폴리곤으로. 위도에 따른 경도 축소를 보정한다. */
function circle(lat: number, lon: number, meters: number, steps = 36): number[][] {
  const dLat = meters / 111_320;
  const dLon = meters / (111_320 * Math.cos((lat * Math.PI) / 180));
  const ring: number[][] = [];
  for (let i = 0; i <= steps; i++) {
    const a = (i / steps) * 2 * Math.PI;
    ring.push([lon + dLon * Math.cos(a), lat + dLat * Math.sin(a)]);
  }
  return ring;
}

export interface ReachOptions {
  budgetMinutes: number;
  walkCapMinutes: number;
}

/**
 * 도달 가능한 역마다 원을 만들어 하나의 FeatureCollection 으로.
 *
 * 소요시간 내림차순으로 정렬해서 내보낸다. MapLibre 가 순서대로 그리므로
 * 짧은 시간대가 나중에(위에) 찍혀 색이 올바르게 겹친다 — 별도 union 없이
 * 등시선 밴드처럼 보이게 하는 값싼 방법이다.
 */
export function buildReachGeoJSON(
  stations: StationMeta[],
  within: Array<[number, number]>,
  opts: ReachOptions,
): GeoJSON.FeatureCollection {
  const sorted = [...within].sort((a, b) => b[1] - a[1]);
  return {
    type: "FeatureCollection",
    features: sorted.map(([stationIndex, minutes]) => {
      const st = stations[stationIndex];
      const radius = walkRadiusMeters(opts.budgetMinutes - minutes, opts.walkCapMinutes);
      return {
        type: "Feature",
        geometry: { type: "Polygon", coordinates: [circle(st.lat, st.lon, radius)] },
        properties: { minutes, name: st.name, radius: Math.round(radius) },
      } satisfies GeoJSON.Feature;
    }),
  };
}

/** 도달 역 자체를 점으로. 원이 0에 가까운 역도 보이게 한다. */
export function buildStationGeoJSON(
  stations: StationMeta[],
  within: Array<[number, number]>,
): GeoJSON.FeatureCollection {
  return {
    type: "FeatureCollection",
    features: within.map(([i, minutes]) => ({
      type: "Feature",
      geometry: { type: "Point", coordinates: [stations[i].lon, stations[i].lat] },
      properties: { minutes, name: stations[i].name },
    })),
  };
}
