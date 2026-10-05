# 0010 — Chat corruption is the message

Status: accepted
Date: 2026-10-05
Amends: constitution §2.1 (exception noted in §2.3), spec SB-100 and SB-179
Requirements: SB-188 to SB-190

## Context

The owner's LDActivities plugin has a typing minigame. SocialBlueprint 2.0.2
prepares corruption at HIGH and wraps renderers at HIGHEST, so a player can type
the correct word and win while readers see corrupted letters. LDActivities has
already read the original mutable chat message. Its source is unavailable.

## Decision

The owner explicitly wants corruption at the start of chat processing: filter
the message and prepare its episode/body once at LOWEST in both Paper modern
and Bukkit legacy async chat events. Other plugins receive the corrupted mutable
message. Legacy strings carry the same letters without colour codes; modern
messages and renderers retain episode colour. The Paper bridge shares one
decision and one sequence step across both events. Load before LDActivities to
register first when its listener also uses LOWEST.

## Consequences

Psychosis chat corruption may make a typed answer fail in LDActivities or other
chat games. The owner accepts that gameplay consequence as a narrow exception
to the cosmetic/no-mechanical-consequence rule. Loggers and bridges that read
the mutable event message also see corruption; Paper's original and signed
message APIs still represent the player input.

Ambient effects remain cosmetic and harmless. SocialBlueprint does not grant
combat, movement or material benefits, and corruption itself writes no status,
Confidence, Psychosis or money. Disabled worlds still bypass chat filtering and
corruption. Prefixes, hover summaries and foreign-renderer choices are preserved.
