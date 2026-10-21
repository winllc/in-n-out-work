package com.winllc.innoutwork.security;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.model.GroupRecord;
import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.GroupRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.GroupService;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.PermissionService;
import com.winllc.innoutwork.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Who may see whom: the one place that decides whose person-level data a signed-in user may see, and which groups
 * they may open and manage. Every controller that returns or changes such data asks here.
 *
 * <p>"Details" are a person's attendance: arrival, departure and status-change times, notes, average login time,
 * their check-in history, calendar statuses and the alternate managers they named. A viewer sees the details of:
 * <ul>
 *   <li><b>themselves</b>, always;</li>
 *   <li><b>everyone</b>, with the ADMIN authority;</li>
 *   <li><b>the members of the groups they manage</b>: the group's {@code owner} in the directory, or an alternate
 *       manager recorded for it (GroupRecord). The Group Managers card says exactly this: managers "receive absent
 *       notifications and are able to see additional details of users in their group". A group's sub-groups are
 *       separate groups with their own managers;</li>
 *   <li><b>their direct reports</b>: people whose manager id in the directory is the viewer's. These are the people
 *       My Reports lists and whose absence alerts go to the viewer. Indirect reports are not included: nothing else
 *       in ReadyRoom follows the reporting line past one level, and the directory is only read one level at a
 *       time;</li>
 *   <li><b>people they are a delegate (alternate manager) of</b>, who also send them their absence alerts. An
 *       administrator sets delegates (DelegateService); people chose their own before, and those choices are kept
 *       until an administrator reviews them.</li>
 * </ul>
 * The last three come from the directory and from the people themselves, not from the MANAGER role: someone with
 * only USER who manages a group or has reports already got their alerts and their My Reports page. The MANAGER role
 * opens the manager pages (metrics, group reports, settings) but no longer everybody's data and every group.
 *
 * <p>Anyone signed in may still find anyone; what they see of a coworker is {@link #coworkerView}, as
 * {@code application.privacy.coworker-visibility} says: their presence ({@link CoworkerVisibility#FULL}, the default),
 * in or out only ({@link CoworkerVisibility#STATUS}), or nothing of their attendance ({@link CoworkerVisibility#NONE}).
 *
 * <p>Groups: a viewer may open a group they are a member of or manage, ADMIN any group; only ADMIN or a current
 * manager of a group may manage it (change its managers, run its report, grant access to it).
 */
@Service
public class AccessPolicy {

    private static final Logger log = LoggerFactory.getLogger(AccessPolicy.class);

    private final PermissionService permissionService;
    private final LdapService ldapService;
    private final GroupService groupService;
    private final UserService userService;
    private final UserRecordRepository userRecordRepository;
    private final GroupRecordRepository groupRecordRepository;
    private final CoworkerVisibility coworkerVisibility;

    public AccessPolicy(PermissionService permissionService, LdapService ldapService, GroupService groupService,
                        UserService userService, UserRecordRepository userRecordRepository,
                        GroupRecordRepository groupRecordRepository, ApplicationProperties properties) {
        this.permissionService = permissionService;
        this.ldapService = ldapService;
        this.groupService = groupService;
        this.userService = userService;
        this.userRecordRepository = userRecordRepository;
        this.groupRecordRepository = groupRecordRepository;
        CoworkerVisibility configured = properties == null ? null : properties.getPrivacy().getCoworkerVisibility();
        this.coworkerVisibility = configured == null ? CoworkerVisibility.FULL : configured;
    }

    /** {@code application.privacy.coworker-visibility}. */
    public CoworkerVisibility coworkerVisibility() {
        return coworkerVisibility;
    }

    /**
     * Whether {@code viewer} sees how many people are present in each organization (the org chart): always, unless
     * coworker visibility is NONE, when only the ADMIN and MANAGER roles do (as on the metrics page, which they
     * alone open). A small organization's count would otherwise say who is in.
     */
    public boolean seesPresenceCounts(Authentication viewer) {
        if (coworkerVisibility != CoworkerVisibility.NONE) {
            return true;
        }
        return signedIn(viewer) && viewer.getAuthorities().stream().anyMatch(a ->
                UserRoleEnum.ADMIN.name().equalsIgnoreCase(a.getAuthority())
                        || UserRoleEnum.MANAGER.name().equalsIgnoreCase(a.getAuthority()));
    }

    // --- people -----------------------------------------------------------------------------------------------

    public boolean isAdmin(Authentication viewer) {
        return signedIn(viewer) && viewer.getAuthorities().stream()
                .anyMatch(a -> UserRoleEnum.ADMIN.name().equalsIgnoreCase(a.getAuthority()));
    }

    public boolean isSelf(Authentication viewer, String dn) {
        return signedIn(viewer) && sameDn(viewer.getName(), dn);
    }

    /** Whether {@code viewer} may see {@code dn}'s details; see the class comment. */
    public boolean canViewDetails(Authentication viewer, String dn) {
        boolean allowed = scope(viewer).canViewDetails(dn);
        log.debug("Details of {} {} for {}", dn, allowed ? "granted" : "denied", viewer != null ? viewer.getName() : null);
        return allowed;
    }

    /**
     * What {@code viewer} may see of people, worked out once for a list of them (a search, a group's members). The
     * people they are responsible for are read on first use, so a viewer looking only at themselves costs nothing.
     */
    public Scope scope(Authentication viewer) {
        if (!signedIn(viewer)) {
            return Scope.of(dn -> false, coworkerVisibility);
        }
        if (isAdmin(viewer)) {
            return Scope.of(dn -> true, coworkerVisibility);
        }
        String me = viewer.getName();
        AtomicReference<Set<String>> people = new AtomicReference<>();
        return Scope.of(dn -> {
            if (dn == null) {
                return false;
            }
            if (sameDn(me, dn)) {
                return true;
            }
            return people.updateAndGet(p -> p != null ? p : responsibleFor(me)).contains(key(dn));
        }, coworkerVisibility);
    }

    /**
     * Everyone whose details {@code viewer} may see, for queries that select them in the database: empty for an
     * administrator (everyone), otherwise their own DN and the DNs of the people they are responsible for, as the
     * directory and their records spell them. Nobody for someone not signed in.
     */
    public Optional<Set<String>> detailDns(Authentication viewer) {
        if (!signedIn(viewer)) {
            return Optional.of(Set.of());
        }
        if (isAdmin(viewer)) {
            return Optional.empty();
        }
        Set<String> people = new LinkedHashSet<>();
        people.add(viewer.getName());
        people.addAll(responsiblePeople(viewer.getName()));
        return Optional.of(people);
    }

    /** The people {@code me} is responsible for, by lower-cased normalised DN: see the class comment. */
    private Set<String> responsibleFor(String me) {
        Set<String> people = new LinkedHashSet<>();
        responsiblePeople(me).forEach(dn -> people.add(key(dn)));
        log.debug("{} is responsible for {} people", me, people.size());
        return people;
    }

    /** The people {@code me} is responsible for, by DN as the directory and their records spell it. */
    private List<String> responsiblePeople(String me) {
        List<String> people = new ArrayList<>();

        for (String groupDn : managedGroupDns(me)) {
            people.addAll(ldapService.getGroupMembers(new LdapDn(groupDn)));
        }
        if (validDn(me)) {
            userService.findDirectReports(new LdapDn(me)).stream()
                    .map(LdapUser::getDn)
                    .forEach(people::add);
        }
        for (UserRecord record : userRecordRepository.findByAlternateManagersLike(mentioning(me))) {
            if (record.getAltManagerList().stream().anyMatch(m -> sameDn(m, me))) {
                people.add(record.getDn());
            }
        }
        people.removeIf(java.util.Objects::isNull);
        return people;
    }

    /**
     * {@code status} as anyone signed in may see it: who the person is and whether they are in, without when they
     * came and went, notes, their average login time or role. A status a manager recorded in answer to an absence
     * alert (unexcused, unscheduled leave and the like) is an attendance judgement, so it reads as out of office.
     */
    public static UserStatus presenceOnly(UserStatus status) {
        return coworkerView(status, CoworkerVisibility.FULL);
    }

    /**
     * {@code status} as a coworker sees it under {@code visibility}: who the person is (their directory details), and
     * <ul>
     *   <li>FULL: their presence, a manager-recorded status reading as out of office ({@link #presenceOnly});</li>
     *   <li>STATUS: "IN" when they checked in or unlocked today and are not checked out (a locked screen counts as
     *       in), "OUT" otherwise, with no calendar status;</li>
     *   <li>NONE: no status at all.</li>
     * </ul>
     * Never when they came and went, notes, their average login time or role.
     */
    public static UserStatus coworkerView(UserStatus status, CoworkerVisibility visibility) {
        String shown = switch (visibility == null ? CoworkerVisibility.FULL : visibility) {
            case FULL -> presence(status.getStatus());
            case STATUS -> inOrOut(status);
            case NONE -> null;
        };
        return UserStatus.builder()
                .dn(status.getDn())
                .status(shown)
                .memberOf(status.getMemberOf())
                .organization(status.getOrganization())
                .employeeType(status.getEmployeeType())
                .location(status.getLocation())
                .branch(status.getBranch())
                .email(status.getEmail())
                .phoneNumber(status.getPhoneNumber())
                .managerDn(status.getManagerDn())
                .build();
    }

    /** In or out today, from the day's check-in records, whatever calendar status the day carries. */
    private static String inOrOut(UserStatus status) {
        String presence = status.getPresence() != null ? status.getPresence() : status.getStatus();
        return "IN".equals(presence) || "AWAY".equals(presence) ? "IN" : "OUT";
    }

    private static String presence(String status) {
        return Stream.of(UserStatusEnum.values())
                .filter(s -> s.name().equals(status) && !s.isSelectable())
                .findFirst()
                .map(s -> UserStatusEnum.OUT_OF_OFFICE.name())
                .orElse(status);
    }

    // --- groups -----------------------------------------------------------------------------------------------

    /** A group's page and its members' presence: its members, its managers and ADMIN. */
    public boolean canViewGroup(Authentication viewer, String groupDn) {
        if (!signedIn(viewer) || !validDn(groupDn)) {
            return false;
        }
        boolean allowed = isAdmin(viewer)
                || memberGroupDns(viewer.getName()).stream().anyMatch(g -> sameDn(g, groupDn))
                || managesGroup(viewer.getName(), groupDn);
        log.debug("Group {} {} for {}", groupDn, allowed ? "granted" : "denied", viewer.getName());
        return allowed;
    }

    /** Changing a group's managers, running its report, granting access to it: ADMIN or a current manager. */
    public boolean canManageGroup(Authentication viewer, String groupDn) {
        if (!signedIn(viewer) || !validDn(groupDn)) {
            return false;
        }
        boolean allowed = isAdmin(viewer) || managesGroup(viewer.getName(), groupDn);
        log.debug("Managing group {} {} for {}", groupDn, allowed ? "granted" : "denied", viewer.getName());
        return allowed;
    }

    /** The groups a non-administrator sees on the Groups page: those they are a member of and those they manage. */
    public List<LdapDn> viewableGroups(Authentication viewer) {
        if (!signedIn(viewer)) {
            return List.of();
        }
        Set<LdapDn> groups = new LinkedHashSet<>();
        memberGroupDns(viewer.getName()).forEach(g -> groups.add(new LdapDn(g)));
        managedGroupDns(viewer.getName()).forEach(g -> groups.add(new LdapDn(g)));
        return new ArrayList<>(groups);
    }

    private boolean managesGroup(String me, String groupDn) {
        return groupService.getManagersForGroup(groupDn).stream().anyMatch(m -> sameDn(m, me));
    }

    private List<String> memberGroupDns(String me) {
        if (!validDn(me)) {
            return List.of();
        }
        return permissionService.getUserGroupPermissionsAndMemberOfGroups(new LdapDn(me)).stream()
                .map(LdapDn::dn)
                .toList();
    }

    /** The groups {@code me} owns in the directory or is an alternate manager of. */
    private List<String> managedGroupDns(String me) {
        Set<String> groups = new LinkedHashSet<>();
        ldapService.findGroupsOwnedBy(me).stream()
                .map(LdapGroup::getDn)
                .filter(AccessPolicy::validDn)
                .forEach(groups::add);
        for (GroupRecord record : groupRecordRepository.findByAlternateManagersLike(mentioning(me))) {
            if (validDn(record.getGroupDn()) && record.getAltManagerList().stream().anyMatch(m -> sameDn(m, me))) {
                groups.add(record.getGroupDn());
            }
        }
        return new ArrayList<>(groups);
    }

    // --- notifications ----------------------------------------------------------------------------------------

    /** An absence alert: the manager it was sent to, or ADMIN. */
    public boolean canOpenNotification(Authentication viewer, NotificationRecord notification) {
        return notification != null && (isAdmin(viewer) || isSelf(viewer, notification.getForUserDn()));
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    /** What one viewer may see, for many people. */
    public static final class Scope {

        private final Predicate<String> details;
        private final CoworkerVisibility visibility;

        private Scope(Predicate<String> details, CoworkerVisibility visibility) {
            this.details = details;
            this.visibility = visibility == null ? CoworkerVisibility.FULL : visibility;
        }

        /** Coworkers seen as {@link CoworkerVisibility#FULL}. */
        public static Scope of(Predicate<String> canViewDetails) {
            return new Scope(canViewDetails, CoworkerVisibility.FULL);
        }

        public static Scope of(Predicate<String> canViewDetails, CoworkerVisibility visibility) {
            return new Scope(canViewDetails, visibility);
        }

        public boolean canViewDetails(String dn) {
            return details.test(dn);
        }

        /** {@code status} in full when the viewer may see the person's details, otherwise as a coworker sees it. */
        public UserStatus show(UserStatus status) {
            return canViewDetails(status.getDn()) ? status : coworkerView(status, visibility);
        }

        public List<UserStatus> show(Collection<UserStatus> statuses) {
            return statuses.stream().map(this::show).toList();
        }
    }

    private static boolean signedIn(Authentication viewer) {
        return viewer != null && viewer.isAuthenticated() && !(viewer instanceof AnonymousAuthenticationToken)
                && viewer.getName() != null;
    }

    static boolean sameDn(String a, String b) {
        return a != null && b != null && key(a).equals(key(b));
    }

    private static String key(String dn) {
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
