"""Traces to Tempo over OTLP. Kafka headers carry the W3C traceparent, like the Java services, so one trace
follows a finding from the aggregator through the agents to the orchestrator's experiment runs and back."""

import os

from aiokafka.structs import ConsumerRecord
from opentelemetry import propagate, trace
from opentelemetry.context import Context
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor

tracer = trace.get_tracer("agent-service")  # a proxy until setup() installs the real provider


def setup() -> TracerProvider | None:
    """Exports only where OTEL_EXPORTER_OTLP_ENDPOINT is set (the Helm chart); elsewhere spans cost nothing."""
    if not os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT"):
        return None
    provider = TracerProvider(resource=Resource.create({"service.name": "agent-service"}))
    provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter()))
    trace.set_tracer_provider(provider)
    return provider


def extract(msg: ConsumerRecord) -> Context:
    return propagate.extract({k: v.decode() for k, v in msg.headers or ()})


def inject() -> list[tuple[str, bytes]]:
    carrier: dict[str, str] = {}
    propagate.inject(carrier)
    return [(k, v.encode()) for k, v in carrier.items()]
