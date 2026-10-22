from aiokafka.structs import ConsumerRecord
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider

from app.tracing import extract, inject


def test_the_trace_survives_a_trip_through_kafka_headers():
    with TracerProvider().get_tracer("test").start_as_current_span("publish") as span:
        headers = inject()
    sent = span.get_span_context()

    msg = ConsumerRecord("t", 0, 0, 0, 0, None, b"{}", None, 2, 2, headers)
    received = trace.get_current_span(extract(msg)).get_span_context()

    assert (received.trace_id, received.span_id) == (sent.trace_id, sent.span_id)
