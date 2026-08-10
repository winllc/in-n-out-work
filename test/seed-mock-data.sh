#!/usr/bin/env bash
#
# seed-mock-data.sh
#
# Populates the local test stack (see test/docker-compose.yml) with mock data:
#   * LDAP  (openldap container)  -> mock users under ou=Users + two groups
#   * Postgres (postgres container) -> check_in_out_records that are
#       - historical (previous days)
#       - current    (today: checked in / out / away / none)
#       - future     (tomorrow)
#
# It talks to the running containers with `docker exec`, so you don't need
# psql / ldap-utils installed on the host. Bring the stack up first:
#
#     cd test && docker compose up -d
#     ./seed-mock-data.sh
#
# The script is re-runnable: it removes previously seeded rows (session_id like
# 'mock-%') and re-adds LDAP entries with `-c` (continue on "already exists").
#
# Everything below can be overridden via environment variables.
set -euo pipefail

# ----------------------------- configuration --------------------------------
PG_CONTAINER="${PG_CONTAINER:-postgres}"
PG_USER="${PG_USER:-appuser}"
PG_DB="${PG_DB:-appdb}"

LDAP_CONTAINER="${LDAP_CONTAINER:-openldap}"
LDAP_BASE_DN="${LDAP_BASE_DN:-dc=winllc,dc=com}"
LDAP_ADMIN_DN="${LDAP_ADMIN_DN:-cn=admin,${LDAP_BASE_DN}}"
LDAP_ADMIN_PW="${LDAP_ADMIN_PW:-adminpassword}"

USERS_OU="ou=Users,${LDAP_BASE_DN}"
GROUPS_OU="ou=Groups,${LDAP_BASE_DN}"

