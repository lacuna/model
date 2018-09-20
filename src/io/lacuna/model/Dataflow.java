package io.lacuna.model;

import io.lacuna.bifurcan.*;
import io.lacuna.bifurcan.utils.Iterators;

import java.util.Objects;
import java.util.function.Function;

import static io.lacuna.bifurcan.Graphs.bfsVertices;

/**
 * An immutable representation of
 *
 * @author ztellman
 */
public class Dataflow<V> {

  /**
   * A graph representing every edge we've added to the graph.
   */
  public final DirectedGraph<V, Void> graph;

  /**
   * Our current selected path through the graph.
   */
  public final List<V> path;

  /**
   * A mapping of lexical references onto their current evaluation path through our graph.
   */
  public final Map<String, List<V>> bindings;

  private final IGraph<Object, Integer> references;
  private final Function<V, ISet<String>> dependencies;

  private Dataflow(
    DirectedGraph<V, Void> graph,
    IGraph<Object, Integer> references,
    List<V> path,
    Map<String, List<V>> bindings,
    Function<V, ISet<String>> dependencies) {

    this.graph = graph;
    this.references = references;
    this.path = path;
    this.bindings = bindings;
    this.dependencies = dependencies;
  }

  public Dataflow(Function<V, ISet<String>> dependencies) {
    this(
      new DirectedGraph<>(),
      new DirectedAcyclicGraph<>(),
      new List<>(),
      new Map<>(),
      dependencies);
  }

  /**
   * @return the lexical dependencies of {@code node}
   */
  public IMap<String, List<V>> dependencies(V node) {
    return dependencies.apply(node).zip(bindings);
  }

  /**
   * @return a topologically sorted list of the lexical dependencies of our current path
   */
  public List<String> pathDependencies() {
    return Iterators.toStream(bfsVertices(path.last(), references::in).iterator())
      .filter(x -> x instanceof String)
      .map(s -> (String) s)
      .collect(Lists.collector());
  }

  /**
   * @return appends {@code node} to the current path
   * @throws IllegalArgumentException if the node depends on lexical references which aren't currently defined
   */
  public Dataflow<V> extend(V node) {

    IGraph<Object, Integer> references = this.references.linear();
    add(references, path.last(), node);

    ISet<String> deps = dependencies.apply(node);
    if (!bindings.containsAll(deps)) {
      throw new IllegalArgumentException("undefined lexical bindings: " + bindings.keys().difference(deps));
    }
    deps.forEach(dep -> add(references, node, dep));

    return new Dataflow<V>(
      graph.link(path.last(), node),
      references.forked(),
      path.addLast(node),
      bindings,
      dependencies);
  }

  /**
   * @return an updated Dataflow object with {@code path} selected
   * @throws IllegalArgumentException if the path assumes edges that don't already exist in the graph
   */
  public Dataflow<V> select(IList<V> path) {
    validatePath(path);

    IGraph<Object, Integer> references = this.references.linear();
    remove(references, this.path);
    add(references, path);

    return new Dataflow<>(
      graph,
      references.forked(),
      List.from(path),
      bindings,
      dependencies);
  }

  /**
   * @return binds the lexical reference {@code name} to the specified {@code path}
   * @throws DirectedAcyclicGraph.CycleException if the specified path causes a cyclic dependency with another binding
   */
  public Dataflow<V> bind(String name, List<V> path) {
    return bind(new LinearMap<String, List<V>>().put(name, path));
  }

  /**
   * @param bindings a collection of bindings of lexical bindings onto paths
   * @throws DirectedAcyclicGraph.CycleException if the specified paths cause a cyclic dependency with other bindings
   */
  public Dataflow<V> bind(IMap<String, List<V>> bindings) {
    validatePath(path);

    IGraph<Object, Integer> references = this.references.linear();

    for (IEntry<String, List<V>> e : bindings) {
      bindings.get(e.key()).ifPresent(l -> {
        remove(references, l);
        remove(references, l.last(), e.key());
      });
    }

    for (IEntry<String, List<V>> e : bindings) {
      add(references, e.value());
      add(references, e.value().last(), e.key());
    }

    List<V> p = List.from(path);
    return new Dataflow<>(
      graph,
      references.forked(),
      p,
      this.bindings.union(bindings),
      dependencies);
  }

