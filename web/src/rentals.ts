import type { Field } from "./grid";
import type { RoomType, Tenure } from "./dongs";

/**
 * 개별 건물의 실거래.
 *
 * 동 시세가 "이 동네가 얼마"라면 이쪽은 "이 건물이 얼마"다. 둘은 답하는 질문이
 * 다르고, 사용자가 그 질문을 하는 시점도 다르다 — 동네를 고를 때는 동, 그 동네
 * 안을 들여다볼 때는 건물. 그래서 배율로 갈아 끼운다.
 *
 * 62,243동 12MB 라 처음부터 받지 않는다. 확대해서 실제로 필요해질 때 받는다.
 * 동네를 고르다 마는 사람에게 12MB 를 물릴 이유가 없고, 광역 배율에서는 그릴 수도 없다.
 */

/** 한 건물의 한 방 종류. 자리를 아끼려고 키를 줄였다(62,243동 × 3구간). */
export interface RentalRoom {
  /** 거래 건수. */
  n: number;
  /** 보증금 1,000만원 기준 월세(만원). 월세 거래가 없으면 null. */
  m: number | null;
  /** 전세 중위(만원). 전세 거래가 없으면 null. */
  j: number | null;
}

export interface Rental {
  name: string;
  lon: number;
  lat: number;
  /** 최근 6개월 거래 건수(전 구간 합). 1건짜리가 절반이 넘으므로 같이 보여줘야 한다. */
  deals: number;
  buildYear: string;
  /** VILLA(연립·다세대) 또는 OFFI(오피스텔). */
  kind: string;
  /**
   * 방 종류별 시세.
   *
   * 한 건물에 원룸과 쓰리룸이 같이 있는 일이 흔하다(파크하비오는 원룸 월 98,
   * 투룸 월 187). 하나로 뭉뚱그리면 원룸을 찾는 사람에게 두 배 값을 보여준다.
   */
  rooms?: Partial<Record<RoomType, RentalRoom>>;
}

export const KIND_LABEL: Record<string, string> = {
  VILLA: "빌라",
  OFFI: "오피스텔",
};

export interface RentalPick {
  r: Rental;
  minutes: number;
  value: number;
  /** 고른 방 종류의 거래 건수. 정렬과 표시 모두 이 숫자를 쓴다. */
  n: number;
}

/**
 * 도달권 안 + 예산 안 건물.
 *
 * 동과 같은 방식이다 — 폴리곤 내부 판정 대신 스칼라 필드를 찍으면 O(1) 이다.
 * 6만 건을 매 렌더마다 훑어도 사람이 못 느낀다.
 */
export function filterRentals(
  all: Rental[],
  field: Field,
  opts: { room: RoomType; tenure: Tenure; cap: number; budgetMinutes: number },
): RentalPick[] {
  const out: RentalPick[] = [];
  for (const r of all) {
    // 고른 방 종류의 거래가 없는 건물은 답이 아니다. 다른 구간 값을 대신 보여주면
    // "원룸 찾는데 왜 이 값이지"가 된다.
    const bucket = r.rooms?.[opts.room];
    if (!bucket) continue;
    const value = opts.tenure === "JEONSE" ? bucket.j : bucket.m;
    if (value === null || value === undefined) continue;
    if (opts.cap > 0 && value > opts.cap) continue;

    const col = Math.round((r.lon - field.minLon) / field.dLon);
    const row = Math.round((r.lat - field.minLat) / field.dLat);
    if (col < 0 || col >= field.cols || row < 0 || row >= field.rows) continue;
    const minutes = field.values[row * field.cols + col];
    if (minutes > opts.budgetMinutes) continue;

    out.push({ r, minutes, value, n: bucket.n });
  }
  // 거래가 많은 건물이 화면에 남을 우선권을 갖는다. 1건짜리가 절반이 넘어서,
  // 그냥 두면 화면이 우연히 한 번 거래된 집들로 채워진다.
  return out.sort((a, b) => b.n - a.n);
}

export function buildRentalGeoJSON(
  picks: RentalPick[],
  tenure: Tenure,
): GeoJSON.FeatureCollection {
  return {
    type: "FeatureCollection",
    features: picks.map((p) => ({
      type: "Feature",
      geometry: { type: "Point", coordinates: [p.r.lon, p.r.lat] },
      properties: {
        name: p.r.name,
        price: tenure === "JEONSE" ? eok(p.value) : "월 " + p.value,
        kind: KIND_LABEL[p.r.kind] ?? p.r.kind,
        deals: p.n,
        minutes: Math.round(p.minutes),
        n: p.n,
      },
    })),
  };
}

function eok(manwon: number): string {
  if (manwon >= 10000) return (manwon / 10000).toFixed(1).replace(/\.0$/, "") + "억";
  return manwon.toLocaleString() + "만";
}

let cache: Rental[] | null = null;
let inflight: Promise<Rental[]> | null = null;

/** 한 번만 받고 그다음부터는 메모리에서. 확대·축소를 오갈 때마다 다시 받으면 안 된다. */
export function loadRentals(baseUrl: string): Promise<Rental[]> {
  if (cache) return Promise.resolve(cache);
  if (inflight) return inflight;
  inflight = fetch(baseUrl + "rentals.json", {
    headers: { "ngrok-skip-browser-warning": "1" },
  })
    .then((res) => (res.ok ? res.json() : { complexes: [] }))
    .then((json: { complexes: Rental[] }) => {
      cache = json.complexes ?? [];
      return cache;
    })
    .catch(() => {
      // 건물 정보가 없어도 동 시세와 도달권은 그대로 동작해야 한다.
      cache = [];
      return cache;
    });
  return inflight;
}
