# 예약 실행용 수집 스크립트.
#
# Windows 작업 스케줄러가 하루 여러 번 부른다. 하는 일은 셋이다:
#   1. 서울 구간 속도 스냅샷  — 시간대별로 찍어야 의미가 생긴다. 매번 실행
#   2. 경기 버스 수집         — 이미 끝났으면 즉시 반환. 실패한 시군만 다시 받는다
#   3. 서울 버스 수집         — 〃
#   4. GTFS 다시 만들고 검사  — 위에서 쌓인 만큼 정확해진다
#   5. 지표 한 줄 기록        — 좋아지고 있는지 눈으로 보려고
#
# 설계에서 지킨 것:
#
# * **모든 단계가 재개 가능하다.** 이 프로젝트에서 같은 실수를 네 번 했다 — 실거래가
#   45분 날림, 버스 정류장 페이징 truncation, 도시코드 오류, Overpass 타일. 예약 실행은
#   사람이 안 보는 사이에 도니까 이게 더 중요하다.
# * **한 단계가 실패해도 나머지는 돈다.** API 일일 한도는 종류별로 따로 걸려서, 하나가
#   막혔다고 전부 멈추면 안 된다.
# * **실행 사본으로 돈다.** 개발 중에 jar 를 다시 빌드하면 실행 중인 JVM 이
#   ClassNotFoundException 으로 죽는다(실제로 겪었다). 시작할 때 복사해서 격리한다.
# * **매번 결과물을 다시 만든다.** 수집만 하고 쌓아두면 "언제 반영되나"를 사람이
#   챙겨야 한다. 4번이 그걸 없앤다 — 스냅샷 한 번 더 찍히면 GTFS 도 그만큼 정확해진다.

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# 콘솔 코드페이지가 CP949 라 한글이 깨진다. 로그가 안 읽히면 로그가 아니다.
$env:JAVA_OPTS = '-Dfile.encoding=UTF-8'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$logDir = Join-Path $root 'data\logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$log = Join-Path $logDir ("collect-" + (Get-Date -Format 'yyyyMMdd') + ".log")

function Say($msg) {
    $line = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $msg
    Write-Output $line
    Add-Content -Path $log -Value $line -Encoding UTF8
}

# ── .env 읽기 ────────────────────────────────────────────────
$envFile = Join-Path $root '.env'
if (-not (Test-Path $envFile)) { Say "!! .env 가 없다 — 중단"; exit 1 }
Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([A-Z_][A-Z0-9_]*)\s*=\s*(.+?)\s*$') {
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}

# ── 실행 사본 ────────────────────────────────────────────────
$dist = Join-Path $root 'builder\build\install\builder'
if (-not (Test-Path $dist)) { Say "!! 빌드 산출물이 없다. ./gradlew :builder:installDist 먼저"; exit 1 }
$run = Join-Path $env:TEMP 'chulsegwon-run'
if (Test-Path $run) { Remove-Item -Recurse -Force $run -ErrorAction SilentlyContinue }
Copy-Item -Recurse -Force $dist $run -ErrorAction SilentlyContinue
$exe = Join-Path $run 'bin\builder.bat'
if (-not (Test-Path $exe)) { $exe = Join-Path $dist 'bin\builder.bat' }   # 복사 실패 시 원본

# ── 단계 ─────────────────────────────────────────────────────
# 마지막 단계의 전체 출력. 아래에서 지표를 뽑아 쓴다.
$script:lastGtfs = ''

function Step($name, $modeArgs) {
    Say "▶ $name"
    $t0 = Get-Date
    try {
        $out = & $exe @modeArgs 2>&1 | Out-String
        if ($name -like 'GTFS*') { $script:lastGtfs += $out }
        # 검사 결과는 여러 줄이라 다 남긴다. 나머지는 마지막 세 줄이면 충분하다.
        $keep = if ($name -like 'GTFS*') { 12 } else { 3 }
        $out -split "`n" | Where-Object { $_.Trim() } | Select-Object -Last $keep |
            ForEach-Object { Say ("   " + $_.Trim()) }
    } catch {
        Say ("   !! " + $_.Exception.Message)
    }
    Say ("◀ $name  {0:n0}초" -f ((Get-Date) - $t0).TotalSeconds)
}

