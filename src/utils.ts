/**
 * Converts canvas to base64 string
 */
export function canvasToBase64(canvas: HTMLCanvasElement, quality: number): string {
  const dataUrl = canvas.toDataURL('image/jpeg', quality);
  return dataUrl.split(',')[1];
}

/**
 * The visible region of a video element rendered with `object-fit: cover`,
 * plus the target dimensions a capture of that region should be rasterized at.
 *
 * `source*` describe the crop in the video's intrinsic (source) pixel space.
 * `display*` are the CSS-pixel dimensions of the on-screen preview and define
 * the aspect ratio of the crop. `output*` are the pixel dimensions the capture
 * canvas should use.
 */
export interface VisibleArea {
  /** Left edge of the visible crop, in intrinsic video pixels. */
  sourceX: number;
  /** Top edge of the visible crop, in intrinsic video pixels. */
  sourceY: number;
  /** Width of the visible crop, in intrinsic video pixels. */
  sourceWidth: number;
  /** Height of the visible crop, in intrinsic video pixels. */
  sourceHeight: number;
  /** CSS-pixel width of the on-screen preview. */
  displayWidth: number;
  /** CSS-pixel height of the on-screen preview. */
  displayHeight: number;
  /** Target canvas width for a capture of the visible crop, in device pixels. */
  outputWidth: number;
  /** Target canvas height for a capture of the visible crop, in device pixels. */
  outputHeight: number;
}

/**
 * Calculates the visible area of the video based on object-fit: cover.
 *
 * The `output*` dimensions default to the visible crop's intrinsic (source)
 * pixel size, so a capture keeps the full detail the stream delivers for that
 * region instead of being downscaled to CSS pixels.
 */
export function calculateVisibleArea(video: HTMLVideoElement): VisibleArea {
  // Get the displayed dimensions of the video element
  const videoRect = video.getBoundingClientRect();
  const displayWidth = videoRect.width;
  const displayHeight = videoRect.height;

  // Get the intrinsic dimensions of the video
  const videoWidth = video.videoWidth;
  const videoHeight = video.videoHeight;

  // Calculate which portion of the video is visible (for object-fit: cover)
  const videoAspect = videoWidth / videoHeight;
  const displayAspect = displayWidth / displayHeight;

  let sourceX = 0;
  let sourceY = 0;
  let sourceWidth = videoWidth;
  let sourceHeight = videoHeight;

  // If video aspect ratio is greater than display aspect ratio,
  // the video is cropped on the sides
  if (videoAspect > displayAspect) {
    sourceWidth = videoHeight * displayAspect;
    sourceX = (videoWidth - sourceWidth) / 2;
  }
  // Otherwise the video is cropped on the top and bottom
  else {
    sourceHeight = videoWidth / displayAspect;
    sourceY = (videoHeight - sourceHeight) / 2;
  }

  // Rasterize the capture at the visible crop's native resolution. Rounding
  // keeps the canvas dimensions integral without distorting the aspect ratio.
  const outputWidth = Math.round(sourceWidth);
  const outputHeight = Math.round(sourceHeight);

  return {
    sourceX,
    sourceY,
    sourceWidth,
    sourceHeight,
    displayWidth,
    displayHeight,
    outputWidth,
    outputHeight,
  };
}

/**
 * The full video frame as a {@link VisibleArea}: no cropping, rasterized at
 * the stream's intrinsic resolution.
 *
 * Used when the session was started with an explicit `aspectRatio`, where the
 * cross-platform contract is that `capture()` returns the full sensor-ratio
 * frame rather than the on-screen cover-cropped region.
 */
export function calculateFullFrameArea(video: HTMLVideoElement): VisibleArea {
  const videoRect = video.getBoundingClientRect();

  return {
    sourceX: 0,
    sourceY: 0,
    sourceWidth: video.videoWidth,
    sourceHeight: video.videoHeight,
    displayWidth: videoRect.width,
    displayHeight: videoRect.height,
    outputWidth: video.videoWidth,
    outputHeight: video.videoHeight,
  };
}

