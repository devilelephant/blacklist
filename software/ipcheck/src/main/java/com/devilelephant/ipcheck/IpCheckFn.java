package com.devilelephant.ipcheck;

import static java.lang.String.format;
import static software.amazon.lambda.powertools.tracing.CaptureMode.DISABLED;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import software.amazon.lambda.powertools.metrics.Metrics;
import software.amazon.lambda.powertools.tracing.Tracing;

/**
 * Handler for requests to Lambda function.
 */
public class IpCheckFn implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  public static final String MOUNT_PATH = "/mnt/firehol";
  public static final Path BLOCK_FILE_PATH = Path.of(MOUNT_PATH, "blocked_ips.txt");
  public static final ObjectMapper mapper = new ObjectMapper();

  private IpTree tree;
  private Instant fileLastModified;

  @Tracing(captureMode = DISABLED)
  @Metrics(captureColdStart = true)
  public APIGatewayProxyResponseEvent handleRequest(final APIGatewayProxyRequestEvent input, final Context context) {
    var headers = Map.of("Content-Type", "application/json", "X-Custom-Header", "application/json");
    var response = new APIGatewayProxyResponseEvent().withHeaders(headers);
    var log = context.getLogger();

    try {
      Instant efsFileLastModified = checkImportFile();
      // Don't reload unless cold start or EFS file has changed
      if (tree == null || !fileLastModified.equals(efsFileLastModified)) {
        tree = new IpTree();
        fileLastModified = efsFileLastModified;
        log.log(format("Load ips from file=%s, date=%s", BLOCK_FILE_PATH.toFile().getAbsolutePath(), fileLastModified.atZone(ZoneOffset.UTC)));
        tree.load(BLOCK_FILE_PATH);
      } else {
        log.log(format("Reuse warm ips from file=%s, date=%s", BLOCK_FILE_PATH.toFile().getAbsolutePath(), fileLastModified.atZone(ZoneOffset.UTC)));
      }
      var ip = input.getQueryStringParameters().get("ip");
      checkIp(ip, response, log);
    } catch (Exception e) {
      log.log(format("ERROR message=%s", e.getMessage()));
      return response
          .withBody(toJson(Map.of("error", e.getMessage())))
          .withStatusCode(500);
    }

    log.log(format("response code=%d, body=%s", response.getStatusCode(), response.getBody()));
    return response;
  }

  Instant checkImportFile() throws IOException {
    var file = BLOCK_FILE_PATH.toFile();
    if (!(file.exists() && file.canRead())) {
      throw new IOException("Missing or unreadable: file=" + file.getAbsolutePath());
    }
    return Files.getLastModifiedTime(BLOCK_FILE_PATH).toInstant();
  }

  @Tracing(namespace = "checkIp")
  private void checkIp(String ip, APIGatewayProxyResponseEvent response, LambdaLogger log) {
    if (ip == null) {
      log.log("missing query parameter 'ip'");
      response
          .withStatusCode(400)
          .withBody(toJson(Map.of("error", "missing query parameter 'ip'")));
    } else {
      var resp = tree.find(ip);
      if (resp.isEmpty()) {
        response
            .withStatusCode(404)
            .withBody(toJson(Map.of("ip", ip, "block", "")));
      } else {
        response
            .withStatusCode(200)
            .withBody(toJson(Map.of("ip", ip, "block", resp)));
      }
    }
  }

  String toJson(Map<String, String> map) {
    try {
      return mapper.writeValueAsString(map);
    } catch (JsonProcessingException e) {
      return "{" + map.entrySet() + "}";
    }
  }
}