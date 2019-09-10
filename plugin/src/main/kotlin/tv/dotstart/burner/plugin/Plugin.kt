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

import io.grpc.BindableService
import io.grpc.Server
import io.grpc.netty.NettyServerBuilder
import io.netty.channel.EventLoopGroup
import org.apache.logging.log4j.LogManager
import tv.dotstart.burner.protocol.Plugin
import tv.dotstart.burner.protocol.PluginHandshake
import tv.dotstart.burner.protocol.transport.Transport
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.thread
import kotlin.concurrent.write

/**
 * Represents a plugin implementation which exposes an arbitrary number of services to a host
 * application.
 *
 * @author [Johannes Donath](mailto:johannesd@torchmind.com)
 * @date 07/09/2019
 */
abstract class Plugin {

  /**
   * Identifies the application specific protocol version with which this plugin communicates.
   *
   * This value is validated by the host application in order to detect unsupported plugin revisions
   * early and kill them if necessary.
   */
  protected open val protocolVersion: Int
    get() = 1

  /**
   * Exposes a listing of services which are exposed by this particular plugin implementation.
   */
  protected abstract val services: List<BindableService>

  /**
   * Identifies the address to which this plugin will bind once initialized.
   *
   * By default, this will return an arbitrary socket address on the loopback interface. Some hosts
   * may, however, provide configuration information to alter this (for instance, when plugins are
   * launched inside of containers).
   */
  protected open val bindAddress: InetSocketAddress
    get() = InetSocketAddress("127.0.0.1", 0)

  /**
   * Defines the amount of time that has to pass before the plugin instance assumes its host to be
   * dead and performs a clean shutdown.
   *
   * By default, this value will be set to 90 seconds (in order to counteract initialization times
   * and potential network failures). Some implementations may wish to reduce this time further,
   * however, to prevent zombie instances from performing duties long after the host has died.
   */
  protected open val heartbeatTimeout: Duration
    get() = Duration.ofSeconds(15)

  /**
   * Identifies the transport which is to be used by this plugin implementation for the purposes of
   * establishing communication channels with the host.
   *
   * Typically this will simply return the current value of [Transport.optimal] in order to select
   * an execution environment specific implementation.
   */
  protected open val transport: Transport<*, *>
    get() = Transport.optimal

  private val lock = ReentrantReadWriteLock()
  private var state = Plugin.State.INITIALIZING
  private var bossEventLoopGroup: EventLoopGroup? = null
  private var workerEventLoopGroup: EventLoopGroup? = null
  private var heartbeatThread: Thread? = null
  private var server: Server? = null

  companion object {
    private val logger = LogManager.getLogger(Plugin::class.java)
  }

  /**
   * Plugin Entry Point
   *
   * Utility method which automates the plugin startup in order to work with most host
   * implementations. Please refer to the host application documentation for more information on
   * its respective startup logic.
   */
  fun main() {
    // bootstrap the server threads and server channel first as we'll need to transmit our bind
    // address as part of the handshake process
    val address = start()

    // transmit the plugin handshake so that the host can start communicating with us (e.g. start
    // sending heartbeats and retrieve the current status)
    PluginHandshake(applicationProtocolVersion = protocolVersion, address = address).print()
    logger.debug("Listening for host connections on $address")

    // call the plugin's internal startup logic now since we're ready for handling requests from the
    // host application
    this.onStart()

    logger.debug("Plugin has entered healthy state")
    this.lock.write { this.state = Plugin.State.HEALTHY }
  }

  fun start(): InetSocketAddress = this.lock.write {
    // ensure that the plugin is not already running, if so, throw an exception to notify the caller
    // about this breach in initialization logic
    if (this.bossEventLoopGroup != null || this.workerEventLoopGroup != null) {
      throw IllegalStateException("Failed to start plugin: Already running")
    }

    this.state = Plugin.State.INITIALIZING

    val bossEventLoopGroup = this.transport.createEventLoopGroup()
    this.bossEventLoopGroup = bossEventLoopGroup
    val workerEventLoopGroup = this.transport.createEventLoopGroup()
    this.workerEventLoopGroup = workerEventLoopGroup

    val builder = NettyServerBuilder.forAddress(this.bindAddress)
        .channelType(this.transport.pluginChannelType.java)
        .bossEventLoopGroup(bossEventLoopGroup)
        .workerEventLoopGroup(workerEventLoopGroup)

    val pluginService = PluginServiceImpl(
        shutdownHandler = {
          logger.debug("Shutdown requested via host connection")
          this.stop()
        },
        stateGetter = { this.lock.read(this::state) }
    )

    builder.addService(pluginService)
    this.services.forEach { builder.addService(it) }

    val server = builder.build()
    this.server = server

    server.start()

    this.heartbeatThread = thread(name = "plugin-keepalive", isDaemon = true) {
      logger.error("Host timeout - Shutting down")
      pluginService.handleTimeout(this.heartbeatTimeout.toMillis(), this::stop)
    }

    server.listenSockets.first()!! as InetSocketAddress
  }

  fun stop() = this.lock.write {
    val bossEventLoopGroup = this.bossEventLoopGroup
    val workerEventLoopGroup = this.workerEventLoopGroup
    val server = this.server

    // ensure the plugin is actually running, otherwise we'll throw an exception to notify the
    // caller about this break in shutdown logic
    if (bossEventLoopGroup == null && workerEventLoopGroup == null && server == null) {
      throw IllegalStateException("Failed to stop plugin: Not running")
    }

    this.state = Plugin.State.SHUTTING_DOWN

    server?.let { it.shutdown().awaitTermination() }
    bossEventLoopGroup?.let { it.shutdownGracefully().await() }
    workerEventLoopGroup?.let { it.shutdownGracefully().await() }

    this.heartbeatThread?.let(Thread::interrupt)

    this.server = null
    this.bossEventLoopGroup = null
    this.workerEventLoopGroup = null
    this.heartbeatThread = null

    this.onStop()
  }

  /**
   * Plugin Startup Hook
   *
   * This method is invoked once the plugin protocol is ready to handle service requests via its
   * rpc interface.
   */
  protected open fun onStart() {
  }

  /**
   * Plugin Shutdown Hook
   *
   * This method is invoked when the plugin is shut down gracefully via the plugin service or due to
   * an internal issue.
   */
  protected open fun onStop() {
  }
}
