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
  /**
   * 월별 월세(보증금 1,000만원 기준). `months` 와 같은 순서, 거래 없는 달은 null.
   *
   * "지금 얼마"만으로는 오르는 중인지 내리는 중인지 모른다. 계약을 앞둔 사람에게
   * 그 방향은 금액만큼 중요하다.
   */
  trend?: Array<number | null>;
}

export interface Dong {
  name: string;
  gu: string;
  lon: number;
  lat: number;
  deals: number;
  rooms: Partial<Record<RoomType, RoomStat>>;
  /** 해발(m). SRTM 3×3 최솟값으로 지면을 추정한 값. */
  elev?: number;
  /**
   * 가장 가까운 역과의 고도차(m). 양수면 역에서 집이 오르막이다.
   *
   * 자취에서 언덕은 월세 몇 만원보다 크게 체감된다. 짐 들고, 장 보고, 매일 걸어
   * 올라가는 사람에게 "역에서 +40m"는 집을 고르는 기준이 되는데 지도만 봐서는
   * 절대 알 수 없다 — 평면에는 높이가 없다.
   */
  climb?: number;
  /** 그 역 이름. */
  station?: string;
  /** 그 역까지 직선거리(m). */
  stationM?: number;
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
  /** 켜면 오르막이 심한 동네를 뺀다. */
  flatOnly?: boolean;
}

/** "평지"의 경계(m). 이 정도까지는 걸어서 부담이 아니다. */
export const FLAT_CLIMB_M = 10;

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
    // 고도를 모르는 동네는 조건을 걸었을 때 통과시키지 않는다. 편의시설과 같은 이유로,
    // 모르는 걸 맞다고 말하면 사용자가 헛걸음을 한다.
    if (f.flatOnly && (d.climb === undefined || d.climb > FLAT_CLIMB_M)) continue;

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

/** 추이의 가로축. `202602` 같은 형식이고 오래된 것부터다. */
export let TREND_MONTHS: string[] = [];

export async function loadDongs(baseUrl: string): Promise<Dong[]> {
  try {
    const res = await fetch(baseUrl + "dongs.json", {
      headers: { "ngrok-skip-browser-warning": "1" },
    });
    if (!res.ok) return [];
    const json = (await res.json()) as { dongs: Dong[]; months?: string[] };
    TREND_MONTHS = json.months ?? [];
    return json.dongs ?? [];
  } catch {
    // 시세가 없어도 도달권은 동작해야 한다. 부가 정보지 전제가 아니다.
    return [];
  }
}
