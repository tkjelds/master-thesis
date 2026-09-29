#!/bin/sh
# Build command traced by `codeql database create --build-mode=manual`.
# CodeQL runs it from --source-root. Each file is compiled on its own, like the
# javac verifier, so one broken file or a clashing helper class cannot block
# the others.
out_root=$(mktemp -d "${TMPDIR:-/tmp}/codeql-javac.XXXXXX")
for f in *.java; do
  [ -e "$f" ] || continue
  out="$out_root/${f%.java}"
  mkdir -p "$out"
  javac -d "$out" "$f" || true
done
rm -rf "$out_root"
exit 0
