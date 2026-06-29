//! services — platform-neutral achievement/stat/presence seam (ADR-0049).
//!
//! Generalizes the Valve-only seam of ADR-0048 to **every store**: Steam
//! (desktop), Game Center (iOS), Google Play Games (Android), PSN Trophies
//! (PS5), Nintendo (Switch). The guest never branches on platform — it names a
//! **logical key** (`"first_blood"`), and the active backend maps it to that
//! store's id via the EDN catalog (`services.edn`). One write-once `game.wasm`
//! ships to all of them.
//!
//! ## Determinism (load-bearing)
//!
//! The guest reaches services only through the `kami:engine/services`
//! **output-only** interface (`achievement-unlock` / `stat-set` / `presence-set`,
//! see `wit/kami-game/world.wit`). Nothing flows back into the i64 sim, so a game
//! runs bit-identically across stores *and* across the wasmtime/wasmi backends
//! (ADR-0037 golden-frame parity) — including the no-JIT consoles, where the same
//! output-only queue works unchanged. The host buffers each call as a
//! [`ServiceEvent`] the engine drains into a [`ServicesBackend`].
//!
//! ## Backends (all gated; real platform calls are the integrator's NDA/SDK work)
//!
//! - [`StubServices`] — default, no-op + `log`, linked everywhere (CI, web,
//!   off-platform desktop, headless golden-frame). Keeps the imports resolving
//!   and the sim deterministic without any SDK.
//! - `steam-sdk` → `steamworks-rs` (the one real binding; needs the SDK + App ID
//!   + Steam client, untested in CI — ADR-0048).
//! - `psn-sdk` / `switch-sdk` / `gamecenter` / `googleplay` → in-repo **skeletons**
//!   that resolve the id and log; the actual NpTrophy / Nintendo / GameKit /
//!   Play-Games-Services calls are filled in by the per-platform native shell
//!   (ADR-0037 §4), which holds the NDA SDK / objc / JNI. They link no external
//!   crate, so they compile under their feature without a device.

use std::collections::HashMap;

/// A platform-service effect emitted by the guest during a tick. Output-only:
/// the engine drains these after `tick` and forwards them to a [`ServicesBackend`].
/// Strings are **logical keys** — the backend resolves them to store ids.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ServiceEvent {
    /// Unlock an achievement by logical key.
    AchievementUnlock(String),
    /// Set an integer stat to an absolute value, by logical key.
    StatSet(String, i64),
    /// Set a rich-presence key/value pair (empty value clears the key).
    PresenceSet(String, String),
}

/// Logical-key → active-store-id resolution, built from `services.edn` for the
/// target store (the host/shell loads it; see `scripts/services.clj`). The stub
/// ignores it; real backends use it to turn the guest's `"first_blood"` into the
/// store's id (`"FIRST_BLOOD"` on Steam, `1` on PSN, `"grp.first_blood"` on Game
/// Center, …). An unmapped key passes through unchanged.
#[derive(Debug, Default, Clone)]
pub struct ServiceIds {
    pub achievements: HashMap<String, String>,
    pub stats: HashMap<String, String>,
}

impl ServiceIds {
    /// Resolve an achievement logical key to the store id (or pass through).
    pub fn achievement<'a>(&'a self, key: &'a str) -> &'a str {
        self.achievements.get(key).map(String::as_str).unwrap_or(key)
    }
    /// Resolve a stat logical key to the store id (or pass through).
    pub fn stat<'a>(&'a self, key: &'a str) -> &'a str {
        self.stats.get(key).map(String::as_str).unwrap_or(key)
    }
}

/// The platform-services sink. One method per [`ServiceEvent`]; an impl forwards
/// to a store (or no-ops). Infallible at this seam — platform telemetry must
/// never break gameplay, so impls swallow/log their own errors.
pub trait ServicesBackend {
    fn achievement_unlock(&mut self, _key: &str) {}
    fn stat_set(&mut self, _key: &str, _value: i64) {}
    fn presence_set(&mut self, _key: &str, _value: &str) {}

