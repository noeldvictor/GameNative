param(
    [string]$ToolsRoot = "C:\Users\leanerdesigner\Documents\SteamPortableTools",
    [string]$GStreamerSdk = "C:\Users\leanerdesigner\Documents\SteamPortableTools\toolchains\gstreamer-android-1.26.1",
    [string]$BionicImageFs = "C:\Users\leanerdesigner\Documents\SteamPortableTools\reports\gamenative_runtime\bionic_full",
    [string]$AndroidMediaSource = "C:\Users\leanerdesigner\Documents\SteamPortableTools\_src\gstreamer\subprojects\gst-plugins-bad\sys\androidmedia",
    [string]$ExpectedGStreamerVersion = "1.26.1",
    [switch]$CopyToAssets
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$ndkRoot = Join-Path $ToolsRoot "toolchains\android-sdk\ndk\22.1.7171670"
$clang = Join-Path $ndkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android26-clang.cmd"
$readelf = Join-Path $ndkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
$llvmStrip = Join-Path $ndkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe"

foreach ($path in @($clang, $readelf, $llvmStrip, $GStreamerSdk, $BionicImageFs, $AndroidMediaSource)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing required path: $path"
    }
}

$sourceRootProbe = (Resolve-Path -LiteralPath $AndroidMediaSource).Path
while ($sourceRootProbe -and -not (Test-Path -LiteralPath (Join-Path $sourceRootProbe ".git"))) {
    $parent = Split-Path -Path $sourceRootProbe -Parent
    if ($parent -eq $sourceRootProbe) {
        $sourceRootProbe = $null
        break
    }
    $sourceRootProbe = $parent
}

if ($sourceRootProbe) {
    $sourceVersion = (& git -C $sourceRootProbe describe --tags --always --dirty 2>$null).Trim()
    if ($LASTEXITCODE -eq 0 -and $sourceVersion -ne $ExpectedGStreamerVersion) {
        throw "GStreamer source is '$sourceVersion', expected exact tag '$ExpectedGStreamerVersion'. Fetch/checkout the matching tag before building."
    }
}

$buildRoot = Join-Path $repoRoot "build\hgo-androidmedia"
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
    "libgstgl-1.0.so" = "libgstgl-1.0.so.0.2601.0"
    "libgstpbutils-1.0.so" = "libgstpbutils-1.0.so.0.2601.0"
    "libgstaudio-1.0.so" = "libgstaudio-1.0.so.0.2601.0"
    "libgstvideo-1.0.so" = "libgstvideo-1.0.so.0.2601.0"
    "libgstphotography-1.0.so" = "libgstphotography-1.0.so.0.2601.0"
    "libgmodule-2.0.so" = "libgmodule-2.0.so.0.8400.1"
    "libgobject-2.0.so" = "libgobject-2.0.so.0.8400.1"
    "libglib-2.0.so" = "libglib-2.0.so.0.8400.1"
    "libgraphene-1.0.so" = "libgraphene-1.0.so.0.1000.8"
}

foreach ($entry in $linkLibs.GetEnumerator()) {
    $source = Join-Path $libDir $entry.Value
    if (-not (Test-Path -LiteralPath $source)) {
        throw "Missing GameNative Bionic runtime library: $source"
    }
    Copy-Item -LiteralPath $source -Destination (Join-Path $linkDir $entry.Key) -Force
}

$gstArm64 = Join-Path $GStreamerSdk "arm64"
$includeFlags = @(
    "-I$AndroidMediaSource",
    "-I$(Join-Path $AndroidMediaSource 'jni')",
    "-I$(Join-Path $AndroidMediaSource 'ndk')",
    "-I$(Join-Path $gstArm64 'include')",
    "-I$(Join-Path $gstArm64 'include\gstreamer-1.0')",
    "-I$(Join-Path $gstArm64 'lib\gstreamer-1.0\include')",
    "-I$(Join-Path $gstArm64 'include\glib-2.0')",
    "-I$(Join-Path $gstArm64 'lib\glib-2.0\include')",
    "-I$(Join-Path $gstArm64 'include\graphene-1.0')",
    "-I$(Join-Path $gstArm64 'lib\graphene-1.0\include')",
    "-I$(Join-Path $gstArm64 'include\orc-0.4')"
)

