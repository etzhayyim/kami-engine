# ADR-0049 — Kotoba 3D suite: commercial CAD/CAE maturity programme

- Status: accepted (roadmap; implementation gates are not yet all satisfied)
- Date: 2026-07-12
- Builds on: ADR-0040 (EDN/Datomic), ADR-0042 (cross-platform CLJ/EDN), ADR-0048
  (standard interchange), network-isekai ADR-0001/0008, and the shipped
  `kami-engine-modeling` / `kami-app-modeler` vertical slice

## Context

The Kotoba 3D suite now has a real browser modeling vertical slice: immutable EDN meshes and
scene objects, dimension-driven sketch constraints, polygon selection and topology editing,
BSP Boolean operations, UV/image materials, a reorderable non-destructive modifier stack,
undo/redo and recovery, glTF exchange, and WebGPU-first rendering with WebGL2 fallback. A
modeler project can also become a `kami-engine` scene and a forkable `network-isekai` game or
social space. The public E2E gate exercises the modeling chain and a 100-object scene.

That is **5/5 for the current browser polygon-modeler scope**, but it is not equivalent to a
commercial mechanical CAD/CAE system or a mature DCC package. The remaining gap is not one
feature. It is seven interacting product systems:

1. exact NURBS/B-rep modeling and STEP exchange;
2. assemblies and kinematic constraints;
3. associative manufacturing drawings;
4. CAE preparation, solving and result review;
5. a production-grade modifier/dependency graph;
6. very-large-scene streaming and optimization;
7. durable multi-user collaboration and auditable history.

Without one architectural decision these can become seven incompatible object models. This
ADR defines a single progression, explicit maturity gates, and the boundary between portable
Kotoba/CLJC data and replaceable heavy-compute adapters.

## Decision

### 1. One document graph; mesh, B-rep, drawing, simulation and world are projections

The canonical artifact is a versioned immutable EDN document, not a renderer mesh:

```clojure
{:document/id "k1..."
 :document/schema 1
 :document/units :mm
 :document/nodes {#uuid "..." {:node/kind :feature ...}}
 :document/roots [#uuid "..."]
 :document/configurations {}
 :document/provenance {:parents ["k1..."] :author "did:plc:..."}}
```

Stable UUIDs identify sketches, curves, surfaces, B-rep topology, features, components,
drawing views and CAE entities. References use UUIDs, never vector offsets. Polygon meshes,
render-IR, collision meshes, drawing geometry and solver decks are deterministic cached
**projections** with source revision, tolerances and generator version recorded. EDN remains
the authoring/interchange brain; dense arrays may live in content-addressed binary payloads.

Every numerical value that crosses a module boundary carries units. Geometry operations also
carry an explicit modeling tolerance. Silent unit conversion and tolerance guessing fail
closed.

### 2. NURBS/B-rep and STEP: exact authoring model, tessellated runtime model

Add a portable `kotoba-lang/cad-kernel` contract with these first-class values:

- NURBS curves/surfaces: degree, knot vector, control points, weights and parameter domain;
- analytic curves/surfaces: line, circle, ellipse, plane, cylinder, cone, sphere and torus;
- B-rep topology: body, shell, face, loop, coedge, edge and vertex with orientation;
- feature graph: sketch, datum, extrude, revolve, sweep, loft, fillet, chamfer, shell,
  draft, Boolean and pattern;
- deterministic tessellation from exact faces to `kami-engine-modeling` meshes, retaining
  face/edge provenance for picking and downstream drawings/CAE.

STEP import/export targets ISO 10303 application profiles in stages: AP203/AP214 compatibility,
then AP242 managed model-based 3D engineering. The suite will use an independently tested STEP
adapter behind the CLJC contract; it will not invent a STEP-like format or claim conformance
from a text parser alone. Native/WASM adapters are allowed for exact Boolean, healing and STEP
codec workloads, but their observable input/output is the same EDN contract and golden corpus.

