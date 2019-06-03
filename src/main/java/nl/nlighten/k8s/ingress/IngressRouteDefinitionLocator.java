package nl.nlighten.k8s.ingress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class IngressRouteDefinitionLocator
        implements RouteDefinitionLocator, Watcher<Ingress> {
    private static final Logger log = LoggerFactory
            .getLogger(IngressRouteDefinitionLocator.class);
    private static final String INGRESS_CLASS = "spring.cloud.gateway";
    private final ConcurrentMap<String, RouteDefinition> routeDefinitions = new ConcurrentHashMap<>();
    private final ApplicationEventPublisher eventPublisher;
    private final KubernetesClient kubernetesClient;
    private final ObjectMapper objectMapper;

    public IngressRouteDefinitionLocator(ApplicationEventPublisher eventPublisher,
                                         KubernetesClient kubernetesClient) {
        this.eventPublisher = eventPublisher;
        this.kubernetesClient = kubernetesClient;
        this.objectMapper = new Jackson2ObjectMapperBuilder().factory(new YAMLFactory())
                .build();
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        return Flux.fromIterable(this.routeDefinitions.values());
    }

    @PostConstruct
    public void watch() {
        this.kubernetesClient.extensions().ingresses().inAnyNamespace().watch(this);
    }

    @Override
    public void eventReceived(Action action, Ingress ingress) {
        try {
            ObjectMeta metadata = ingress.getMetadata();
            String id = metadata.getNamespace() + "/" + metadata.getName();
            if (action == Action.ADDED || action == Action.MODIFIED) {
                Map<String, String> annotations = metadata.getAnnotations();
                if ((annotations != null) && annotations.containsKey("kubernetes.io/ingress.class") && annotations.get("kubernetes.io/ingress.class").equals(INGRESS_CLASS)) {
                    String routes = annotations.get(INGRESS_CLASS + "/routes");
                    if (routes == null) {
                        log.warn("No {}/routes annotation found on ingress definition {}/{}. Ignoring this ingress definition.", INGRESS_CLASS, metadata.getNamespace(), metadata.getName());
                        return;
                    }
                    // ingress must have a default backend
                    if (ingress.getSpec().getBackend() == null) {
                        log.warn("No default backend found on ingress definition {}/{}. Ignoring this ingress definition.", metadata.getNamespace(), metadata.getName());
                        return;
                    }
                    String serviceName = ingress.getSpec().getBackend().getServiceName();
                    RouteDefinition routeDefinition = this.objectMapper.readValue(routes, RouteDefinition.class);
                    routeDefinition.setId(id);
                    if (routeDefinition.getUri() == null) {
                        URI uri = UriComponentsBuilder.newInstance()
                                .scheme("lb")
                                .host(serviceName)
                                .build().toUri();
                        routeDefinition.setUri(uri);
                    }
                    String yaml = this.objectMapper
                            .writeValueAsString(routeDefinition);
                    log.info("Create or update ingress: {}\t{}", id, yaml);
                    this.routeDefinitions.put(routeDefinition.getId(),
                            routeDefinition);
                    this.eventPublisher.publishEvent(new RefreshRoutesEvent(this));
                }
            } else if (action == Action.DELETED) {
                log.info("Delete {}", id);
                this.routeDefinitions.remove(id);
                this.eventPublisher.publishEvent(new RefreshRoutesEvent(this));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }


    @Override
    public void onClose(KubernetesClientException e) {
        log.debug("close");
    }
}
