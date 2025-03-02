export interface CameraViewPlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
}
