package money.hejje.backtest.experiments;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import money.hejje.backtest.Splits;

/** An experiment with its variants (ranked once DONE) and experiment-level notes such as the multiple-comparisons warning. */
public record Experiment(UUID id, UUID baseVersionId, UUID strategyId, String goal, Dataset dataset, Splits splits, ExperimentStatus status, String createdBy,
        UUID createdBySession, Instant createdAt, Instant finishedAt, String error, List<String> notes, List<Variant> variants) {

    public Experiment {
        notes = notes == null ? List.of() : List.copyOf(notes);
        variants = variants == null ? List.of() : List.copyOf(variants);
    }
}
