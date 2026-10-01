# 0003 — Honor has two windows, not one

**Status:** accepted
**Supersedes:** part of `0001-honor-cost-is-a-fixed-yaml-amount.md`

## Context

Decision 0001 gave honor a single rolling `window`, used for two unrelated
purposes: the progressive cost multiplier, and the cap of three ratings per
actor-target pair.

Those purposes need different lengths, and the shipped configuration proved it.
With `window: 1h` and `cooldown-per-pair: 24h`, three ratings to one target
needed at least three days while the allowance reset every hour, so the cap of
SB-054 could never be reached. It was unreachable logic, and a test written
against an invented 15-minute cooldown hid that for two phases.

Widening the single window to `7d` made the cap reachable, but also held the
higher price tiers in force across *every* target for a week rather than an
hour. That is a different economy from the one decision 0001 accepted, adopted
by accident while fixing something else.

## Decision

Honor has **two** configured windows.

* `honor.multiplier-window` — how long an actor's own ratings keep raising the
  price of their next one, across all targets. Default `1h`, unchanged from
  decision 0001.
* `honor.cap-window` — the rolling window in which the cap of three positive
  and three negative per actor-target pair is counted. Default `7d`.

`cap-window` must be longer than `cooldown-per-pair` times `max-per-target`,
or the cap cannot be reached. The configuration is validated on load and
refuses to start with a message naming both keys, exactly as an invalid tier
ladder does.

## Consequences

The cost curve behaves as originally accepted: rate several people in an hour
and each costs more, wait an hour and the price returns to base.

The cap behaves as the owner described it: at most three opinions about the
same person per week, at most one per day.

One more configuration key, and one more validation rule. Both are cheaper than
a rule that silently does nothing, which is what we had.
