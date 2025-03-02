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
* [`switchCamera()`](#switchcamera)
* [`getZoom()`](#getzoom)
* [`setZoom(...)`](#setzoom)
* [`getFlashMode()`](#getflashmode)
* [`getSupportedFlashModes()`](#getsupportedflashmodes)
* [`setFlashMode(...)`](#setflashmode)
* [Type Aliases](#type-aliases)
* [Enums](#enums)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### start(...)

```typescript
start(options: { cameraPosition: CameraPosition; }) => Promise<void>
```

Start the camera view

| Param         | Type                                                                           |
| ------------- | ------------------------------------------------------------------------------ |
| **`options`** | <code>{ cameraPosition: <a href="#cameraposition">CameraPosition</a>; }</code> |

--------------------


### stop()

```typescript
stop() => Promise<void>
```

Stop the camera view

--------------------


### isRunning()

```typescript
isRunning() => Promise<boolean>
```

Check if the camera view is running.

**Returns:** <code>Promise&lt;boolean&gt;</code>

--------------------


### capture(...)

```typescript
capture(options: { quality: Range<100>; }) => Promise<string>
```

Capture a photo.

| Param         | Type                           |
| ------------- | ------------------------------ |
| **`options`** | <code>{ quality: any; }</code> |

**Returns:** <code>Promise&lt;string&gt;</code>

--------------------


### switchCamera()

```typescript
switchCamera() => Promise<void>
```

Switches between front and rear camera.

--------------------


### getZoom()

```typescript
getZoom() => Promise<{ min: number; max: number; current: number; }>
```

Get zoom levels options and current zoom level.

**Returns:** <code>Promise&lt;{ min: number; max: number; current: number; }&gt;</code>

--------------------


### setZoom(...)

```typescript
setZoom(options: { level: number; }) => Promise<void>
```

Set zoom level.

| Param         | Type                            |
| ------------- | ------------------------------- |
| **`options`** | <code>{ level: number; }</code> |

--------------------


### getFlashMode()

```typescript
getFlashMode() => Promise<FlashMode>
```

Get flash mode.

**Returns:** <code>Promise&lt;<a href="#flashmode">FlashMode</a>&gt;</code>

--------------------


### getSupportedFlashModes()

```typescript
getSupportedFlashModes() => Promise<FlashMode[]>
```

Get supported flash modes.

**Returns:** <code>Promise&lt;FlashMode[]&gt;</code>

--------------------


### setFlashMode(...)

```typescript
setFlashMode(options: { mode: FlashMode; }) => Promise<void>
```

Set flash mode.

| Param         | Type                                                       |
| ------------- | ---------------------------------------------------------- |
| **`options`** | <code>{ mode: <a href="#flashmode">FlashMode</a>; }</code> |

--------------------


### Type Aliases


#### Range

<code>Result['length'] extends N ? Result[number] | N : <a href="#range">Range</a>&lt;N, [...Result, Result['length']]&gt;</code>


### Enums


#### CameraPosition

| Members     | Value                |
| ----------- | -------------------- |
| **`FRONT`** | <code>'front'</code> |
| **`REAR`**  | <code>'rear'</code>  |


#### FlashMode

| Members     | Value                |
| ----------- | -------------------- |
| **`OFF`**   | <code>'off'</code>   |
| **`ON`**    | <code>'on'</code>    |
| **`AUTO`**  | <code>'auto'</code>  |
| **`TORCH`** | <code>'torch'</code> |

</docgen-api>
