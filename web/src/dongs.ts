import type { Field } from "./grid";

/** 전용면적으로 나눈 방 종류. 실거래가에 방 개수가 없어 면적이 유일한 단서다. */
export type RoomType = "ONE" | "TWO" | "THREE";
/** 전세냐 월세냐. 자취는 월세가 실제 기준이라 기본값이 월세다. */
export type Tenure = "WOLSE" | "JEONSE";

export interface RoomStat {
  /** 거래 건수. 적으면 중위값을 믿을 수 없다. */
  n: number;
  jeonse: number | null;
  deposit: number | null;
  monthly: number | null;
}

export interface Dong {
  name: string;
  gu: string;
  lon: number;
  lat: number;
  deals: number;
  rooms: Partial<Record<RoomType, RoomStat>>;
}

export const ROOM_LABEL: Record<RoomType, string> = {
  ONE: "원룸",
  TWO: "투룸",
  THREE: "쓰리룸+",
};

/**
 * 중위값을 쓰려면 표본이 있어야 한다.
 *
 * 거래 두 건짜리 동의 "중위 월세"는 그 두 집이 얼마였는지일 뿐이다. 지도에 숫자로
 * 찍히는 순간 사용자는 그걸 시세로 읽으므로, 못 믿을 값은 아예 안 보여주는 편이 낫다.
 *
 * 처음엔 5로 뒀는데 8건짜리 투룸이 "월 120만"으로 나왔다. 6개월치를 모았을 때 10건은
 * 최소한의 선이다. 그 아래는 "거래 적음"이라고 말하는 게 정직하다.
 */
const MIN_SAMPLES = 10;

export interface DongPick {
  d: Dong;
  minutes: number;
  /** 표시할 값. 월세면 보증금/월세, 전세면 보증금. */
  deposit: number;
  monthly: number;
  n: number;
}

export interface DongFilter {
  room: RoomType;
  tenure: Tenure;
  budgetMinutes: number;
  /** 상한 (월세는 만원/월, 전세는 만원). 0 이면 제한 없음. */
  cap: number;
}

/**
 * 도달권 안에 있으면서 예산에 맞는 동을 고른다.
 *
 * 단지와 같은 이유로 폴리곤 내부 판정을 하지 않는다. 등시선을 뽑기 전의 스칼라
 * 필드를 그대로 찍으면 O(1) 이다.
 */
export function filterDongs(all: Dong[], field: Field, f: DongFilter): DongPick[] {
  const out: DongPick[] = [];
  for (const d of all) {
    const s = d.rooms[f.room];
    if (!s || s.n < MIN_SAMPLES) continue;

    let deposit: number | null;
    let monthly: number;
    if (f.tenure === "JEONSE") {
      deposit = s.jeonse;
      monthly = 0;
    } else {
      deposit = s.deposit;
      monthly = s.monthly ?? 0;
      if (monthly <= 0) continue;
    }
    if (deposit === null) continue;
    if (f.cap > 0) {
      const value = f.tenure === "JEONSE" ? deposit : monthly;
      if (value > f.cap) continue;
    }

    const col = Math.round((d.lon - field.minLon) / field.dLon);
    const row = Math.round((d.lat - field.minLat) / field.dLat);
    if (col < 0 || col >= field.cols || row < 0 || row >= field.rows) continue;
    const minutes = field.values[row * field.cols + col];
    if (minutes > f.budgetMinutes) continue;

    out.push({ d, minutes, deposit, monthly, n: s.n });
  }
  // 거래가 많은 동이 위에 오도록. 화면에 다 못 그릴 때 먼저 살아남아야 하는 쪽이다.
  return out.sort((a, b) => b.n - a.n);
}

/**
 * 지도 라벨에 쓸 짧은 표기.
 *
 * 월세는 모든 동네가 보증금 1,000만원 기준으로 통일돼 있다. 그러면 "1000/78" 의
 * 앞 절반은 어느 라벨에나 똑같이 붙는 글자라 자리만 먹는다. 기준은 화면 어딘가에서
 * 한 번 밝히고, 라벨에는 실제로 다른 값만 남긴다. 라벨이 짧아지면 같은 화면에
 * 동네가 더 들어간다.
 */
export function priceLabel(p: DongPick, tenure: Tenure): string {
  if (tenure === "JEONSE") return eok(p.deposit);
  return "월 " + p.monthly;
}

/** 카드처럼 자리가 있는 곳에서는 보증금까지 밝힌다. */
export function priceFull(p: DongPick, tenure: Tenure): string {
  if (tenure === "JEONSE") return eok(p.deposit);
  return p.deposit.toLocaleString() + "/" + p.monthly;
}

function eok(manwon: number): string {
  if (manwon >= 10000) return (manwon / 10000).toFixed(1).replace(/\.0$/, "") + "억";
  return manwon.toLocaleString() + "만";
}

/** 보증금은 자리를 많이 먹는다. 라벨 안에서는 천 단위로 줄인다. */
function short(manwon: number): string {
  if (manwon >= 10000) return (manwon / 10000).toFixed(1).replace(/\.0$/, "") + "억";
  return String(manwon);
}

export function buildDongGeoJSON(picks: DongPick[], tenure: Tenure): GeoJSON.FeatureCollection {
  return {
    type: "FeatureCollection",
    features: picks.map((p) => ({
      type: "Feature",
      geometry: { type: "Point", coordinates: [p.d.lon, p.d.lat] },
      properties: {
        key: dongKey(p.d),
        name: p.d.name,
        gu: p.d.gu,
        price: priceLabel(p, tenure),
        minutes: Math.round(p.minutes),
        n: p.n,
      },
    })),
  };
}

/** 같은 동 이름이 여러 구에 있다(중앙동·신흥동 등). 구까지 붙여야 유일하다. */
export function dongKey(d: Dong): string {
  return d.gu + "|" + d.name;
}

export async function loadDongs(baseUrl: string): Promise<Dong[]> {
  try {
    const res = await fetch(baseUrl + "dongs.json", {
      headers: { "ngrok-skip-browser-warning": "1" },
    });
    if (!res.ok) return [];
    const json = (await res.json()) as { dongs: Dong[] };
    return json.dongs ?? [];
  } catch {
    // 시세가 없어도 도달권은 동작해야 한다. 부가 정보지 전제가 아니다.
    return [];
  }
}
