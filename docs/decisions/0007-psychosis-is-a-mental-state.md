# 0007 — Psychosis is a mental state

Status: accepted
Date: 2026-10-02
Amends: constitution §2.3
Replaces: SB-093, SB-118 and SB-119, and the timing rules of SB-004 and SB-117, with SB-130 to SB-138

## Context

Release 1 defined Killing Psychosis as how prone a player is to killing other
players. Its madness side counted kills in a 72-hour rolling window, and its
serenity side grew only with active playtime, which SB-118 said explicitly
was the only source. After living with release 1, the owner wants the metric to
describe the player's mind as a whole. Dying, coming close to death and not
sleeping should unsettle it. Sleeping and peaceful work such as fishing,
farming and caring for animals should calm it. Two behaviours of release 1
also felt wrong in play: a kill erased all serenity at once, and madness
vanished all at once when kills left the window.

## Decision

Constitution §2.3 now names the third metric **Psychosis** (Spanish
*Psicosis*) and defines it as the player's mental state. It remains separate
from status and Confidence: nothing in it reads or writes them, and no honor
event touches it.

The metric is one signed value, from psychosis 100 through Neutral to serenity
100.

- **Bad actions** first wear serenity down to Neutral and only then build
  psychosis. These are kills, deaths, near-deaths and sleepless nights, all
  outside plugin duels.
- **Good actions** first cure psychosis down to Neutral and then build
  serenity. These are a clean day of active play, sleep and capped peaceful
  actions.
- **Neither side spills past Neutral** in a single action, and nothing resets
  or expires on a timer.
- **Offline and idle time earn nothing.**

Duels stay outside the metric entirely, as in release 1.

## Consequences

- Recovery is slower than release 1 by design. One kill takes about four to
  five days of best behaviour to cure, and extreme psychosis more than a month.
- The 72-hour window, its configuration key and the active-hours curve are
  retired. An upgrade converts existing data once, inventing no history
  (SB-136).
- Kill rows in `psychosis_event` are kept, because the victim ghost reads them.
- Votes still cannot affect the metric. §2.3 still forbids deriving one metric
  from another.