Say "===== 수집 시작 ====="

# 1) 구간 속도 — 시간대별로 찍는 게 목적이라 매번 실행한다
Step '서울 구간속도 스냅샷' @('--mode','seoulspeed')

# 2~3) 노선·정류장 — 이미 끝났으면 즉시 반환한다
Step '경기 버스'  @('--mode','bus')
Step '서울 버스'  @('--mode','seoulbus')

# 4) 결과물. 위에서 스냅샷이 하나 늘면 여기서 바로 반영된다
Step 'GTFS 생성'  @('--mode','gtfs')
Step 'GTFS 검사'  @('--mode','gtfscheck')

# 5) 바깥 기준값과 대조하고 보정을 다시 맞춘다.
#
#    이게 이 스크립트에서 제일 중요한 단계다. gtfscheck 는 "규격에 맞나"까지만 보고
#    **전체가 일정하게 빠른 것**은 못 잡는다. 실제로 처음 만든 GTFS 는 카카오맵 대비
#    30% 빨랐고, 그건 규격 검사를 멀쩡히 통과했다.
#
#    tools/reference-bus.json 은 카카오맵에서 받아 고정해둔 구간이다. 속도 표본이
#    바뀌면 대조 오차도 바뀌고, --fit 이 정류장 통과 비용을 다시 계산해
#    data/calibration.json 에 쓴다. 값이 바뀌었으면 그걸로 한 번 더 만든다.
New-Item -ItemType Directory -Force -Path 'data/out/verify' | Out-Null
$before = if (Test-Path 'data/calibration.json') { Get-Content 'data/calibration.json' -Raw } else { '' }
Say '▶ 카카오 대조 · 보정'
$fit = & python tools/gtfs_match.py tools/reference-bus.json data/out/verify/matched.json --fit 2>&1 | Out-String
$fit -split "`n" | Where-Object { $_ -match '구간 \d+개|편향|보정 갱신|정류장 통과' } |
    ForEach-Object { Say ('   ' + $_.Trim()) }
$calLine = [regex]::Match($fit, 'CALIBRATION (\d+) ([\d.]+) ([-+]?\d+) (\d+) ([\d.]+)')
$after = if (Test-Path 'data/calibration.json') { Get-Content 'data/calibration.json' -Raw } else { '' }
if ($after -and $after -ne $before) {
    Say '   보정이 바뀌었다 — 그 값으로 다시 만든다'
    Step 'GTFS 재생성' @('--mode','gtfs')
}
Say '◀ 카카오 대조 · 보정'

# 6) 도달권 행렬. GTFS 가 바뀌었을 때만 다시 만든다.
#
#    한 번에 6분이라 매 회차 돌릴 이유가 없다. 바뀌는 건 속도 스냅샷이 하나 더
#    쌓였을 때뿐이고, 그건 하루 다섯 번 중 몇 번이다. zip 이 행렬보다 새것이면 돈다.
#    ⚠️ 수정시각으로 비교하면 안 된다. `--mode gtfs` 는 내용이 같아도 매번 zip 을
#    새로 쓰므로 zip 이 항상 더 새것이 되고, 행렬이 매 회차(4분씩) 헛돈다.
#    **내용 해시**로 본다.
$zipPath = 'data/out/gtfs-seoul-gyeonggi.zip'
$subPath = 'data/out/gtfs-subway.zip'
$stampPath = 'data/out/matrix-built.txt'
$sig = ''
foreach ($f in @($zipPath, $subPath)) {
    if (Test-Path $f) { $sig += (Get-FileHash $f -Algorithm SHA256).Hash }
}
$prev = if (Test-Path $stampPath) { (Get-Content $stampPath -Raw).Trim() } else { '' }
$needMatrix = ($sig -ne '') -and ($sig -ne $prev)
if ($needMatrix) {
    Step '도달권 행렬' @('--mode','dongmatrix','--gml','data/raw/metro_graph.gml','--out','web/public/data')
    Set-Content -Path $stampPath -Value $sig -Encoding ASCII
} else {
    Say '· 도달권 행렬 — GTFS 내용이 그대로라 건너뛴다'
}

