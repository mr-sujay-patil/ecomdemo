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

# burst_throttles <file> -> True when plenty were served before the limiter refused: it throttles, it does
# not ban. More than 50 rather than an exact number: the bucket refills during the burst.
burst_throttles() {
    if [ "$(burst_count "$1" 200)" -gt 50 ]; then echo True; else echo False; fi
}