/**
 * Further crops a {@link VisibleArea} to account for a CSS `transform: scale()`
 * zoom applied to the video element.
 *
 * The CSS-fallback zoom magnifies the preview about its center, but
 * `getBoundingClientRect()` reports the post-transform size and therefore
 * leaves the crop unchanged. Tightening the source rectangle by `scale`, kept
 * centered, makes a capture match what the user actually sees.
 *
 * A `scale <= 1` (no zoom) returns the area unchanged.
 */
export function applyCssZoomCrop(area: VisibleArea, scale: number): VisibleArea {
  if (!(scale > 1)) {
    return area;
  }

  const sourceWidth = area.sourceWidth / scale;
  const sourceHeight = area.sourceHeight / scale;

  return {
    ...area,
    sourceX: area.sourceX + (area.sourceWidth - sourceWidth) / 2,
    sourceY: area.sourceY + (area.sourceHeight - sourceHeight) / 2,
    sourceWidth,
    sourceHeight,
    outputWidth: Math.round(sourceWidth),
    outputHeight: Math.round(sourceHeight),
  };
}

/**
 * Draws the visible area of the video to the canvas at the crop's target
 * output resolution (see {@link VisibleArea.outputWidth}).
 */
export function drawVisibleAreaToCanvas(
  canvas: HTMLCanvasElement,
  videoElement: HTMLVideoElement,
  area: VisibleArea,
): void {
  const { sourceX, sourceY, sourceWidth, sourceHeight, outputWidth, outputHeight } = area;

  // Sized to the crop's intrinsic pixel size rather than the CSS-pixel preview
  // size, so the JPEG keeps the stream's real detail.
  canvas.width = outputWidth;
  canvas.height = outputHeight;

  const ctx = canvas.getContext('2d', { alpha: false });
  if (!ctx) {
    throw new Error('Could not get canvas context');
  }

  // Draw only the visible portion of the video to match what the user sees
  ctx.drawImage(videoElement, sourceX, sourceY, sourceWidth, sourceHeight, 0, 0, outputWidth, outputHeight);
}

/**
 * Transforms barcode coordinates from the video source space to display space,
 * accounting for how the video element is scaled into its container.
 *
 * Both `object-fit` modes are a single-scale, centered mapping and share one
 * formula, differing only in which axis scale wins: `'cover'` takes the larger
 * (center-cropped), `'fit'` the smaller (letterboxed).
 *
 * @param barcodeBoundingBox The original barcode bounding box from the detector
 * @param videoElement The video element with the camera stream
 * @param scaleMode Whether the preview uses `cover` or `fit` scaling
 * @returns The transformed bounding box coordinates in display space
 */
export function transformBarcodeBoundingBox(
  barcodeBoundingBox: {
    x: number;
    y: number;
    width: number;
    height: number;
  },
  videoElement: HTMLVideoElement,
  scaleMode: 'cover' | 'fit' = 'cover',
): {
  x: number;
  y: number;
  width: number;
  height: number;
} {
  // Get the video element's displayed dimensions
  const videoRect = videoElement.getBoundingClientRect();
  const displayWidth = videoRect.width;
  const displayHeight = videoRect.height;

  // Get original video dimensions
  const videoWidth = videoElement.videoWidth;
  const videoHeight = videoElement.videoHeight;

  const scaleX = displayWidth / videoWidth;
  const scaleY = displayHeight / videoHeight;
  const scale = scaleMode === 'fit' ? Math.min(scaleX, scaleY) : Math.max(scaleX, scaleY);

  // Centering offset of the scaled frame within the container: negative on a
  // cropped axis, positive on a letterboxed one.
  const offsetX = (displayWidth - videoWidth * scale) / 2;
  const offsetY = (displayHeight - videoHeight * scale) / 2;

  return {
    x: barcodeBoundingBox.x * scale + offsetX,
    y: barcodeBoundingBox.y * scale + offsetY,
    width: barcodeBoundingBox.width * scale,
    height: barcodeBoundingBox.height * scale,
  };
}
