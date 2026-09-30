#!/usr/bin/env bash
#
# seed-mock-data.sh
#
# Populates the local test stack (see test/docker-compose.yml) with mock data:
#   * LDAP  (openldap container)  -> mock users under ou=Users + two groups,
#                                  including the "Demo Admin" account demo mode presents
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

# How the clients reach the services:
#   exec     (default) run psql/ldapadd inside the running containers with docker exec - what a
#            developer on the host has, and what this script has always done.
#   network  run them directly against PG_HOST/LDAP_HOST. The demo stack's seeder container has
#            both clients but no docker socket, so that is the mode it uses.
SEED_TRANSPORT="${SEED_TRANSPORT:-exec}"
PG_HOST="${PG_HOST:-postgres}"
PG_PASSWORD="${PG_PASSWORD:-secret123}"
LDAP_HOST="${LDAP_HOST:-openldap}"
LDAP_PORT="${LDAP_PORT:-389}"
# Network mode waits for things to come up rather than failing on a race.
WAIT_TIMEOUT_SECONDS="${WAIT_TIMEOUT_SECONDS:-300}"
# Which half to run: all (default), ldap, or db. The demo stack runs them as two services,
# because the image that already has ldapadd and the image that already has psql are different
# ones - splitting is what lets it use images it already needs instead of building a seeder with
# both clients in it.
#
# Safe to split because the halves do not share anything: the user list is fixed, and the records
# the db half writes are derived from that list alone. Group membership is NOT reproducible across
# the two - RANDOM is seeded, but bash 5.0 and 5.2 produce different sequences from the same seed,
# and the two images do not carry the same bash. Only the ldap half creates the groups, so that
# does not matter, but it is why the summary below only reports them in the half that made them.
SEED_ONLY="${SEED_ONLY:-all}"
case "$SEED_ONLY" in
  all|ldap|db) ;;
  *) echo "ERROR: SEED_ONLY must be all, ldap or db (got '${SEED_ONLY}')." >&2; exit 1 ;;
esac
do_ldap() { [[ "$SEED_ONLY" == "all" || "$SEED_ONLY" == "ldap" ]]; }
do_db()   { [[ "$SEED_ONLY" == "all" || "$SEED_ONLY" == "db" ]]; }

# Declared up front and cleaned up by one trap: a second "trap ... EXIT" replaces the first
# instead of adding to it, so installing one per temp file left the earlier one leaking.
LDAP_LOG=""
SQL_FILE=""
trap 'rm -f "$LDAP_LOG" "$SQL_FILE"' EXIT
LDAP_BASE_DN="${LDAP_BASE_DN:-dc=winllc,dc=com}"
LDAP_ADMIN_DN="${LDAP_ADMIN_DN:-cn=admin,${LDAP_BASE_DN}}"
LDAP_ADMIN_PW="${LDAP_ADMIN_PW:-adminpassword}"

USERS_OU="ou=Users,${LDAP_BASE_DN}"
GROUPS_OU="ou=Groups,${LDAP_BASE_DN}"

# ----------------------------- helpers --------------------------------------
psql_exec() {
  if [[ "$SEED_TRANSPORT" == "network" ]]; then
    PGPASSWORD="$PG_PASSWORD" psql -v ON_ERROR_STOP=1 -h "$PG_HOST" -U "$PG_USER" -d "$PG_DB" "$@"
  else
    docker exec -i "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -U "$PG_USER" -d "$PG_DB" "$@"
  fi
}

ldap_add() {
  if [[ "$SEED_TRANSPORT" == "network" ]]; then
    ldapadd -c -x -H "ldap://${LDAP_HOST}:${LDAP_PORT}" -D "$LDAP_ADMIN_DN" -w "$LDAP_ADMIN_PW"
  else
    docker exec -i "$LDAP_CONTAINER" ldapadd -c -x -D "$LDAP_ADMIN_DN" -w "$LDAP_ADMIN_PW"
  fi
}