# ----------------------------- helpers --------------------------------------
psql_exec() { docker exec -i "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -U "$PG_USER" -d "$PG_DB" "$@"; }
ldap_add()  { docker exec -i "$LDAP_CONTAINER" ldapadd -c -x -D "$LDAP_ADMIN_DN" -w "$LDAP_ADMIN_PW"; }

require_container() {
  local name="$1"
  if ! docker ps --format '{{.Names}}' | grep -qx "$name"; then
    echo "ERROR: container '$name' is not running. Start the stack first:" >&2
    echo "         cd test && docker compose up -d" >&2
    exit 1
  fi
}

command -v docker >/dev/null 2>&1 || { echo "ERROR: docker is required but not found on PATH." >&2; exit 1; }
require_container "$PG_CONTAINER"
require_container "$LDAP_CONTAINER"

# ----------------------------- mock users -----------------------------------
# Each row: cn|sn|uid|departmentNumber|employeeType|o|l|branch|today_status
#   departmentNumber = duty sub-org, parsed into the org chart (e.g. RYS34B -> RYS/34/B)
#   today_status     = IN | OUT | AWAY | NONE  (controls today's check-in records)
USERS=(
  "Alice Adams|Adams|alice|RYS34B|FT|WinLLC|New York|North|IN"
  "Bob Barker|Barker|bob|RYS34B|PT|WinLLC|New York|North|OUT"
  "Heidi Hughes|Hughes|heidi|RYS34B|FT|WinLLC|Chicago|South|AWAY"
  "Carol Clark|Clark|carol|RYS34C|FT|WinLLC|New York|North|NONE"
  "Dave Davis|Davis|dave|RYS35A|CON|WinLLC|Los Angeles|West|IN"
  "Erin Evans|Evans|erin|ABC12X|FT|WinLLC|New York|East|OUT"
  "Grace Green|Green|grace|ABC12X|FT|WinLLC|Chicago|South|IN"
  "Frank Foster|Foster|frank|ABC12Y|PT|WinLLC|Los Angeles|West|AWAY"
)

# Alice is everyone's manager. manager lookup = user.title -> manager.street
MANAGER_ID="MGR-100"
MANAGER_CN="Alice Adams"

# ----------------------------- build LDIF -----------------------------------
build_ldif() {
  # Organizational units (harmless if they already exist; ldapadd -c continues)
  cat <<LDIF
dn: ${USERS_OU}
objectClass: organizationalUnit
ou: Users

dn: ${GROUPS_OU}
objectClass: organizationalUnit
ou: Groups

LDIF

  # Users
  for row in "${USERS[@]}"; do
    IFS='|' read -r cn sn uid dept etype org loc branch _status <<<"$row"
    # manager lookup = user.title -> manager.street. Alice is the manager, so she
    # carries 'street' (her id) and no 'title'; everyone else has a 'title' pointing at it.
    local title="$MANAGER_ID"
    local street=""
    if [[ "$cn" == "$MANAGER_CN" ]]; then
      title=""
      street="$MANAGER_ID"
    fi
    cat <<LDIF
dn: cn=${cn},${USERS_OU}
objectClass: inetOrgPerson
objectClass: extensibleObject
cn: ${cn}
sn: ${sn}
uid: ${uid}
displayName: ${cn}
givenName: ${cn%% *}
mail: ${uid}@winllc.com
telephoneNumber: 555-0${RANDOM:0:3}
employeeType: ${etype}
departmentNumber: ${dept}
o: ${org}
l: ${loc}
userPassword: password
LDIF
    # Emit optional attributes only when set (LDAP rejects empty attribute values).
    [[ -n "$title"  ]] && echo "title: ${title}"
    [[ -n "$street" ]] && echo "street: ${street}"
    echo   # blank line separates entries
  done

  # Two groups (groupOfUniqueNames) so group/membership features have data
  cat <<LDIF
dn: cn=Engineering,${GROUPS_OU}
objectClass: groupOfUniqueNames
cn: Engineering
owner: cn=${MANAGER_CN},${USERS_OU}
uniqueMember: cn=Alice Adams,${USERS_OU}
uniqueMember: cn=Bob Barker,${USERS_OU}
uniqueMember: cn=Heidi Hughes,${USERS_OU}
uniqueMember: cn=Carol Clark,${USERS_OU}

dn: cn=Sales,${GROUPS_OU}
objectClass: groupOfUniqueNames
cn: Sales
owner: cn=${MANAGER_CN},${USERS_OU}
uniqueMember: cn=Dave Davis,${USERS_OU}
uniqueMember: cn=Erin Evans,${USERS_OU}
uniqueMember: cn=Grace Green,${USERS_OU}
uniqueMember: cn=Frank Foster,${USERS_OU}
LDIF
}

echo "==> Loading mock users and groups into LDAP (${LDAP_CONTAINER})..."
build_ldif | ldap_add || true   # -c already continues; tolerate 'already exists'

# ----------------------------- build check-in/out SQL -----------------------
# Timestamps are computed in SQL with now()/date_trunc so they stay timezone
# correct regardless of host locale.
SQL_FILE="$(mktemp)"
trap 'rm -f "$SQL_FILE"' EXIT

SEQ=0
emit() {
  # emit <dn> <action> <ts_sql_expr> <employeeType> <org> <loc> <branch> <dept> <wuid>
  SEQ=$((SEQ + 1))
  printf "INSERT INTO check_in_out_records (dn, \"timestamp\", session_id, action, windows_user_id, organization, employee_type, location, branch, duty_sub_organization, forced) VALUES ('%s', %s, 'mock-%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', false);\n" \
    "$1" "$3" "$SEQ" "$2" "$9" "$5" "$4" "$6" "$7" "$8" >>"$SQL_FILE"
}

# Remove any rows from a previous run so the script is idempotent.
echo "DELETE FROM check_in_out_records WHERE session_id LIKE 'mock-%';" >>"$SQL_FILE"

for row in "${USERS[@]}"; do
  IFS='|' read -r cn sn uid dept etype org loc branch status <<<"$row"
  dn="cn=${cn},${USERS_OU}"

  # ---- historical: last 3 working-ish days, checked in 08:00, out 17:00 ----
  for d in 1 2 3; do
    ci="date_trunc('day', now()) - interval '${d} day' + interval '8 hours'"
    co="date_trunc('day', now()) - interval '${d} day' + interval '17 hours'"
    emit "$dn" "CHECK_IN"  "$ci" "$etype" "$org" "$loc" "$branch" "$dept" "$uid"
    emit "$dn" "CHECK_OUT" "$co" "$etype" "$org" "$loc" "$branch" "$dept" "$uid"
  done

  # ---- current: today, per configured status ----
  ci_today="date_trunc('day', now()) + interval '8 hours'"
  lock_today="date_trunc('day', now()) + interval '12 hours'"
  co_today="date_trunc('day', now()) + interval '16 hours'"
  case "$status" in
    IN)   # checked in, still present
      emit "$dn" "CHECK_IN" "$ci_today" "$etype" "$org" "$loc" "$branch" "$dept" "$uid"
      ;;
    OUT)  # checked in then out
      emit "$dn" "CHECK_IN"  "$ci_today" "$etype" "$org" "$loc" "$branch" "$dept" "$uid"
      emit "$dn" "CHECK_OUT" "$co_today" "$etype" "$org" "$loc" "$branch" "$dept" "$uid"
      ;;
    AWAY) # checked in then locked (away, still counted present)
      emit "$dn" "CHECK_IN" "$ci_today"   "$etype" "$org" "$loc" "$branch" "$dept" "$uid"
      emit "$dn" "LOCK"     "$lock_today" "$etype" "$org" "$loc" "$branch" "$dept" "$uid"
      ;;
    NONE) : ;;  # no record today
  esac

  # ---- future: tomorrow, scheduled in 09:00 / out 17:00 ----
  ci_fut="date_trunc('day', now()) + interval '1 day' + interval '9 hours'"
  co_fut="date_trunc('day', now()) + interval '1 day' + interval '17 hours'"
  emit "$dn" "CHECK_IN"  "$ci_fut" "$etype" "$org" "$loc" "$branch" "$dept" "$uid"
  emit "$dn" "CHECK_OUT" "$co_fut" "$etype" "$org" "$loc" "$branch" "$dept" "$uid"
done

echo "==> Inserting check-in/out records into Postgres (${PG_CONTAINER})..."
psql_exec <"$SQL_FILE" >/dev/null

# ----------------------------- summary --------------------------------------
echo "==> Done. Summary:"
psql_exec -c "SELECT duty_sub_organization AS org, employee_type AS type, action, count(*)
              FROM check_in_out_records WHERE session_id LIKE 'mock-%'
              GROUP BY 1,2,3 ORDER BY 1,2,3;"

echo
echo "LDAP users:   ${#USERS[@]} under ${USERS_OU}"
echo "Org values:   RYS34B, RYS34C, RYS35A, ABC12X, ABC12Y"
echo "Today status: IN=Alice,Dave,Grace  OUT=Bob,Erin  AWAY=Heidi,Frank  NONE=Carol"
echo
echo "Note: user_records rows are created lazily by the app when a user is looked up,"
echo "      so start the app (which also creates the DB schema via ddl-auto=update)"
echo "      at least once before running this if the tables don't exist yet."
