package com.winllc.innoutwork.service;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Delegates (alternate managers): the people who, besides a person's directory manager, receive their absence
 * alerts, may answer them and decide their status requests. Administrators set them; a person can no longer pick
 * their own (RR-07). Delegates people chose for themselves before are kept, marked for review
 * ({@link UserRecord#isAlternateManagersReviewPending()}), so nobody silently stops receiving alerts on upgrade.
 * Every change is audited as {@code person.updated} ({@code via} {@code alternate-managers} or
 * {@code alternate-managers-reviewed}).
 */
@Service
public class DelegateService {

    private static final Logger log = LoggerFactory.getLogger(DelegateService.class);

    /** user_records.alternate_managers, varchar(2000). */
    static final int MAX_LENGTH = 2000;

    private final UserRecordRepository userRecordRepository;
    private final UserService userService;
    private final LdapService ldapService;

    private AuditLog audit = AuditLog.NOOP;

    public DelegateService(UserRecordRepository userRecordRepository, UserService userService, LdapService ldapService) {
        this.userRecordRepository = userRecordRepository;
        this.userService = userService;
        this.ldapService = ldapService;
    }

    @Autowired(required = false)
    public void setAuditLog(AuditLog audit) {
        this.audit = audit;
    }

    /**
     * Replaces {@code personDn}'s delegates, as an administrator (the caller checks that). Each must be someone else
     * in the directory; the list is stored normalised, without repeats. Clears any pending review.
     *
     * @throws InvalidRequestException (400) for a person or delegate not in the directory, the person themselves,
     *                                 or more than fits
     */
    public UserRecord setDelegates(String personDn, List<String> delegateDns) {
        LdapDn person = dn(personDn, "The person");
        Map<String, String> delegates = new LinkedHashMap<>();
        for (String raw : delegateDns == null ? List.<String>of() : delegateDns) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            LdapDn delegate = dn(raw.strip(), "A delegate");
            if (Approvers.sameDn(delegate.dn(), person.dn())) {
                throw InvalidRequestException.badRequest("invalid_delegate",
                        "Nobody is their own delegate: " + delegate.getCn());
            }
            if (ldapService.lookupUser(delegate).isEmpty()) {
                throw InvalidRequestException.badRequest("invalid_delegate",
                        "Not in the directory: " + delegate.dn());
            }
            delegates.putIfAbsent(Approvers.key(delegate.dn()), delegate.dn());
        }
        String joined = String.join(";", delegates.values());
        if (joined.length() > MAX_LENGTH) {
            throw InvalidRequestException.badRequest("invalid_delegate", "Too many delegates for one person");
        }

        UserRecord record = userService.getUserByDn(person).orElseThrow(() -> InvalidRequestException.badRequest(
                "invalid_person", "Not a tracked person in the directory: " + person.dn()));
        String old = record.getAlternateManagers();
        boolean wasPending = record.isAlternateManagersReviewPending();
        return audit.inTransaction(() -> {
            record.setAlternateManagers(joined.isEmpty() ? null : joined);
            record.setAlternateManagersReviewPending(false);
            UserRecord saved = userRecordRepository.save(record);
            audit.record(AuditEntry.of(AuditEntry.Category.DATA, "person.updated")
                    .target("user", saved.getDn(), LdapDn.cnOf(saved.getDn()))
                    .detail("via", "alternate-managers")
                    .detail("reviewed", wasPending)
                    .change("alternateManagers", old, saved.getAlternateManagers()));
            log.info("Delegates of {} set to {}", saved.getDn(), saved.getAlternateManagers());
            return saved;
        });
    }

    /** An administrator keeps the delegates a person chose before: they stay, no longer marked for review. */
    public UserRecord keep(String personDn) {
        UserRecord record = userRecordRepository.findByDnIgnoreCase(personDn).orElseThrow(() ->
                InvalidRequestException.badRequest("invalid_person", "No record for " + personDn));
        if (!record.isAlternateManagersReviewPending()) {
            return record;
        }
        return audit.inTransaction(() -> {
            record.setAlternateManagersReviewPending(false);
            UserRecord saved = userRecordRepository.save(record);
            audit.record(AuditEntry.of(AuditEntry.Category.DATA, "person.updated")
                    .target("user", saved.getDn(), LdapDn.cnOf(saved.getDn()))
                    .detail("via", "alternate-managers-reviewed")
                    .detail("alternateManagers", saved.getAlternateManagers()));
            return saved;
        });
    }

    /** People with delegates, those awaiting review first when {@code pendingOnly} is false. */
    public List<UserRecord> withDelegates(boolean pendingOnly) {
        List<UserRecord> all = new ArrayList<>(userRecordRepository.findWithAlternateManagers());
        if (pendingOnly) {
            all.removeIf(r -> !r.isAlternateManagersReviewPending());
        } else {
            all.sort((a, b) -> Boolean.compare(b.isAlternateManagersReviewPending(), a.isAlternateManagersReviewPending()));
        }
        return all;
    }

    public long pendingReviewCount() {
        return userRecordRepository.countByAlternateManagersReviewPendingTrue();
    }

    /** Says at startup how many self-chosen delegates still wait for review, while any do. */
    @EventListener(ApplicationReadyEvent.class)
    public void reportPendingReviews() {
        try {
            long pending = pendingReviewCount();
            if (pending > 0) {
                log.info("{} {} delegates (alternate managers) chosen before administrators managed them; they still"
                                + " receive alerts and decide requests. Review them under Administration > Delegates"
                                + " (/app/admin/delegates)", pending, pending == 1 ? "person has" : "people have");
            }
        } catch (RuntimeException e) {
            log.warn("Could not count delegates awaiting review: {}", e.getMessage());
        }
    }

    private static LdapDn dn(String value, String what) {
        try {
            return new LdapDn(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw InvalidRequestException.badRequest("invalid_delegate", what + " is not a DN: " + value);
        }
    }
}