# Polls without ON_ERROR_STOP, so "not up yet" is not an error. Both tables, because the seed
# writes to both: the records, and the user_records row that grants the demo manager their role.
schema_ready() {
  PGPASSWORD="$PG_PASSWORD" psql -tAqX -h "$PG_HOST" -U "$PG_USER" -d "$PG_DB" \
    -c "SELECT to_regclass('public.check_in_out_records') IS NOT NULL
           AND to_regclass('public.user_records') IS NOT NULL" 2>/dev/null | grep -qx "t"
}

ldap_search() {
  if [[ "$SEED_TRANSPORT" == "network" ]]; then
    ldapsearch -x -LLL -H "ldap://${LDAP_HOST}:${LDAP_PORT}" -D "$LDAP_ADMIN_DN" -w "$LDAP_ADMIN_PW" "$@"
  else
    docker exec -i "$LDAP_CONTAINER" ldapsearch -x -LLL -D "$LDAP_ADMIN_DN" -w "$LDAP_ADMIN_PW" "$@"
  fi
}

# ldapadd's real failures: its own "ldapadd:" lines, minus the already-exists ones a re-run causes.
# Exits non-zero when there are none, so it doubles as the test for "anything worth printing".
ldap_errors() {
  grep -E "^ldapadd: " "$LDAP_LOG" 2>/dev/null | grep -vi "already exists"
}

# How many of the mock users are actually in the directory right now.
ldap_user_count() {
  ldap_search -b "$USERS_OU" -s one "(objectClass=inetOrgPerson)" dn 2>/dev/null \
    | grep -c "^dn: " || true
}

ldap_ready() {
  ldapsearch -x -H "ldap://${LDAP_HOST}:${LDAP_PORT}" -b "$LDAP_BASE_DN" -s base \
    -D "$LDAP_ADMIN_DN" -w "$LDAP_ADMIN_PW" >/dev/null 2>&1
}

# wait_for <description> <predicate>
wait_for() {
  local what="$1" predicate="$2" waited=0
  until "$predicate"; do
    if (( waited >= WAIT_TIMEOUT_SECONDS )); then
      echo "ERROR: gave up waiting ${WAIT_TIMEOUT_SECONDS}s for ${what}." >&2
      exit 1
    fi
    if (( waited % 15 == 0 )); then
      echo "    waiting for ${what}... (${waited}s)"
    fi
    sleep 3
    waited=$((waited + 3))
  done
}

require_container() {
  local name="$1"
  if ! docker ps --format '{{.Names}}' | grep -qx "$name"; then
    echo "ERROR: container '$name' is not running. Start the stack first:" >&2
    echo "         cd test && docker compose up -d" >&2
    exit 1
  fi
}

if [[ "$SEED_TRANSPORT" == "network" ]]; then
  needed=()
  do_ldap && needed+=(ldapadd ldapsearch)
  do_db   && needed+=(psql)
  for tool in "${needed[@]}"; do
    command -v "$tool" >/dev/null 2>&1 || { echo "ERROR: $tool is required in network mode." >&2; exit 1; }
  done
  echo "==> Seeding ${SEED_ONLY} over the network (ldap://${LDAP_HOST}:${LDAP_PORT}, postgres ${PG_HOST})"
  do_ldap && wait_for "the directory at ${LDAP_HOST}:${LDAP_PORT}" ldap_ready
else
  command -v docker >/dev/null 2>&1 || { echo "ERROR: docker is required but not found on PATH." >&2; exit 1; }
  require_container "$PG_CONTAINER"
  require_container "$LDAP_CONTAINER"
fi

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
  # The account demo mode is presented as (application.demo.user-dn, and a super-user in
  # test/application.yml). Appended rather than inserted: group assignment is indexed off this
  # array, so adding here leaves everyone else's groups exactly as they were.
  "Demo Admin|Admin|demo|RYS34B|FT|WinLLC|New York|North|IN"
)

# Alice is everyone's manager. manager lookup = user.title -> manager.street
MANAGER_ID="MGR-100"
MANAGER_CN="Alice Adams"