Say "===== 수집 끝 ====="

# ── 지표 한 줄 ───────────────────────────────────────────────
# 목표는 "실측 비율"이 100 에 가까워지는 것이다. 이 표가 그걸 보여준다.
# 오르지 않으면 스케줄이 헛도는 것이고, 그건 로그를 뒤지기 전에 알아야 한다.
$metrics = Join-Path $logDir 'progress.csv'
if (-not (Test-Path $metrics)) {
    Set-Content -Path $metrics -Encoding UTF8 `
        -Value 'time,snapshots,measured_segments,measured_pct,stops,routes,trips,zip_kb,dwell_sec,detour,kakao_bias_sec,kakao_mae_sec,kakao_within3min,speed_curve'
}
$snapCount = @(Get-ChildItem 'data/raw/seoul-bus/speed' -Filter '*.jsonl' -ErrorAction SilentlyContinue).Count
$zip = Get-Item 'data/out/gtfs-seoul-gyeonggi.zip' -ErrorAction SilentlyContinue
$last = $lastGtfs   # Step 이 채워둔 마지막 출력
$m = [regex]::Match($last, '실측 ([\d,]+) · 곡선 ([\d,]+) · 고정표 ([\d,]+) \(실측 ([\d.]+)%')
$s2 = [regex]::Match($last, '정류장 ([\d,]+) · 노선 ([\d,]+) · 운행 ([\d,]+)')
# 형상점(미정차·가상)을 뺀 뒤가 실제로 파일에 들어간 정류장 수다. 앞 줄은 내부 집계다.
$pub = [regex]::Match($last, '형상점.*?정류장 ([\d,]+)')
# 속도 곡선도 남긴다. 실측 비율은 서울 자체노선에만 sectSpd 가 있어서 ~17% 에서
# 멈추지만, 스냅샷이 쌓이면서 실제로 좋아지는 건 이 곡선이다. 값이 흔들리다 멎으면
# 표본이 충분해진 것이고, 그때가 수집을 줄여도 되는 시점이다.
$cv = [regex]::Match($last, '속도 곡선\(구간길이:km/h\) (.+)')
$cg = { param($i) if ($calLine.Success) { $calLine.Groups[$i].Value } else { '' } }
$row = '{0},{1},{2},{3},{4},{5},{6},{7},{8},{9},{10},{11},{12},{13}' -f `
    (Get-Date -Format 'yyyy-MM-dd HH:mm'), $snapCount,
    $(if ($m.Success) { $m.Groups[1].Value -replace ',','' } else { '' }),
    $(if ($m.Success) { $m.Groups[4].Value } else { '' }),
    $(if ($pub.Success) { $pub.Groups[1].Value -replace ',','' } elseif ($s2.Success) { $s2.Groups[1].Value -replace ',','' } else { '' }),
    $(if ($s2.Success) { $s2.Groups[2].Value -replace ',','' } else { '' }),
    $(if ($s2.Success) { $s2.Groups[3].Value -replace ',','' } else { '' }),
    $(if ($zip) { [int]($zip.Length / 1KB) } else { '' }),
    (& $cg 1), (& $cg 2), (& $cg 3), (& $cg 4), (& $cg 5),
    $(if ($cv.Success) { '"' + $cv.Groups[1].Value.Trim() + '"' } else { '' })
Add-Content -Path $metrics -Value $row -Encoding UTF8
Say "지표: $row"

# 로그가 무한정 쌓이지 않게 30일치만 남긴다
Get-ChildItem $logDir -Filter 'collect-*.log' |
    Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-30) } |
    Remove-Item -Force -ErrorAction SilentlyContinue
