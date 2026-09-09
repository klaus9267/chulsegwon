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
/**
 * 좌표 → 가장 가까운 동네 색인.
 *
 * **왜 필요한가.** 행렬은 동네까지만 안다. 건물 62,243동에는 동 코드가 없고 좌표뿐이라,
 * 건물의 도달시간을 알려면 어떤 동네의 값을 쓸지 정해야 한다. 예전엔 등시선 필드를
 * 찍었는데 그 필드가 뭉개진 그림이었다([filterDongs] 의 주석을 볼 것).
 *
 * **한계를 분명히 해둔다.** 동네 중심점은 지오코딩된 점 하나고 경계 폴리곤이 없다.
 * 그래서 큰 면(양평·가평은 수십 km²)의 가장자리 건물은 이웃 동네 중심이 더 가까울 수
 * 있다. 그래도 **뭉갠 필드를 찍는 것보다는 낫다** — 적어도 그 값은 어떤 실제 동네의
 * 실제 계산값이다. 건물은 확대했을 때만 보이는 보조 자료라 이 정도로 둔다.
 */
export function buildNearestDong(all: Dong[]): (lat: number, lon: number) => number {
  // 위도 0.02° ≈ 2.2km. 동네 중심 사이 거리가 중앙 1.4km 라 한 칸에 한둘씩 들어간다.
  const CELL = 0.02;
  const grid = new Map<string, number[]>();
  const key = (r: number, c: number) => r + ":" + c;
  for (let i = 0; i < all.length; i++) {
    const k = key(Math.floor(all[i].lat / CELL), Math.floor(all[i].lon / CELL));
    const bucket = grid.get(k);
    if (bucket) bucket.push(i);
    else grid.set(k, [i]);
  }
  return (lat, lon) => {
    const r0 = Math.floor(lat / CELL);
    const c0 = Math.floor(lon / CELL);
    let best = -1;
    let bestD = Infinity;
    // 한 칸 반경으로 시작해 후보가 나올 때까지 넓힌다. 섬이나 외곽에서도 답이 나온다.
    for (let ring = 1; ring <= 8 && best < 0; ring++) {
      for (let dr = -ring; dr <= ring; dr++) {
        for (let dc = -ring; dc <= ring; dc++) {
          const bucket = grid.get(key(r0 + dr, c0 + dc));
          if (!bucket) continue;
          for (const i of bucket) {
            const dy = all[i].lat - lat;
            const dx = (all[i].lon - lon) * 0.79; // cos(37.5°)
            const d = dy * dy + dx * dx;
            if (d < bestD) { bestD = d; best = i; }
          }
        }
      }
    }
    return best;
  };
}

/**
 * 도달권 안 + 조건에 맞는 동네.
 *
 * ⚠️ **[minutes] 는 행렬에서 온 값이어야 한다. 등시선 필드를 찍어 오면 안 된다.**
 *
 * 예전엔 `field: Field` 를 받아 `field.values[row*cols+col]` 을 찍었다. 행렬에는
 * 동네마다 정확한 값이 이미 있는데, 그걸 도보 원으로 부풀려 6회 스무딩한 **그림에서
 * 되읽고** 있었던 것이다. 스무딩이 "도달불가" 표식(예산×1.4)을 이웃으로 끌어다
 * 평균내기 때문에, 이웃과 원이 안 겹치는 동네는 값이 밀려 올라가 사라졌다.
 *
 * 실측(강남 도착 08:00 · 예산 40분 · 도보 15분): 행렬이 예산 안이라고 한 동네
 * **162곳 중 78곳(48%)이 목록에서 사라졌고**, 살아남은 것도 중앙 +4.9분 부풀려졌다.
 * 도보 슬라이더를 5분으로 내리면 76%, 0분이면 100%가 사라졌다 — 슬라이더가
 * 제품 조작인 동시에 보간 커널 반경이었기 때문이다.
 *
 * 그림은 그림대로 두고, **답은 행렬에서 읽는다.** 그래서 필드가 어떻게 생겼든
 * 목록·순위·헤드라인 숫자는 영향을 안 받는다.
 */
export function filterDongs(
  all: Dong[],
  minutes: ReadonlyMap<number, number>,
  f: DongFilter,
): DongPick[] {
  const out: DongPick[] = [];
  for (let i = 0; i < all.length; i++) {
    const d = all[i];
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

    // 행렬 열 색인 = 이 배열의 색인이다. `dongs.json` 과 `manifest.dongs` 가
    // 같은 순서로 만들어진다(DongMatrix 가 dongs.json 을 그대로 읽어 쓴다).
    const m = minutes.get(i);
    if (m === undefined || m > f.budgetMinutes) continue;

    out.push({ d, minutes: m, deposit, monthly, n: s.n });
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
