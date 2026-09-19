package com.winllc.innoutwork.service;

import com.winllc.innoutwork.data.LdapGroup;

/**
 * Thrown when the directory fails part way through a recursive group walk, leaving the
 * tree missing branches.
 *
 * <p>It exists to keep a half-built tree out of the cache. A failed child enumeration
 * used to return the node it had managed to build, which is indistinguishable from a
 * genuine leaf group — and once that was cached, a single transient directory failure
 * served a group with no children for the whole expiry window, with nothing able to
 * evict it.
 *
 * <p>The partial tree still travels on the exception, so the request that hit the
 * failure can render what was built. Nothing stores it, so the next request goes back
 * to the directory.
 */
public class GroupTreeIncompleteException extends RuntimeException {

    /** Not serialised: this travels between beans inside one JVM, never over the wire. */
    private final transient LdapGroup partial;

    public GroupTreeIncompleteException(String dn, LdapGroup partial, Throwable cause) {
        super("Group tree for %s is incomplete; the directory failed during the walk".formatted(dn), cause);
        this.partial = partial;
    }

    /** The tree as far as it was built, or {@code null} if nothing usable was produced. */
    public LdapGroup getPartial() {
        return partial;
    }
}
