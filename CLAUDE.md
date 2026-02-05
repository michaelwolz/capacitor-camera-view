# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Capacitor Camera View is a Capacitor plugin that embeds a live camera feed into hybrid mobile apps. It supports iOS, Android, and Web platforms with features including photo capture, barcode detection, zoom/flash/torch control, and virtual camera support (iOS triple camera).

## Common Commands

```bash
# Build the plugin (generates docs + compiles TypeScript + bundles with Rollup)
npm run build

# Lint TypeScript
npm run lint

# Lint Swift code
npm run lint:ios

# Format all code (TypeScript, Java, Swift)
npm run fmt

# Verify all platforms build correctly
npm run verify

# Verify individual platforms
npm run verify:ios      # Builds iOS with xcodebuild
npm run verify:android  # Builds Android with Gradle
npm run verify:web      # Same as npm run build
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

The `example-app/` directory contains an Ionic Angular app demonstrating plugin usage. It has its own `package.json` and must be built separately.

## Release Process

Uses semantic-release with conventional commits. Commit messages must follow the format:
- `feat(scope): description` - New features (minor version bump)
- `fix(scope): description` - Bug fixes (patch version bump)
- `chore: description` - Maintenance tasks (no version bump)