$defines = @(
    "-DHAVE_JNI_H",
    "-DHAVE_NDKMEDIA",
    "-DPLUGIN_NAME=androidmedia",
    '-DPLUGIN_DESCRIPTION=\"AndroidMediaPlugin\"',
    '-DPACKAGE=\"gst-plugins-bad\"',
    '-DPACKAGE_VERSION=\"1.26.1\"',
    '-DGST_LICENSE=\"LGPL\"',
    '-DGST_PACKAGE_NAME=\"GStreamerBadPlugIns\"',
    '-DGST_PACKAGE_ORIGIN=\"https://gstreamer.freedesktop.org/\"',
    "-DGST_USE_UNSTABLE_API"
)

$commonFlags = @(
    "-fPIC",
    "-Wall",
    "-Wno-deprecated-declarations",
    "-Wno-unused-function",
    "-Wno-unused-variable",
    "-Wno-incompatible-pointer-types-discards-qualifiers"
) + $defines + $includeFlags

$sources = @(
    "gstamcaudiodec.c",
    "gstamc.c",
    "gstamc-codec.c",
    "gstamc-format.c",
    "gstamcsurfacetexture.c",
    "gstamcvideodec.c",
    "gstamcvideoenc.c",
    "gstahcsrc.c",
    "gstahssrc.c",
    "gst-android-graphics-imageformat.c",
    "gst-android-hardware-camera.c",
    "gst-android-hardware-sensor.c",
    "gstjniutils.c",
    "jni\gstamc-jni.c",
    "jni\gstamc-codec-jni.c",
    "jni\gstamc-codeclist-jni.c",
    "jni\gstamc-format-jni.c",
    "jni\gstamcsurface.c",
    "jni\gstamcsurfacetexture-jni.c",
    "ndk\gstamc-codec-ndk.c",
    "ndk\gstamc-format-ndk.c"
)

$objects = @()
foreach ($rel in $sources) {
    $src = Join-Path $AndroidMediaSource $rel
    if (-not (Test-Path -LiteralPath $src)) {
        throw "Missing source: $src"
    }
    $objName = ($rel -replace '[\\/]', '_') -replace '\.c$', '.o'
    $obj = Join-Path $objDir $objName
    & $clang @commonFlags -c $src -o $obj
    if ($LASTEXITCODE -ne 0) {
        throw "Compile failed: $rel"
    }
    $objects += $obj
}

$plugin = Join-Path $outDir "libgstandroidmedia.so"
$linkFlags = @(
    "-shared",
    "-o", $plugin,
    "-Wl,-soname,libgstandroidmedia.so",
    "-Wl,--no-undefined",
    "-L$linkDir",
    "-lgstgl-1.0",
    "-lgstpbutils-1.0",
    "-lgstaudio-1.0",
    "-lgstvideo-1.0",
    "-lgstphotography-1.0",
    "-lgstbase-1.0",
    "-lgstreamer-1.0",
    "-lgmodule-2.0",
    "-lgobject-2.0",
    "-lglib-2.0",
    "-lgraphene-1.0",
    "-landroid",
    "-llog",
    "-ldl",
    "-latomic",
    "-lm"
)

& $clang @objects @linkFlags
if ($LASTEXITCODE -ne 0) {
    throw "Link failed"
}

& $llvmStrip --strip-unneeded $plugin
Copy-Item -LiteralPath $plugin -Destination (Join-Path $pluginDir "libgstandroidmedia.so") -Force

$patchArchive = Join-Path $outDir "gstreamer_androidmedia.tzst"
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
    Copy-Item -LiteralPath $patchArchive -Destination (Join-Path $assetsDir "gstreamer_androidmedia.tzst") -Force
    Write-Host "Copied patch archive to app assets."
}
