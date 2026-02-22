# [2.2.0-rc.1](https://github.com/michaelwolz/capacitor-camera-view/compare/2.1.0...2.2.0-rc.1) (2026-02-22)


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
