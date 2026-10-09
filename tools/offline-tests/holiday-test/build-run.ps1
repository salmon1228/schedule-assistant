param()
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

$here = $PSScriptRoot
Set-Location $here
$root = (Resolve-Path (Join-Path $here "..\..\..")).Path   # tools/offline-tests/<test> → 仓库根
# Kotlin 编译器 jar 从 Gradle 缓存里取(先跑一次 .\gradlew assembleDebug 就会有)
$cache = @(
    $(if ($env:GRADLE_USER_HOME) { Join-Path $env:GRADLE_USER_HOME "caches\modules-2\files-2.1" }),
    (Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1"),
    (Join-Path $root "_env\gradle-home\caches\modules-2\files-2.1")
) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $cache) { throw "找不到 Gradle 缓存。请先跑一次 .\gradlew assembleDebug" }
# JDK 17(设置 JAVA_HOME,或用 -Jdk 参数指定)
$jdk = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) { $env:JAVA_HOME }
       elseif ($env:JDK_HOME -and (Test-Path (Join-Path $env:JDK_HOME "bin\java.exe"))) { $env:JDK_HOME }
       else { throw "没找到 JDK 17。请设置环境变量 JAVA_HOME 指向 JDK 17 目录" }

function Find-Jar($groupArtifact, $version, $jarName) {
    $p = Join-Path $cache ($groupArtifact + "\" + $version)
    $f = Get-ChildItem -Path $p -Recurse -Filter $jarName -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $f) { throw "jar not found: $groupArtifact $version $jarName" }
    return $f.FullName
}

# android.jar is only needed at COMPILE time (org.json + android.content.Context declarations).
# At runtime the local org.json stub under .\stub provides real behaviour.
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME }
       elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
       else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$sdkPlatforms = Join-Path $sdk "platforms"
$androidJar = Get-ChildItem $sdkPlatforms -Recurse -Filter "android.jar" -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1
if (-not $androidJar) { throw "android.jar not found under $sdkPlatforms" }
$androidJar = $androidJar.FullName
Write-Output "android.jar = $androidJar"

$kc  = Find-Jar "org.jetbrains.kotlin\kotlin-compiler-embeddable" "1.9.24" "kotlin-compiler-embeddable-1.9.24.jar"
$std = Find-Jar "org.jetbrains.kotlin\kotlin-stdlib" "1.9.24" "kotlin-stdlib-1.9.24.jar"
$tro = Find-Jar "org.jetbrains.intellij.deps\trove4j" "1.0.20200330" "trove4j-1.0.20200330.jar"
$ann = Find-Jar "org.jetbrains\annotations" "23.0.0" "annotations-23.0.0.jar"
$compilerCp = (@($kc, $std, $tro, $ann) -join ";")

$srcs = @(
    (Join-Path $root "app\src\main\java\com\sdh\schedule\util\LunarCalendar.kt"),
    (Join-Path $root "app\src\main\java\com\sdh\schedule\util\HolidayRepo.kt"),
    (Join-Path $here "HolidayTest.kt")
)

$assetsJson = Join-Path $root "app\src\main\assets\holidays.json"
$resultTxt  = Join-Path $here "result.txt"

# ---- 1. compile the org.json stub (real implementation for the JVM run) ----
$stubSrc = Join-Path $here "stub"
$stubOut = Join-Path $here "stubout"
if (Test-Path $stubOut) { Remove-Item -Recurse -Force $stubOut }
New-Item -ItemType Directory -Force -Path $stubOut | Out-Null

Write-Output "=== javac org.json stub ==="
$stubFiles = Get-ChildItem -Path $stubSrc -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
& "$jdk\bin\javac.exe" -encoding UTF-8 -nowarn -d $stubOut $stubFiles 2>&1 | Out-String | Write-Output
if ($LASTEXITCODE -ne 0) { Write-Output "STUB COMPILE FAILED exit=$LASTEXITCODE"; exit 1 }

# ---- 2. compile the Kotlin sources against stdlib + android.jar ----
$out = Join-Path $here "out"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out | Out-Null

Write-Output "=== kotlinc ==="
& "$jdk\bin\java.exe" "-Dfile.encoding=UTF-8" "-DprojectRoot=$root" -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -jvm-target 17 -nowarn -cp "$std;$androidJar" -d $out $srcs 2>&1 | Out-String | Write-Output
if ($LASTEXITCODE -ne 0) { Write-Output "COMPILE FAILED exit=$LASTEXITCODE"; exit 1 }

# ---- 3. run (stubout first, so the real org.json wins over any android.jar on the path) ----
Write-Output "=== run ==="
& "$jdk\bin\java.exe" "-Dfile.encoding=UTF-8" "-DprojectRoot=$root" -cp "$stubOut;$out;$std" HolidayTestKt $assetsJson $resultTxt 2>&1 | Out-String | Write-Output
$runExit = $LASTEXITCODE
Write-Output "RUN exit=$runExit"
Write-Output "result.txt = $resultTxt"
exit $runExit


