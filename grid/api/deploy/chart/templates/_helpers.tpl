{{/* Standard helpers — `helm create` shape. */}}
{{- define "wordsparrow-api.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "wordsparrow-api.fullname" -}}
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
{{- define "wordsparrow-api.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "wordsparrow-api.labels" -}}
helm.sh/chart: {{ include "wordsparrow-api.chart" . }}
{{ include "wordsparrow-api.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
{{- define "wordsparrow-api.selectorLabels" -}}
app.kubernetes.io/name: {{ include "wordsparrow-api.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
{{- define "wordsparrow-api.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "wordsparrow-api.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
{{/* CNPG cluster name kept distinct so the operator's "<name>-app" secret is predictable. */}}
{{- define "wordsparrow-api.pgClusterName" -}}
{{- printf "%s-pg" (include "wordsparrow-api.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{/* Image ref: digest-pinned when set (manifesto), tag fallback otherwise. */}}
{{- define "wordsparrow-api.image" -}}
{{- if and (not .Values.image.digest) .Values.image.requireDigest -}}
{{- fail "image.digest must be set for production — MANIFESTO reproducible builds" -}}
{{- end -}}
{{- if .Values.image.digest -}}
{{- printf "%s@%s" .Values.image.repository .Values.image.digest -}}
{{- else -}}
{{- printf "%s:%s" .Values.image.repository .Values.image.tag -}}
{{- end -}}
{{- end -}}
