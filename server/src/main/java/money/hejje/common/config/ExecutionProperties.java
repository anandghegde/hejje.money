package money.hejje.common.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Execution-side settings ({@code hejje.execution.*}).
 *
 * @param expectedIps          public egress IPs registered with the broker; live execution is blocked unless the
 *                             server's egress IP is one of them
 * @param allowOffSessionPaper let PAPER-mode intents through outside the session (dev/replay only; live modes are
 *                             never exempt)
 */
@ConfigurationProperties("hejje.execution")
public record ExecutionProperties(@DefaultValue({}) List<String> expectedIps, @DefaultValue("false") boolean allowOffSessionPaper) {
}
