import type { StationMeta } from "./types";

/** 보행속도 4.5km/h. 직선거리 기준이라 실제 도보망보다 낙관적이다. */
const WALK_MPS = 1.25;

/**
 * 도달 못 하는 칸의 값. 등시선을 뽑을 때 어떤 구간에도 안 걸리도록 크게 잡는다.
 * 행렬 파일의 sentinel(255)과는 다른 값이라 이름을 구분한다.
 */
export const FIELD_UNREACHABLE = 9999;

export interface FieldOptions {
  budgetMinutes: number;
  walkCapMinutes: number;
  /** 목표 칸 크기(m). 칸 수가 너무 많아지면 자동으로 키운다. */
  cellMeters: number;
  /** 이 칸 수를 넘지 않게 해상도를 낮춘다. 등시선 추출이 칸 수에 비례해 느려진다. */
  maxCells?: number;
}

/**
 * 각 격자점까지의 도달시간을 담은 스칼라 필드.
 *
 * 등시선은 이 필드에서 뽑는다. 격자를 그대로 그리면 칸이 사각형으로 보이고,
 * 도보 반경이 칸보다 작으면(도보 4분=300m < 칸 400m) 역마다 사각형 하나씩
 * 찍혀서 블록처럼 흩어진다.
 */
export interface Field {
  /** rows*cols 개의 도달시간(분). 도달 불가는 [FIELD_UNREACHABLE]. */
  values: Float32Array;
  cols: number;
  rows: number;
  minLon: number;
  minLat: number;
  dLon: number;
  dLat: number;
  /** 실제로 쓰인 칸 크기(m). maxCells 때문에 요청값과 다를 수 있다. */
  cellMeters: number;
}

/**
 * 역에서 바깥으로 칠하는 scatter-min.
 *
 * 칸마다 최솟값 하나만 남으므로 어떤 칸도 두 번 계산되지 않는다. 도보 상한이
 * 15분이면 역 하나가 덮는 칸이 수십 개뿐이라, 역 300개라도 만 번 남짓이면 끝난다.
 */
export function buildField(
  stations: StationMeta[],
  within: Array<[number, number]>,
  opts: FieldOptions,
): Field | null {
  if (within.length === 0) return null;

  const maxCells = opts.maxCells ?? 160_000;
  const padMeters = Math.max(opts.walkCapMinutes, 1) * 60 * WALK_MPS;

  const lats = within.map(([i]) => stations[i].lat);
  const lons = within.map(([i]) => stations[i].lon);
  const midLat = (Math.min(...lats) + Math.max(...lats)) / 2;

  const mPerLat = 111_320;
  const mPerLon = 111_320 * Math.cos((midLat * Math.PI) / 180);

  const minLat = Math.min(...lats) - padMeters / mPerLat;
  const maxLat = Math.max(...lats) + padMeters / mPerLat;
  const minLon = Math.min(...lons) - padMeters / mPerLon;
  const maxLon = Math.max(...lons) + padMeters / mPerLon;

  // 요청한 해상도로 칸이 너무 많아지면 키운다. 등시선 추출 비용이 칸 수에 비례한다.
  let cellMeters = opts.cellMeters;
  for (let guard = 0; guard < 8; guard++) {
    const c = Math.ceil(((maxLon - minLon) * mPerLon) / cellMeters);
    const r = Math.ceil(((maxLat - minLat) * mPerLat) / cellMeters);
    if (c * r <= maxCells) break;
    cellMeters *= 1.5;
  }

  const dLat = cellMeters / mPerLat;
  const dLon = cellMeters / mPerLon;
  const cols = Math.ceil((maxLon - minLon) / dLon) + 1;
  const rows = Math.ceil((maxLat - minLat) / dLat) + 1;

  const values = new Float32Array(rows * cols).fill(FIELD_UNREACHABLE);

  for (const [stationIndex, minutes] of within) {
    const st = stations[stationIndex];
    const allowance = Math.min(opts.budgetMinutes - minutes, opts.walkCapMinutes);
    if (allowance < 0) continue;
    const radius = allowance * 60 * WALK_MPS;

    const cr = Math.ceil(radius / cellMeters) + 1;
    const r0 = Math.round((st.lat - minLat) / dLat);
    const c0 = Math.round((st.lon - minLon) / dLon);

    for (let dr = -cr; dr <= cr; dr++) {
      const r = r0 + dr;
      if (r < 0 || r >= rows) continue;
      const dy = (minLat + r * dLat - st.lat) * mPerLat;

      for (let dc = -cr; dc <= cr; dc++) {
        const c = c0 + dc;
        if (c < 0 || c >= cols) continue;
        const dx = (minLon + c * dLon - st.lon) * mPerLon;

        const dist = Math.hypot(dx, dy);
        if (dist > radius) continue;

        const total = minutes + dist / WALK_MPS / 60;
        if (total > opts.budgetMinutes) continue;

        const idx = r * cols + c;
        if (total < values[idx]) values[idx] = total;
      }
    }
  }

  return { values, cols, rows, minLon, minLat, dLon, dLat, cellMeters };
}

/** 도달 역 자체를 점으로. 등시선만으로는 어디가 역인지 안 보인다. */
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
