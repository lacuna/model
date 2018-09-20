package io.lacuna.model;

import io.lacuna.bifurcan.*;

import java.util.Comparator;

public class Layout<V> {

  public static class Node<V> {
    public final int row, col;
    public final List<V> path;

    public Node(int row, int col, List<V> path) {
      this.row = row;
      this.col = col;
      this.path = path;
    }

    @Override
    public int hashCode() {
      return ((row * 31) + col) ^ path.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      } else if (obj instanceof Node) {
        Node<V> n = (Node<V>) obj;
        return n.row == row
          && n.col == col
          && path.equals(n.path);
      } else {
        return false;
      }
    }
  }

  public final Dataflow<V> dataflow;
  public final IntMap<IntMap<Node<V>>> rows;
  public final Map<Node<V>, Node<V>> parent;
  public final List<Node<V>> navigation;

  private final Comparator<List<V>> comparator;

  public Layout(Dataflow<V> dataflow, Comparator<List<V>> comparator) {
    this(dataflow, List.of(new Node<>(0, 0, dataflow.path)), comparator);
  }

  private Layout(Dataflow<V> dataflow, List<Node<V>> navigation, Comparator<List<V>> comparator) {
    this.dataflow = dataflow;
    this.navigation = navigation;
    this.comparator = comparator;

    IntMap<IntMap<Node<V>>> rows = new IntMap<IntMap<Node<V>>>().linear();
    Map<Node<V>, Node<V>> parent = new Map<Node<V>, Node<V>>().linear();

    add(rows, parent, null, navigation.first());
    for (Node<V> curr : navigation) {
      if (curr.row <= 0) {
        // direct ancestors
        int row = curr.row - 1;
        for (List<V> path = curr.path.removeLast(); path.size() > 0; path = path.removeLast()) {
          // only add the full ancestry for a given column once
          if (!add(rows, parent, curr, new Node<>(row--, curr.col, path))) {
            break;
          }
        }

        // lexical ancestors
        IList<List<V>> deps = dataflow.dependencies(curr.path.last()).values();
        int col = curr.col + 1;
        for (List<V> l : Lists.sort(deps, comparator)) {
          add(rows, parent, curr, new Node<>(curr.row, col++, l));
        }
      }

      if (curr.row >= 0) {
        IList<List<V>> children = dataflow.graph.out(curr.path.last())
          .stream()
          .map(curr.path::addLast)
          .collect(Lists.linearCollector());
        int col = curr.col;
        for (List<V> l : Lists.sort(children, comparator)) {
          add(rows, parent, curr, new Node<>(curr.row + 1, col++, l));
        }
      }
    }

    this.rows = rows.mapValues((k, v) -> v.forked()).forked();
    this.parent = parent.forked();
  }

  public int minRow() {
    return rows.first().key().intValue();
  }

  public int maxRow() {
    return rows.last().key().intValue();
  }

  public Layout<V> up() {
    Node<V> n = navigation.last();
    return moveTo(n.row, n.col - 1);
  }

  public Layout<V> down() {
    Node<V> n = navigation.last();
    return moveTo(n.row, n.col + 1);
  }

  public Layout<V> left() {
    Node<V> n = navigation.last();
    return moveTo(n.row - 1, n.col);
  }

  public Layout<V> right() {
    Node<V> n = navigation.last();
    return moveTo(n.row + 1, n.col);
  }

  public Layout<V> recenter() {
    return new Layout<>(dataflow.select(navigation.last().path), comparator);
  }

  ///

  private Layout<V> moveTo(int row, int col) {
    List<Node<V>> navigation = new List<Node<V>>().linear();

    row = Math.max(minRow(), Math.min(maxRow(), row));
    Node<V> curr = rows.get(row).get().floor(col).value();
    if (curr == this.navigation.last()) {
      return this;
    }

    while (curr != null) {
      navigation.addFirst(curr);
      curr = parent.get(curr).get();
    }

    return new Layout<V>(dataflow, navigation.forked(), comparator);
  }

  ///

  private static <V> boolean add(IntMap<IntMap<Node<V>>> rows, Map<Node<V>, Node<V>> parent, Node<V> curr, Node<V> child) {
    IntMap<Node<V>> row = rows.getOrCreate((long) child.row, () -> new IntMap<Node<V>>().linear());
    parent.put(child, curr);
    long size = row.size();
    row.put(child.col, child);
    return size != row.size();
  }

}
