#!/bin/sh
# Refuses a tree that contains any part of the closed-source validation backend.
#
# That backend lives in its own private repository, checked out at `private/` and ignored here. This
# is the guard against it being committed anyway — by a forced add, a moved file, or a paste. It
# runs as the pre-commit hook (`git config core.hooksPath .githooks`) and can be run by hand.

cd "$(git rev-parse --show-toplevel)" || exit 2

self='scripts/check-no-private.sh'
status=0

paths=$(git ls-files --cached | grep -E -i \
    -e '^private/' \
    -e 'IMDAppleServices' \
    -e 'open-absinthe/src/emulator\.rs$' \
    -e 'open-absinthe/tests/' \
    -e 'NAC_ANDROID_PORT' \
    -e 'nac-research')
if [ -n "$paths" ]; then
    echo "check-no-private: these paths belong in the private repository, not this one:" >&2
    echo "$paths" | sed 's/^/    /' >&2
    status=1
fi

# The same sources under another name: markers that only the backend itself contains.
content=$(git grep --cached -l -I -E \
    -e 'IMD_APPLE_SERVICES' \
    -e 'NAC_KEY_ESTABLISHMENT' \
    -e 'fn io_create_prop' \
    -- . ":!$self")
if [ -n "$content" ]; then
    echo "check-no-private: these files contain the private backend's source:" >&2
    echo "$content" | sed 's/^/    /' >&2
    status=1
fi

[ "$status" -eq 0 ] || echo "check-no-private: nothing was committed." >&2
exit "$status"
