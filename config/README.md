# 1. Dynamic Config
Dynamic Config builds a single config object from one or more specified config source files or directories. It is 
built-on config API of [Helidon](https://helidon.io/). Once initialized this monitors the changes on source config sources and auto-refreshes the in-memory config object. 
Additionally, provides hooks to be called on a config change event. 

Config property with same name in a source config file take precedence over other source config file having same config 
property according to the order they are added into the list. This enables to override a global config value for a 
specific application at runtime if needed.

## 1.1 Initialization
````
# Initialize
DynamicConfig.builder().sources("/config/svc-config.properties").build();

# Initialize - Include System Environments
DynamicConfig.builder().sources("/config/svc-config.properties").includeSysEnvProps().build();

# Initialize - Add a handler
DynamicConfig.builder().sources("/config/svc-config.properties")..handler(LogLevelChangeHandler.class).build();

# Initialize - With Custom Executor (Dynamic config can be shutdown and re-initialized if needed)
DynamicConfig.builder().sources("/config/svc-config.properties").useCustomExecutor().build();

# Initialize - Custom Executor as daemons and implements auto-shutdown hook on JVM exit
DynamicConfig.builder().sources("/config/svc-config.properties").useCustomExecutor().runAsDaemon().build();

# Initialize - With Poll Strategy With Frequency. Default strategy is File System *Watch*
DynamicConfig.builder().sources("/config/svc-config.properties").strategy(Strategy.POLL).frequency(PollFrequency.HIGH).build();
````

## 1.2 Config Getters
Below accessors throws IllegalStateException if the DynamicConfig is not initialized
+ getConfigAsMap() - Returns a copy of config entries in a map
+ getValue(String key)    - Returns String value for the specified key, null if key not found
+ getBooleanValue(String key) - Returns Boolean value for the specified key
+ getIntValue(String key) - Returns Integer value for the specified key
+ getDoubleValue(String key) - Returns Double value for the specified key
+ getLongValue(String key) - Returns Long value for the specified key
+ getLongValue(String key) - Returns Long value for the specified key
+ getLongValue(String key) - Returns Long value for the specified key

Does not throw exception if config is not initialized rather returns the provided default value in such case
+ getConfigValueOrDefault(String key, Object defaultVal, Class<T> valType)
+ getConfigValueOrDefault(String key, String defaultVal)



## 1.3 Config Override
````
# Example: There are two config files, one is global level and one is specific to one service. At initialization there 
is a property named 'poll.interval=30' defined in global.properties. Config object holds value 'poll.interval' defined in 
global.properties as it is not defined in service config
DynamicConfig.builder().sources("/config/svc-config.properties", "/config/global-config.properties").build();
int pollInterval = DynamicConfig.getIntValue("poll.interval"); // Retruns 30

# Add same property 'poll.interval=60' into service specific config post-initialization. This is because service config 
is added before global properties in the arguments of sources at initialization. Other services using global config with
 their own config will still get the value as 30.
int pollInterval = DynamicConfig.getIntValue("poll.interval"); // Retruns 60
````

# 2. Kubernetes
Config files can be imported as K8S configmap and then mounted as a volume mount to the containers. The volume mount 
being a FileSystem enables config change at runtime withing restarting the pods.

# 2.1 Export config files as K8S configmap
````
$ kubectl create configmap hw-config --from-file=../my-config
````

# 2.2 Mount configmap as Volume
````yaml
kind: Deployment
apiVersion: apps/v1
spec:
  template:
    spec:
      volumes:
        - name: hw-config
          configMap:
            name: hw-config  
      containers:
        - name: hello-world
          volumeMounts:
            - name: hw-config
              mountPath: /config
````

# 2.3 Update or Replace Config Map
Kubernetes pushes the changes of config map into mounted volume of containers within few seconds. Once the FileSystem 
change event received the in-memory config object auto-refreshes. Any call from embedding service or application post 
this change sees the new value of existing config property or newly added config properties.


Update the file locally and execute below command
```bash
$ kubectl create configmap hw-config --from-file=../my-config -o yaml --dry-run | kubectl replace -f -
configmap "hw-config" replaced
$ 
```
Update one config property value(Local file not required)
```bash
$ kubectl get configmap hw-config --export -o yaml | sed 's/poll.interval=30/poll.interval=45/g' | kubectl replace -f - 
```

Log Entry when config object refreshes on chnage event
````bash
Mar 05, 2020 9:18:32 PM com.routp.container.config.DynamicConfig onChange
INFO: Change event received on specified config source files.
````

## 3. Docker-Compose
Changes into files inside my-config pushes immediately to containers and config object refreshes quickly.
````yaml
hello-world:
  image: helloWorld
  container_name: hello-world
    volumes:
    - /my-config/:/config/
````

## 4. Docker
````bash
$ docker run -d -it --name hello-world --mount source=/my-config,target=/config helloWorld
$ docker run -d -it --name hello-world -v /my-config/:/config helloWorld:latest
````

## 4. Development
Also, can be used during development time like examples given in unit test.
