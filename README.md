# ဖီးနစ် VPN (Phoenix VPN)

A WireGuard VPN for Android, built for lines where WARP keeps dying
(MPT in particular). No scanner inside. It pulls a published list of
verified endpoints, measures them with real handshakes on your own
line, and connects to the best one.

- Package: `com.phoenix.phoenixvpn`
- Display name: **ဖီးနစ် VPN**
- Min SDK 24 (Android 7.0), target SDK 36, one universal APK
- [Releases](https://github.com/tugyix05/Phoenix-VPN/releases)

## What it does

- **Endpoints come from a feed.** The app fetches the top-10 list (see
  below) instead of shipping a fixed list that goes stale.
- **It measures before it connects.** The endpoint sheet runs real
  handshake draws. A row shows ● LIVE once two draws in a row agree.
- **It picks in this order:** your explicit choice, published top-3,
  last-working, the rest, bundled, manual.
- **The switch is yours.** The app never flips the master switch by
  itself. Turning it off manually (including "ရပ်ရန်" in the
  notification) sticks until you turn it back on.
- **It connects and stops by itself.** Validated internet means on.
  No usable internet means off.
- **A stall does not mean a new endpoint.** When the tunnel stalls, the
  app re-draws the same endpoint until it handshakes again. Endpoints
  don't die. Flows do.
- **Updates don't re-download.** The app checks on every open. A
  downloaded update stays on the phone until it installs, so a failed
  install just retries the same file.

## Endpoint feed

The feed is published by
[Warp IP Scanner](https://github.com/tugyix05/Warp-IP-Scanner):

`GET https://raw.githubusercontent.com/tugyix05/Warp-IP-Scanner/main/endpoints.json`

Schema: `{"v":1,"updated_at":"<ISO8601>","isp":"MPT","endpoints":[{"ip","port","ms","jitter_ms"}]}`

If the feed can't be reached, the app falls back in this order:
fetched, cached, bundled, one manual endpoint slot.

## Usage

Tap the hero button to connect or disconnect. Open the endpoint sheet
to pick an endpoint, and hit Refresh there to measure live handshake
ms. When a new release is out, a banner shows up. It doesn't block
anything. Tap Update to download and install.

## Privacy

Firebase Analytics and Crashlytics are in the app for anonymous usage
counts and crash reports.

## Building

CI builds every push to `main`
(`.github/workflows/release.yml`) and holds the APK as a workflow
artifact — no release yet, so no update appears in the app. The
release is published only after that build is VirusTotal-scanned and
appealed (`.github/workflows/publish.yml`); only then does the release
APK land in
[Releases](https://github.com/tugyix05/Phoenix-VPN/releases)
and the in-app update appear.

Local build (Android SDK and JDK 21 required):

```sh
./gradlew assembleRelease
```

Signing uses repo secrets. Nothing signing-related lives in this repo.