    /// Drain a batch produced by one tick. Default fans out per event; a backend
    /// can override to pump its callback loop / coalesce a store flush per frame.
    fn apply(&mut self, events: Vec<ServiceEvent>) {
        for e in events {
            match e {
                ServiceEvent::AchievementUnlock(k) => self.achievement_unlock(&k),
                ServiceEvent::StatSet(k, v) => self.stat_set(&k, v),
                ServiceEvent::PresenceSet(k, v) => self.presence_set(&k, &v),
            }
        }
    }
}

/// Default backend: log + no-op. Linked on every target so the
/// `kami:engine/services` imports always resolve and the sim stays deterministic
/// off-platform.
#[derive(Debug, Default)]
pub struct StubServices;

impl ServicesBackend for StubServices {
    fn achievement_unlock(&mut self, key: &str) {
        log::debug!("[services stub] achievement-unlock {key:?}");
    }
    fn stat_set(&mut self, key: &str, value: i64) {
        log::debug!("[services stub] stat-set {key:?} = {value}");
    }
    fn presence_set(&mut self, key: &str, value: &str) {
        log::debug!("[services stub] presence-set {key:?} = {value:?}");
    }
}

/// Construct the backend a host should use, given the logical→store id map from
/// `services.edn`. With no store feature (the default — CI, web, off-platform
/// desktop) this is [`StubServices`]. With a store feature on, the matching
/// backend is selected; a backend that needs a live client (Steam) falls back to
/// the stub if none is running. Boxed so the per-frame call site is store-agnostic.
pub fn default_backend(ids: ServiceIds) -> Box<dyn ServicesBackend> {
    #[cfg(feature = "steam-sdk")]
    {
        match steam::SteamworksServices::new(ids.clone()) {
            Ok(s) => return Box::new(s),
            Err(e) => log::warn!("[services] no Steam client ({e}); using stub"),
        }
    }
    #[cfg(feature = "psn-sdk")]
    {
        return Box::new(psn::PsnServices::new(ids.clone()));
    }
    #[cfg(feature = "switch-sdk")]
    {
        return Box::new(switch::SwitchServices::new(ids.clone()));
    }
    #[cfg(feature = "gamecenter")]
    {
        return Box::new(gamecenter::GameCenterServices::new(ids.clone()));
    }
    #[cfg(feature = "googleplay")]
    {
        return Box::new(googleplay::GooglePlayServices::new(ids.clone()));
    }
    let _ = &ids;
    Box::new(StubServices)
}

// ---------------------------------------------------------------------------
// Steam — the one REAL binding. GATED behind `steam-sdk` (ADR-0048/0049 Phase 3).
// Needs the Steamworks SDK + App ID (steam_appid.txt) + a running client — not
// in CI — so it's feature-off and unvalidated. Verify against your pinned
// `steamworks` crate version when enabling.
// ---------------------------------------------------------------------------
#[cfg(feature = "steam-sdk")]
mod steam {
    use super::{ServiceEvent, ServiceIds, ServicesBackend};

    pub struct SteamworksServices {
        client: steamworks::Client,
        single: steamworks::SingleClient,
        ids: ServiceIds,
        stats_ready: bool,
    }

    impl SteamworksServices {
        pub fn new(ids: ServiceIds) -> Result<Self, String> {
            let (client, single) = steamworks::Client::init().map_err(|e| e.to_string())?;
            client.user_stats().request_current_stats();
            Ok(Self { client, single, ids, stats_ready: false })
        }
    }

