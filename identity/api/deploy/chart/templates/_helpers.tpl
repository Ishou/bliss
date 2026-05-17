{{/* Standard helpers — `helm create` shape, mirrors game/api chart. */}}
{{- define "bliss-identity-api.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "bliss-identity-api.fullname" -}}
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
{{- define "bliss-identity-api.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "bliss-identity-api.labels" -}}
helm.sh/chart: {{ include "bliss-identity-api.chart" . }}
{{ include "bliss-identity-api.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
{{- define "bliss-identity-api.selectorLabels" -}}
app.kubernetes.io/name: {{ include "bliss-identity-api.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
{{- define "bliss-identity-api.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "bliss-identity-api.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
{{/* Image ref: digest-pinned when set (manifesto), tag fallback otherwise. */}}
{{- define "bliss-identity-api.image" -}}
{{- if and (not .Values.image.digest) .Values.image.requireDigest -}}
{{- fail "image.digest must be set for production — MANIFESTO reproducible builds" -}}
{{- end -}}
{{- if .Values.image.digest -}}
{{- printf "%s@%s" .Values.image.repository .Values.image.digest -}}
{{- else -}}
{{- printf "%s:%s" .Values.image.repository .Values.image.tag -}}
{{- end -}}
{{- end -}}
