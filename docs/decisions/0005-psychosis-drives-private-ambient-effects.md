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
changed. Quiet intervals and the retained ambient opt-out (SB-044) leave room
to play under §2.2. Expiry from the 72-hour window provides recovery under §2.6.

## Consequences

- SB-040 and SB-042 are superseded by SB-096 through SB-099; SB-041, SB-043 and
  SB-044 retain the private-delivery, rate-limit and opt-out boundaries.
- The scheduler, per-player cooldowns and session caps, managed-entity cleanup
  and configurable layered sounds already exist. Rewire their trigger and
  episode behaviour; do not build another effects engine.
- Existing configurations must retire the status gate and adopt the new default
  window without discarding unrelated owner settings. These are implementation
  tasks in P13, not code changes made by this decision.
- Decision 0002 stays in the repository, marked superseded, so its mistake and
  retained safeguards remain visible.
