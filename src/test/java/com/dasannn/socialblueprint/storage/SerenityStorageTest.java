package com.dasannn.socialblueprint.storage;

import com.dasannn.socialblueprint.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SerenityStorageTest {
    @TempDir Path folder;
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static PlayerId id() { return PlayerId.of(UUID.randomUUID()); }
    private static MigrationRunner r1() { return new MigrationRunner(List.of(new Migration_1_InitialSchema(),
            new Migration_2_RaterReveal(),new Migration_3_KillPenaltyClaim(),new Migration_4_PendingCompensation(),new Migration_5_Serenity())); }
    private static void kill(StorageEngine engine, PlayerId player, CombatContext context, Instant when) {
        engine.run(conn -> {
            try (var ps = conn.prepareStatement("INSERT INTO psychosis_event(killer_uuid,victim_uuid,context,created_at) VALUES(?,?,?,?)")) {
                ps.setString(1,player.toString()); ps.setString(2,id().toString()); ps.setString(3,context.dbValue());
                ps.setString(4,StorageTimestamps.format(when)); ps.executeUpdate();
            }
        });
    }
    @Test void conversionHonorsOldWindowCurveBoundariesAndRunsOnceAcrossRestart() {
        String url = "jdbc:sqlite:" + folder.resolve("mind.db");
        PlayerId calm=id(), mad=id(), neutral=id(), capped=id();
        var legacy=new LegacyMindConversion(Duration.ofHours(90),new SerenityConfig(80,50,300));
        var runner=MigrationRunner.withDefaultMigrations(legacy,Clock.fixed(NOW,ZoneOffset.UTC));
        try (var engine=StorageEngine.open(url)) {
            engine.runMigrations(r1());
            var profiles=new ProfileRepository(engine);
            for (PlayerId player : List.of(calm,mad,neutral,capped)) profiles.save(PlayerProfile.create(player,"P"+player,NOW));
            var old=new PsychosisRepository(engine);
            old.saveStreakAsync(calm,25*3_600_000d,0).join();
            old.saveStreakAsync(mad,25*3_600_000d,0).join();
            kill(engine,mad,CombatContext.OPEN,NOW.minus(Duration.ofHours(89)));
            kill(engine,mad,CombatContext.OPEN,NOW.minus(Duration.ofHours(90)));
            kill(engine,mad,CombatContext.DUEL,NOW);
            kill(engine,mad,CombatContext.OPEN,NOW.plusNanos(1));
            for (int i=0;i<12;i++) kill(engine,capped,CombatContext.OPEN,NOW);
            assertThat(engine.runMigrations(runner)).isEqualTo(6);
            var mind=new MindRepository(engine);
            assertThat(mind.value(calm)).isEqualTo(60); // R1 custom curve: C*(2*.5-.5*.5).
            assertThat(mind.value(mad)).isEqualTo(-10); // Kill priority over credited serenity.
            assertThat(mind.value(neutral)).isZero();
            assertThat(mind.value(capped)).isEqualTo(-100);
            for (PlayerId player : List.of(calm,mad,neutral,capped)) {
                assertThat(mind.events(player)).hasSize(1);
                assertThat(mind.events(player).getFirst().kind()).isEqualTo("upgrade");
                assertThat(mind.events(player).getFirst().createdAt()).isEqualTo(NOW);
            }
            assertThat(old.findKillsByKillerSince(mad,Instant.EPOCH)).hasSize(4);
            mind.applyAsync(calm,MindInput.SLEEP,MindInput.SLEEP.defaults(),"test",NOW).join();
        }
        try (var engine=StorageEngine.open(url)) {
            engine.runMigrations(runner);
            var mind=new MindRepository(engine);
            assertThat(mind.value(calm)).isEqualTo(60.5);
            assertThat(mind.events(calm)).hasSize(2);
            assertThat(mind.events(mad)).hasSize(1);
            assertThat(new ProfileRepository(engine).findById(calm).orElseThrow().mindValue()).isEqualTo(60.5);
        }
    }
    @Test void duelPreservesSignedSerenityAndOpenKillDrainsWithoutSpill() {
        try (var engine=StorageEngine.inMemory()) {
            engine.runMigrations();
            var mind=new MindRepository(engine); var kills=new PsychosisRepository(engine);
            PlayerId player=id(), victim=id();
            mind.applyAsync(player,MindInput.CLEAN_DAY,new MindInputConfig(true,30,1,1),"test",NOW).join();
            kills.saveAsync(new PsychosisEvent(player,victim,CombatContext.DUEL,NOW)).join();
            assertThat(mind.value(player)).isEqualTo(30);
            kills.saveAsync(new PsychosisEvent(player,victim,CombatContext.OPEN,NOW)).join();
            assertThat(mind.value(player)).isEqualTo(5);
            kills.saveAsync(new PsychosisEvent(player,victim,CombatContext.OPEN,NOW)).join();
            assertThat(mind.value(player)).isZero();
            kills.saveAsync(new PsychosisEvent(player,victim,CombatContext.OPEN,NOW)).join();
            assertThat(mind.value(player)).isEqualTo(-10);
            assertThat(mind.events(player)).hasSize(4);
            var event=mind.events(player).get(2);
            assertThat(event.requestedDelta()).isEqualTo(-25);
            assertThat(event.appliedDelta()).isEqualTo(-5);
            assertThat(event.source()).isEqualTo(victim.toString());
            assertThat(kills.findKillsByKillerSince(player,Instant.EPOCH)).hasSize(4);
        }
    }
    @Test void suppressedIndependentPenaltyStillAppliesMindWithoutWritingStatusOrConfidence() {
        try (var engine=StorageEngine.inMemory()) {
            engine.runMigrations(); var mind=new MindRepository(engine);
            var psychosis=new PsychosisRepository(engine); var reputation=new ReputationRepository(engine,new StatusCache());
            PlayerId player=id(),victim=id();
            var enabled=new KillPenaltySettings(true,-1,Duration.ofHours(1),Duration.ofDays(7),1,Set.of());
            assertThat(reputation.executeKillPenaltyAsync(player,victim,"world",NOW,enabled,psychosis).join().wasPenaltyCharged()).isTrue();
            for (var settings:List.of(enabled,new KillPenaltySettings(true,-1,Duration.ZERO,Duration.ofDays(7),1,Set.of()),
                    new KillPenaltySettings(true,0,Duration.ZERO,Duration.ofDays(7),10,Set.of()),
                    new KillPenaltySettings(false,-1,Duration.ZERO,Duration.ofDays(7),10,Set.of()),
                    new KillPenaltySettings(true,-1,Duration.ZERO,Duration.ofDays(7),10,Set.of("world")))) {
                var before=reputation.findByTarget(player); double value=mind.value(player);
                assertThat(reputation.executeKillPenaltyAsync(player,victim,"world",NOW.plusSeconds(1),settings,psychosis).join().wasPenaltyCharged()).isFalse();
                assertThat(mind.value(player)).isEqualTo(value-10);
                assertThat(reputation.findByTarget(player)).isEqualTo(before);
                assertThat(new ConfidenceCalculator(ConfidenceConfig.defaults()).calculate(before,NOW)).isEqualTo(ConfidenceLevel.UNKNOWN);
            }
        }
    }
    @Test void resetIsAuditedRestartsCleanDayAndPreservesUsedCapsAndKillHistory() {
        try (var engine=StorageEngine.inMemory()) {
            engine.runMigrations();var mind=new MindRepository(engine);PlayerId player=id(),other=id(),actor=id();
            mind.applyAsync(player,MindInput.SLEEP,MindInput.SLEEP.defaults(),"night",NOW).join();
            mind.applyAsync(other,MindInput.KILL,MindInput.KILL.defaults(),"victim",NOW).join();
            assertThat(mind.resetAsync(player,actor,NOW.plusSeconds(5)).join()).isEqualTo(1);
            assertThat(mind.value(player)).isZero();assertThat(mind.value(other)).isEqualTo(-10);
            assertThat(mind.events(player)).hasSize(2); // Original sleep credit remains usable for rolling-cap queries.
            assertThat(mind.events(player).getLast().actor()).isEqualTo(actor);
            assertThat(mind.events(player).getLast().kind()).isEqualTo("admin-reset");
            assertThat(new AuditRepository(engine).findByTarget(player)).hasSize(1);
            engine.execute(conn -> {
                try(var ps=conn.prepareStatement("SELECT mind_clean_day_at FROM player_profile WHERE uuid=?")) {
                    ps.setString(1,player.toString());try(var rs=ps.executeQuery()){rs.next();assertThat(StorageTimestamps.parse(rs.getString(1))).isEqualTo(NOW.plusSeconds(5));}
                } return null;
            });
            assertThat(mind.resetAllAsync(PlayerId.CONSOLE,NOW.plusSeconds(10)).join()).isEqualTo(2);
            assertThat(mind.value(other)).isZero();
            assertThat(new AuditRepository(engine).findByTarget(player)).hasSize(2);
            assertThat(new AuditRepository(engine).findByTarget(other).getFirst().actor()).isEqualTo(PlayerId.CONSOLE);
        }
    }
    @Test void immutableLogAndFailureRollbackProtectValueAndKillRows() {
        try (var engine=StorageEngine.inMemory()) {
            engine.runMigrations();var mind=new MindRepository(engine);var kills=new PsychosisRepository(engine);PlayerId player=id();
            mind.applyAsync(player,MindInput.SLEEP,MindInput.SLEEP.defaults(),"night",NOW).join();
            assertThatThrownBy(() -> engine.run(conn -> {try(var stmt=conn.createStatement()){stmt.execute("UPDATE mind_event SET after=1");}})).isInstanceOf(StorageException.class);
            assertThatThrownBy(() -> engine.run(conn -> {try(var stmt=conn.createStatement()){stmt.execute("DELETE FROM mind_event");}})).isInstanceOf(StorageException.class);
            engine.run(conn -> {try(var stmt=conn.createStatement()){stmt.execute("CREATE TRIGGER reject_kill BEFORE INSERT ON psychosis_event BEGIN SELECT RAISE(ABORT,'test'); END");}});
            assertThatThrownBy(() -> kills.saveAsync(new PsychosisEvent(player,id(),CombatContext.OPEN,NOW)).join()).isInstanceOf(java.util.concurrent.CompletionException.class);
            assertThat(mind.value(player)).isEqualTo(0.5);assertThat(mind.events(player)).hasSize(1);
            assertThat(kills.findKillsByKillerSince(player,Instant.EPOCH)).isEmpty();
        }
    }
    @Test void disabledInputWritesNoMindEventButRetainsGhostHistory() {
        try (var engine=StorageEngine.inMemory()) {
            engine.runMigrations();var kills=new PsychosisRepository(engine);var mind=new MindRepository(engine);PlayerId player=id();
            kills.saveAsync(new PsychosisEvent(player,id(),CombatContext.OPEN,NOW),new MindInputConfig(false,25,10,0)).join();
            assertThat(mind.value(player)).isZero();assertThat(mind.events(player)).isEmpty();
            assertThat(kills.findKillsByKillerSince(player,Instant.EPOCH)).hasSize(1);
        }
    }
}
