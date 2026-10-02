package com.dasannn.socialblueprint.platform.listener;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.*;
import com.dasannn.socialblueprint.feature.duel.DuelService;
import com.dasannn.socialblueprint.feature.profile.SerenityService;
import com.dasannn.socialblueprint.storage.MindRepository;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityEnterLoveModeEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.event.world.ClockTimeSkipEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Bukkit facts are captured here; asynchronous persistence receives only plain data. */
public final class MindInputListener implements Listener {
    private final JavaPlugin plugin;
    private final ConfigManager manager;
    private final MindRepository mind;
    private final SerenityService activity;
    private final DuelService duels;
    private final DuelCombatListener combat;
    private final Map<PlayerId, MindTriggers.NearDeath> nearDeath = new HashMap<>();
    private final Map<UUID, MindNight> nights = new HashMap<>();
    private final Map<UUID, Long> worldTimes = new HashMap<>();
    private record Sample(UUID world, double activeMillis) {}
    private final Map<PlayerId, Sample> samples = new HashMap<>();

    public MindInputListener(JavaPlugin plugin, ConfigManager manager, MindRepository mind,
            SerenityService activity, DuelService duels, DuelCombatListener combat) {
        this.plugin = plugin; this.manager = manager; this.mind = mind;
        this.activity = activity; this.duels = duels; this.combat = combat;
    }
    private PlayerId id(Player player) { return PlayerId.of(player.getUniqueId()); }
    private void refresh(Player player) {
        activity.setMetadataAfk(id(player), player.getMetadata("afk").stream().anyMatch(value -> value.asBoolean()));
    }
    private void apply(PlayerId player, MindInput kind, String source, RuntimeSnapshot snapshot, boolean active) {
        var config = snapshot.config().psychosis().input(kind);
        if (!MindTriggers.eligible(config.enabled(), duels.isInActiveDuel(player), active)) return;
        mind.applyAsync(player, kind, config, source, Instant.now()).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to record " + kind.id() + " for " + player, ex); return null;
        });
    }
    private void peaceful(Player player, MindInput kind, String source, RuntimeSnapshot snapshot) {
        refresh(player);
        activity.activity(id(player));
        apply(id(player), kind, source, snapshot, activity.active(id(player)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        RuntimeSnapshot snapshot = manager.snapshot();
        double threshold = snapshot.config().psychosis().nearDeathHealth();
        PlayerId victim = id(player);
        double before = player.getHealth();
        // MONITOR sees final damage after cancellation/modifiers. Never mutate the event.
        boolean crossed = nearDeath.computeIfAbsent(victim, ignored -> new MindTriggers.NearDeath())
                .damage(before, before - event.getFinalDamage(), threshold);
        var cause = event.getDamageSource().getCausingEntity();
        PlayerId attacker = cause instanceof Player p ? id(p) : null;
        if (crossed && combat.damageContext(victim, attacker, Instant.now(), snapshot) != CombatContext.DUEL
                && snapshot.config().psychosis().input(MindInput.NEAR_DEATH).enabled()) {
            // The attack context is authoritative even after duel membership changes.
            mind.applyAsync(victim, MindInput.NEAR_DEATH, snapshot.config().psychosis().input(MindInput.NEAR_DEATH),
                    attacker == null ? "environment" : attacker.toString(), Instant.now()).exceptionally(ex -> {
                plugin.getLogger().log(Level.SEVERE, "Failed to record near-death for " + victim, ex); return null;
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void fishing(PlayerFishEvent event) {
        Player player = event.getPlayer();
        refresh(player);
        boolean fish = event.getState() == PlayerFishEvent.State.CAUGHT_FISH
                && event.getCaught() instanceof Item item && MindTriggers.fish(item.getItemStack().getType().name());
        if (MindTriggers.fishing(fish, activity.active(id(player)), activity.afk(id(player))))
            apply(id(player), MindInput.FISHING, "caught-fish", manager.snapshot(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void breeding(EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player player)
            peaceful(player, MindInput.BREEDING, event.getEntity().getUniqueId().toString(), manager.snapshot());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void love(EntityEnterLoveModeEvent event) {
        if (event.getHumanEntity() instanceof Player player)
            peaceful(player, MindInput.FEEDING, event.getEntity().getUniqueId().toString(), manager.snapshot());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void feedBaby(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof org.bukkit.entity.Ageable baby) || baby.getAge() >= 0) return;
        UUID animal = baby.getUniqueId();
        UUID player = event.getPlayer().getUniqueId();
        int before = baby.getAge();
        RuntimeSnapshot snapshot = manager.snapshot();
        Player actorBefore = event.getPlayer();
        refresh(actorBefore); activity.activity(id(actorBefore));
        if (duels.isInActiveDuel(id(actorBefore)) || !activity.active(id(actorBefore))) return;
        var hand = event.getHand();
        var food = actorBefore.getInventory().getItem(hand);
        Material type = food.getType();
        int count = food.getAmount();
        if (count <= 0 || type.isAir()) return;
        boolean creative = actorBefore.getGameMode() == org.bukkit.GameMode.CREATIVE;
        boolean creativeFood = baby instanceof org.bukkit.entity.Animals animalBaby && animalBaby.isBreedItem(food);
        // Observe actual food use and growth; natural ageing/failed interactions earn nothing.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            var entity = plugin.getServer().getEntity(animal);
            Player actor = plugin.getServer().getPlayer(player);
            if (actor == null || !(entity instanceof org.bukkit.entity.Ageable after)) return;
            var remaining = actor.getInventory().getItem(hand);
            boolean used = remaining.getType() != type || remaining.getAmount() < count;
            // Creative feeding retains the item, so require growth beyond natural ageing.
            if (creative) used = creativeFood || after.getAge() - (long) before > 1;
            if (MindTriggers.feeding(before, after.getAge(), used))
                apply(id(actor), MindInput.FEEDING, animal.toString(), snapshot, activity.active(id(actor)));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void planting(BlockPlaceEvent event) {
        var block = event.getBlockPlaced();
        if (MindTriggers.planting(block.getType().name(), block.getRelative(0, -1, 0).getType() == Material.FARMLAND))
            peaceful(event.getPlayer(), MindInput.PLANTING, block.getType().name(), manager.snapshot());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void harvesting(BlockBreakEvent event) {
        var block = event.getBlock();
        if ((block.getBlockData() instanceof Ageable crop && MindTriggers.harvesting(block.getType().name(), crop.getAge(), crop.getMaximumAge()))
                || MindTriggers.terminalCrop(block.getType().name(), block.getRelative(0, -1, 0).getType() == Material.FARMLAND))
            peaceful(event.getPlayer(), MindInput.HARVESTING, block.getType().name(), manager.snapshot());
    }

    private MindNight night(World world) { return nights.computeIfAbsent(world.getUID(), ignored -> new MindNight(13000, 23000)); }
    private void credits(World world, java.util.List<MindNight.Credit> credits, RuntimeSnapshot snapshot) {
        for (var credit : credits) {
            boolean eligible = true;
            if (credit.kind() == MindInput.SLEEP) {
                Player player = plugin.getServer().getPlayer(credit.player().value());
                if (player == null || !player.getWorld().getUID().equals(world.getUID())) continue;
                refresh(player); eligible = !activity.afk(credit.player());
            }
            // Sleeplessness depends on observed presence during the night, not presence at dawn.
            apply(credit.player(), credit.kind(), world.getUID().toString(), snapshot, eligible);
        }
    }

    /** Runs after the existing one-second activity heartbeat, on the main thread. */
    public void tick() {
        RuntimeSnapshot snapshot = manager.snapshot();
        for (World world : plugin.getServer().getWorlds()) {
            if (world.getEnvironment() != World.Environment.NORMAL) continue;
            MindNight night = night(world);
            long now = world.getFullTime();
            Long previousTime = worldTimes.put(world.getUID(), now);
            credits(world, night.time(previousTime == null ? now : previousTime), snapshot);
            for (Player player : world.getPlayers()) {
                PlayerId playerId = id(player);
                double total = activity.onlineCredit(playerId).orElse(0);
                Sample previous = samples.put(playerId, new Sample(world.getUID(), total));
                double delta = previous != null && previous.world().equals(world.getUID()) && previousTime != null && night.isNight(previousTime)
                        ? Math.max(0, total - previous.activeMillis()) : 0;
                boolean eligible = !duels.isInActiveDuel(playerId) && !activity.afk(playerId);
                night.presence(playerId, eligible ? delta : 0, player.isSleeping(), player.isDeeplySleeping(), eligible && activity.active(playerId));
            }
            // Include the final observed interval before deciding the half-night threshold.
            credits(world, night.time(now), snapshot);
        }
        // Health can re-arm in the Nether and End too.
        for (Player player : plugin.getServer().getOnlinePlayers())
            nearDeath.computeIfAbsent(id(player), ignored -> new MindTriggers.NearDeath()).observe(player.getHealth(), snapshot.config().psychosis().nearDeathHealth());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void bed(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) return;
        Player player = event.getPlayer();
        if (player.getWorld().getEnvironment() != World.Environment.NORMAL) return;
        refresh(player); activity.activity(id(player));
        var night = night(player.getWorld());
        credits(player.getWorld(), night.time(player.getWorld().getFullTime()), manager.snapshot());
        night.presence(id(player), 0, true, false, false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void wake(PlayerBedLeaveEvent event) {
        MindNight night = nights.get(event.getPlayer().getWorld().getUID());
        if (night != null) night.wake(id(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void deep(io.papermc.paper.event.player.PlayerDeepSleepEvent event) {
        Player player = event.getPlayer();
        if (player.getWorld().getEnvironment() != World.Environment.NORMAL) return;
        refresh(player);
        MindNight night = night(player.getWorld());
        credits(player.getWorld(), night.time(player.getWorld().getFullTime()), manager.snapshot());
        night.presence(id(player), 0, true, true, activity.active(id(player)) && !duels.isInActiveDuel(id(player)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void skip(TimeSkipEvent event) {
        skip(event.getWorld(), event.getSkipAmount(), event.getSkipReason(), manager.snapshot());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void clockSkip(ClockTimeSkipEvent event) {
        if (event instanceof TimeSkipEvent) return;
        RuntimeSnapshot snapshot = manager.snapshot();
        for (World world : plugin.getServer().getWorlds()) skip(world, event.getSkipAmount(), event.getSkipReason(), snapshot);
    }

    private void skip(World world, long amount, ClockTimeSkipEvent.SkipReason reason, RuntimeSnapshot snapshot) {
        if (world.getEnvironment() != World.Environment.NORMAL) return;
        MindNight night = night(world);
        credits(world, night.time(world.getFullTime()), snapshot);
        boolean earlySleep = !night.isNight(world.getFullTime()) && reason == ClockTimeSkipEvent.SkipReason.NIGHT_SKIP;
        // Sample deep sleep before Minecraft wakes everybody after the skip.
        for (Player player : world.getPlayers()) {
            refresh(player);
            PlayerId playerId = id(player);
            double total = activity.onlineCredit(playerId).orElse(0);
            Sample previous = samples.put(playerId, new Sample(world.getUID(), total));
            Long previousTime = worldTimes.get(world.getUID());
            double delta = previous != null && previous.world().equals(world.getUID()) && previousTime != null && night.isNight(previousTime)
                    ? Math.max(0, total - previous.activeMillis()) : 0;
            boolean eligible = !duels.isInActiveDuel(playerId) && !activity.afk(playerId);
            night.presence(playerId, eligible ? delta : 0, player.isSleeping(), player.isDeeplySleeping(), eligible && activity.active(playerId));
            // Vanilla permits deep sleep during sunset, before the nominal night starts.
            if (earlySleep && player.isDeeplySleeping() && amount > 0 && Math.floorDiv(world.getFullTime() + amount, 24000) > Math.floorDiv(world.getFullTime(), 24000))
                apply(id(player), MindInput.SLEEP, world.getUID().toString(), snapshot, activity.active(id(player)));
        }
        credits(world, night.time(world.getFullTime() + amount), snapshot);
        worldTimes.put(world.getUID(), world.getFullTime() + amount);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        PlayerId player = id(event.getPlayer());
        samples.remove(player); nearDeath.remove(player);
        nights.values().forEach(night -> night.wake(player));
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void world(PlayerChangedWorldEvent event) {
        samples.remove(id(event.getPlayer()));
        MindNight old = nights.get(event.getFrom().getUID());
        if (old != null) old.wake(id(event.getPlayer()));
    }
    public void clear() { nearDeath.clear(); nights.clear(); samples.clear(); worldTimes.clear(); }
}
