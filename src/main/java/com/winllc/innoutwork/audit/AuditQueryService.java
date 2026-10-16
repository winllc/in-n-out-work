package com.winllc.innoutwork.audit;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Reading the audit trail: the admin page's search, the exports, the pull API and integrity verification.
 */
@Service
public class AuditQueryService {

    /** The most an export returns. */
    public static final int MAX_EXPORT = 100_000;
    private static final int BATCH = 1000;

    private final AuditEventRepository events;

    public AuditQueryService(AuditEventRepository events) {
        this.events = events;
    }

    /** The admin page's filters; every one optional. */
    public record Filter(Instant from, Instant to, String category, String action, String outcome, String severity,
                         String actor, String target, String sourceIp, String text) {

        public static Filter none() {
            return new Filter(null, null, null, null, null, null, null, null, null, null);
        }

        Specification<AuditEventRecord> toSpecification() {
            return (root, query, cb) -> {
                List<Predicate> where = new ArrayList<>();
                if (from != null) {
                    where.add(cb.greaterThanOrEqualTo(root.get("time"), from));
                }
                if (to != null) {
                    where.add(cb.lessThan(root.get("time"), to));
                }
                if (present(category)) {
                    where.add(cb.equal(root.get("category"), category.trim()));
                }
                if (present(action)) {
                    where.add(cb.equal(root.get("action"), action.trim()));
                }
                if (present(outcome)) {
                    where.add(cb.equal(root.get("outcome"), outcome.trim()));
                }
                if (present(severity)) {
                    where.add(cb.equal(root.get("severity"), severity.trim()));
                }
                if (present(actor)) {
                    String like = like(actor);
                    where.add(cb.or(cb.like(cb.lower(root.get("actorId")), like, '\\'),
                            cb.like(cb.lower(root.get("actorName")), like, '\\')));
                }
                if (present(target)) {
                    String like = like(target);
                    where.add(cb.or(cb.like(cb.lower(root.get("targetId")), like, '\\'),
                            cb.like(cb.lower(root.get("targetName")), like, '\\'),
                            cb.like(cb.lower(root.get("targetType")), like, '\\')));
                }
                if (present(sourceIp)) {
                    where.add(cb.like(root.get("sourceIp"), sourceIp.trim().replace("\\", "\\\\")
                            .replace("%", "\\%").replace("_", "\\_") + "%", '\\'));
                }
                if (present(text)) {
                    where.add(cb.like(cb.lower(root.get("line")), like(text), '\\'));
                }
                return cb.and(where.toArray(new Predicate[0]));
            };
        }

        /** The filter as a short description, for the export's audit record. */
        public String describe() {
            List<String> parts = new ArrayList<>();
            add(parts, "from", from == null ? null : from.toString());
            add(parts, "to", to == null ? null : to.toString());
            add(parts, "category", category);
            add(parts, "action", action);
            add(parts, "outcome", outcome);
            add(parts, "severity", severity);
            add(parts, "actor", actor);
            add(parts, "target", target);
            add(parts, "sourceIp", sourceIp);
            add(parts, "text", text);
            return parts.isEmpty() ? "all" : String.join(" ", parts);
        }

        private static void add(List<String> parts, String name, String value) {
            if (present(value)) {
                parts.add(name + "=" + value.trim());
            }
        }

        private static boolean present(String s) {
            return s != null && !s.isBlank();
        }

        private static String like(String s) {
            return "%" + s.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        }
    }

