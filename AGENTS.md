# GameNative Agent Notes

These notes are for work in this GameNative fork. Keep changes practical, testable, and aimed at making legally owned offline PC games better on handheld Android devices such as AYN Thor.

## Source Control

- Writable remote is the user's fork: `origin = git@github.com:noeldvictor/GameNative.git`.
- Original project is comparison/pull-only: `upstream = https://github.com/utkarshdalal/GameNative.git`.
- Do not push to upstream. This checkout should have upstream push disabled.
- Commit GameNative source changes in this repo, not in the tools repo.
- Do not commit APK builds, runtime imagefs archives, extracted runtimes, Gradle caches, Android SDKs, or game files.

## Safety Scope

- Work only on compatibility, controller UX, media playback, offline single-player helper support, diagnostics, and runtime packaging.
- Do not patch DRM, Steam ownership checks, anti-cheat, networking, multiplayer, leaderboards, or online-service protections.
- Do not add code or docs for redistributing modified commercial game files.
- If a change touches Steam integration, keep it to normal launcher compatibility and logging. Do not alter ownership or entitlement decisions.

## Local Workflow

- On this Windows workstation, use PowerShell commands. Prefer `Get-ChildItem`, `Get-Content`, and `Select-String`; do not use `rg`.
- Before editing, check `git status --short --branch`.
- Keep changes small and reversible. If a patch is speculative, gate it behind explicit runtime detection and log clearly.
- Prefer app logs and `logcat` proof over guessing. Record exact env vars and loaded library/plugin names when debugging GameNative launches.
- For AYN Thor UI work, scrcpy mirror mode is the practical companion path; blind ADB form entry is unreliable.

## Build Notes

- Repo-local JDK used by the tools workspace: `C:\Users\leanerdesigner\Documents\SteamPortableTools\toolchains\jdk-21.0.11+10`.
- Repo-local Android SDK used by the tools workspace: `C:\Users\leanerdesigner\Documents\SteamPortableTools\toolchains\android-sdk`.
- Gradle also needs Android SDK configuration through `local.properties`. Use forward slashes in `sdk.dir` and `ndk.dir`; backslashes become bad Java escapes in generated `BuildConfig`.
- If `local.properties` is missing, do not claim the APK was built.
- A source compile check can be attempted with:

```powershell
$env:JAVA_HOME='C:\Users\leanerdesigner\Documents\SteamPortableTools\toolchains\jdk-21.0.11+10'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:compileDebugJavaWithJavac
```
- Build the optional Android MediaCodec plugin archive with:

```powershell
.\tools\build_gstreamer_androidmedia_patch.ps1 -CopyToAssets
```

- The build script expects the sparse GStreamer source at exact tag `1.26.1`, matching the bundled runtime libraries `libgstreamer-1.0.so.0.2601.0`. Do not build from GStreamer `main` for this experiment.
- `app\src\main\assets\gstreamer_androidmedia.tzst` is generated and ignored. Rebuild it locally before assembling a test APK.
- A custom APK cannot update the user's installed Thor GameNative unless it is signed with the same release/debug lineage. Do not uninstall the existing app just to force an install unless the user explicitly accepts losing GameNative app data/profiles.

## Runtime Imagefs Rules

- GameNative media/runtime behavior is not only Android app code. The app downloads or extracts imagefs archives such as `imagefs_gamenative.txz`, `imagefs_bionic.txz`, and runtime patch archives.
- Do not assume an env var can enable a plugin that is not present in imagefs. Prove the plugin exists in `usr/lib/gstreamer-1.0` and appears in logs.
- Keep Bionic and glibc paths separate. A Bionic Android plugin is not a safe drop-in for the glibc imagefs.
- For optional runtime experiments, prefer a separate archive such as `gstreamer_androidmedia.tzst` and extract it only when present. Missing optional archives must be non-fatal.
- If adding executable libraries to imagefs, set permissions and log the installed path.

## Vulkan / DXVK Presentation

