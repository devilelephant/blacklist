package devilelephant.ipblacklist;

import static java.nio.file.Files.isWritable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;

/**
 * Loads an {@link IpTree} instance with the latest ipsets from the FIREHOL repo.
 */
@SuppressWarnings("SpellCheckingInspection")
@Slf4j
public class IpTreeLoader {

  public static final String FIREHOL_GITHUB_REPO = "https://github.com/firehol/blocklist-ipsets";
  public static final int MAX_REPO_WALK_DEPTH = 10;
  private final File baseDir;
  private final File repoDir;
  private final Set<String> fileFilters;

  public IpTreeLoader(Path workingPath, Set<String> fileFilters) {
    this.baseDir = workingPath.toFile();
    this.repoDir = new File(baseDir, "firehof");
    this.fileFilters = Set.copyOf(fileFilters);
  }

  public void load(IpTree tree) {
    try {
      log.info("updating local: repo={}", repoDir.getAbsoluteFile());
      updateLocalRepo();

      log.info("walk repo file tree");
      var files = fetchFiles(repoDir.getAbsolutePath());

      log.info("parsing {} files", files.size());
      files.forEach(f -> parseIpFile(tree, f));
      log.info("finshed");

      tree.walk(new File(repoDir, "ips.txt"));

    } catch (Exception e) {
      throw new IllegalStateException("Unexpected error loading tree", e);
    }
  }

  // clone or pull firehof repo locally
  void updateLocalRepo() throws GitAPIException, IOException {
    log.info("run path: path={}", new File(".").getAbsolutePath());
    log.info("baseDir path: path={}", baseDir.getAbsolutePath());

    if (!baseDir.exists()) {
      throw new IllegalStateException("Missing base dir " + baseDir);
    }

    if (!isWritable(baseDir.toPath())) {
      throw new IllegalStateException("Unwritable path " + baseDir);
    }

    if (repoDir.exists()) {
      log.info("git pull existing repo");
      try (var open = Git.open(repoDir.getAbsoluteFile())) {
        open.reset().setMode(ResetType.HARD).call();
        open.pull().call();
      }
    } else {
      log.info("git clone new repo");
      Git.cloneRepository()
          .setURI(FIREHOL_GITHUB_REPO)
          .setDirectory(repoDir.getAbsoluteFile())
          .call();
    }
  }

  // find all relevant files
  Set<Path> fetchFiles(String dir) throws IOException {
    try (Stream<Path> stream = Files.walk(Paths.get(dir), MAX_REPO_WALK_DEPTH)) {
      return stream
          .filter(file ->
              !Files.isDirectory(file)
                  && isUseFile(file)
          )
          .collect(Collectors.toSet());
    }
  }

  boolean isUseFile(Path path) {
    String name = path.toFile().getName();
    return (name.endsWith(".netset") || name.endsWith(".ipset"))
        // if no fileFilters, return all
        && (fileFilters.isEmpty() || fileFilters.stream().anyMatch(name::matches));
  }

  static void parseIpFile(IpTree tree, Path path) {
    log.info("file={}", path.toFile().getName());
    try {
      Files.lines(path)
          .filter(line -> !line.startsWith("#"))
          .forEach(line -> tree.add(line.trim()));
    } catch (IOException e) {
      log.error("error: path={}", path, e);
    }
  }
}