    impl ServicesBackend for SteamworksServices {
        fn achievement_unlock(&mut self, key: &str) {
            let stats = self.client.user_stats();
            let id = self.ids.achievement(key);
            if let Err(e) = stats.achievement(id).set() {
                log::warn!("[steam] unlock {id:?} failed: {e:?}");
            }
            let _ = stats.store_stats();
        }
        fn stat_set(&mut self, key: &str, value: i64) {
            let stats = self.client.user_stats();
            let id = self.ids.stat(key);
            if let Err(e) = stats.set_stat_i32(id, value as i32) {
                log::warn!("[steam] set-stat {id:?} failed: {e:?}");
            }
            let _ = stats.store_stats();
        }
        fn presence_set(&mut self, key: &str, value: &str) {
            let v = if value.is_empty() { None } else { Some(value) };
            self.client.friends().set_rich_presence(key, v);
        }
        fn apply(&mut self, events: Vec<ServiceEvent>) {
            self.single.run_callbacks();
            if !self.stats_ready {
                self.client.user_stats().request_current_stats();
                self.stats_ready = true;
            }
            for e in events {
                match e {
                    ServiceEvent::AchievementUnlock(k) => self.achievement_unlock(&k),
                    ServiceEvent::StatSet(k, v) => self.stat_set(&k, v),
                    ServiceEvent::PresenceSet(k, v) => self.presence_set(&k, &v),
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Console / mobile store SKELETONS. Each resolves the logical key and logs the
// store call it WOULD make; the real API is wired by the per-platform native
// shell (ADR-0037 §4), which owns the NDA SDK / objc / JNI. No external crate, so
// these compile under their feature without a device — the integration point is
// the one method body marked `TODO(shell)`.
// ---------------------------------------------------------------------------
#[cfg(feature = "psn-sdk")]
mod psn {
    use super::{ServiceIds, ServicesBackend};
    /// PSN Trophies (Sony NpTrophy). Trophy ids are small integers; the catalog
    /// maps logical key → trophy id string, parsed here.
    pub struct PsnServices {
        ids: ServiceIds,
    }
    impl PsnServices {
        pub fn new(ids: ServiceIds) -> Self {
            Self { ids }
        }
    }
    impl ServicesBackend for PsnServices {
        fn achievement_unlock(&mut self, key: &str) {
            let id = self.ids.achievement(key);
            match id.parse::<i32>() {
                Ok(trophy) => {
                    // TODO(shell): sceNpTrophyUnlockTrophy(ctx, handle, trophy, &platinum)
                    log::info!("[psn] unlock trophy #{trophy} (key {key:?})");
                }
                Err(_) => log::warn!("[psn] achievement {key:?} → non-numeric trophy id {id:?}"),
            }
        }
        fn stat_set(&mut self, key: &str, value: i64) {
            // PSN has no generic stats API; games persist their own save data.
            log::debug!("[psn] stat {key:?}={value} (game-side save, no NpTrophy stat)");
        }
        fn presence_set(&mut self, key: &str, value: &str) {
            // TODO(shell): sceNpSetPresence — store-level presence string.
            log::debug!("[psn] presence {key:?}={value:?}");
        }
    }
}

#[cfg(feature = "switch-sdk")]
mod switch {
    use super::{ServiceIds, ServicesBackend};
    /// Nintendo (Switch) has no OS achievement service; titles implement their
    /// own progression and optionally report via NPLN. We resolve + log so the
    /// game's logical keys are visible; the shell decides what to persist.
    pub struct SwitchServices {
        ids: ServiceIds,
    }
    impl SwitchServices {
        pub fn new(ids: ServiceIds) -> Self {
            Self { ids }
        }
    }
    impl ServicesBackend for SwitchServices {
        fn achievement_unlock(&mut self, key: &str) {
            let id = self.ids.achievement(key);
            // TODO(shell): persist to the title's save / NPLN progression.
            log::info!("[switch] achievement {key:?} → {id:?} (title-side)");
        }
        fn stat_set(&mut self, key: &str, value: i64) {
            log::debug!("[switch] stat {key:?}={value} (title-side save)");
        }
        fn presence_set(&mut self, key: &str, value: &str) {
            log::debug!("[switch] presence {key:?}={value:?}");
        }
    }
}

#[cfg(feature = "gamecenter")]
mod gamecenter {
    use super::{ServiceIds, ServicesBackend};
    /// Apple Game Center (iOS GameKit). Achievement ids are reverse-DNS strings.
    pub struct GameCenterServices {
        ids: ServiceIds,
    }
    impl GameCenterServices {
        pub fn new(ids: ServiceIds) -> Self {
            Self { ids }
        }
    }
    impl ServicesBackend for GameCenterServices {
        fn achievement_unlock(&mut self, key: &str) {
            let id = self.ids.achievement(key);
            // TODO(shell): GKAchievement(identifier: id){ percentComplete=100 };
            //              GKAchievement.report([a]) via the Swift shell (objc2).
            log::info!("[gamecenter] report achievement {id:?} (key {key:?}) 100%");
        }
        fn stat_set(&mut self, key: &str, value: i64) {
            // Game Center models stats as leaderboard scores.
            let id = self.ids.stat(key);
            // TODO(shell): GKLeaderboard.submitScore(value, context, player, [id])
            log::debug!("[gamecenter] submit score {value} to {id:?} (key {key:?})");
        }
        fn presence_set(&mut self, key: &str, value: &str) {
            log::debug!("[gamecenter] presence {key:?}={value:?} (no GK presence API)");
        }
    }
}

#[cfg(feature = "googleplay")]
mod googleplay {
    use super::{ServiceIds, ServicesBackend};
    /// Google Play Games Services (Android). Ids are opaque "CgkI…" strings.
    pub struct GooglePlayServices {
        ids: ServiceIds,
    }
    impl GooglePlayServices {
        pub fn new(ids: ServiceIds) -> Self {
            Self { ids }
        }
    }
    impl ServicesBackend for GooglePlayServices {
        fn achievement_unlock(&mut self, key: &str) {
            let id = self.ids.achievement(key);
            // TODO(shell): AchievementsClient.unlock(id) via JNI from the
            //              NativeActivity shell.
            log::info!("[googleplay] unlock achievement {id:?} (key {key:?})");
        }
        fn stat_set(&mut self, key: &str, value: i64) {
            let id = self.ids.stat(key);
            // TODO(shell): LeaderboardsClient.submitScore(id, value) via JNI.
            log::debug!("[googleplay] submit score {value} to {id:?} (key {key:?})");
        }
        fn presence_set(&mut self, key: &str, value: &str) {
            log::debug!("[googleplay] presence {key:?}={value:?}");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Default)]
    struct Recorder(Vec<ServiceEvent>);
    impl ServicesBackend for Recorder {
        fn achievement_unlock(&mut self, key: &str) {
            self.0.push(ServiceEvent::AchievementUnlock(key.into()));
        }
        fn stat_set(&mut self, key: &str, value: i64) {
            self.0.push(ServiceEvent::StatSet(key.into(), value));
        }
        fn presence_set(&mut self, key: &str, value: &str) {
            self.0.push(ServiceEvent::PresenceSet(key.into(), value.into()));
        }
    }

    #[test]
    fn apply_fans_out_in_order() {
        let batch = vec![
            ServiceEvent::AchievementUnlock("first_blood".into()),
            ServiceEvent::StatSet("kills".into(), 42),
            ServiceEvent::PresenceSet("status".into(), "in_combat".into()),
        ];
        let mut rec = Recorder::default();
        rec.apply(batch.clone());
        assert_eq!(rec.0, batch);
    }

    #[test]
    fn stub_is_infallible_noop() {
        let mut s = StubServices;
        s.apply(vec![
            ServiceEvent::AchievementUnlock("a".into()),
            ServiceEvent::StatSet("s".into(), -1),
            ServiceEvent::PresenceSet("k".into(), "".into()),
        ]);
    }

    #[test]
    fn ids_resolve_or_pass_through() {
        let mut ids = ServiceIds::default();
        ids.achievements.insert("first_blood".into(), "FIRST_BLOOD".into());
        assert_eq!(ids.achievement("first_blood"), "FIRST_BLOOD"); // mapped
        assert_eq!(ids.achievement("unknown"), "unknown"); // pass-through
        assert_eq!(ids.stat("kills"), "kills");
    }
}
