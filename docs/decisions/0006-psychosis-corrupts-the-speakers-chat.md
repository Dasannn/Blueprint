# 0006 — Psychosis corrupts the speaker's chat

Status: accepted
Date: 2026-10-01
Replaces: SB-020 and SB-021 with SB-094 and SB-095

## Context

The former chat gradient darkened a speaker's message according to social
status. That mixed community judgement with the speaker's mental state.
Psychosis must instead affect the voice itself, without denying the player
the ability to communicate.

## Decision

Status retains the coloured tier prefix and ladder. It no longer colours the
message body (SB-094). Psychosis can substitute letters, scramble words and
mangle characters in part of the speaker's message (SB-095).

Medium Psychosis causes occasional corruption, high more frequent corruption,
and extreme frequent corruption with intact messages between episodes. The
lowest level leaves messages intact. Frequency and extent are configurable,
but no setting may make corruption constant or destroy a whole message
(SB-095, SB-097).

Every reader, including the speaker, sees the same corrupted text. The result
is chosen once for the message, not separately for each viewer. This is the
speaker's voice failing; viewer-specific distortion would instead depict each
reader's perception and make people disagree about what was said.

No message is rendered fully illegible, hidden, blocked, delayed or truncated.
Enough remains readable to communicate; a short message stays intact when a
partial transformation cannot preserve that. A wholly unreadable line is a
muted player. That is exclusion under constitution §2.2, not a cosmetic effect.
The prefix and name hover stay intact because social identity is still useful
while the voice falters.

Rater text remains length-bounded, stored as written and rendered as plain text
under SB-083. It is never parsed for colour, formatting or click actions. The
chat change is no reason to reinterpret a stored rating or its reason.

## Boundaries and consequences

The public message is distinct from private ambient hallucinations (SB-041).
The ambient opt-out controls only private effects; it changes neither the
shared chat text nor any metric (SB-044, SB-100).

This respects constitution §2.3: Psychosis causes a cosmetic presentation and
still neither derives from nor alters status. Corruption changes no status,
Confidence or money, and has no mechanical consequence. The kill rules of
decision 0004 remain independent.

Reuse the existing async chat path and immutable snapshots. Compute one
corruption result before delivery to viewers; keep database and Bukkit access
out of the async transformation. Tests must check identical text for all
readers, intact messages between episodes and readability at every level,
including short inputs. P13 records the implementation and live-server checks.
