package money.hejje.execution;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Smart, basket and split order settings ({@code hejje.execution.planning.*}, plan M5.3, docs/execution.md).
 *
 * @param legTimeout            longest a basket leg or split child is waited on for a terminal state (besides the deadline)
 * @param maxBasketLegs         legs per basket
 * @param basketDeadline        default basket deadline
 * @param splitDeadline         default split deadline
 * @param autoSplitAbove        intents above this quantity are split automatically with the defaults below (0 = only when asked)
 * @param autoSplitChildQuantity child size of automatic splits
 * @param autoSplitDelay        delay between automatic split children
 */
@ConfigurationProperties("hejje.execution.planning")
public record PlanningProperties(
        @DefaultValue("PT30S") Duration legTimeout,
        @DefaultValue("20") int maxBasketLegs,
        @DefaultValue("PT15M") Duration basketDeadline,
        @DefaultValue("PT15M") Duration splitDeadline,
        @DefaultValue("0") int autoSplitAbove,
        @DefaultValue("0") int autoSplitChildQuantity,
        @DefaultValue("PT1S") Duration autoSplitDelay) {
}
