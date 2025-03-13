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
* [`flipCamera()`](#flipcamera)
* [`getAvailableDevices()`](#getavailabledevices)
* [`getZoom()`](#getzoom)
* [`setZoom(...)`](#setzoom)
* [`getFlashMode()`](#getflashmode)
* [`getSupportedFlashModes()`](#getsupportedflashmodes)
* [`setFlashMode(...)`](#setflashmode)
* [`checkPermissions()`](#checkpermissions)
* [`requestPermissions()`](#requestpermissions)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### start(...)

```typescript
start(options?: CameraSessionConfiguration | undefined) => Promise<void>
```

Start the camera view

| Param         | Type                                                                              |
| ------------- | --------------------------------------------------------------------------------- |
| **`options`** | <code><a href="#camerasessionconfiguration">CameraSessionConfiguration</a></code> |

--------------------


### stop()

```typescript
stop() => Promise<void>
```

Stop the camera view

--------------------


### isRunning()

```typescript
isRunning() => Promise<{ isRunning: boolean; }>
```

Check if the camera view is running.

**Returns:** <code>Promise&lt;{ isRunning: boolean; }&gt;</code>

--------------------


### capture(...)

```typescript
capture(options: { quality: number; }) => Promise<{ photo: string; }>
```

Capture a photo.

| Param         | Type                              |
| ------------- | --------------------------------- |
| **`options`** | <code>{ quality: number; }</code> |

**Returns:** <code>Promise&lt;{ photo: string; }&gt;</code>

--------------------


### flipCamera()

```typescript
flipCamera() => Promise<void>
```

Switches between front and back camera.

--------------------


### getAvailableDevices()

```typescript
getAvailableDevices() => Promise<{ devices: Array<CameraDevice>; }>
```

Get available devices for taking photos.

**Returns:** <code>Promise&lt;{ devices: CameraDevice[]; }&gt;</code>

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
setZoom(options: { level: number; ramp?: boolean; }) => Promise<void>
```

Set zoom level.

| Param         | Type                                            |
| ------------- | ----------------------------------------------- |
| **`options`** | <code>{ level: number; ramp?: boolean; }</code> |

--------------------


### getFlashMode()

```typescript
getFlashMode() => Promise<{ flashMode: FlashMode; }>
```

Get flash mode.

**Returns:** <code>Promise&lt;{ flashMode: <a href="#flashmode">FlashMode</a>; }&gt;</code>

--------------------


### getSupportedFlashModes()

```typescript
getSupportedFlashModes() => Promise<{ flashModes: Array<FlashMode>; }>
```

Get supported flash modes.

**Returns:** <code>Promise&lt;{ flashModes: FlashMode[]; }&gt;</code>

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


### checkPermissions()

```typescript
checkPermissions() => Promise<{ camera: PermissionStatus; }>
```

Check camera permission.

**Returns:** <code>Promise&lt;{ camera: <a href="#permissionstatus">PermissionStatus</a>; }&gt;</code>

--------------------


### requestPermissions()

```typescript
requestPermissions() => Promise<{ camera: PermissionStatus; }>
```

Request camera permission.

**Returns:** <code>Promise&lt;{ camera: <a href="#permissionstatus">PermissionStatus</a>; }&gt;</code>

--------------------


### Interfaces


#### CameraSessionConfiguration

Configuration for the camera session.

| Prop                             | Type                                                      | Description                                              |
| -------------------------------- | --------------------------------------------------------- | -------------------------------------------------------- |
| **`enableBarcodeScanner`**       | <code>boolean</code>                                      | Enables the barcode scanner, defaults to `false`         |
| **`position`**                   | <code><a href="#cameraposition">CameraPosition</a></code> | Position of the camera (front or back)                   |
| **`deviceId`**                   | <code>string</code>                                       | The device ID of the camera to use                       |
| **`preset`**                     | <code><a href="#camerapreset">CameraPreset</a></code>     | The preset to use for the camera session                 |
| **`useTripleCameraIfAvailable`** | <code>boolean</code>                                      | Whether to use the triple camera if available (iOS only) |
| **`zoomFactor`**                 | <code>number</code>                                       | The initial zoom factor to use for the camera session    |


#### Array

| Prop         | Type                | Description                                                                                            |
| ------------ | ------------------- | ------------------------------------------------------------------------------------------------------ |
| **`length`** | <code>number</code> | Gets or sets the length of the array. This is a number one higher than the highest index in the array. |

| Method             | Signature                                                                                                                     | Description                                                                                                                                                                                                                                 |
| ------------------ | ----------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **toString**       | () =&gt; string                                                                                                               | Returns a string representation of an array.                                                                                                                                                                                                |
| **toLocaleString** | () =&gt; string                                                                                                               | Returns a string representation of an array. The elements are converted to string using their toLocalString methods.                                                                                                                        |
| **pop**            | () =&gt; T \| undefined                                                                                                       | Removes the last element from an array and returns it. If the array is empty, undefined is returned and the array is not modified.                                                                                                          |
| **push**           | (...items: T[]) =&gt; number                                                                                                  | Appends new elements to the end of an array, and returns the new length of the array.                                                                                                                                                       |
| **concat**         | (...items: <a href="#concatarray">ConcatArray</a>&lt;T&gt;[]) =&gt; T[]                                                       | Combines two or more arrays. This method returns a new array without modifying any existing arrays.                                                                                                                                         |
| **concat**         | (...items: (T \| <a href="#concatarray">ConcatArray</a>&lt;T&gt;)[]) =&gt; T[]                                                | Combines two or more arrays. This method returns a new array without modifying any existing arrays.                                                                                                                                         |
| **join**           | (separator?: string \| undefined) =&gt; string                                                                                | Adds all the elements of an array into a string, separated by the specified separator string.                                                                                                                                               |
| **reverse**        | () =&gt; T[]                                                                                                                  | Reverses the elements in an array in place. This method mutates the array and returns a reference to the same array.                                                                                                                        |
| **shift**          | () =&gt; T \| undefined                                                                                                       | Removes the first element from an array and returns it. If the array is empty, undefined is returned and the array is not modified.                                                                                                         |
| **slice**          | (start?: number \| undefined, end?: number \| undefined) =&gt; T[]                                                            | Returns a copy of a section of an array. For both start and end, a negative index can be used to indicate an offset from the end of the array. For example, -2 refers to the second to last element of the array.                           |
| **sort**           | (compareFn?: ((a: T, b: T) =&gt; number) \| undefined) =&gt; this                                                             | Sorts an array in place. This method mutates the array and returns a reference to the same array.                                                                                                                                           |
| **splice**         | (start: number, deleteCount?: number \| undefined) =&gt; T[]                                                                  | Removes elements from an array and, if necessary, inserts new elements in their place, returning the deleted elements.                                                                                                                      |
| **splice**         | (start: number, deleteCount: number, ...items: T[]) =&gt; T[]                                                                 | Removes elements from an array and, if necessary, inserts new elements in their place, returning the deleted elements.                                                                                                                      |
| **unshift**        | (...items: T[]) =&gt; number                                                                                                  | Inserts new elements at the start of an array, and returns the new length of the array.                                                                                                                                                     |
| **indexOf**        | (searchElement: T, fromIndex?: number \| undefined) =&gt; number                                                              | Returns the index of the first occurrence of a value in an array, or -1 if it is not present.                                                                                                                                               |
| **lastIndexOf**    | (searchElement: T, fromIndex?: number \| undefined) =&gt; number                                                              | Returns the index of the last occurrence of a specified value in an array, or -1 if it is not present.                                                                                                                                      |
| **every**          | &lt;S extends T&gt;(predicate: (value: T, index: number, array: T[]) =&gt; value is S, thisArg?: any) =&gt; this is S[]       | Determines whether all the members of an array satisfy the specified test.                                                                                                                                                                  |
| **every**          | (predicate: (value: T, index: number, array: T[]) =&gt; unknown, thisArg?: any) =&gt; boolean                                 | Determines whether all the members of an array satisfy the specified test.                                                                                                                                                                  |
| **some**           | (predicate: (value: T, index: number, array: T[]) =&gt; unknown, thisArg?: any) =&gt; boolean                                 | Determines whether the specified callback function returns true for any element of an array.                                                                                                                                                |
| **forEach**        | (callbackfn: (value: T, index: number, array: T[]) =&gt; void, thisArg?: any) =&gt; void                                      | Performs the specified action for each element in an array.                                                                                                                                                                                 |
| **map**            | &lt;U&gt;(callbackfn: (value: T, index: number, array: T[]) =&gt; U, thisArg?: any) =&gt; U[]                                 | Calls a defined callback function on each element of an array, and returns an array that contains the results.                                                                                                                              |
| **filter**         | &lt;S extends T&gt;(predicate: (value: T, index: number, array: T[]) =&gt; value is S, thisArg?: any) =&gt; S[]               | Returns the elements of an array that meet the condition specified in a callback function.                                                                                                                                                  |
| **filter**         | (predicate: (value: T, index: number, array: T[]) =&gt; unknown, thisArg?: any) =&gt; T[]                                     | Returns the elements of an array that meet the condition specified in a callback function.                                                                                                                                                  |
| **reduce**         | (callbackfn: (previousValue: T, currentValue: T, currentIndex: number, array: T[]) =&gt; T) =&gt; T                           | Calls the specified callback function for all the elements in an array. The return value of the callback function is the accumulated result, and is provided as an argument in the next call to the callback function.                      |
| **reduce**         | (callbackfn: (previousValue: T, currentValue: T, currentIndex: number, array: T[]) =&gt; T, initialValue: T) =&gt; T          |                                                                                                                                                                                                                                             |
| **reduce**         | &lt;U&gt;(callbackfn: (previousValue: U, currentValue: T, currentIndex: number, array: T[]) =&gt; U, initialValue: U) =&gt; U | Calls the specified callback function for all the elements in an array. The return value of the callback function is the accumulated result, and is provided as an argument in the next call to the callback function.                      |
| **reduceRight**    | (callbackfn: (previousValue: T, currentValue: T, currentIndex: number, array: T[]) =&gt; T) =&gt; T                           | Calls the specified callback function for all the elements in an array, in descending order. The return value of the callback function is the accumulated result, and is provided as an argument in the next call to the callback function. |
| **reduceRight**    | (callbackfn: (previousValue: T, currentValue: T, currentIndex: number, array: T[]) =&gt; T, initialValue: T) =&gt; T          |                                                                                                                                                                                                                                             |
| **reduceRight**    | &lt;U&gt;(callbackfn: (previousValue: U, currentValue: T, currentIndex: number, array: T[]) =&gt; U, initialValue: U) =&gt; U | Calls the specified callback function for all the elements in an array, in descending order. The return value of the callback function is the accumulated result, and is provided as an argument in the next call to the callback function. |


#### ConcatArray

| Prop         | Type                |
| ------------ | ------------------- |
| **`length`** | <code>number</code> |

| Method    | Signature                                                          |
| --------- | ------------------------------------------------------------------ |
| **join**  | (separator?: string \| undefined) =&gt; string                     |
| **slice** | (start?: number \| undefined, end?: number \| undefined) =&gt; T[] |


#### CameraDevice

| Prop           | Type                                                      |
| -------------- | --------------------------------------------------------- |
| **`id`**       | <code>string</code>                                       |
| **`name`**     | <code>string</code>                                       |
| **`position`** | <code><a href="#cameraposition">CameraPosition</a></code> |


#### PermissionStatus

Permission status for the camera.

| Prop         | Type                                                        |
| ------------ | ----------------------------------------------------------- |
| **`camera`** | <code><a href="#permissionstate">PermissionState</a></code> |


### Type Aliases


#### CameraPosition

Position options for the camera session.

<code>'front' | 'back'</code>


#### CameraPreset

<code>'low' | 'medium' | 'high' | 'photo'</code>


#### FlashMode

Flash mode options for the camera session.

<code>'off' | 'on' | 'auto'</code>


#### PermissionState

<code>'prompt' | 'prompt-with-rationale' | 'granted' | 'denied'</code>

</docgen-api>
