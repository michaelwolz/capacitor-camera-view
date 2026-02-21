# Video Recording Feature Design

**Date:** 2026-02-19
**Issue:** [#14 - add support for video recording](https://github.com/michaelwolz/capacitor-camera-view/issues/14)

## Overview

Add native video recording support to the capacitor-camera-view plugin across iOS, Android, and Web platforms. The feature integrates seamlessly with the existing camera preview session (no reinitialization needed) and saves recordings to the temp filesystem, returning a web-accessible path.

## API Design

### New TypeScript Methods

```typescript
// Start recording video (camera must be running)
startRecording(options?: VideoRecordingOptions): Promise<void>

// Stop recording and get the result file
stopRecording(): Promise<VideoRecordingResponse>
```

### New Types

```typescript
interface VideoRecordingOptions {
  enableAudio?: boolean;  // default: false
  quality?: VideoQuality; // default: 'medium'
}

type VideoQuality = 'low' | 'medium' | 'high';

interface VideoRecordingResponse {
  webPath: string;       // Web-accessible path to the video file
}
```

### Permission Changes

- Add `microphone` permission to plugin declaration
- Android: add `RECORD_AUDIO` to manifest (optional usage)
- iOS: app must add `NSMicrophoneUsageDescription` to Info.plist when using audio

## Platform Implementation

### iOS (AVFoundation)

- Add `AVCaptureMovieFileOutput` to the existing capture session
- Extend `CameraViewManager` with video recording functionality in `CameraViewManager+VideoRecording.swift`
- Use `TempFileManager` to track video temp files (extend with `createTempVideoFile()`)
- File format: MP4 (`.mp4`)
- Microphone input added to session only when `enableAudio: true`
- Handle concurrent photo/video output conflicts

### Android (CameraX)

- Use CameraX `VideoCapture` use case with `Recorder`
- `LifecycleCameraController` does not support `VideoCapture` + `ImageCapture` simultaneously in all versions; use `CameraController.VIDEO_CAPTURE` enabled mode
- File format: MP4 (`.mp4`), saved to `context.cacheDir`
- Return path via `FileUtils.getPortablePath()`
- Add `RECORD_AUDIO` to plugin permissions (conditional)

### Web (MediaRecorder API)

- Use `MediaRecorder` with the existing `MediaStream`
- Request audio track from `getUserMedia` if `enableAudio: true`
- Collect video chunks in `ondataavailable` handler
- On stop: create a `Blob`, generate an `object URL` via `URL.createObjectURL()`
- Format: `video/webm` (broadest browser support)

## File Naming Pattern

Consistent with existing photo naming:
- iOS: `camera_recording_<timestamp>.mp4` in `FileManager.default.temporaryDirectory`
- Android: `camera_recording_<timestamp>.mp4` in `context.cacheDir`
- Web: blob URL (in-memory, garbage collected when URL is revoked)

## Error Handling

- `startRecording()` throws if camera is not running
- `startRecording()` throws if recording is already in progress
- `stopRecording()` throws if no recording is in progress
- Platform-level errors propagated to JS layer

## Out of Scope

- Live streaming / real-time video transmission
- Video editing or trimming
- Background recording (app must be in foreground)
- Custom bitrate / codec selection (future enhancement)
