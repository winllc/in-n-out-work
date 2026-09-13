package com.winllc.innoutwork.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Splits a collection into fixed-size pieces, for queries with an IN list. */
public final class Chunks {

    /**
     * Values per IN list. PostgreSQL's driver allows 32,767 bound parameters per statement; staying far
     * below keeps statements a reasonable size and lets the planner cache them.
     */
    public static final int IN_LIST_SIZE = 1000;

    private Chunks() {
    }

    public static <T> List<List<T>> of(Collection<T> items, int size) {
        List<T> all = new ArrayList<>(items);
        List<List<T>> chunks = new ArrayList<>();
        for (int from = 0; from < all.size(); from += size) {
            chunks.add(all.subList(from, Math.min(from + size, all.size())));
        }
        return chunks;
    }

    public static <T> List<List<T>> of(Collection<T> items) {
        return of(items, IN_LIST_SIZE);
    }
}
