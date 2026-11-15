#!/bin/sh
out_root=$(mktemp -d "${TMPDIR:-/tmp}/codeql-javac.XXXXXX")
for f in *.java; do
  [ -e "$f" ] || continue
  out="$out_root/${f%.java}"
  mkdir -p "$out"
  javac -d "$out" "$f" || true
done
rm -rf "$out_root"
exit 0
