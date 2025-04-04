/**
 * Converts canvas to base64 string
 */
export function canvasToBase64(canvas: HTMLCanvasElement, quality: number): string {
  const dataUrl = canvas.toDataURL('image/jpeg', quality);
  return dataUrl.split(',')[1];
}

/**
 * Calculates the visible area of the video based on object-fit: cover
 */
export function calculateVisibleArea(video: HTMLVideoElement): {
  sourceX: number;
  sourceY: number;
  sourceWidth: number;
  sourceHeight: number;
  displayWidth: number;
  displayHeight: number;
} {
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

  return {
    sourceX,
    sourceY,
    sourceWidth,
    sourceHeight,
    displayWidth,
    displayHeight,
  };
}

/**
 * Draws the visible area of the video to the canvas
 */
export function drawVisibleAreaToCanvas(
  canvas: HTMLCanvasElement,
  videoElement: HTMLVideoElement,
  area: {
    sourceX: number;
    sourceY: number;
    sourceWidth: number;
    sourceHeight: number;
    displayWidth: number;
    displayHeight: number;
  },
): void {
  const { sourceX, sourceY, sourceWidth, sourceHeight, displayWidth, displayHeight } = area;

  // Set canvas size to match the displayed dimensions
  canvas.width = displayWidth;
  canvas.height = displayHeight;

  const ctx = canvas.getContext('2d', { alpha: false });
  if (!ctx) {
    throw new Error('Could not get canvas context');
  }

  // Draw only the visible portion of the video to match what the user sees
  ctx.drawImage(videoElement, sourceX, sourceY, sourceWidth, sourceHeight, 0, 0, displayWidth, displayHeight);
}

/**
 * Transforms barcode coordinates from the video source space to display space
 * accounting for object-fit: cover scaling and cropping.
 * 
 * @param barcodeBoundingBox The original barcode bounding box from the detector
 * @param videoElement The video element with the camera stream
 * @returns The transformed bounding box coordinates in display space
 */
export function transformBarcodeBoundingBox(
  barcodeBoundingBox: {
    x: number;
    y: number;
    width: number;
    height: number;
  },
  videoElement: HTMLVideoElement
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
  
  // Calculate scaling and positioning for object-fit: cover
  const videoAspect = videoWidth / videoHeight;
  const displayAspect = displayWidth / displayHeight;
  
  let scaledX, scaledY, scaledWidth, scaledHeight;
  
  if (videoAspect > displayAspect) {
    // Video is wider than display area - height matches, width is centered and cropped
    const scale = displayHeight / videoHeight;
    const scaledVideoWidth = videoWidth * scale;
    const cropX = (scaledVideoWidth - displayWidth) / 2;
    
    scaledWidth = barcodeBoundingBox.width * scale;
    scaledHeight = barcodeBoundingBox.height * scale;
    scaledX = barcodeBoundingBox.x * scale - cropX;
    scaledY = barcodeBoundingBox.y * scale;
  } else {
    // Video is taller than display area - width matches, height is centered and cropped
    const scale = displayWidth / videoWidth;
    const scaledVideoHeight = videoHeight * scale;
    const cropY = (scaledVideoHeight - displayHeight) / 2;
    
    scaledWidth = barcodeBoundingBox.width * scale;
    scaledHeight = barcodeBoundingBox.height * scale;
    scaledX = barcodeBoundingBox.x * scale;
    scaledY = barcodeBoundingBox.y * scale - cropY;
  }
  
  return {
    x: scaledX,
    y: scaledY,
    width: scaledWidth,
    height: scaledHeight
  };
}
