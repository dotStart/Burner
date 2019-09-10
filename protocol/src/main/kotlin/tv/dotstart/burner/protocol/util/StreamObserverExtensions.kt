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

import com.google.protobuf.Empty
import io.grpc.stub.StreamObserver
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

/**
 * Completes a void stream.
 *
 * This utility is aimed at non-streaming or client-streaming services which return a void
 * value (represented by [Empty]).
 */
fun StreamObserver<Empty>.returnValue() = this.returnValue(Empty.getDefaultInstance())

/**
 * Writes a single value to a given observer and completes the stream.
 *
 * This utility is primarily aimed at services which return only a single value per call (e.g.
 * non-streaming or client-streaming methods).
 */
fun <R : Any> StreamObserver<R>.returnValue(value: R) {
  this.onNext(value)
  this.onCompleted()
}

/**
 * Generates a stream observer for a given completable future.
 *
 * This utility function permits the synchronous retrieval of data from a grpc interface when only
 * a single return value is expected.
 *
 * @author [Johannes Donath](mailto:johannesd@torchmind.com)
 * @date 09/09/2019
 */
fun <R : Any> CompletableFuture<R>.toStreamObserver() = object : StreamObserver<R> {

  lateinit var latestValue: R

  override fun onNext(value: R) {
    this.latestValue = value
  }

  override fun onError(ex: Throwable) {
    this@toStreamObserver.completeExceptionally(ex)
  }

  override fun onCompleted() {
    this@toStreamObserver.complete(this.latestValue)
  }
}

/**
 * Wraps an asynchronous RPC call into a JVM native future.
 *
 * @author [Johannes Donath](mailto:johannesd@torchmind.com)
 * @date 09/09/2019
 */
fun <R : Any> async(call: (StreamObserver<R>) -> Unit): Future<R> = CompletableFuture<R>().apply {
  call(this.toStreamObserver())
}

/**
 * Converts an asynchronous rpc call into a synchronous call.
 *
 * @author [Johannes Donath](mailto:johannesd@torchmind.com)
 * @date 09/09/2019
 */
fun exec(call: (StreamObserver<Empty>) -> Unit) {
  val future = CompletableFuture<Empty>()
  call(future.toStreamObserver())
  future.get()
}

/**
 * Converts an asynchronous rpc call into a synchronous call which returns the given value.
 *
 * @author [Johannes Donath](mailto:johannesd@torchmind.com)
 * @date 09/09/2019
 */
fun <R : Any> retrieve(call: (StreamObserver<R>) -> Unit): R {
  val future = CompletableFuture<R>()
  call(future.toStreamObserver())
  return future.get()
}
