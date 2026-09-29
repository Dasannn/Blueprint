# 0001 — Honor cost is a fixed YAML amount, not a percentage of balance

- Status: accepted
- Date: 2026-09-29
- Supersedes: §6 of `docs/reference/playerstatus-sistema-reputacion.md`

## Conflict

The functional design document specifies a cost proportional to the actor's
balance:

```
cost = max(minimum, current_balance × percentage)
```

with 0.50% for Trust and 0.75% for Distrust. The product owner instead asked for
a **fixed amount configurable in YAML**, and separately mentioned "10% per
honor" and a cap of three positive or three negative honors per player.

The baseline charges nothing at all (`main.java:104-145`), so neither model is
implemented today.

## Decision

The cost of giving or removing honor is a **fixed amount, configured in YAML and
editable in-game**. Percentage-of-balance is not implemented.

The ambiguous "10%" is resolved as follows: it is **not** a percentage of the
actor's balance. It is read as the owner's intent that honor be costly rather
than free, which the fixed amount satisfies. If a percentage model is ever
wanted, it is a change to one cost calculation, not to the architecture.

## Rationale

The design document's percentage exists to stop spam and to keep wealthy players
from flooding ratings. That goal is already served by three controls the same
document requires, all of which are kept:

- a progressive multiplier within a rolling time window (1.0x, 1.5x, 2.0x, 3.0x);
- a cooldown per actor-target pair;
- the owner's cap of three positive and three negative honors per target.

Given those three, the percentage adds unpredictable pricing for the player and
a harder dependency on reading live balances, without defending against anything
the other three miss.

Constitution §2.4 is unaffected: the cost still buys the *right to state an
opinion*, and the resulting status change still depends on how many distinct
players participated, never on how much was paid.

## Consequences

- `pom.xml` takes a hard Vault dependency; the plugin does not enable without an
  economy provider.
- Configuration shape:

```yaml
honor:
  cost: 500
  multipliers: [1.0, 1.5, 2.0, 3.0]
  window: 1h
  cooldown-per-pair: 24h
  max-per-target: 3
```

- `max-per-target` counts positive and negative honors **separately**, per
  actor-target pair, within the rolling window.
- Revisiting this requires a superseding decision file, not an edit here.
