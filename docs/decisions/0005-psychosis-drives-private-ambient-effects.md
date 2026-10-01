# 0005 — Psychosis drives private ambient effects

Status: accepted
Date: 2026-10-01
Supersedes: [0002 — Low-status ambient effects are private, cosmetic and rate-limited](0002-low-status-effects-are-private-and-cosmetic.md)

## Context

The effects were attached to the wrong metric. Low status triggered whispers,
phantom mobs and fake connection messages, while Killing Psychosis was a number
on a profile that nothing read for consequences. Status is what other people
think of a player. It cannot explain why that player hears things. Psychosis is
the player's mind coming apart from killing, so it is the cause of these
effects. The owner corrected the reading; this is a reconsidered decision,
not a missing feature in decision 0002.

## Decision

Psychosis alone gates the ambient effects (SB-096). The status threshold
disappears. Status keeps its tier ladder and chat prefix, but no longer colours
the message body (SB-094). Public chat corruption is a separate presentation
of Psychosis, governed by decision 0006 and SB-095.

At medium Psychosis episodes are occasional, at high more common, and at extreme
frequent. Every level leaves silence between episodes, even when sounds have
delayed layers. Cooldowns and per-session caps remain configurable and cannot
permit a constant effect (SB-043, SB-097). The intention is unease, not a player
who can never hear or speak normally again.

Fake connection messages describe the affected player themselves, joining or
leaving while they are already present, and only that player sees them
(SB-098). Phantom mobs appear and vanish at once. They are packet-only fakes,
without damage, collision, targets, drops or persistence; cleanup still covers
disappearance, quit, world change and server stop (SB-099). A hallucination
must not become an encounter with a real mob.

The Psychosis rolling window defaults to 72 hours instead of 24, configurable
in YAML and in-game (SB-093). Kills raise it immediately and recovery follows
their expiry from the longer window: it rises quickly and falls slowly, but a
player who stops killing recovers.

## Why this respects the constitution

Constitution §2.3 keeps the metrics separate. Psychosis causes **effects**, but
still neither derives from nor alters status. SB-032 and decision 0004 remain
true: the kill independently feeds Psychosis and a system-authored reputation
event. Neither metric feeds the other. Effects write neither metric and cannot
touch Reputation Confidence or money (SB-100).

Private, cosmetic delivery respects §2.1: no combat or material advantage is
changed. Quiet intervals leave room to play under §2.2. SB-101 subsequently
removed the ambient opt-out; SB-044 is superseded. Expiry from the 72-hour
window provides recovery under §2.6.

## Consequences

- SB-040 and SB-042 are superseded by SB-096 through SB-099; SB-041, SB-043 and
  SB-044 originally retained the private-delivery, rate-limit and opt-out
  boundaries; SB-101 subsequently superseded SB-044.
- The scheduler, per-player cooldowns and session caps, managed-entity cleanup
  and configurable layered sounds already exist. Rewire their trigger and
  episode behaviour; do not build another effects engine.
- Existing configurations must retire the status gate and adopt the new default
  window without discarding unrelated owner settings. These are implementation
  tasks in P13, not code changes made by this decision.
- Decision 0002 stays in the repository, marked superseded, so its mistake and
  retained safeguards remain visible.

## Owner clarification — serenity and the constitutional boundary

The owner approved and then withdrew the Extreme nausea clause (SB-112)
because it contradicted constitution §2.1, and declined an amendment to that
principle. No mechanical exception exists, however short or rare. SB-112
records the refusal in place; its configuration and implementation task are
removed. SB-100 again forbids every mechanical advantage or impairment.

The same metric continues past neutral into serenity (SB-117 through SB-125).
It does not derive from or alter status, and no fourth metric is introduced.
Madness still rises on eligible kills and recovers through their rolling-window
expiry; serenity thereafter grows only through active peaceful play, with
diminishing returns and a reachable ceiling. One eligible kill resets the
peaceful streak, then contributes to madness as before. Logout can expire a
kill but can never earn serenity.

The owner chose perceptual serenity effects mirroring the existing machinery:
private dawn, clean sourceless sounds, gentle particles and a kindly cat, fox
or wolf apparition. There is no serene title. Calm is a social signal, so
nearby visible players hear/see sounds, particles and apparitions. Dawn is the
exception and stays private. This clarifies SB-041's boundary: madness is in
the sufferer's head, while serenity can be noticed by others. Shared cosmetic
delivery never creates a real animal, world change, reward or mechanical
advantage. Existing scheduling, limits, sound slots and fake cleanup are reused.
