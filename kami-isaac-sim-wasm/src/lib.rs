//! kami-isaac-sim-wasm — JS-callable `isaacsim.core.api` World / Articulation /
//! ArticulationController, generalized from `kami-cartpole-wasm` (fixed
//! Cartpole topology only) to load ANY URDF, backed directly by
//! `kami-genesis::IsaacWorld` (RNEA / CRBA Featherstone dynamics, PD control —
//! 208 tests in kami-genesis alone).
//!
//! Demonstrates the ADR-2607011300 migration path: `@etzhayyim/kami-nv-compat`
//! reimplements this dynamics / PD-control surface from scratch in
//! TypeScript (`dynamics/`, `controllers/`, `actions/`, `e7m-sim/`); this
//! crate is the wasm-bindgen bridge that would let it delegate to the real,
//! more rigorously tested Rust engine instead of duplicating it. This first
//! slice proves the bridge works end-to-end (Rust → wasm32 → Node/JS); wiring
//! it into `isaac-sim.ts` to replace the TS engine is a follow-up (it needs a
//! sync-vs-async-WASM-loading decision the existing 486 synchronous TS tests
//! don't currently accommodate).

#[cfg(target_arch = "wasm32")]
use wasm_bindgen::prelude::*;

use kami_genesis::{ArticulationAction, ArticulationHandle, ArticulationView, ArticulationViewMut, IsaacWorld};

/// JS-callable handle around an `IsaacWorld` scene of articulations.
#[cfg_attr(target_arch = "wasm32", wasm_bindgen)]
pub struct IsaacWorldHandle {
    world: IsaacWorld,
}

#[cfg_attr(target_arch = "wasm32", wasm_bindgen)]
impl IsaacWorldHandle {
    /// `new IsaacWorldHandle(physicsDt)` ~ `isaacsim.core.api.World(physics_dt=...)`.
    #[cfg_attr(target_arch = "wasm32", wasm_bindgen(constructor))]
    pub fn new(physics_dt: f32) -> IsaacWorldHandle {
        IsaacWorldHandle { world: IsaacWorld::new(physics_dt) }
    }

    /// `world.scene.add(Articulation(urdf))` — parses `urdf_text` and
    /// registers it. Returns an opaque handle index for the other methods.
    /// Panics on malformed URDF or an unsupported topology, matching the
    /// `kami-cartpole-wasm` precedent's `.expect(...)` style; graceful
    /// `Result`-based error propagation to JS is a follow-up.
    pub fn add_articulation_from_urdf(&mut self, urdf_text: &str) -> u32 {
        let sys = kami_articulated::parse_urdf(urdf_text).expect("parse_urdf");
        let h = self.world.add_articulation(sys).expect("add_articulation");
        h.0 as u32
    }

    /// `world.step(render=False)` — advance physics by one `physics_dt`.
    pub fn step(&mut self) {
        self.world.step();
    }

    /// `world.reset()` — zero all registered articulations' joint state.
    pub fn reset(&mut self) {
        self.world.reset();
    }

    /// `world.current_time`.
    pub fn current_time(&self) -> f32 {
        self.world.current_time()
    }

    /// `world.current_time_step_index`.
    pub fn current_time_step_index(&self) -> u32 {
        self.world.current_time_step_index() as u32
    }

    /// `world.get_physics_dt()`.
    pub fn get_physics_dt(&self) -> f32 {
        self.world.get_physics_dt()
    }

    /// `articulation.num_dof` (property).
    pub fn num_dof(&self, handle: u32) -> u32 {
        self.view(handle).map(|v| v.num_dof() as u32).unwrap_or(0)
    }

    /// `articulation.dof_names` (property).
    pub fn dof_names(&self, handle: u32) -> Vec<String> {
        self.view(handle).map(|v| v.dof_names()).unwrap_or_default()
    }

    /// `articulation.get_joint_positions()` → `[n_dof]`.
    pub fn get_joint_positions(&self, handle: u32) -> Vec<f32> {
        self.view(handle).map(|v| v.get_joint_positions()).unwrap_or_default()
    }

    /// `articulation.get_joint_velocities()` → `[n_dof]`.
    pub fn get_joint_velocities(&self, handle: u32) -> Vec<f32> {
        self.view(handle).map(|v| v.get_joint_velocities()).unwrap_or_default()
    }

    /// `articulation.set_joint_efforts(efforts)`.
    pub fn set_joint_efforts(&mut self, handle: u32, efforts: Vec<f32>) {
        if let Some(mut v) = self.view_mut(handle) {
            v.set_joint_efforts(&efforts);
        }
    }

    /// `articulation.set_joint_positions(positions)` — seed/teleport.
    pub fn set_joint_positions(&mut self, handle: u32, positions: Vec<f32>) {
        if let Some(mut v) = self.view_mut(handle) {
            v.set_joint_positions(&positions);
        }
    }

    /// `articulation.set_joint_velocities(velocities)`.
    pub fn set_joint_velocities(&mut self, handle: u32, velocities: Vec<f32>) {
        if let Some(mut v) = self.view_mut(handle) {
            v.set_joint_velocities(&velocities);
        }
    }

    /// `RigidPrimView.get_world_poses(link)` → `[px,py,pz, qw,qx,qy,qz]` (7
    /// floats), or an empty array if the link/handle is unknown.
    pub fn get_world_pose(&self, handle: u32, link_name: &str) -> Vec<f32> {
        self.view(handle)
            .and_then(|v| v.get_world_pose(link_name))
            .map(|(p, q)| vec![p[0], p[1], p[2], q[0], q[1], q[2], q[3]])
            .unwrap_or_default()
    }

