package money.hejje.system;

import java.time.Instant;
import java.util.Optional;
import money.hejje.common.ExecutionMode;
import money.hejje.common.config.HejjeProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/server")
class ServerController {

    private final HejjeProperties properties;
    private final String version;

    ServerController(HejjeProperties properties, Optional<BuildProperties> buildProperties) {
        this.properties = properties;
        this.version = buildProperties.map(BuildProperties::getVersion).orElse("unknown");
    }

    record Health(String status, ExecutionMode mode, String version, Instant time) {}

    @GetMapping("/health")
    Health health() {
        return new Health("UP", properties.mode(), version, Instant.now());
    }
}
