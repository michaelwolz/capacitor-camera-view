import { describe, expect, it, vi } from 'vitest';

import {
  applyCssZoomCrop,
  calculateFullFrameArea,
  calculateVisibleArea,
  drawVisibleAreaToCanvas,
  transformBarcodeBoundingBox,
} from './utils';

/** Minimal stand-in exposing exactly what the visible-area math reads. */
function mockVideo(options: {
  videoWidth: number;
  videoHeight: number;
  displayWidth: number;
  displayHeight: number;
}): HTMLVideoElement {
  return {
    videoWidth: options.videoWidth,
    videoHeight: options.videoHeight,
    getBoundingClientRect: () => ({ width: options.displayWidth, height: options.displayHeight }),
  } as unknown as HTMLVideoElement;
}

describe('calculateVisibleArea', () => {
  it('crops the sides when the source is wider than the container', () => {
    const video = mockVideo({ videoWidth: 1920, videoHeight: 1080, displayWidth: 400, displayHeight: 400 });

    const area = calculateVisibleArea(video);

    expect(area).toEqual({
      sourceX: 420, // (1920 - 1080) / 2
      sourceY: 0,
      sourceWidth: 1080,
      sourceHeight: 1080,
      displayWidth: 400,
      displayHeight: 400,
      // Output is the crop's native source resolution, not the CSS-pixel size.
      outputWidth: 1080,
      outputHeight: 1080,
    });
  });

  it('crops the top and bottom when the source is taller than the container', () => {
    const video = mockVideo({ videoWidth: 1080, videoHeight: 1920, displayWidth: 400, displayHeight: 300 });

    const area = calculateVisibleArea(video);

    expect(area.sourceX).toBe(0);
    expect(area.sourceY).toBe(555); // (1920 - 810) / 2
    expect(area.sourceWidth).toBe(1080);
    expect(area.sourceHeight).toBe(810); // 1080 / (400 / 300)
    expect(area.outputWidth).toBe(1080);
    expect(area.outputHeight).toBe(810);
  });

  it('uses the full frame when source and container aspect ratios match exactly', () => {
    const video = mockVideo({ videoWidth: 1280, videoHeight: 720, displayWidth: 640, displayHeight: 360 });

    const area = calculateVisibleArea(video);

    expect(area.sourceX).toBe(0);
    expect(area.sourceY).toBe(0);
    // Even though the preview is only 640x360 CSS pixels, the capture keeps
    // the stream's full 1280x720 resolution.
    expect(area.outputWidth).toBe(1280);
    expect(area.outputHeight).toBe(720);
  });

  it('outputs source-region resolution on a high-DPR full-screen preview, not CSS pixels', () => {
    // Full-screen portrait preview on a DPR-3 phone (393x852 CSS px) backed by
    // a landscape 1080p stream.
    const video = mockVideo({ videoWidth: 1920, videoHeight: 1080, displayWidth: 393, displayHeight: 852 });

    const area = calculateVisibleArea(video);

    expect(area.sourceWidth).toBeCloseTo(1080 * (393 / 852), 6);
    expect(area.sourceX).toBeCloseTo((1920 - 1080 * (393 / 852)) / 2, 6);
    expect(area.outputWidth).toBe(Math.round(1080 * (393 / 852))); // 498
    expect(area.outputWidth).toBeGreaterThan(area.displayWidth);
    expect(area.outputHeight).toBeGreaterThan(area.displayHeight);
    // ...and preserves the on-screen aspect ratio (what the user sees).
    expect(area.outputWidth / area.outputHeight).toBeCloseTo(393 / 852, 2);
  });
});

describe('calculateFullFrameArea', () => {
  it('returns the uncropped frame at its intrinsic resolution regardless of the container ratio', () => {
    const video = mockVideo({ videoWidth: 1920, videoHeight: 1080, displayWidth: 400, displayHeight: 400 });

    const area = calculateFullFrameArea(video);

    expect(area).toEqual({
      sourceX: 0,
      sourceY: 0,
      sourceWidth: 1920,
      sourceHeight: 1080,
      displayWidth: 400,
      displayHeight: 400,
      outputWidth: 1920,
      outputHeight: 1080,
    });
  });

  it('preserves a 4:3 stream ratio in a portrait container', () => {
    const video = mockVideo({ videoWidth: 1440, videoHeight: 1080, displayWidth: 393, displayHeight: 852 });

    const area = calculateFullFrameArea(video);

    expect(area.outputWidth / area.outputHeight).toBeCloseTo(4 / 3, 6);
  });
});

describe('applyCssZoomCrop', () => {
  const baseArea = {
    sourceX: 100,
    sourceY: 200,
    sourceWidth: 800,
    sourceHeight: 600,
    displayWidth: 400,
    displayHeight: 300,
    outputWidth: 800,
    outputHeight: 600,
  };

  it('returns the area unchanged when not zoomed in', () => {
    expect(applyCssZoomCrop(baseArea, 1)).toEqual(baseArea);
    expect(applyCssZoomCrop(baseArea, 0.5)).toEqual(baseArea);
  });

  it('tightens the crop about its center at scale 2', () => {
    const cropped = applyCssZoomCrop(baseArea, 2);

    expect(cropped.sourceWidth).toBe(400);
    expect(cropped.sourceHeight).toBe(300);
    expect(cropped.sourceX).toBe(300); // 100 + (800 - 400) / 2
    expect(cropped.sourceY).toBe(350); // 200 + (600 - 300) / 2
    // Output resized to the tightened crop, display dimensions untouched.
    expect(cropped.outputWidth).toBe(400);
    expect(cropped.outputHeight).toBe(300);
    expect(cropped.displayWidth).toBe(400);
    expect(cropped.displayHeight).toBe(300);
  });

  it('rounds the output dimensions for a non-integer scale', () => {
    const cropped = applyCssZoomCrop(baseArea, 3);

    expect(cropped.sourceWidth).toBeCloseTo(800 / 3, 6);
    expect(cropped.outputWidth).toBe(Math.round(800 / 3)); // 267
    expect(cropped.outputHeight).toBe(200);
  });
});

