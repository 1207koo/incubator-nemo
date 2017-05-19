/*
 * Copyright (C) 2017 Seoul National University
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.snu.vortex.compiler.ir;

import edu.snu.vortex.utils.dag.DAG;
import edu.snu.vortex.utils.dag.DAGBuilder;

import java.util.*;
import java.util.function.IntPredicate;

/**
 * IRVertex that contains a partial DAG that is iterative.
 */
public final class LoopVertex extends IRVertex {
  private final DAGBuilder<IRVertex, IREdge> builder; // Contains DAG information
  private final String compositeTransformFullName;

  private final Map<IRVertex, Set<IREdge>> dagIncomingEdges; // for the initial iteration
  private final Map<IRVertex, Set<IREdge>> iterativeIncomingEdges; // Edges from previous iterations connected internal.
  private final Map<IRVertex, Set<IREdge>> nonIterativeIncomingEdges; // Edges from outside previous iterations.
  private final Map<IRVertex, Set<IREdge>> dagOutgoingEdges; // for the final iteration

  private Integer maxNumberOfIterations;
  private IntPredicate terminationCondition;

  /**
   * The LoopVertex constructor.
   * @param compositeTransformFullName full name of the composite transform.
   */
  public LoopVertex(final String compositeTransformFullName) {
    super();
    this.builder = new DAGBuilder<>();
    this.compositeTransformFullName = compositeTransformFullName;
    this.dagIncomingEdges = new HashMap<>();
    this.iterativeIncomingEdges = new HashMap<>();
    this.nonIterativeIncomingEdges = new HashMap<>();
    this.dagOutgoingEdges = new HashMap<>();
    this.maxNumberOfIterations = 1; // 1 is the default number of iterations.
    this.terminationCondition = (integer -> false); // nothing much yet.
  }

  @Override
  public LoopVertex getClone() {
    final LoopVertex newLoopVertex = new LoopVertex(compositeTransformFullName);

    // Copy all elements to the clone
    final DAG<IRVertex, IREdge> dagToCopy = this.getBuilder().build();
    dagToCopy.topologicalDo(v -> {
      newLoopVertex.getBuilder().addVertex(v, dagToCopy);
      dagToCopy.getIncomingEdgesOf(v).forEach(e -> newLoopVertex.getBuilder().connectVertices(e));
    });
    this.dagIncomingEdges.forEach(((v, es) -> es.forEach(newLoopVertex::addDagIncomingEdge)));
    this.iterativeIncomingEdges.forEach((v, es) -> es.forEach(newLoopVertex::addIterativeIncomingEdge));
    this.nonIterativeIncomingEdges.forEach((v, es) -> es.forEach(newLoopVertex::addNonIterativeIncomingEdge));
    this.dagOutgoingEdges.forEach(((v, es) -> es.forEach(newLoopVertex::addDagOutgoingEdge)));
    newLoopVertex.setMaxNumberOfIterations(maxNumberOfIterations);
    newLoopVertex.setTerminationCondition(terminationCondition);

    return newLoopVertex;
  }

  /**
   * @return DAGBuilder of the LoopVertex.
   */
  public DAGBuilder<IRVertex, IREdge> getBuilder() {
    return builder;
  }

  /**
   * @return the DAG of rthe LoopVertex
   */
  public DAG<IRVertex, IREdge> getDAG() {
    return builder.build();
  }

  /**
   * @return the full name of the composite transform.
   */
  public String getName() {
    return compositeTransformFullName;
  }

  /**
   * Adds the incoming edge of the contained DAG.
   * @param edge edge to add.
   */
  public void addDagIncomingEdge(final IREdge edge) {
    this.dagIncomingEdges.putIfAbsent(edge.getDst(), new HashSet<>());
    this.dagIncomingEdges.get(edge.getDst()).add(edge);
  }
  /**
   * @return incoming edges of the contained DAG.
   */
  public Map<IRVertex, Set<IREdge>> getDagIncomingEdges() {
    return this.dagIncomingEdges;
  }

  /**
   * Adds an iterative incoming edge, from the previous iteration, but connection internally.
   * @param edge edge to add.
   */
  public void addIterativeIncomingEdge(final IREdge edge) {
    this.iterativeIncomingEdges.putIfAbsent(edge.getDst(), new HashSet<>());
    this.iterativeIncomingEdges.get(edge.getDst()).add(edge);
  }
  /**
   * @return the iterative incoming edges inside the DAG.
   */
  public Map<IRVertex, Set<IREdge>> getIterativeIncomingEdges() {
    return this.iterativeIncomingEdges;
  }

  /**
   * Adds a non-iterative incoming edge, from outside the previous iteration.
   * @param edge edge to add.
   */
  public void addNonIterativeIncomingEdge(final IREdge edge) {
    this.nonIterativeIncomingEdges.putIfAbsent(edge.getDst(), new HashSet<>());
    this.nonIterativeIncomingEdges.get(edge.getDst()).add(edge);
  }

  /**
   * @return the non-iterative incoming edges of the LoopVertex.
   */
  public Map<IRVertex, Set<IREdge>> getNonIterativeIncomingEdges() {
    return this.nonIterativeIncomingEdges;
  }

