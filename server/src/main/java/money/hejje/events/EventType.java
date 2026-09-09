package money.hejje.events;

/** PRD sections 18.1 (instrument) and 18.2 (market) event types. */
public enum EventType {
    // instrument events
    RESULTS, EARNINGS_CALL, BOARD_MEETING, DIVIDEND, EX_DIVIDEND, BONUS, SPLIT, BUYBACK, AGM, CORPORATE_ACTION, REGULATORY, COURT,
    PROMOTER_TRANSACTION, BLOCK_DEAL, INDEX_INCLUSION,
    // market events
    RBI_POLICY, FED_DECISION, INDIA_CPI, US_CPI, EMPLOYMENT_DATA, BUDGET, ELECTION, GEOPOLITICAL, FNO_EXPIRY, INDEX_REBALANCE, HOLIDAY;

    /** Scheduled macro releases and decisions whose proximity moves the whole market. */
    public boolean isMacro() {
        return switch (this) {
            case RBI_POLICY, FED_DECISION, INDIA_CPI, US_CPI, EMPLOYMENT_DATA, BUDGET, ELECTION, GEOPOLITICAL -> true;
            default -> false;
        };
    }

    /** Results-type instrument events (HIGH on the day). */
    public boolean isResults() {
        return this == RESULTS || this == EARNINGS_CALL;
    }

    /** Corporate actions whose ex-date changes the price basis. */
    public boolean isExDate() {
        return this == EX_DIVIDEND || this == BONUS || this == SPLIT;
    }

    public boolean isMarketScope() {
        return ordinal() >= RBI_POLICY.ordinal();
    }
}
