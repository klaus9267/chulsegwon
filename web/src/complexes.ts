import type { Field } from "./grid";

export interface ComplexMeta {
  name: string;
  lon: number;
  lat: number;
  /** 매매 중위값 (만원). 거래가 없으면 null. */
  sale: number | null;
  /** 전세 중위값 (만원). 거래가 없으면 null. */
  jeonse: number | null;
  deals: number;
  buildYear: string;
}

export interface ComplexFilter {
  /** 전세 상한 (만원). 0 이면 제한 없음. */
  maxJeonseManwon: number;
  budgetMinutes: number;
}

/**
 * 도달권 안에 있으면서 예산에 맞는 단지를 고른다.
 *
 * 도달 여부를 폴리곤 안에 있는지(point-in-polygon)로 따지지 않는다. 등시선을 뽑기
 * 전의 **스칼라 필드**를 그대로 찍어보면 되기 때문이다. 단지 좌표 -> 격자 칸 인덱스는
 * 산술 두 번이라 O(1) 이고, 단지가 만 개여도 폴리곤 검사보다 비교가 안 되게 싸다.
 * 등시선을 격자에서 뽑기로 한 결정이 여기서 한 번 더 값을 한다.
 */
export function filterComplexes(
  all: ComplexMeta[],
  field: Field,
  filter: ComplexFilter,
): Array<{ c: ComplexMeta; minutes: number }> {
  const out: Array<{ c: ComplexMeta; minutes: number }> = [];
  for (const c of all) {
    if (filter.maxJeonseManwon > 0) {
      // 전세 기록이 없는 단지는 예산으로 거를 수 없다. 사용자가 예산을 건 이상
      // "얼마인지 모르는 곳"을 섞어 보여주면 결과를 믿을 수 없게 된다.
      if (c.jeonse === null || c.jeonse > filter.maxJeonseManwon) continue;
    }
    const col = Math.round((c.lon - field.minLon) / field.dLon);
    const row = Math.round((c.lat - field.minLat) / field.dLat);
    if (col < 0 || col >= field.cols || row < 0 || row >= field.rows) continue;
    const minutes = field.values[row * field.cols + col];
    if (minutes > filter.budgetMinutes) continue;
    out.push({ c, minutes });
  }
  return out;
}

export function buildComplexGeoJSON(
  picked: Array<{ c: ComplexMeta; minutes: number }>,
): GeoJSON.FeatureCollection {
  return {
    type: "FeatureCollection",
    features: picked.map(({ c, minutes }) => ({
      type: "Feature",
      geometry: { type: "Point", coordinates: [c.lon, c.lat] },
      properties: {
        name: c.name,
        minutes: Math.round(minutes),
        jeonse: c.jeonse,
        sale: c.sale,
        buildYear: c.buildYear,
      },
    })),
  };
}

/** 만원 -> "4.2억" 처럼 읽기 쉬운 표기. */
export function formatManwon(v: number | null): string {
  if (v === null) return "-";
  if (v >= 10000) return (v / 10000).toFixed(1).replace(/\.0$/, "") + "억";
  return v.toLocaleString() + "만";
}

export async function loadComplexes(baseUrl: string): Promise<ComplexMeta[]> {
  try {
    const res = await fetch(baseUrl + "complexes.json", {
      headers: { "ngrok-skip-browser-warning": "1" },
    });
    if (!res.ok) return [];
    const json = (await res.json()) as { complexes: ComplexMeta[] };
    return json.complexes ?? [];
  } catch {
    // 단지 데이터가 없어도 도달권은 동작해야 한다. 부가 기능이지 전제가 아니다.
    return [];
  }
}
