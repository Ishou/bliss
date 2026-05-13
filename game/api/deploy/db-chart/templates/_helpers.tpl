{{/* Minimal helpers — the only resource in this chart is the Cluster.
     The Cluster name is the release name (set by the deploy workflow to
     `wordsparrow-game-api-pg`), so the auto-managed `<name>-app` Secret
     that the api Deployment reads via `secretKeyRef` is predictable. */}}
{{- define "bliss-game-api-pg.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "bliss-game-api-pg.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "bliss-game-api-pg.labels" -}}
helm.sh/chart: {{ include "bliss-game-api-pg.chart" . }}
app.kubernetes.io/name: {{ include "bliss-game-api-pg.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
