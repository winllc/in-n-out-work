-- =====================================================================================================
-- V8: the license state in the database (ens-license 1.2.0, JdbcLicenseStore), so every replica sees the
-- same license, installation id and revisions. Copied from the library's schema/postgresql.sql.
--
-- On the first start of this release the library imports what an earlier one kept in files (the license file
-- and ENS_LICENSE_STATE_DIR: the installation id and revisions), when the database holds no installation id
-- yet, so the installation keeps its id. Keep the old license volume mounted for that first start.
-- =====================================================================================================


CREATE TABLE ens_license_installation (
    product          VARCHAR(64)  NOT NULL PRIMARY KEY,
    installation_id  VARCHAR(32)  NOT NULL,
    created_at       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE ens_license_file (
    product          VARCHAR(64)  NOT NULL PRIMARY KEY,
    license_text     TEXT         NOT NULL,
    license_version  BIGINT       NOT NULL,
    updated_at       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE ens_license_revision (
    product          VARCHAR(64)  NOT NULL,
    license_id       VARCHAR(128) NOT NULL,
    revision         INTEGER      NOT NULL,
    updated_at       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (product, license_id)
);
