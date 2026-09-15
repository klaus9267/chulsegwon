export type Direction = "ARRIVE_BY" | "DEPART_AT";

export interface SlotMeta {
  index: number;
  direction: Direction;
  secondsOfDay: number;
  label: string;
}

export interface StationMeta {
  index: number;
  name: string;
  lat: number;
  lon: number;
  lines: string[];
}

/** 도착 축. 버스가 들어오면서 역이 아니라 법정동이 됐다. */
export interface DongMeta {
  index: number;
  name: string;
  gu: string;
  lat: number;
  lon: number;
}

export interface Manifest {
  version: number;
  generatedBy: string;
  warning: string;
  capMinutes: number;
  transferOverheadSeconds: number;
  slots: SlotMeta[];
  /** **출발지** 목록. 사용자가 고르는 직장이다. */
  stations: StationMeta[];
  /** **도착 축**. 행렬의 열이 이 순서다. */
  dongs: DongMeta[];
}

/**
 * 도달시간 조회. 프론트는 이것만 보고, 뒤가 무엇인지 모른다.
 *
 * 이름을 `…Station` 에서 바꾼 이유: 도착 축이 역에서 동네로 바뀌었다.
 * 버스가 들어오면 정류장이 5만 개라 도착 축에 다 넣을 수 없고, "가까운 역"이라는
 * 개념도 의미를 잃는다 — 역에서 먼 동네도 버스로는 가깝다.
 */
export interface ReachabilitySet {
  /**
   * 예산 안 + 도보 상한 안에 드는 것들. `[인덱스, 소요시간(분)]`
   *
   * [walkCapMinutes] 를 주면 "이탈 도보가 그보다 긴 곳"을 뺀다. 도보 평면이 없는
   * 옛 파일에서는 무시된다.
   *
   * 행렬 값은 "역에서 내려 집까지"를 이미 포함한 문앞 시간이고, 도보 평면은 그 안에
   * 든 이탈 도보(분)다. 예전엔 도보를 몰라서 웹이 한 번 더 더해 그렸다(이중계상).
   *
   * 칸 하나씩 읽는 `minutesTo`·`walkTo` 는 맞벌이(두 직장 교집합)만 썼고, 09-16 에
   * 그 기능을 빼면서 같이 뺐다.
   */
  within(budgetMinutes: number, walkCapMinutes?: number): Array<[number, number]>;
}

export interface ReachabilityProvider {
  manifest(): Manifest;
  reachability(originStation: number, slotIndex: number): Promise<ReachabilitySet>;
}

export const UNREACHABLE_MINUTES = 255;
