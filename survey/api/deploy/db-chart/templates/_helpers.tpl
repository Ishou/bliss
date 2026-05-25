{{/* Minimal helpers — the only resource in this chart is the Cluster.
     The Cluster name is the release name (set by the deploy workflow to
     `wordsparrow-survey-api-pg`), so the auto-managed `<name>-app`
     Secret that the api Deployment reads via `secretKeyRef` is
     predictable. Mirrors identity/api/deploy/db-chart/templates/_helpers.tpl. */}}
{{- define "bliss-survey-api-pg.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "bliss-survey-api-pg.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "bliss-survey-api-pg.labels" -}}
helm.sh/chart: {{ include "bliss-survey-api-pg.chart" . }}
app.kubernetes.io/name: {{ include "bliss-survey-api-pg.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
