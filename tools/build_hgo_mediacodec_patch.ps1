param(
    [string]$ToolsRoot = "C:\Users\leanerdesigner\Documents\SteamPortableTools",
    [string]$GStreamerSdk = "C:\Users\leanerdesigner\Documents\SteamPortableTools\toolchains\gstreamer-android-1.26.1",
    [string]$BionicImageFs = "C:\Users\leanerdesigner\Documents\SteamPortableTools\reports\gamenative_runtime\bionic_full",
    [switch]$CopyToAssets
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$source = Join-Path $PSScriptRoot "hgo_mediacodec_gst\gsthgomediacodec.c"
$ndkRoot = Join-Path $ToolsRoot "toolchains\android-sdk\ndk\22.1.7171670"
$clang = Join-Path $ndkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android30-clang.cmd"
$readelf = Join-Path $ndkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
$llvmStrip = Join-Path $ndkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe"

foreach ($path in @($clang, $readelf, $llvmStrip, $GStreamerSdk, $BionicImageFs, $source)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing required path: $path"
    }
}

$buildRoot = Join-Path $repoRoot "build\hgo-mediacodec-gst"
$objDir = Join-Path $buildRoot "obj"
$outDir = Join-Path $buildRoot "out"
$patchRoot = Join-Path $buildRoot "patch"
$pluginDir = Join-Path $patchRoot "usr\lib\gstreamer-1.0"
$libDir = Join-Path $BionicImageFs "usr\lib"
$linkDir = Join-Path $buildRoot "link-libs"

New-Item -ItemType Directory -Force -Path $objDir, $outDir, $pluginDir, $linkDir | Out-Null

$linkLibs = @{
    "libgstreamer-1.0.so" = "libgstreamer-1.0.so.0.2601.0"
    "libgstbase-1.0.so" = "libgstbase-1.0.so.0.2601.0"
    "libgstvideo-1.0.so" = "libgstvideo-1.0.so.0.2601.0"
    "libgobject-2.0.so" = "libgobject-2.0.so.0.8400.1"
    "libglib-2.0.so" = "libglib-2.0.so.0.8400.1"
}

foreach ($entry in $linkLibs.GetEnumerator()) {
    $sourceLib = Join-Path $libDir $entry.Value
    if (-not (Test-Path -LiteralPath $sourceLib)) {
        throw "Missing GameNative Bionic runtime library: $sourceLib"
    }
    Copy-Item -LiteralPath $sourceLib -Destination (Join-Path $linkDir $entry.Key) -Force
}

$gstArm64 = Join-Path $GStreamerSdk "arm64"
$includeFlags = @(
    "-I$(Join-Path $gstArm64 'include')",
    "-I$(Join-Path $gstArm64 'include\gstreamer-1.0')",
    "-I$(Join-Path $gstArm64 'lib\gstreamer-1.0\include')",
    "-I$(Join-Path $gstArm64 'include\glib-2.0')",
    "-I$(Join-Path $gstArm64 'lib\glib-2.0\include')"
)

$defines = @(
    '-DPACKAGE=\"hgo-mediacodec\"',
    '-DPACKAGE_VERSION=\"0.1\"',
    "-DGST_USE_UNSTABLE_API"
)

$obj = Join-Path $objDir "gsthgomediacodec.o"
$commonFlags = @(
    "-fPIC",
    "-Wall",
    "-Wextra",
    "-Wno-unused-parameter",
    "-Wno-deprecated-declarations",
    "-O2"
) + $defines + $includeFlags

& $clang @commonFlags -c $source -o $obj
if ($LASTEXITCODE -ne 0) {
    throw "Compile failed"
}

$plugin = Join-Path $outDir "libgsthgomediacodec.so"
$linkFlags = @(
    "-shared",
    "-o", $plugin,
    "-Wl,-soname,libgsthgomediacodec.so",
    "-Wl,--no-undefined",
    "-L$linkDir",
    "-lgstvideo-1.0",
    "-lgstbase-1.0",
    "-lgstreamer-1.0",
    "-lgobject-2.0",
    "-lglib-2.0",
    "-lmediandk",
    "-landroid",
    "-llog",
    "-ldl",
    "-latomic",
    "-lm"
)

& $clang $obj @linkFlags
if ($LASTEXITCODE -ne 0) {
    throw "Link failed"
}

& $llvmStrip --strip-unneeded $plugin
Copy-Item -LiteralPath $plugin -Destination (Join-Path $pluginDir "libgsthgomediacodec.so") -Force

$patchArchive = Join-Path $outDir "hgo_mediacodec.tzst"
if (Test-Path -LiteralPath $patchArchive) {
    Remove-Item -LiteralPath $patchArchive -Force
}
tar --zstd -cf $patchArchive -C $patchRoot usr

Write-Host ""
Write-Host "Built plugin: $plugin"
Get-Item -LiteralPath $plugin | Select-Object FullName, Length
Write-Host ""
Write-Host "Dynamic dependencies:"
& $readelf -d $plugin | Select-String -Pattern "NEEDED|SONAME" | ForEach-Object { $_.Line.Trim() }
Write-Host ""
Write-Host "Patch archive: $patchArchive"
Get-Item -LiteralPath $patchArchive | Select-Object FullName, Length

if ($CopyToAssets) {
    $assetsDir = Join-Path $repoRoot "app\src\main\assets"
    New-Item -ItemType Directory -Force -Path $assetsDir | Out-Null
    Copy-Item -LiteralPath $patchArchive -Destination (Join-Path $assetsDir "hgo_mediacodec.tzst") -Force
    Write-Host "Copied patch archive to app assets."
}
