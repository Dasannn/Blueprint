package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MindConfigTest {
    @TempDir Path folder;
    private YamlConfiguration shipped() { return YamlConfiguration.loadConfiguration(new InputStreamReader(
            getClass().getClassLoader().getResourceAsStream("config.yml"),StandardCharsets.UTF_8)); }
    @Test void defaultsMatchEverySpecRowAndAllLeavesSupportLiveEditing() {
        var manager=new ConfigManager(folder.resolve("config.yml").toFile(),new MessageRegistry(folder.toFile(),"en",null),Runnable::run,null);
        manager.initialize();var yaml=shipped();
        for(MindInput kind:MindInput.values()) assertThat(manager.config().psychosis().input(kind)).isEqualTo(kind.defaults());
        assertThat(manager.config().psychosis().nearDeathHealth()).isEqualTo(4);
        assertThat(manager.config().psychosis().cleanDayActiveMinutes()).isEqualTo(30);
        for(String key:yaml.getKeys(true)) {
            if(yaml.isConfigurationSection(key)||!key.startsWith("psychosis.inputs.")&&!key.startsWith("psychosis.levels."))continue;
            assertThat(manager.isEditableKey(key)).as(key).isTrue();assertThat(manager.get(key)).isNotNull();
            manager.set(key,yaml.get(key).toString());
        }
        manager.set("psychosis.inputs.kill.enabled","false");assertThat(manager.config().psychosis().input(MindInput.KILL).enabled()).isFalse();
        manager.set("psychosis.inputs.kill.serene-drain","12.5");assertThat(manager.config().psychosis().input(MindInput.KILL).sereneAmount()).isEqualTo(12.5);
        manager.set("psychosis.inputs.peaceful.cap","0");
        for(MindInput kind:MindInput.values())if(kind.peaceful())assertThat(manager.config().psychosis().input(kind).cap()).isZero();
    }
    @Test void invalidNumbersBooleansCapsAndThresholdsNameTheirPathAndDoNotPublish() {
        var manager=new ConfigManager(folder.resolve("config.yml").toFile(),new MessageRegistry(folder.toFile(),"en",null),Runnable::run,null);manager.initialize();
        for(String key:List.of("psychosis.inputs.kill.serene-drain","psychosis.inputs.sleep.cure","psychosis.inputs.fishing.gain",
                "psychosis.inputs.near-death.health","psychosis.inputs.clean-day.active-minutes")) {
            for(Object value:List.of(-1,Double.NaN,Double.POSITIVE_INFINITY,"invalid")) {
                var yaml=shipped();yaml.set(key,value);assertThatThrownBy(() -> PsychosisConfigSection.load(yaml)).hasMessageContaining(key);
            }
        }
        for(String key:List.of("psychosis.inputs.sleep.cap","psychosis.inputs.clean-day.cap","psychosis.inputs.peaceful.cap")) {
            for(Object value:List.of(-1,0.5,Double.NaN,Double.POSITIVE_INFINITY,2147483648L)) {
                var yaml=shipped();yaml.set(key,value);assertThatThrownBy(() -> PsychosisConfigSection.load(yaml)).hasMessageContaining(key);
            }
        }
        for(String[] invalid:new String[][]{{"psychosis.levels.low","1"},{"psychosis.levels.medium","0"},{"psychosis.levels.high","20"},
                {"psychosis.levels.extreme","101"},{"psychosis.inputs.kill.enabled","sometimes"},{"psychosis.inputs.peaceful.cap","2.5"}}) {
            var snapshot=manager.snapshot();assertThatThrownBy(() -> manager.set(invalid[0],invalid[1])).hasMessageContaining(invalid[0]);
            assertThat(manager.snapshot()).isSameAs(snapshot);
        }
    }
    @Test void upgradeRetiresOldKeysMergesInputsPreservesOwnerValuesAndSurvivesInterruptedMigration() throws Exception {
        var yaml=shipped();yaml.set("psychosis.inputs",null);yaml.set("psychosis.levels",null);
        yaml.set("psychosis.window","96h");yaml.set("psychosis.medium-threshold",3);yaml.set("psychosis.high-threshold",8);yaml.set("psychosis.extreme-threshold",15);
        yaml.set("psychosis.serenity.active-hours-to-ceiling",50);yaml.set("psychosis.serenity.ceiling",80);
        yaml.set("psychosis.inputs.kill.serene-drain",12);yaml.set("psychosis.inputs.kill.enabled",false);
        yaml.save(folder.resolve("config.yml").toFile());
        var manager=new ConfigManager(folder.resolve("config.yml").toFile(),new MessageRegistry(folder.toFile(),"en",null),Runnable::run,null);manager.initialize();
        var upgraded=YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile());
        for(String key:List.of("psychosis.window","psychosis.medium-threshold","psychosis.high-threshold","psychosis.extreme-threshold","psychosis.serenity.active-hours-to-ceiling")) {
            assertThat(upgraded.contains(key)).isFalse();assertThat(manager.isEditableKey(key)).isFalse();
        }
        assertThat(manager.config().psychosis().input(MindInput.KILL).sereneAmount()).isEqualTo(12);
        assertThat(manager.config().psychosis().input(MindInput.KILL).enabled()).isFalse();
        assertThat(manager.legacyMindConversion().window()).isEqualTo(java.time.Duration.ofHours(96));
        assertThat(manager.legacyMindConversion().convert(0,25*3_600_000d)).isEqualTo(60);
        var restarted=new ConfigManager(folder.resolve("config.yml").toFile(),new MessageRegistry(folder.toFile(),"en",null),Runnable::run,null);restarted.initialize();
        assertThat(restarted.legacyMindConversion()).isEqualTo(manager.legacyMindConversion());
        restarted.finishMindUpgrade();assertThat(Files.exists(folder.resolve("mind-upgrade.yml"))).isFalse();
    }
}