# ----------------------------- mock groups ----------------------------------
# groupOfUniqueNames entries under ou=Groups; users are randomly assigned below.
# NB: not named GROUPS -- that's a read-only bash builtin (the user's OS group ids).
MOCK_GROUPS=("Engineering" "Sales" "Operations" "Finance" "Support")

# Seed RANDOM so the (random) assignment is stable across runs.
RANDOM=42
GROUP_PRIMARY=()
GROUP_SECOND=()
assign_groups() {
  local n=${#MOCK_GROUPS[@]} i s
  for i in "${!USERS[@]}"; do
    GROUP_PRIMARY[$i]=$(( RANDOM % n ))
    GROUP_SECOND[$i]=-1
    # ~40% of users also belong to a second, distinct group
    if (( RANDOM % 10 < 4 )); then
      s=$(( RANDOM % n ))
      (( s != GROUP_PRIMARY[i] )) && GROUP_SECOND[$i]=$s
    fi
  done
}

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

dn: ou=Companies,${LDAP_BASE_DN}
objectClass: organizationalUnit
ou: Companies

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

  # Groups: users are randomly assigned (see assign_groups). Empty groups are skipped,
  # since groupOfUniqueNames must have at least one uniqueMember.
  local g i mcn members
  for g in "${!MOCK_GROUPS[@]}"; do
    members=""
    for i in "${!USERS[@]}"; do
      if (( GROUP_PRIMARY[i] == g || GROUP_SECOND[i] == g )); then
        IFS='|' read -r mcn _ <<<"${USERS[$i]}"
        members+="uniqueMember: cn=${mcn},${USERS_OU}"$'\n'
      fi
    done
    if [[ -n "$members" ]]; then
      cat <<LDIF
dn: cn=${MOCK_GROUPS[$g]},${GROUPS_OU}
objectClass: groupOfUniqueNames
cn: ${MOCK_GROUPS[$g]}
owner: cn=${MANAGER_CN},${USERS_OU}
${members}
LDIF
    fi
  done
}

assign_groups

if do_ldap; then
echo "==> Loading mock users and groups into LDAP (${LDAP_HOST:-$LDAP_CONTAINER})..."

LDAP_LOG="$(mktemp)"

# ldapadd -c keeps going past entries that already exist and still exits non-zero, so its exit
# code cannot tell "already there" from "every entry rejected". This used to be "|| true", which
# discarded the output as well - a directory that rejected the whole load looked identical to a
# successful one, and the script went on to print a happy summary over an empty directory. So:
# show anything that is not an "already exists", then check the directory rather than trust it.
if ! build_ldif | ldap_add >"$LDAP_LOG" 2>&1; then
  # ldapadd narrates every entry it attempts ("adding new entry ..."); only the "ldapadd:" lines
  # are failures, and "Already exists" among those is the expected result of a re-run. Filtering on
  # the narration instead made every re-run look like it had reported errors.
  if ldap_errors >/dev/null; then
    echo "    ldapadd reported:"
    ldap_errors | sed 's/^/      /'
  fi
fi