Completion gate: analytic and trimmed-surface round trips; topology/volume within declared
tolerance; unit/assembly metadata retained; at least 100 public STEP corpus files import,
heal, tessellate and re-export; malformed files fail with structured diagnostics.

### 3. Assemblies: definitions, occurrences, mates and configurations

A part definition is immutable and reusable. An assembly contains occurrences referencing a
part revision plus transform, configuration and suppression state. Mates are declarative
constraints (`:coincident`, `:concentric`, `:parallel`, `:distance`, `:angle`, `:gear`,
`:rack-pinion`, `:limit`); a solver produces occurrence poses and degrees-of-freedom reports.
The graph detects cycles and distinguishes grounded, under-constrained, fully-constrained and
over-constrained states. Editing a part invalidates only affected projections.

Completion gate: deterministic solve; useful conflict set for unsatisfiable mates; nested
assemblies and configurations; interference/clearance and mass-property checks; 1,000-part
interactive reference assembly; import/export retains stable occurrence identity.

### 4. Manufacturing drawings are associative document views

Drawings reference model revisions and generate orthographic, section, detail, auxiliary and
exploded views. Hidden-line removal, center marks, hatching, dimensions, tolerances, datum
feature symbols, surface finish, weld symbols, balloons and BOM are semantic EDN nodes rather
than painted pixels. A model change marks affected views dirty and regenerates them while
preserving user annotations by stable topology/provenance references.

Initial exchange is vector PDF/SVG and DXF; later profiles may add standards-compliant PMI.
Templates explicitly select ISO or ASME conventions, paper size, projection method and units.

Completion gate: golden visual/vector diffs; dimension values trace to model parameters;
section/hidden-line correctness; BOM/balloon consistency; A3/A4 and ANSI templates; changed
features update without orphaning unrelated annotations.

### 5. CAE: solver-neutral study graph with qualified adapters

CAE is a document projection, not hardwired into the geometric kernel. A study records source
revision, idealization, material model and units, contacts, loads, boundary conditions, mesh
controls, solver/adapter version and result fields. Begin with linear static structural and
steady thermal analysis, then modal, transient/nonlinear and CFD only after separate ADRs and
validation evidence.

Meshing, sparse solve and GPU/native acceleration are replaceable adapters. Browser WebGPU may
visualize and accelerate suitable work, but numerical correctness never depends on one GPU
backend. Results are reproducible content-addressed artifacts and are labelled
`experimental`, `verified`, or `qualified`; Kotoba must not market safety certification it
has not earned.

Completion gate: unit/mesh convergence tests; patch tests; comparison with published analytic
solutions and a second established solver; reaction/energy balance; deterministic study
manifest; stale-result detection when any upstream input changes.

### 6. Modifier stack becomes a typed dependency graph

The shipped mirror/array/subdivision stack remains the first UI projection of a general typed
feature/modifier DAG. Each node declares accepted and produced geometry kinds, parameters,
dependencies, determinism, cache key and provenance mapping. Evaluation supports disable,
reorder, grouping, instancing, partial invalidation and background cancellation. Destructive
apply is an explicit history event, never an implicit loss of the source graph.

Completion gate: 25 production modifiers/features; stable evaluation under reorder; topology
reference diagnostics; cache correctness; undo/redo and save/reload preserve the graph;
failed nodes retain the last valid preview and expose a structured error.

### 7. Large scenes: hierarchy, instances, LOD and out-of-core streaming

The current 100-object gate becomes the baseline, not the target. Scene packages use spatial
chunks, instancing, bounding-volume hierarchy, frustum/occlusion culling, geometry/material
deduplication, progressive LOD and content-addressed streaming. CPU authoring state is
separate from compact GPU buffers. WebGPU remains primary and WebGL2 fallback uses the same
render-IR with an explicit capability matrix; neither path silently drops semantic content.

Performance gates are measured on named reference devices and scenes:

