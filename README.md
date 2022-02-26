Setup
=====
Requires JDK 11 

**AWS Credentials**

AWS credentials required either at `.aws` or environment.

Build and Run Local
=====
```text
mvn install
mvn spring-boot:run -Dspring.boot.run.profiles=local
```

Test
====
Spring actuator endpoints
```
http://localhost:8080/actuator
http://localhost:8080/actuator/info
http://localhost:8080/actuator/health
http://localhost:8080/actuator/metrics
```

Reload Lists
---
```text
curl -i -X POST localhost:8080/blacklist/api/reload
```

IP Check endpoint
----
IP Not Found (not blocked)
```text
curl -i localhost:8080/blacklist/api?ip=1.2.3.4

HTTP/1.1 404
Content-Type: application/json
Content-Length: 28
Date: Sat, 26 Feb 2022 19:17:52 GMT

{"result":"","ip":"1.2.3.4"}
```
IP Found (should be blocked)
```text
curl -i localhost:8080/blacklist/api\?ip=222.122.209.102                                                                                      master ◼
HTTP/1.1 200
Content-Type: application/json
Content-Length: 51
Date: Sat, 26 Feb 2022 19:26:53 GMT

{"ip":"222.122.209.102","result":"222.122.209.102"}%
```
