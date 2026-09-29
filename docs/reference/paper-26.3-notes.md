# Paper 26.3 API notes for SocialBlueprint

Target: `paper-api:26.3.build.135-beta`, Java 25. The linked `/paper/26.3/` Javadoc is a moving 26.3 page (some pages now identify build 136); key signatures below were also checked with `javap` against the locally cached pinned 135 API jar. Paper plugin loading and command APIs are especially liable to change during beta. Java snippets omit imports and enclosing classes.

## 1. Plugin bootstrap

**Use `plugin.yml` for this rewrite.** It is supported on Paper 26.3 and matches the approved architecture: ordinary JavaPlugin lifecycle, `commands`, `permissions`, `depend`, and `libraries`. `paper-plugin.yml` opts into Paper's **experimental** plugin loader, bootstrapper, different dependency/classloading rules, and no `commands` field. A Paper plugin resolves Maven libraries through a `PluginLoader`/`MavenLibraryResolver`, not this simple manifest key. The two manifests are not interchangeable. [Paper `plugin.yml`](https://docs.papermc.io/paper/dev/plugin-yml/); [Paper plugins](https://docs.papermc.io/paper/dev/getting-started/paper-plugins/).

```yaml
name: SocialBlueprint
main: com.dasannn.socialblueprint.SocialBlueprintPlugin
version: 1.0.0
api-version: '26.3'
depend: [Vault]
libraries:
  - org.xerial:sqlite-jdbc:3.50.3.0 # example version; pin the reviewed version
```

`libraries:` uses Maven `groupId:artifactId:version`; Paper downloads it through its configured Maven Central mirror and adds it to the plugin classpath. `org.xerial:sqlite-jdbc` is a valid Maven coordinate for that mechanism, but Paper's [database guide](https://docs.papermc.io/paper/dev/using-databases/) says a SQLite JDBC driver is already bundled. That makes the architecture's extra runtime copy redundant unless its pinned version is needed; changing that architecture requires a separate decision. **26.3-specific runtime resolution of this exact coordinate was not confirmed**; exercise it on a clean server. Paper's currently published [manifest guide](https://docs.papermc.io/paper/dev/plugin-yml/) lists valid `api-version` values only through 26.2, so `api-version: '26.3'` in this example also needs a 26.3 server startup check. The manifest is loaded at startup; dependency download can delay startup, while JDBC queries belong off thread. Trap: putting `libraries:` into `paper-plugin.yml` or assuming a compile dependency alone supplies a runtime jar.

## 2. Adventure and colour

