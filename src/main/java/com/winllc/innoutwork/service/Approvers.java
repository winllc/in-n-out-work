package com.winllc.innoutwork.service;

import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.security.AccessPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Who decides a person's status requests and records statuses for them: the people their absence alerts go to,
 * that is their manager in the directory and their delegates (the alternate managers an administrator gave them),
 * and any administrator. Nobody decides their own: a manager's or an administrator's own request goes to their own
 * manager, delegates or another administrator.
 */
@Service
public class Approvers {

    private final UserService userService;
    private final UserRecordRepository userRecordRepository;
    private final AccessPolicy accessPolicy;

    public Approvers(UserService userService, UserRecordRepository userRecordRepository, AccessPolicy accessPolicy) {
        this.userService = userService;
        this.userRecordRepository = userRecordRepository;
        this.accessPolicy = accessPolicy;
    }

    /** The people who decide {@code personDn}'s requests besides administrators: manager first, then delegates. */
    public List<String> of(String personDn) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> approvers = new ArrayList<>();
        if (validDn(personDn)) {
            LdapUser manager = userService.getUserManager(new LdapDn(personDn));
            if (manager != null && manager.getDn() != null && seen.add(key(manager.getDn()))) {
                approvers.add(manager.getDn());
            }
        }
        Optional<UserRecord> record = userRecordRepository.findByDnIgnoreCase(personDn);
        record.ifPresent(r -> r.getAltManagerList().stream()
                .filter(dn -> dn != null && !dn.isBlank())
                .filter(dn -> seen.add(key(dn)))
                .forEach(approvers::add));
        approvers.removeIf(dn -> sameDn(dn, personDn));
        return approvers;
    }

    /** Whether {@code viewer} may approve, reject or record statuses for {@code personDn}. */
    public boolean mayDecide(Authentication viewer, String personDn) {
        if (viewer == null || personDn == null || sameDn(viewer.getName(), personDn)) {
            return false;
        }
        if (accessPolicy.isAdmin(viewer)) {
            return true;
        }
        return of(personDn).stream().anyMatch(dn -> sameDn(dn, viewer.getName()));
    }

    /**
     * The people {@code approverDn} decides for (not counting the administrator's everyone), as lower-cased
     * normalised DNs: their direct reports in the directory and the people they are a delegate of.
     */
    public Set<String> peopleDecidedBy(String approverDn) {
        Set<String> people = new LinkedHashSet<>();
        if (!validDn(approverDn)) {
            return people;
        }
        userService.findDirectReports(new LdapDn(approverDn)).stream()
                .map(LdapUser::getDn)
                .filter(dn -> dn != null)
                .forEach(dn -> people.add(key(dn)));
        for (UserRecord record : userRecordRepository.findByAlternateManagersLike(mentioning(approverDn))) {
            if (record.getAltManagerList().stream().anyMatch(m -> sameDn(m, approverDn))) {
                people.add(key(record.getDn()));
            }
        }
        people.remove(key(approverDn));
        return people;
    }

    public static boolean sameDn(String a, String b) {
        return a != null && b != null && key(a).equals(key(b));
    }

    /** A DN as compared everywhere: normalised and lower-cased. */
    public static String key(String dn) {
        return LdapDn.normalize(dn).toLowerCase(Locale.ROOT);
    }

    private static boolean validDn(String dn) {
        if (dn == null || dn.isBlank()) {
            return false;
        }
        try {
            new LdapName(dn);
            return true;
        } catch (InvalidNameException e) {
            return false;
        }
    }

    /** A LIKE pattern matching text that contains {@code dn}, lower-cased and escaped with '!'. */
    private static String mentioning(String dn) {
        String escaped = LdapDn.normalize(dn).toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }
}
