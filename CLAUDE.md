# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Capacitor Camera View is a Capacitor plugin that embeds a live camera feed into hybrid mobile apps. It supports iOS, Android, and Web platforms with features including photo capture, barcode detection, zoom/flash/torch control, and virtual camera support (iOS triple camera).

## Common Commands

This repo uses **pnpm**, not npm. The version is pinned via `packageManager` in
`package.json`. Never run `npm install` here — it would bypass the dependency
age policy described below and produce a stray `package-lock.json`.

```bash
# Install dependencies
pnpm install

# Build the plugin (generates docs + compiles TypeScript + bundles with Rollup)
pnpm run build

# Lint TypeScript
pnpm run lint

# Lint Swift code
pnpm run lint:ios

# Format all code (TypeScript, Java, Swift)
pnpm run fmt

# Run unit tests
pnpm run test

# Verify all platforms build correctly
pnpm run verify

# Verify individual platforms
pnpm run verify:ios      # Builds iOS with xcodebuild
pnpm run verify:android  # Builds Android with Gradle
pnpm run verify:web      # Same as pnpm run build
```

## Architecture

### Plugin Structure

This is a standard Capacitor plugin with platform-specific implementations:

- **TypeScript API** (`src/`): Plugin interface and web implementation
  - `definitions.ts` - All TypeScript types and the `CameraViewPlugin` interface
  - `web.ts` - Web implementation using MediaDevices API and BarcodeDetector
  - `index.ts` - Entry point that registers the plugin

- **iOS** (`ios/Sources/CameraViewPlugin/`): Swift implementation using AVFoundation
  - `CameraViewPlugin.swift` - Capacitor plugin bridge
  - `CameraViewManager.swift` - Core camera session management
  - `CameraViewManager+PhotoCapture.swift` - Photo capture extension
  - `CameraViewManager+BarcodeScan.swift` - Barcode detection extension
  - `CameraViewManager+VideoDataOutput.swift` - Video frame sampling

- **Android** (`android/src/main/java/com/michaelwolz/capacitorcameraview/`): Kotlin implementation using CameraX
  - `CameraViewPlugin.kt` - Capacitor plugin bridge
  - `CameraView.kt` - Camera preview and capture logic
  - `model/` - Data classes for configuration and responses

### Key Implementation Details

- The camera view renders behind the WebView; apps must make the WebView transparent to see it
- Barcode detection uses platform-native APIs: AVCaptureMetadataOutput (iOS), ML Kit (Android), BarcodeDetector API (Web)
- Virtual camera support (iOS) enables automatic lens switching based on zoom level
- The `capture()` method uses full camera pipeline; `captureSample()` samples from video stream for faster, lower-quality results

## Example App

The `example-app/` directory contains an Ionic Angular app demonstrating plugin usage. It is a separate pnpm project with its own `package.json`, `pnpm-lock.yaml`, and `pnpm-workspace.yaml`, and must be installed and built separately.

## Release Process

Uses semantic-release with conventional commits. Commit messages must follow the format:
- `feat(scope): description` - New features (minor version bump)
- `fix(scope): description` - Bug fixes (patch version bump)
- `chore: description` - Maintenance tasks (no version bump)

## Agent skills

### Issue tracker

Issues and PRDs live as markdown files under `.scratch/<feature-slug>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Default role vocabulary (needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix), unchanged. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context — one `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.
