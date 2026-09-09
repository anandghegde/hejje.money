package money.hejje.strategy;

/** One structured definition error: a YAML path such as {@code entry.all[2]} and a message. */
public record ValidationError(String path, String message) {}
