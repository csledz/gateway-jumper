{{/*
SPDX-FileCopyrightText: 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
*/}}

{{/*
Expand the name of the chart. The chart is named "gateway-core/control-plane",
which cannot appear in a DNS label, so we strip everything before the last "/".
*/}}
{{- define "control-plane.name" -}}
{{- $parts := splitList "/" .Chart.Name -}}
{{- $base := index $parts (sub (len $parts) 1) -}}
{{- default $base (index .Values "name-override") | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "control-plane.fullname" -}}
{{- $override := index .Values "fullname-override" -}}
{{- if $override -}}
{{- $override | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := include "control-plane.name" . -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "control-plane.chart" -}}
{{- printf "%s-%s" (.Chart.Name | replace "/" "-") .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Common labels applied to every object rendered by this chart.
*/}}
{{- define "control-plane.labels" -}}
helm.sh/chart: {{ include "control-plane.chart" . }}
{{ include "control-plane.selectorLabels" . }}
app.kubernetes.io/component: control-plane
app.kubernetes.io/part-of: gateway-core
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "control-plane.selectorLabels" -}}
app.kubernetes.io/name: {{ include "control-plane.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "control-plane.serviceAccountName" -}}
{{- $sa := index .Values "service-account" -}}
{{- if $sa.create -}}
{{- default (include "control-plane.fullname" .) $sa.name -}}
{{- else -}}
{{- default "default" $sa.name -}}
{{- end -}}
{{- end -}}

{{- define "control-plane.image" -}}
{{- $tag := default .Chart.AppVersion .Values.image.tag -}}
{{- printf "%s:%s" .Values.image.repository $tag -}}
{{- end -}}

{{- define "control-plane.adminStatusApiImage" -}}
{{- $img := index (index .Values "admin-status-api") "image" -}}
{{- $tag := default .Chart.AppVersion $img.tag -}}
{{- printf "%s:%s" $img.repository $tag -}}
{{- end -}}
