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
package io.atomix.util.buffer;

/**
 * Unpooled buffer allocator.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public abstract class UnpooledAllocator implements BufferAllocator {

  /**
   * Returns the maximum buffer capacity.
   *
   * @return The maximum buffer capacity.
   */
  protected abstract long maxCapacity();

  @Override
  public Buffer allocate() {
    return allocate(4096, maxCapacity());
  }

  @Override
  public Buffer allocate(long capacity) {
    return allocate(capacity, capacity);
  }

}
