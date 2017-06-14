/*
 * Copyright 2017-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.protocols.raft.session.impl;

import io.atomix.cluster.NodeId;
import io.atomix.protocols.raft.client.CommunicationStrategy;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Address selectors.
 */
public final class NodeSelectorManager {
  private final Set<NodeSelector> selectors = new CopyOnWriteArraySet<>();
  private volatile NodeId leader;
  private volatile Collection<NodeId> servers = Collections.emptyList();

  /**
   * Returns the current cluster leader.
   *
   * @return The current cluster leader.
   */
  public NodeId leader() {
    return leader;
  }

  /**
   * Returns the set of servers in the cluster.
   *
   * @return The set of servers in the cluster.
   */
  public Collection<NodeId> servers() {
    return servers;
  }

  /**
   * Creates a new address selector.
   *
   * @param selectionStrategy The server selection strategy.
   * @return A new address selector.
   */
  public NodeSelector createSelector(CommunicationStrategy selectionStrategy) {
    NodeSelector selector = new NodeSelector(leader, servers, selectionStrategy, this);
    selectors.add(selector);
    return selector;
  }

  /**
   * Resets all child selectors.
   */
  public void resetAll() {
    selectors.forEach(NodeSelector::reset);
  }

  /**
   * Resets all child selectors.
   *
   * @param leader  The current cluster leader.
   * @param servers The collection of all active servers.
   */
  public void resetAll(NodeId leader, Collection<NodeId> servers) {
    this.leader = leader;
    this.servers = new LinkedList<>(servers);
    selectors.forEach(s -> s.reset(leader, servers));
  }

  /**
   * Removes the given selector.
   *
   * @param selector The address selector to remove.
   */
  void remove(NodeSelector selector) {
    selectors.remove(selector);
  }

}
