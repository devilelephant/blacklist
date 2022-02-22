Setup
=====
Requires JDK 11 

**Credentials**

add an `[ipcheck]` section to your ~/.aws/credentials file

```text
[ipcheck]
aws_default_region = <REGION>
aws_access_key_id = <KEY>
aws_secret_access_key = <SECRET>
```

Build
=====

    mvn install

Test
====
Spring actuator endpoints
```
http://localhost:8080/actuator
http://localhost:8080/actuator/info
http://localhost:8080/actuator/health
http://localhost:8080/actuator/metrics
```

IP Check endpoint
```text
http://localhost:8080/ipcheck/<IPv4 ip>

http://localhost:8080/ipcheck/255.0.0.0
```
