#!/usr/bin/env bash
# Falla si aparece un SDK de telemetría/crash reporting prohibido (ADR-010) en los
# archivos de build, en las fuentes, en AARs o en el classpath de release.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

strict=0
for arg in "$@"; do
  case "$arg" in
    --strict) strict=1 ;;
    *)
      echo "✗ Argumento desconocido: $arg" >&2
      echo "Uso: $0 [--strict]" >&2
      exit 2
      ;;
  esac
done

if [[ -n "${CI:-}" ]]; then
  strict=1
fi

PATTERN='firebase|crashlytics|sentry|com\.google\.android\.gms|google-services|appsflyer|amplitude|mixpanel|(^|[^a-z0-9])segment([^a-z0-9]|$)|com\.segment|braze|bugsnag|datadog|newrelic|appcenter|onesignal|com\.facebook|applovin|ironsource|com\.huawei\.hms'

matches=0
scanned=0

scan_file() {
  local f="$1"
  [[ -f "$f" ]] || return 0
  scanned=$((scanned + 1))
  local out
  out="$(grep -EinH "$PATTERN" "$f" 2>/dev/null || true)"
  if [[ -n "$out" ]]; then
    echo "✗ Coincidencias prohibidas en $f:" >&2
    echo "$out" >&2
    matches=1
  fi
}

scan_archive() {
  local f="$1"
  [[ -f "$f" ]] || return 0
  scanned=$((scanned + 1))
  local out
  if command -v unzip >/dev/null 2>&1; then
    out="$(unzip -p "$f" 2>/dev/null | grep -Ein "$PATTERN" || true)"
  else
    out="$(grep -aEin "$PATTERN" "$f" 2>/dev/null || true)"
  fi
  if [[ -n "$out" ]]; then
    echo "✗ Coincidencias prohibidas en $f:" >&2
    echo "$out" >&2
    matches=1
  fi
}

shopt -s globstar nullglob
for f in **/build.gradle **/build.gradle.kts settings.gradle settings.gradle.kts \
         gradle/libs.versions.toml; do
  scan_file "$f"
done

for f in app/src/**/*.kt app/src/**/*.xml; do
  scan_file "$f"
done

for f in **/*.aar; do
  [[ "$f" == */build/* || "$f" == */.gradle/* ]] && continue
  scan_archive "$f"
done

if (( scanned == 0 )); then
  if (( strict )); then
    echo "✗ Modo estricto: no se encontró ningún archivo de build que escanear (fail-open detectado)." >&2
    exit 1
  fi
  echo "• Sin archivos de build todavía."
else
  echo "• Escaneados $scanned archivo(s)."
fi

if [[ -x ./gradlew ]]; then
  echo "• Escaneando classpath de release (./gradlew :app:dependencies)…"
  if ! gradle_out="$(./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath 2>&1)"; then
    echo "✗ ./gradlew falló; no se pudo verificar el classpath de release:" >&2
    echo "$gradle_out" >&2
    exit 1
  fi
  out="$(printf '%s\n' "$gradle_out" | grep -Ein "$PATTERN" || true)"
  if [[ -n "$out" ]]; then
    echo "✗ Coincidencias prohibidas en el classpath de release:" >&2
    echo "$out" >&2
    matches=1
  fi
else
  echo "• Sin ./gradlew todavía: solo se escanearon archivos de build."
fi

if (( matches )); then
  echo "✗ Telemetría prohibida detectada (ADR-010)." >&2
  exit 1
fi

echo "✓ Sin SDKs de telemetría prohibidos."
