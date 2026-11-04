# Sourced by scripts/smoke-test.sh (and scripts/test-smoke-pipes.sh): matching a check's output (KI-082).

# contains <grep options and pattern> -> succeeds when its input has a matching line, like `grep -q`, but
# reads ALL of its input first.
#
# `grep -q` exits at its first match. When the command piping into it is still writing (a response's
# later headers, a long log), that command gets SIGPIPE, and smoke-test.sh's `set -o pipefail` makes the
# pipeline report ITS failure: `curl -D - ... | grep -q Retry-After && echo True` printed False with the
# header there (PR #112). Reading to the end costs nothing at these sizes, and a producer that really
# fails still fails the pipeline, which pipefail is there for.
contains() {
    grep "$@" >/dev/null
}
