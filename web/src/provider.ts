import type {
  Manifest,
  ReachabilityProvider,
  ReachabilitySet,
} from "./types";
import { UNREACHABLE_MINUTES } from "./types";

const MAGIC = "TRMX";
const HEADER_BYTES = 10;
/** flags 비트 0: 총시간 평면 뒤에 이탈 도보(분) 평면이 한 벌 더 있다. */
const FLAG_WALK_PLANE = 1;

interface OriginData {
  body: Uint8Array;
  slots: number;
  hasWalk: boolean;
}

/**
 * ngrok 무료 플랜은 첫 방문에 경고 페이지를 끼워넣는데, 그게 .bin 요청까지 가로채
 * 바이너리 대신 HTML 이 온다. 이 헤더가 있으면 통과한다. 다른 환경에서는 무시된다.
 */
const FETCH_INIT: RequestInit = { headers: { "ngrok-skip-browser-warning": "1" } };

/**
 * v2 구현: **출발역 -> 동네** 소요시간 행렬 (지하철+버스+도보).
 *
 * v1 은 역 -> 역이었다. 버스가 들어오면서 도착 축을 법정동 1,768개로 바꿨다.
 * 갈아끼운 건 이 파일과 타입뿐이고, 지도·UI 는 [ReachabilityProvider] 만 본다 —
 * 설계할 때 노린 게 그거였다.
 */
export class StationMatrixProvider implements ReachabilityProvider {
  private cache = new Map<number, OriginData>();

  private constructor(
    private readonly meta: Manifest,
    private readonly baseUrl: string,
  ) {}

  static async load(baseUrl: string): Promise<StationMatrixProvider> {
    const res = await fetch(`${baseUrl}/manifest.json`, FETCH_INIT);
    if (!res.ok) throw new Error(`manifest 로드 실패: ${res.status}`);
    return new StationMatrixProvider(await res.json(), baseUrl);
  }

  manifest(): Manifest {
    return this.meta;
  }

  async reachability(origin: number, slotIndex: number): Promise<ReachabilitySet> {
    const { body, slots, hasWalk } = await this.fetchOrigin(origin);
    const n = this.meta.dongs.length;
    const row = body.subarray(slotIndex * n, (slotIndex + 1) * n);
    // 도보 평면은 총시간 평면 **전체 뒤**에 온다. 옛 파일에는 없다.
    const walkRow = hasWalk
      ? body.subarray((slots + slotIndex) * n, (slots + slotIndex + 1) * n)
      : null;

    return {
      within(budget, walkCap) {
        const out: Array<[number, number]> = [];
        for (let i = 0; i < row.length; i++) {
          const v = row[i];
          if (v === UNREACHABLE_MINUTES || v > budget) continue;
          if (walkRow && walkCap !== undefined && walkRow[i] > walkCap) continue;
          out.push([i, v]);
        }
        return out;
      },
    };
  }

  /**
   * 출발역 파일 하나(약 140KB)만 받는다. 이후 시각·예산 슬라이더를 아무리 움직여도
   * 네트워크 호출이 없다 — threshold 를 계산에서 분리한 설계의 핵심 이득.
   */
  private async fetchOrigin(origin: number): Promise<OriginData> {
    const hit = this.cache.get(origin);
    if (hit) return hit;

    const res = await fetch(`${this.baseUrl}/matrix/${origin}.bin`, FETCH_INIT);
    if (!res.ok) throw new Error(`행렬 로드 실패 (역 ${origin}): ${res.status}`);
    const buf = new Uint8Array(await res.arrayBuffer());

    const magic = String.fromCharCode(...buf.subarray(0, 4));
    if (magic !== MAGIC) throw new Error(`포맷이 아니다: ${magic}`);

    const hasWalk = (buf[5] & FLAG_WALK_PLANE) !== 0;
    const slots = buf[6] | (buf[7] << 8);
    const stations = buf[8] | (buf[9] << 8);
    const planes = hasWalk ? 2 : 1;
    const expected = HEADER_BYTES + planes * slots * stations;
    if (buf.length !== expected) {
      throw new Error(`크기 불일치: ${buf.length} != ${expected}`);
    }

    const data: OriginData = { body: buf.subarray(HEADER_BYTES), slots, hasWalk };
    this.cache.set(origin, data);
    return data;
  }
}
