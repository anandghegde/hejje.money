package money.hejje.common.config;

import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.ZoneId;
import money.hejje.common.ExecutionMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Top-level application settings bound from the {@code hejje.*} namespace.
 *
 * @param mode     global execution mode; defaults to PAPER
 * @param timezone business time zone; defaults to Asia/Kolkata
 * @param dataDir  directory for files the server owns (parquet, backups, ...)
 */
@Validated
@ConfigurationProperties("hejje")
public record HejjeProperties(
        @NotNull @DefaultValue("PAPER") ExecutionMode mode,
        @NotNull @DefaultValue("Asia/Kolkata") ZoneId timezone,
        @NotNull @DefaultValue("./data") Path dataDir) {
}
