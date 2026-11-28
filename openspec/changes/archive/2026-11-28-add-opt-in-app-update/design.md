# Design: add-opt-in-app-update

## Context

LeanCast targets sideloaded Android TV / Fire TV / set-top boxes (`minSdk 21`, `targetSdk 28`), with a large share of users on mainland China home broadband where direct connections to `api.github.com` and `github.com/.../releases/download/...` frequently time out. At the same time, LeanCast's core brand promise is privacy and minimalism: zero telemetry, zero ads, zero background cloud traffic, and a ~5 MB pure-Kotlin APK with no native libraries.

Using a first-party distribution subdomain (`dl.solocraft.app`) with a clean `/<app>/...` convention avoids untrusted third-party public GitHub proxies, gives full operational control, and scales cleanly across future SoloCraft apps.

## Goals / Non-Goals

**Goals**
- Preserve 100% zero outbound update traffic when the opt-in toggle is OFF (default).
- Never run update polling in `ReceiverService` (keep idle standby memory/CPU unchanged).
- Make update checks resilient to GitHub API rate limits and China network conditions via static `latest.json` + first-party `dl.solocraft.app` primary source + direct GitHub Releases fallback.
- Use a multi-app, cross-platform `latest.json` schema (`assets.<target>`) reusable by future apps.
- Guarantee binary integrity (SHA-256 verification) before launching the Android package installer.
- Keep zero third-party networking/serialization dependencies (JDK `HttpURLConnection`, `MessageDigest`, `org.json`, `androidx.core.content.FileProvider`).

