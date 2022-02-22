package devilelephant.ipblacklist;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties
@Slf4j
public class Config {

  // root path to clone repo locally
  @Value("${app.working_dir}")
  private String workingDir;

  @Value("${app.filters}")
  private Set<String> fileFilters;

  @Bean
  public Path workingPath() {
    return Path.of(workingDir);
  }

  @Bean
  public IpTreeLoader getIpTreeLoader() {
    var filters = fileFilters.stream()
        .map(f -> f.indexOf('*') == -1 ? String.format(".*%s.*", f) : f)
        .collect(Collectors.toSet());
    for (String f : filters) {
      log.info("Filter regex={}", f);
    }
    return new IpTreeLoader(workingPath(), filters);
  }
}
