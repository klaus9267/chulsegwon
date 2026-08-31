/**
 * 동네별 편의시설 개수 (반경 800m).
 *
 * 다른 서비스는 이걸 지도 위 아이콘으로 보여준다. 아이콘은 켜고 끄는 것이라
 * "편의점이 없는 동네를 지워달라"는 요구에는 답하지 못한다. 우리는 **필터**로 쓴다.
 */
export type AmenityMap = Record<string, Record<string, number>>;

export interface AmenityDef {
  code: string;
  label: string;
  /**
   * 있고 없고가 갈리는 것(마트·지하철역), 많고 적고가 갈리는 것(편의점·병원),
   * 그리고 **거리**로 따져야 하는 것(백화점)이 각각 다르다.
   *
   * 백화점은 개수가 의미 없다. 이름에 그 글자가 들어간 상점까지 세어져서 강남역
   * 3km 안에 879개가 나온다. 대신 가장 가까운 하나까지의 거리를 쓴다 — 사용자가
   * 실제로 궁금한 것도 "몇 개"가 아니라 "걸어갈 만한가"다.
   */
  mode: "presence" | "density" | "distance";
}

export const AMENITIES: AmenityDef[] = [
  { code: "SW8", label: "지하철역", mode: "presence" },
  { code: "CS2", label: "편의점", mode: "density" },
  { code: "MT1", label: "대형마트", mode: "presence" },
  { code: "HP8", label: "병원", mode: "density" },
  { code: "FD6", label: "음식점", mode: "density" },
  { code: "DEPT", label: "백화점", mode: "distance" },
];



/**
 * 기준값을 코드에 박지 않는다.
 *
 * "편의점 15개 이상"이 많은 건지는 다른 동네와 비교해야 안다. 데이터가 바뀌거나
 * 반경을 조정하면 박아둔 숫자는 곧 거짓말이 된다. 분포의 상위 40% 지점을 쓰면
 * "이 조건을 켜면 후보가 대략 절반으로 준다"가 항상 성립한다.
 *
 * 비교 대상은 **후보가 될 수 있는 동네**여야 한다. 수도권 1,768개 동에는 연천·가평처럼
 * 원룸 시장 자체가 없는 곳이 절반 가까이 섞여 있고, 그걸 포함해 중위값을 내면 "편의점
 * 5곳 이상"처럼 서울에서는 아무것도 거르지 못하는 기준이 나온다. `eligible` 로 모집단을
 * 좁힌다.
 */
export function thresholds(
  data: AmenityMap,
  eligible?: Set<string>,
): Record<string, number> {
  const out: Record<string, number> = {};
  for (const a of AMENITIES) {
    if (a.mode === "presence") {
      out[a.code] = 1;
      continue;
    }
    const v = Object.entries(data)
      .filter(([k]) => !eligible || eligible.has(k))
      .map(([, r]) => r[a.code])
      .filter((n): n is number => typeof n === "number")
      .sort((x, y) => x - y);
    if (v.length === 0) {
      out[a.code] = 1;
      continue;
    }
    if (a.mode === "distance") {
      // 거리는 작을수록 좋으므로 아래쪽 40% 지점을 쓴다. 다른 조건과 같은 약속이
      // 성립한다 — 이 조건을 켜면 후보가 대략 절반으로 준다.
      // 500m 단위로 반올림하는 건 "2.5km 이내"가 "2,806m 이내"보다 읽히기 때문이다.
      const raw = v[Math.floor(v.length * 0.4)];
      out[a.code] = Math.max(500, Math.round(raw / 500) * 500);
      continue;
    }
    out[a.code] = v[Math.floor(v.length * 0.6)];
  }
  return out;
}

export function passes(
  counts: Record<string, number> | undefined,
  selected: Set<string>,
  th: Record<string, number>,
): boolean {
  if (selected.size === 0) return true;
  // 데이터가 없는 동네는 조건을 걸었을 때 통과시키지 않는다. 모르는 걸 맞다고
  // 말하면 사용자가 헛걸음을 한다.
  if (!counts) return false;
  for (const code of selected) {
    const def = AMENITIES.find((a) => a.code === code);
    const v = counts[code];
    if (v === undefined) return false;
    // 거리는 작을수록 좋다. 나머지는 클수록 좋다. 부등호를 뒤집지 않으면
    // "백화점 가까운 곳"이 정확히 반대로 걸린다.
    if (def?.mode === "distance") {
      if (v > (th[code] ?? 2500)) return false;
    } else if (v < (th[code] ?? 1)) return false;
  }
  return true;
}

export async function loadAmenities(baseUrl: string): Promise<AmenityMap> {
  try {
    const res = await fetch(baseUrl + "amenities.json", {
      headers: { "ngrok-skip-browser-warning": "1" },
    });
    if (!res.ok) return {};
    return (await res.json()) as AmenityMap;
  } catch {
    return {};
  }
}

/**
 * 편의시설을 하나의 순위로 접는다.
 *
 * 개수를 그냥 더하면 음식점(수백)이 마트(한둘)를 완전히 덮는다. 항목마다 **분위**로
 * 바꾼 다음 평균을 낸다. 그러면 "다른 동네들과 비교해 전반적으로 얼마나 갖춰졌나"가
 * 되고, 항목 간 자릿수 차이에 휘둘리지 않는다.
 */
export function amenityRanks(data: AmenityMap): Map<string, number> {
  const keys = Object.keys(data);
  const acc = new Map<string, number>(keys.map((k) => [k, 0]));
  for (const a of AMENITIES) {
    // 거리는 가까울수록 좋으므로 순위를 뒤집는다.
    const sign = a.mode === "distance" ? -1 : 1;
    const miss = a.mode === "distance" ? 1e9 : 0;
    const sorted = keys
      .map((k) => [k, (data[k][a.code] ?? miss) * sign] as const)
      .sort((x, y) => x[1] - y[1]);
    sorted.forEach(([k], i) => {
      acc.set(k, (acc.get(k) ?? 0) + i / Math.max(1, sorted.length - 1));
    });
  }
  for (const [k, v] of acc) acc.set(k, v / AMENITIES.length);
  return acc;
}
