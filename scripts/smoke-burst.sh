# Sourced by scripts/smoke-test.sh (and scripts/test-smoke-burst.sh): what the rate-limit burst says.
# Input is a file with one HTTP status per line, the codes curl wrote for the burst's 300 requests.

# burst_count <file> <status> -> how many requests got that status.
burst_count() {
    grep -c "^$2\$" "$1" 2>/dev/null || true
}

# burst_limited <file> -> True when the limiter refused some of the burst (429).
burst_limited() {
    if [ "$(burst_count "$1" 429)" -gt 0 ]; then echo True; else echo False; fi
}

# burst_admitted <file> -> how many requests the limiter let through: every HTTP answer except 429. curl's
# 000 (no answer at all) is not counted: nothing showed that the limiter let it through.
burst_admitted() {
    grep -v -c -e '^429$' -e '^000$' "$1" 2>/dev/null || true
}

# burst_throttles <file> -> True when plenty were admitted before the limiter refused: it throttles, it
# does not ban. More than 50 rather than an exact number: the bucket refills during the burst.
#
# ADMITTED, not "answered 200" (KI-055): behind the limiter, the catalog route has a 2 s timeout and a
# circuit breaker, so on a cold stack some admitted requests end as the fallback's 503. Counting only 200s
# made a slow catalog-service look like a limiter that bans; the 503s are reported on their own (KI-062).
burst_throttles() {
    if [ "$(burst_admitted "$1")" -gt 50 ]; then echo True; else echo False; fi
}
