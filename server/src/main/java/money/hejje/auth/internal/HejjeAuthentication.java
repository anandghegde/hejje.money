package money.hejje.auth.internal;

import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.security.ScopeCatalog;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Spring Security authentication wrapping a {@link HejjePrincipal}; authorities are {@code SCOPE_*}. */
public class HejjeAuthentication extends AbstractAuthenticationToken {

    private final HejjePrincipal principal;

    public HejjeAuthentication(HejjePrincipal principal) {
        super(principal.scopes().stream().sorted().map(s -> new SimpleGrantedAuthority(ScopeCatalog.authority(s))).toList());
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public HejjePrincipal getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal.name();
    }

}
