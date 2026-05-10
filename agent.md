# GameThor Fork Notes

GameThor is a vibecoded fork of GameNative customized for the AYN Thor handheld. It keeps GameNative's core mission of running legally owned PC games on Android, but this repo is a Thor-first lab fork rather than an official upstream GameNative release.

The short version: upstream GameNative is the general Android PC-game launcher; GameThor is the experimental AYN Thor branch where controller behavior, runtime packaging, media playback, and known-good compatibility presets can move faster.

## What Stays From GameNative

- Core Android app structure, store/library flows, container management, and the GPL-3.0 license lineage come from GameNative.
- The app still targets legally owned games. Do not use this fork for piracy, DRM bypasses, anti-cheat bypasses, online cheating, or redistribution of commercial game files.
- The package id can remain `app.gamenative` for update/data compatibility unless a change intentionally moves to a separate install line.

## What GameThor Changes

- **AYN Thor branding:** visible lab branding such as `GameNative AYN Thor AI Lab` makes experimental builds distinct from public GameNative.
- **Thor-first presets:** HGO lab presets collect tested Wine, DXVK, graphics, controller, and env combinations so users do not have to hand-enter fragile launch settings.
- **Controller bridge work:** Thor physical controls get special attention, including raw passthrough paths, private EVSHIM shared-memory handling, and controller-friendly helper overlays.
- **Runtime and video experiments:** optional imagefs patches, HGO MediaCodec work, and GStreamer/Android media experiments are used to prove hardware video playback paths on Thor.
- **DXVK and presentation testing:** the fork carries known-good and canary paths for DXVK, DRI3, Vulkan wrapper settings, and game-specific compatibility proofs.
- **Offline helper UX:** game-local helper or cheat overlays are scoped to legally owned offline single-player use, with clear on/off state and controller navigation.
- **Debuggable launch behavior:** explicit launch overrides, preset JSON/extras, and logging are treated as first-class because Thor tuning often depends on exact runtime evidence.

## How To Explain It

Use this wording when introducing the repo:

> GameThor is a vibecoded AYN Thor fork of GameNative. It keeps the upstream launcher foundation, but adds Thor-focused presets, controller bridge fixes, runtime/media experiments, and compatibility canaries for handheld use.

Avoid describing GameThor as an official GameNative release. It is a fork, a lab, and a practical compatibility playground for AYN Thor.

## Remotes

- Writable fork: `git@github.com:noeldvictor/GameThor.git`
- Upstream reference: `https://github.com/utkarshdalal/GameNative`

Always push with SSH. Do not push to upstream GameNative from this checkout. If a change becomes broadly useful beyond Thor, prepare it cleanly so it can be proposed upstream separately.

## Contribution Expectations

- Say what changed compared with upstream GameNative and why it matters on AYN Thor.
- Prefer reversible, logged, device-detectable experiments over broad hidden behavior changes.
- Record the tested device, game, commit, runtime settings, and proof logs for compatibility claims.
- Keep Thor-specific behavior isolated behind presets, package/build checks, runtime detection, or clearly named code paths.
- Leave unrelated GameNative behavior alone unless the change is necessary for the Thor goal.
