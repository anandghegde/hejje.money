package money.hejje.recommend;

/** One PRD 15 caution (docs/decisions.md): a structured reason a viable trade is only TRADE WITH CAUTION. */
public record Caution(String code, String message) {
}
