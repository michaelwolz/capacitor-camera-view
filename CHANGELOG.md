## [3.0.4-rc.1](https://github.com/michaelwolz/capacitor-camera-view/compare/3.0.3...3.0.4-rc.1) (2026-09-15)


### Bug Fixes

* **android:** derive capture rotation from the display instead of the device sensor ([c9e80b4](https://github.com/michaelwolz/capacitor-camera-view/commit/c9e80b46328c1c6674f1f2111edb540b3dff73ff))

## [3.0.3](https://github.com/michaelwolz/capacitor-camera-view/compare/3.0.2...3.0.3) (2026-09-11)


### Bug Fixes

* **ios:** restore the WebView's presentation state after a session ([7f88a3e](https://github.com/michaelwolz/capacitor-camera-view/commit/7f88a3e267e286a2b8574ece0ec7028d75cb0b45))

## [3.0.2](https://github.com/michaelwolz/capacitor-camera-view/compare/3.0.1...3.0.2) (2026-09-10)


### Bug Fixes

* **dependencies:** bump capacitor to 8.5.1 ([66d3c6f](https://github.com/michaelwolz/capacitor-camera-view/commit/66d3c6fd1c1404ea6acf0511cdafbb800526fdf1))

## [3.0.1](https://github.com/michaelwolz/capacitor-camera-view/compare/3.0.0...3.0.1) (2026-08-24)


### Bug Fixes

* **ios:** keep capture orientation in sync with the preview ([bd4464c](https://github.com/michaelwolz/capacitor-camera-view/commit/bd4464c3cbcb20ac7edfa4ba84ee7381f429b49a))

# [3.0.0](https://github.com/michaelwolz/capacitor-camera-view/compare/2.4.0...3.0.0) (2026-08-07)


* feat(*)!: expand the plugin API surface ([6daa584](https://github.com/michaelwolz/capacitor-camera-view/commit/6daa5841b7895e7223e3d233889239b96c8ae401))
* feat(android)!: remove RECORD_AUDIO from the plugin manifest ([2f1a528](https://github.com/michaelwolz/capacitor-camera-view/commit/2f1a52889ef04f98021f395fd1bfa29a0101b5c5))
* feat(ios)!: raise minimum deployment target to iOS 16 ([3ad6c17](https://github.com/michaelwolz/capacitor-camera-view/commit/3ad6c1762f7215de187b62e56ed6892be168e9d1))


### Bug Fixes

* **camera-modal:** guard torch state debug logging ([af22559](https://github.com/michaelwolz/capacitor-camera-view/commit/af22559bd6ae4c57141b3f3a838401670e0403ea))
* **camera-modal:** set current zoom factor from zoom range ([dedb25c](https://github.com/michaelwolz/capacitor-camera-view/commit/dedb25cf2e9f7e86ba536d7a870e451a5d0f1ae7))
* **example-app:** repair pinch-to-zoom gesture ([4b919cb](https://github.com/michaelwolz/capacitor-camera-view/commit/4b919cbb6da73ca045c2e78ee20f6b7eb94129e1))


### Features

* **android:** rework the CameraX pipeline ([a091ef6](https://github.com/michaelwolz/capacitor-camera-view/commit/a091ef601315b06cd4af9b8d155f5646de644d08))
* **example:** demonstrate the new options ([dd879aa](https://github.com/michaelwolz/capacitor-camera-view/commit/dd879aa06a55376ca2ae4492805e12cf2d39f69f))
* **ios:** rework the capture session ([ff2afd8](https://github.com/michaelwolz/capacitor-camera-view/commit/ff2afd80e9a082867b2b69a06d2ee45e89bbafc0))
* **web:** rework the web implementation ([40aabdf](https://github.com/michaelwolz/capacitor-camera-view/commit/40aabdf33615861a5b876cdcb859146eda9f2f86))


### BREAKING CHANGES

* `start()` now rejects with 'Camera session is already
running' on iOS and web when a session is active; previously iOS reconfigured
the running session in place and web silently resolved. Call `stop()` before
starting a new session with different options.
* `CaptureResponse` resolves to the union of
`CaptureFileResult` and `CaptureBase64Result` when `saveToFile` is a
non-literal `boolean`. It previously resolved to the base64 result alone,
which was unsound because the runtime value can be either. Split the call
into literal `true`/`false` branches, or narrow the result before use.
* `removeAllListeners()` no longer accepts an `eventName`
argument. No implementation ever honored it; drop the argument at the call
site.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
* Apps that record video with audio on Android must declare
`android.permission.RECORD_AUDIO` in their own manifest. Apps that never
record audio no longer inherit the permission.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
* The plugin no longer supports iOS 15. Host apps must raise
their deployment target to iOS 16.0 or later.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>

# [2.4.0](https://github.com/michaelwolz/capacitor-camera-view/compare/2.3.1...2.4.0) (2026-07-01)


### Features

* add platform specific `path` to file results ([6ab4631](https://github.com/michaelwolz/capacitor-camera-view/commit/6ab4631d241dbaed4df29fa5db9414fc7f3ff91a)), closes [#26](https://github.com/michaelwolz/capacitor-camera-view/issues/26)

## [2.3.1](https://github.com/michaelwolz/capacitor-camera-view/compare/2.3.0...2.3.1) (2026-06-29)


### Bug Fixes

* **android:** correctly reflect camera running state after stopping ([c733eea](https://github.com/michaelwolz/capacitor-camera-view/commit/c733eea3990bee6e12472fda62902f4c05666220)), closes [#23](https://github.com/michaelwolz/capacitor-camera-view/issues/23)

# [2.3.0](https://github.com/michaelwolz/capacitor-camera-view/compare/2.2.0...2.3.0) (2026-05-08)


### Features

* **android:** add pending stop-recording callback for CameraX integration ([1eff5a9](https://github.com/michaelwolz/capacitor-camera-view/commit/1eff5a9c5b2ae361cad7d8bd90057883f1ec8b1a))
* **android:** implement CameraX video recording support ([2d98c08](https://github.com/michaelwolz/capacitor-camera-view/commit/2d98c088e7aaaecb0e2ce3447344c4d7d9e56f42))
* **example-app:** add video recording UI and gallery integration ([bcb9ae9](https://github.com/michaelwolz/capacitor-camera-view/commit/bcb9ae9f414203931165c3610c3e6cabd548aeb8))
* **ios:** implement native video recording pipeline ([d80b0f6](https://github.com/michaelwolz/capacitor-camera-view/commit/d80b0f6cd4327690814df0f783234e59bfd88ca5))
* **ios:** improve session handling in startRecording method ([10ca684](https://github.com/michaelwolz/capacitor-camera-view/commit/10ca684d3f9ddb24159c235a994c9bc74f2504f5))
* **ios:** update minimum iOS platform version to 15.0 ([f727e68](https://github.com/michaelwolz/capacitor-camera-view/commit/f727e68c526e1fd300d3ac9d07c321c9da5a18a2))
* update video recording options and documentation for backward compatibility ([78d1e9a](https://github.com/michaelwolz/capacitor-camera-view/commit/78d1e9a0a63ff0ca2c49c5ba4ce542769bb4819d))
* **video:** add configurable recording quality ([50475eb](https://github.com/michaelwolz/capacitor-camera-view/commit/50475eb02598dcc0b2eaf3f05025ad0f21a6c658))
* **web:** add video recording API and MediaRecorder implementation ([19ff57d](https://github.com/michaelwolz/capacitor-camera-view/commit/19ff57d301cedd8c695d9342bb870c367deca9a3))

# [2.2.0](https://github.com/michaelwolz/capacitor-camera-view/compare/2.1.0...2.2.0) (2026-04-30)


### Bug Fixes

* **example-app:** bump dependencies ([1c1644f](https://github.com/michaelwolz/capacitor-camera-view/commit/1c1644f380236e51b52ec595d84d51fc595d02f3))
* **ios:** remove non existent displayValue property for barcodes ([78e263c](https://github.com/michaelwolz/capacitor-camera-view/commit/78e263cdcff6165965d6abfec470b13fc866a449))


### Features

* add rawBytes property for barcode scanning ([4be262f](https://github.com/michaelwolz/capacitor-camera-view/commit/4be262fa1ecfc0cb400c6f3fadd293f1b0da5579))

# [2.1.0](https://github.com/michaelwolz/capacitor-camera-view/compare/2.0.2...2.1.0) (2026-02-21)


### Features

* **camera-view:** add configurable barcode types and optimize capture performance ([0951c4b](https://github.com/michaelwolz/capacitor-camera-view/commit/0951c4bf17a4882b29e3bc27976cafc4e8cabb6e))

## [2.0.2](https://github.com/michaelwolz/capacitor-camera-view/compare/2.0.1...2.0.2) (2026-02-20)


### Bug Fixes

* **ios:** wait for stop session method to complete ([745bbd8](https://github.com/michaelwolz/capacitor-camera-view/commit/745bbd83e9dd38946bc600dfe37683a9c27f130c))

## [2.0.1](https://github.com/michaelwolz/capacitor-camera-view/compare/2.0.0...2.0.1) (2026-02-20)


### Bug Fixes

* **ios:** wait for stop session method to complete ([179d4a2](https://github.com/michaelwolz/capacitor-camera-view/commit/179d4a278ccb51f7ffacec3ed3d80fda90bb5430))

# [2.0.0](https://github.com/michaelwolz/capacitor-camera-view/compare/v1.2.2...2.0.0) (2026-01-02)


* feat!: update to Capacitor 8 ([b05be94](https://github.com/michaelwolz/capacitor-camera-view/commit/b05be94aa3234442d677eadb0dfa80f7c40fecbd))


### Bug Fixes

* **ios:** remove deprecated code ([3ce847f](https://github.com/michaelwolz/capacitor-camera-view/commit/3ce847f088f62d604a197f3777790ac6fc1265a3))


### BREAKING CHANGES

* This plugin now supports only Capacitor 8.

## <small>1.2.2 (2025-09-23)</small>

* fix(ios): only initialize metadata output after session is running ([48a4648](https://github.com/michaelwolz/capacitor-camera-view/commit/48a4648))

## <small>1.2.1 (2025-08-19)</small>

* fix(ios): reflect correct torch state ([cee6000](https://github.com/michaelwolz/capacitor-camera-view/commit/cee6000))

## 1.2.0 (2025-08-04)

* feat(*): add torch support ([9dafbf2](https://github.com/michaelwolz/capacitor-camera-view/commit/9dafbf2))

## <small>1.1.1 (2025-07-25)</small>

* fix(ios): remove metadata output in case barcoded detection is disabled ([d6ef90d](https://github.com/michaelwolz/capacitor-camera-view/commit/d6ef90d))

## 1.1.0 (2025-07-23)

* feat(*): add saveToFile option for photo capture ([1a3de95](https://github.com/michaelwolz/capacitor-camera-view/commit/1a3de95))

## <small>1.0.4 (2025-07-10)</small>

* fix(android): remove experimental zero shutter lag capture mode ([08f5391](https://github.com/michaelwolz/capacitor-camera-view/commit/08f5391))

## <small>1.0.3 (2025-06-30)</small>

* fix(android): calculate image orientation based on display orientation ([756eb4c](https://github.com/michaelwolz/capacitor-camera-view/commit/756eb4c))

## <small>1.0.2 (2025-06-27)</small>

* fix(android): set image capture to minimize latency ([2ad728a](https://github.com/michaelwolz/capacitor-camera-view/commit/2ad728a))

## <small>1.0.1 (2025-06-26)</small>

* fix(android): improve image processing ([6770578](https://github.com/michaelwolz/capacitor-camera-view/commit/6770578))

## 1.0.0 (2025-06-08)

- First Release 🎉
