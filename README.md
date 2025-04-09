# capacitor-camera-view

A Capacitor plugin for embedding a live camera feed directly into your app.

## Install

```bash
npm install capacitor-camera-view
npx cap sync
```

## API

<docgen-index>

* [`start(...)`](#start)
* [`stop()`](#stop)
* [`isRunning()`](#isrunning)
* [`capture(...)`](#capture)
* [`captureSample(...)`](#capturesample)
* [`flipCamera()`](#flipcamera)
* [`getAvailableDevices()`](#getavailabledevices)
* [`getZoom()`](#getzoom)
* [`setZoom(...)`](#setzoom)
* [`getFlashMode()`](#getflashmode)
* [`getSupportedFlashModes()`](#getsupportedflashmodes)
* [`setFlashMode(...)`](#setflashmode)
* [`checkPermissions()`](#checkpermissions)
* [`requestPermissions()`](#requestpermissions)
* [`addListener('barcodeDetected', ...)`](#addlistenerbarcodedetected-)
* [`removeAllListeners(...)`](#removealllisteners)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

Main plugin interface for Capacitor Camera View functionality.

### start(...)

```typescript
start(options?: CameraSessionConfiguration | undefined) => Promise<void>
```

Start the camera view with optional configuration.

| Param         | Type                                                                              | Description                                    |
| ------------- | --------------------------------------------------------------------------------- | ---------------------------------------------- |
| **`options`** | <code><a href="#camerasessionconfiguration">CameraSessionConfiguration</a></code> | - Configuration options for the camera session |

--------------------


### stop()

```typescript
stop() => Promise<void>
```

Stop the camera view and release resources.

--------------------


### isRunning()

```typescript
isRunning() => Promise<IsRunningResponse>
```

Check if the camera view is currently running.

**Returns:** <code>Promise&lt;<a href="#isrunningresponse">IsRunningResponse</a>&gt;</code>

--------------------


### capture(...)

```typescript
capture(options: { quality: number; }) => Promise<CaptureResponse>
```

Capture a photo using the current camera configuration.

| Param         | Type                              | Description                     |
| ------------- | --------------------------------- | ------------------------------- |
| **`options`** | <code>{ quality: number; }</code> | - Capture configuration options |

**Returns:** <code>Promise&lt;<a href="#captureresponse">CaptureResponse</a>&gt;</code>

--------------------


### captureSample(...)

```typescript
captureSample(options: { quality: number; }) => Promise<CaptureResponse>
```

Captures a frame from the current camera preview without using the full camera capture pipeline.

Unlike `capture()` which may trigger hardware-level photo capture on native platforms,
this method quickly samples the current video stream. This is suitable computer vision or
simple snapshots where high fidelity is not required.

On web this method does exactly the same as `capture()` as it only captures a frame from the video stream
because unfortunately [ImageCapture API](https://developer.mozilla.org/en-US/docs/Web/API/ImageCapture) is 
not yet well supported on the web.

| Param         | Type                              | Description                     |
| ------------- | --------------------------------- | ------------------------------- |
| **`options`** | <code>{ quality: number; }</code> | - Capture configuration options |

**Returns:** <code>Promise&lt;<a href="#captureresponse">CaptureResponse</a>&gt;</code>

--------------------


### flipCamera()

```typescript
flipCamera() => Promise<void>
```

Switch between front and back camera.

--------------------


### getAvailableDevices()

```typescript
getAvailableDevices() => Promise<GetAvailableDevicesResponse>
```

Get available camera devices for capturing photos.

**Returns:** <code>Promise&lt;<a href="#getavailabledevicesresponse">GetAvailableDevicesResponse</a>&gt;</code>

--------------------


### getZoom()

```typescript
getZoom() => Promise<GetZoomResponse>
```

Get current zoom level information and available range.

**Returns:** <code>Promise&lt;<a href="#getzoomresponse">GetZoomResponse</a>&gt;</code>

--------------------


### setZoom(...)

```typescript
setZoom(options: { level: number; ramp?: boolean; }) => Promise<void>
```

Set the camera zoom level.

| Param         | Type                                            | Description                  |
| ------------- | ----------------------------------------------- | ---------------------------- |
| **`options`** | <code>{ level: number; ramp?: boolean; }</code> | - Zoom configuration options |

--------------------


### getFlashMode()

```typescript
getFlashMode() => Promise<GetFlashModeResponse>
```

Get current flash mode setting.

**Returns:** <code>Promise&lt;<a href="#getflashmoderesponse">GetFlashModeResponse</a>&gt;</code>

--------------------


### getSupportedFlashModes()

```typescript
getSupportedFlashModes() => Promise<GetSupportedFlashModesResponse>
```

Get supported flash modes for the current camera.

**Returns:** <code>Promise&lt;<a href="#getsupportedflashmodesresponse">GetSupportedFlashModesResponse</a>&gt;</code>

--------------------


### setFlashMode(...)

```typescript
setFlashMode(options: { mode: FlashMode; }) => Promise<void>
```

Set the camera flash mode.

| Param         | Type                                                       | Description                        |
| ------------- | ---------------------------------------------------------- | ---------------------------------- |
| **`options`** | <code>{ mode: <a href="#flashmode">FlashMode</a>; }</code> | - Flash mode configuration options |

--------------------


### checkPermissions()

```typescript
checkPermissions() => Promise<PermissionStatus>
```

Check camera permission status without requesting permissions.

**Returns:** <code>Promise&lt;<a href="#permissionstatus">PermissionStatus</a>&gt;</code>

--------------------


### requestPermissions()

```typescript
requestPermissions() => Promise<PermissionStatus>
```

Request camera permission from the user.

**Returns:** <code>Promise&lt;<a href="#permissionstatus">PermissionStatus</a>&gt;</code>

--------------------


### addListener('barcodeDetected', ...)

```typescript
addListener(eventName: 'barcodeDetected', listenerFunc: (data: BarcodeDetectionData) => void) => Promise<PluginListenerHandle>
```

Listen for barcode detection events.
This event is emitted when a barcode is detected in the camera preview.

| Param              | Type                                                                                     | Description                                                   |
| ------------------ | ---------------------------------------------------------------------------------------- | ------------------------------------------------------------- |
| **`eventName`**    | <code>'barcodeDetected'</code>                                                           | - The name of the event to listen for ('barcodeDetected')     |
| **`listenerFunc`** | <code>(data: <a href="#barcodedetectiondata">BarcodeDetectionData</a>) =&gt; void</code> | - The callback function to execute when a barcode is detected |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### removeAllListeners(...)

```typescript
removeAllListeners(eventName?: string | undefined) => Promise<void>
```

Remove all listeners for this plugin.

| Param           | Type                | Description                                   |
| --------------- | ------------------- | --------------------------------------------- |
| **`eventName`** | <code>string</code> | - Optional event name to remove listeners for |

--------------------


### Interfaces


#### CameraSessionConfiguration

Configuration options for starting a camera session.

| Prop                             | Type                                                      | Description                                                                                                                                                           | Default             |
| -------------------------------- | --------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------- |
| **`enableBarcodeDetection`**     | <code>boolean</code>                                      | Enables the barcode detection functionality                                                                                                                           | <code>false</code>  |
| **`position`**                   | <code><a href="#cameraposition">CameraPosition</a></code> | Position of the camera to use                                                                                                                                         | <code>'back'</code> |
| **`deviceId`**                   | <code>string</code>                                       | Specific device ID of the camera to use If provided, takes precedence over position                                                                                   |                     |
| **`useTripleCameraIfAvailable`** | <code>boolean</code>                                      | Whether to use the triple camera if available (iPhone Pro models only)                                                                                                | <code>false</code>  |
| **`zoomFactor`**                 | <code>number</code>                                       | The initial zoom factor to use                                                                                                                                        | <code>1.0</code>    |
| **`containerElementId`**         | <code>string</code>                                       | Optional HTML ID of the container element where the camera view should be rendered. If not provided, the camera view will be appended to the document body. Web only. |                     |


#### IsRunningResponse

Response for checking if the camera view is running.

| Prop            | Type                 | Description                                                  |
| --------------- | -------------------- | ------------------------------------------------------------ |
| **`isRunning`** | <code>boolean</code> | Indicates if the camera view is currently active and running |


#### CaptureResponse

Response for capturing a photo.

| Prop        | Type                | Description                                     |
| ----------- | ------------------- | ----------------------------------------------- |
| **`photo`** | <code>string</code> | The base64 encoded string of the captured photo |


#### GetAvailableDevicesResponse

Response for getting available camera devices.

| Prop          | Type                        | Description                          |
| ------------- | --------------------------- | ------------------------------------ |
| **`devices`** | <code>CameraDevice[]</code> | An array of available camera devices |


#### CameraDevice

Represents a physical camera device on the device.

| Prop           | Type                                                      | Description                                       |
| -------------- | --------------------------------------------------------- | ------------------------------------------------- |
| **`id`**       | <code>string</code>                                       | The unique identifier of the camera device        |
| **`name`**     | <code>string</code>                                       | The human-readable name of the camera device      |
| **`position`** | <code><a href="#cameraposition">CameraPosition</a></code> | The position of the camera device (front or back) |


#### GetZoomResponse

Response for getting zoom level information.

| Prop          | Type                | Description                      |
| ------------- | ------------------- | -------------------------------- |
| **`min`**     | <code>number</code> | The minimum zoom level supported |
| **`max`**     | <code>number</code> | The maximum zoom level supported |
| **`current`** | <code>number</code> | The current zoom level           |


#### GetFlashModeResponse

Response for getting the current flash mode.

| Prop            | Type                                            | Description                    |
| --------------- | ----------------------------------------------- | ------------------------------ |
| **`flashMode`** | <code><a href="#flashmode">FlashMode</a></code> | The current flash mode setting |


#### GetSupportedFlashModesResponse

Response for getting supported flash modes.

| Prop             | Type                     | Description                                             |
| ---------------- | ------------------------ | ------------------------------------------------------- |
| **`flashModes`** | <code>FlashMode[]</code> | An array of flash modes supported by the current camera |


#### PermissionStatus

Response for the camera permission status.

| Prop         | Type                                                        | Description                        |
| ------------ | ----------------------------------------------------------- | ---------------------------------- |
| **`camera`** | <code><a href="#permissionstate">PermissionState</a></code> | The state of the camera permission |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


#### BarcodeDetectionData

Data for a detected barcode.

| Prop               | Type                                                  | Description                                                      |
| ------------------ | ----------------------------------------------------- | ---------------------------------------------------------------- |
| **`value`**        | <code>string</code>                                   | The decoded string value of the barcode                          |
| **`displayValue`** | <code>string</code>                                   | The display value of the barcode (may differ from the raw value) |
| **`type`**         | <code>string</code>                                   | The type/format of the barcode (e.g., 'qr', 'code128', etc.)     |
| **`boundingRect`** | <code><a href="#boundingrect">BoundingRect</a></code> | The bounding rectangle of the barcode in the camera frame.       |


#### BoundingRect

Rectangle defining the boundary of the barcode in the camera frame.
Coordinates are normalized between 0 and 1 relative to the camera frame.

| Prop         | Type                | Description                                                                      |
| ------------ | ------------------- | -------------------------------------------------------------------------------- |
| **`x`**      | <code>number</code> | X-coordinate of the top-left corner                                              |
| **`y`**      | <code>number</code> | Y-coordinate of the top-left corner                                              |
| **`width`**  | <code>number</code> | Width of the bounding rectangle (should match the actual width of the barcode)   |
| **`height`** | <code>number</code> | Height of the bounding rectangle (should match the actual height of the barcode) |


### Type Aliases


#### CameraPosition

Position options for the camera.
- 'front': Front-facing camera
- 'back': Rear-facing camera

<code>'front' | 'back'</code>


#### FlashMode

Flash mode options for the camera.
- 'off': Flash disabled
- 'on': Flash always on
- 'auto': Flash automatically enabled in low-light conditions

<code>'off' | 'on' | 'auto'</code>


#### PermissionState

<code>'prompt' | 'prompt-with-rationale' | 'granted' | 'denied'</code>

</docgen-api>
