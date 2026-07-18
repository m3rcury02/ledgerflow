{{- define "ledgerflow.labels" -}}
app.kubernetes.io/name: ledgerflow
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: ledgerflow
{{- end -}}

{{- define "ledgerflow.selectorLabels.api" -}}
app.kubernetes.io/name: ledgerflow
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: api
{{- end -}}

{{- define "ledgerflow.selectorLabels.worker" -}}
app.kubernetes.io/name: ledgerflow
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: worker
{{- end -}}
