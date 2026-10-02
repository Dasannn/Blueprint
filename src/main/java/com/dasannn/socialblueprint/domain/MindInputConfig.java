package com.dasannn.socialblueprint.domain;

/** Amounts mean drain/weight for bad inputs and gain/cure for good inputs. */
public record MindInputConfig(boolean enabled, double sereneAmount, double psychosisAmount, int cap) {
    public MindInputConfig {
        if (!Double.isFinite(sereneAmount) || sereneAmount < 0 || !Double.isFinite(psychosisAmount)
                || psychosisAmount < 0 || cap < 0) throw new IllegalArgumentException("Invalid mind input amounts or cap");
    }
}
