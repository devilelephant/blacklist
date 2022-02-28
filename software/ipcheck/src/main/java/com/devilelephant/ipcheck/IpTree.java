package com.devilelephant.ipcheck;

import com.github.x25.net.tree.IpSubnetTree;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Proxies an underlying {@link IpSubnetTree} instance that simplifies insertion.
 */
public class IpTree {

  private final IpSubnetTree<String> tree;

  public IpTree() {
    tree = new IpSubnetTree<>();
    tree.setDefaultValue("");
  }

  public String find(String ip) {
    return tree.find(ip);
  }

  public void load(Path path) {
    try {
      Files.lines(path)
          .filter(line -> !line.startsWith("#") && !line.isBlank())
          .forEach(line -> add(line.trim()));
    } catch (Exception e) {
      throw new IllegalStateException("Unexpected error loading tree", e);
    }
  }

  public void add(String ip) {
    String ipCidr = ip;
    // fix IpSubnetTree bug
    if (ip.indexOf('/') == -1) {
      ipCidr = ip + "/32";
    }
    tree.insert(ipCidr, ip);
  }
}