- 10,000 visible occurrences / 1,000,000 total occurrences;
- 10 million resident triangles and a 100 million triangle streamed scene;
- first useful frame under 3 seconds on the reference broadband/device profile;
- interactive navigation at p95 30 fps, bounded memory, no main-thread stall over 100 ms;
- identical picking IDs and document provenance across WebGPU and fallback renderers.

### 8. Collaboration: content-addressed operation history plus Datomic semantics

Every accepted command records parent revision(s), actor DID, logical time, operation,
preconditions and resulting document hash. Branch, fork, merge, review, named release,
checkpoint and `as-of` are first-class. Binary payloads remain content-addressed; authorization
and signatures wrap immutable revisions rather than rewriting them.

Merge operates at semantic node/parameter level. Commuting edits merge automatically;
conflicting sketch dimensions, topology replacements, assembly mates and drawing annotations
produce domain-specific conflicts. Presence and transient cursors are separate ephemeral data
and never pollute canonical history. Offline edits sync through the same operation protocol.

Completion gate: two-client offline/online convergence; deterministic replay; branch/merge and
selective revert; conflict explanations; permission/audit tests; 10,000-operation history
loads from snapshots without replaying the entire log; no lost updates under fault injection.

## Maturity model and release order

| Level | Meaning | Required evidence |
|---|---|---|
| 1/5 | Contract | schema, units/tolerance policy, fixtures and ADR accepted |
| 2/5 | Vertical slice | one end-to-end workflow in modeler → engine → network-isekai |
| 3/5 | Useful | major workflows, persistence, diagnostics and public examples |
| 4/5 | Production candidate | interoperability corpus, performance/reliability gates, migration policy |
| 5/5 | Mature in declared scope | all domain gates above, public E2E, compatibility matrix, no critical known gaps |

Delivery order follows dependencies: document graph/units → exact kernel and feature DAG →
STEP → assemblies → associative drawings → CAE → large-scene hardening → collaboration
hardening. Large-scene caching and collaboration primitives begin early, but cannot claim 5/5
before stable identity and deterministic projections exist.

No aggregate “commercial CAD 5/5” claim is permitted by averaging. Each domain reports its
own level, and the suite's commercial-CAD level is the **minimum** of the seven domain levels.
The programme started at **1/5 (architecture accepted)**. The implementation
ledger below supersedes that historical baseline; a level changes only when its
evidence is committed and repeatable.

## Implementation ledger (2026-07-12)

| Domain | Level | Committed evidence | Gate still required for 5/5 |
|---|---:|---|---|
| NURBS/B-rep/STEP/PMI | 4/5 | Rational NURBS and trimming; stable closed B-rep and healing diagnostics; 100 generated round trips; checked NIST AP203 fixture; external NIST FTC-11 AP242-e2 audit imports 72 vertices, 104 edges, 42 faces plus 6 dimensions, 4 tolerances, 2 datums and one datum system | Checked multi-vendor 100-file AP242 corpus, re-export comparison, units/product/assembly retention and exact-kernel Boolean/healing coverage |
| Assemblies | 4/5 | Deterministic mates and conflict/DOF diagnostics; configurations; nested stable paths; revolute/prismatic joints, limits and gear/rack coupling; interference and mass properties; 1,000-occurrence data gate | General rotational/closed-loop mate solving, stable-identity exchange round trip and interactive public reference assembly |
| Manufacturing drawings | 4/5 | Associative revision/stale/orphan diagnostics; section/detail views; classified hidden lines; semantic dimensions/GD&T/BOM; deterministic SVG, DXF and vector PDF | Curved-body HLR, auxiliary/exploded views, golden ISO/ASME template corpus and parameter-to-dimension regeneration E2E |
| CAE | 4/5 | Solver-neutral revision/provenance graph; 1D bar, 2D truss, 3D tetrahedral static FEM; thermal and modal; analytic, reaction/energy, patch and convergence tests | Independent established-solver comparison corpus, contact/mesh-control adapter and published qualification manifest |
| Modifier/dependency DAG | 5/5 | Typed DAG, 25 production modifiers, deterministic cache/invalidation, reorder/disable, structured failure with last-valid preview, persistence/history tests and public modeler integration | Maintain compatibility gates; no known critical gap in declared scope |
| Very large scenes | 4/5 | Shared uploads/instancing, BVH/frustum/LOD/chunks/stream plans/stable picking; 1M occurrence and 600M streamed-triangle data gate; real Chromium WebGPU and forced-WebGL2 gates each render 20k visible instances / 11.2M resident triangles in one draw with identical picking/provenance (local reference: WebGPU 206.5ms first, 0.2ms p95 submit; WebGL2 917.8ms first, 6.6ms p95 submit) | End-to-end frame/FPS rather than submit-only timing, PerformanceObserver long-task evidence, total CPU/GPU memory matrix and occlusion/progressive LOD |
| Collaboration/history | 5/5 | Deterministic semantic replay/merge/revert/checkpoints; 10k operation snapshot gate; offline/out-of-order/fault convergence; RBAC/audit; real Ed25519 rotation/revocation/tamper tests; network-isekai transport integration | Maintain protocol/version compatibility; production key custody remains an explicit deployment adapter boundary |

