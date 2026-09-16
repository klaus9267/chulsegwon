# 카카오 기준 한 회차: 표본을 뜨고 -> 우리 값과 맞대보고 -> 지표 한 줄을 쌓는다.
# 설계는 docs/LOOP.md.
#
# 하루 두 번(08:05 / 19:05) 도는 게 기본이다. 카카오 PC 길찾기는 "지금 출발"뿐이라
# **수집 시각이 곧 출발 시각**이고, 우리가 보는 시간대가 출근·퇴근이기 때문이다.
#
#   powershell -ExecutionPolicy Bypass -File tools\loop-measure.ps1 [-N 25] [-Gap 20]
param(
    [int]$N = 25,        # 이번 회차에 뜰 표본 수
    [int]$Gap = 20,      # 한 건 사이 쉬는 초. 예의이자 차단 방지다
    [int]$Sweep = 60,    # 출발 시각을 훑는 폭(분)
    [int]$Step = 3,      # 훑는 간격(분)
    [int]$Budget = 120   # 탐색 상한(분)
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# 콘솔 코드페이지가 CP949 라 한글이 깨진다. 로그가 안 읽히면 로그가 아니다.
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$env:PYTHONUTF8 = '1'
$env:JAVA_OPTS = '-Dfile.encoding=UTF-8'

$logDir = Join-Path $root 'data\logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$log = Join-Path $logDir ("loop-" + (Get-Date -Format 'yyyyMMdd') + ".log")

function Say($msg) {
    $line = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $msg
    Write-Output $line
    Add-Content -Path $log -Value $line -Encoding UTF8
}

Say "===== 대조 회차 시작 (표본 $N) ====="

# 1) 카카오 표본. 실패해도 멈추지 않는다 — 옛 표본으로 대조라도 한다.
Say '▶ 카카오 표본'
& node (Join-Path $root 'tools\kakao-collect.mjs') --n $N --gap $Gap 2>&1 |
    Where-Object { $_.ToString().Trim() } | ForEach-Object { Say ("   " + $_.ToString().Trim()) }

$sample = Get-ChildItem (Join-Path $root 'data\verify\kakao') -Filter '*.json' -ErrorAction SilentlyContinue |
    Sort-Object Name | Select-Object -Last 1
if (-not $sample) { Say '!! 표본이 하나도 없다 — 중단'; exit 1 }
Say ("◀ 카카오 표본 · " + $sample.Name)

# 2) 같은 문 앞에서 우리 값. 도보 모델까지 생산 행렬과 공유한다(Access).
$dist = Join-Path $root 'builder\build\install\builder'
$exe = Join-Path $dist 'bin\builder.bat'
if (-not (Test-Path $exe)) { Say '!! 빌드 산출물이 없다. ./gradlew :builder:installDist 먼저'; exit 1 }

$csv = Join-Path $root ('data\out\verify\odcheck-' + [IO.Path]::GetFileNameWithoutExtension($sample.Name) + '.csv')
Say '▶ 대조'
& $exe --mode odcheck --od $sample.FullName --sweep $Sweep --step $Step --budget $Budget --out $csv 2>&1 |
    Where-Object { $_.ToString().Trim() } | Select-Object -Last 4 |
    ForEach-Object { Say ("   " + $_.ToString().Trim()) }

# 3) 지표 한 줄. 동결 표본은 따로 센다.
Say '▶ 지표'
& python (Join-Path $root 'tools\od_metrics.py') $csv $sample.FullName 2>&1 |
    Where-Object { $_.ToString().Trim() } | ForEach-Object { Say ("   " + $_.ToString().Trim()) }

Say "===== 대조 회차 끝 ====="
