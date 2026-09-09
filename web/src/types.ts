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
  /** 도착 축 인덱스 -> 소요시간(분). 도달 불가면 null. */
  minutesTo(index: number): number | null;
  /**
   * 그 소요시간 **안에 들어 있는 이탈 도보**(분). 모르면 null.
   *
   * 행렬 값은 "역에서 내려 집까지"를 이미 포함한 문앞 시간이다. 화면의 도보 슬라이더가
   * 뜻을 가지려면 그중 도보가 몇 분인지 알아야 한다. 예전엔 그걸 몰라서 웹이 도보를
   * 한 번 더 더해 그렸다(이중계상).
   */
  walkTo(index: number): number | null;
  /**
   * 예산 안 + 도보 상한 안에 드는 것들. `[인덱스, 소요시간(분)]`
   *
   * [walkCapMinutes] 를 주면 "이탈 도보가 그보다 긴 곳"을 뺀다. 도보 평면이 없는
   * 옛 파일에서는 무시된다.
   */
  within(budgetMinutes: number, walkCapMinutes?: number): Array<[number, number]>;
}

export interface ReachabilityProvider {
  manifest(): Manifest;
  reachability(originStation: number, slotIndex: number): Promise<ReachabilitySet>;
}

export const UNREACHABLE_MINUTES = 255;
