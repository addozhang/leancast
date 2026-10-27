# Proposal: Document device compatibility matrix + community report flow

## Why

LeanCast's architecture (pure-Kotlin, no native libs, `minSdk 21`) makes it
run on a very wide range of TVs and boxes, but nothing in the repo tells users
which devices are verified, which are expected to work, and which cannot run
Android APKs at all. Open-sourcing without this invites duplicated "does it
work on X?" issues and loses field results.

## What Changes

- README gains a tiered **Supported devices** section (✅ verified / ✅ expected / ⚠️ experimental / ❌ unsupported) matching the documented technical baseline.
- A GitHub **issue template** (`device-compatibility.yml`) structures community
  compatibility reports (device, OS family, Android version, app version,
  sender app, result, details) so results are actionable and comparable.

## Capabilities

### New Capabilities

- `device-compat-docs`: The documented compatibility matrix and the community
  reporting flow that feeds it.

### Modified Capabilities

(none)

## Impact

- `README.md` (new section), `.github/ISSUE_TEMPLATE/device-compatibility.yml` (new).
- No application code, protocol, or build changes.
