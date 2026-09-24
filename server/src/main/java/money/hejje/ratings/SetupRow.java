package money.hejje.ratings;

/** A row of a list: the stock's ratings for the session and, for the setup lists, the base it is listed for. */
public record SetupRow(DailyRating rating, Base base) {
}
