import { afterEach, describe, expect, it, vi } from 'vitest';

import { CameraViewError, CameraViewWeb, classifyGetUserMediaErrorCode } from './web';

describe('CameraViewError', () => {
  it('carries a stable code alongside the message', () => {
    const error = new CameraViewError('Camera is not running', 'SESSION_NOT_RUNNING');

    expect(error).toBeInstanceOf(Error);
    expect(error.name).toBe('CameraViewError');
    expect(error.message).toBe('Camera is not running');
    expect(error.code).toBe('SESSION_NOT_RUNNING');
  });
});

describe('classifyGetUserMediaErrorCode', () => {
  it('maps a permission denial to PERMISSION_DENIED regardless of media kind', () => {
    const err = new DOMException('denied', 'NotAllowedError');
    expect(classifyGetUserMediaErrorCode(err, 'camera')).toBe('PERMISSION_DENIED');
    expect(classifyGetUserMediaErrorCode(err, 'microphone')).toBe('PERMISSION_DENIED');
  });

  it('maps a missing device to the media-specific "unavailable" code', () => {
    const err = new DOMException('not found', 'NotFoundError');
    expect(classifyGetUserMediaErrorCode(err, 'camera')).toBe('CAMERA_UNAVAILABLE');
    expect(classifyGetUserMediaErrorCode(err, 'microphone')).toBe('AUDIO_DEVICE_UNAVAILABLE');
  });

  it('maps a device already claimed by another process to the media-specific "in use" code', () => {
    const err = new DOMException('busy', 'NotReadableError');
    expect(classifyGetUserMediaErrorCode(err, 'camera')).toBe('DEVICE_LOCKED');
    expect(classifyGetUserMediaErrorCode(err, 'microphone')).toBe('AUDIO_INPUT_ADDITION_FAILED');
  });

  it('falls back to UNKNOWN_ERROR for an unrecognized DOMException name', () => {
    const err = new DOMException('aborted', 'AbortError');
    expect(classifyGetUserMediaErrorCode(err, 'camera')).toBe('UNKNOWN_ERROR');
  });

  it('falls back to UNKNOWN_ERROR for a non-DOMException failure', () => {
    expect(classifyGetUserMediaErrorCode(new Error('boom'), 'camera')).toBe('UNKNOWN_ERROR');
    expect(classifyGetUserMediaErrorCode('not even an error', 'microphone')).toBe('UNKNOWN_ERROR');
  });
});

describe('CameraViewWeb.flipCamera', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('cycles cameras by device id when none reports a facing mode', async () => {
    const ids = ['a', 'b', 'c'];
    const getUserMedia = async ({ video }: { video: MediaTrackConstraints }) => {
      const deviceId = (video.deviceId as ConstrainDOMStringParameters | undefined)?.exact ?? ids[0];
      const track = { stop: () => undefined, getSettings: () => ({ deviceId }) };
      return { getTracks: () => [track], getVideoTracks: () => [track] };
    };
    vi.stubGlobal('window', {});
    vi.stubGlobal('document', {
      createElement: () => ({ style: {}, play: async () => undefined }),
      body: { appendChild: () => undefined },
    });
    vi.stubGlobal('navigator', {
      mediaDevices: {
        getUserMedia,
        enumerateDevices: async () => ids.map((deviceId) => ({ deviceId, kind: 'videoinput' })),
      },
    });

    const plugin = new CameraViewWeb();
    const activeDeviceId = () =>
      (plugin as unknown as { stream: MediaStream }).stream.getVideoTracks()[0].getSettings().deviceId;
    await plugin.start();

    const visited = [activeDeviceId()];
    for (let i = 0; i < 3; i++) {
      await plugin.flipCamera();
      visited.push(activeDeviceId());
    }

    expect(visited).toEqual(['a', 'b', 'c', 'a']);
  });
});
