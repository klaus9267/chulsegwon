import type { StationMeta } from "./types";

/** 보행속도 4.5km/h. 직선거리 기준이라 실제 도보망보다 낙관적이다. */
const WALK_MPS = 1.25;

/**
 * 도달 못 하는 칸의 값 — **예산에 비례해서** 잡는다.
 *
 * 처음엔 9999 로 채웠는데, 그러면 등시선이 망가진다. turf 는 격자점 사이를 선형
 * 보간해서 경계를 찾는데, 38분 칸과 9999 칸 사이에서 40분 지점을 찾으면 거의
 * 38분 칸에 붙어버린다. 결과적으로 경계가 칸 모양을 따라 각지고, 역마다 원이
 * 따로 노는 것처럼 보인다.
 *
 * 예산의 1.4배로 두면 38분과 56분 사이에서 40분을 찾으므로 보간이 자연스럽게
 * 퍼지고, 인접한 역의 원들이 매끄럽게 이어진다.
 */
export function unreachableValue(budgetMinutes: number): number {
  return budgetMinutes * 1.4;
}

export interface FieldOptions {
  budgetMinutes: number;
  walkCapMinutes: number;
  /** 목표 칸 크기(m). 칸 수가 너무 많아지면 자동으로 키운다. */
  cellMeters: number;
  /** 이 칸 수를 넘지 않게 해상도를 낮춘다. 등시선 추출이 칸 수에 비례해 느려진다. */
  maxCells?: number;
  /** 필드를 부드럽게 만드는 횟수. 역마다 생기는 동심원 자국을 없앤다. */
  smoothPasses?: number;
}

/**
 * 각 격자점까지의 도달시간을 담은 스칼라 필드.
 *
 * 등시선은 이 필드에서 뽑는다. 격자를 그대로 그리면 칸이 사각형으로 보이고,
 * 도보 반경이 칸보다 작으면(도보 4분=300m < 칸 400m) 역마다 사각형 하나씩
 * 찍혀서 블록처럼 흩어진다.
 */
export interface Field {
  /** rows*cols 개의 도달시간(분). 도달 불가는 [unreachableValue]. */
  values: Float32Array;
  /** 이 값 이상은 도달 불가로 본다. */
  unreachable: number;
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

  const unreachable = unreachableValue(opts.budgetMinutes);
  const values = new Float32Array(rows * cols).fill(unreachable);

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

  // 6회가 균형점이다. 조각 199→40개로 이어지고 면적 손실은 4%(282→270km²),
  // 등고선 추출도 오히려 빨라진다(149→79ms). 더 돌리면 도달권이 눈에 띄게 깎인다.
  smooth(values, rows, cols, opts.smoothPasses ?? 6);

  return { values, cols, rows, minLon, minLat, dLon, dLat, cellMeters, unreachable };
}

/**
 * 3x3 평균으로 필드를 완만하게 만든다.
 *
 * 직선거리 원으로 도보를 근사하다 보니 역마다 원이 하나씩 생기고, 그 원들이
 * 동심원 자국으로 드러난다. 실제 도보 경계는 그렇게 딱 떨어지지 않으므로
 * 살짝 뭉개는 편이 오히려 사실에 가깝다. 인접한 역의 원들도 이 과정에서 이어진다.
 */
function smooth(values: Float32Array, rows: number, cols: number, passes: number) {
  if (passes <= 0) return;
  const buf = new Float32Array(values.length);
  for (let p = 0; p < passes; p++) {
    buf.set(values);
    for (let r = 1; r < rows - 1; r++) {
      for (let c = 1; c < cols - 1; c++) {
        const i = r * cols + c;
        values[i] =
          (buf[i] * 4 +
            buf[i - 1] + buf[i + 1] + buf[i - cols] + buf[i + cols] +
            (buf[i - cols - 1] + buf[i - cols + 1] + buf[i + cols - 1] + buf[i + cols + 1]) * 0.5) /
          10;
      }
    }
  }
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
