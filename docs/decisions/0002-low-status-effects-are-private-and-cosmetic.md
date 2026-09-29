# 0002 — Low-status ambient effects are private, cosmetic and rate-limited

- Status: accepted
- Date: 2026-09-29
- Extends: §3 of `docs/reference/playerstatus-sistema-reputacion.md`

## Context

The product owner added four ambient effects for players at the bottom of the
status range, none of which appear in the functional design document:

1. Silverfish spawning with Speed III that despawn after ~2 seconds.
2. Rare, very short messages in the chat rendered in near-black.
3. Creeper fuse sounds.
4. Fake join and leave announcements ("Herobrine joined the game").

Constitution §2.1 forbids mechanical consequences and §2.2 forbids exclusion, so
these need explicit boundaries before anyone writes them. The audit also noted
that the design document's near-black treatment applies to the *player's own
chat messages*, which is a different thing from injecting new text.

## Decision

The effects ship, under four constraints.

**Private.** Every effect is delivered to the affected player alone, as
client-side packets. No other player sees the silverfish, hears the sound, reads
the messages or sees the fake join. Nothing is written to the real chat or to
the server log.

The silverfish is therefore a **packet-only fake, not a real entity hidden from
other players**. Paper's entity visibility API hides an entity from a client, but
the mob still exists server-side: it still occupies space, still ticks, still
participates in collision and targeting, and a bug in the hiding leaks it to
everyone. A hidden real mob cannot deliver the guarantee this decision makes.
See `docs/reference/paper-26.3-notes.md` §6 and §7.

**Harmless.** The silverfish cannot deal damage, cannot be damaged, cannot push
the player, cannot target anything, drop no loot and no XP, are not persistent,
and are removed on a fixed timer whether or not the player interacts with them.
They are scenery.

**Rate-limited.** Each effect has its own cooldown and its own per-session cap,
both configurable. The intent is unease, not spam. Defaults err on the side of
rare.

**Opt-out.** A player can disable the effects for themselves with a command.
This is an accessibility requirement, not a reward: opting out changes nothing
about their status, and their status remains just as visible to everyone else.

## Rationale

Private delivery is what keeps the feature inside the constitution. A public
fake join message is indistinguishable from a real server event and degrades the
experience of players who did nothing; a visible hostile mob near a low-status
player invites bystanders to react to something that is not real. Both would
turn a social signal into collateral damage.

Rendering in near-black rather than absolute black follows the design document's
own accessibility note: `#000000` can become unreadable depending on client,
background or resource pack.

## Consequences

- These effects are separate from the chat-colour gradient of design §3. The
  gradient darkens the low-status player's *own* messages for everyone; these
  effects inject text only that player sees. Both exist.
- Implementation needs per-player scheduling on the main thread, and an entity
  lifecycle guaranteed to clean up on player quit, world change and server stop.
- The opt-out flag is per-player persistent state.
