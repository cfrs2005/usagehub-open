#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

forbidden_files=$(git ls-files 2>/dev/null | rg -n '(^|/)(\.env($|\.)|.*\.(jks|keystore|apk|aab|sqlite|db|base64)$)' | rg -v '(^|/)\.env\.example$' || true)
if [ -n "$forbidden_files" ]; then
  printf 'Forbidden tracked runtime files:\n%s\n' "$forbidden_files" >&2
  exit 1
fi

secrets=$(rg -n --hidden -g '!.git/**' -g '!node_modules/**' -g '!scripts/secret-scan.sh' -g '!**/*.md' '(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16}|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----|CF_API_TOKEN=[A-Za-z0-9_-]{20,})' . || true)
if [ -n "$secrets" ]; then
  printf 'Potential secrets found:\n%s\n' "$secrets" >&2
  exit 1
fi

private_markers=$(rg -n -i --hidden -g '!.git/**' -g '!node_modules/**' -g '!scripts/secret-scan.sh' '(dev\.toy|scr01|20260401|wptheme|hkg-vps|singbox|keychain|/Users/zhangqingyue)' . || true)
if [ -n "$private_markers" ]; then
  printf 'Private deployment marker found:\n%s\n' "$private_markers" >&2
  exit 1
fi

printf 'secret_scan=ok\n'
