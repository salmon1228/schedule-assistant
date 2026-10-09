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
# Android SDK(设置 ANDROID_HOME / ANDROID_SDK_ROOT)
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME }
       elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
       else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$androidJar = Join-Path $sdk "platforms\android-34\android.jar"
if (-not (Test-Path $androidJar)) { throw "找不到 android.jar(需要 Android SDK Platform 34):$androidJar" }

function Find-Jar($groupArtifact, $version, $jarName) {
    $p = Join-Path $cache ($groupArtifact + "\" + $version)
    $f = Get-ChildItem -Path $p -Recurse -Filter $jarName -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $f) { throw "jar not found: $groupArtifact $version $jarName" }
    return $f.FullName
}

$kc  = Find-Jar "org.jetbrains.kotlin\kotlin-compiler-embeddable" "1.9.24" "kotlin-compiler-embeddable-1.9.24.jar"
$std = Find-Jar "org.jetbrains.kotlin\kotlin-stdlib" "1.9.24" "kotlin-stdlib-1.9.24.jar"
$tro = Find-Jar "org.jetbrains.intellij.deps\trove4j" "1.0.20200330" "trove4j-1.0.20200330.jar"
$ann = Find-Jar "org.jetbrains\annotations" "23.0.0" "annotations-23.0.0.jar"
$compilerCp = (@($kc, $std, $tro, $ann) -join ";")

$srcs = @(
    (Join-Path $root "app\src\main\java\com\sdh\schedule\util\LunarCalendar.kt"),
    (Join-Path $root "app\src\main\java\com\sdh\schedule\util\HolidayRepo.kt"),
    (Join-Path $root "app\src\main\java\com\sdh\schedule\util\CalendarLabels.kt"),
    (Join-Path $here "CalTest.kt")
)

$out = Join-Path $here "out"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out | Out-Null

# android.jar 里的 org.json 是空壳(运行时抛 Stub!),这里用一份最小可用实现盖住它
$stubDir = Join-Path $root "tools\offline-tests\holiday-test\stub"
$stubOut = Join-Path $here "stubout"
if (Test-Path $stubOut) { Remove-Item -Recurse -Force $stubOut }
New-Item -ItemType Directory -Force -Path $stubOut | Out-Null
Write-Output "=== compile org.json stub ==="
$stubSrcs = Get-ChildItem -Path $stubDir -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
& "$jdk\bin\javac.exe" -encoding UTF-8 -nowarn -d $stubOut $stubSrcs 2>&1 | Out-String | Write-Output

Write-Output "=== compile ==="
& "$jdk\bin\java.exe" "-Dfile.encoding=UTF-8" "-DprojectRoot=$root" -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -jvm-target 17 -nowarn -cp "$std;$androidJar" -d $out $srcs 2>&1 | Out-String | Write-Output
if ($LASTEXITCODE -ne 0) { Write-Output "COMPILE FAILED exit=$LASTEXITCODE"; exit 1 }

Write-Output "=== run ==="
& "$jdk\bin\java.exe" "-Dfile.encoding=UTF-8" "-DprojectRoot=$root" -cp "$stubOut;$out;$std;$androidJar" CalTestKt 2>&1 | Out-String | Write-Output
Write-Output "RUN exit=$LASTEXITCODE"


