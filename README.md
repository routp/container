# Container Library

## 1. Dynamic Config
Dynamic Config builds a single config object from one or more specified config source files or directories. It is 
built-on config API of [Helidon](https://helidon.io/). Once initialized this monitors the changes on source config sources and auto-refreshes the in-memory config object. 
Additionally, provides hooks to be called on a config change event. 

