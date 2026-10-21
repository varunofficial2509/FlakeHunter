import json
import logging

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from langgraph.graph.state import CompiledStateGraph
from tenacity import AsyncRetrying, stop_after_attempt, wait_fixed

log = logging.getLogger(__name__)

FINDING_DETECTED = "finding.detected"
DLQ_SUFFIX = ".dlq"  # same as Topics.DLQ_SUFFIX in libs/events


async def handle(graph: CompiledStateGraph, envelope: dict) -> None:
    """Runs, resumes or skips the session of one finding. Safe to call again for the same event."""
    finding = envelope["payload"]
    config = {"configurable": {"thread_id": finding["findingId"]}}
    state = await graph.aget_state(config)
    if state.values and not state.next:
        return  # finished earlier: a redelivery
    # A session cut off mid-graph (pod killed) resumes from its last checkpoint instead of starting over.
    await graph.ainvoke(None if state.next else {"finding": finding, "hunt_id": envelope["huntId"]}, config)


async def consume(graph: CompiledStateGraph, consumer: AIOKafkaConsumer, producer: AIOKafkaProducer) -> None:
    """At-least-once: the offset is committed only after the session finished or was dead-lettered.

    shortcut: one finding at a time per pod; KEDA adds pods when findings queue up.
    """
    async for msg in consumer:
        try:
            envelope = json.loads(msg.value)
            async for attempt in AsyncRetrying(stop=stop_after_attempt(4), wait=wait_fixed(1), reraise=True):
                with attempt:
                    await handle(graph, envelope)
        except Exception:  # bad JSON is not retried, like the Java consumers
            log.exception("finding.detected at offset %s failed, sending to %s", msg.offset, msg.topic + DLQ_SUFFIX)
            await producer.send_and_wait(msg.topic + DLQ_SUFFIX, msg.value, key=msg.key)
        await consumer.commit()
