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
package io.atomix.copycat.coordination.state;

import io.atomix.catalog.client.Command;
import io.atomix.catalog.client.Operation;
import io.atomix.catalyst.buffer.BufferInput;
import io.atomix.catalyst.buffer.BufferOutput;
import io.atomix.catalyst.serializer.CatalystSerializable;
import io.atomix.catalyst.serializer.SerializeWith;
import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.util.BuilderPool;

/**
 * Group commands.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class GroupCommands {

  private GroupCommands() {
  }

  /**
   * Abstract topic command.
   */
  public static abstract class GroupCommand<V> implements Command<V>, CatalystSerializable {
    @Override
    public void writeObject(BufferOutput buffer, Serializer serializer) {

    }

    @Override
    public void readObject(BufferInput buffer, Serializer serializer) {

    }

    /**
     * Base map command builder.
     */
    public static abstract class Builder<T extends Builder<T, U, V>, U extends GroupCommand<V>, V> extends Command.Builder<T, U, V> {
      protected Builder(BuilderPool<T, U> pool) {
        super(pool);
      }
    }
  }

  /**
   * Join command.
   */
  @SerializeWith(id=520)
  public static class Join extends GroupCommand<Void> {

    /**
     * Returns a new join command builder.
     *
     * @return A new join command builder.
     */
    @SuppressWarnings("unchecked")
    public static Builder builder() {
      return Operation.builder(Builder.class, Builder::new);
    }

    /**
     * Join command builder.
     */
    public static class Builder extends GroupCommand.Builder<Builder, Join, Void> {
      public Builder(BuilderPool<Builder, Join> pool) {
        super(pool);
      }

      @Override
      protected Join create() {
        return new Join();
      }
    }
  }

  /**
   * Leave command.
   */
  @SerializeWith(id=521)
  public static class Leave extends GroupCommand<Void> {

    /**
     * Returns a new leave command builder.
     *
     * @return A new leave command builder.
     */
    @SuppressWarnings("unchecked")
    public static Builder builder() {
      return Operation.builder(Builder.class, Builder::new);
    }

    /**
     * Leave command builder.
     */
    public static class Builder extends GroupCommand.Builder<Builder, Leave, Void> {
      public Builder(BuilderPool<Builder, Leave> pool) {
        super(pool);
      }

      @Override
      protected Leave create() {
        return new Leave();
      }
    }
  }

  /**
   * Execute command.
   */
  @SerializeWith(id=522)
  public static class Execute extends GroupCommand<Void> {

    /**
     * Returns a new execute command builder.
     *
     * @return The execute command builder.
     */
    @SuppressWarnings("unchecked")
    public static Builder builder() {
      return Operation.builder(Builder.class, Builder::new);
    }

    private Runnable callback;

    /**
     * Returns the execute callback.
     *
     * @return The execute callback.
     */
    public Runnable callback() {
      return callback;
    }

    @Override
    public void writeObject(BufferOutput buffer, Serializer serializer) {
      serializer.writeObject(callback, buffer);
    }

    @Override
    public void readObject(BufferInput buffer, Serializer serializer) {
      callback = serializer.readObject(buffer);
    }

    /**
     * Execute command builder.
     */
    public static class Builder extends GroupCommand.Builder<Builder, Execute, Void> {

      public Builder(BuilderPool<Builder, Execute> pool) {
        super(pool);
      }

      /**
       * Sets the execute command message.
       *
       * @param callback The callback.
       * @return The execute command builder.
       */
      public Builder withCallback(Runnable callback) {
        command.callback = callback;
        return this;
      }

      @Override
      protected Execute create() {
        return new Execute();
      }
    }
  }

}
