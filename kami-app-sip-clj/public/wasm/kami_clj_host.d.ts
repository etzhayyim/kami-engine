/* tslint:disable */
/* eslint-disable */

/**
 * Host state. One per canvas.
 */
export class KamiCljHost {
    private constructor();
    free(): void;
    [Symbol.dispose](): void;
    /**
     * Bootstrap a host bound to `canvas`. Async (adapter/device request).
     */
    static create(canvas: HTMLCanvasElement): Promise<KamiCljHost>;
    /**
     * Upload material params under `id`. `params[0..4]` = albedo RGBA (the rest
     * is reserved; the default shader only reads albedo).
     */
    register_material(id: string, params: Float32Array): void;
    /**
     * Upload a mesh once under `id`. `vertices` is interleaved pos3+norm3+uv2.
     */
    register_mesh(id: string, vertices: Float32Array, indices: Uint32Array): void;
    /**
     * Register a clj-authored WGSL shader (from `kami.wgsl/emit`) as a pipeline
     * under `id`. `layout` is reserved for the bind-group plan.
     */
    register_shader(id: string, wgsl: string, _layout: string): void;
    /**
     * Resize the surface + depth target.
     */
    resize(width: number, height: number): void;
    /**
     * Render one frame. `meta_json` is the `kami.ipc/pack` `:meta` draw-table;
     * `data` is the KAMI columnar buffer (camera + instance matrices).
     */
    submit_frame(meta_json: string, data: Uint8Array): void;
}

export type InitInput = RequestInfo | URL | Response | BufferSource | WebAssembly.Module;

export interface InitOutput {
    readonly memory: WebAssembly.Memory;
    readonly __wbg_kamicljhost_free: (a: number, b: number) => void;
    readonly kamicljhost_create: (a: any) => any;
    readonly kamicljhost_register_material: (a: number, b: number, c: number, d: number, e: number) => void;
    readonly kamicljhost_register_mesh: (a: number, b: number, c: number, d: number, e: number, f: number, g: number) => void;
    readonly kamicljhost_register_shader: (a: number, b: number, c: number, d: number, e: number, f: number, g: number) => void;
    readonly kamicljhost_resize: (a: number, b: number, c: number) => void;
    readonly kamicljhost_submit_frame: (a: number, b: number, c: number, d: number, e: number) => [number, number];
    readonly wasm_bindgen__closure__destroy__h40096cae267b238e: (a: number, b: number) => void;
    readonly wasm_bindgen__closure__destroy__hbda59b3b1fd1515f: (a: number, b: number) => void;
    readonly wasm_bindgen__closure__destroy__h5aaff8ea3f358cc2: (a: number, b: number) => void;
    readonly wasm_bindgen__convert__closures_____invoke__h4f3d1bb8ebec98d2: (a: number, b: number, c: any) => [number, number];
    readonly wasm_bindgen__convert__closures_____invoke__h07c5524e2faf0ff9: (a: number, b: number, c: any, d: any) => void;
    readonly wasm_bindgen__convert__closures_____invoke__hc288264df7df8be9: (a: number, b: number, c: any) => void;
    readonly wasm_bindgen__convert__closures_____invoke__h0075a575bc291c5b: (a: number, b: number, c: any) => void;
    readonly wasm_bindgen__convert__closures_____invoke__ha5793ca85b62359f: (a: number, b: number) => number;
    readonly __wbindgen_malloc: (a: number, b: number) => number;
    readonly __wbindgen_realloc: (a: number, b: number, c: number, d: number) => number;
    readonly __wbindgen_exn_store: (a: number) => void;
    readonly __externref_table_alloc: () => number;
    readonly __wbindgen_externrefs: WebAssembly.Table;
    readonly __externref_table_dealloc: (a: number) => void;
    readonly __wbindgen_start: () => void;
}

export type SyncInitInput = BufferSource | WebAssembly.Module;

/**
 * Instantiates the given `module`, which can either be bytes or
 * a precompiled `WebAssembly.Module`.
 *
 * @param {{ module: SyncInitInput }} module - Passing `SyncInitInput` directly is deprecated.
 *
 * @returns {InitOutput}
 */
export function initSync(module: { module: SyncInitInput } | SyncInitInput): InitOutput;

/**
 * If `module_or_path` is {RequestInfo} or {URL}, makes a request and
 * for everything else, calls `WebAssembly.instantiate` directly.
 *
 * @param {{ module_or_path: InitInput | Promise<InitInput> }} module_or_path - Passing `InitInput` directly is deprecated.
 *
 * @returns {Promise<InitOutput>}
 */
export default function __wbg_init (module_or_path?: { module_or_path: InitInput | Promise<InitInput> } | InitInput | Promise<InitInput>): Promise<InitOutput>;
