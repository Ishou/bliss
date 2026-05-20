{{- define "bliss-nats.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "bliss-nats.fullname" -}}
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
{{- define "bliss-nats.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "bliss-nats.labels" -}}
helm.sh/chart: {{ include "bliss-nats.chart" . }}
app.kubernetes.io/name: {{ include "bliss-nats.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
{{- define "bliss-nats.selectorLabels" -}}
app.kubernetes.io/name: {{ include "bliss-nats.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
{{- define "bliss-nats.image" -}}
{{- if .Values.image.digest -}}
{{- printf "%s:%s@%s" .Values.image.repository .Values.image.tag .Values.image.digest -}}
{{- else -}}
{{- printf "%s:%s" .Values.image.repository .Values.image.tag -}}
{{- end -}}
{{- end -}}
{{- define "bliss-nats.streamInitImage" -}}
{{- if .Values.streamInit.image.digest -}}
{{- printf "%s:%s@%s" .Values.streamInit.image.repository .Values.streamInit.image.tag .Values.streamInit.image.digest -}}
{{- else -}}
{{- printf "%s:%s" .Values.streamInit.image.repository .Values.streamInit.image.tag -}}
{{- end -}}
{{- end -}}
{{/* durationToNanos: NATS --config JSON requires int64 nanos, not human-readable durations. */}}
{{- define "bliss-nats.durationToNanos" -}}
{{- $d := . -}}
{{- if hasSuffix "h" $d -}}
{{- $n := trimSuffix "h" $d | int64 -}}
{{- mul $n 3600000000000 -}}
{{- else if hasSuffix "m" $d -}}
{{- $n := trimSuffix "m" $d | int64 -}}
{{- mul $n 60000000000 -}}
{{- else if hasSuffix "s" $d -}}
{{- $n := trimSuffix "s" $d | int64 -}}
{{- mul $n 1000000000 -}}
{{- else -}}
{{- fail (printf "unsupported duration suffix in %q (expected h, m, or s)" $d) -}}
{{- end -}}
{{- end -}}
{{- define "bliss-nats.metricsExporterImage" -}}
{{- if .Values.metricsExporter.image.digest -}}
{{- printf "%s:%s@%s" .Values.metricsExporter.image.repository .Values.metricsExporter.image.tag .Values.metricsExporter.image.digest -}}
{{- else -}}
{{- printf "%s:%s" .Values.metricsExporter.image.repository .Values.metricsExporter.image.tag -}}
{{- end -}}
{{- end -}}
