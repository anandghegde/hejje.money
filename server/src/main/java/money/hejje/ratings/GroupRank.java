package money.hejje.ratings;

import java.time.LocalDate;

/** An industry group's rank for one session: {@code strength} is the median RS raw value of its rated members. */
public record GroupRank(LocalDate sessionDate, String groupId, String engineVersion, String name, int rank, double strength, int members) {
}
