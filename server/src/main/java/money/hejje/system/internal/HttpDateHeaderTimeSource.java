package money.hejje.system.internal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads the {@code Date} header of an HTTPS HEAD response (one-second resolution). */
public final class HttpDateHeaderTimeSource implements RemoteTimeSource {

    private static final Logger log = LoggerFactory.getLogger(HttpDateHeaderTimeSource.class);

    private final URI uri;
    private final HttpClient client;
    private final Duration timeout;

    public HttpDateHeaderTimeSource(String url, HttpClient client, Duration timeout) {
        this.uri = URI.create(url);
        this.client = client;
        this.timeout = timeout;
    }

    @Override
    public Optional<Instant> remoteNow() {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.headers().firstValue("Date")
                    .map(value -> ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (Exception e) {
            log.warn("Clock reference {} failed: {}", uri.getHost(), e.toString());
            return Optional.empty();
        }
    }
}
