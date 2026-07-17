// KAMI style-v1 post process.
// Input contract: resolved single-sample linear-HDR colour, WebGPU device depth
// [0,1], and unit normals from one declared common space (world or view),
// encoded as normal * 0.5 + 0.5 (zero = no normal).
// Output is display-linear; use an sRGB render target for transfer encoding.
struct StylePostFxParams {
  inv_resolution: vec2<f32>,
  outline_width_px: f32,
  depth_threshold: f32,
  normal_threshold: f32,
  saturation: f32,
  contrast: f32,
  exposure: f32,
  outline_color: vec4<f32>,
  outline_enabled: u32,
  tone_map: u32,
  _pad: vec2<u32>,
};

@group(0) @binding(0) var scene_color: texture_2d<f32>;
@group(0) @binding(1) var scene_depth: texture_depth_2d;
@group(0) @binding(2) var scene_normal: texture_2d<f32>;
@group(0) @binding(3) var scene_sampler: sampler;
@group(0) @binding(4) var<uniform> params: StylePostFxParams;

struct VertexOutput {
  @builtin(position) clip_position: vec4<f32>,
  @location(0) uv: vec2<f32>,
};

@vertex
fn vs_main(@builtin(vertex_index) vertex_index: u32) -> VertexOutput {
  // Oversized fullscreen triangle: no vertex buffer or seam.
  var positions = array<vec2<f32>, 3>(
    vec2<f32>(-1.0, -1.0),
    vec2<f32>(3.0, -1.0),
    vec2<f32>(-1.0, 3.0));
  let p = positions[vertex_index];
  var out: VertexOutput;
  out.clip_position = vec4<f32>(p, 0.0, 1.0);
  out.uv = vec2<f32>(p.x * 0.5 + 0.5, 1.0 - (p.y * 0.5 + 0.5));
  return out;
}

fn valid_normal(encoded: vec3<f32>) -> bool {
  return dot(encoded, encoded) > 0.000001;
}

fn edge_at(center: vec2<i32>, offset: vec2<i32>, size: vec2<i32>,
           center_depth: f32, center_normal_raw: vec3<f32>) -> f32 {
  let coord = clamp(center + offset, vec2<i32>(0), size - vec2<i32>(1));
  let neighbor_depth = textureLoad(scene_depth, coord, 0);
  let depth_edge = step(params.depth_threshold,
                        abs(neighbor_depth - center_depth));
  let neighbor_normal_raw = textureLoad(scene_normal, coord, 0).xyz;
  var normal_edge = 0.0;
  if valid_normal(center_normal_raw) && valid_normal(neighbor_normal_raw) {
    let center_normal = normalize(center_normal_raw * 2.0 - 1.0);
    let neighbor_normal = normalize(neighbor_normal_raw * 2.0 - 1.0);
    normal_edge = step(params.normal_threshold,
                       1.0 - max(dot(center_normal, neighbor_normal), 0.0));
  }
  return max(depth_edge, normal_edge);
}

fn aces_fitted(color: vec3<f32>) -> vec3<f32> {
  let a = 2.51;
  let b = 0.03;
  let c = 2.43;
  let d = 0.59;
  let e = 0.14;
  return clamp((color * (a * color + b)) /
               (color * (c * color + d) + e), vec3<f32>(0.0), vec3<f32>(1.0));
}

fn grade(color: vec3<f32>) -> vec3<f32> {
  var graded = color * params.exposure;
  if params.tone_map == 1u {
    graded = aces_fitted(graded);
  }
  let luma = dot(graded, vec3<f32>(0.2126, 0.7152, 0.0722));
  graded = mix(vec3<f32>(luma), graded, params.saturation);
  return clamp((graded - vec3<f32>(0.5)) * params.contrast +
               vec3<f32>(0.5), vec3<f32>(0.0), vec3<f32>(1.0));
}

@fragment
fn fs_main(in: VertexOutput) -> @location(0) vec4<f32> {
  let source = textureSampleLevel(scene_color, scene_sampler, in.uv, 0.0);
  var result = grade(source.rgb);
  if params.outline_enabled == 1u {
    let size = vec2<i32>(textureDimensions(scene_depth));
    let center = clamp(vec2<i32>(in.uv * vec2<f32>(size)),
                       vec2<i32>(0), size - vec2<i32>(1));
    let center_depth = textureLoad(scene_depth, center, 0);
    let center_normal = textureLoad(scene_normal, center, 0).xyz;
    let radius = i32(clamp(round(params.outline_width_px), 1.0, 8.0));
    var edge = 0.0;
    edge = max(edge, edge_at(center, vec2<i32>( radius, 0), size, center_depth, center_normal));
    edge = max(edge, edge_at(center, vec2<i32>(-radius, 0), size, center_depth, center_normal));
    edge = max(edge, edge_at(center, vec2<i32>(0,  radius), size, center_depth, center_normal));
    edge = max(edge, edge_at(center, vec2<i32>(0, -radius), size, center_depth, center_normal));
    edge = max(edge, edge_at(center, vec2<i32>( radius,  radius), size, center_depth, center_normal));
    edge = max(edge, edge_at(center, vec2<i32>(-radius,  radius), size, center_depth, center_normal));
    edge = max(edge, edge_at(center, vec2<i32>( radius, -radius), size, center_depth, center_normal));
    edge = max(edge, edge_at(center, vec2<i32>(-radius, -radius), size, center_depth, center_normal));
    result = mix(result, params.outline_color.rgb,
                 clamp(edge * params.outline_color.a, 0.0, 1.0));
  }
  return vec4<f32>(result, source.a);
}