  /**
   * Replaces every instance of {@code a} with {@code b}, effectively overwriting the existing history.  In almost every
   * case, we should prefer {@code reroute()}.
   */
  public Dataflow<V> replace(V a, V b) {
    return new Dataflow<>(
      graph.replace(a, b),
      references.replace(a, b),
      replace(path, a, b),
      bindings.mapValues((k, l) -> replace(l, a, b)),
      dependencies);
  }

  /**
   * Every path passing through {@code a} now also passes through {@code b}.  The current path and bindings are all
   * updated to prefer this new route.
   *
   * @throws DirectedAcyclicGraph.CycleException if the updated bindings create a cyclic dependency
   */
  public Dataflow<V> reroute(V a, V b) {
    DirectedGraph<V, Void> g = graph.linear();
    graph.in(a).forEach(v -> g.link(v, b));
    graph.out(a).forEach(v -> g.link(b, v));

    return new Dataflow<>(
      g.forked(),
      references.replace(a, b),
      replace(path, a, b),
      bindings.mapValues((k, l) -> replace(l, a, b)),
      dependencies);
  }

  /**
   * Every path connecting {@code a} and {@code b} now also passes through {@code v} as an intermediate step.  The
   * current path and all bindings are updated to prefer this new route.
   *
   * @throws DirectedAcyclicGraph.CycleException if the updated bindings create a cyclic dependency
   */
  public Dataflow<V> interpose(V a, V b, V v) {
    IGraph<Object, Integer> references = this.references;
    if (references.out(a).contains(b)) {
      int e = references.edge(a, b);
      references = references.linear()
        .unlink(a, b)
        .link(a, v, e)
        .link(v, b, e)
        .forked();
    }

    return new Dataflow<>(
      graph.link(a, v).link(v, b),
      references,
      interpose(path, a, b, v),
      bindings.mapValues((k, l) -> interpose(l, a, b, v)),
      dependencies);
  }

  ///

  private static void add(IGraph<Object, Integer> references, IList path) {
    for (int i = 1; i < path.size(); i++) {
      add(references, path.nth(i - 1), path.nth(i));
    }
  }

  private static void remove(IGraph<Object, Integer> references, IList path) {
    for (int i = 1; i < path.size(); i++) {
      remove(references, path.nth(i - 1), path.nth(i));
    }
  }

  private static void add(IGraph<Object, Integer> references, Object a, Object b) {
    references.link(a, b, 1, (x, y) -> x + y);
  }

  private static void remove(IGraph<Object, Integer> references, Object a, Object b) {
    int e = references.edge(a, b);
    if (e == 1) {
      references.unlink(a, b);
    } else {
      references.link(a, b, e - 1);
    }
  }

  private static <V> List<V> interpose(List<V> l, V a, V b, V v) {
    for (int i = 1; i < l.size(); i++) {
      if (Objects.equals(l.nth(i), b) && Objects.equals(l.nth(i - 1), a)) {
        return (List<V>) l.slice(0, i).addLast(v).concat(l.slice(i, l.size()));
      }
    }
    return l;
  }

  private static <V> List<V> replace(List<V> l, V a, V b) {
    for (int i = 0; i < l.size(); i++) {
      if (Objects.equals(l.nth(i), a)) {
        return l.set(i, b);
      }
    }
    return l;
  }

  private void validatePath(IList<V> path) {
    if (graph.in(path.first()).size() != 0) {
      throw new IllegalArgumentException("path must begin at the top of the graph");
    }

    for (int i = 1; i < path.size(); i++) {
      if (!graph.in(path.nth(i)).contains(path.nth(i - 1))) {
        throw new IllegalArgumentException("every edge of the path must already exist in the graph");
      }
    }
  }

}
