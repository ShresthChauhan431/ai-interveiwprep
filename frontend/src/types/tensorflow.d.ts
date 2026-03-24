// FIX: Type declarations for TensorFlow packages used by proctoring face detection (Issue 3)
// These are dynamically imported in useProctoring.ts and may not be installed,
// so we provide minimal type stubs to satisfy the TypeScript compiler.

declare module '@tensorflow/tfjs' {
  // FIX: Minimal type stub — TensorFlow.js is loaded for side effects only (backend registration)
  const tf: any;
  export default tf;
  export * from '@tensorflow/tfjs';
}

declare module '@tensorflow-models/blazeface' {
  // FIX: Minimal type declarations for BlazeFace face detection model
  export interface NormalizedFace {
    topLeft: [number, number] | Float32Array;
    bottomRight: [number, number] | Float32Array;
    landmarks?: Array<[number, number]> | Float32Array;
    probability: number | Float32Array;
  }

  export interface BlazeFaceModel {
    estimateFaces(
      input: HTMLVideoElement | HTMLImageElement | HTMLCanvasElement | ImageData,
      returnTensors?: boolean,
    ): Promise<NormalizedFace[]>;
    dispose(): void;
  }

  export function load(config?: {
    maxFaces?: number;
    inputWidth?: number;
    inputHeight?: number;
    iouThreshold?: number;
    scoreThreshold?: number;
  }): Promise<BlazeFaceModel>;
}
