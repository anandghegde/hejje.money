package money.hejje.system.internal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Calls a plain-text "what is my IP" endpoint such as api.ipify.org. */
public final class HttpPublicIpResolver implements PublicIpResolver {

    private static final Logger log = LoggerFactory.getLogger(HttpPublicIpResolver.class);
    private static final Pattern IP = Pattern.compile("^[0-9a-fA-F:.]{7,45}$");

    private final URI uri;
    private final HttpClient client;
    private final Duration timeout;

    public HttpPublicIpResolver(String url, HttpClient client, Duration timeout) {
        this.uri = URI.create(url);
        this.client = client;
        this.timeout = timeout;
    }

    @Override
    public String name() {
        return uri.getHost();
    }

    @Override
    public Optional<String> resolve() {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Egress IP resolver {} returned HTTP {}", name(), response.statusCode());
                return Optional.empty();
            }
            String body = response.body().trim();
            if (!IP.matcher(body).matches()) {
                log.warn("Egress IP resolver {} returned unexpected body", name());
                return Optional.empty();
            }
            return Optional.of(body);
        } catch (Exception e) {
            log.warn("Egress IP resolver {} failed: {}", name(), e.toString());
            return Optional.empty();
        }
    }
}