describe('drawVisibleAreaToCanvas', () => {
  function mockCanvas(context: CanvasRenderingContext2D | null) {
    return {
      width: 0,
      height: 0,
      getContext: vi.fn(() => context),
    } as unknown as HTMLCanvasElement;
  }

  it('sizes the canvas to the output resolution and draws the source crop into it', () => {
    const drawImage = vi.fn();
    const canvas = mockCanvas({ drawImage } as unknown as CanvasRenderingContext2D);
    const video = mockVideo({ videoWidth: 1920, videoHeight: 1080, displayWidth: 400, displayHeight: 400 });
    const area = calculateVisibleArea(video);

    drawVisibleAreaToCanvas(canvas, video, area);

    expect(canvas.width).toBe(area.outputWidth);
    expect(canvas.height).toBe(area.outputHeight);
    expect(drawImage).toHaveBeenCalledWith(
      video,
      area.sourceX,
      area.sourceY,
      area.sourceWidth,
      area.sourceHeight,
      0,
      0,
      area.outputWidth,
      area.outputHeight,
    );
  });

  it('throws when the 2d context is unavailable', () => {
    const canvas = mockCanvas(null);
    const video = mockVideo({ videoWidth: 1920, videoHeight: 1080, displayWidth: 400, displayHeight: 400 });

    expect(() => drawVisibleAreaToCanvas(canvas, video, calculateVisibleArea(video))).toThrow(
      'Could not get canvas context',
    );
  });
});

describe('transformBarcodeBoundingBox', () => {
  it('offsets and scales the box when the video is cropped on the sides', () => {
    const video = mockVideo({ videoWidth: 1920, videoHeight: 1080, displayWidth: 400, displayHeight: 400 });
    const box = { x: 960, y: 540, width: 100, height: 50 };

    const transformed = transformBarcodeBoundingBox(box, video);

    // scale = displayHeight / videoHeight = 400 / 1080
    expect(transformed.x).toBeCloseTo(200, 6);
    expect(transformed.y).toBeCloseTo(200, 6);
    expect(transformed.width).toBeCloseTo((100 * 400) / 1080, 6);
    expect(transformed.height).toBeCloseTo((50 * 400) / 1080, 6);
  });

  it('offsets and scales the box when the video is cropped top/bottom', () => {
    const video = mockVideo({ videoWidth: 1080, videoHeight: 1920, displayWidth: 400, displayHeight: 300 });
    const box = { x: 200, y: 800, width: 80, height: 120 };

    const transformed = transformBarcodeBoundingBox(box, video);

    const scale = 400 / 1080;
    const cropY = (1920 * scale - 300) / 2;
    expect(transformed.x).toBeCloseTo(200 * scale, 6);
    expect(transformed.y).toBeCloseTo(800 * scale - cropY, 6);
    expect(transformed.width).toBeCloseTo(80 * scale, 6);
    expect(transformed.height).toBeCloseTo(120 * scale, 6);
  });

  it('fit mode letterboxes top/bottom and offsets the box down', () => {
    const video = mockVideo({ videoWidth: 1920, videoHeight: 1080, displayWidth: 400, displayHeight: 400 });
    const box = { x: 960, y: 540, width: 100, height: 50 };

    const transformed = transformBarcodeBoundingBox(box, video, 'fit');

    // scale = min(400/1920, 400/1080) = 400/1920; letterbox offsetY = 87.5.
    const scale = 400 / 1920;
    expect(transformed.x).toBeCloseTo(960 * scale, 6); // offsetX = 0
    expect(transformed.y).toBeCloseTo(540 * scale + (400 - 1080 * scale) / 2, 6);
    expect(transformed.width).toBeCloseTo(100 * scale, 6);
    expect(transformed.height).toBeCloseTo(50 * scale, 6);
  });

  it('fit mode letterboxes left/right and offsets the box right', () => {
    const video = mockVideo({ videoWidth: 1080, videoHeight: 1920, displayWidth: 400, displayHeight: 300 });
    const box = { x: 200, y: 800, width: 80, height: 120 };

    const transformed = transformBarcodeBoundingBox(box, video, 'fit');

    // scale = min(400/1080, 300/1920) = 300/1920.
    const scale = 300 / 1920;
    expect(transformed.x).toBeCloseTo(200 * scale + (400 - 1080 * scale) / 2, 6);
    expect(transformed.y).toBeCloseTo(800 * scale, 6); // offsetY = 0
    expect(transformed.width).toBeCloseTo(80 * scale, 6);
    expect(transformed.height).toBeCloseTo(120 * scale, 6);
  });

  it('leaves the box unscaled and unshifted when aspect ratios match, in both modes', () => {
    const video = mockVideo({ videoWidth: 1280, videoHeight: 720, displayWidth: 640, displayHeight: 360 });
    const box = { x: 100, y: 50, width: 200, height: 100 };

    // scale = 640 / 1280 = 0.5, no crop or letterbox offset either way.
    expect(transformBarcodeBoundingBox(box, video)).toEqual({ x: 50, y: 25, width: 100, height: 50 });
    expect(transformBarcodeBoundingBox(box, video, 'fit')).toEqual({ x: 50, y: 25, width: 100, height: 50 });
  });
});
