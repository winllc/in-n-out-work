package com.winllc.innoutwork.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunksTest {

    @Test
    void splitsIntoPiecesOfAtMostTheSizeKeepingOrder() {
        assertEquals(List.of(List.of(1, 2), List.of(3, 4), List.of(5)), Chunks.of(List.of(1, 2, 3, 4, 5), 2));
        assertEquals(List.of(List.of(1, 2)), Chunks.of(List.of(1, 2), 2));
    }

    @Test
    void anEmptyCollectionHasNoChunks() {
        assertTrue(Chunks.of(Set.of()).isEmpty());
    }
}
