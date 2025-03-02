import { CameraView } from 'capacitor-camera-view';

window.testEcho = () => {
    const inputValue = document.getElementById("echoInput").value;
    CameraView.echo({ value: inputValue })
}
