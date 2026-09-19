package money.hejje.sim;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import money.hejje.common.time.SimClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.PropertyResolver;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Runs the {@code @Scheduled} jobs of a SIM instance on simulation time (plan M7.1). Spring's own scheduler is replaced
 * by one that drops every task, and this class fires a job when the {@link SimClock} has crossed its next firing time:
 * {@code fixedDelay}/{@code fixedRate} (and their {@code String} forms, placeholders resolved) from the start plus the
 * initial delay, {@code cron} in its zone. Due jobs fire once per {@link #advance} in the {@link SimJobs} order; several
 * missed occurrences inside one step coalesce into one firing (a fixed-rate job keeps its phase). Jobs the registry marks
 * SKIP never fire; a {@code @Scheduled} method missing from the registry is refused at construction.
 */
public final class SimScheduler {

    private static final Logger log = LoggerFactory.getLogger(SimScheduler.class);
    private static final Pattern SIMPLE = Pattern.compile("(\\d+)(ns|us|ms|s|m|h|d)");

    /** How a job is triggered: an interval (with an initial delay) or a cron expression in a zone. */
    public record Trigger(Duration interval, boolean fixedRate, Duration initialDelay, CronExpression cron, ZoneId zone) {

        static Trigger every(Duration interval, boolean fixedRate, Duration initialDelay) {
            return new Trigger(interval, fixedRate, initialDelay, null, null);
        }

        static Trigger cron(CronExpression cron, ZoneId zone) {
            return new Trigger(null, false, Duration.ZERO, cron, zone);
        }

        Instant first(Instant start) {
            return cron != null ? next(start) : start.plus(initialDelay);
        }

        Instant after(Instant previousDue, Instant now) {
            if (cron != null) {
                return next(now);
            }
            if (!fixedRate) {
                return now.plus(interval);
            }
            Instant next = previousDue.plus(interval);
            while (!next.isAfter(now)) {
                next = next.plus(interval);
            }
            return next;
        }

        private Instant next(Instant after) {
            ZonedDateTime next = cron.next(after.atZone(zone));
            return next == null ? Instant.MAX : next.toInstant();
        }
    }

    /** One scheduled method: its registry key, how it runs and what it is. */
    public record Job(String key, Trigger trigger, Runnable task) {}

    private final SimClock clock;
    private final List<Job> jobs;
    private final SimJobs registry;
    private final Map<String, Instant> next = new LinkedHashMap<>();
    private final Map<String, Integer> fired = new LinkedHashMap<>();

    public SimScheduler(SimClock clock, SimJobs registry, List<Job> jobs) {
        Set<String> unknown = new TreeSet<>();
        for (Job job : jobs) {
            if (registry.get(job.key()) == null) {
                unknown.add(job.key());
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("@Scheduled method(s) " + unknown + " are not in SimJobs: decide RUN or SKIP for simulations before running SIM");
        }
        this.clock = clock;
        this.registry = registry;
        this.jobs = jobs.stream().filter(j -> registry.get(j.key()).policy() == SimJobs.Policy.RUN)
                .sorted(Comparator.comparingInt((Job j) -> registry.order(j.key())).thenComparing(Job::key)).toList();
        restart();
    }

    /** Recomputes every job's next firing from the clock (after the clock was set to a new session). */
    public synchronized void restart() {
        next.clear();
        Instant now = clock.instant();
        for (Job job : jobs) {
            next.put(job.key(), job.trigger().first(now));
        }
    }

    /** Moves the simulation clock forwards by {@code step} and fires every job now due. */
    public synchronized List<String> advance(Duration step) {
        clock.advance(step);
        return runDue();
    }

    /** Fires, in registry order, every job whose next firing is at or before the clock; returns their keys. */
    public synchronized List<String> runDue() {
        Instant now = clock.instant();
        List<String> ran = new ArrayList<>();
        for (Job job : jobs) {
            Instant due = next.get(job.key());
            if (due.isAfter(now)) {
                continue;
            }
            try {
                job.task().run();
            } catch (RuntimeException e) {
                log.warn("Simulated job {} failed at {}", job.key(), now, e);
            }
            fired.merge(job.key(), 1, Integer::sum);
            next.put(job.key(), job.trigger().after(due, now));
            ran.add(job.key());
        }
        return ran;
    }

    /** Next firing per running job. */
    public synchronized Map<String, Instant> nextFirings() {
        return new LinkedHashMap<>(next);
    }

    /** How often each job has fired since the scheduler was built. */
    public synchronized Map<String, Integer> firedCounts() {
        return new LinkedHashMap<>(fired);
    }

    /** The running jobs, in firing order. */
    public List<String> runningJobs() {
        return jobs.stream().map(Job::key).toList();
    }

    public SimJobs registry() {
        return registry;
    }

    /** Every {@code @Scheduled} method of the beans in {@code beans}, as jobs invoking the bean (through its proxy). */
    public static List<Job> discover(ListableBeanFactory beans, PropertyResolver properties, ZoneId defaultZone) {
        List<Job> out = new ArrayList<>();
        for (String name : beans.getBeanDefinitionNames()) {
            Class<?> type = beans.getType(name);
            if (type == null) {
                continue;
            }
            Class<?> target = org.springframework.util.ClassUtils.getUserClass(type);
            Map<Method, Set<Scheduled>> annotated = MethodIntrospector.selectMethods(target,
                    (MethodIntrospector.MetadataLookup<Set<Scheduled>>) m -> {
                        Set<Scheduled> s = AnnotatedElementUtils.getMergedRepeatableAnnotations(m, Scheduled.class, org.springframework.scheduling.annotation.Schedules.class);
                        return s.isEmpty() ? null : s;
                    });
            if (annotated.isEmpty()) {
                continue;
            }
            Object bean = beans.getBean(name);
            for (Map.Entry<Method, Set<Scheduled>> e : annotated.entrySet()) {
                Method invocable = AopUtils.selectInvocableMethod(e.getKey(), bean.getClass());
                ReflectionUtils.makeAccessible(invocable);
                String key = target.getSimpleName() + "#" + e.getKey().getName();
                for (Scheduled s : e.getValue()) {
                    out.add(new Job(key, trigger(s, properties, defaultZone), () -> ReflectionUtils.invokeMethod(invocable, bean)));
                }
            }
        }
        return out;
    }

    /** The trigger of one annotation, placeholders resolved like Spring's scheduler does. */
    public static Trigger trigger(Scheduled s, PropertyResolver properties, ZoneId defaultZone) {
        TimeUnit unit = s.timeUnit();
        String cron = properties.resolvePlaceholders(s.cron());
        if (StringUtils.hasText(cron)) {
            String zone = properties.resolvePlaceholders(s.zone());
            return Trigger.cron(CronExpression.parse(cron), StringUtils.hasText(zone) ? ZoneId.of(zone) : defaultZone);
        }
        Duration initial = duration(s.initialDelay(), s.initialDelayString(), unit, properties);
        Duration rate = duration(s.fixedRate(), s.fixedRateString(), unit, properties);
        if (rate != null) {
            return Trigger.every(rate, true, initial == null ? Duration.ZERO : initial);
        }
        Duration delay = duration(s.fixedDelay(), s.fixedDelayString(), unit, properties);
        if (delay == null) {
            throw new IllegalArgumentException("@Scheduled without cron, fixedDelay or fixedRate");
        }
        return Trigger.every(delay, false, initial == null ? Duration.ZERO : initial);
    }

    private static Duration duration(long value, String text, TimeUnit unit, PropertyResolver properties) {
        if (value >= 0) {
            return Duration.of(value, unit.toChronoUnit());
        }
        String resolved = properties.resolvePlaceholders(text).trim();
        if (resolved.isEmpty()) {
            return null;
        }
        return parse(resolved, unit);
    }

    /** ISO-8601 ({@code PT5M}), simple ({@code 30s}, {@code 5m}) or a bare number in {@code unit}. */
    static Duration parse(String text, TimeUnit unit) {
        if (text.startsWith("P") || text.startsWith("-P")) {
            return Duration.parse(text);
        }
        Matcher m = SIMPLE.matcher(text);
        if (m.matches()) {
            long n = Long.parseLong(m.group(1));
            return switch (m.group(2)) {
                case "ns" -> Duration.ofNanos(n);
                case "us" -> Duration.ofNanos(n * 1000);
                case "ms" -> Duration.ofMillis(n);
                case "s" -> Duration.ofSeconds(n);
                case "m" -> Duration.ofMinutes(n);
                case "h" -> Duration.ofHours(n);
                default -> Duration.ofDays(n);
            };
        }
        return Duration.of(Long.parseLong(text), unit.toChronoUnit());
    }
}
