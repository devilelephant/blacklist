package com.devilelephant.fireholupdater;

import static java.lang.String.format;
import static java.nio.file.Files.isWritable;
import static java.nio.file.Files.newBufferedReader;
import static java.nio.file.Files.newBufferedWriter;
import static software.amazon.lambda.powertools.tracing.CaptureMode.DISABLED;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import software.amazon.lambda.powertools.metrics.Metrics;
import software.amazon.lambda.powertools.tracing.Tracing;

/**
 * Handler for requests to Lambda function.
 */
public class FireholUpdaterFn implements RequestHandler<ScheduledEvent, Void> {

  public static final String FIREHOL_GITHUB_REPO = "https://github.com/firehol/blocklist-ipsets";
  public static final int MAX_REPO_WALK_DEPTH = 10;
  public static final String MOUNT_PATH = "/mnt/firehol";
  public static final Path REPO_PATH = Path.of(MOUNT_PATH, "firehol");
  public static final String INCLUDE_FILTERS = "include_firehol_filters.txt";
  public static final Path BLOCK_FILE_PATH = Path.of(MOUNT_PATH, "blocked_ips.txt");

  private Set<String> fileFilters;

  @Tracing(captureMode = DISABLED)
  @Metrics(captureColdStart = true)
  public Void handleRequest(ScheduledEvent event, final Context context) {
    var log = context.getLogger();
    try {
      checkMountPath();
      fileFilters = fetchFireholFilters(log);
      updateOrCreateRepo(log);
      createBlockedIpsFile(log);
    } catch (Exception e) {
      log.log(format("ERROR message=%s", e.getMessage()));
    }
    return null;
  }

  void checkMountPath() {
    var baseDir = new File(MOUNT_PATH);
    if (!baseDir.exists()) {
      throw new IllegalStateException("Missing base dir " + baseDir);
    }
    if (!isWritable(baseDir.toPath())) {
      throw new IllegalStateException("Unwritable path " + baseDir);
    }
  }

  @SuppressWarnings("ConstantConditions")
  @Tracing(namespace = "fetchFireholFilters")
  Set<String> fetchFireholFilters(LambdaLogger log) throws URISyntaxException, IOException {
    var resource = getClass().getClassLoader().getResource(INCLUDE_FILTERS);
    var uri = resource.toURI();
    return Files.readAllLines(Path.of(uri))
        .stream()
        .filter(line -> !line.startsWith("#") || line.isBlank()) // exclude comment and spacer lines
        .map(line -> line.indexOf('*') == -1 ? format("%s.*", line) : line) // add regex to end
        .peek(s -> log.log(format("fetchFilters: filter=%s", s)))
        .collect(Collectors.toSet());
  }

  @Tracing(namespace = "updateOrCreateRepo")
  void updateOrCreateRepo(LambdaLogger log) throws GitAPIException, IOException {
    var repoDir = REPO_PATH.toFile();
    log.log(format("repo path: path=%s", repoDir.getAbsolutePath()));

    if (repoDir.exists()) {
      log.log("updateOrCreateRepo pull repo");
      var lockFile = Path.of(repoDir.getAbsolutePath(), ".git", "index.lock").toFile();
      if (lockFile.exists()) {
        log.log(format("updateOrCreateRepo try to unlock file=%s, success=%s", lockFile.getAbsolutePath(), lockFile.delete()));
      }
      try (var open = Git.open(repoDir.getAbsoluteFile())) {
        open.reset().setMode(ResetType.HARD).call();
        var result = open.pull().call();
        log.log("updateOrCreateRepo pull: result=" + result.toString());
      }
    } else {
      log.log("git clone new repo");
      var result = Git.cloneRepository()
          .setURI(FIREHOL_GITHUB_REPO)
          .setDirectory(repoDir.getAbsoluteFile())
          .call();
      log.log("updateOrCreateRepo clone: result=" + result.toString());
    }
  }

  @Tracing(namespace = "createBlockedIpsFile")
  void createBlockedIpsFile(LambdaLogger log) throws IOException {
    Set<Path> files = fetchFiles();
    log.log(format("createBlockedIpsFile size=%s", files));

    try (var writer = newBufferedWriter(BLOCK_FILE_PATH)) {
      for (Path path : files) {
        log.log(format("appending file=%s, size=%d", path.toFile().getAbsolutePath(), Files.size(path)));
        try (var reader = newBufferedReader(path)) {
          String line = reader.readLine();
          while (line != null) {
            // write to the output file, exclude comments
            if (!line.startsWith("#")) {
              writer.write(line);
              writer.newLine();
            }
            line = reader.readLine();
          }
          writer.flush();
        }
      }
    }
    log.log("createBlockedIpsFile finished " + Files.size(BLOCK_FILE_PATH));
  }

  // find all relevant files
  Set<Path> fetchFiles() throws IOException {
    try (Stream<Path> stream = Files.walk(REPO_PATH, MAX_REPO_WALK_DEPTH)) {
      return stream
          .filter(path ->
              !Files.isDirectory(path)
                  && isIncludePath(path)
          )
          .collect(Collectors.toSet());
    }
  }

  boolean isIncludePath(Path path) {
    String name = path.toFile().getName();
    return (name.endsWith(".netset") || name.endsWith(".ipset"))
        && fileFilters.stream().anyMatch(name::matches);
  }
}