import isobands from "@turf/isobands";
import type { Field } from "./grid";
import { FIELD_UNREACHABLE } from "./grid";

/**
 * 스칼라 필드에서 등시선(구간별 폴리곤)을 뽑는다.
 *
 * 격자를 칸 단위로 그리면 두 가지가 거슬린다. 칸이 사각형으로 드러나고,
 * 도보 반경이 칸보다 작을 때(도보 4분 = 반경 300m < 칸 400m) 역마다 사각형
 * 하나씩 찍혀 블록처럼 흩어진다. 등시선은 격자점 사이를 보간하므로 둘 다 사라진다.
 *
 * 직접 marching squares 를 짜지 않고 @turf/isobands 를 쓰는 이유는 **구멍** 때문이다.
 * 20분 구간은 10분 구간을 도넛처럼 감싸야 하는데, 이걸 안 하고 겹쳐 그리면
 * 반투명이 겹쳐 색이 다시 무너진다. 안쪽 링을 바깥 링에 올바로 배정하는 게
 * 이 알고리즘의 까다로운 부분이고, turf 가 그걸 해준다.
 */
export function buildIsobandsGeoJSON(field: Field, breaks: number[]): GeoJSON.FeatureCollection {
  const empty: GeoJSON.FeatureCollection = { type: "FeatureCollection", features: [] };
  if (breaks.length < 2) return empty;

  // turf 는 점 격자를 받는다. 필드를 그대로 넘길 수 없어 여기서 한 번 변환한다.
  const points: GeoJSON.Feature<GeoJSON.Point>[] = new Array(field.rows * field.cols);
  let n = 0;
  for (let r = 0; r < field.rows; r++) {
    const lat = field.minLat + r * field.dLat;
    for (let c = 0; c < field.cols; c++) {
      points[n++] = {
        type: "Feature",
        properties: { m: field.values[r * field.cols + c] },
        geometry: { type: "Point", coordinates: [field.minLon + c * field.dLon, lat] },
      };
    }
  }

  try {
    const out = isobands(
      { type: "FeatureCollection", features: points },
      breaks,
      { zProperty: "m" },
    ) as unknown as GeoJSON.FeatureCollection;

    // turf 는 구간을 "10-20" 같은 문자열로 붙인다. 색을 칠하려면 숫자가 필요하다.
    for (const f of out.features) {
      const label = String((f.properties as Record<string, unknown>)?.m ?? "");
      const lower = Number.parseFloat(label.split("-")[0]);
      f.properties = {
        ...(f.properties as Record<string, unknown>),
        minutes: Number.isFinite(lower) ? lower : 0,
      };
    }
    // 도달 불가 구간이 섞여 들어오면 지도 전체가 덮인다.
    out.features = out.features.filter(
      (f) => (f.properties as { minutes: number }).minutes < FIELD_UNREACHABLE,
    );
    return out;
  } catch (e) {
    console.warn("등시선 추출 실패:", e);
    return empty;
  }
}

/**
 * 예산에 맞춰 구간 경계를 만든다.
 *
 * 예산이 25분인데 경계가 60분까지 있으면 대부분의 구간이 비어 색이 단조로워진다.
 * 예산을 균등 분할해야 어느 예산에서도 밴드가 고르게 나온다.
 */
export function breaksFor(budgetMinutes: number, bandCount = 5): number[] {
  const step = budgetMinutes / bandCount;
  const out: number[] = [];
  for (let i = 0; i <= bandCount; i++) out.push(Math.round(i * step * 10) / 10);
  // 마지막 경계는 예산보다 아주 조금 크게 둔다. 경계값과 정확히 같은 칸이 빠지지 않도록.
  out[out.length - 1] = budgetMinutes + 0.01;
  return out;
}