The suite-wide commercial-CAD maturity is therefore **4/5**, because the
aggregate is the minimum and five domains still have mandatory 5/5 evidence.
Modifier/DAG and collaboration are 5/5 only in the bounded scopes stated here;
this ADR does not imply safety certification or universal STEP conformance.

## Integration and ownership

- `kami-engine-modeling`: portable mesh editing and tessellated projection.
- `kotoba-lang/cad-kernel` (new): exact geometry/B-rep/feature contracts and portable logic.
- `kami-app-modeler`: Kotoba HTML/CSS authoring UI, diagnostics and drawing/CAE workspaces.
- `kami-engine`: render/collision/scene consumption and adapter conformance fixtures.
- `network-isekai`: forkable worlds, collaboration transport, Asset Hub and playable spaces.
- format/solver adapters: STEP, exact kernel, mesher and CAE execution behind versioned
  contracts; no adapter becomes the canonical document store.

## Consequences

- (+) CAD, DCC, CAE and game/world creation share identity, history and projections instead
  of copying geometry between unrelated models.
- (+) Exact authoring geometry can produce lightweight WebGPU/WebGL2 runtime meshes without
  forcing games to carry a full CAD kernel.
- (+) Each maturity claim has a falsifiable gate and corpus rather than a feature checklist.
- (+) Content-addressed EDN preserves Kotoba/network-isekai fork and `as-of` semantics.
- (−) Exact geometry, STEP and validated CAE require substantial specialist work and may use
  external/native/WASM adapters; pure CLJC is the contract, not a promise that all numerical
  kernels are efficiently reimplemented in CLJC.
- (−) Stable topology naming across feature regeneration is intrinsically difficult. The UI
  must expose broken references and repair tools; it must never silently bind to a nearby edge.
- (−) “5/5” is domain-scoped. Safety-critical CAE qualification and full AP242 conformance
  require external certification/corpus evidence beyond ordinary application tests.

## Rejected alternatives

1. **Use polygon meshes as the CAD source of truth.** Rejected: exact dimensions, STEP,
   associative drawings and robust feature editing cannot be recovered reliably from a mesh.
2. **Store one opaque native-kernel file.** Rejected: breaks portable history, semantic merge,
   adapter replacement and network-isekai forking.
3. **Build seven independent apps.** Rejected: duplicates identity, units, history and cache
   invalidation, and makes model-to-game/world use lossy.
4. **Claim parity after UI demos.** Rejected: interoperability, numerical validation, large
   data and multi-user fault tests are mandatory parts of maturity.
