# kami-web (nested — moved)

> **SSoT: [`kotoba-lang/kami-web`](https://github.com/kotoba-lang/kami-web)**  
> (ADR-2607102200 addendum 12)

| | |
|---|---|
| CLJC (`kotoba.web.*`) | `orgs/kotoba-lang/kami-web/src` |
| Static demos (graph/play/vendor) | `orgs/kotoba-lang/kami-web/demos` |

```bash
cd orgs/kotoba-lang/kami-web && python -m http.server 8765 --directory demos
# open http://localhost:8765/graph.html
```

Do not restore a full nested tree here.
