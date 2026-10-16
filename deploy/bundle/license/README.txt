Put your ENS Solutions license file here, named exactly:

    license.lic

It is mounted read-only into the ReadyRoom container at /etc/ens/license/license.lic.
Without it ReadyRoom runs with its core features only. A renewed license dropped in here
takes effect within 30 seconds, without a restart. Do not edit the file: any change
invalidates its signature. It must be readable by the container (chmod 0644 license.lic).
