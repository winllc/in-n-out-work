ReadyRoom needs an ENS Solutions license. Download it from your account on the ENS portal
(an evaluation, or the license issued against your order). Either:

  - upload it on ReadyRoom's license screen, Administration > License (/app/admin/license),
    signed in as an administrator; or
  - save it here, named exactly license.lic, before running ./install.sh or ./upgrade.sh.

The scripts copy license/license.lic into the ReadyRoom license volume (readyroom_ens-license,
/var/lib/ens/readyroom in the container) when it holds no license yet. After that, replace the
license on the license screen; a file left here is not copied over an installed license.

The license screen shows this installation's id (for example RR-7F3K-29QD). Enter it on the
portal to bind a license to this installation. The id is kept in the license volume, so keep
that volume: "docker compose down -v" deletes it, and the installation gets a new id.

Without a license, ReadyRoom offers only sign-in and the license screen. Do not edit the file:
any change invalidates its signature. Licenses in the format used before this release are not
accepted; download the license again from the portal.
