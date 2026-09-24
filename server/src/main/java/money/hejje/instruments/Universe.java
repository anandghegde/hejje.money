package money.hejje.instruments;

import java.util.List;
import java.util.Map;

/**
 * A named list of symbols from {@code config/universe/<name>.yaml}.
 *
 * @param index    the index the universe tracks, or null
 * @param symbols  Hejje symbols in file order, upper case
 * @param industry industry per symbol (empty when the file has none)
 */
public record Universe(String name, String index, List<String> symbols, Map<String, String> industry) {

    /** The universe resolved through the instrument master; {@code unresolved} lists the symbols the master lacks. */
    public record Resolved(Universe universe, List<Instrument> instruments, List<String> unresolved) {
    }
}
