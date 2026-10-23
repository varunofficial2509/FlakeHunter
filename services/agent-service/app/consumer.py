import json
import logging
from datetime import UTC, datetime
from typing import Any
from uuid import uuid4

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from langgraph.graph.state import CompiledStateGraph
from langgraph.types import Command
from opentelemetry.trace import SpanKind
from tenacity import AsyncRetrying, stop_after_attempt, wait_fixed

from app.tracing import extract, inject, tracer

log = logging.getLogger(__name__)

FINDING_DETECTED = "finding.detected"
EXPERIMENT_COMPLETED = "experiment.completed"
FIX_VERIFIED = "fix.verified"
RESUMES = {EXPERIMENT_COMPLETED: "experiment", FIX_VERIFIED: "verify"}  # answer topic → the node waiting for it
DIAGNOSIS_COMPLETED = "diagnosis.completed"
DLQ_SUFFIX = ".dlq"  # same as Topics.DLQ_SUFFIX in libs/events


def envelope(topic: str, hunt_id: str, payload: dict[str, Any]) -> dict[str, Any]:
    """The event envelope of tech doc section 6, like EventEnvelope.of in libs/events."""
    occurred = datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    return {
        "eventId": str(uuid4()),
        "eventType": topic,
        "schemaVersion": 1,
        "occurredAt": occurred,
        "huntId": hunt_id,
        "payload": payload,
    }


def publisher(producer: AIOKafkaProducer):
    async def publish(topic: str, key: str, hunt_id: str, payload: dict[str, Any]) -> None:
        value = json.dumps(envelope(topic, hunt_id, payload)).encode()
        with tracer.start_as_current_span(f"{topic} publish", kind=SpanKind.PRODUCER):
            await producer.send_and_wait(topic, value, key=key.encode(), headers=inject())

    return publish


def thread(finding_id: str) -> dict[str, Any]:
    return {"configurable": {"thread_id": finding_id}}


async def handle(graph: CompiledStateGraph, topic: str, envelope: dict) -> None:
    """Starts, resumes or skips the session of one finding. Safe to call again for the same event."""
    payload = envelope["payload"]
    config = thread(payload["findingId"])
    state = await graph.aget_state(config)
    if topic in RESUMES:
        pending = state.values.get("pending")
        if RESUMES[topic] in state.next and pending and pending["experimentId"] == payload["experimentId"]:
            await graph.ainvoke(Command(resume=payload), config)
        elif state.next and not any(t.interrupts for t in state.tasks):
            # Redelivered because the pod died after taking this answer in, before the session paused again.
            await graph.ainvoke(None, config)
        return  # otherwise a duplicate, or an answer the session no longer waits for
    if state.values and not state.next:
        return  # finished earlier: a redelivery
    # A session cut off mid-graph (pod killed) resumes from its last checkpoint instead of starting over.
    await graph.ainvoke(None if state.next else {"finding": payload, "hunt_id": envelope["huntId"]}, config)


async def consume(graph: CompiledStateGraph, consumer: AIOKafkaConsumer, producer: AIOKafkaProducer) -> None:
    """At-least-once: the offset is committed only after the session finished, paused for an experiment, or
    the message was dead-lettered.

    shortcut: one message at a time per pod; KEDA adds pods when findings queue up.
    """
    publish = publisher(producer)
    async for msg in consumer:
        # Continues the trace of whoever published the event.
        with tracer.start_as_current_span(f"{msg.topic} process", context=extract(msg), kind=SpanKind.CONSUMER):
            event = None
            try:
                event = json.loads(msg.value)
                async for attempt in AsyncRetrying(stop=stop_after_attempt(4), wait=wait_fixed(1), reraise=True):
                    with attempt:
                        await handle(graph, msg.topic, event)
            except Exception:  # bad JSON is not retried, like the Java consumers
                log.exception("%s at offset %s failed, sending to %s", msg.topic, msg.offset, msg.topic + DLQ_SUFFIX)
                await producer.send_and_wait(msg.topic + DLQ_SUFFIX, msg.value, key=msg.key, headers=list(msg.headers))
                if isinstance(event, dict) and "findingId" in event.get("payload", {}):
                    # Otherwise the hunt waits for this finding's diagnosis forever.
                    finding_id = event["payload"]["findingId"]
                    failed = {
                        "findingId": finding_id,
                        "status": "ERROR",
                        "category": None,
                        "confidence": None,
                        "explanation": "The agent session failed; its event is in " + msg.topic + DLQ_SUFFIX,
                        "patch": None,
                        "steps": [],
                    }
                    await publish(DIAGNOSIS_COMPLETED, finding_id, event["huntId"], failed)
            await consumer.commit()
