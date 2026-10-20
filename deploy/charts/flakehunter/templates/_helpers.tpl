{{- /* Kafka's address from any namespace; runners connect from theirs. */}}
{{- define "flakehunter.kafka" -}}
kafka.{{ .Release.Namespace }}.svc.cluster.local:9092
{{- end }}