Use Adventure `Component`s. `LegacyComponentSerializer.legacyAmpersand()` is the exact serializer for `&` codes, including the Adventure RGB spelling `&#202020`. The equivalent explicit configuration is `LegacyComponentSerializer.builder().character('&').build()`. `hexColors()` controls **serialization** back to legacy text, not whether RGB input is read. For generated gradient text, use `TextColor.color(0x202020)` directly so it cannot be downsampled by a legacy string round trip. [Adventure legacy serializer](https://docs.papermc.io/adventure/serializer/legacy/); [Paper component API](https://docs.papermc.io/paper/dev/component-api/).

```java
Component configured = LegacyComponentSerializer.legacyAmpersand()
    .deserialize("&6[|] &#202020Quiet");
Component dark = Component.text("Quiet", TextColor.color(0x202020));
```

On 26.3, legacy `Player#setDisplayName(String)` and `setPlayerListName(String)` **still exist but are deprecated**; use `displayName(Component)` and `playerListName(Component)` if those surfaces are actually owned by this plugin. Legacy `setCustomName(String)` also remains via `Nameable`; use `customName(Component)` for mobs/blocks, **but custom names have no effect on players**. `org.bukkit.ChatColor` still exists but is deprecated in favour of Adventure `NamedTextColor`/`TextColor`; the `net.md_5.bungee` `Player.Spigot#sendMessage(BaseComponent)` bridge also still exists but is deprecated in favour of `sendMessage(Component)`. Legacy section strings cannot represent the full rich component model. SocialBlueprint should decorate its own chat output through a renderer instead of overwriting other plugins' names. Component construction is safe off thread; player/name setters and sends belong on the main thread. Trap: replacing every `&` with `§`, which mishandles hex and hover. [26.3 Player Javadoc](https://jd.papermc.io/paper/26.3/org/bukkit/entity/Player.html); [26.3 Nameable Javadoc](https://jd.papermc.io/paper/26.3/org/bukkit/Nameable.html); [26.3 ChatColor](https://jd.papermc.io/paper/26.3/org/bukkit/ChatColor.html); [26.3 Player.Spigot Javadoc](https://jd.papermc.io/paper/26.3/org/bukkit/entity/Player.Spigot.html).

## 3. Chat

Listen to `AsyncChatEvent`, then install a `ChatRenderer` to change the **message** component, preserving chat delivery and the player's name. A `ViewerUnaware` renderer is suitable when all viewers see the same result. `ChatEvent` is synchronous but stalls the main thread; the old `AsyncPlayerChatEvent#setFormat(String)` is the legacy formatting path. [Paper chat events](https://docs.papermc.io/paper/dev/chat-events/); [26.3 AsyncChatEvent](https://jd.papermc.io/paper/26.3/io/papermc/paper/event/player/AsyncChatEvent.html).

```java
@EventHandler void chat(AsyncChatEvent event) {
    ProfileView view = profiles.get().get(event.getPlayer().getUniqueId()); // immutable snapshot
    TextColor shade = view == null ? NamedTextColor.WHITE : view.chatColor();
    event.renderer((source, name, message, viewer) ->
        name.append(Component.text(": ")).append(message.color(shade)));
}
```

The handler is asynchronous; do not read a mutable `HashMap`, YAML configuration, SQLite connection, player location, world, or inventory there. Publish a fully built immutable map/record through `AtomicReference` (or a volatile field), then perform one read per event. The renderer can also run on the async path, so the captured shade and other data must remain immutable. Hop to the main thread for Bukkit work. Trap: mutating `event.message()` to recolour chat, which changes the message input rather than merely its presentation and may interfere with signed chat or other renderers. [Paper chat guide](https://docs.papermc.io/paper/dev/chat-events/); [Paper signed messages](https://docs.papermc.io/paper/dev/component-api/signed-messages/).

## 4. Name hover

Attach a `showText` hover to the **name component** returned by the chat renderer. Build the summary from the same immutable profile snapshot used for chat; line breaks are `Component.newline()`. The hover is visible to viewers of that rendered line, not a mutation of the actual player name. [Adventure hover events](https://docs.papermc.io/adventure/text/#hover-and-click-events); [Paper chat renderer](https://docs.papermc.io/paper/dev/chat-events/).

```java
Component info = Component.text("Status: " + view.status())
    .append(Component.newline()).append(Component.text("Tier: " + view.tier()))
    .append(Component.newline()).append(Component.text("Confidence: " + view.confidence()))
    .append(Component.newline()).append(Component.text("Psychosis: " + view.psychosis()))
    .append(Component.newline()).append(Component.text("Contributors: " + view.contributors()));
Component hoveredName = name.hoverEvent(HoverEvent.showText(info));
return hoveredName.append(Component.text(": ")).append(message);
```

Threading follows §3. Trap: placing the hover on the whole chat line or using legacy strings, which cannot carry hover metadata. [Adventure legacy limitations](https://docs.papermc.io/adventure/serializer/legacy/).

## 5. Scheduling

For this **Paper-only, non-Folia** target, `BukkitScheduler` with `BukkitTask` is the documented simple scheduler. `ScheduledTask` belongs to Paper's global/region/entity/async schedulers for Folia compatibility; it is not the return type of `BukkitScheduler#runTaskTimer`. Folia's entity scheduler follows the entity across regions; a region scheduler must not be used for entity operations. If Folia support is later required, replace entity work with `entity.getScheduler()` and use the appropriate global/async scheduler. [Paper scheduling](https://docs.papermc.io/paper/dev/scheduler/); [Paper/Folia support](https://docs.papermc.io/paper/dev/folia-support/); [26.3 BukkitScheduler](https://jd.papermc.io/paper/26.3/org/bukkit/scheduler/BukkitScheduler.html).

```java
UUID id = player.getUniqueId();
BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
    Player online = Bukkit.getPlayer(id);
    if (online != null) tickEffect(online);
}, 20L, 20L);
tasks.put(id, task);
// PlayerQuitEvent: Optional.ofNullable(tasks.remove(id)).ifPresent(BukkitTask::cancel);
Bukkit.getScheduler().runTaskLater(plugin, fish::remove, 100L);
```

Schedule game/entity work synchronously, cancel per-player tasks on quit and all owned tasks on disable; also remove tracked entities explicitly. `runTaskLater` delays in server ticks, not guaranteed wall seconds. Trap: calling world/entity methods from `runTaskTimerAsynchronously`, or treating `ScheduledTask` and `BukkitTask` as interchangeable.

## 6. Client-only effects

Send text and sounds to the one `Player`: `player.sendMessage(component)` and `player.playSound(location, sound, category, volume, pitch)`; do not use server broadcast or `World#playSound`. A **real entity can be visually restricted**: set `Entity#setVisibleByDefault(false)` in the spawn callback, then call `target.showEntity(plugin, entity)`. `hideEntity` after ordinary spawning can leak a spawn to nearby clients before the hide. These methods are in the pinned 135 jar. [26.3 Player](https://jd.papermc.io/paper/26.3/org/bukkit/entity/Player.html); [26.3 Entity visibility](https://jd.papermc.io/paper/26.3/org/bukkit/entity/Entity.html); [26.3 RegionAccessor spawn](https://jd.papermc.io/paper/26.3/org/bukkit/RegionAccessor.html).

```java
Silverfish fish = world.spawn(at, Silverfish.class,
    e -> e.setVisibleByDefault(false));
target.showEntity(plugin, fish);
target.playSound(at, Sound.ENTITY_CREEPER_PRIMED, SoundCategory.HOSTILE, 1f, 1f);
target.sendMessage(Component.text("..."));
```

All Bukkit calls belong on the main thread. **A real spawned entity cannot meet SB-041 and the accepted private-client-packets decision as written:** visibility control affects packets for that entity, but the entity still exists server-side and can affect other entities, blocks, plugins and possibly world sound/particles. The snippet demonstrates visual filtering only; it is **not** a compliant implementation of the private silverfish effect. For strict privacy use a packet-only fake silverfish through a protocol implementation (client animation, no server physics) or use `Player#spawnParticle`/other player-only visuals (simpler, but no silverfish model or chase). A visually hidden real fish is available only if the product decision is explicitly relaxed after testing all side effects. Trap: assuming `hideEntity` changes server simulation. [26.3 `hideEntity` Javadoc](https://jd.papermc.io/paper/26.3/org/bukkit/entity/Player.html); [accepted privacy decision](../decisions/0002-low-status-effects-are-private-and-cosmetic.md).

## 7. Harmless silverfish

**Use this only if a real fish is explicitly allowed despite §6's privacy conflict.** Use the spawn callback so flags apply before normal tracking. `setAI(false)` prevents the moving Speed III effect. Keep AI on; clear targets, set attack damage to zero where the attribute is present, and cancel `EntityDamageByEntityEvent` from an owned fish as a second guard. `setInvulnerable(true)` prevents ordinary damage; cancel incoming `EntityDamageEvent` for an owned fish if *no* damage is required, including exceptional damage sources. `setCollidable(false)` does not prove a real mob cannot push players in every circumstance; a packet-only fake has no server collision. `setPersistent(false)` prevents saving, but is not a timer or cleanup strategy. [26.3 LivingEntity](https://jd.papermc.io/paper/26.3/org/bukkit/entity/LivingEntity.html); [26.3 Entity](https://jd.papermc.io/paper/26.3/org/bukkit/entity/Entity.html); [26.3 Mob](https://jd.papermc.io/paper/26.3/org/bukkit/entity/Mob.html).

```java
Silverfish fish = world.spawn(at, Silverfish.class, e -> {
    e.setVisibleByDefault(false); e.setPersistent(false);
    e.setInvulnerable(true); e.setCollidable(false); e.setSilent(true);
    e.setTarget(null);
    AttributeInstance attack = e.getAttribute(Attribute.ATTACK_DAMAGE);
    if (attack != null) attack.setBaseValue(0);
    e.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 2, false, false)); // III; no particles
});
target.showEntity(plugin, fish);
```

`Attribute.ATTACK_DAMAGE` is present in the pinned 135 jar; Speed III is amplifier `2`. Cancel `EntityTargetLivingEntityEvent` for owned fish: `setTarget(null)` only clears the current target and AI could acquire another. Cancel owned silverfish `EntityChangeBlockEvent` too. For an owned fish's `EntityDeathEvent`, clear `getDrops()` and call `setDroppedExp(0)`; prevent death earlier where possible. Track UUIDs/owners and remove fish on timer, owner quit, world change, opt-out and `onDisable`; cancel corresponding timers. `setRemoveWhenFarAway(true)` is only natural despawning and cannot replace explicit removal. These events and cleanup run on the main thread. **No combination of these flags is documented as making a real fish client-only.** Trap: `setAI(false)` and assuming invulnerability alone means no attack, no loot, or no persistence. [26.3 EntityTargetLivingEntityEvent](https://jd.papermc.io/paper/26.3/org/bukkit/event/entity/EntityTargetLivingEntityEvent.html); [26.3 EntityChangeBlockEvent](https://jd.papermc.io/paper/26.3/org/bukkit/event/entity/EntityChangeBlockEvent.html); [26.3 EntityDeathEvent](https://jd.papermc.io/paper/26.3/org/bukkit/event/entity/EntityDeathEvent.html).

## 8. Death and combat

`PlayerDeathEvent` extends `EntityDeathEvent`, whose `getDamageSource()` identifies the **fatal** damage source. `victim.getKiller()` is a credited player and may remain set even when the fatal source is environmental. Use `getDamageSource().getCausingEntity() instanceof Player` to classify a direct/indirect player-caused fatal hit; also consult `getDirectEntity()` for projectiles. Log the credited killer separately if useful, and exclude active duels before recording Psychosis. [26.3 PlayerDeathEvent](https://jd.papermc.io/paper/26.3/org/bukkit/event/entity/PlayerDeathEvent.html); [26.3 DamageSource](https://jd.papermc.io/paper/26.3/org/bukkit/damage/DamageSource.html); [26.3 LivingEntity#getKiller](https://jd.papermc.io/paper/26.3/org/bukkit/entity/LivingEntity.html).

```java
Player victim = event.getEntity();
Player credited = victim.getKiller();
Entity fatalCause = event.getDamageSource().getCausingEntity();
if (fatalCause instanceof Player killer && !duels.contains(victim.getUniqueId())) {
    recordNonDuelPvpKill(killer.getUniqueId(), victim.getUniqueId());
}
```

This deliberately does not count an environmental fall after a fight as an unambiguous PvP kill, even if `credited` is non-null. For combat logging, track recent uncancelled player-caused damage with UUIDs and timestamps from `EntityDamageByEntityEvent`/`DamageSource`, then inspect that state on `PlayerQuitEvent` alongside duel state; quit alone has no built-in combat-log verdict. Event access is synchronous; database writes go to the executor. Trap: `getKiller() != null` interpreted as proof that the final damage was PvP. [26.3 EntityDamageByEntityEvent](https://jd.papermc.io/paper/26.3/org/bukkit/event/entity/EntityDamageByEntityEvent.html); [26.3 PlayerQuitEvent](https://jd.papermc.io/paper/26.3/org/bukkit/event/player/PlayerQuitEvent.html).

## 9. Permissions

Declare explicit defaults and children in `plugin.yml`. **Children are directional:** granting a parent grants its children; granting a child does not grant its parent. Thus `ARCHITECTURE.md` §7's proposed `socialblueprint.*` parent → `pstatus.*` child does **not** by itself preserve an old `pstatus.addRemoveRep` LuckPerms grant when code checks a new `socialblueprint.*` node. This is a material conflict in the approved architecture; implementation must not assume it works. To preserve grants through manifest children, make the **old node the parent** of the new checked node, or check both names during migration. The actual baseline nodes are `pstatus.addRemoveRep`, `pstatus.setReputation`, `pstatus.show`, `pstatus.showOtherPlayers`, `pstatus.giveReputation`, and `pstatus.viewReputation`; `pstatus.evaluate` is checked in code but undeclared and its command is removed. Map each retained old capability deliberately, especially the admin nodes, without granting broader rights from an old node. [Paper `plugin.yml` permissions](https://docs.papermc.io/paper/dev/plugin-yml/); [26.3 Permission children](https://jd.papermc.io/paper/26.3/org/bukkit/permissions/Permission.html).

```yaml
permissions:
  socialblueprint.admin.give:
    default: op
  pstatus.addRemoveRep:
    default: op
    children:
      socialblueprint.admin.give: true
```

```java
if (sender.hasPermission("socialblueprint.admin.give")) { /* action */ }
```

This requires LuckPerms' `apply-bukkit-child-permissions` setting, [documented as enabled by default](https://github.com/LuckPerms/LuckPerms/wiki/Configuration/e385fd899f4c365d52648fa5c51c89a1265648e1); if disabled, check both nodes or migrate grants explicitly. Ordinary `hasPermission` is enough for permissions compatibility; no direct LuckPerms API is needed. Checks belong on the main thread when using a live sender. Trap: declaring new → old children and assuming old grants imply new access; also, wildcard strings are not magic Bukkit inheritance unless declared or supplied by the permission plugin. [26.3 Permissible](https://jd.papermc.io/paper/26.3/org/bukkit/permissions/Permissible.html).

## 10. Commands

`plugin.yml` commands with aliases plus one `CommandExecutor`/`TabCompleter` dispatcher remain supported and fit the approved architecture; parse sender type before player-only branches. Paper's **recommended modern** path for a true subcommand tree is Brigadier registered through `getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, ...)`, which supports requirements, typed arguments, suggestions and aliases, including console senders via `CommandSourceStack`. This lifecycle API can be used from a `plugin.yml` JavaPlugin; a `paper-plugin.yml` bootstrapper is optional. Choose the manifest dispatcher here unless richer client-side parsing justifies changing architecture. [Paper command comparison](https://docs.papermc.io/paper/dev/command-api/misc/comparison-bukkit-brigadier/); [Paper registration](https://docs.papermc.io/paper/dev/command-api/basics/registration/); [Paper `plugin.yml` commands](https://docs.papermc.io/paper/dev/plugin-yml/).

```yaml
commands:
  status:
    aliases: [pstatus, reputation]
    description: Social profile and actions
```

```java
PluginCommand root = Objects.requireNonNull(getCommand("status"));
root.setExecutor(dispatcher);
root.setTabCompleter(dispatcher);
// Brigadier alternative in onEnable():
getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
    event -> event.registrar().register(commandTree, "Social profile", List.of("pstatus", "reputation")));
```

Both registration paths and ordinary command callbacks run on the server thread; move I/O off it. Trap: declaring `commands:` in `paper-plugin.yml`, registering the same root twice, or casting every `CommandSender` to `Player`. Brigadier/lifecycle APIs are Paper-specific and beta-sensitive.

## 11. Config and I/O

Paper explicitly says YAML `load`/`save` block the main thread and should be asynchronous; scheduler async tasks also may not touch world state. SocialBlueprint's single-threaded JDBC executor is an architecture choice built from Java's `Executors.newSingleThreadExecutor()`, not a special Paper executor. Capture UUID/plain inputs on main, run SQL on that executor, then schedule Bukkit work with `BukkitScheduler#runTask`. Publish immutable config/profile snapshots atomically for async chat; never share a mutable `YamlConfiguration` or `Connection` across threads. [Paper configuration](https://docs.papermc.io/paper/dev/plugin-configurations/); [Paper scheduling](https://docs.papermc.io/paper/dev/scheduler/); [Paper database guide](https://docs.papermc.io/paper/dev/using-databases/).

```java
ExecutorService db = Executors.newSingleThreadExecutor();
UUID id = player.getUniqueId();
db.execute(() -> {
    ProfileView result = repository.load(id); // JDBC only
    Bukkit.getScheduler().runTask(plugin, () -> {
        Player online = Bukkit.getPlayer(id);
        if (online != null) showProfile(online, result);
    });
});
```

Close/flush the executor and JDBC connection on disable. Trap: `saveConfig()` inside a command/listener, or calling `Player#sendMessage` directly from a database callback.

## 12. Vault

Use the Vault API's Bukkit service registration, require a non-null `Economy` provider at enable, and inspect `EconomyResponse#transactionSuccess()` plus `errorMessage` after withdrawal/refund. `withdrawPlayer(OfflinePlayer, double)` keeps UUID identity, though the actual economy provider decides offline-account behavior. Vault does not make its transaction atomic with SQLite: the architecture's refund on failed event write remains required. Paper documents the [Bukkit services registry in its 26.3 Javadoc](https://jd.papermc.io/paper/26.3/org/bukkit/plugin/ServicesManager.html); Vault types are external, so their authoritative signatures come from [VaultAPI's `Economy`](https://github.com/MilkBowl/VaultAPI/blob/master/src/main/java/net/milkbowl/vault/economy/Economy.java) and [`EconomyResponse`](https://github.com/MilkBowl/VaultAPI/blob/master/src/main/java/net/milkbowl/vault/economy/EconomyResponse.java).

```java
RegisteredServiceProvider<Economy> rsp =
    Bukkit.getServicesManager().getRegistration(Economy.class);
if (rsp == null) throw new IllegalStateException("No Vault economy provider");
Economy economy = rsp.getProvider();
EconomyResponse charge = economy.withdrawPlayer(actor, cost);
if (!charge.transactionSuccess()) throw new IllegalStateException(charge.errorMessage);
```

VaultUnlocked is the actively released fork; its latest listed 2.20.1 release says it added **26.2** support. MilkBowl Vault/VaultAPI is the legacy API namespace. **Neither project's 26.3 server compatibility, nor whether a particular VaultUnlocked release satisfies `depend: [Vault]`, was confirmed from Paper's docs/Javadoc**; test the chosen bridge plus an economy provider on 26.3 before fixing that manifest dependency. Vault provider calls have no general async guarantee: execute them on the main thread unless that provider documents an async contract; only JDBC is confined to the DB executor. Trap: dispatching `eco take` and assuming success, or invoking a synchronous economy provider on the JDBC thread. [VaultUnlocked releases](https://github.com/TheNewEconomy/VaultUnlocked/releases); [Vault releases](https://github.com/MilkBowl/Vault/releases).

## 13. `plugins/update/`

Paper still documents staging plugin jars under `plugins/update/` and **restarting** to apply them; `bukkit.yml` defaults `settings.update-folder` to `update`, and server owners can change it. Use the configured update directory rather than hardcoding if possible. The update jar's manifest `name` should identify the installed plugin. Paper's public guide does **not specify any mandatory jar basename**; an exact filename rule for 26.3 build 135 could not be confirmed from Paper's docs/Javadoc. The conservative staging name is the installed jar's **current filename** (for example, if installed as `plugins/SocialBlueprint.jar`, stage `plugins/update/SocialBlueprint.jar`) with the same manifest plugin name. This is a recommendation, **not** a confirmed 26.3 filename requirement; verify replacement on a 26.3 restart before relying on `/status update`. [Paper updating](https://docs.papermc.io/paper/updating/); [Paper `bukkit.yml`](https://docs.papermc.io/paper/reference/bukkit-configuration/); [Paper adding plugins](https://docs.papermc.io/paper/adding-plugins/).

```text
plugins/SocialBlueprint.jar          # installed file
plugins/update/SocialBlueprint.jar   # checksum-verified staged replacement
```

Download and checksum verification run off thread; only a verified completed jar goes into the update directory. The running jar stays untouched; never use `/reload` as the update step. A Paper plugin type change (`plugin.yml` → `paper-plugin.yml`) is an extra risk: a prior Paper issue reported update-folder migration trouble, though it was closed as not reproducible; this rewrite stays on `plugin.yml`. Trap: the Paper **server** jar's start-script filename rule is unrelated to plugin update filenames. [Paper issue #11750](https://github.com/PaperMC/Paper/issues/11750).

## Gotchas for a 1.19 rewrite

- `setDisplayName(String)`, `setPlayerListName(String)` and Bungee component sends still exist but are deprecated; render Adventure chat components instead of rewriting global player names. [26.3 Player](https://jd.papermc.io/paper/26.3/org/bukkit/entity/Player.html).
- `&#202020` is legacy **input**; use `TextColor.color(0x202020)` for generated RGB and hover. [Adventure legacy](https://docs.papermc.io/adventure/serializer/legacy/).
- `AsyncChatEvent` is async, even when the renderer looks like ordinary formatting code. Read one immutable snapshot. [Paper chat events](https://docs.papermc.io/paper/dev/chat-events/).
- A hidden real mob is still real and cannot guarantee the accepted client-only effect. Use a packet-only fake for the silverfish; if a real mob is separately allowed, guard damage, collision and loot, then remove it explicitly. [26.3 entity visibility](https://jd.papermc.io/paper/26.3/org/bukkit/entity/Entity.html).
- `getKiller()` is credit, not proof that the fatal damage source was a player. [26.3 DamageSource](https://jd.papermc.io/paper/26.3/org/bukkit/damage/DamageSource.html).
- Permission children flow parent → child. Old grants require old → new mapping or a dual check; verify LuckPerms' child setting. [Paper permissions](https://docs.papermc.io/paper/dev/plugin-yml/).
- `BukkitTask` is the Paper-only scheduler handle here; `ScheduledTask` is from regional schedulers. [Paper/Folia support](https://docs.papermc.io/paper/dev/folia-support/).
