# 0004 — A non-duel kill costs status

Status: accepted
Date: 2026-09-30

## Context

SB-032 originally said a kill outside a duel raises Killing Psychosis and
**never** touches social status. The reasoning was constitution §2.3: killing
someone is not the same as being untrustworthy, and collapsing two metrics into
one is forbidden.

In play that reading is too pure. Murdering a stranger who never agreed to
fight is exactly the behaviour the community would vote down if anybody had
seen it. Leaving it to peer ratings means it is only punished when witnessed,
which rewards killing people who are alone.

## Decision

A kill outside a duel feeds **both** metrics, each by its own rule:

* Killing Psychosis rises, as before.
* Social status falls by a configured delta (default `-1`), recorded as a
  system-authored reputation event.

The event is written with actor `SYSTEM`, cost `0` and a message-key reason,
and is otherwise an ordinary event: derived into status, decayed by SB-006,
listed in the history GUI, revertible by an administrator.

## Why this does not violate the constitution

§2.3 forbids **deriving** one metric from another. It is not violated here:
neither metric is computed from the other, and SB-004 still holds — Psychosis
never reduces status. A world event feeding two independent metrics is one
cause with two effects, not one metric wearing two names. Read the other way,
§2.3 would also forbid a peer rating from changing status while a duel kill
changes Psychosis, which was never the intent.

§2.7 forbids punishing the ambiguous. The penalty is therefore skipped when the
plugin cannot name the killer, when the fight was consented, and in worlds the
owner marks exempt (SB-036).

§2.6 requires recovery. The event decays like any other, so a reformed player
recovers without an amnesty (SB-006).

## Guards

* One penalty per killer-victim pair per cooldown (SB-035) — otherwise a player
  can be farmed to the bottom tier by a single enemy with a respawn timer.
* A per-window cap bounds automatic loss (SB-035), keeping peer opinion the
  dominant force on a record.
* `SYSTEM` never raises Confidence (SB-034): automatic events are not evidence
  that the community has an opinion.
* Delta `0` disables the feature for owners who preferred the old reading.

## Consequences

`EventManager.java:24-29` in the baseline deducted status on every death,
including deaths with no killer. That is still a defect; this decision is not a
return to it. The difference is the guards above and the stored event.
