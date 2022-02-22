package devilelephant.ipblacklist;

import static java.nio.file.Files.newBufferedWriter;
import static org.springframework.util.ReflectionUtils.findField;
import static org.springframework.util.ReflectionUtils.getField;

import com.github.x25.net.tree.IpSubnetTree;
import com.github.x25.net.tree.radix.RadixInt32Tree;
import com.github.x25.net.tree.radix.node.Node;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import lombok.extern.slf4j.Slf4j;

/**
 * Proxies an underlying {@link IpSubnetTree} instance that simplifies insertion.
 */
@Slf4j
public class IpTree {

  private final IpSubnetTree<String> tree;

  public IpTree() {
    tree = new IpSubnetTree<>();
    tree.setDefaultValue("");
  }

  /**
   * Return a set of zero or more names associated with the given ip address.
   */
  public String find(String ip) {
    return tree.find(ip);
  }

  public void add(String ip) {
    String ipCidr = ip;
    // fix IpSubnetTree bug
    if (ip.indexOf('/') == -1) {
      ipCidr = ip + "/32";
    }
    tree.insert(ipCidr, ip);
  }

  public void walk(File target) {
    var node = findTreeRoot();

    if (!target.delete()) {
      log.warn("Failed to delete target file={}", target.getName());
    }
    try (var w = newBufferedWriter(target.toPath())) {
      walkNode(node, w);

    } catch (IOException e) {
      log.error("failed to walk tree", e);
    }
  }

  private void walkNode(Node<String> node, BufferedWriter w) throws IOException {
    if (node.getLeft() != null) {
      walkNode(node.getLeft(), w);
    }
    if (node.getRight() != null) {
      walkNode(node.getRight(), w);
    }
    if (node.getValue() != null) {
      w.write(node.getValue());
      w.write('\n');
    }
  }

  /**
   * Function used during testing to access root node for tree walking.
   */
  @SuppressWarnings({"ConstantConditions", "rawtypes", "unchecked"})
  private Node<String> findTreeRoot() {
    Field treeField = findField(IpSubnetTree.class, "tree");
    treeField.setAccessible(true);
    RadixInt32Tree radixTree = (RadixInt32Tree) getField(treeField, tree);
    Field nodeField = findField(RadixInt32Tree.class, "root");
    nodeField.setAccessible(true);
    return (Node<String>) getField(nodeField, radixTree);
  }

}
