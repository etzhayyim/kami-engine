# kami-physics

KAMI Engine integration for the unified Kotoba physics contract. It routes the
same immutable SI-unit scene between realtime 2D rigid bodies, reduced-order
vehicle physics and high-fidelity CAE. Fidelity and capabilities must match
exactly; a game approximation cannot silently answer an engineering case.

The package is CLJC for ClojureScript/ClojureWasm hosts. External CAE processes
remain host-side finite solves; realtime interaction stays in the browser.
