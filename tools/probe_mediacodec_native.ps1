param(
    [string]$NdkRoot = "C:\Users\leanerdesigner\Documents\SteamPortableTools\toolchains\android-sdk\ndk\22.1.7171670",
    [string]$Adb = "C:\Users\leanerdesigner\Documents\SteamPortableTools\tools\platform-tools\adb.exe",
    [string]$Serial = "c3ca0370",
    [string]$Mime = "video/avc",
    [int]$Width = 1280,
    [int]$Height = 720,
    [switch]$ConfigureStart,
    [switch]$RunOnThor
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$src = Join-Path $PSScriptRoot "mediacodec_probe\hgo_mediacodec_probe.c"
$outDir = Join-Path $repoRoot "build\hgo-mediacodec-probe"
$out = Join-Path $outDir "hgo_mediacodec_probe"
$clang = Join-Path $NdkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android30-clang.cmd"

foreach ($path in @($clang, $src)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing required path: $path"
    }
}

New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$compileArgs = @(
    "-std=c11",
    "-Wall",
    "-Wextra",
    "-Werror",
    "-O2",
    "-fPIE",
    "-pie",
    "-o", $out,
    $src,
    "-landroid",
    "-lmediandk",
    "-llog"
)

& $clang @compileArgs
if ($LASTEXITCODE -ne 0) {
    throw "Native MediaCodec probe build failed with exit code $LASTEXITCODE"
}

Get-Item -LiteralPath $out | Select-Object FullName, Length, LastWriteTime

if ($RunOnThor) {
    if (-not (Test-Path -LiteralPath $Adb)) {
        throw "Missing adb: $Adb"
    }

    $remote = "/data/local/tmp/hgo_mediacodec_probe"
    & $Adb -s $Serial push $out $remote
    if ($LASTEXITCODE -ne 0) {
        throw "adb push failed with exit code $LASTEXITCODE"
    }
    & $Adb -s $Serial shell chmod 755 $remote
    if ($LASTEXITCODE -ne 0) {
        throw "adb chmod failed with exit code $LASTEXITCODE"
    }

    $runArgs = @($Mime, [string]$Width, [string]$Height)
    if ($ConfigureStart) {
        $runArgs += "--start"
    }

    & $Adb -s $Serial shell $remote @runArgs
    if ($LASTEXITCODE -ne 0) {
        throw "MediaCodec probe on Thor failed with exit code $LASTEXITCODE"
    }
}
