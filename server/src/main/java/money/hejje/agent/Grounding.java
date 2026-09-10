package money.hejje.agent;

import java.util.List;

/**
 * Post-check of an answer against this turn's tool outputs: numbers found in them (verified) or not (unverified), and
 * cited ids that do or do not appear in them. Unverified claims are shown to the user, never dropped silently.
 */
public record Grounding(List<String> verifiedNumbers, List<String> unverifiedNumbers, List<String> citedIds, List<String> unknownIds) {

    public Grounding {
        verifiedNumbers = List.copyOf(verifiedNumbers);
        unverifiedNumbers = List.copyOf(unverifiedNumbers);
        citedIds = List.copyOf(citedIds);
        unknownIds = List.copyOf(unknownIds);
    }

    public boolean clean() {
        return unverifiedNumbers.isEmpty() && unknownIds.isEmpty();
    }
}
