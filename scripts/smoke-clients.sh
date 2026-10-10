# Sourced by scripts/smoke-test.sh (and scripts/test-smoke-clients.sh): the smoke test's Redis and
# PostgreSQL clients. They use the caller's ctr_exec, REDIS_CONTAINER, POSTGRES_CONTAINER, REDIS_TLS,
# PGUSER_ and PGDB.

# redis_cli <args...> -> runs redis-cli, preferring one on PATH and falling back to the container.
# Returns non-zero when neither is available, so the caller can SKIP rather than invent a pass.
redis_cli() {
    if command -v redis-cli >/dev/null 2>&1; then
        # KI-050: Redis requires a password. REDISCLI_AUTH keeps it off the command line; it comes
        # from the environment, else .env. (The container path below already has it set.)
        REDISCLI_AUTH="${REDIS_PASSWORD:-$(sed -n 's/^REDIS_PASSWORD=//p' .env 2>/dev/null | tail -1)}" \
            redis-cli -h "${REDIS_HOST:-localhost}" -p "${REDIS_PORT:-6379}" "$@" 2>/dev/null
    elif command -v docker >/dev/null 2>&1 \
        && ctr_exec "$REDIS_CONTAINER" true >/dev/null 2>&1; then
        ctr_exec "$REDIS_CONTAINER" redis-cli $REDIS_TLS "$@" 2>/dev/null
    else
        return 1
    fi
}

# redis_cli_noauth <args...> -> the same, as a client that has NO password; prints stdout and stderr
# because the point is the refusal message. KI-050.
redis_cli_noauth() {
    if command -v redis-cli >/dev/null 2>&1; then
        REDISCLI_AUTH= redis-cli -h "${REDIS_HOST:-localhost}" -p "${REDIS_PORT:-6379}" "$@" 2>&1
    elif command -v docker >/dev/null 2>&1 \
        && ctr_exec "$REDIS_CONTAINER" true >/dev/null 2>&1; then
        ctr_exec "$REDIS_CONTAINER" sh -c 'unset REDISCLI_AUTH; redis-cli "$@"' sh $REDIS_TLS "$@" 2>&1
    else
        return 1
    fi
}

# psql_query <sql> -> prints the result, one row per line, no headers or padding
psql_query() {
    if command -v psql >/dev/null 2>&1; then
        PGPASSWORD="${POSTGRES_PASSWORD:-ecomdemo}" psql -qtAX \
            -h "${POSTGRES_HOST:-localhost}" -p "${POSTGRES_PORT:-5432}" \
            -U "$PGUSER_" -d "$PGDB" -c "$1" 2>/dev/null
    elif command -v docker >/dev/null 2>&1 \
        && ctr_exec "$POSTGRES_CONTAINER" true >/dev/null 2>&1; then
        ctr_exec "$POSTGRES_CONTAINER" psql -qtAX -U "$PGUSER_" -d "$PGDB" -c "$1" 2>/dev/null
    else
        return 1
    fi
}
