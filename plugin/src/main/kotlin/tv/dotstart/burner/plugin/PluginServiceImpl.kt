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
package tv.dotstart.burner.plugin

import com.google.protobuf.Empty
import io.grpc.stub.StreamObserver
import tv.dotstart.burner.protocol.Plugin
import tv.dotstart.burner.protocol.PluginServiceGrpc
import tv.dotstart.burner.protocol.util.returnValue
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * @author [Johannes Donath](mailto:johannesd@torchmind.com)
 * @date 10/09/2019
 */
class PluginServiceImpl(
    private val shutdownHandler: () -> Unit,
    private val stateGetter: () -> Plugin.State) : PluginServiceGrpc.PluginServiceImplBase() {

  private val timeoutBarrier = CyclicBarrier(2)

  private val lock = ReentrantLock()
  private var observer: StreamObserver<Plugin.PluginStatus>? = null

  override fun heartbeat(
      ob: StreamObserver<Plugin.PluginStatus>): StreamObserver<Empty> = this.lock.withLock {
    // immediately complete any prior observers as only a single host may stay in contact with us
    // at a given time
    this.observer?.let(StreamObserver<Plugin.PluginStatus>::onCompleted)

    this.observer = ob
    HeartbeatObserver(ob)
  }

  override fun shutdown(req: Empty, ob: StreamObserver<Empty>) {
    ob.returnValue()
    this.shutdownHandler()
  }

  fun handleTimeout(
      timeoutMillis: Long,
      handler: () -> Unit) {
    try {
      // TODO: We may want to provide hosts with a slightly increased timeout during startup

      while (!Thread.currentThread().isInterrupted) {
        this.timeoutBarrier.await(timeoutMillis, TimeUnit.MILLISECONDS)
      }
    } catch (ignore: BrokenBarrierException) {
      // we'll interpret interrupts as shutdown signals here so we'll simply terminate early
    } catch (ex: TimeoutException) {
      handler()
    }
  }

  private inner class HeartbeatObserver(private val ob: StreamObserver<Plugin.PluginStatus>) :
      StreamObserver<Empty> {

    override fun onNext(value: Empty) {
      timeoutBarrier.await()

      this.ob.onNext(Plugin.PluginStatus.newBuilder()
          .setState(stateGetter())
          .build())
    }

    override fun onError(ex: Throwable): Unit = lock.withLock {
      observer = null
    }

    override fun onCompleted(): Unit = lock.withLock {
      observer = null
    }
  }
}
