package dev.flakehunter.orchestrator.ai;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Turns agent-service off and on through its KEDA ScaledObject (tech doc section 8, AI kill switch). The paused
 * annotation makes KEDA scale it to 0 and hold it there; removing it hands the replicas back to the Kafka lag.
 * Only in the cluster: Compose runs no agents.
 */
@Component
@ConditionalOnProperty(name = "flakehunter.runner.platform", havingValue = "kubernetes")
public class AgentScaler implements AutoCloseable {

    static final String SCALED_OBJECT = "agent-service";
    static final String PAUSED = "autoscaling.keda.sh/paused-replicas";

    private final KubernetesClient client;

    @Autowired
    AgentScaler() {
        this(new KubernetesClientBuilder().build()); // its own namespace, from the ServiceAccount
    }

    AgentScaler(KubernetesClient client) {
        this.client = client;
    }

    public void scale(boolean enabled) {
        String value = enabled ? "null" : "\"0\""; // a merge patch removes a key set to null
        client.genericKubernetesResources("keda.sh/v1alpha1", "ScaledObject").withName(SCALED_OBJECT)
                .patch(PatchContext.of(PatchType.JSON_MERGE),
                        "{\"metadata\":{\"annotations\":{\"" + PAUSED + "\":" + value + "}}}");
    }

    @Override
    public void close() {
        client.close();
    }
}
