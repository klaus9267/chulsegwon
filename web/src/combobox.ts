export interface ComboOption {
  /** 입력창에 들어갈 값. 선택 시 onSelect 로 넘어간다. */
  value: string;
  /** 오른쪽에 흐리게 붙는 부가 정보. 같은 이름의 역을 구분하는 데 쓴다. */
  hint?: string;
}

interface ComboConfig {
  input: HTMLInputElement;
  toggle: HTMLElement;
  list: HTMLElement;
  options: ComboOption[];
  onSelect: (value: string) => void;
  /** 한 번에 그릴 최대 항목 수. 역이 600개가 넘어 전부 그리면 타이핑이 무거워진다. */
  maxRendered?: number;
}

/**
 * 타이핑 검색과 목록 선택을 둘 다 지원하는 콤보박스.
 *
 * 네이티브 `<datalist>` 를 쓰다가 걷어냈다. 브라우저마다 목록이 열리는 조건이 다르고
 * (크롬은 타이핑해야 뜨는 경우가 있다), 항목에 노선 같은 부가 정보를 붙일 수 없고,
 * 키보드 동작을 손볼 수 없다. 역을 고르는 게 이 화면의 첫 단계라 그 정도는 직접 만든다.
 */
export function createCombobox(config: ComboConfig) {
  const { input, toggle, list, options, onSelect } = config;
  const maxRendered = config.maxRendered ?? 120;

  let filtered: ComboOption[] = options;
  let activeIndex = -1;
  let open = false;

  /**
   * 목록을 패널 밖(body)으로 꺼내 띄운다.
   *
   * 원래는 입력창 옆에 `position:absolute` 로 두었는데 두 가지가 겹쳐 깨졌다.
   * 하나는 조건 영역이 `overflow-y:auto` 라 목록이 스크롤 박스에 **잘린다**는 것,
   * 다른 하나는 패널의 `backdrop-filter` 가 합성 레이어를 만들어 목록이 뒤 내용과
   * 섞여 보인다는 것이다. 둘 다 조상 때문에 생기므로 조상에서 빼내는 게 답이다.
   *
   * 대신 위치를 직접 계산해야 한다. 화면 아래 공간이 모자라면 위로 뒤집는데,
   * 모바일에서는 패널이 하단 시트라 이게 없으면 목록이 화면 밖으로 나간다.
   */
  function place() {
    const r = input.getBoundingClientRect();
    const GAP = 5;
    const MAX = 236;
    const below = window.innerHeight - r.bottom - GAP - 8;
    const above = r.top - GAP - 8;
    const flip = below < 140 && above > below;

    list.style.position = "fixed";
    list.style.left = r.left + "px";
    list.style.width = r.width + "px";
    list.style.maxHeight = Math.max(96, Math.min(MAX, flip ? above : below)) + "px";
    if (flip) {
      list.style.top = "";
      list.style.bottom = window.innerHeight - r.top + GAP + "px";
    } else {
      list.style.bottom = "";
      list.style.top = r.bottom + GAP + "px";
    }
  }

  // 조건 패널을 스크롤하거나 창 크기가 바뀌면 입력창이 움직인다. 목록만 제자리에
  // 남으면 엉뚱한 곳에 떠 있게 되므로 따라가게 한다.
  const reposition = () => { if (open) place(); };

  function normalize(s: string) {
    return s.trim().toLowerCase();
  }

  function filter(query: string): ComboOption[] {
    const q = normalize(query);
    if (q === "") return options;
    // 앞에서부터 맞는 걸 먼저 보여준다. "강남" 을 치면 강남이 강남구청보다 위에 와야 한다.
    const starts: ComboOption[] = [];
    const contains: ComboOption[] = [];
    for (const o of options) {
      const v = o.value.toLowerCase();
      if (v.startsWith(q)) starts.push(o);
      else if (v.includes(q)) contains.push(o);
    }
    return [...starts, ...contains];
  }

  function render() {
    list.innerHTML = "";
    if (filtered.length === 0) {
      const li = document.createElement("li");
      li.className = "empty";
      li.textContent = "일치하는 역이 없습니다";
      list.appendChild(li);
      return;
    }

    filtered.slice(0, maxRendered).forEach((o, i) => {
      const li = document.createElement("li");
      li.setAttribute("role", "option");
      li.setAttribute("aria-selected", String(i === activeIndex));
      li.dataset.index = String(i);

      const name = document.createElement("span");
      name.textContent = o.value;
      li.appendChild(name);

      if (o.hint) {
        const hint = document.createElement("span");
        hint.className = "hint";
        hint.textContent = o.hint;
        li.appendChild(hint);
      }
      list.appendChild(li);
    });

    if (filtered.length > maxRendered) {
      const li = document.createElement("li");
      li.className = "empty";
      li.textContent = `외 ${filtered.length - maxRendered}개 — 더 입력해 좁혀보세요`;
      list.appendChild(li);
    }
  }

  function show(query = input.value) {
    filtered = filter(query);
    // 이미 고른 값이 있으면 그 항목에 커서를 둔다. 다시 열었을 때 어디였는지 보인다.
    activeIndex = filtered.findIndex((o) => o.value === input.value.trim());
    open = true;
    if (list.parentElement !== document.body) document.body.appendChild(list);
    list.hidden = false;
    input.setAttribute("aria-expanded", "true");
    render();
    place();
    scrollActiveIntoView();
    // capture 로 받아야 조건 패널 안쪽 스크롤도 잡힌다. 스크롤은 버블링하지 않는다.
    window.addEventListener("scroll", reposition, true);
    window.addEventListener("resize", reposition);
  }

  function hide() {
    open = false;
    list.hidden = true;
    input.setAttribute("aria-expanded", "false");
    window.removeEventListener("scroll", reposition, true);
    window.removeEventListener("resize", reposition);
  }

  function scrollActiveIntoView() {
    if (activeIndex < 0) return;
    const el = list.querySelector<HTMLElement>(`li[data-index="${activeIndex}"]`);
    el?.scrollIntoView({ block: "nearest" });
  }

  function move(delta: number) {
    if (!open) {
      show();
      return;
    }
    const limit = Math.min(filtered.length, maxRendered);
    if (limit === 0) return;
    activeIndex = (activeIndex + delta + limit) % limit;
    render();
    scrollActiveIntoView();
  }

  function commit(index: number) {
    const picked = filtered[index];
    if (!picked) return;
    input.value = picked.value;
    hide();
    onSelect(picked.value);
  }

  input.addEventListener("input", () => {
    show(input.value);
    activeIndex = filtered.length > 0 ? 0 : -1;
    render();
  });

  input.addEventListener("focus", () => show());

  input.addEventListener("keydown", (e) => {
    switch (e.key) {
      case "ArrowDown":
        e.preventDefault();
        move(1);
        break;
      case "ArrowUp":
        e.preventDefault();
        move(-1);
        break;
      case "Enter":
        // 목록이 열려 있으면 선택, 아니면 폼의 기본 동작(계산 실행)에 맡긴다.
        if (open && activeIndex >= 0) {
          e.preventDefault();
          commit(activeIndex);
        }
        break;
      case "Escape":
        if (open) {
          e.preventDefault();
          hide();
        }
        break;
      case "Tab":
        hide();
        break;
    }
  });

  toggle.addEventListener("mousedown", (e) => {
    // mousedown 에서 막지 않으면 input 이 blur 됐다가 다시 focus 돼 목록이 깜빡인다.
    e.preventDefault();
    if (open) hide();
    else {
      input.focus();
      show("");
    }
  });

  list.addEventListener("mousedown", (e) => {
    const li = (e.target as HTMLElement).closest<HTMLElement>("li[data-index]");
    if (!li) return;
    e.preventDefault();
    commit(Number(li.dataset.index));
  });

  document.addEventListener("mousedown", (e) => {
    if (!open) return;
    const inside = input.contains(e.target as Node) ||
      list.contains(e.target as Node) ||
      toggle.contains(e.target as Node);
    if (!inside) hide();
  });

  return {
    /** 입력값이 실제 목록에 있는 값인지. 없으면 계산을 시작하면 안 된다. */
    isValid: () => options.some((o) => o.value === input.value.trim()),
    close: hide,
  };
}
