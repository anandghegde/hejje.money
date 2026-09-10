package money.hejje.broker.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import money.hejje.broker.BrokerAccount;
import money.hejje.broker.BrokerAccountService;
import money.hejje.broker.BrokerAdapter;
import money.hejje.common.security.HejjePrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/broker/accounts")
class BrokerAccountController {

    private final BrokerAccountService accounts;
    private final BrokerAdapter broker;

    BrokerAccountController(BrokerAccountService accounts, BrokerAdapter broker) {
        this.accounts = accounts;
        this.broker = broker;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("adapter", broker.instrumentBrokerCode());
        out.put("active", accounts.active().orElse(null));
        out.put("accounts", accounts.list());
        return out;
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    Map<String, Object> activate(@PathVariable UUID id, @AuthenticationPrincipal HejjePrincipal principal) {
        BrokerAccount account = accounts.activate(id, principal.name());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("account", account);
        if (!account.broker().equals(broker.instrumentBrokerCode())) {
            out.put("note", "This server runs the " + broker.instrumentBrokerCode() + " adapter: restart it with hejje.broker.adapter="
                    + account.broker() + " (and log in) to trade on this account; until then orders are blocked.");
        }
        return out;
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(java.util.NoSuchElementException.class)
    org.springframework.http.ProblemDetail notFound(java.util.NoSuchElementException e) {
        return org.springframework.http.ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatus.NOT_FOUND, e.getMessage());
    }
}
