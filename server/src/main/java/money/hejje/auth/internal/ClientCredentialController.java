package money.hejje.auth.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import money.hejje.common.security.AgentPresets;
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

    /** Either explicit {@code scopes} or an agent {@code preset} ({@link AgentPresets}: research, execution). */
    record CreateRequest(@NotBlank String name, List<String> scopes, String preset, Instant expiresAt) {}

    private final ClientCredentialService clients;

    ClientCredentialController(ClientCredentialService clients) {
        this.clients = clients;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ClientCredentialService.Created create(@Valid @RequestBody CreateRequest request, @AuthenticationPrincipal HejjePrincipal actor) {
        boolean hasScopes = request.scopes() != null && !request.scopes().isEmpty();
        boolean hasPreset = request.preset() != null && !request.preset().isBlank();
        if (hasScopes == hasPreset) {
            throw new IllegalArgumentException("Give either scopes or a preset (" + String.join(", ", AgentPresets.names()) + ")");
        }
        return clients.create(request.name(), hasPreset ? AgentPresets.scopes(request.preset()) : request.scopes(), request.expiresAt(), actor);
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
