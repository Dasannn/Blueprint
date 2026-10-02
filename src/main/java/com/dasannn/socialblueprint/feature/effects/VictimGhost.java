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
        return select(killer, rows, since, now, names, java.util.concurrent.ThreadLocalRandom.current());
    }

    /** Picks among distinct eligible victims at random; always the first one meant one killer saw one ghost forever. */
    public static Optional<VictimGhost> select(PlayerId killer, List<PsychosisEvent> rows, Instant since,
                                               Instant now, Function<PlayerId, Optional<String>> names,
                                               java.util.Random random) {
        java.util.List<PlayerId> victims = new java.util.ArrayList<>(new java.util.LinkedHashSet<>(rows.stream()
                .filter(row -> row.killer().equals(killer) && row.context() == CombatContext.OPEN
                        && row.createdAt().isAfter(since) && !row.createdAt().isAfter(now))
                .map(PsychosisEvent::victim)
                .toList()));
        java.util.Collections.shuffle(victims, random);
        for (PlayerId victim : victims) {
            Optional<String> name = names.apply(victim).filter(value -> value.matches("[A-Za-z0-9_]{1,16}"));
            if (name.isPresent()) return Optional.of(new VictimGhost(victim, name.get()));
        }
        return Optional.empty();
    }
}
