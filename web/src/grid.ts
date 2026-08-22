import type { StationMeta } from "./types";

/** 보행속도 4.5km/h. 직선거리 기준이라 실제 도보망보다 낙관적이다. */
const WALK_MPS = 1.25;
const UNSET = 255;

export interface GridOptions {
  budgetMinutes: number;
  walkCapMinutes: number;
  /** 격자 한 칸의 크기(m). 작을수록 곱지만 셀 수가 제곱으로 는다. */
  cellMeters: number;
}

/**
 * 역마다 원을 그려 겹치는 방식은 색이 무너진다.
 *
 * fill-opacity 가 1 미만이면 겹친 곳이 계속 진해져서, 색이 "소요시간"이 아니라
 * "원이 몇 개 겹쳤나"를 나타내게 된다. 그렇다고 opacity 를 1 로 올리면 배경지도가
 * 가려진다.
 *
 * 격자로 바꾸면 이 문제가 사라진다. 칸마다 최솟값 하나만 남기므로 어떤 칸도 두 번
 * 그려지지 않는다. 반투명을 써도 겹침이 없고, 색이 소요시간과 정확히 대응한다.
 *
 * 계산은 역에서 바깥으로 칠하는 scatter-min 이라 싸다. 도보 상한이 15분이면
 * 역 하나가 덮는 칸이 수십 개뿐이라, 역 300개라도 만 번 남짓이면 끝난다.
 */
export function buildGridGeoJSON(
  stations: StationMeta[],
  within: Array<[number, number]>,
  opts: GridOptions,
): GeoJSON.FeatureCollection {
  const empty: GeoJSON.FeatureCollection = { type: "FeatureCollection", features: [] };
  if (within.length === 0) return empty;

  const padMeters = opts.walkCapMinutes * 60 * WALK_MPS;
  const lats = within.map(([i]) => stations[i].lat);
  const lons = within.map(([i]) => stations[i].lon);
  const midLat = (Math.min(...lats) + Math.max(...lats)) / 2;

  const mPerLat = 111_320;
  const mPerLon = 111_320 * Math.cos((midLat * Math.PI) / 180);

  const padLat = padMeters / mPerLat;
  const padLon = padMeters / mPerLon;
  const minLat = Math.min(...lats) - padLat;
  const maxLat = Math.max(...lats) + padLat;
  const minLon = Math.min(...lons) - padLon;
  const maxLon = Math.max(...lons) + padLon;

  const dLat = opts.cellMeters / mPerLat;
  const dLon = opts.cellMeters / mPerLon;
  const rows = Math.ceil((maxLat - minLat) / dLat);
  const cols = Math.ceil((maxLon - minLon) / dLon);
  if (rows <= 0 || cols <= 0 || rows * cols > 4_000_000) return empty;

  const best = new Uint8Array(rows * cols).fill(UNSET);

  for (const [stationIndex, minutes] of within) {
    const st = stations[stationIndex];
    const allowance = Math.min(opts.budgetMinutes - minutes, opts.walkCapMinutes);
    if (allowance < 0) continue;
    const radius = allowance * 60 * WALK_MPS;

    const cr = Math.ceil(radius / opts.cellMeters);
    const r0 = Math.round((st.lat - minLat) / dLat);
    const c0 = Math.round((st.lon - minLon) / dLon);

    for (let dr = -cr; dr <= cr; dr++) {
      const r = r0 + dr;
      if (r < 0 || r >= rows) continue;
      const cellLat = minLat + (r + 0.5) * dLat;
      const dy = (cellLat - st.lat) * mPerLat;

      for (let dc = -cr; dc <= cr; dc++) {
        const c = c0 + dc;
        if (c < 0 || c >= cols) continue;
        const cellLon = minLon + (c + 0.5) * dLon;
        const dx = (cellLon - st.lon) * mPerLon;

        const dist = Math.hypot(dx, dy);
        if (dist > radius) continue;

        const total = minutes + dist / WALK_MPS / 60;
        if (total > opts.budgetMinutes) continue;

        const v = Math.round(total);
        const idx = r * cols + c;
        if (v < best[idx]) best[idx] = v;
      }
    }
  }

  const features: GeoJSON.Feature[] = [];
  for (let r = 0; r < rows; r++) {
    for (let c = 0; c < cols; c++) {
      const v = best[r * cols + c];
      if (v === UNSET) continue;
      const la = minLat + r * dLat;
      const lo = minLon + c * dLon;
      features.push({
        type: "Feature",
        properties: { minutes: v },
        geometry: {
          type: "Polygon",
          coordinates: [[
            [lo, la], [lo + dLon, la], [lo + dLon, la + dLat], [lo, la + dLat], [lo, la],
          ]],
        },
      });
    }
  }
  return { type: "FeatureCollection", features };
}

/** 도달 역 자체를 점으로. 격자만으로는 어디가 역인지 안 보인다. */
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
