/*
 * Copyright 2019 Johannes Donath <johannesd@torchmind.com>
 * and other copyright owners as documented in the project's IP log.
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
package tv.dotstart.burner.protocol.util

import io.grpc.stub.StreamObserver
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Provides a stream observer implementation which permits the synchronous retrieval of the next
 * value, errors or stream completion.
 *
 * @author [Johannes Donath](mailto:johannesd@torchmind.com)
 * @date 09/09/2019
 */
class CyclicStreamObserver<R : Any> : StreamObserver<R> {

  /**
   * Identifies whether this stream has been completed (e.g. it has been completed by the server or
   * an error has been received).
   */
  val completed: Boolean
    get() = this.lock.withLock(this::completionReceived)

  /**
   * Allocates a new future (or retrieves the current future if it is still pending) which receives
   * the values and exceptions passed to this observer.
   *
   * When the channel is completed without returning a value, null is passed to the future instead.
   */
  val next: Future<R?>
    get() = this.lock.withLock {
      if (this.completionReceived) {
        throw IllegalStateException("Cannot allocate future: Stream has been completed")
      }

      var future = this.nextFuture
      if (future != null) {
        return@withLock future
      }

      future = CompletableFuture()
      this.nextFuture = future
      future
    }

  private val lock = ReentrantLock()
  private var completionReceived: Boolean = false
  private var nextFuture: CompletableFuture<R?>? = null

  override fun onNext(value: R): Unit = this.lock.withLock {
    val future = this.nextFuture
    this.nextFuture = null

    future?.complete(value)
  }

  override fun onError(ex: Throwable): Unit = this.lock.withLock {
    this.completionReceived = true

    val future = this.nextFuture
    this.nextFuture = null

    future?.completeExceptionally(ex)
  }

  override fun onCompleted(): Unit = this.lock.withLock {
    this.completionReceived = true

    val future = this.nextFuture
    this.nextFuture = null

    future?.complete(null)
  }
}
