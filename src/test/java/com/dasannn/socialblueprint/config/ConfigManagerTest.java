package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.Tier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigManagerTest {

    @TempDir
    File tempDir;

    private File configFile;
    private MessageRegistry messageRegistry;
    private ConfigManager configManager;
    private Logger logger;

    @BeforeEach
    void setUp() throws Exception {
        logger = Logger.getLogger("ConfigManagerTest-" + System.nanoTime());
        configFile = new File(tempDir, "config.yml");

        // Copy valid bundled config.yml to temp directory
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertThat(in).isNotNull();
            Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        messageRegistry = new MessageRegistry(tempDir, "en", logger);
        configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, logger);
        configManager.initialize();
    }

    @Test
    @DisplayName("T-034: Atomic reload replaces snapshot wholesale")
    void atomicReloadReplacesSnapshotWholesale() {
        PluginConfig before = configManager.config();
        assertThat(before).isNotNull();

        configManager.reload();

        PluginConfig after = configManager.config();
        assertThat(after).isNotNull();
        // wholesale replacement: new object instance
        assertThat(after).isNotSameAs(before);
        assertThat(after.tiers()).isEqualTo(before.tiers());
    }

    @Test
    @DisplayName("T-035: In-game reading via get(key)")
    void inGameReadConfig() {
        assertThat(configManager.get("language")).isEqualTo(configManager.config().language());
        assertThat(configManager.get("language")).isIn("en", "es");
        assertThat(configManager.get("honor.cost")).isEqualTo("500.0");
        assertThat(configManager.get("psychosis.medium-threshold")).isEqualTo("2");
        assertThat(configManager.get("tiers.tier-4.threshold")).isEqualTo("-50");
        assertThat(configManager.get("tier-4.threshold")).isEqualTo("-50");
    }

    @Test
    @DisplayName("T-035: In-game editing with valid value updates disk and snapshot atomically")
    void inGameEditValidValue() {
        PluginConfig before = configManager.config();

        configManager.set("honor.cost", "750.0");

        PluginConfig after = configManager.config();
        assertThat(after).isNotSameAs(before);
        assertThat(after.honor().cost()).isEqualTo(750.0);

        // Verify disk file was updated
        ConfigManager freshManager = new ConfigManager(configFile, messageRegistry, Runnable::run, logger);
        freshManager.initialize();
        assertThat(freshManager.config().honor().cost()).isEqualTo(750.0);
    }

    @Test
    void durationWithDaySuffixRoundTripsAsString() {
        configManager.set("decay.half-life", "7d");

        assertThat(readConfigFile()).contains("half-life: '7d'");
        assertThat(configManager.reload().config().decay().halfLife()).isEqualTo(java.time.Duration.ofDays(7));
    }

    @Test
    @DisplayName("T-035: In-game editing with invalid value refuses change, naming offending key, leaving disk and snapshot untouched")
    void inGameEditInvalidValueRefused() {
        PluginConfig before = configManager.config();
        String diskBefore = readConfigFile();

        // Attempt invalid edit: positive threshold on negative tier
        assertThatThrownBy(() -> configManager.set("tiers.tier-1.threshold", "10"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("tiers.tier-1.threshold");

        // Verify running snapshot was not modified
        PluginConfig after = configManager.config();
        assertThat(after).isSameAs(before);
        assertThat(after.tiers().get(Tier.TEMERARIO).threshold()).isEqualTo(-5);

        // Verify disk was not modified
        String diskAfter = readConfigFile();
        assertThat(diskAfter).isEqualTo(diskBefore);
    }

    @Test
    @DisplayName("T-035: In-game editing with negative cooldown refuses change, naming offending key")
    void inGameEditNegativeCooldownRefused() {
        PluginConfig before = configManager.config();

        assertThatThrownBy(() -> configManager.set("honor.cooldown-per-pair", "-12h"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("honor.cooldown-per-pair");

        assertThat(configManager.config()).isSameAs(before);
    }

    @Test
    @DisplayName("T-035: In-game editing language immediately switches MessageRegistry language")
    void inGameEditLanguageSwitchesMessageRegistry() {
        // Pin explicitly to English first so the test is decoupled from shipped default
        configManager.set("language", "en");
        assertThat(messageRegistry.activeLanguage()).isEqualTo("en");
        assertThat(messageRegistry.tierName(Tier.FORAJIDO)).isEqualTo("Outlaw");

        configManager.set("language", "es");

        assertThat(configManager.config().language()).isEqualTo("es");
        assertThat(messageRegistry.activeLanguage()).isEqualTo("es");
        assertThat(messageRegistry.tierName(Tier.FORAJIDO)).isEqualTo("Forajido");
    }

    @Test
    @DisplayName("T-035: In-game editing tier prefix applies without restart (SB-013)")
    void inGameEditPrefixAppliesImmediately() {
        assertThat(configManager.config().tiers().prefix(Tier.CRIMINAL)).isEqualTo("&7[&4||||&7]");

        configManager.set("tiers.tier-4.prefix", "&4[CRIMINAL]");

        assertThat(configManager.config().tiers().prefix(Tier.CRIMINAL)).isEqualTo("&4[CRIMINAL]");
    }

    @Test
    @DisplayName("T-031 / DoD 2: Initializing config with a malformed tier ladder fails enable, naming the offending key")
    void initializeWithMalformedTierLadderFailsNamingKey() throws Exception {
        File badConfigFile = new File(tempDir, "bad-config.yml");
        // Write config with positive threshold on negative tier
        String badYaml = Files.readString(configFile.toPath())
                .replace("threshold: -30", "threshold: 30");
        Files.writeString(badConfigFile.toPath(), badYaml);

        ConfigManager badManager = new ConfigManager(badConfigFile, messageRegistry, Runnable::run, logger);
        assertThatThrownBy(badManager::initialize)
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("tiers.tier-3.threshold")
                .matches(e -> ((ConfigValidationException) e).key().equals("tiers.tier-3.threshold"));
    }

    @Test
    @DisplayName("Finding 4: In-game editing preserves comments and formatting in config.yml")
    void inGameEditPreservesCommentsAndFormatting() {
        String beforeContent = readConfigFile();
        assertThat(beforeContent).contains("# Exponential decay half-life for rating age");
        assertThat(beforeContent).contains("# ==============================================================================");

        configManager.set("honor.cost", "650.0");

        String afterContent = readConfigFile();
        // The edited value is updated
        assertThat(afterContent).contains("cost: 650.0");
        // Comments and dividers are preserved
        assertThat(afterContent).contains("# Exponential decay half-life for rating age");
        assertThat(afterContent).contains("# ==============================================================================");
        assertThat(afterContent).contains("# Reputation Confidence (SB-003, T-013)");
    }

    @Test
    @DisplayName("Finding 2: In-game editing of message key routes to active language file, updates disk and snapshot")
    void inGameEditMessageUpdatesActiveLanguageFileAndSnapshot() throws Exception {
        // Pin explicitly to Spanish
        configManager.set("language", "es");
        File esFile = new File(tempDir, "messages_es.yml");

        configManager.set("commands.config.usage", "&eUso modificado: &f/status config <k> <v>");

        // Verify disk file updated
        assertThat(esFile).exists();
        String esContent = Files.readString(esFile.toPath());
        assertThat(esContent).contains("usage: '&eUso modificado: &f/status config <k> <v>'");

        // Verify config.yml was NOT polluted with this key
        String configContent = readConfigFile();
        assertThat(configContent).doesNotContain("usage:");

        // Verify snapshot updated
        assertThat(messageRegistry.getRaw("commands.config.usage"))
                .isEqualTo("&eUso modificado: &f/status config <k> <v>");
    }

    @Test
    @DisplayName("Finding 3: In-game editing of unknown or uneditable key is rejected before modifying disk")
    void inGameEditUnknownKeyRefused() {
        String diskBefore = readConfigFile();

        assertThatThrownBy(() -> configManager.set("imaginary.key", "someValue"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("imaginary.key");

        assertThatThrownBy(() -> configManager.set("tiers.tier0.typo", "someValue"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("tiers.tier0.typo");

        // Verify disk was completely untouched
        String diskAfter = readConfigFile();
        assertThat(diskAfter).isEqualTo(diskBefore);
    }

    @Test
    @DisplayName("Finding 5: Single atomic RuntimeSnapshot couples config and messages")
    void reloadIsAtomicAcrossConfigAndLanguage() {
        RuntimeSnapshot snap1 = configManager.snapshot();
        assertThat(snap1).isNotNull();
        assertThat(snap1.config().language()).isEqualTo(snap1.messages().activeLanguage());

        configManager.set("language", "es");
        RuntimeSnapshot snap2 = configManager.snapshot();
        assertThat(snap2).isNotSameAs(snap1);
        assertThat(snap2.config().language()).isEqualTo("es");
        assertThat(snap2.messages().activeLanguage()).isEqualTo("es");
    }

    @Test
    @DisplayName("Fix 2: Root 'prefix' key is not an editable key and cannot be set")
    void rootPrefixIsNotEditable() {
        assertThat(configManager.isEditableKey("prefix")).isFalse();
        assertThatThrownBy(() -> configManager.set("prefix", "&4[TEST]&r "))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("prefix");
    }

    @Test
    @DisplayName("T-100 Preflight Finding 6: Every leaf in shipped config.yml is editable or named in explicit exclusion list with reason")
    void everyShippedConfigLeafIsRegisteredAsEditableOrExcludedWithReason() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
        Set<String> allKeys = yaml.getKeys(true);

        // Explicit exclusion list with reasons per T-100 Preflight Finding 6
        Map<String, String> explicitExclusions = Map.of(
                // A layered slot is a list of layers, each with its own key,
                // volume, pitch, category and delay. /status config edits a
                // scalar leaf, and a list has no scalar to address, so the
                // whole slot is edited in the file. Excluded deliberately,
                // not overlooked.
                "sounds.source-less", "layered sound slot: a list of layers, not a scalar leaf"
        );

        List<String> uneditableLeaves = new ArrayList<>();
        for (String key : allKeys) {
            if (!yaml.isConfigurationSection(key)) {
                if (!configManager.isEditableKey(key) && !explicitExclusions.containsKey(key)) {
                    uneditableLeaves.add(key);
                }
            }
        }

        assertThat(uneditableLeaves)
                .as("Every leaf in config.yml must be registered in ConfigManager as editable or explicitly excluded")
                .isEmpty();
    }

    @Test
    @DisplayName("T-100 Preflight Finding 6: duel.attack-context-window and honor.multipliers can be read and edited in game")
    void duelAttackContextWindowAndHonorMultipliersEditableInGame() {
        // Read
        assertThat(configManager.get("duel.attack-context-window")).isEqualTo("30s");
        assertThat(configManager.get("honor.multipliers")).isEqualTo("[1.0, 1.5, 2.0, 3.0]");

        // Edit duel.attack-context-window
        configManager.set("duel.attack-context-window", "45s");
        assertThat(configManager.config().duel().attackContextWindow()).isEqualTo(java.time.Duration.ofSeconds(45));
        assertThat(configManager.get("duel.attack-context-window")).isEqualTo("45s");

        // Edit honor.multipliers
        configManager.set("honor.multipliers", "[1.0, 2.0, 4.0]");
        assertThat(configManager.config().honor().multipliers()).containsExactly(1.0, 2.0, 4.0);
        assertThat(configManager.get("honor.multipliers")).isEqualTo("[1.0, 2.0, 4.0]");

        // Verify disk file reloads cleanly
        ConfigManager freshManager = new ConfigManager(configFile, messageRegistry, Runnable::run, logger);
        freshManager.initialize();
        assertThat(freshManager.config().duel().attackContextWindow()).isEqualTo(java.time.Duration.ofSeconds(45));
        assertThat(freshManager.config().honor().multipliers()).containsExactly(1.0, 2.0, 4.0);
    }

    private String readConfigFile() {
        try {
            return Files.readString(configFile.toPath());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
