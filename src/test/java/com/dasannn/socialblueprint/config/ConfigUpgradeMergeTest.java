package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.command.StatusConfigCommand;
import org.bukkit.command.CommandSender;
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