- If a game has audio and a mapped window but a black screen under DXVK, inspect logcat for `Present`, `DRI3`, `DXGI`, `swapchain`, and `BadImplementation` before changing game files.
- Dragon Quest Heroes proved a useful pattern: WineD3D/OpenGL showed the title and movies, while DXVK/Vulkan reached D3D11 swapchain creation and then failed in GameNative's X Present path.
- The Present extension must not reject normal XCB Present requests such as `NotifyMSC` or `QueryCapabilities`. If Vulkan still fails, add exact minor-opcode logs first and then implement the missing protocol request minimally and truthfully.
- The first DQH `.hgo` canary after minimal `NotifyMSC`/`QueryCapabilities` handling still rendered black, even though the old obvious Present `BadImplementation` disappeared and DXVK recreated a `1280x720` swapchain. Do not keep patching random Present requests unless logs show a new missing opcode.
- Before deeper code changes, strip high-risk Vulkan defaults in canary launches: remove `MESA_VK_WSI_PRESENT_MODE=mailbox`, remove `WRAPPER_MAX_IMAGE_COUNT=0`, bypass the LSFG implicit layer if possible, and test DRI3 on/off.
- External launch overrides must preserve explicit keys, even when the supplied value equals GameNative's default. DQH canary launches were accidentally unable to force `dxwrapper=dxvk`, `useDRI3=true`, or default-valued controller/render fields over an existing WineD3D saved profile until `ContainerData.explicitOverrideKeys` was added.
- External JSON launch parsing must include `graphicsDriverConfig`; otherwise Vulkan canaries fall back to blank wrapper settings and produce misleading env like `MESA_VK_WSI_PRESENT_MODE=`.
- Do not export blank Vulkan wrapper env vars. Empty values such as `MESA_VK_WSI_PRESENT_MODE=` are not neutral; Mesa logs them as invalid and they muddy the failure signal.
- Latest DQH `.hgo` FIFO canary after override/env cleanup still black-screens. It now proves the cleaned env is applied (`MESA_VK_WSI_PRESENT_MODE=fifo`, wrapper Vulkan `1.3.0`), but D3D9 DXVK logs `No adapters found` while the D3D11/DXGI path sees a `Wrapper()` adapter and can start a 1x1 swapchain. Next code work should explain that D3D9/D3D11 adapter split before more random present-mode changes.
- DQH visible Vulkan canary: DXVK `1.10.3` plus `useDRI3=false` produced a visible title screen with wrapper Vulkan `1.3.0` and FIFO present mode. In this profile GameNative adds `MESA_VK_WSI_DEBUG=sw`; D3D11 then resizes to `1280x720`. DXVK `2.7.1` still rejects the D3D9 path over missing `shaderInt64`, and DXVK `1.10.3` with DRI3 on tends to stick at `1x1`.
- DQH cheat-helper Vulkan canary: `1.10.3`, `async-1.10.3`, and `1.11.1-sarek` all reached a visible title screen with DRI3 off and `WINEDLLOVERRIDES=dinput8=n,b`. Prefer `async-1.10.3` for the next regular-UI profile because it keeps the visible older-DXVK path and confirms `DXVK_ASYNC=1`. Keep `1.11.1-sarek` as an alternate canary because it confirms `WRAPPER_NO_PATCH_OPCONSTCOMP=1`.
- Repeat DQH DXVK canaries from the tools repo with `games\dragon-quest-heroes-slime-edition\tools\start_gamenative_dqh_dxvk_canary.ps1`. It launches `app.gamenative.hgo` by default, clears stale runtime logs unless `-PreserveDeviceLogs` is supplied, captures a screenshot, pulls logcat, DXVK logs, and `StandardCheatMenu.log`, then stops the app unless `-KeepRunning` is supplied.
- ADB-launching public `app.gamenative` with the same DQH canary can still reach a visible title screen and load the helper, but that is not DXVK proof unless `DQH_dxgi.log` exists and logcat shows DXVK env such as `DXVK_ASYNC=1`. The public package may keep saved-profile defaults or lack the fork's explicit override behavior.
- Keep WineD3D as the compatibility fallback for affected games until DXVK is proven visible in a fresh launch.

## Android MediaCodec / GStreamer Focus

