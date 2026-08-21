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

export interface Manifest {
  version: number;
  generatedBy: string;
  warning: string;
  capMinutes: number;
  transferOverheadSeconds: number;
  slots: SlotMeta[];
  stations: StationMeta[];
}

/** 도달시간 조회. 프론트는 이것만 보고, 뒤가 역 행렬인지 격자 벡터인지 모른다. */
export interface ReachabilitySet {
  /** 역 인덱스 -> 소요시간(분). 도달 불가면 null. */
  minutesToStation(stationIndex: number): number | null;
  /** 예산 안에 드는 역들. [역 인덱스, 소요시간(분)] */
  stationsWithin(budgetMinutes: number): Array<[number, number]>;
}

export interface ReachabilityProvider {
  manifest(): Manifest;
  reachability(originStation: number, slotIndex: number): Promise<ReachabilitySet>;
}

export const UNREACHABLE_MINUTES = 255;
