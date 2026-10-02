package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.command.StatusConfigCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigUpgradeMergeTest {

    @TempDir
    File tempDir;

    private File configFile;
    private MessageRegistry messageRegistry;
    private Logger testLogger;
    private List<LogRecord> loggedRecords;

    @BeforeEach
    void setUp() {
        configFile = new File(tempDir, "config.yml");
        loggedRecords = new ArrayList<>();
        testLogger = Logger.getLogger("ConfigUpgradeMergeTest-" + System.nanoTime());
        testLogger.setUseParentHandlers(false);
        testLogger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                loggedRecords.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        messageRegistry = new MessageRegistry(tempDir, "es", testLogger);
    }

    @Test
    void upgradeMergesChatColoursPreservesOwnerValuesAndLiveEditsRoundTrip() throws Exception {
        String bundled;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            bundled = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String old = bundled.replace("medium-colour: '#AAAAAA'", "medium-colour: '#999999'")
                .replaceAll("(?m)^    (?:high|extreme)-colour:.*\\r?\\n", "");
        Files.writeString(configFile.toPath(), old);
        ConfigManager manager = new ConfigManager(configFile, messageRegistry, Runnable::run, () -> "1.0", testLogger);
        manager.initialize();
        assertThat(manager.config().psychosis().chat().mediumColour()).isEqualTo("#999999");
        assertThat(manager.config().psychosis().chat().highColour()).isEqualTo("#666666");
        assertThat(manager.config().psychosis().chat().extremeColour()).isEqualTo("#303030");
        assertThat(manager.get("psychosis.chat.medium-colour")).isEqualTo("#999999");
        assertThat(manager.get("psychosis.chat.high-colour")).isEqualTo("#666666");
        assertThat(manager.get("psychosis.chat.extreme-colour")).isEqualTo("#303030");
        for (String level : List.of("medium", "high", "extreme")) {
            String key = "psychosis.chat." + level + "-colour";
            assertThat(manager.isEditableKey(key)).isTrue();
            var before = manager.snapshot();
            String disk = Files.readString(configFile.toPath());
            for (String invalid : List.of("#000000", "#010101", "#202020", "#2B2B2B", "black", "#123", "#GGGGGG")) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> manager.set(key, invalid))
                        .isInstanceOf(ConfigValidationException.class).hasMessageContaining(level + "-colour");
                assertThat(manager.snapshot()).isSameAs(before);
                assertThat(Files.readString(configFile.toPath())).isEqualTo(disk);
            }
        }
        manager.set("psychosis.chat.medium-colour", "#AAAAAA");
        manager.set("psychosis.chat.high-colour", "#777777");
        manager.set("psychosis.chat.extreme-colour", "#404040");
        var chat = manager.config().psychosis().chat();
        manager.reload();
        assertThat(manager.config().psychosis().chat()).isEqualTo(chat);
        assertThat(YamlConfiguration.loadConfiguration(configFile).getString("psychosis.chat.extreme-colour")).isEqualTo("#404040");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> manager.set("psychosis.chat.extreme-colour", "#FFFFFF"))
                .hasMessageContaining("extreme-colour");
    }

    @Test
    void upgradeAdoptsCustomChatExtentAndRetiresTheOldKey() throws Exception {
        for (int legacy : List.of(20, 17, 25)) {
            String bundled;
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                bundled = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String old = bundled.replace("    medium-extent: 20", "    extent: " + legacy)
                    .replaceAll("(?m)^    (?:high|extreme)-extent:.*\\r?\\n", "");
            Files.writeString(configFile.toPath(), old, StandardCharsets.UTF_8);
            ConfigManager manager = new ConfigManager(configFile, messageRegistry, Runnable::run, () -> "1.0", testLogger);
            manager.initialize();
            var chat = manager.snapshot().config().psychosis().chat();
            assertThat(chat.mediumExtent()).isEqualTo(legacy);
            assertThat(chat.highExtent()).isEqualTo(legacy == 20 ? 35 : legacy);
            assertThat(chat.extremeExtent()).isEqualTo(legacy == 20 ? 50 : legacy);
            assertThat(chat.mediumRate()).isEqualTo(10);
            assertThat(chat.highRate()).isEqualTo(25);
            assertThat(chat.extremeRate()).isEqualTo(40);
            assertThat(YamlConfiguration.loadConfiguration(configFile).contains("psychosis.chat.extent")).isFalse();
            manager.reload();
            assertThat(manager.snapshot().config().psychosis().chat()).isEqualTo(chat);
        }
    }

    @Test
    void chatExtentsAreLiveEditableAndInvalidEditsAreAtomic() throws Exception {
        ConfigManager manager = new ConfigManager(configFile, messageRegistry, Runnable::run, () -> "1.0", testLogger);
        manager.initialize();
        manager.set("psychosis.chat.medium-extent", "30");
        manager.set("psychosis.chat.high-extent", "40");
        manager.set("psychosis.chat.extreme-extent", "45");
        var snapshot = manager.snapshot();
        String disk = Files.readString(configFile.toPath());
        for (String[] invalid : List.of(new String[]{"medium-extent", "0"}, new String[]{"extreme-extent", "51"},
                new String[]{"medium-extent", "41"}, new String[]{"high-extent", "29"},
                new String[]{"extreme-extent", "39"}, new String[]{"high-extent", "1.5"})) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> manager.set("psychosis.chat." + invalid[0], invalid[1]))
                    .isInstanceOf(ConfigValidationException.class).hasMessageContaining(invalid[0]);
            assertThat(manager.snapshot()).isSameAs(snapshot);
            assertThat(Files.readString(configFile.toPath())).isEqualTo(disk);
        }
        assertThat(manager.snapshot().config().psychosis().chat().mediumExtent()).isEqualTo(30);
        assertThat(manager.snapshot().config().psychosis().chat().highExtent()).isEqualTo(40);
        assertThat(manager.snapshot().config().psychosis().chat().extremeExtent()).isEqualTo(45);
        assertThat(manager.isEditableKey("psychosis.chat.extent")).isFalse();
    }

    @Test
    void upgradeRetiresObsoleteEffectsKeysAndPreservesOwnerValues() throws Exception {
        String bundled;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            bundled = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String old = bundled.replace("  window: 72h", "  window: 48h")
                .replace("effects:\n", "effects:\n  threshold: -99\n")
                .replaceFirst("duration-ticks: 20(\\r?\\n\\s+distance-blocks: 8)", "duration-ticks: 37$1")
                .replace("  fake-announcement:\n", "  fake-announcement:\n    fake-names: [OldVisitor]\n")
                .replace("permissions:\n", "permissions:\n  effects: sb.effects\n");
        Files.writeString(configFile.toPath(), old, StandardCharsets.UTF_8);
        for (String language : List.of("en", "es")) {
            Files.writeString(new File(tempDir, "messages_" + language + ".yml").toPath(),
                    "effects:\n  opt-out-enabled: old-enabled\n  opt-out-disabled: old-disabled\n", StandardCharsets.UTF_8);
        }
        ConfigManager manager = new ConfigManager(configFile, messageRegistry, Runnable::run, testLogger);
        manager.initialize();
        String updated = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
        // Owner tuning restores a timed phantom; its duration is preserved on upgrade.
        assertThat(updated).doesNotContain("threshold: -99", "fake-names:", "effects: sb.effects");
        YamlConfiguration merged = YamlConfiguration.loadConfiguration(configFile);
        assertThat(merged.getInt("effects.silverfish.duration-ticks")).isEqualTo(37);
        assertThat(manager.config().psychosis().window()).isEqualTo(java.time.Duration.ofHours(48));
        assertThat(manager.isEditableKey("effects.threshold")).isFalse();
        assertThat(manager.isEditableKey("effects.silverfish.duration-ticks")).isTrue();
        assertThat(manager.isEditableKey("effects.fake-announcement.fake-names")).isFalse();
        assertThat(manager.isEditableKey("effects.opt-out-enabled")).isFalse();
        manager.set("psychosis.window", "96h");
        assertThat(manager.config().psychosis().window()).isEqualTo(java.time.Duration.ofHours(96));
        manager.set("effects.quiet-interval.medium", "6m");
        assertThat(manager.config().effects().mediumQuietInterval()).isEqualTo(java.time.Duration.ofMinutes(6));
        manager.reload();
        assertThat(manager.config().effects().mediumQuietInterval()).isEqualTo(java.time.Duration.ofMinutes(6));
        for (String language : List.of("en", "es")) {
            assertThat(Files.readString(new File(tempDir, "messages_" + language + ".yml").toPath()))
                    .doesNotContain("opt-out-enabled:", "opt-out-disabled:");
        }
    }

    @Test void tuningUpgradeAddsEveryNewLeafAndPreservesOwnerLimits() throws Exception {
        YamlConfiguration old;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            old = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        List<String> added = List.of("psychosis.chat.min-letters", "effects.episodes.duration-scale.medium",
                "effects.episodes.duration-scale.high", "effects.episodes.duration-scale.extreme",
                "effects.silverfish.mobs", "effects.silverfish.duration-ticks", "effects.silverfish.distance-blocks");
        for (String key : added) old.set(key, null);
        // An older file has no duration-scale section at all, not an empty one.
        old.set("effects.episodes.duration-scale", null);
        old.set("effects.silverfish.cooldown", "7m");
        old.set("effects.silverfish.session-cap", 9);
        old.set("effects.episodes.low", null);
        old.set("effects.episodes.medium.interval-ticks", 7200);
        old.set("effects.episodes.high.interval-ticks", 3600);
        old.set("effects.episodes.extreme.interval-ticks", 800);
        old.set("effects.quiet-interval.medium", "6m");
        old.set("effects.quiet-interval.high", "3m");
        old.set("effects.quiet-interval.extreme", "40s");
        old.save(configFile);
        ConfigManager manager = new ConfigManager(configFile, messageRegistry, Runnable::run, testLogger);
        manager.initialize();
        YamlConfiguration merged = YamlConfiguration.loadConfiguration(configFile);
        for (String key : added) {
            assertThat(merged.contains(key)).as(key).isTrue();
            assertThat(manager.isEditableKey(key)).isTrue();
            assertThat(manager.get(key)).isNotNull();
        }
        for (String key : List.of("effects.silverfish.cooldown", "effects.silverfish.session-cap",
                "effects.episodes.medium.interval-ticks", "effects.episodes.high.interval-ticks", "effects.episodes.extreme.interval-ticks",
                "effects.quiet-interval.medium", "effects.quiet-interval.high", "effects.quiet-interval.extreme"))
            assertThat(merged.get(key)).as(key).isEqualTo(old.get(key));
        assertThat(manager.config().effects().presentation().phantom().durationTicks()).isEqualTo(20);
        manager.set("effects.silverfish.mobs", "[creeper, enderman]");
        manager.set("effects.silverfish.duration-ticks", "32");
        manager.set("effects.episodes.duration-scale.extreme", "3.5");
        manager.reload();
        assertThat(manager.config().effects().presentation().phantom().mobs()).containsExactly("minecraft:creeper", "minecraft:enderman");
        assertThat(manager.config().effects().presentation().phantom().durationTicks()).isEqualTo(32);
        assertThat(manager.config().effects().presentation().durationScale().extreme()).isEqualTo(3.5);
    }

    @Test
    @DisplayName("T-104: An old file missing three sections gains exactly those three, with comments, and pre-existing values are byte-identical")
    void oldFileMissingThreeSectionsGainsThoseThreeWithCommentsAndByteIdenticalValues() throws Exception {
        // Prepare an old config.yml containing core sections from previous release, missing decay, kill-penalty, and sounds
        String oldYaml = """
                # ==============================================================================
                # SocialBlueprint Configuration
                # ==============================================================================

                language: es
                chat-prefix: '&8[&bSocialBlueprint&8]&r '

                tiers:
                  tier-4:
                    prefix: '&7[&4||||&7]'
                    threshold: -50
                  tier-3:
                    prefix: '&7[&c|||&7]'
                    threshold: -30
                  tier-2:
                    prefix: '&7[&c||&7]'
                    threshold: -15
                  tier-1:
                    prefix: '&7[&c|&7]'
                    threshold: -5
                  tier0:
                    prefix: '&7[&f|&7]'
                    threshold: 0
                  tier1:
                    prefix: '&7[&a|&7]'
                    threshold: 5
                  tier2:
                    prefix: '&7[&a||&7]'
                    threshold: 15
                  tier3:
                    prefix: '&7[&a|||&7]'
                    threshold: 30
                  tier4:
                    prefix: '&7[&b||||&7]'
                    threshold: 50

                confidence:
                  half-life: 30d
                  low-threshold: 1.0
                  established-threshold: 5.0
                  high-threshold: 15.0

                psychosis:
                  window: 24h
                  medium-threshold: 2
                  high-threshold: 5
                  extreme-threshold: 10

                honor:
                  cost: 250.0
                  multipliers:
                    - 1.0
                    - 1.5
                    - 2.0
                    - 3.0
                  multiplier-window: 1h
                  cap-window: 7d
                  cooldown-per-pair: 24h
                  max-per-target: 3

                permissions:
                  show: "socialblueprint.show"
                  show-others: "socialblueprint.show.others"
                  give-reputation: "socialblueprint.give"
                  take-reputation: "socialblueprint.take"
                  view-reputation: "socialblueprint.view"
                  admin-adjust: "socialblueprint.admin.adjust"
                  admin-config: "socialblueprint.admin.config"
                  duel: "socialblueprint.duel"
                  effects: "socialblueprint.effects"
                  version: "socialblueprint.version"
                  admin-update: "socialblueprint.admin.update"
                """;
        Files.writeString(configFile.toPath(), oldYaml, StandardCharsets.UTF_8);

        ConfigManager configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, () -> "1.0", testLogger);
        configManager.initialize();

        String mergedContent = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);

        // 1. Gains decay, kill-penalty, sounds sections
        assertThat(mergedContent).contains("decay:");
        assertThat(mergedContent).contains("kill-penalty:");
        assertThat(mergedContent).contains("sounds:");

        // 2. Comments are carried over
        assertThat(mergedContent).contains("# Reputation Decay (SB-006, Constitution");
        assertThat(mergedContent).contains("# Status lost for open-world kills outside sanctioned duels.");
        assertThat(mergedContent).contains("# Configurable Sounds (SB-090, SB-091");

        // 3. Pre-existing section values are byte-identical
        assertThat(mergedContent).contains("cost: 250.0");
        assertThat(configManager.get("honor.cost")).isEqualTo("250.0");

        // Substring check for byte-identical preservation of tiers section
        int tiersStart = oldYaml.indexOf("tiers:");
        int tiersEnd = oldYaml.indexOf("confidence:");
        String oldTiersBlock = oldYaml.substring(tiersStart, tiersEnd);
        assertThat(mergedContent).contains(oldTiersBlock);

        // 4. Backup config.yml.bak-1.0 was created with pre-merge content
        File backupFile = new File(tempDir, "config.yml.bak-1.0");
        assertThat(backupFile).exists();
        assertThat(Files.readString(backupFile.toPath(), StandardCharsets.UTF_8)).isEqualTo(oldYaml);

        // 5. Summary log line produced
        assertThat(loggedRecords).anyMatch(r ->
                r.getMessage().contains("Added") && r.getMessage().contains("config.yml"));
    }

    @Test
    @DisplayName("T-104: A customised value (honor.cost: 250.0 vs default 500.0) is still 250.0 after the merge")
    void customizedValuePreservedAfterMerge() throws Exception {
        // Read bundled config and customise honor.cost from 500.0 to 250.0, and omit sounds section
        InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml");
        assertThat(in).isNotNull();
        String bundled = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        String customized = bundled.replace("cost: 500.0", "cost: 250.0");

        // Remove sounds section to trigger merge
        int soundsIdx = customized.indexOf("sounds:");
        assertThat(soundsIdx).isGreaterThan(0);
        int nextSectionIdx = customized.indexOf("history:", soundsIdx);
        String withoutSounds = customized.substring(0, soundsIdx) + customized.substring(nextSectionIdx);

        Files.writeString(configFile.toPath(), withoutSounds, StandardCharsets.UTF_8);

        ConfigManager configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, () -> "1.0", testLogger);
        configManager.initialize();

        // Verify customized value is strictly preserved
        assertThat(configManager.get("honor.cost")).isEqualTo("250.0");
        assertThat(configManager.config().honor().cost()).isEqualTo(250.0);

        String diskContent = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
        assertThat(diskContent).contains("cost: 250.0");
        assertThat(diskContent).doesNotContain("cost: 500.0");

        // Verify missing section was restored
        assertThat(diskContent).contains("sounds:");
        assertThat(configManager.get("sounds.creeper-fuse.key")).isEqualTo("entity.creeper.primed");
    }

    @Test
    @DisplayName("T-104: A file already containing every key is not rewritten at all, produces no new lines and no backups")
    void fileAlreadyContainingEveryKeyIsNotRewrittenAtAll() throws Exception {
        // Copy complete bundled config.yml to temp directory
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertThat(in).isNotNull();
            Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        long mtimeBefore = configFile.lastModified();

        ConfigManager configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, () -> "1.0", testLogger);
        configManager.initialize();

        long mtimeAfter = configFile.lastModified();

        // 1. File was not rewritten
        assertThat(mtimeAfter).isEqualTo(mtimeBefore);

        // 2. No backup file created
        File backupFile = new File(tempDir, "config.yml.bak-1.0");
        assertThat(backupFile).doesNotExist();

        // 3. No upgrade log records emitted
        assertThat(loggedRecords).noneMatch(r ->
                r.getMessage().contains("Added") && r.getMessage().contains("config.yml"));
    }

    @Test
    @DisplayName("T-104: A missing message key is added to the right language file; an existing one with different text is left alone")
    void missingMessageKeyAddedToRightLanguageFileAndExistingOneLeftAlone() throws Exception {
        File esFile = new File(tempDir, "messages_es.yml");
        File enFile = new File(tempDir, "messages_en.yml");

        // Write Spanish file with custom permission message and missing updater.version-unknown
        String customEs = """
                tiers:
                  tier-4: 'Criminal'
                  tier-3: 'Forajido'
                  tier-2: 'Delincuente'
                  tier-1: 'Temerario'
                  tier0: 'Particular'
                  tier1: 'Afable'
                  tier2: 'Honorable'
                  tier3: 'Insigne'
                  tier4: 'Ilustre'
                commands:
                  no-permission: '&c¡No tienes permiso para esto!'
                updater:
                  version-current: '&7Versión en ejecución: &b{current}&7.'
                """;
        Files.writeString(esFile.toPath(), customEs, StandardCharsets.UTF_8);

        // Write English file with missing updater.version-unknown
        String customEn = """
                tiers:
                  tier-4: 'Criminal'
                  tier-3: 'Outlaw'
                  tier-2: 'Delinquent'
                  tier-1: 'Reckless'
                  tier0: 'Citizen'
                  tier1: 'Affable'
                  tier2: 'Honorable'
                  tier3: 'Distinguished'
                  tier4: 'Illustrious'
                commands:
                  no-permission: '&cYou do not have permission.'
                updater:
                  version-current: '&7Running version: &b{current}&7.'
                """;
        Files.writeString(enFile.toPath(), customEn, StandardCharsets.UTF_8);

        // Initialize config
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertThat(in).isNotNull();
            Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        ConfigManager configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, () -> "1.0", testLogger);
        configManager.initialize();

        // 1. Spanish file gained updater.version-unknown with Spanish bundled text
        String updatedEs = Files.readString(esFile.toPath(), StandardCharsets.UTF_8);
        assertThat(updatedEs).contains("version-unknown: '&7Versión en ejecución: &b{current}&7. Último lanzamiento: &cdesconocido&7.'");

        // 2. Custom permission message in Spanish file is untouched
        assertThat(updatedEs).contains("no-permission: '&c¡No tienes permiso para esto!'");

        // 3. English file gained updater.version-unknown with English bundled text
        String updatedEn = Files.readString(enFile.toPath(), StandardCharsets.UTF_8);
        assertThat(updatedEn).contains("version-unknown: '&7Running version: &b{current}&7. Latest release: &cunknown&7.'");

        // 4. English permission message is untouched
        assertThat(updatedEn).contains("no-permission: '&cYou do not have permission.'");

        // 5. MessageRegistry resolves the key from disk without missing key warning
        assertThat(messageRegistry.getRaw("updater.version-unknown"))
                .isEqualTo("&7Versión en ejecución: &b{current}&7. Último lanzamiento: &cdesconocido&7.");
    }

    @Test
    @DisplayName("T-104: After the merge, a key that was absent is editable through the same path /status config uses")
    void absentKeyBecomesEditableThroughStatusConfigPathAfterMerge() throws Exception {
        // Old config missing decay section entirely
        String oldYaml = """
                language: es
                chat-prefix: '&8[&bSocialBlueprint&8]&r '

                tiers:
                  tier-4:
                    prefix: '&7[&4||||&7]'
                    threshold: -50
                  tier-3:
                    prefix: '&7[&c|||&7]'
                    threshold: -30
                  tier-2:
                    prefix: '&7[&c||&7]'
                    threshold: -15
                  tier-1:
                    prefix: '&7[&c|&7]'
                    threshold: -5
                  tier0:
                    prefix: '&7[&f|&7]'
                    threshold: 0
                  tier1:
                    prefix: '&7[&a|&7]'
                    threshold: 5
                  tier2:
                    prefix: '&7[&a||&7]'
                    threshold: 15
                  tier3:
                    prefix: '&7[&a|||&7]'
                    threshold: 30
                  tier4:
                    prefix: '&7[&b||||&7]'
                    threshold: 50

                confidence:
                  half-life: 30d
                  low-threshold: 1.0
                  established-threshold: 5.0
                  high-threshold: 15.0

                psychosis:
                  window: 24h
                  medium-threshold: 2
                  high-threshold: 5
                  extreme-threshold: 10

                honor:
                  cost: 500.0
                  multipliers:
                    - 1.0
                    - 1.5
                    - 2.0
                    - 3.0
                  multiplier-window: 1h
                  cap-window: 7d
                  cooldown-per-pair: 24h
                  max-per-target: 3

                permissions:
                  show: "socialblueprint.show"
                  show-others: "socialblueprint.show.others"
                  give-reputation: "socialblueprint.give"
                  take-reputation: "socialblueprint.take"
                  view-reputation: "socialblueprint.view"
                  admin-adjust: "socialblueprint.admin.adjust"
                  admin-config: "socialblueprint.admin.config"
                  duel: "socialblueprint.duel"
                  effects: "socialblueprint.effects"
                  version: "socialblueprint.version"
                  admin-update: "socialblueprint.admin.update"
                """;
        Files.writeString(configFile.toPath(), oldYaml, StandardCharsets.UTF_8);

        ConfigManager configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, () -> "1.0", testLogger);
        configManager.initialize();

        // Key was absent in the old file, but now it exists and is editable
        assertThat(configManager.isEditableKey("decay.half-life")).isTrue();
        assertThat(configManager.get("decay.half-life")).isEqualTo("30d");

        // Edit via /status config set
        configManager.set("decay.half-life", "14d");

        assertThat(configManager.get("decay.half-life")).isEqualTo("14d");
        assertThat(configManager.config().decay().halfLife()).isEqualTo(java.time.Duration.ofDays(14));

        String diskUpdated = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
        assertThat(diskUpdated).contains("half-life: '14d'");

        // Also test through StatusConfigCommand
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) {
                        return "socialblueprint.admin.config".equals(args[0]);
                    }
                    return null;
                });
        StatusConfigCommand cmd = new StatusConfigCommand(configManager, messageRegistry, null, Runnable::run, testLogger);

        // Set another merged key: kill-penalty.delta
        assertThat(configManager.get("kill-penalty.delta")).isEqualTo("-1");
        assertThat(cmd.execute(sender, new String[]{"set", "kill-penalty.delta", "-2"})).isTrue();
        assertThat(configManager.get("kill-penalty.delta")).isEqualTo("-2");

        // Set merged key: history.reveal-cost
        assertThat(configManager.isEditableKey("history.reveal-cost")).isTrue();
        assertThat(configManager.get("history.reveal-cost")).isEqualTo("100.0");
        assertThat(cmd.execute(sender, new String[]{"set", "history.reveal-cost", "75.0"})).isTrue();
        assertThat(configManager.get("history.reveal-cost")).isEqualTo("75.0");
        assertThat(configManager.config().history().revealCost()).isEqualTo(75.0);

        // Set merged key: legacy-import.trust-name-lookup
        assertThat(configManager.isEditableKey("legacy-import.trust-name-lookup")).isTrue();
        assertThat(configManager.get("legacy-import.trust-name-lookup")).isEqualTo("false");
        assertThat(cmd.execute(sender, new String[]{"set", "legacy-import.trust-name-lookup", "true"})).isTrue();
        assertThat(configManager.get("legacy-import.trust-name-lookup")).isEqualTo("true");
        assertThat(configManager.config().legacyImport().trustNameLookup()).isTrue();

        // Set merged key: kill-penalty.exempt-worlds
        assertThat(configManager.isEditableKey("kill-penalty.exempt-worlds")).isTrue();
        assertThat(cmd.execute(sender, new String[]{"set", "kill-penalty.exempt-worlds", "[world_nether]"})).isTrue();
        assertThat(configManager.config().killPenalty().exemptWorlds()).contains("world_nether");

        // Set merged key: sounds.creeper-fuse.volume
        assertThat(configManager.isEditableKey("sounds.creeper-fuse.volume")).isTrue();
        assertThat(cmd.execute(sender, new String[]{"set", "sounds.creeper-fuse.volume", "0.8"})).isTrue();
        assertThat(configManager.config().sounds().creeperFuse().volume()).isEqualTo(0.8f);
    }

    @Test
    @DisplayName("T-104: Backup is only created once per upgrade and not overwritten on subsequent reloads")
    void backupOnlyCreatedOncePerUpgrade() throws Exception {
        String oldYaml = """
                language: es
                chat-prefix: '&8[&bSocialBlueprint&8]&r '
                tiers:
                  tier0:
                    prefix: '&7[&f|&7]'
                    threshold: 0
                  tier-1:
                    prefix: '&7[&c|&7]'
                    threshold: -5
                  tier-2:
                    prefix: '&7[&c||&7]'
                    threshold: -15
                  tier-3:
                    prefix: '&7[&c|||&7]'
                    threshold: -30
                  tier-4:
                    prefix: '&7[&4||||&7]'
                    threshold: -50
                  tier1:
                    prefix: '&7[&a|&7]'
                    threshold: 5
                  tier2:
                    prefix: '&7[&a||&7]'
                    threshold: 15
                  tier3:
                    prefix: '&7[&a|||&7]'
                    threshold: 30
                  tier4:
                    prefix: '&7[&b||||&7]'
                    threshold: 50
                confidence:
                  half-life: 30d
                  low-threshold: 1.0
                  established-threshold: 5.0
                  high-threshold: 15.0
                psychosis:
                  window: 24h
                  medium-threshold: 2
                  high-threshold: 5
                  extreme-threshold: 10
                honor:
                  cost: 500.0
                  multipliers:
                    - 1.0
                  multiplier-window: 1h
                  cap-window: 7d
                  cooldown-per-pair: 24h
                  max-per-target: 3
                permissions:
                  show: "sb.show"
                  show-others: "sb.show"
                  give-reputation: "sb.give"
                  take-reputation: "sb.take"
                  view-reputation: "sb.view"
                  admin-adjust: "sb.admin"
                  admin-config: "sb.config"
                  duel: "sb.duel"
                  effects: "sb.effects"
                  version: "sb.version"
                  admin-update: "sb.update"
                """;
        Files.writeString(configFile.toPath(), oldYaml, StandardCharsets.UTF_8);

        ConfigManager configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, () -> "1.0", testLogger);
        configManager.initialize();

        File backup = new File(tempDir, "config.yml.bak-1.0");
        assertThat(backup).exists();
        String originalBackupContent = Files.readString(backup.toPath(), StandardCharsets.UTF_8);

        // Manually alter backup to verify it is NOT overwritten
        Files.writeString(backup.toPath(), "DO_NOT_OVERWRITE", StandardCharsets.UTF_8);

        // Reload
        configManager.reload();

        // Verify backup was untouched
        assertThat(Files.readString(backup.toPath(), StandardCharsets.UTF_8)).isEqualTo("DO_NOT_OVERWRITE");
    }

    @Test
    @DisplayName("T-104: A single key missing inside an existing section is added with its preceding comments")
    void singleKeyMissingInsideExistingSectionIsAddedWithComments() throws Exception {
        // Shipped config with history.reveal-cost omitted
        InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml");
        assertThat(in).isNotNull();
        String bundled = new String(in.readAllBytes(), StandardCharsets.UTF_8);

        // Keep history section header and comments, but omit reveal-cost
        String withoutRevealCost = bundled.replace("  reveal-cost: 100.0", "");
        Files.writeString(configFile.toPath(), withoutRevealCost, StandardCharsets.UTF_8);

        ConfigManager configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, () -> "1.0", testLogger);
        configManager.initialize();

        String mergedContent = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);

        // Key is restored inside history section
        assertThat(mergedContent).contains("reveal-cost: 100.0");
        assertThat(configManager.get("history.reveal-cost")).isEqualTo("100.0");

        // Comments preceding reveal-cost are preserved
        assertThat(mergedContent).contains("# Cost in currency to reveal an anonymous rater's identity in the /status GUI (T-124).");
    }

    @Test
    @DisplayName("T-104: Nested and list leaves return at their existing section's indentation")
    void missingNestedAndListLeavesStayInsideExistingSections() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertThat(in).isNotNull();
            String bundled = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String oldConfig = bundled.replace("    volume: 1.0", "")
                    .replace("  exempt-worlds: []", "")
                    .replace("    key: \"entity.creeper.primed\"", "      key: \"entity.creeper.primed\"")
                    .replace("    pitch: 0.5", "      pitch: 0.5")
                    .replace("    category: HOSTILE", "      category: HOSTILE");
            Files.writeString(configFile.toPath(), oldConfig, StandardCharsets.UTF_8);
        }

        ConfigManager configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, () -> "1.0", testLogger);
        configManager.initialize();

        String merged = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
        assertThat(merged).contains("\n      volume: 1.0\n");
        assertThat(merged).contains("\n  # Worlds where a kill costs nothing.\n  exempt-worlds: []\n");
        assertThat(configManager.get("sounds.creeper-fuse.volume")).isEqualTo("1.0");
        assertThat(configManager.get("kill-penalty.exempt-worlds")).isEqualTo("[]");
    }
}
