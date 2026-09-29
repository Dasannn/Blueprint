# Constitution — SocialBlueprint

The highest authority in this repository. When any other document, any piece of
code, or any agent's suggestion contradicts this file, this file wins. Amending
it requires the product owner's explicit approval, recorded as a decision under
`docs/decisions/`.

## 1. What this product is

SocialBlueprint is a Paper plugin that gives a Minecraft server a **social
layer**. It makes visible how a community perceives a player, based on that
player's interactions with other players.

It is the successor to the imported `PlayerStatus` plugin, whose baseline is
audited in `docs/reference/baseline-audit.md`. The functional design it
implements is `docs/reference/playerstatus-sistema-reputacion.md` (transcript of
the authoritative `.docx` beside it).

## 2. Principles

These are binding. Every feature is measured against them.

### 2.1 Consequences are social, never mechanical

Reputation must never grant or remove combat advantage, damage, health, movement
speed, mining speed, drops, or any material reward. A high-status player is not
a stronger player. A low-status player is not a weaker one.

This rule is why the baseline's `/pstatus evaluate` economy payouts are removed
rather than repaired: they paid money to players for having reputation, which is
a material reward.

### 2.2 Low status creates friction, never exclusion

A player with the worst possible standing can still chat, build, trade, move and
play. Reputation may make others cautious, may add a confirmation step, may make
a message easier to overlook. It may never silence, block, or lock a player out
of a core game function.

### 2.3 The three metrics stay separate

- **Social status** — how the community perceives this player's trustworthiness.
- **Reputation Confidence** — how much evidence stands behind that status.
- **Killing Psychosis** — how prone this player is to killing other players.

They are stored separately, computed separately, and displayed separately.
Killing someone is not the same as being untrustworthy, and having no record is
not the same as having a bad one. Collapsing any two of these into one number is
a violation of this constitution.

### 2.4 Emitting an opinion costs something

Giving or removing honor charges the actor. The purpose is not to sell
reputation; it is to make the player think before spending. Money buys the right
to state an opinion, never the outcome: no amount of money may convert directly
into status, because the amount of change depends on how many *distinct* people
participated and on frequency rules, not on how much was paid.

### 2.5 A number without a history explains nothing

Every reputation change is stored as an immutable event carrying actor, target,
delta, cost, reason and timestamp. The aggregate status is derived from those
events. Storing only a final integer is forbidden: it makes abuse impossible to
investigate, decay impossible to apply, and historical bugs impossible to
correct.

### 2.6 Recovery is always possible

A bad record must never become a permanent sentence. Old ratings lose weight
over time. New players start neutral and marked as *Unknown*, never as suspect.

### 2.7 Nothing ambiguous is punished automatically

The plugin does not deduct status for events it cannot interpret with certainty
— a broken block whose permission context is unknown, a cancelled trade, a death
in a consented fight. When in doubt, the plugin records nothing.

### 2.8 The server owner configures everything without recompiling

Every message, every permission node, every colour, every threshold, every cost
and every limit is editable from YAML **and** in-game. Colours use
Essentials-style `&` codes. This includes the plugin's own chat prefix and the
tier prefixes.

## 3. Fixed platform

Not open for rediscussion by any agent.

- Minecraft **26.3**, **Paper** server.
- **Java 25** (required by Paper from 26.1 onward).
- `io.papermc.paper:paper-api:26.3.build.<n>-beta` from
  `https://repo.papermc.io/repository/maven-public/`. Paper 26.3 is on the BETA
  channel; the build number is pinned in `pom.xml` and bumped deliberately.
- **LuckPerms** compatible.
- **Vault** required for the economy.

## 4. Preserved identity

The nine tier prefixes already shipped by PlayerStatus — the `[|]` ladder with
its Spanish names, from `Criminal` to `Ilustre` — are the product's visual
identity and are kept. Their storage, their thresholds and the way they are
resolved are rewritten; the tokens and colours the players already recognise are
not.

The **tokens and colours** are the identity, not the words. The nine names are
translated (SB-070i): the ladder reads `Criminal` to `Ilustre` in Spanish and
its English equivalents in English, while `&7[&a||&7]` is the same everywhere.

## 5. How work is done

- Specification-driven. Code follows an approved document, never the reverse.
- Source-of-truth order: this file, then `docs/spec.md`, then `ARCHITECTURE.md`,
  then `docs/plan.md`, then `docs/tasks.md`.
- Three agents: Claude orchestrates and owns git; Antigravity writes the
  production code; Codex reviews it and issues corrections. One task per git
  worktree.
- `.agent/` is scratch space and is never committed.
- A conflict between this constitution and the functional design document is
  resolved in a file under `docs/decisions/`, never silently in code.
