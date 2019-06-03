# Spring Cloud Gateway Ingress
Naive implementation of Spring Cloud Gateway as K8S ingress based on similar example by [making](https://github.com/making/kubernetes-spring-cloud-gateway).


```
./mvn clean package -DskipTests=true
docker build -t localhost:5000/spring-cloud-gateway-ingress .
docker push localhost:5000/spring-cloud-gateway-ingress
```

```
kubectl apply -f k8s/ingress.yml
kubectl apply -f k8s/httpbin.yml
```


sample ingress definition (deployment & service omitted)

```yaml
apiVersion: networking.k8s.io/v1beta1
 kind: Ingress
 metadata:
   name: httpbin
   namespace: ingress
   annotations:
     kubernetes.io/ingress.class: spring.cloud.gateway
     spring.cloud.gateway/routes: |
       predicates:
       - Host=httpbin.nlighten.nl
       - Path=/{segment}
       filters:
       - SetPath=/anything/{segment}
       - PreserveHostHeader
       - SecureHeaders
       - name: Retry
         args:
           retries: 3
           statuses: BAD_GATEWAY
       uri: lb://httpbin
 spec:
   backend:
     serviceName: httpbin
     servicePort: 80

```


Notes/limitations
* Just a test. Do not run this in production.
* Both gateway and backends must run in same namespace.
* Relies on Spring Cloud Kubernetes for backend discovery and ribbon load balancing