    /// `controller.set_gains(kps, kds)`.
    pub fn set_gains(&mut self, handle: u32, kps: Vec<f32>, kds: Vec<f32>) {
        if let Some(mut c) = self.world.get_articulation_controller(ArticulationHandle(handle as usize)) {
            c.set_gains(kps, kds);
        }
    }

    /// `controller.set_max_efforts(max_efforts)`.
    pub fn set_max_efforts(&mut self, handle: u32, max_efforts: Vec<f32>) {
        if let Some(mut c) = self.world.get_articulation_controller(ArticulationHandle(handle as usize)) {
            c.set_max_efforts(max_efforts);
        }
    }

    /// `controller.apply_action(ArticulationAction(joint_positions=targets))`
    /// — PD-tracked position targets, using the gains from `set_gains`.
    pub fn apply_position_action(&mut self, handle: u32, targets: Vec<f32>) {
        if let Some(mut c) = self.world.get_articulation_controller(ArticulationHandle(handle as usize)) {
            c.apply_action(&ArticulationAction::positions(targets));
        }
    }

    /// `controller.apply_action(ArticulationAction(joint_efforts=targets))`
    /// — direct feedforward effort, clamped by `set_max_efforts`.
    pub fn apply_effort_action(&mut self, handle: u32, targets: Vec<f32>) {
        if let Some(mut c) = self.world.get_articulation_controller(ArticulationHandle(handle as usize)) {
            c.apply_action(&ArticulationAction::efforts(targets));
        }
    }
}

// Private helpers in a separate (non-wasm_bindgen) impl block: `wasm_bindgen`
// only ever sees the block above, so these borrowed-view return types (not
// wasm-ABI-compatible) never reach the macro.
impl IsaacWorldHandle {
    fn view(&self, handle: u32) -> Option<ArticulationView<'_>> {
        self.world.articulation(ArticulationHandle(handle as usize))
    }

    fn view_mut(&mut self, handle: u32) -> Option<ArticulationViewMut<'_>> {
        self.world.articulation_mut(ArticulationHandle(handle as usize))
    }
}

/// `kamiIsaacSimWasmVersion()` — version banner for HUD/audit strings.
#[cfg_attr(target_arch = "wasm32", wasm_bindgen(js_name = kamiIsaacSimWasmVersion))]
pub fn kami_isaac_sim_wasm_version() -> String {
    format!("{}@{}", kami_genesis::ADR, kami_genesis::PHASE)
}

#[cfg(test)]
mod tests {
    use super::*;

    const CARTPOLE_URDF: &str = include_str!("../../fixtures/cartpole/cartpole.urdf");
    const ARM3_URDF: &str = include_str!("../../fixtures/arm3/arm3.urdf");

    #[test]
    fn cartpole_lifecycle() {
        let mut w = IsaacWorldHandle::new(1.0 / 60.0);
        let h = w.add_articulation_from_urdf(CARTPOLE_URDF);
        assert_eq!(w.num_dof(h), 2);
        assert_eq!(w.dof_names(h), vec!["slider_to_cart", "cart_to_pole"]);

        let q0 = w.get_joint_positions(h);
        for _ in 0..30 {
            w.set_joint_efforts(h, vec![10.0, 0.0]);
            w.step();
        }
        let q1 = w.get_joint_positions(h);
        assert!(q1[0] > q0[0] + 0.01, "cart did not move: {q0:?} -> {q1:?}");
    }

    #[test]
    fn pd_controller_drives_cart_to_target() {
        // Mirrors kami-genesis's own isaac_controller_pd_drives_cart_to_target,
        // through the wasm-bindgen-shaped surface instead of the raw crate API.
        let mut w = IsaacWorldHandle::new(1.0 / 60.0);
        let h = w.add_articulation_from_urdf(CARTPOLE_URDF);
        w.set_gains(h, vec![200.0, 0.0], vec![20.0, 0.0]);
        for _ in 0..600 {
            w.apply_position_action(h, vec![0.5, 0.0]);
            w.step();
        }
        let q = w.get_joint_positions(h);
        assert!((q[0] - 0.5).abs() < 0.05, "PD did not reach target x: {q:?}");
    }

    #[test]
    fn arm_pose_and_reset_roundtrip() {
        let mut w = IsaacWorldHandle::new(1.0 / 240.0);
        let h = w.add_articulation_from_urdf(ARM3_URDF);
        assert_eq!(w.num_dof(h), 3);

        let pose = w.get_world_pose(h, "l1");
        assert_eq!(pose.len(), 7, "expected [px,py,pz,qw,qx,qy,qz]");
        let qn = (pose[3] * pose[3] + pose[4] * pose[4] + pose[5] * pose[5] + pose[6] * pose[6]).sqrt();
        assert!((qn - 1.0).abs() < 1e-4, "quat not unit: {pose:?}");

        assert!(w.get_world_pose(h, "nope").is_empty());

        for _ in 0..20 {
            w.set_joint_efforts(h, vec![5.0, 0.0, 0.0]);
            w.step();
        }
        assert!(w.get_joint_positions(h)[0].abs() > 1e-3);
        w.reset();
        assert!(w.get_joint_positions(h).iter().all(|v| v.abs() < 1e-6));
        assert_eq!(w.current_time_step_index(), 0);
    }

    #[test]
    fn version_banner() {
        assert_eq!(kami_isaac_sim_wasm_version(), format!("{}@{}", kami_genesis::ADR, kami_genesis::PHASE));
    }
}
