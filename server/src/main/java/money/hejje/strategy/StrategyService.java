package money.hejje.strategy;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Ids;
import money.hejje.common.event.EventMeta;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.strategy.StrategyDefinition.UniverseEntry;
import money.hejje.strategy.StrategyDefinition.UniverseKind;
import money.hejje.strategy.internal.DefinitionParser;
import money.hejje.strategy.internal.StrategyLifecycle;
import money.hejje.strategy.internal.StrategyStore;
import money.hejje.strategy.internal.StrategyValidator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public API of the strategy module: definitions, immutable versions, lifecycle and deployments. */
@Service
public class StrategyService {

    public static final String BUNDLED_CHANGE_NOTE = "bundled";

    private final StrategyStore store;
    private final DefinitionParser parser;
    private final StrategyValidator validator;
    private final StrategyLifecycle lifecycle;
    private final InstrumentService instruments;
    private final StrategyProperties properties;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final HejjeClock clock;
    private final org.springframework.beans.factory.ObjectProvider<PaperTradeEvidence> paperTrades;
    private final money.hejje.common.config.AutoProperties auto;
    private final JsonMapper canonical = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    StrategyService(StrategyStore store, DefinitionParser parser, StrategyValidator validator, StrategyLifecycle lifecycle,
            InstrumentService instruments, StrategyProperties properties, AuditService audit, ApplicationEventPublisher events,
            HejjeClock clock, org.springframework.beans.factory.ObjectProvider<PaperTradeEvidence> paperTrades, money.hejje.common.config.AutoProperties auto) {
        this.paperTrades = paperTrades;
        this.auto = auto;
        this.store = store;
        this.parser = parser;
        this.validator = validator;
        this.lifecycle = lifecycle;
        this.instruments = instruments;
        this.properties = properties;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    // --- definitions ---

    /** Parses and validates; throws {@link StrategyValidationException} with every error found. */
    public StrategyDefinition parse(String yaml) {
        StrategyDefinition definition = parser.parse(yaml);
        List<ValidationError> errors = validator.validate(definition);
        if (!errors.isEmpty()) {
            throw new StrategyValidationException(errors);
        }
        return definition;
    }

    /** The definition document as a plain, insertion-ordered tree (experiment deltas, M4.7). */
    public Map<String, Object> readTree(String yaml) {
        return parser.readTree(yaml);
    }

    public String writeTree(Map<String, Object> tree) {
        return parser.writeTree(tree);
    }

    public ValidationReport validate(String yaml) {
        try {
            return new ValidationReport(true, List.of(), parse(yaml));
        } catch (StrategyValidationException e) {
            return new ValidationReport(false, e.errors(), null);
        }
    }

    /** SHA-256 of the canonical JSON form of the parsed definition (whitespace and comments do not count). */
    public String hash(StrategyDefinition definition) {
        try {
            byte[] bytes = canonical.writeValueAsBytes(definition);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash definition", e);
        }
    }

    // --- strategies and versions ---

    /** Creates a strategy (slug = definition name) with version 1 in DRAFT. */
    @Transactional
    public StrategyVersion create(String yaml, String changeNote, String createdBy) {
        StrategyDefinition definition = parse(yaml);
        if (store.findStrategyBySlug(definition.name()).isPresent()) {
            throw new StrategyException.Conflict("A strategy named '" + definition.name() + "' already exists; add a version instead");
        }
        UUID id = Ids.newId();
        Instant now = clock.now();
        store.insertStrategy(id, definition.name(), definition.family(), definition.name(), now);
        audit.record(AuditEvent.of(AuditEventType.STRATEGY_CREATED, actorOf(createdBy)).withActorId(createdBy).withStrategyId(id)
                .withPayload(Map.of("slug", definition.name(), "family", definition.family().name())));
        return insertVersion(id, 1, null, yaml, definition, changeNote == null || changeNote.isBlank() ? "initial" : changeNote, createdBy, now);
    }

    /** Adds the next version; the definition name must match the strategy slug and differ from the latest version. */
    @Transactional
    public StrategyVersion addVersion(UUID strategyId, String yaml, String changeNote, String createdBy) {
        Strategy strategy = requireStrategy(strategyId);
        StrategyDefinition definition = parse(yaml);
        if (!definition.name().equals(strategy.slug())) {
            throw new StrategyValidationException(List.of(new ValidationError("name",
                    "must be '" + strategy.slug() + "' for a new version of this strategy (clone to rename)")));
        }
        if (changeNote == null || changeNote.isBlank()) {
            throw new IllegalArgumentException("A change note is required for a new version");
        }
        StrategyVersion latest = store.findLatestVersion(strategyId).orElse(null);
        String hash = hash(definition);
        if (latest != null && latest.definitionHash().equals(hash)) {
            throw new StrategyException.Conflict("Definition is identical to version " + latest.version());
        }
        int next = latest == null ? 1 : latest.version() + 1;
        return insertVersion(strategyId, next, latest == null ? null : latest.id(), yaml, definition, changeNote, createdBy, clock.now());
    }

    private StrategyVersion insertVersion(UUID strategyId, int number, UUID parentId, String yaml, StrategyDefinition definition,
            String changeNote, String createdBy, Instant now) {
        StrategyVersion version = new StrategyVersion(Ids.newId(), strategyId, number, yaml, definition, hash(definition), changeNote,
                parentId, createdBy, now, VersionStatus.DRAFT);
        store.insertVersion(version);
        audit.record(AuditEvent.of(AuditEventType.STRATEGY_VERSION_CREATED, actorOf(createdBy)).withActorId(createdBy)
                .withStrategyId(strategyId).withPayload(Map.of("versionId", version.id().toString(), "version", number,
                        "hash", version.definitionHash(), "changeNote", changeNote)));
        return version;
    }

    /** Copies the latest version of a strategy into a new strategy named {@code newName} (version 1, DRAFT). */
    @Transactional
    public StrategyVersion clone(UUID strategyId, String newName, String createdBy) {
        Strategy source = requireStrategy(strategyId);
        StrategyVersion latest = store.findLatestVersion(strategyId)
                .orElseThrow(() -> new StrategyException.Conflict("Strategy " + source.slug() + " has no versions"));
        Map<String, Object> tree = new LinkedHashMap<>(parser.readTree(latest.definitionYaml()));
        tree.put("name", newName);
        tree.remove("version");
        String yaml = parser.writeTree(tree);
        return create(yaml, "cloned from " + source.slug() + " v" + latest.version(), createdBy);
    }

    public List<Strategy> list() {
        return store.findAllStrategies();
    }

    public Optional<Strategy> find(UUID id) {
        return store.findStrategy(id);
    }

    public Optional<Strategy> findBySlug(String slug) {
        return store.findStrategyBySlug(slug);
    }

    public List<StrategyVersion> versions(UUID strategyId) {
        requireStrategy(strategyId);
        return store.findVersions(strategyId);
    }

    public Optional<StrategyVersion> version(UUID strategyId, int version) {
        return store.findVersion(strategyId, version);
    }

    public Optional<StrategyVersion> versionById(UUID versionId) {
        return store.findVersionById(versionId);
    }

    public Optional<StrategyVersion> latestVersion(UUID strategyId) {
        return store.findLatestVersion(strategyId);
    }

    public Optional<StrategyVersion> versionByHash(UUID strategyId, String hash) {
        return store.findVersionByHash(strategyId, hash);
    }

    /** Versions currently in any of the given statuses (the signal engine asks for PAPER/LIVE). */
    public List<StrategyVersion> versionsIn(List<VersionStatus> statuses) {
        return store.findVersionsByStatus(statuses);
    }

    // --- lifecycle ---

    @Transactional
    public StrategyVersion changeStatus(UUID strategyId, int number, VersionStatus to, String note, String by) {
        return changeStatus(strategyId, number, to, note, by, false);
    }

    /**
     * @param force bypass the lifecycle's evidence and transition checks; only honoured when
     *              {@code hejje.strategy.allow-forced-status} is set (dev/test), and always audited as forced
     */
    @Transactional
    public StrategyVersion changeStatus(UUID strategyId, int number, VersionStatus to, String note, String by, boolean force) {
        StrategyVersion version = store.findVersion(strategyId, number)
                .orElseThrow(() -> new StrategyException.NotFound("Version " + number + " of strategy " + strategyId + " not found"));
        boolean forced = force && properties.allowForcedStatus();
        String rejection = forced ? (version.status() == to ? "version is already " + to : null) : lifecycle.reject(version, to);
        if (rejection != null) {
            throw new StrategyException.Conflict(rejection);
        }
        VersionStatus from = version.status();
        store.updateVersionStatus(version.id(), to);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("versionId", version.id().toString());
        payload.put("version", number);
        payload.put("from", from.name());
        payload.put("to", to.name());
        if (note != null && !note.isBlank()) {
            payload.put("note", note);
        }
        if (forced) {
            payload.put("forced", true);
        }
        audit.record(AuditEvent.of(AuditEventType.STRATEGY_STATUS_CHANGED, actorOf(by)).withActorId(by).withStrategyId(strategyId).withPayload(payload));
        if (to == VersionStatus.PAUSED) {
            audit.record(AuditEvent.of(AuditEventType.STRATEGY_PAUSED, actorOf(by)).withActorId(by).withStrategyId(strategyId).withPayload(payload));
        }
        if (to == VersionStatus.PAUSED || to == VersionStatus.RETIRED) {
            for (StrategyDeployment d : store.findDeployments(version.id(), null, true)) {
                pauseDeployment(d, "version " + to, by);
            }
        }
        if (to == VersionStatus.RETIRED && store.findVersions(strategyId).stream().allMatch(v -> v.id().equals(version.id()) || v.status() == VersionStatus.RETIRED)) {
            store.retireStrategy(strategyId, clock.now());
        }
        events.publishEvent(new StrategyVersionStatusChanged(EventMeta.create(clock), strategyId, version.id(), from, to));
        return store.findVersionById(version.id()).orElseThrow();
    }

    // --- deployments ---

    /**
     * Deploys a version. PAPER deployments need a version in PAPER or LIVE; CONFIRM/AUTO deployments need LIVE.
     * {@code symbols} may be empty, in which case the definition's universe is resolved.
     */
    @Transactional
    public StrategyDeployment deploy(UUID strategyId, int number, ExecutionMode mode, List<String> symbols, int autonomyLevel,
            Map<String, Object> params, String by) {
        StrategyVersion version = store.findVersion(strategyId, number)
                .orElseThrow(() -> new StrategyException.NotFound("Version " + number + " of strategy " + strategyId + " not found"));
        if (mode == null) {
            throw new IllegalArgumentException("Deployment mode is required");
        }
        boolean allowed = mode.simulated()
                ? version.status() == VersionStatus.PAPER || version.status() == VersionStatus.LIVE
                : version.status() == VersionStatus.LIVE;
        if (!allowed) {
            throw new StrategyException.Conflict("Version " + number + " is " + version.status() + "; a " + mode + " deployment needs "
                    + (mode.simulated() ? "PAPER or LIVE" : "LIVE"));
        }
        if (autonomyLevel < 0 || autonomyLevel > 5) {
            throw new IllegalArgumentException("Autonomy level must be between 0 and 5");
        }
        if (autonomyLevel >= 4) {
            // levels 4-5 (plan M5.2): never on CONFIRM; the mode rules above already require PAPER/LIVE (PAPER) or LIVE (AUTO), i.e. past VALIDATED
            if (mode == ExecutionMode.CONFIRM) {
                throw new IllegalArgumentException("A CONFIRM deployment confirms every trade: autonomy 4-5 needs an AUTO deployment (or PAPER to rehearse)");
            }
            if (mode == ExecutionMode.AUTO) {
                int closed = paperTrades.getIfAvailable(() -> v -> 0).closedPaperTrades(version.id());
                if (closed < auto.minPaperTrades()) {
                    throw new StrategyException.Conflict("Version " + number + " has " + closed + " closed paper trade(s); autonomy 4-5 in AUTO needs at least "
                            + auto.minPaperTrades() + " (hejje.auto.min-paper-trades): a new strategy version is never automatic");
                }
            }
        }
        List<UUID> instrumentIds = symbols == null || symbols.isEmpty()
                ? resolveUniverse(version.definition()).stream().map(Instrument::id).toList()
                : resolveSymbols(symbols).stream().map(Instrument::id).toList();
        if (instrumentIds.isEmpty()) {
            throw new IllegalArgumentException("No instruments resolved for the deployment");
        }
        StrategyDeployment deployment = new StrategyDeployment(Ids.newId(), version.id(), strategyId, mode, instrumentIds, autonomyLevel,
                true, params == null ? Map.of() : params, clock.now(), null, null, null);
        store.insertDeployment(deployment);
        audit.record(AuditEvent.of(AuditEventType.STRATEGY_DEPLOYED, actorOf(by)).withActorId(by).withStrategyId(strategyId)
                .withPayload(Map.of("deploymentId", deployment.id().toString(), "versionId", version.id().toString(), "mode", mode.name(),
                        "instruments", instrumentIds.stream().map(UUID::toString).toList(), "autonomyLevel", autonomyLevel)));
        events.publishEvent(new DeploymentChanged(EventMeta.create(clock), deployment.id(), version.id(), true));
        return deployment;
    }

    @Transactional
    public StrategyDeployment updateDeployment(UUID id, boolean enabled, String reason, String by) {
        StrategyDeployment d = store.findDeployment(id).orElseThrow(() -> new StrategyException.NotFound("Deployment " + id + " not found"));
        if (enabled) {
            StrategyVersion version = store.findVersionById(d.versionId()).orElseThrow();
            if (version.status() != VersionStatus.PAPER && version.status() != VersionStatus.LIVE) {
                throw new StrategyException.Conflict("Version is " + version.status() + "; move it to PAPER or LIVE before enabling deployments");
            }
            if (d.enabled()) {
                return d;
            }
            store.updateDeployment(id, true, null, null);
            audit.record(AuditEvent.of(AuditEventType.STRATEGY_DEPLOYMENT_UPDATED, actorOf(by)).withActorId(by).withStrategyId(d.strategyId())
                    .withPayload(Map.of("deploymentId", id.toString(), "enabled", true)));
            events.publishEvent(new DeploymentChanged(EventMeta.create(clock), id, d.versionId(), true));
            return store.findDeployment(id).orElseThrow();
        }
        if (!d.enabled()) {
            return d;
        }
        pauseDeployment(d, reason == null || reason.isBlank() ? "paused by " + by : reason, by);
        return store.findDeployment(id).orElseThrow();
    }

    private void pauseDeployment(StrategyDeployment d, String reason, String by) {
        store.updateDeployment(d.id(), false, clock.now(), reason);
        audit.record(AuditEvent.of(AuditEventType.STRATEGY_DEPLOYMENT_UPDATED, actorOf(by)).withActorId(by).withStrategyId(d.strategyId())
                .withPayload(Map.of("deploymentId", d.id().toString(), "enabled", false, "reason", reason)));
        events.publishEvent(new DeploymentChanged(EventMeta.create(clock), d.id(), d.versionId(), false));
    }

    /** Sets the deployment's size multiplier (0.01-1.00); audited with the reason. Takes effect for the next signal. */
    @Transactional
    public StrategyDeployment setSizeMultiplier(UUID id, java.math.BigDecimal multiplier, String reason, String by) {
        StrategyDeployment d = store.findDeployment(id).orElseThrow(() -> new StrategyException.NotFound("Deployment " + id + " not found"));
        java.math.BigDecimal m = multiplier.setScale(2, java.math.RoundingMode.HALF_UP);
        if (m.signum() <= 0 || m.compareTo(java.math.BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Size multiplier must be above 0 and at most 1");
        }
        if (m.compareTo(d.sizeMultiplier()) == 0) {
            return d;
        }
        store.updateSizeMultiplier(id, m);
        audit.record(AuditEvent.of(AuditEventType.STRATEGY_DEPLOYMENT_UPDATED, actorOf(by)).withActorId(by).withStrategyId(d.strategyId())
                .withPayload(Map.of("deploymentId", id.toString(), "sizeMultiplier", m.toPlainString(), "from", d.sizeMultiplier().toPlainString(),
                        "reason", reason == null ? "" : reason)));
        events.publishEvent(new DeploymentChanged(EventMeta.create(clock), id, d.versionId(), d.enabled()));
        return store.findDeployment(id).orElseThrow();
    }

    /**
     * Moves a CONFIRM/AUTO deployment to paper: pauses it with {@code reason} and deploys the same version on the same
     * instruments, parameters and size multiplier in PAPER at autonomy 0 (the drift monitor's MOVE_TO_PAPER action).
     */
    @Transactional
    public StrategyDeployment moveToPaper(UUID id, String reason, String by) {
        StrategyDeployment d = store.findDeployment(id).orElseThrow(() -> new StrategyException.NotFound("Deployment " + id + " not found"));
        if (d.mode().simulated()) {
            throw new StrategyException.Conflict("Deployment " + id + " is already " + d.mode());
        }
        if (d.enabled()) {
            pauseDeployment(d, reason, by);
        }
        StrategyDeployment paper = new StrategyDeployment(Ids.newId(), d.versionId(), d.strategyId(), ExecutionMode.PAPER, d.instrumentIds(), 0, true,
                d.params(), clock.now(), null, null, d.sizeMultiplier());
        store.insertDeployment(paper);
        audit.record(AuditEvent.of(AuditEventType.STRATEGY_DEPLOYED, actorOf(by)).withActorId(by).withStrategyId(d.strategyId())
                .withPayload(Map.of("deploymentId", paper.id().toString(), "versionId", d.versionId().toString(), "mode", "PAPER",
                        "instruments", d.instrumentIds().stream().map(UUID::toString).toList(), "autonomyLevel", 0, "movedFrom", d.id().toString(),
                        "reason", reason)));
        events.publishEvent(new DeploymentChanged(EventMeta.create(clock), paper.id(), d.versionId(), true));
        return paper;
    }

    public Optional<StrategyDeployment> deployment(UUID id) {
        return store.findDeployment(id);
    }

    public List<StrategyDeployment> deployments(UUID versionId, ExecutionMode mode, Boolean enabled) {
        return store.findDeployments(versionId, mode, enabled);
    }

    // --- universe resolution ---

    /** Resolves the definition's universe to concrete instruments in order, without duplicates. Unknown entries are skipped. */
    public List<Instrument> resolveUniverse(StrategyDefinition definition) {
        LinkedHashSet<Instrument> out = new LinkedHashSet<>();
        for (UniverseEntry target : universeTargets(definition)) {
            resolveTarget(target).ifPresent(out::add);
        }
        return new ArrayList<>(out);
    }

    /**
     * The universe with aliases expanded: every entry is a SYMBOL, NEAREST_FUTURE or INDEX target. Consumers that
     * substitute a continuous series for "nearest future" (the backtester) start from this list.
     */
    public List<UniverseEntry> universeTargets(StrategyDefinition definition) {
        List<UniverseEntry> out = new ArrayList<>();
        for (UniverseEntry entry : definition.universe()) {
            if (entry.kind() != UniverseKind.ALIAS) {
                out.add(entry);
                continue;
            }
            String target = properties.aliases().get(entry.value());
            if (target == null) {
                continue;
            }
            String t = target.trim();
            if (t.startsWith("nearest_future:")) {
                out.add(new UniverseEntry(UniverseKind.NEAREST_FUTURE, t.substring("nearest_future:".length()).trim().toUpperCase()));
            } else if (t.startsWith("index:")) {
                out.add(new UniverseEntry(UniverseKind.INDEX, t.substring("index:".length()).trim().toUpperCase()));
            } else {
                out.add(new UniverseEntry(UniverseKind.SYMBOL, t.toUpperCase()));
            }
        }
        return out;
    }

    private Optional<Instrument> resolveTarget(UniverseEntry target) {
        return switch (target.kind()) {
            case SYMBOL -> instruments.resolve(target.value());
            case NEAREST_FUTURE -> instruments.nearestFuture(target.value());
            case INDEX -> instruments.resolve("INDEX:" + target.value());
            case ALIAS -> Optional.empty();
        };
    }

    private List<Instrument> resolveSymbols(List<String> symbols) {
        List<Instrument> out = new ArrayList<>();
        for (String symbol : symbols) {
            out.add(instruments.resolve(symbol).orElseThrow(() -> new IllegalArgumentException("Unknown instrument " + symbol)));
        }
        return out;
    }

    private Strategy requireStrategy(UUID id) {
        return store.findStrategy(id).orElseThrow(() -> new StrategyException.NotFound("Strategy " + id + " not found"));
    }

    private static ActorType actorOf(String by) {
        return "system".equals(by) ? ActorType.SYSTEM : ActorType.USER;
    }
}
