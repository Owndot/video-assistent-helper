# Shared helpers for the build scripts (sourced, not executed).
# Portable between Linux and macOS: no GNU-only flags.

# sha256 FILE... -> "<hex>  <file>" lines on Linux (sha256sum) and macOS (shasum).
sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi
}

check_rust() {
  local v
  v="$(cd "$HERE/../pake-channel/core" && rustc -V)"
  case "$v" in "rustc 1.97.0 "*) ;; *) echo "rustc 1.97.0 required (rust-toolchain.toml), found: $v" >&2; exit 1 ;; esac
}

ndk_revision() { sed -n 's/^Pkg.Revision *= *//p' "$1/source.properties"; }

check_ndk() {
  local rev
  rev="$(ndk_revision "$1")"
  [ "$rev" = "27.2.12479018" ] || { echo "NDK 27.2.12479018 (r27c) required, found: ${rev:-none}" >&2; exit 1; }
}

check_jdk() {
  local v
  v="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')"
  [ "$v" = "17" ] || { echo "JDK 17 required (java.specification.version), found: ${v:-none}" >&2; exit 1; }
}
