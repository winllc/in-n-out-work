package com.winllc.innoutwork.diagnostics;

import org.springframework.ldap.core.ContextSource;

import javax.naming.directory.DirContext;

/**
 * Counts each context the LDAP template opens. The template takes one per operation (search, lookup), and a
 * paged search one for all its pages, so this is the number of directory operations.
 */
class CountingContextSource implements ContextSource {

    private final ContextSource delegate;

    CountingContextSource(ContextSource delegate) {
        this.delegate = delegate;
    }

    ContextSource delegate() {
        return delegate;
    }

    @Override
    public DirContext getReadOnlyContext() {
        RequestMetrics.ldapOperation();
        return delegate.getReadOnlyContext();
    }

    @Override
    public DirContext getReadWriteContext() {
        RequestMetrics.ldapOperation();
        return delegate.getReadWriteContext();
    }

    @Override
    public DirContext getContext(String principal, String credentials) {
        RequestMetrics.ldapOperation();
        return delegate.getContext(principal, credentials);
    }
}
