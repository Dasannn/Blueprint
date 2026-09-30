package com.dasannn.socialblueprint.domain.duel;

import com.dasannn.socialblueprint.domain.PlayerId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class DuelDomainTest {

    private final Instant baseTime = Instant.parse("2026-09-30T12:00:00Z");

    @Test
    @DisplayName("DuelState: maps db values and parses accurately, throwing on invalid")
    void duelStateParsingAndDbValues() {
        assertThat(DuelState.PENDING.dbValue()).isEqualTo("pending");
        assertThat(DuelState.ACTIVE.dbValue()).isEqualTo("active");
        assertThat(DuelState.ENDED.dbValue()).isEqualTo("ended");
        assertThat(DuelState.CANCELLED.dbValue()).isEqualTo("cancelled");

        assertThat(DuelState.fromDbValue("pending")).isEqualTo(DuelState.PENDING);
        assertThat(DuelState.fromDbValue("ACTIVE")).isEqualTo(DuelState.ACTIVE);
        assertThat(DuelState.fromDbValue("ended")).isEqualTo(DuelState.ENDED);
        assertThat(DuelState.fromDbValue("cancelled")).isEqualTo(DuelState.CANCELLED);
        assertThat(DuelState.fromDbValue("canceled")).isEqualTo(DuelState.CANCELLED);

        assertThatThrownBy(() -> DuelState.fromDbValue("unknown"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("DuelParticipant: validates arguments and guarantees immutability")
    void duelParticipantValidation() {
        PlayerId player = PlayerId.of(UUID.randomUUID());
        DuelParticipant participant = new DuelParticipant(player, "team_a");

        assertThat(participant.playerId()).isEqualTo(player);
        assertThat(participant.side()).isEqualTo("team_a");

        assertThatThrownBy(() -> new DuelParticipant(null, "side"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DuelParticipant(player, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DuelParticipant(player, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("DuelRecord: validates fields and creates defensive copy of participants")
    void duelRecordValidationAndImmutability() {
        PlayerId p1 = PlayerId.of(UUID.randomUUID());
        PlayerId p2 = PlayerId.of(UUID.randomUUID());
        List<DuelParticipant> participants = List.of(
                new DuelParticipant(p1, "side_1"),
                new DuelParticipant(p2, "side_2")
        );

        DuelRecord record = new DuelRecord("duel-123", DuelState.ACTIVE, baseTime, participants);
        assertThat(record.id()).isEqualTo("duel-123");
        assertThat(record.state()).isEqualTo(DuelState.ACTIVE);
        assertThat(record.createdAt()).isEqualTo(baseTime);
        assertThat(record.endedAt()).isNull();
        assertThat(record.participants()).hasSize(2);

        DuelRecord endedRecord = new DuelRecord("duel-123", DuelState.ENDED, baseTime, baseTime.plusSeconds(30), participants);
        assertThat(endedRecord.endedAt()).isEqualTo(baseTime.plusSeconds(30));

        assertThatThrownBy(() -> new DuelRecord(null, DuelState.ACTIVE, baseTime, participants))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DuelRecord("id", null, baseTime, participants))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("T-060: 1v1 DuelChallenge lifecycle: creation, auto-consent, accept, expiry")
    void duelChallenge1v1Lifecycle() {
        PlayerId challenger = PlayerId.of(UUID.randomUUID());
        PlayerId opponent = PlayerId.of(UUID.randomUUID());

        Map<String, Set<PlayerId>> sides = Map.of(
                "side_1", Set.of(challenger),
                "side_2", Set.of(opponent)
        );

        Instant expiresAt = baseTime.plusSeconds(60);
        DuelChallenge challenge = new DuelChallenge("c-1", challenger, sides, baseTime, expiresAt);

        assertThat(challenge.id()).isEqualTo("c-1");
        assertThat(challenge.challenger()).isEqualTo(challenger);
        assertThat(challenge.is1v1()).isTrue();
        assertThat(challenge.allParticipants()).containsExactlyInAnyOrder(challenger, opponent);

        // Challenger consents automatically on creation
        assertThat(challenge.acceptedPlayers()).containsExactly(challenger);
        assertThat(challenge.hasEveryMemberConsented()).isFalse();

        // Non-participant cannot accept
        PlayerId stranger = PlayerId.of(UUID.randomUUID());
        assertThat(challenge.accept(stranger)).isFalse();

        // Expiry check
        assertThat(challenge.hasExpired(baseTime.plusSeconds(30))).isFalse();
        assertThat(challenge.hasExpired(baseTime.plusSeconds(61))).isTrue();

        // Opponent accepts
        assertThat(challenge.accept(opponent)).isTrue();
        assertThat(challenge.hasEveryMemberConsented()).isTrue();
    }

    @Test
    @DisplayName("T-060: Group duel consent policy: requires EVERY member's individual consent")
    void groupDuelRequiresEveryMemberConsent() {
        PlayerId p1 = PlayerId.of(UUID.randomUUID()); // Challenger
        PlayerId p2 = PlayerId.of(UUID.randomUUID());
        PlayerId p3 = PlayerId.of(UUID.randomUUID());
        PlayerId p4 = PlayerId.of(UUID.randomUUID());

        Map<String, Set<PlayerId>> sides = Map.of(
                "team_blue", Set.of(p1, p2),
                "team_red", Set.of(p3, p4)
        );

        DuelChallenge challenge = new DuelChallenge("c-group", p1, sides, baseTime, baseTime.plusSeconds(60));

        assertThat(challenge.is1v1()).isFalse();
        assertThat(challenge.allParticipants()).containsExactlyInAnyOrder(p1, p2, p3, p4);

        // Only p1 (challenger) is consented initially (1/4)
        assertThat(challenge.acceptedPlayers()).containsExactly(p1);
        assertThat(challenge.hasEveryMemberConsented()).isFalse();

        // p3 (team red leader/first) accepts -> still false (2/4)
        assertThat(challenge.accept(p3)).isTrue();
        assertThat(challenge.hasEveryMemberConsented()).isFalse();

        // p2 (team blue member) accepts -> still false (3/4)
        assertThat(challenge.accept(p2)).isTrue();
        assertThat(challenge.hasEveryMemberConsented()).isFalse();

        // Finally p4 (team red second member) accepts -> now true (4/4)!
        assertThat(challenge.accept(p4)).isTrue();
        assertThat(challenge.hasEveryMemberConsented()).isTrue();
    }

    @Test
    @DisplayName("DuelChallenge: validation rejects less than two sides or empty sides")
    void duelChallengeValidation() {
        PlayerId p1 = PlayerId.of(UUID.randomUUID());
        PlayerId p2 = PlayerId.of(UUID.randomUUID());

        assertThatThrownBy(() -> new DuelChallenge("c", p1, Map.of("side_1", Set.of(p1)), baseTime, baseTime.plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two sides");

        assertThatThrownBy(() -> new DuelChallenge("c", p1, Map.of("side_1", Set.of(p1), "side_2", Set.of()), baseTime, baseTime.plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be empty");
    }

    @Test
    @DisplayName("ActiveDuelSession: tracks sides, elimination, opponents, disconnects, and winners")
    void activeDuelSessionTracking() {
        PlayerId blue1 = PlayerId.of(UUID.randomUUID());
        PlayerId blue2 = PlayerId.of(UUID.randomUUID());
        PlayerId red1 = PlayerId.of(UUID.randomUUID());

        Map<String, Set<PlayerId>> sides = Map.of(
                "blue", Set.of(blue1, blue2),
                "red", Set.of(red1)
        );

        ActiveDuelSession session = new ActiveDuelSession("session-1", sides, baseTime);

        assertThat(session.id()).isEqualTo("session-1");
        assertThat(session.allParticipants()).containsExactlyInAnyOrder(blue1, blue2, red1);
        assertThat(session.remainingPlayers()).containsExactlyInAnyOrder(blue1, blue2, red1);
        assertThat(session.getSide(blue1)).isEqualTo("blue");
        assertThat(session.getSide(red1)).isEqualTo("red");
        assertThat(session.getSide(PlayerId.of(UUID.randomUUID()))).isNull();

        assertThat(session.areOpponents(blue1, red1)).isTrue();
        assertThat(session.areOpponents(blue1, blue2)).isFalse();

        // Disconnect tracking
        assertThat(session.isDisconnected(blue2)).isFalse();
        session.markDisconnected(blue2, baseTime.plusSeconds(10));
        assertThat(session.isDisconnected(blue2)).isTrue();
        assertThat(session.getDisconnectTime(blue2)).contains(baseTime.plusSeconds(10));

        session.markReconnected(blue2);
        assertThat(session.isDisconnected(blue2)).isFalse();

        // Elimination
        assertThat(session.isConcluded()).isFalse();

        // Eliminate blue1
        boolean concludedAfterBlue1 = session.eliminate(blue1);
        assertThat(concludedAfterBlue1).isFalse();
        assertThat(session.remainingPlayers()).containsExactlyInAnyOrder(blue2, red1);
        assertThat(session.winningSide()).isEmpty();

        // Eliminate red1 -> only blue side remains!
        boolean concludedAfterRed1 = session.eliminate(red1);
        assertThat(concludedAfterRed1).isTrue();
        assertThat(session.isConcluded()).isTrue();
        assertThat(session.winningSide()).contains("blue");
        assertThat(session.winningPlayers()).containsExactly(blue2);

        // toParticipantList contains all original participants
        List<DuelParticipant> participants = session.toParticipantList();
        assertThat(participants).hasSize(3);
    }

    @Test
    @DisplayName("P1: DuelChallenge constructor rejects duplicate membership across sides")
    void duelChallengeRejectsDuplicateMemberAcrossSides() {
        PlayerId p1 = PlayerId.of(UUID.randomUUID());
        PlayerId p2 = PlayerId.of(UUID.randomUUID());

        Map<String, Set<PlayerId>> sides = Map.of(
                "side_1", Set.of(p1, p2),
                "side_2", Set.of(p2)
        );

        assertThatThrownBy(() -> new DuelChallenge("c-dup", p1, sides, baseTime, baseTime.plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Player cannot be on multiple sides");
    }

    @Test
    @DisplayName("P1: ActiveDuelSession constructor rejects duplicate membership across sides")
    void activeDuelSessionRejectsDuplicateMemberAcrossSides() {
        PlayerId p1 = PlayerId.of(UUID.randomUUID());
        PlayerId p2 = PlayerId.of(UUID.randomUUID());

        Map<String, Set<PlayerId>> sides = Map.of(
                "side_1", Set.of(p1, p2),
                "side_2", Set.of(p2)
        );

        assertThatThrownBy(() -> new ActiveDuelSession("s-dup", sides, baseTime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Player cannot be on multiple sides");
    }
}
