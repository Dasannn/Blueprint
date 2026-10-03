# 0008 — Honor cost scales with balance

- Status: accepted
- Date: 2026-10-03
- Supersedes: decision 0001's fixed/progressive formula and decision 0003's multiplier window

## Decision

Giving and taking honor use the same quote:

```
cost = roundCurrency(honor.cost + honor.cost-percent / 100 * max(0, actorBalance))
```

The Vault balance is captured at quote time on the main thread. Defaults are
30.0 base and 8.0 percent. Base must be finite and non-negative, percent finite
in [0, 100], and they cannot both be zero. The quoted amount is shown and
confirmed under SB-052 and charged exactly, subject to the existing funds check.
Confirmation never reprices it after a balance or config change.

Remove progressive multipliers and their window. Keep decision 0003's cap
window (7d), pair cooldown (24h), and independent positive/negative cap (3).
Existing configs prune the removed keys and merge the percent. An untouched
legacy base of 500.0 adopts 30.0; customized bases survive. Once the percent is
present, subsequent explicit base settings of 500.0 also survive.

## Rationale and consequences

The owner wants an opinion to cost a modest base plus a proportion of the
actor's available wealth. This replaces progressively increasing prices with
a predictable formula, while pair limits still constrain repeated opinions.

Constitution §2.4 remains unchanged: money buys the right to state an opinion,
never its outcome. The price does not change rating delta, status, Confidence
or mental-state amounts. No storage migration is needed. Decision 0001 and
0003 remain as historical records marked amended.