**Non-Goals**
- Background silent auto-install or periodic timers in `ReceiverService`.
- Pre-release / beta channel switching (GitHub's `/releases/latest/` tracks stable releases only).
- Supporting downgrades or non-matching signing keys.
- Third-party public GitHub proxies or custom proxy settings UI.

## Decisions

1. **Static cross-platform `latest.json` attached to GitHub Releases (instead of GitHub REST API).**
   - Schema (reusable across Android, macOS, Windows apps):
     ```json
     {
       "version": "0.4.0",
       "changelog": "Markdown or plain-text release notes",
       "assets": {
         "android-universal": {
           "file": "LeanCast-0.4.0.apk",
           "sha256": "<64-char-lowercase-hex>"
         }
       }
     }
     ```
   - LeanCast reads `assets["android-universal"]`.
   - Rationale: Avoids unauthenticated GitHub API `60/hr/IP` limits, parses natively on Android via `org.json.JSONObject` with zero added dependencies, and generalizes to multi-architecture / multi-OS apps under `solocraft.app`.

2. **First-party `dl.solocraft.app` primary + direct GitHub fallback (`UpdateUrlResolver` in `domain/update`).**
   - Manifest candidates (in order):
     1. `https://dl.solocraft.app/leancast/latest.json`
     2. `https://github.com/addozhang/leancast/releases/latest/download/latest.json`
   - APK candidates (in order, for `version` + `file`):
     1. `https://dl.solocraft.app/leancast/v<version>/<file>`
     2. `https://github.com/addozhang/leancast/releases/download/v<version>/<file>`
   - Each attempt validates HTTP `200..299` (following redirects up to 5 hops) and validates payload structure (valid SemVer `version`, single-segment `.apk` filename without `/` or `..`, and 64-hex `sha256`). Any error page (even with HTTP 200 HTML) fails validation and advances to the fallback source.
   - Connect timeout `8 s`, read timeout `15 s` for manifest; connect timeout `10 s`, read timeout `30 s` per read for APK stream.
   - Because the client only speaks `dl.solocraft.app/leancast/...`, the backend behind `dl.solocraft.app` can later migrate from a Cloudflare Worker reverse proxy to R2/CDN with zero client changes.

3. **Reference Cloudflare Worker for `dl.solocraft.app` (deployed outside this repo).**
   - Strict repo allowlist prevents open-proxy abuse; streams response body directly without buffering large binaries in memory; contains no secrets.
   - Reference snippet:
     ```js
     const APPS = {
       leancast: "addozhang/leancast",
     };
     const SAFE_SEGMENT = /^[A-Za-z0-9._-]+$/;

     export default {
       async fetch(request) {
         if (request.method !== "GET" && request.method !== "HEAD") {
           return new Response("Method Not Allowed", { status: 405 });
         }
         const parts = new URL(request.url).pathname.split("/").filter(Boolean);
         const repo = APPS[parts[0]];
         if (!repo) return new Response("Not Found", { status: 404 });

         let upstream;
         let cacheControl;
         if (parts.length === 2 && parts[1] === "latest.json") {
           upstream = `https://github.com/${repo}/releases/latest/download/latest.json`;
           cacheControl = "public, max-age=300";
         } else if (
           parts.length === 3 &&
           /^v[A-Za-z0-9._-]+$/.test(parts[1]) &&
           SAFE_SEGMENT.test(parts[2])
         ) {
           upstream = `https://github.com/${repo}/releases/download/${parts[1]}/${parts[2]}`;
           cacheControl = "public, max-age=31536000, immutable";
         } else {
           return new Response("Not Found", { status: 404 });
         }

         const res = await fetch(upstream, {
           method: request.method,
           redirect: "follow",
           headers: { "User-Agent": "solocraft-dl/1" },
         });
         const headers = new Headers(res.headers);
         if (res.ok) headers.set("Cache-Control", cacheControl);
         return new Response(res.body, { status: res.status, headers });
       },
     };
     ```

4. **SHA-256 verification before install.**
   - APK is streamed to `File(cacheDir, "updates/${asset.file}.part")` while feeding `MessageDigest.getInstance("SHA-256")`.
   - Download progress callbacks are strictly monotonic (`percent > lastPercent`) so fallback retries never make the progress bar jump backwards.
   - After stream EOF, computed hex digest is compared case-insensitively with `asset.sha256`. On mismatch, `.part` is deleted immediately and an error is reported. On match, `.part` is renamed to `${asset.file}` and stale APKs in `cacheDir/updates/` are pruned.

5. **Installer handoff (`FileProvider` + `REQUEST_INSTALL_PACKAGES`).**
   - Declare `androidx.core.content.FileProvider` with authority `${applicationId}.fileprovider` exposing `cache-path` `updates/`.
   - On API 26+, check `packageManager.canRequestPackageInstalls()` first. If `false`, launch `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` (`package:$packageName`) and inform the user to grant permission and retry.
   - Launch `Intent(Intent.ACTION_VIEW)` with `setDataAndType(contentUri, "application/vnd.android.package-archive")` and `FLAG_GRANT_READ_URI_PERMISSION or FLAG_ACTIVITY_NEW_TASK`.
   - Catch `ActivityNotFoundException` / `SecurityException` and display a fallback message pointing to `solocraft.app/leancast` for manual install.

6. **Throttled foreground-only automatic check.**
   - When `settings.updateCheckEnabled == true`, `MainActivity` triggers a single background-thread check on resume only if `now - settings.lastUpdateCheckMs >= 6 hours` (pure policy in `domain/update/UpdateCheckThrottle`).
   - Turning the toggle ON in the modal immediately allows checking and triggers a fresh check.
   - Turning the toggle OFF clears cached "available update" UI state and blocks all future requests.
   - `ReceiverService` has zero update logic.

7. **Pure-Kotlin domain separation (TDD).**
   - `SemVer`: parses `v?major.minor.patch(-prerelease)?`, implements `Comparable<SemVer>` (stable > prerelease of same core version; strict `remote > current` triggers update).
   - `UpdateManifest`: data class (`version`, `file`, `sha256`, `changelog`) + `validate()` (non-blank SemVer, safe `.apk` filename, 64-char hex `sha256`).
   - `UpdateUrlResolver`: builds ordered `[dl.solocraft.app, github.com]` candidate URLs for `latest.json` and versioned APK files.
   - `UpdateCheckThrottle`: pure predicate `shouldAutoCheck(enabled, lastCheckMs, nowMs, intervalMs)`.

## Risks / Trade-offs

- **[`dl.solocraft.app` Worker outage or DNS issue]** → Direct GitHub Releases URL is always the automatic second candidate; Cloudflare rate-limiting rules can protect against abuse.
- **[Signing key mismatch on existing sideloaded installs]** → Android rejects in-place upgrades if the installed APK was signed with a different key (e.g. debug key vs CI release keystore). CI `KEYSTORE_BASE64` must stay consistent across releases.
- **[Restricted Chinese TV firmware blocking third-party `ACTION_VIEW` APK install]** → Catch launcher exceptions cleanly and display the manual download URL (`solocraft.app/leancast`).
- **[`REQUEST_INSTALL_PACKAGES` added to manifest]** → Required by Android 8+ for in-app APK install; dormant unless the user explicitly enables updates and triggers an install.

## Migration Plan

Purely additive settings (`update_check_enabled` defaults to `false`, `last_update_check_ms` defaults to `0`). Existing users upgrading to this version see zero behavior change until they opt in. Rollback = reinstall previous APK.
