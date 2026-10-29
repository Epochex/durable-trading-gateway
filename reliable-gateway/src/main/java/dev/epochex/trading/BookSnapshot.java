package dev.epochex.trading;

public record BookSnapshot(long[] askPrices, long[] askVolumes, long[] bidPrices, long[] bidVolumes) {}
