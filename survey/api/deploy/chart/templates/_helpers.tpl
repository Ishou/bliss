{{/* Standard helpers — `helm create` shape, mirrors identity/api chart. */}}
{{- define "bliss-survey-api.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "bliss-survey-api.fullname" -}}
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
{{- define "bliss-survey-api.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "bliss-survey-api.labels" -}}
helm.sh/chart: {{ include "bliss-survey-api.chart" . }}
{{ include "bliss-survey-api.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
{{- define "bliss-survey-api.selectorLabels" -}}
app.kubernetes.io/name: {{ include "bliss-survey-api.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
{{- define "bliss-survey-api.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "bliss-survey-api.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
{{/* Image ref: digest-pinned when set (manifesto), tag fallback otherwise. */}}
{{- define "bliss-survey-api.image" -}}
{{- if and (not .Values.image.digest) .Values.image.requireDigest -}}
{{- fail "image.digest must be set for production — MANIFESTO reproducible builds" -}}
{{- end -}}
{{- if .Values.image.digest -}}
{{- printf "%s@%s" .Values.image.repository .Values.image.digest -}}
{{- else -}}
{{- printf "%s:%s" .Values.image.repository .Values.image.tag -}}
{{- end -}}
{{- end -}}