- Immediate DQH goal is Vulkan plus hardware-decoded 720p video in the same run. Do not treat WineD3D, software decode, or sub-720p movie encodes as the destination.
- Current fork patch `0b41a266` wires the optional Bionic `gstreamer_androidmedia.tzst` path and app-side GStreamer Android callback helpers.
- `tools\build_gstreamer_androidmedia_patch.ps1` now builds a real arm64 Bionic `usr/lib/gstreamer-1.0/libgstandroidmedia.so` and packages it into `gstreamer_androidmedia.tzst`.
- Hardware decode is still not proven until a same-signed APK installs on Thor, the plugin loads, and logcat shows actual `androidmedia` / `amcviddec-*` / `MediaCodec` usage during DQH movie playback.
- `WINE_GST_NO_GL=0` should be enabled automatically only when `libgstandroidmedia.so` exists. If the plugin is absent, keep the existing `WINE_GST_NO_GL=1` behavior.
- DQH movie lag diagnosis should look for actual decoder lines, not just MP4 acceptance. MP4 through Wine/GStreamer can still be software decode.
- Latest `.hgo` hardware-video probe proves the plugin archive is installed but not usable yet: `libgstandroidmedia.so` fails to initialize because the Wine/GStreamer child process cannot resolve `JNI_CreateJavaVM`, `JNI_GetCreatedJavaVMs`, or `libdvm.so`. The next fix target is JNI/application-class-loader access for androidmedia, or an app-side MediaCodec bridge. More env vars alone will not create hardware decode.
- Online research update, 2026-05-06: upstream GStreamer `androidmedia` expects Android app/JNI plumbing in the loader process, including `gst_android_get_java_vm` and `gst_android_get_application_class_loader`. The faster implementation target is now a native HGO MediaCodec GStreamer plugin or bridge for H.264 first, using Android NDK `AMediaCodec` directly and logging `AMediaCodec_getName` as the hardware proof. Keep the upstream `androidmedia` JNI/class-loader path as a secondary research track. The paired tools-repo report is `games\dragon-quest-heroes-slime-edition\reports\vulkan_hardware_video_research_20260506.md`.
- Native probe checkpoint, 2026-05-06: `tools\probe_mediacodec_native.ps1 -RunOnThor -ConfigureStart` builds and runs a plain arm64 NDK MediaCodec probe on Thor. It proved `video/avc` chooses `c2.qti.avc.decoder`, and `AMediaCodec_configure`, `AMediaCodec_start`, and `AMediaCodec_stop` all returned `AMEDIA_OK` for `1280x720`. This validates the native HGO plugin direction before writing the GStreamer element.
- HGO decoder proof, 2026-05-06: `tools\build_hgo_mediacodec_patch.ps1 -CopyToAssets` builds `libgsthgomediacodec.so` and packages ignored asset `app\src\main\assets\hgo_mediacodec.tzst`. The first element is `hgomediacodech264dec`; it uses Android NDK `AMediaCodec` directly, enables high rank only with `HGO_GST_MEDIACODEC_ENABLE=1`, and logs the selected decoder name/output format.
- DQH `.hgo` proof run `reports\gamenative_runtime\dqh_dxvk_async1103_dri3off_cheats_20260506_114137` reached `HardwareDecodeStatus=PROVEN_HGO_MEDIACODEC_H264`, with `HGO MediaCodec decoder selected: c2.qti.avc.decoder`, `HGO MediaCodec raw output negotiated: NV12 1280x720`, and no active `libgstandroidmedia.so` failure.
- When `libgsthgomediacodec.so` is present, treat upstream `libgstandroidmedia.so` as superseded for HGO tests. Skip installing it, rename it to `.hgo-disabled`, or delete an active duplicate if the disabled copy already exists. The old androidmedia plugin still fails in Wine/GStreamer child processes because JNI/class-loader symbols are unavailable there.
- DQH full `mdat\Low` proof pack is now MP4/H.264-in-`.dat` at 1280x720, size `3,045,618,813 -> 1,403,079,162` bytes, applied to both PC and Thor. This is the current hardware-video canary media state.
- Repeat DQH hardware-video proof with `games\dragon-quest-heroes-slime-edition\tools\start_gamenative_dqh_dxvk_canary.ps1 -HardwareVideoProbe -CaptureSeconds 45` from the tools repo. A pass needs visible DXVK, helper logs, movie open, and real Android decoder evidence in `hardware_decode_probe.txt`.

## Controller / Handheld UX

- Controller usability is a release gate for handheld work.
- For game helper overlays launched through GameNative, controller menus must open, navigate, confirm, back out, and close without keyboard/mouse.
- Avoid adding extra handheld hotkey chords unless the user asks. The preferred cheat-menu toggle for game-local helpers is `L3+R3`.
- For the `.hgo` debug package, keep `EVSHIM_MEM_DIR` inside the private imagefs `tmp` folder. Do not use `/sdcard/GameNativeHGO` for controller bridge files; stale UID/permission ownership can leave Wine reading dead `gamepad*.mem` state even when the game launches.

## Reports

- Durable cross-project notes live in the tools repo, especially `docs/gamenative-mediacodec-wiring.md`.
- When making a GameNative change for a specific game, record the tested game, device, GameNative version/commit, env vars, and log evidence in the tools repo report too.
