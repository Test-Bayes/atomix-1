/*
 * Copyright 2015 the original author or authors.
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
package io.atomix.protocols.raft.server.state;

import io.atomix.protocols.raft.RaftQuery;
import io.atomix.protocols.raft.error.RaftError;
import io.atomix.protocols.raft.error.RaftException;
import io.atomix.protocols.raft.protocol.AppendRequest;
import io.atomix.protocols.raft.protocol.AppendResponse;
import io.atomix.protocols.raft.protocol.InstallRequest;
import io.atomix.protocols.raft.protocol.InstallResponse;
import io.atomix.protocols.raft.protocol.OperationResponse;
import io.atomix.protocols.raft.protocol.QueryRequest;
import io.atomix.protocols.raft.protocol.QueryResponse;
import io.atomix.protocols.raft.protocol.RaftResponse;
import io.atomix.protocols.raft.server.RaftServer;
import io.atomix.protocols.raft.server.storage.Indexed;
import io.atomix.protocols.raft.server.storage.LogReader;
import io.atomix.protocols.raft.server.storage.LogWriter;
import io.atomix.protocols.raft.server.storage.entry.Entry;
import io.atomix.protocols.raft.server.storage.entry.QueryEntry;
import io.atomix.protocols.raft.server.storage.snapshot.Snapshot;
import io.atomix.protocols.raft.server.storage.snapshot.SnapshotWriter;
import io.atomix.util.serializer.KryoNamespaces;
import io.atomix.util.serializer.Serializer;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Passive state.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
class PassiveState extends ReserveState {
  private final Map<Long, Snapshot> pendingSnapshots = new HashMap<>();
  private int nextSnapshotOffset;

  public PassiveState(ServerContext context) {
    super(context);
  }

  @Override
  public RaftServer.State type() {
    return RaftServer.State.PASSIVE;
  }

  @Override
  public CompletableFuture<ServerState> open() {
    return super.open()
        .thenRun(this::truncateUncommittedEntries)
        .thenApply(v -> this);
  }

  /**
   * Truncates uncommitted entries from the log.
   */
  private void truncateUncommittedEntries() {
    if (type() == RaftServer.State.PASSIVE) {
      final LogWriter writer = context.getLogWriter();
      writer.lock();
      try {
        writer.truncate(context.getCommitIndex());
      } finally {
        writer.unlock();
      }
    }
  }

  @Override
  public CompletableFuture<AppendResponse> append(final AppendRequest request) {
    context.checkThread();
    logRequest(request);
    updateTermAndLeader(request.term(), request.leader());

    return CompletableFuture.completedFuture(logResponse(handleAppend(request)));
  }

  /**
   * Handles an append request.
   */
  protected AppendResponse handleAppend(AppendRequest request) {
    // If the request term is less than the current term then immediately
    // reply false and return our current term. The leader will receive
    // the updated term and step down.
    if (request.term() < context.getTerm()) {
      LOGGER.debug("{} - Rejected {}: request term is less than the current term ({})", context.getCluster().member().id(), request, context.getTerm());
      return AppendResponse.builder()
          .withStatus(RaftResponse.Status.OK)
          .withTerm(context.getTerm())
          .withSucceeded(false)
          .withLogIndex(context.getLogWriter().lastIndex())
          .build();
    } else {
      return checkPreviousEntry(request);
    }
  }

  /**
   * Checks the previous entry in the append request for consistency.
   */
  protected AppendResponse checkPreviousEntry(AppendRequest request) {
    final long lastIndex = context.getLogWriter().lastIndex();
    if (request.logIndex() != 0 && request.logIndex() > lastIndex) {
      LOGGER.debug("{} - Rejected {}: Previous index ({}) is greater than the local log's last index ({})", context.getCluster().member().id(), request, request.logIndex(), lastIndex);
      return AppendResponse.builder()
          .withStatus(RaftResponse.Status.OK)
          .withTerm(context.getTerm())
          .withSucceeded(false)
          .withLogIndex(lastIndex)
          .build();
    }
    return appendEntries(request);
  }

  /**
   * Appends entries to the local log.
   */
  protected AppendResponse appendEntries(AppendRequest request) {
    // Get the last entry index or default to the request log index.
    long lastEntryIndex = request.logIndex();
    if (!request.entries().isEmpty()) {
      lastEntryIndex = request.entries().get(request.entries().size() - 1).index();
    }

    // Ensure the commitIndex is not increased beyond the index of the last entry in the request.
    long commitIndex = Math.max(context.getCommitIndex(), Math.min(request.commitIndex(), lastEntryIndex));

    // Get the server log reader/writer.
    final LogReader reader = context.getLogReader();
    final LogWriter writer = context.getLogWriter();

    // If the request entries are non-empty, write them to the log.
    if (!request.entries().isEmpty()) {
      writer.lock();
      try {
        for (Indexed<? extends Entry> entry : request.entries()) {
          // If the entry index is greater than the commitIndex, break the loop.
          if (entry.index() > commitIndex) {
            break;
          }

          // Read the existing entry from the log. If the entry does not exist in the log,
          // append it. If the entry's term is different than the term of the entry in the log,
          // overwrite the entry in the log. This will force the log to be truncated if necessary.
          Indexed<? extends Entry<?>> existing = reader.get(entry.index());
          if (existing == null || existing.term() != entry.term()) {
            writer.append(entry);
            LOGGER.debug("{} - Appended {}", context.getCluster().member().id(), entry);
          }
        }
      } finally {
        writer.unlock();
      }
    }

    // Update the context commit and global indices.
    long previousCommitIndex = context.getCommitIndex();
    context.setCommitIndex(commitIndex);

    if (context.getCommitIndex() > previousCommitIndex) {
      LOGGER.trace("{} - Committed entries up to index {}", context.getCluster().member().id(), commitIndex);
    }

    // Apply commits to the state machine in batch.
    context.getStateMachine().applyAll(context.getCommitIndex());

    return AppendResponse.builder()
        .withStatus(RaftResponse.Status.OK)
        .withTerm(context.getTerm())
        .withSucceeded(true)
        .withLogIndex(lastEntryIndex)
        .build();
  }

  @Override
  public CompletableFuture<QueryResponse> query(QueryRequest request) {
    context.checkThread();
    logRequest(request);

    // If the query was submitted with sequential read consistency, attempt to apply the query to the local state machine.
    if (request.consistency() == RaftQuery.ConsistencyLevel.SEQUENTIAL) {

      // If this server has not yet applied entries up to the client's session ID, forward the
      // query to the leader. This ensures that a follower does not tell the client its session
      // doesn't exist if the follower hasn't had a chance to see the session's registration entry.
      if (context.getStateMachine().getLastApplied() < request.session()) {
        LOGGER.trace("{} - State out of sync, forwarding query to leader", context.getCluster().member().id());
        return queryForward(request);
      }

      // If the commit index is not in the log then we've fallen too far behind the leader to perform a local query.
      // Forward the request to the leader.
      if (context.getLogWriter().lastIndex() < context.getCommitIndex()) {
        LOGGER.trace("{} - State out of sync, forwarding query to leader", context.getCluster().member().id());
        return queryForward(request);
      }

      final Indexed<QueryEntry> entry = new Indexed<>(
          request.index(),
          context.getTerm(),
          new QueryEntry(
              System.currentTimeMillis(),
              request.session(),
              request.sequence(),
              request.bytes()), 0);

      return applyQuery(entry).thenApply(this::logResponse);
    } else {
      return queryForward(request);
    }
  }

  /**
   * Forwards the query to the leader.
   */
  private CompletableFuture<QueryResponse> queryForward(QueryRequest request) {
    if (context.getLeader() == null) {
      return CompletableFuture.completedFuture(logResponse(QueryResponse.builder()
          .withStatus(RaftResponse.Status.ERROR)
          .withError(RaftError.Type.NO_LEADER_ERROR)
          .build()));
    }

    LOGGER.trace("{} - Forwarding {}", context.getCluster().member().id(), request);
    return forward(request, context.getProtocolDispatcher()::query)
        .exceptionally(error -> QueryResponse.builder()
            .withStatus(RaftResponse.Status.ERROR)
            .withError(RaftError.Type.NO_LEADER_ERROR)
            .build())
        .thenApply(this::logResponse);
  }

  /**
   * Performs a local query.
   */
  protected CompletableFuture<QueryResponse> queryLocal(Indexed<QueryEntry> entry) {
    return applyQuery(entry);
  }

  /**
   * Applies a query to the state machine.
   */
  protected CompletableFuture<QueryResponse> applyQuery(Indexed<QueryEntry> entry) {
    // In the case of the leader, the state machine is always up to date, so no queries will be queued and all query
    // indexes will be the last applied index.
    CompletableFuture<QueryResponse> future = new CompletableFuture<>();
    context.getStateMachine().<OperationResult>apply(entry).whenComplete((result, error) -> {
      completeOperation(result, QueryResponse.builder(), error, future);
    });
    return future;
  }

  /**
   * Completes an operation.
   */
  protected <T extends OperationResponse> void completeOperation(OperationResult result, OperationResponse.Builder<?, T> builder, Throwable error, CompletableFuture<T> future) {
    if (isOpen()) {
      if (result != null) {
        builder.withIndex(result.index);
        builder.withEventIndex(result.eventIndex);
        if (result.result instanceof Exception) {
          error = (Exception) result.result;
        }
      }

      if (error == null) {
        future.complete(builder.withStatus(RaftResponse.Status.OK)
            .withResult(result != null ? result.result : null)
            .build());
      } else if (error instanceof CompletionException && error.getCause() instanceof RaftException) {
        future.complete(builder.withStatus(RaftResponse.Status.ERROR)
            .withError(((RaftException) error.getCause()).getType())
            .build());
      } else if (error instanceof RaftException) {
        future.complete(builder.withStatus(RaftResponse.Status.ERROR)
            .withError(((RaftException) error).getType())
            .build());
      } else {
        LOGGER.warn("An unexpected error occurred: {}", error);
        future.complete(builder.withStatus(RaftResponse.Status.ERROR)
            .withError(RaftError.Type.INTERNAL_ERROR)
            .build());
      }
    }
  }

  @Override
  public CompletableFuture<InstallResponse> install(InstallRequest request) {
    context.checkThread();
    logRequest(request);
    updateTermAndLeader(request.term(), request.leader());

    // If the request is for a lesser term, reject the request.
    if (request.term() < context.getTerm()) {
      return CompletableFuture.completedFuture(logResponse(InstallResponse.builder()
          .withStatus(RaftResponse.Status.ERROR)
          .withError(RaftError.Type.ILLEGAL_MEMBER_STATE_ERROR)
          .build()));
    }

    // Get the pending snapshot for the associated snapshot ID.
    Snapshot pendingSnapshot = pendingSnapshots.get(request.id());

    // If a snapshot is currently being received and the snapshot versions don't match, simply
    // close the existing snapshot. This is a naive implementation that assumes that the leader
    // will be responsible in sending the correct snapshot to this server. Leaders must dictate
    // where snapshots must be sent since entries can still legitimately exist prior to the snapshot,
    // and so snapshots aren't simply sent at the beginning of the follower's log, but rather the
    // leader dictates when a snapshot needs to be sent.
    if (pendingSnapshot != null && request.index() != pendingSnapshot.index()) {
      pendingSnapshot.close();
      pendingSnapshot.delete();
      pendingSnapshot = null;
      nextSnapshotOffset = 0;
    }

    // If there is no pending snapshot, create a new snapshot.
    if (pendingSnapshot == null) {
      // For new snapshots, the initial snapshot offset must be 0.
      if (request.offset() > 0) {
        return CompletableFuture.completedFuture(logResponse(InstallResponse.builder()
            .withStatus(RaftResponse.Status.ERROR)
            .withError(RaftError.Type.ILLEGAL_MEMBER_STATE_ERROR)
            .build()));
      }

      pendingSnapshot = context.getSnapshotStore().createSnapshot(request.id(), request.index());
      nextSnapshotOffset = 0;
    }

    // If the request offset is greater than the next expected snapshot offset, fail the request.
    if (request.offset() > nextSnapshotOffset) {
      return CompletableFuture.completedFuture(logResponse(InstallResponse.builder()
          .withStatus(RaftResponse.Status.ERROR)
          .withError(RaftError.Type.ILLEGAL_MEMBER_STATE_ERROR)
          .build()));
    }

    // Write the data to the snapshot.
    try (SnapshotWriter writer = pendingSnapshot.writer(Serializer.using(KryoNamespaces.RAFT))) {
      writer.write(request.data());
    }

    // If the snapshot is complete, store the snapshot and reset state, otherwise update the next snapshot offset.
    if (request.complete()) {
      pendingSnapshot.persist().complete();
      pendingSnapshots.remove(request.id());
      nextSnapshotOffset = 0;
    } else {
      nextSnapshotOffset++;
    }

    return CompletableFuture.completedFuture(logResponse(InstallResponse.builder()
        .withStatus(RaftResponse.Status.OK)
        .build()));
  }

  @Override
  public CompletableFuture<Void> close() {
    for (Snapshot pendingSnapshot : pendingSnapshots.values()) {
      pendingSnapshot.close();
      pendingSnapshot.delete();
    }
    return super.close();
  }

}
