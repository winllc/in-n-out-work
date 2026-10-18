-- =====================================================================================================
-- V5: the logoff that cannot sign in is bound to the address its logon came from (RR-01). Each check-in
-- records the client address it was made from; a logoff presenting its session id from another address is
-- refused and audited.
--
-- Check-ins from before this release have no address. Their session ids sit in agents' session files and
-- are still needed for those users' next logoff, so they are marked '*' (any address) and stay usable until
-- they expire (application.logoff.session-max-age-hours, a week by default) or are used. Only the last 90
-- days are marked: older ids have long expired at any sensible setting, and the update stays small on a
-- large table. A check-in without an address (one an older release still running during a rolling upgrade
-- wrote after this ran) identifies no logoff: that user's next logoff is refused and needs a fresh logon.
-- =====================================================================================================

ALTER TABLE check_in_out_records ADD COLUMN IF NOT EXISTS client_address varchar(64);

UPDATE check_in_out_records
   SET client_address = '*'
 WHERE action = 'CHECK_IN'
   AND session_id IS NOT NULL
   AND client_address IS NULL
   AND "timestamp" > now() - interval '90 days';
