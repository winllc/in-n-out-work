package com.winllc.innoutwork.data;

/**
 * Outcome of a directory -> database metadata refresh.
 *
 * @param scanned   directory entries examined
 * @param created   user records inserted because no row existed for the DN
 * @param updated   existing records whose metadata differed and were written
 * @param unchanged existing records that already matched the directory (no write)
 * @param skipped   directory entries ignored, e.g. an entry with no DN
 */
public record DirectorySyncResult(int scanned, int created, int updated, int unchanged, int skipped) {

    public static final DirectorySyncResult EMPTY = new DirectorySyncResult(0, 0, 0, 0, 0);

    /** Rows actually written; the rest of the scan cost nothing but a comparison. */
    public int written() {
        return created + updated;
    }
}