  /**
   * Adds and outgoing edge of the contained DAG.
   * @param edge edge to add.
   */
  public void addDagOutgoingEdge(final IREdge edge) {
    this.dagOutgoingEdges.putIfAbsent(edge.getSrc(), new HashSet<>());
    this.dagOutgoingEdges.get(edge.getSrc()).add(edge);
  }
  /**
   * @return outgoing edges of the contained DAG.
   */
  public Map<IRVertex, Set<IREdge>> getDagOutgoingEdges() {
    return this.dagOutgoingEdges;
  }

  /**
   * Method for unrolling an iteration of the LoopVertex.
   * @param dagBuilder DAGBuilder to add the unrolled iteration to.
   * @return a LoopVertex with one less maximum iteration.
   */
  public LoopVertex unRollIteration(final DAGBuilder<IRVertex, IREdge> dagBuilder) {
    final HashMap<IRVertex, IRVertex> originalToNewIRVertex = new HashMap<>();
    final DAG<IRVertex, IREdge> dagToAdd = getDAG();

    decreaseMaxNumberOfIterations();

    // add the DAG and internal edges to the dagBuilder.
    dagToAdd.topologicalDo(irVertex -> {
      final IRVertex newIrVertex = irVertex.getClone();
      originalToNewIRVertex.putIfAbsent(irVertex, newIrVertex);

      dagBuilder.addVertex(newIrVertex, dagToAdd);
      dagToAdd.getIncomingEdgesOf(irVertex).forEach(edge -> {
        final IRVertex newSrc = originalToNewIRVertex.get(edge.getSrc());
        final IREdge newIrEdge = new IREdge(edge.getType(), newSrc, newIrVertex, edge.getCoder());
        IREdge.copyAttributes(edge, newIrEdge);
        dagBuilder.connectVertices(newIrEdge);
      });
    });

    // process DAG incoming edges.
    getDagIncomingEdges().forEach((dstVertex, irEdges) -> irEdges.forEach(edge -> {
      final IREdge newIrEdge = new IREdge(edge.getType(), edge.getSrc(), originalToNewIRVertex.get(dstVertex),
          edge.getCoder());
      IREdge.copyAttributes(edge, newIrEdge);
      dagBuilder.connectVertices(newIrEdge);
    }));

    if (loopTerminationConditionMet()) {
      // if termination condition met, we process the DAG outgoing edge.
      getDagOutgoingEdges().forEach((srcVertex, irEdges) -> irEdges.forEach(edge -> {
        final IREdge newIrEdge = new IREdge(edge.getType(), originalToNewIRVertex.get(srcVertex), edge.getDst(),
            edge.getCoder());
        IREdge.copyAttributes(edge, newIrEdge);
        dagBuilder.connectVertices(newIrEdge);
      }));
    }

    // process next iteration's DAG incoming edges
    this.getDagIncomingEdges().clear();
    this.nonIterativeIncomingEdges.forEach((dstVertex, irEdges) -> irEdges.forEach(this::addDagIncomingEdge));
    this.iterativeIncomingEdges.forEach((dstVertex, irEdges) -> irEdges.forEach(edge -> {
      final IREdge newIrEdge = new IREdge(edge.getType(), originalToNewIRVertex.get(edge.getSrc()), dstVertex,
          edge.getCoder());
      IREdge.copyAttributes(edge, newIrEdge);
      this.addDagIncomingEdge(newIrEdge);
    }));

    return this;
  }

  /**
   * @return whether or not the loop termination condition has been met.
   */
  public Boolean loopTerminationConditionMet() {
    return loopTerminationConditionMet(0);
  }
  /**
   * @param intPredicateInput input for the intPredicate of the loop termination condition.
   * @return whether or not the loop termination condition has been met.
   */
  public Boolean loopTerminationConditionMet(final Integer intPredicateInput) {
    return maxNumberOfIterations <= 0 || terminationCondition.test(intPredicateInput);
  }

  /**
   * Set the maximum number of iterations.
   * @param maxNum maximum number of iterations.
   */
  public void setMaxNumberOfIterations(final Integer maxNum) {
    this.maxNumberOfIterations = maxNum;
  }
  /**
   * increase the value of maximum number of iterations by 1.
   */
  public void increaseMaxNumberOfIterations() {
    this.maxNumberOfIterations++;
  }
  /**
   * decrease the value of maximum number of iterations by 1.
   */
  private void decreaseMaxNumberOfIterations() {
    this.maxNumberOfIterations--;
  }

  /**
   * Set the intPredicate termination condition for the LoopVertex.
   * @param terminationCondition the termination condition to set.
   */
  public void setTerminationCondition(final IntPredicate terminationCondition) {
    this.terminationCondition = terminationCondition;
  }

  @Override
  public String propertiesToJSON() {
    final StringBuilder sb = new StringBuilder();
    sb.append("{");
    sb.append(irVertexPropertiesToString());
    sb.append(", \"Remaining Iteration\": ");
    sb.append(this.maxNumberOfIterations);
    sb.append(", \"DAG\": ");
    sb.append(getDAG());
    sb.append("}");
    return sb.toString();
  }
}