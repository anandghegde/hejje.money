package money.hejje.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Reconciliation settings ({@code hejje.reconciliation.*}).
 *
 * @param pauseOnCritical set the kill switch (STOP_NEW_ORDERS) when a CRITICAL issue (position quantity mismatch) is found
 */
@ConfigurationProperties("hejje.reconciliation")
public record ReconciliationProperties(@DefaultValue("true") boolean pauseOnCritical) {
}
