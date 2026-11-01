# Radio Arkiv — a fork of RadioDroid

**Radio Arkiv** (formerly "Radio") is an unofficial fork of [RadioDroid](https://github.com/segler-alex/RadioDroid)
by segler-alex and contributors, an Android internet-radio app built on the free
[Radio Browser](https://www.radio-browser.info) station directory.
Current version: **1.0.6** (based on RadioDroid 0.86).
It installs alongside upstream (`applicationId io.github.arkivx0.radio`).
All credit for the app itself goes to the RadioDroid project. Licensed GPL-3.0 like upstream.

<p align="center">
  <img src="docs/screenshots/stations.png" width="250" alt="Station list" />
  <img src="docs/screenshots/player.png" width="250" alt="Player with time-shift controls" />
  <img src="docs/screenshots/recordings.png" width="250" alt="Player with the Recordings tab" />
  <img src="docs/screenshots/favorite.png" width="250" alt="Player with a favourite station" />
</p>
<p align="center"><em>Stations shown are a random selection picked from the public directory for screenshots and testing, not an endorsement.</em></p>

## What this fork adds
- **Offline station backup** (new in 1.0.5/1.0.6): station lists and browse results are kept on the device, so
  the app keeps working when the Radio Browser servers are unreachable (other mirrors are tried first, and a
  short hint shows when an offline copy is used). Favourites, history and recordings never depend on the servers.
- **Export / import stations backup** (Settings → Connectivity): save favourites, history and station lists to a
  JSON file and restore them later; import asks for confirmation and only adds what is missing.
- **Reliable resume after phone calls**: if playback stalls after a call, the stream is restarted automatically.
- **Incoming-call crash fix**: audio-focus loss (e.g. a call) no longer kills the service on Android 12+;
  playback pauses cleanly and resumes after the call.
- **11 skins** with swatch previews in Settings → Appearance → Theme: Omarchy / Tokyo Night (default), AMOLED Black,
  Gruvbox Dark, Catppuccin Mocha, Nord, Dracula, Enclave, Terminal (green phosphor), Vintage (tube radio),
  Light and Dark. Bundled OFL fonts: VT323 and Limelight.
- **Time-shift**: skip back 5 s (repeatable), **long-press for Back 30 s**, and **Go live**, with a behind-live indicator.
- **Skip-back buffer setting** (Settings → Playback): 1, 2, 5 (default), 10 or 30 minutes, or Auto. Sized from the station's bitrate (128 kbps if unknown); large buffers are kept on disk in the app cache, and the summary shows the approximate storage.
- **Lock-screen / media controls**: media notification with Back 5 s, Go live and Record actions.
- **Lock-screen seek bar** (new in 1.0.4): see and drag through the rewind buffer right from the lock screen or notification; drag to the end to go live.

  <img src="docs/screenshots/lockscreen-seekbar.png" width="520" alt="Lock screen player with the seek bar for the rewind buffer" />

- **Scheduled recordings**: pick station, start time, duration, once or on repeat days.
- **Silent recordings**: scheduled recordings can record without playing aloud or taking audio focus
  (per-schedule *Record silently* option, on by default; off keeps play-while-recording). There is also
  *Record now silently*. Files go to `Music/Radio` like other recordings. All recording options are in
  Settings → Recordings.
- **Open audio files with Radio Arkiv**: recordings (`Music/Radio`) and other audio files can be opened with Radio Arkiv
  and play in its player with seeking, pause/resume and lock-screen controls. In the recordings list, tap to
  play in Radio Arkiv, long-press to open with another app.
- **Open file**: the folder button in the toolbar (or ⋮ → *Open audio file…*, or the top row of the Recordings tab) picks any audio file with the system file picker (no storage
  permission needed) and plays it in Radio Arkiv.
- **Codec info line**: the full player shows the decoded source format, e.g. `AAC-LC · 44.1 kHz · stereo`.
- **Android 13/14 fixes**: recording without legacy storage permission, exact-alarm fallback,
  notification-permission prompt, unique provider authorities, working sleep timer and alarms.
- **Fixes for upstream issues**:
  [#1260](https://github.com/segler-alex/RadioDroid/issues/1260) Wi-Fi playback blocked when mobile data is on,
  [#1261](https://github.com/segler-alex/RadioDroid/issues/1261) favourite star not updating,
  [#1241](https://github.com/segler-alex/RadioDroid/issues/1241) crash searching short names like "rad",
  [#1222](https://github.com/segler-alex/RadioDroid/issues/1222) silent failure adding an alarm,
  [#349](https://github.com/segler-alex/RadioDroid/issues/349) stream format details.
- **About screen** works again and shows this fork's info.

## Themes
Omarchy (Tokyo Night), Light, Dark, AMOLED Black, Gruvbox Dark, Catppuccin Mocha, Nord, Dracula, Enclave, Terminal and Vintage (Settings → Appearance → Theme).

<p align="center">
  <img src="docs/screenshots/theme-grid.png" width="800" alt="All 11 themes" />
</p>

## Install
Download `RadioArkiv.apk` from the [Releases](../../releases) page and sideload it (allow "install unknown apps").
It does not replace RadioDroid. Builds are the `free` flavor (no proprietary services, no analytics).

## Download & verify
Download `RadioArkiv.apk` from [Releases](../../releases). APKs are signed with the Radio Arkiv release key.

- Signing certificate SHA-256:
  `291e1bccf7a19a6d0542a0a4c1af02aef42d305216534d798a85dc4187254861`
- APK SHA-256 for 1.0.6:
  `72311a241c4d49f045ff35fdc30363db7265a4ee20b1c7645b06c84c16e25082`

Verify before installing:
```
sha256sum RadioArkiv.apk
apksigner verify --print-certs RadioArkiv.apk   # "Signer #1 certificate SHA-256 digest" must match
```
Each release lists its own APK SHA-256; the certificate fingerprint stays the same for all updates.

## Known limits
- HLS streams cannot be rewound (time-shift works for regular Icecast/Shoutcast streams).
- Silent recording does not support HLS streams (turn *Record silently* off for those).
- Time-shift positions are estimated from the stream bitrate; jumps can cause a short gap.

## Credits and license
Based on [RadioDroid](https://github.com/segler-alex/RadioDroid) by segler-alex and contributors.
Station data from [Radio Browser](https://www.radio-browser.info).
Free software under the [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html).
