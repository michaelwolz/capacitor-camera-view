import { CameraView } from 'capacitor-camera-view';

window.startCamera = async () => {
    await CameraView.start({ cameraPosition: 'back' });
}

window.stopCamera = async () => {
    await CameraView.stop();
}

window.capture = async () => {
    const result = await CameraView.capture();
    console.log(result);
}

window.switchCamera = async () => {
    await CameraView.switchCamera();
}
