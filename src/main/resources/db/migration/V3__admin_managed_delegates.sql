-- =====================================================================================================
-- V3: alternate managers (delegates) are set by administrators only (RR-07). They receive a person's
-- absence alerts, may answer them and decide the person's status requests, so a person no longer picks
-- their own.
--
-- Delegates people chose for themselves before this release are kept, so nobody silently stops receiving
-- the alerts they had, but marked for an administrator to review (Administration > Delegates). ReadyRoom
-- logs how many at startup.
-- =====================================================================================================

-- Room for an administrator to name several delegates, as favourite groups already have.
ALTER TABLE user_records ALTER COLUMN alternate_managers TYPE varchar(2000);

ALTER TABLE user_records ADD COLUMN alternate_managers_review_pending boolean NOT NULL DEFAULT false;

UPDATE user_records
SET alternate_managers_review_pending = true
WHERE alternate_managers IS NOT NULL
  AND btrim(alternate_managers, '; ') <> '';
