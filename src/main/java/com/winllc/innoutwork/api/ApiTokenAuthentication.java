package com.winllc.innoutwork.api;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.ServiceAuthentication;
import com.winllc.innoutwork.data.LdapDn;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * A request signed in with a public API token, for that request only. Its name is the token owner's DN, so
 * AccessPolicy decides what it sees exactly as for the owner; its authorities are the owner's current roles, the
 * token's scopes ({@code SCOPE_...}) and {@link ApiScope#API_TOKEN_AUTHORITY}. Audited as the token, not the owner.
 * Never stored: the API's chain keeps no session.
 */
public final class ApiTokenAuthentication extends AbstractAuthenticationToken implements ServiceAuthentication {

    private final long tokenId;
    private final String tokenName;
    private final String ownerDn;
    private final Set<ApiScope> scopes;

    public ApiTokenAuthentication(long tokenId, String tokenName, String ownerDn, Set<ApiScope> scopes,
                                  Collection<? extends GrantedAuthority> ownerAuthorities) {
        super(authorities(scopes, ownerAuthorities));
        this.tokenId = tokenId;
        this.tokenName = tokenName;
        this.ownerDn = ownerDn;
        this.scopes = Set.copyOf(scopes);
        setAuthenticated(true);
    }

    private static List<GrantedAuthority> authorities(Set<ApiScope> scopes,
                                                      Collection<? extends GrantedAuthority> owner) {
        List<GrantedAuthority> all = new ArrayList<>(owner);
        all.add(new SimpleGrantedAuthority(ApiScope.API_TOKEN_AUTHORITY));
        scopes.forEach(s -> all.add(new SimpleGrantedAuthority(s.authority())));
        return all;
    }

    public long tokenId() {
        return tokenId;
    }

    public Set<ApiScope> scopes() {
        return scopes;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return ownerDn;
    }

    @Override
    public String getName() {
        return ownerDn;
    }

    @Override
    public AuditEntry.Actor auditActor() {
        List<String> roles = getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().distinct().toList();
        return new AuditEntry.Actor("api-token", "api-token:" + tokenId,
                tokenName + " (for " + LdapDn.cnOf(ownerDn) + ")", roles);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ApiTokenAuthentication other && other.tokenId == tokenId && super.equals(o);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(tokenId) * 31 + super.hashCode();
    }
}