LOADED_USERS="$(ldap_user_count)"
if (( LOADED_USERS < ${#USERS[@]} )); then
  echo "ERROR: the directory holds ${LOADED_USERS} of the ${#USERS[@]} mock users under ${USERS_OU}." >&2
  echo "       Nothing else will look right, so stopping here. ldapadd said:" >&2
  sed 's/^/       /' "$LDAP_LOG" >&2
  exit 1
fi
echo "    ${LOADED_USERS} users present under ${USERS_OU}"
fi

if do_db; then
# ----------------------------- build check-in/out SQL -----------------------
# Timestamps are computed in SQL with now()/date_trunc so they stay timezone
# correct regardless of host locale.
SQL_FILE="$(mktemp)"

SEQ=0
emit() {
  # emit <dn> <action> <ts_sql_expr> <employeeType> <org> <loc> <branch> <dept> <wuid>
  SEQ=$((SEQ + 1))
  printf "INSERT INTO check_in_out_records (dn, \"timestamp\", session_id, action, windows_user_id, organization, employee_type, location, branch, duty_sub_organization, forced) VALUES ('%s', %s, 'mock-%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', false);\n" \
    "$1" "$3" "$SEQ" "$2" "$9" "$5" "$4" "$6" "$7" "$8" >>"$SQL_FILE"
}

# Remove any rows from a previous run so the script is idempotent.
echo "DELETE FROM check_in_out_records WHERE session_id LIKE 'mock-%';" >>"$SQL_FILE"

# Application roles. ADMIN comes from super-user-dns in the configuration, but MANAGER lives in
# user_records.user_role, which the application otherwise only ever creates as USER - so the demo
# login page could not honestly offer a manager without this. Written before the app has seen the
# user: createUserIfDoesNotExist only inserts when the row is absent, so it leaves this one alone.
cat >>"$SQL_FILE" <<SQL
DELETE FROM user_records WHERE lower(dn) = lower('cn=${MANAGER_CN},${USERS_OU}');
INSERT INTO user_records (dn, user_role) VALUES ('cn=${MANAGER_CN},${USERS_OU}', 'MANAGER');
SQL

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

if [[ "$SEED_TRANSPORT" == "network" ]]; then
  # Hibernate creates the tables (ddl-auto=update), so there is nothing to insert into until the
  # application has started at least once. The note at the end of this script is the manual
  # equivalent; in the demo stack this is what makes one "up" enough.
  wait_for "the application to create its tables" schema_ready
fi

echo "==> Inserting check-in/out records into Postgres (${PG_HOST:-$PG_CONTAINER})..."
psql_exec <"$SQL_FILE" >/dev/null

SEEDED_ROWS="$(psql_exec -tAqX -c \
  "SELECT count(*) FROM check_in_out_records WHERE session_id LIKE 'mock-%'" | tr -d '[:space:]')"
if [[ "${SEEDED_ROWS:-0}" == "0" ]]; then
  echo "ERROR: no mock check-in/out rows are in the database after inserting ${SEQ} of them." >&2
  exit 1
fi
echo "    ${SEEDED_ROWS} check-in/out rows present"

MANAGER_ROLE="$(psql_exec -tAqX -c \
  "SELECT user_role FROM user_records WHERE lower(dn) = lower('cn=${MANAGER_CN},${USERS_OU}')" \
  | tr -d '[:space:]')"
if [[ "$MANAGER_ROLE" != "MANAGER" ]]; then
  echo "ERROR: ${MANAGER_CN} should hold the MANAGER role but user_records says '${MANAGER_ROLE:-nothing}'." >&2
  exit 1
fi
echo "    ${MANAGER_CN} holds the MANAGER role"
fi

# ----------------------------- summary --------------------------------------
echo "==> Done. Summary:"
if do_db; then
  psql_exec -c "SELECT duty_sub_organization AS org, employee_type AS type, action, count(*)
                FROM check_in_out_records WHERE session_id LIKE 'mock-%'
                GROUP BY 1,2,3 ORDER BY 1,2,3;"
fi

echo
if do_ldap; then
echo "LDAP users:   ${#USERS[@]} under ${USERS_OU}"
echo "LDAP groups (random membership):"
for g in "${!MOCK_GROUPS[@]}"; do
  members=""
  for i in "${!USERS[@]}"; do
    if (( GROUP_PRIMARY[i] == g || GROUP_SECOND[i] == g )); then
      IFS='|' read -r mcn _ <<<"${USERS[$i]}"
      members+="${mcn}, "
    fi
  done
  [[ -n "$members" ]] && echo "  ${MOCK_GROUPS[$g]}: ${members%, }"
done
fi
echo "Org values:   RYS34B, RYS34C, RYS35A, ABC12X, ABC12Y"
echo "Today status: IN=Alice,Dave,Grace,Demo Admin  OUT=Bob,Erin  AWAY=Heidi,Frank  NONE=Carol"
echo
echo "Note: user_records rows are created lazily by the app when a user is looked up."
if [[ "$SEED_TRANSPORT" != "network" ]]; then
  echo "      Start the app (which creates the DB schema via ddl-auto=update) at least once"
  echo "      before running this if the tables don't exist yet. Network mode waits instead."
fi
