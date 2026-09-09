package money.hejje.pulse;

/**
 * One weighted rule of the Technical Pulse.
 *
 * @param value        −1..+1 (null when the rule's inputs are unavailable; the weight is then excluded)
 * @param contribution weight × value, in score points before normalisation
 * @param evidence     the observed numbers behind the value, templated
 */
public record PulseComponent(String name, double weight, Double value, double contribution, String evidence) {

    public boolean available() {
        return value != null;
    }

    public static PulseComponent unavailable(String name, double weight, String reason) {
        return new PulseComponent(name, weight, null, 0, reason);
    }
}
