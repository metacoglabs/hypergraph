#!/usr/bin/env bash
set -Eeuo pipefail

log() {
	printf '%s [entrypoint] %s\n' "$(date '+%Y-%m-%d %H:%M:%S %Z')" "$*" >&2
}

if [ "${1:0:1}" = '-' ]; then
	set -- serve "$@"
fi

if [ "$1" != 'serve' ]; then
	exec hstore "$@"
fi
shift

if [ "$(id -u)" = '0' ]; then
	mkdir -p "$HSTORE_DATA"
	chown -R hstore:hstore "$HSTORE_DATA"
	chmod 700 "$HSTORE_DATA"
	exec setpriv --reuid=hstore --regid=hstore --init-groups "$BASH_SOURCE" serve "$@"
fi

if [ ! -w "$HSTORE_DATA" ]; then
	log "error: $HSTORE_DATA is not writable by $(id -un) (uid $(id -u))"
	log '       chown the volume to uid 999, or start the container with --user root once'
	log '       so the entrypoint can fix the ownership and drop privileges itself'
	exit 1
fi

if [ ! -s "$HSTORE_DATA/FORMAT" ]; then
	user="${HSTORE_USER:-hstore}"
	if [ -n "${HSTORE_PASSWORD_FILE:-}" ]; then
		HSTORE_PASSWORD="$(< "$HSTORE_PASSWORD_FILE")"
	fi
	if [ -n "${HSTORE_PASSWORD:-}" ]; then
		log "initialising $HSTORE_DATA with superuser $user"
		hstore init "$HSTORE_DATA" --superuser "$user" --password "$HSTORE_PASSWORD"
	elif [ "${HSTORE_AUTHENTICATION:-auto}" = 'off' ]; then
		log "initialising $HSTORE_DATA without a superuser; authentication is off"
		hstore init "$HSTORE_DATA"
	else
		log 'error: the database is uninitialised and HSTORE_PASSWORD is not set'
		log '       set HSTORE_PASSWORD (or HSTORE_PASSWORD_FILE) to create a superuser,'
		log '       or HSTORE_AUTHENTICATION=off to allow unauthenticated connections'
		exit 1
	fi
	for script in /docker-entrypoint-initdb.d/*; do
		case "$script" in
			*.hql)
				log "running $script"
				hstore exec "$HSTORE_DATA" "$script" > /dev/null
				;;
			*.sh)
				log "sourcing $script"
				. "$script"
				;;
			*)
				[ -e "$script" ] && log "ignoring $script"
				;;
		esac
	done
	log 'initialisation complete; starting server'
fi

unset HSTORE_PASSWORD
exec hstore serve "$HSTORE_DATA" "$@"
