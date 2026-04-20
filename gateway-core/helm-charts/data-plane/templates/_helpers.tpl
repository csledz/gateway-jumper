{{/*
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
*/}}

{{- define "data-plane.name" -}}
{{- $parts := splitList "/" .Chart.Name -}}
{{- $base := index $parts (sub (len $parts) 1) -}}
{{- default $base (index .Values "name-override") | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "data-plane.fullname" -}}
{{- $override := index .Values "fullname-override" -}}
{{- if $override -}}
{{- $override | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := include "data-plane.name" . -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "data-plane.chart" -}}
{{- printf "%s-%s" (.Chart.Name | replace "/" "-") .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "data-plane.labels" -}}
helm.sh/chart: {{ include "data-plane.chart" . }}
{{ include "data-plane.selectorLabels" . }}
app.kubernetes.io/component: data-plane
app.kubernetes.io/part-of: gateway-core
{{- if .Values.zone }}
gateway.telekom.de/zone: {{ .Values.zone | quote }}
{{- end }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "data-plane.selectorLabels" -}}
app.kubernetes.io/name: {{ include "data-plane.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "data-plane.serviceAccountName" -}}
{{- $sa := index .Values "service-account" -}}
{{- if $sa.create -}}
{{- default (include "data-plane.fullname" .) $sa.name -}}
{{- else -}}
{{- default "default" $sa.name -}}
{{- end -}}
{{- end -}}

{{- define "data-plane.image" -}}
{{- $tag := default .Chart.AppVersion .Values.image.tag -}}
{{- printf "%s:%s" .Values.image.repository $tag -}}
{{- end -}}

{{- define "data-plane.keypairSecretName" -}}
{{- $existing := index .Values.keypair "existing-secret" -}}
{{- default (printf "%s-keypair" (include "data-plane.fullname" .)) $existing -}}
{{- end -}}
