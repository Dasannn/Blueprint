package com.dasannn.socialblueprint.storage;

/**
 * Result of an atomic administrative score adjustment transaction.
 */
public record AdminAdjustmentResult(int beforeScore, int afterScore, int delta) {}
