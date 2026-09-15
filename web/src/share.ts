import type { RoomType, Tenure } from "./dongs";

/**
 * 조건을 URL 에 담는다.
 *
 * 이 도구는 한 번 쓰고 끝나기 쉽다. 살 곳을 정하고 나면 다시 올 이유가 없다.
 * 그래서 **남에게 보내는 것**이 사실상 유일한 자연 유입 경로다. 커플이나
 * 룸메이트는 어차피 "여기 어때?"를 주고받으며 고르므로, 그 대화에 링크가
 * 끼어들 수 있으면 된다.
 *
 * 역 이름을 쓰고 인덱스를 쓰지 않는다. 인덱스는 그래프를 다시 만들면 바뀌어서,
 * 어제 보낸 링크가 오늘 엉뚱한 역을 가리킨다.
 */
export interface ShareState {
  origin: string;
  direction: "ARRIVE_BY" | "DEPART_AT";
  timeIndex: number;
  budget: number;
  walkCap: number;
  room: RoomType;
  tenure: Tenure;
  cap: number;
  amenities: string[];
  /**
   * 오르막을 뺄지. **답을 바꾸는 조건이라 반드시 실어야 한다.**
   * 빠져 있어서, 켜고 보낸 링크가 받는 쪽에서는 꺼진 채 열렸다.
   */
  flatOnly: boolean;
}

export function encodeState(s: ShareState): string {
  const p = new URLSearchParams();
  p.set("o", s.origin);
  p.set("d", s.direction === "ARRIVE_BY" ? "a" : "d");
  p.set("t", String(s.timeIndex));
  p.set("b", String(s.budget));
  p.set("w", String(s.walkCap));
  p.set("r", s.room);
  p.set("n", s.tenure === "WOLSE" ? "w" : "j");
  if (s.cap > 0) p.set("c", String(s.cap));
  if (s.amenities.length > 0) p.set("am", s.amenities.join(","));
  // 답을 바꾸는 조건이라 반드시 싣는다. 기본값(꺼짐)일 때만 생략한다.
  if (s.flatOnly) p.set("fl", "1");
  return p.toString();
}

/** 링크가 손상됐거나 옛 형식이어도 화면은 떠야 한다. 읽을 수 있는 것만 취한다. */
export function decodeState(hash: string): Partial<ShareState> {
  const p = new URLSearchParams(hash.replace(/^#/, ""));
  const out: Partial<ShareState> = {};
  const o = p.get("o");
  if (o) out.origin = o;
  // `o2`(맞벌이 두 번째 직장)는 09-16 에 기능을 뺐다. 옛 링크에 있어도 첫 직장 기준으로 연다.
  if (p.get("d") === "d") out.direction = "DEPART_AT";
  else if (p.get("d") === "a") out.direction = "ARRIVE_BY";

  const num = (k: string, lo: number, hi: number): number | undefined => {
    const v = Number(p.get(k));
    return p.has(k) && Number.isFinite(v) && v >= lo && v <= hi ? v : undefined;
  };
  const t = num("t", 0, 200);
  if (t !== undefined) out.timeIndex = t;
  const b = num("b", 10, 120);
  if (b !== undefined) out.budget = b;
  // 행렬의 이탈 도보 예산이 15분이라 그 위는 뜻이 없다.
  const w = num("w", 0, 15);
  if (w !== undefined) out.walkCap = w;
  if (p.has("fl")) out.flatOnly = p.get("fl") === "1";
  const c = num("c", 0, 100000);
  if (c !== undefined) out.cap = c;

  const r = p.get("r");
  if (r === "ONE" || r === "TWO" || r === "THREE") out.room = r;
  const n = p.get("n");
  if (n === "w") out.tenure = "WOLSE";
  else if (n === "j") out.tenure = "JEONSE";
  const am = p.get("am");
  // 모르는 코드는 버린다. 옛 링크에 없어진 분류가 들어 있으면 아무것도 안 나온다.
  if (am) out.amenities = am.split(",").filter((c) => /^[A-Z]{2}[0-9]$/.test(c));
  return out;
}

/**
 * 클립보드에 복사.
 *
 * `navigator.clipboard` 는 https 나 localhost 에서만 된다. 사내망이나 ngrok http
 * 주소로 열면 조용히 실패하므로, 옛 방식으로 한 번 더 시도한다.
 */
export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    const ta = document.createElement("textarea");
    ta.value = text;
    ta.style.position = "fixed";
    ta.style.opacity = "0";
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand("copy");
    document.body.removeChild(ta);
    return ok;
  }
}
