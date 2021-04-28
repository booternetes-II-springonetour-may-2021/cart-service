# Cart Service

Manages the cart for customers visiting the coffee shop.

* This module depends on configuration that's read from a configmap. We use the [Spring Cloud Kubernetes
Config Map events change detector](https://github.com/spring-cloud/spring-cloud-kubernetes/blob/main/spring-cloud-kubernetes-fabric8-config/src/main/java/org/springframework/cloud/kubernetes/fabric8/config/reload/EventBasedConfigMapChangeDetector.java) 
  to publish a refresh event whenever the configmap is changed to load the configuration. At the moment, the application 
