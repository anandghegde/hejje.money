package money.hejje.auth.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import money.hejje.common.security.HejjePrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/clients")
@PreAuthorize("hasAuthority('SCOPE_admin')")
class ClientCredentialController {

    record CreateRequest(@NotBlank String name, @NotEmpty List<String> scopes, Instant expiresAt) {}

    private final ClientCredentialService clients;

    ClientCredentialController(ClientCredentialService clients) {
        this.clients = clients;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ClientCredentialService.Created create(@Valid @RequestBody CreateRequest request, @AuthenticationPrincipal HejjePrincipal actor) {
        return clients.create(request.name(), request.scopes(), request.expiresAt(), actor);
    }

    @GetMapping
    List<ClientCredentialService.Summary> list() {
        return clients.list();
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> revoke(@PathVariable UUID id, @AuthenticationPrincipal HejjePrincipal actor) {
        if (!clients.exists(id)) {
            return ResponseEntity.notFound().build();
        }
        clients.revoke(id, actor);
        return ResponseEntity.noContent().build();
    }
}
