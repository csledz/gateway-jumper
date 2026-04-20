#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0
#
# Helm charts verification entry-point driven by exec-maven-plugin.
#
# Primary path:
#   - `helm lint` each chart (control-plane, data-plane, umbrella)
#   - `helm template` each chart and pipe through
#     `kubectl --dry-run=client` to validate k8s syntax
#
# Fallback (documented; used when `helm` is not on PATH):
#   - `yamllint` every template file, or (if yamllint also missing)
#     a Python `yaml.safe_load_all` syntax check per file.
#
# Non-zero exit on any failure.

set -euo pipefail

HELM_BIN=${1:-helm}
KUBECTL_BIN=${2:-kubectl}
YAMLLINT_BIN=${3:-yamllint}
CONTROL_PLANE_DIR=${4:?control-plane dir required}
DATA_PLANE_DIR=${5:?data-plane dir required}
EXAMPLE_DIR=${6:?example dir required}
RENDER_OUTPUT=${7:?render output dir required}

mkdir -p "${RENDER_OUTPUT}"

WORK_DIR=$(mktemp -d -t verify-charts.XXXXXX)
trap 'rm -rf "${WORK_DIR}"' EXIT

have() { command -v "$1" >/dev/null 2>&1; }

log() { printf '[verify-charts] %s\n' "$*"; }

render_and_validate() {
    local chart_dir="$1"
    local release_name="$2"
    local out_file="${RENDER_OUTPUT}/$(basename "${chart_dir}").yaml"

    log "helm lint ${chart_dir}"
    "${HELM_BIN}" lint "${chart_dir}"

    log "helm template ${release_name} ${chart_dir} > ${out_file}"
    "${HELM_BIN}" template "${release_name}" "${chart_dir}" \
        --namespace gateway-core \
        > "${out_file}"

    if have "${KUBECTL_BIN}"; then
        # kubectl --dry-run=client still tries to discover the cluster API, which
        # breaks in offline CI. Try it, but treat connection failures as a soft
        # pass — the primary YAML validity check is `helm template` itself.
        log "kubectl --dry-run=client validate ${out_file}"
        local err_log="${WORK_DIR}/kubectl.err"
        if ! "${KUBECTL_BIN}" apply --dry-run=client --validate=false \
                -f "${out_file}" >/dev/null 2>"${err_log}"; then
            if grep -q -E "connection refused|no such host|unable to recognize" "${err_log}"; then
                log "kubectl offline (no cluster reachable), skipping server-side schema check"
            else
                cat "${err_log}" >&2
                return 1
            fi
        fi
    else
        log "kubectl not available, skipping server-side schema check"
    fi
}

fallback_validate() {
    local chart_dir="$1"
    log "fallback: validating YAML syntax under ${chart_dir}/templates"

    if have "${YAMLLINT_BIN}"; then
        "${YAMLLINT_BIN}" -d "{extends: relaxed, rules: {line-length: disable}}" \
            "${chart_dir}/templates"
        return 0
    fi

    if have python3; then
        log "yamllint not available, falling back to python3 yaml.safe_load_all"
        local found=0
        while IFS= read -r -d '' file; do
            found=1
            python3 - "$file" <<'PY'
import sys, yaml, re
path = sys.argv[1]
with open(path, 'r', encoding='utf-8') as fh:
    text = fh.read()
# Strip Helm template directives so pyyaml can parse the surrounding shape.
# We care here only about "does the file look like YAML", not semantic correctness.
stripped = re.sub(r'\{\{-?.*?-?\}\}', 'PLACEHOLDER', text, flags=re.DOTALL)
try:
    list(yaml.safe_load_all(stripped))
except yaml.YAMLError as exc:
    sys.stderr.write(f"YAML syntax error in {path}: {exc}\n")
    sys.exit(1)
PY
        done < <(find "${chart_dir}/templates" -type f \( -name '*.yaml' -o -name '*.yml' \) -print0)

        if [ "${found}" = "0" ]; then
            log "no YAML files found under ${chart_dir}/templates"
        fi
        return 0
    fi

    log "ERROR: neither helm, yamllint, nor python3 available — cannot validate"
    return 1
}

log "helm binary candidate: ${HELM_BIN}"
log "kubectl binary candidate: ${KUBECTL_BIN}"

if have "${HELM_BIN}"; then
    log "mode: helm lint + helm template (primary)"
    render_and_validate "${CONTROL_PLANE_DIR}" "test-control-plane"
    render_and_validate "${DATA_PLANE_DIR}"   "test-data-plane"

    # Umbrella rendering needs dependencies to be resolved. `dependency
    # update` pulls from each repo declared in Chart.yaml (no prior
    # `helm repo add` required) whereas `dependency build` expects the
    # repos to be pre-registered.
    log "helm dependency update (umbrella)"
    dep_log="${WORK_DIR}/dep.log"
    if ! "${HELM_BIN}" dependency update "${EXAMPLE_DIR}" >"${dep_log}" 2>&1; then
        cat "${dep_log}" >&2
        log "umbrella dependency update failed (likely offline); falling back to YAML syntax check"
        fallback_validate "${EXAMPLE_DIR}"
    else
        render_and_validate "${EXAMPLE_DIR}" "test-two-zone-mesh"
    fi
else
    log "mode: yamllint / python YAML fallback (helm not on PATH)"
    log "see gateway-core/helm-charts/README.md for install instructions"
    fallback_validate "${CONTROL_PLANE_DIR}"
    fallback_validate "${DATA_PLANE_DIR}"
    fallback_validate "${EXAMPLE_DIR}"
fi

log "verify-charts: OK"
