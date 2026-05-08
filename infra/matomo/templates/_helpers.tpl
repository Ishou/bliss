{{/* Standard helpers — `helm create` shape. */}}
{{- define "matomo.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "matomo.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "matomo.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "matomo.labels" -}}
helm.sh/chart: {{ include "matomo.chart" . }}
{{ include "matomo.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "matomo.selectorLabels" -}}
app.kubernetes.io/name: {{ include "matomo.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/* Per-component fullnames. */}}
{{- define "matomo.matomo.fullname" -}}
{{- printf "%s-app" (include "matomo.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "matomo.mariadb.fullname" -}}
{{- printf "%s-mariadb" (include "matomo.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Image refs: digest-pinned when set (manifesto), tag fallback otherwise. */}}
{{- define "matomo.matomo.image" -}}
{{- $img := .Values.matomo.image -}}
{{- if and (not $img.digest) $img.requireDigest -}}
{{- fail "matomo.image.digest must be set for production — MANIFESTO reproducible builds" -}}
{{- end -}}
{{- if $img.digest -}}
{{- printf "%s@%s" $img.repository $img.digest -}}
{{- else -}}
{{- printf "%s:%s" $img.repository $img.tag -}}
{{- end -}}
{{- end -}}

{{- define "matomo.mariadb.image" -}}
{{- $img := .Values.mariadb.image -}}
{{- if and (not $img.digest) $img.requireDigest -}}
{{- fail "mariadb.image.digest must be set for production — MANIFESTO reproducible builds" -}}
{{- end -}}
{{- if $img.digest -}}
{{- printf "%s@%s" $img.repository $img.digest -}}
{{- else -}}
{{- printf "%s:%s" $img.repository $img.tag -}}
{{- end -}}
{{- end -}}
