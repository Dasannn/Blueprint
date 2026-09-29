package com.dasannn.socialblueprint.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves a {@link Status} or score to its corresponding {@link Tier}.
 * Per SB-010, SB-011, SB-012 and T-011:
 * - Nine tiers with signed, strictly ordered thresholds.
 * - Exactly 0 resolves to the neutral tier (Particular).
 * - A malformed ladder is rejected at construction with a message naming the offending tier.
 */
public final class TierLadder {

    private final Map<Tier, Integer> thresholds;
    private final int criminal;
    private final int forajido;
    private final int delincuente;
    private final int temerario;
    private final int particular;
    private final int afable;
    private final int honorable;
    private final int insigne;
    private final int ilustre;

    public TierLadder(Map<Tier, Integer> thresholds) {
        Objects.requireNonNull(thresholds, "Thresholds map must not be null");

        // Validate all 9 tiers are present
        for (Tier tier : Tier.values()) {
            if (!thresholds.containsKey(tier) || thresholds.get(tier) == null) {
                throw new IllegalArgumentException("Malformed tier ladder: missing threshold for tier '" + tier.displayName() + "'");
            }
        }

        int tCrim = thresholds.get(Tier.CRIMINAL);
        int tFor = thresholds.get(Tier.FORAJIDO);
        int tDel = thresholds.get(Tier.DELINCUENTE);
        int tTem = thresholds.get(Tier.TEMERARIO);
        int tPart = thresholds.get(Tier.PARTICULAR);
        int tAfa = thresholds.get(Tier.AFABLE);
        int tHon = thresholds.get(Tier.HONORABLE);
        int tIns = thresholds.get(Tier.INSIGNE);
        int tIlu = thresholds.get(Tier.ILUSTRE);

        // Neutral tier must be exactly 0 (SB-012, T-011)
        if (tPart != 0) {
            throw new IllegalArgumentException("Malformed tier ladder: offending tier '" + Tier.PARTICULAR.displayName()
                    + "' threshold must be exactly 0, got " + tPart);
        }

        // Negative tiers must be strictly negative and strictly ascending:
        // tCrim < tFor < tDel < tTem < 0
        if (tTem >= 0) {
            throw new IllegalArgumentException("Malformed tier ladder: offending tier '" + Tier.TEMERARIO.displayName()
                    + "' threshold must be strictly negative (< 0), got " + tTem);
        }
        if (tDel >= 0) {
            throw new IllegalArgumentException("Malformed tier ladder: offending tier '" + Tier.DELINCUENTE.displayName()
                    + "' threshold must be strictly negative (< 0), got " + tDel);
        }
        if (tFor >= 0) {
            throw new IllegalArgumentException("Malformed tier ladder: offending tier '" + Tier.FORAJIDO.displayName()
                    + "' threshold must be strictly negative (< 0), got " + tFor);
        }
        if (tCrim >= 0) {
            throw new IllegalArgumentException("Malformed tier ladder: offending tier '" + Tier.CRIMINAL.displayName()
                    + "' threshold must be strictly negative (< 0), got " + tCrim);
        }

        if (tDel >= tTem) {
            throw new IllegalArgumentException("Malformed tier ladder: offending tier '" + Tier.DELINCUENTE.displayName()
                    + "' threshold (" + tDel + ") must be strictly less than '" + Tier.TEMERARIO.displayName() + "' threshold (" + tTem + ")");
        }
        if (tFor >= tDel) {
            throw new IllegalArgumentException("Malformed tier ladder: offending tier '" + Tier.FORAJIDO.displayName()
                    + "' threshold (" + tFor + ") must be strictly less than '" + Tier.DELINCUENTE.displayName() + "' threshold (" + tDel + ")");
        }
        if (tCrim >= tFor) {
            throw new IllegalArgumentException("Malformed tier ladder: offending tier '" + Tier.CRIMINAL.displayName()
                    + "' threshold (" + tCrim + ") must be strictly less than '" + Tier.FORAJIDO.displayName() + "' threshold (" + tFor + ")");
        }

        // Positive tiers must be strictly positive and strictly ascending:
        // 0 < tAfa < tHon < tIns < tIlu
        if (tAfa <= 0) {
            throw new IllegalArgumentException("Malformed tier ladder: offending tier '" + Tier.AFABLE.displayName()
                    + "' threshold must be strictly positive (> 0), got " + tAfa);
        }
        if (tHon <= tAfa) {
            throw new IllegalArgumentException("Malformed tier ladder: offending tier '" + Tier.HONORABLE.displayName()
                    + "' threshold (" + tHon + ") must be strictly greater than '" + Tier.AFABLE.displayName() + "' threshold (" + tAfa + ")");
        }
        if (tIns <= tHon) {
            throw new IllegalArgumentException("Malformed tier ladder: offending tier '" + Tier.INSIGNE.displayName()
                    + "' threshold (" + tIns + ") must be strictly greater than '" + Tier.HONORABLE.displayName() + "' threshold (" + tHon + ")");
        }
        if (tIlu <= tIns) {
            throw new IllegalArgumentException("Malformed tier ladder: offending tier '" + Tier.ILUSTRE.displayName()
                    + "' threshold (" + tIlu + ") must be strictly greater than '" + Tier.INSIGNE.displayName() + "' threshold (" + tIns + ")");
        }

        this.thresholds = Collections.unmodifiableMap(new EnumMap<>(thresholds));
        this.criminal = tCrim;
        this.forajido = tFor;
        this.delincuente = tDel;
        this.temerario = tTem;
        this.particular = tPart;
        this.afable = tAfa;
        this.honorable = tHon;
        this.insigne = tIns;
        this.ilustre = tIlu;
    }

    public static TierLadder of(Map<Tier, Integer> thresholds) {
        return new TierLadder(thresholds);
    }

    /**
     * Resolves a {@link Status} to its corresponding {@link Tier}.
     */
    public Tier resolve(Status status) {
        Objects.requireNonNull(status, "Status must not be null");
        return resolve(status.value());
    }

    /**
     * Resolves an integer status score to its corresponding {@link Tier}.
     * Covered across the full signed integer range without gaps or fallbacks.
     */
    public Tier resolve(int status) {
        if (status <= criminal) {
            return Tier.CRIMINAL;
        } else if (status <= forajido) {
            return Tier.FORAJIDO;
        } else if (status <= delincuente) {
            return Tier.DELINCUENTE;
        } else if (status <= temerario) {
            return Tier.TEMERARIO;
        } else if (status < afable) {
            return Tier.PARTICULAR; // includes exactly 0, as temerario < 0 < afable
        } else if (status < honorable) {
            return Tier.AFABLE;
        } else if (status < insigne) {
            return Tier.HONORABLE;
        } else if (status < ilustre) {
            return Tier.INSIGNE;
        } else {
            return Tier.ILUSTRE;
        }
    }

    public int threshold(Tier tier) {
        return thresholds.get(tier);
    }

    public Map<Tier, Integer> thresholds() {
        return thresholds;
    }
}
