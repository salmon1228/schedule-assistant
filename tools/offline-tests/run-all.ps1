# 一次性跑完所有离线测试(不需要手机/模拟器,只要有 JDK 17 + Android SDK 34)
# 用法:  .\tools\offline-tests\run-all.ps1
$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$here = $PSScriptRoot
$names = @(
    "parser-test", "recur-test", "holiday-test", "course-test", "todo-test",
    "cal-test", "migrate-test", "course-text-test", "grid-test", "color-test", "ics-test"
)
$pass = 0; $fail = 0
foreach ($n in $names) {
    $runner = Join-Path $here "$n\build-run.ps1"
    if (-not (Test-Path $runner)) { Write-Host "[跳过] $n"; continue }
    Write-Host ""
    Write-Host ("=" * 60)
    Write-Host "▶ $n"
    Write-Host ("=" * 60)
    & $runner
    $resultFile = Join-Path $here "$n\result.txt"
    if (Test-Path $resultFile) {
        $all = Get-Content $resultFile -Encoding UTF8 -Raw
        if ($all -match "ALL TESTS PASSED|ALL PASSED|ALL CHECKS PASSED") { $pass++ }
        else { $fail++; Write-Host "[FAIL] $n" }
        ($all -split "`n" | Select-String -Pattern "总计" | Select-Object -First 1).Line
    } else { $fail++; Write-Host "[FAIL] $n (没有 result.txt)" }
}
Write-Host ""
# migrate-test 是 node 脚本(校验数据库升级不丢数据),单独跑
$mig = Join-Path $here "migrate-test\migrate.js"
if ((Test-Path $mig) -and (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Host ("=" * 60)
    Write-Host "▶ migrate-test"
    Write-Host ("=" * 60)
    Push-Location (Join-Path $here "migrate-test")
    & node migrate.js
    Pop-Location
}
Write-Host ""
Write-Host ("=" * 60)
Write-Host "通过 $pass 套,失败 $fail 套"
if ($fail -gt 0) { exit 1 }

