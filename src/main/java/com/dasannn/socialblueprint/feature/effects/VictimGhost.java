package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.domain.CombatContext;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PsychosisEvent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Read-only selection; unresolved UUIDs never become display names. */
public record VictimGhost(PlayerId victim, String name) {
    public static Optional<VictimGhost> select(PlayerId killer, List<PsychosisEvent> rows, Instant since,
                                               Instant now, Function<PlayerId, Optional<String>> names) {
        for (PsychosisEvent row : rows) {
            if (!row.killer().equals(killer) || row.context() != CombatContext.OPEN
                    || !row.createdAt().isAfter(since) || row.createdAt().isAfter(now)) continue;
            Optional<String> name = names.apply(row.victim()).filter(value -> value.matches("[A-Za-z0-9_]{1,16}"));
            if (name.isPresent()) return Optional.of(new VictimGhost(row.victim(), name.get()));
        }
        return Optional.empty();
    }
}
