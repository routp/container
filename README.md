# Container Library

## 1. Dynamic Config for Kubernetes and Docker
Dynamic Config builds a single config object from one or more specified config source files or directories. It is 
built-on config API of [Helidon](https://helidon.io/). Once initialized this monitors the changes on source config sources and auto-refreshes the in-memory config object. Additionally, provides hooks to be called on a config change event.

In Kubernetes the config source files can be exported as config map and then mounted to docker containers as volume mount. Dynamic Config auto updates the in-memory Config object whenever there is a change to config map at runtime. This enables to chnage configuration values during runtime without restarting pods.

