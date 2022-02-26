package devilelephant.ipblacklist;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.PostConstruct;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
public class Controller {

  private final IpTreeLoader ipTreeLoader;
  private IpTree tree;
  private final ObjectMapper mapper;
  private final Lock lock = new ReentrantLock();

  @Autowired
  public Controller(IpTreeLoader ipTreeLoader) {
    this.ipTreeLoader = ipTreeLoader;
    this.tree = new IpTree();
    this.mapper = new ObjectMapper();
  }

  @PostConstruct
  public void reloadTree() {
    try {
      lock.lock();
      log.info("start");
      IpTree newTree = new IpTree();
      ipTreeLoader.load(newTree);
      tree = newTree;
      log.info("finished");
    } finally {
      lock.unlock();
    }
  }

  // TODO: create some cron job to fire this automatically
  @PostMapping(path = "/blacklist/api/reload")
  public ResponseEntity<String> refreshIps() {
    try {
      if (lock.tryLock()) {
        new Thread(this::reloadTree).start();
      } else {
        return new ResponseEntity<>("already currently loading", HttpStatus.LOCKED);
      }
    } finally {
      lock.unlock();
    }
    return new ResponseEntity<>(HttpStatus.OK);
  }

  @SneakyThrows
  @GetMapping(
      path = "/blacklist/api",
      produces = {MediaType.APPLICATION_JSON_VALUE}
  )
  public ResponseEntity<String> ipCheck(@RequestParam(name = "ip") final String ip) {
    try {
      var result = tree.find(ip);
      if (result == null) {
        result = "";
      }
      var output = mapper.writeValueAsString(Map.of("ip", ip, "result", result));
      log.info(output);

      return new ResponseEntity<>(output, result.isEmpty() ? HttpStatus.NOT_FOUND : HttpStatus.OK);
    } catch (IllegalArgumentException | JsonProcessingException e) {
      var output = mapper.writeValueAsString( Map.of("ip", ip == null || ip.isEmpty() ? "<missing>" : ip, "result", e.getMessage()));
      return new ResponseEntity<>(output, HttpStatus.BAD_REQUEST);
    }
  }
}
