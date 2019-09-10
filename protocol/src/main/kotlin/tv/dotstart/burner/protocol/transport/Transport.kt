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
package tv.dotstart.burner.protocol.transport

import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.ServerSocketChannel
import io.netty.channel.socket.SocketChannel
import java.util.*
import java.util.concurrent.ThreadFactory
import kotlin.reflect.KClass

/**
 * Provides information on a specific transport implementation.
 *
 * Transport implementations may be generic (e.g. may be supported on all JVM implementations) or,
 * as is typically the case, operating system, architecture or implementation specific. Generally
 * speaking, this interface is designed to select the most optimal transport implementation for the
 * purposes of communication depending on the executing environment.
 *
 * Note that this implementation requires the netty grpc implementation to be present in order to
 * be useful. If a different grpc transport provider is chosen, this functionality will not be
 * available.
 *
 * @author [Johannes Donath](mailto:johannesd@torchmind.com)
 * @date 07/09/2019
 */
interface Transport<SC : ServerSocketChannel, CC : SocketChannel> {

  /**
   * Defines a human readable name with which this particular transport is identified within the
   * logs.
   *
   * Note that this information is typically only exposed on the host side (unless plugin debugging
   * has been enabled).
   */
  val name: String

  /**
   * Evaluates whether this particular transport is available within the current execution
   * environment.
   *
   * Implementations are free to select the criteria on which this field is populated. Typically
   * this will simply be a check which evaluates whether a native library is available for the
   * respective operating system and architecture on which the host/plugin is executing at the
   * moment.
   */
  val available: Boolean
    get() = true

  /**
   * Identifies the relative priority with which this transport is typically selected.
   *
   * Generally this value is meaningless without a comparison (e.g. another available
   * transport). However, transports with higher numbers tend to be more likely to be
   * selected (given that there tend to be less transports with higher values).
   *
   * This value should be selected based on the transport efficiency. For instance, the default
   * Epoll transport (which is available on all compatible JVM implementations) selects
   * [Int.MIN_VALUE] as it is the least efficient implementation. System specific implementations,
   * like epoll (on Linux) choose zero as their priority.
   */
  val priority: Int
    get() = 0

  /**
   * Identifies the channel type which is used by plugins to create a server socket which is
   * made available to the respective host application.
   */
  val pluginChannelType: KClass<SC>

  /**
   * Identifies the channel type which is used by host applications to establish a connection with
   * a given plugin.
   */
  val hostChannelType: KClass<CC>

  /**
   * Constructs a new event loop group with the given thread factory.
   *
   * The returned instance is expected to be compatible with the given plugin and host channel
   * types.
   */
  fun createEventLoopGroup(threadCount: Int = 0, factory: ThreadFactory? = null): EventLoopGroup

  companion object {

    /**
     * Permanently stores the list of available transports within the current execution environment.
     *
     * This property is initialized lazily and will be cached until the application is shut down. As
     * such, it should be invoked once all desired dependencies have been loaded into the
     * Class-Path.
     */
    val available: List<Transport<*, *>> by lazy {
      ServiceLoader.load(Transport::class.java).iterator().asSequence()
          .filter(Transport<*, *>::available)
          .toList()
    }

    /**
     * Retrieves the most optimal transport implementation within the current Class-Path.
     *
     * This property is initialized lazily and will be cached until the application is shut down. As
     * such, it should be invoked once all desired dependencies have been loaded into the
     * Class-Path.
     */
    val optimal: Transport<*, *> by lazy {
      available
          .maxBy(Transport<*, *>::priority)
          ?: throw IllegalStateException(
              "Unsupported JVM implementation: No compatible transport in Class-Path")
    }
  }
}
