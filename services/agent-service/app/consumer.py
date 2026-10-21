import json
import logging
from datetime import UTC, datetime
from typing import Any
from uuid import uuid4

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from langgraph.graph.state import CompiledStateGraph
from langgraph.types import Command
from tenacity import AsyncRetrying, stop_after_attempt, wait_fixed

log = logging.getLogger(__name__)

FINDING_DETECTED = "finding.detected"
EXPERIMENT_COMPLETED = "experiment.completed"
FIX_VERIFIED = "fix.verified"
RESUMES = {EXPERIMENT_COMPLETED: "experiment", FIX_VERIFIED: "verify"}  # answer topic → the node waiting for it
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
        await producer.send_and_wait(topic, value, key=key.encode())

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
        if RESUMES[topic] not in state.next or not pending or pending["experimentId"] != payload["experimentId"]:
            return  # a duplicate, or an answer the session no longer waits for
        await graph.ainvoke(Command(resume=payload), config)
        return
    if state.values and not state.next:
        return  # finished earlier: a redelivery
    # A session cut off mid-graph (pod killed) resumes from its last checkpoint instead of starting over.
    await graph.ainvoke(None if state.next else {"finding": payload, "hunt_id": envelope["huntId"]}, config)


async def consume(graph: CompiledStateGraph, consumer: AIOKafkaConsumer, producer: AIOKafkaProducer) -> None:
    """At-least-once: the offset is committed only after the session finished, paused for an experiment, or
    the message was dead-lettered.

    shortcut: one message at a time per pod; KEDA adds pods when findings queue up.
    """
    async for msg in consumer:
        try:
            event = json.loads(msg.value)
            async for attempt in AsyncRetrying(stop=stop_after_attempt(4), wait=wait_fixed(1), reraise=True):
                with attempt:
                    await handle(graph, msg.topic, event)
        except Exception:  # bad JSON is not retried, like the Java consumers
            log.exception("%s at offset %s failed, sending to %s", msg.topic, msg.offset, msg.topic + DLQ_SUFFIX)
            await producer.send_and_wait(msg.topic + DLQ_SUFFIX, msg.value, key=msg.key)
        await consumer.commit()
