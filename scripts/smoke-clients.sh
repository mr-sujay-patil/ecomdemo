# Sourced by scripts/smoke-test.sh (and scripts/test-smoke-clients.sh): the smoke test's Redis and
# PostgreSQL clients. They use the caller's ctr_exec, REDIS_CONTAINER, POSTGRES_CONTAINER, REDIS_TLS,
# PGUSER_ and PGDB.
#
# A client on the HOST is used only on compose, where the stack publishes Redis and PostgreSQL on
# 127.0.0.1. On the kind cluster nothing forwards those ports, and since KI-058/Phase 35/Phase 36 the
# cluster's Redis and databases accept only TLS with a client certificate, which only the pod has: there
# a host client would reach the wrong server, or a compose stack's (KI-064, KI-066).
use_host_client() { # use_host_client <tool>
    [ "${SMOKE_PLATFORM:-compose}" != "k8s" ] && command -v "$1" >/dev/null 2>&1
}

# redis_cli <args...> -> runs redis-cli: one on the host on compose (if installed), else in the container
# or pod (ctr_exec: docker on compose, kubectl on k8s).
# Returns non-zero when neither is available, so the caller can SKIP rather than invent a pass.
redis_cli() {
    if use_host_client redis-cli; then
        # KI-050: Redis requires a password. REDISCLI_AUTH keeps it off the command line; it comes
        # from the environment, else .env. (The container path below already has it set.)
        REDISCLI_AUTH="${REDIS_PASSWORD:-$(sed -n 's/^REDIS_PASSWORD=//p' .env 2>/dev/null | tail -1)}" \
            redis-cli -h "${REDIS_HOST:-localhost}" -p "${REDIS_PORT:-6379}" "$@" 2>/dev/null
    elif ctr_exec "$REDIS_CONTAINER" true >/dev/null 2>&1; then
        ctr_exec "$REDIS_CONTAINER" redis-cli $REDIS_TLS "$@" 2>/dev/null
    else
        return 1
    fi
}

# redis_cli_noauth <args...> -> the same, as a client that has NO password; prints stdout and stderr
# because the point is the refusal message. KI-050.
redis_cli_noauth() {
    if use_host_client redis-cli; then
        REDISCLI_AUTH= redis-cli -h "${REDIS_HOST:-localhost}" -p "${REDIS_PORT:-6379}" "$@" 2>&1
    elif ctr_exec "$REDIS_CONTAINER" true >/dev/null 2>&1; then
        ctr_exec "$REDIS_CONTAINER" sh -c 'unset REDISCLI_AUTH; redis-cli "$@"' sh $REDIS_TLS "$@" 2>&1
    else
        return 1
    fi
}

# psql_query <sql> -> prints the result, one row per line, no headers or padding
psql_query() {
    if use_host_client psql; then
        PGPASSWORD="${POSTGRES_PASSWORD:-ecomdemo}" psql -qtAX \
            -h "${POSTGRES_HOST:-localhost}" -p "${POSTGRES_PORT:-5432}" \
            -U "$PGUSER_" -d "$PGDB" -c "$1" 2>/dev/null
    elif ctr_exec "$POSTGRES_CONTAINER" true >/dev/null 2>&1; then
        ctr_exec "$POSTGRES_CONTAINER" psql -qtAX -U "$PGUSER_" -d "$PGDB" -c "$1" 2>/dev/null
    else
        return 1
    fi
}