    /** One page, newest first. */
    @Transactional(readOnly = true)
    public Page<AuditEventRecord> search(Filter filter, int page, int size) {
        return events.findAll(filter.toSpecification(),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), Sort.by(Sort.Direction.DESC, "seq")));
    }

    @Transactional(readOnly = true)
    public Optional<AuditEventRecord> find(long seq) {
        return events.findById(seq);
    }

    /**
     * Hands every event matching the filter, newest first, to {@code sink}, up to {@link #MAX_EXPORT}, starting
     * at {@code upToSeq} (so the export's own audit record, written just before, is not in it).
     */
    @Transactional(readOnly = true)
    public int export(Filter filter, long upToSeq, Consumer<AuditEventRecord> sink) {
        int count = 0;
        Specification<AuditEventRecord> spec = filter.toSpecification();
        Long below = upToSeq + 1;
        while (count < MAX_EXPORT) {
            Long bound = below;
            Specification<AuditEventRecord> page = bound == null ? spec
                    : spec.and((root, query, cb) -> cb.lessThan(root.get("seq"), bound));
            List<AuditEventRecord> batch = events.findAll(page,
                    PageRequest.of(0, Math.min(BATCH, MAX_EXPORT - count), Sort.by(Sort.Direction.DESC, "seq")))
                    .getContent();
            if (batch.isEmpty()) {
                break;
            }
            for (AuditEventRecord e : batch) {
                sink.accept(e);
                count++;
            }
            below = batch.getLast().getSeq();
        }
        return count;
    }

    /** Events after {@code seq}, oldest first: the pull API and the forwarders. */
    @Transactional(readOnly = true)
    public List<AuditEventRecord> after(long seq, int limit) {
        return events.findBySeqGreaterThanOrderBySeqAsc(seq, Pageable.ofSize(limit));
    }

    /** Every action recorded so far, plus the ones this build knows, sorted: the action filter's list. */
    @Transactional(readOnly = true)
    public List<String> knownActions() {
        TreeSet<String> actions = new TreeSet<>(AuditActions.ALL);
        actions.addAll(events.findDistinctActions());
        return List.copyOf(actions);
    }

    /** The outcome of a verification. */
    public record Verification(boolean intact, long checked, Long anchorSeq, boolean anchorIsGenesis,
                               Long brokenSeq, String problem, Long lastSeq) {

        public String summary() {
            if (checked == 0) {
                return "No events to verify.";
            }
            String anchor = anchorIsGenesis
                    ? "from seq 1"
                    : "from seq " + anchorSeq + " (the oldest retained event; earlier ones were pruned, so its "
                    + "prevHash is accepted as given)";
            if (intact) {
                return "Intact: " + checked + " event(s) verified " + anchor + " to seq " + lastSeq + ".";
            }
            return "Broken at seq " + brokenSeq + ": " + problem + " (checked " + anchor + ").";
        }
    }

    /**
     * Walks the retained events in seq order and recomputes each hash from the stored line. Reports the first
     * broken seq, or intact, and the anchor: the oldest retained event, whose prevHash is accepted as given (it is
     * seq 1, chained to 64 zeros, until retention pruning removes the start of the chain).
     */
    @Transactional(readOnly = true)
    public Verification verify() {
        long checked = 0;
        Long anchor = null;
        String expectedPrev = null;
        long expectedSeq = -1;
        long cursor = 0;
        while (true) {
            List<AuditEventRecord> batch = events.findBySeqGreaterThanOrderBySeqAsc(cursor, Pageable.ofSize(BATCH));
            if (batch.isEmpty()) {
                break;
            }
            for (AuditEventRecord e : batch) {
                long seq = e.getSeq();
                String line = e.getLine();
                String body = AuditJson.bodyOf(line);
                String lineHash = AuditJson.hashOf(line);
                String prev = body == null ? null : AuditJson.prevHashOf(body);
                if (anchor == null) {
                    anchor = seq;
                    expectedSeq = seq;
                    if (seq == 1 && !AuditJson.ZERO_HASH.equals(prev)) {
                        return new Verification(false, checked, anchor, true, seq,
                                "the first event's prevHash is not 64 zeros", null);
                    }
                }
                if (body == null || prev == null) {
                    return broken(checked, anchor, seq, "the stored line is not a hashed ens-audit/1 event");
                }
                if (seq != expectedSeq) {
                    return broken(checked, anchor, expectedSeq, "seq " + expectedSeq + " is missing");
                }
                if (!body.contains("\"seq\":" + seq + ",")) {
                    return broken(checked, anchor, seq, "the line's seq does not match its position");
                }
                if (expectedPrev != null && !expectedPrev.equals(prev)) {
                    return broken(checked, anchor, seq, "prevHash does not match the previous event's hash");
                }
                String computed = AuditJson.hash(prev, body);
                if (!computed.equals(lineHash) || !computed.equals(e.getHash())) {
                    return broken(checked, anchor, seq, "the hash does not match the event's content");
                }
                checked++;
                expectedPrev = computed;
                expectedSeq = seq + 1;
                cursor = seq;
            }
        }
        return new Verification(true, checked, anchor, anchor == null || anchor == 1, null, null,
                checked == 0 ? null : cursor);
    }

    private static Verification broken(long checked, Long anchor, long seq, String problem) {
        return new Verification(false, checked, anchor, anchor != null && anchor == 1, seq, problem, null);
    }
}
