/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.kuujo.raft.state;

import java.util.Set;

import net.kuujo.raft.ReplicationServiceEndpoint;
import net.kuujo.raft.StateMachine;
import net.kuujo.raft.log.Log;
import net.kuujo.raft.protocol.PingRequest;
import net.kuujo.raft.protocol.PollRequest;
import net.kuujo.raft.protocol.SubmitRequest;
import net.kuujo.raft.protocol.SyncRequest;

import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;

/**
 * A node state.
 *
 * @author Jordan Halterman
 */
public abstract class State {
  protected Vertx vertx;
  protected ReplicationServiceEndpoint endpoint;
  protected StateMachine stateMachine;
  protected Log log;
  protected StateContext context;

  /**
   * Sets the vertx instance.
   *
   * @param vertx
   *   A vertx instance.
   * @return
   *   The state instance.
   */
  public State setVertx(Vertx vertx) {
    this.vertx = vertx;
    return this;
  }

  /**
   * Sets the endpoint.
   *
   * @param endpoint
   *   An endpoint instance.
   * @return
   *   The state instance.
   */
  public State setEndpoint(ReplicationServiceEndpoint endpoint) {
    this.endpoint = endpoint;
    return this;
  }

  /**
   * Sets the state machine.
   *
   * @param stateMachine
   *   The state machine.
   * @return
   *   The state instance.
   */
  public State setStateMachine(StateMachine stateMachine) {
    this.stateMachine = stateMachine;
    return this;
  }

  /**
   * Sets the log.
   *
   * @param log
   *   A log instance.
   * @return
   *   The state instance.
   */
  public State setLog(Log log) {
    this.log = log;
    return this;
  }

  /**
   * Sets the state context.
   *
   * @param context
   *   A state context.
   * @return
   *   The state instance.
   */
  public State setContext(StateContext context) {
    this.context = context;
    return this;
  }

  /**
   * Starts up the state.
   *
   * @param doneHandler
   *   A handler to be called once the state is started up.
   */
  public abstract void startUp(Handler<Void> doneHandler);

  /**
   * Updates the state configuration.
   *
   * @param members
   *   A set of members in the cluster.
   */
  public abstract void configure(Set<String> members);

  /**
   * Executes a ping request.
   *
   * @param request
   *   The request to execute.
   */
  public abstract void ping(PingRequest request);

  /**
   * Executes a sync request.
   *
   * @param request
   *   The request to execute.
   */
  public abstract void sync(SyncRequest request);

  /**
   * Executes a poll request.
   *
   * @param request
   *   The request to execute.
   */
  public abstract void poll(PollRequest request);

  /**
   * Executes a submit command request.
   *
   * @param request
   *   The request to execute.
   */
  public abstract void submit(SubmitRequest request);

  /**
   * Tears down the state.
   *
   * @param doneHandler
   *   A handler to be called once the state is shut down.
   */
  public abstract void shutDown(Handler<Void> doneHandler);

}
