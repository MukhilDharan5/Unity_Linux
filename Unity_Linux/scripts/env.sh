#!/usr/bin/env bash
# Source from Unity_Linux; prefer installed tools, otherwise use local tooling.
UNITY_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -x "$UNITY_ROOT/.tools/cargo/bin/cargo" ]]; then
 export RUSTUP_HOME="$UNITY_ROOT/.tools/rustup"
 export CARGO_HOME="$UNITY_ROOT/.tools/cargo"
 export PATH="$UNITY_ROOT/.tools/cargo/bin:$PATH"
fi

# Avoid invoking a distro ccache wrapper that writes outside a sandboxed build tree.
if [[ -x /usr/bin/gcc ]]; then export CC="${CC:-/usr/bin/gcc}"; fi
