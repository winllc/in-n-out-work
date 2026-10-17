package com.winllc.innoutwork.security;

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
 *   <li><b>people who named them as an alternate manager</b> on their own profile, who also send them their absence
 *       alerts.</li>
 * </ul>
 * The last three come from the directory and from the people themselves, not from the MANAGER role: someone with
 * only USER who manages a group or has reports already got their alerts and their My Reports page. The MANAGER role
 * opens the manager pages (metrics, group reports, settings) but no longer everybody's data and every group.
 *
 * <p>Anyone signed in may still find anyone and see their presence: {@link #presenceOnly} is what a coworker sees.
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

    public AccessPolicy(PermissionService permissionService, LdapService ldapService, GroupService groupService,
                        UserService userService, UserRecordRepository userRecordRepository,
                        GroupRecordRepository groupRecordRepository) {
        this.permissionService = permissionService;
        this.ldapService = ldapService;
        this.groupService = groupService;
        this.userService = userService;
        this.userRecordRepository = userRecordRepository;
        this.groupRecordRepository = groupRecordRepository;
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
            return Scope.of(dn -> false);
        }
        if (isAdmin(viewer)) {
            return Scope.of(dn -> true);
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
        });
    }

    /** The people {@code me} is responsible for, by lower-cased normalised DN: see the class comment. */
    private Set<String> responsibleFor(String me) {
        Set<String> people = new LinkedHashSet<>();

        for (String groupDn : managedGroupDns(me)) {
            ldapService.getGroupMembers(new LdapDn(groupDn)).forEach(member -> people.add(key(member)));
        }
        if (validDn(me)) {
            userService.findDirectReports(new LdapDn(me)).stream()
                    .map(LdapUser::getDn)
                    .forEach(report -> people.add(key(report)));
        }
        for (UserRecord record : userRecordRepository.findByAlternateManagersLike(mentioning(me))) {
            if (record.getAltManagerList().stream().anyMatch(m -> sameDn(m, me))) {
                people.add(key(record.getDn()));
            }
        }

        log.debug("{} is responsible for {} people", me, people.size());
        return people;
    }

    /**
     * {@code status} as anyone signed in may see it: who the person is and whether they are in, without when they
     * came and went, notes, their average login time or role. A status a manager recorded in answer to an absence
     * alert (unexcused, unscheduled leave and the like) is an attendance judgement, so it reads as out of office.
     */
    public static UserStatus presenceOnly(UserStatus status) {
        return UserStatus.builder()
                .dn(status.getDn())
                .status(presence(status.getStatus()))
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

        private Scope(Predicate<String> details) {
            this.details = details;
        }

        public static Scope of(Predicate<String> canViewDetails) {
            return new Scope(canViewDetails);
        }

        public boolean canViewDetails(String dn) {
            return details.test(dn);
        }

        /** {@code status} in full when the viewer may see the person's details, otherwise their presence only. */
        public UserStatus show(UserStatus status) {
            return canViewDetails(status.getDn()) ? status : presenceOnly(status);
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
