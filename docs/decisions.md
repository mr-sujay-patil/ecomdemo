# Decision Log

> One or two lines per decision. Newest at the bottom. Format:
> `[Phase XX] Decision: … | Reason: … | Alternatives considered: …`

[Phase 00] Decision: Merge commits only, no branch deletion | Reason: preserves full phase history and enables ancestry-based merge verification | Alternatives considered: squash merge (rejected: breaks verification)
[Phase 00] Decision: Per-phase docs with a checkpoint file | Reason: keeps each Claude Code session's context small and resumable | Alternatives considered: single roadmap file
[Phase 00] Decision: Repository is public and named `ecomdemo`; the earlier attempt was renamed to `EcomDemo-archive` | Reason: GitHub repo names are case-insensitive, so the old `EcomDemo` blocked the name | Alternatives considered: reusing the old repo (rejected: its `main` already holds ~22 phases of divergent work)
[Phase 00] Decision: Branch protection on `main` with `enforce_admins: true` | Reason: without it GitHub exempts the owner, so "a direct push to main is rejected" would be false for the only person who pushes | Alternatives considered: protection without admin enforcement
[Phase 01] Decision: Spring Boot 4.1.1 on Java 21 | Reason: latest stable 4.x on start.spring.io at the time; Java 21 is the stack's LTS baseline even though JDK 25 is installed locally | Alternatives considered: Boot 4.2.0-M1 (rejected: not GA)
[Phase 01] Decision: Order lines snapshot product name and price, and the order stores its total | Reason: an order is a historical record and must survive repricing or deletion of a product | Alternatives considered: a @ManyToOne to Product with the total derived on read (rejected: rewrites history)
[Phase 01] Decision: The cart total is always derived, never stored | Reason: a stored total is a second source of truth that goes stale when a price or quantity changes | Alternatives considered: a cached total column
[Phase 01] Decision: Lazy associations plus explicit JOIN FETCH queries, and cart writes re-read before mapping | Reason: `save()` on a detached entity merges and returns a copy whose lazy associations are uninitialised proxies, which threw LazyInitializationException; the alternative fix, `@Transactional`, is Phase 6's scope | Alternatives considered: EAGER fetching (rejected: N+1 on every read), @Transactional (deferred to Phase 6)
[Phase 01] Decision: Checkout validates stock for all lines before writing any, but is not yet atomic | Reason: keeps the common failure clean without pulling Phase 6's transaction work forward; the remaining race is the reason Phase 6 exists | Alternatives considered: adding @Transactional now (rejected: out of phase scope)
[Phase 01] Decision: Orders map to table `orders` | Reason: ORDER is a reserved SQL word and an unquoted `insert into order` is a syntax error | Alternatives considered: quoting the identifier everywhere
