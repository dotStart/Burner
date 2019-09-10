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
package tv.dotstart.burner.host

import com.google.protobuf.Empty
import io.grpc.ManagedChannel
import io.grpc.netty.NettyChannelBuilder
import io.netty.channel.EventLoopGroup
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import tv.dotstart.burner.host.io.logOutput
import tv.dotstart.burner.host.io.redirectOutput
import tv.dotstart.burner.protocol.Plugin
import tv.dotstart.burner.protocol.PluginHandshake
import tv.dotstart.burner.protocol.PluginServiceGrpc
import tv.dotstart.burner.protocol.transport.Transport
import tv.dotstart.burner.protocol.util.CyclicStreamObserver
import tv.dotstart.burner.protocol.util.async
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.thread
import kotlin.concurrent.write

/**
 * @author [Johannes Donath](mailto:johannesd@torchmind.com)
 * @date 08/09/2019
 */
abstract class PluginInstance(private val command: List<String>) {

  /**
   * Evaluates whether this particular plugin instance is currently alive.
   *
   * This value depends on the process state and may as such be slightly out of sync (in cases where
   * the heartbeat logic has failed).
   */
  val alive: Boolean
    get() = this.lock.read { this.process?.isAlive ?: false }

  /**
   * Defines a set of environment variables which are to be passed to the plugin implementation at
   * startup.
   *
   * By default, no environment variables are passed to plugin implementations as the base
   * specification does not provide any sort of configuration logic out of the box. However, some
   * host implementations may use environmental markers to provide context hints to plugins (such as
   * whether an external bind address shall be chosen instead of the loopback address).
   */
  protected open val environmentVariables: Map<String, String>
    get() = emptyMap()

  /**
   * Defines the working directory in which this plugin instance will operate.
   *
   * By default, the working directory is simply inherited from the host application. This may,
   * however, be undesirable when plugins store their own data. In these cases, hosts should
   * override this property and provide a proper storage directory.
   *
   * Important: This path is expected to be part of the default file system.
   */
  protected open val workingDirectory: Path
    get() = Paths.get(".")

  /**
   * Defines the maximum amount of times that the instance is going to attempt to establish a
   * connection to the plugin when the connection is lost (or an internal error occurs within the
   * plugin service).
   *
   * By default, a maximum of 5 attempts are made.
   */
  protected open val maxConnectionAttempts: Int
    get() = 5

  /**
   * Defines the amount of time which needs to pass between connection attempts.
   *
   * This value needs to be adjusted to match the host's expectations as other services will be
   * unavailable during this duration as well.
   *
   * By default this value is set to an incredibly low value (50 millis) to fail fast in scenarios
   * where plugin instances run locally.
   */
  protected open val connectionRetryDuration: Duration
    get() = Duration.ofMillis(50)

  /**
   * Defines the amount of time which passes between host heartbeat requests.
   *
   * By default, this occurs every 30 seconds.
   */
  protected open val heartbeatDuration: Duration
    get() = Duration.ofSeconds(30)

  /**
   * Defines the amount of time which is permitted to pass between a heartbeat request and its
   * respective response.
   *
   * When no response is received within this time period, the instance will be terminated
   * immediately. This value should typically not exceed [heartbeatDuration] to provide a swift
   * response to issues.
   *
   * By default, this timeout is set to 30 seconds.
   */
  protected open val heartbeatTimeout: Duration
    get() = Duration.ofSeconds(30)

  /**
   * Defines the transport which is to be used to communicate with this plugin instance.
   *
   * By default, this method will simply return the most optimal implementation for a given
   * execution environment.
   */
  protected open val transport: Transport<*, *>
    get() = Transport.optimal

  protected open val logger: Logger
    get() = Companion.logger

  private val lock = ReentrantReadWriteLock()
  private var process: Process? = null
  private var stdoutRedirectorThread: Thread? = null
  private var stderrRedirectorThread: Thread? = null

  private var channel: ManagedChannel? = null
  private var eventLoopGroup: EventLoopGroup? = null

  private var pluginService: PluginServiceGrpc.PluginServiceStub? = null

  companion object {
    private val logger = LogManager.getLogger(PluginInstance::class.java)
  }

  /**
   * Starts this plugin by invoking its respective executable and establishing communication with
   * its RPC server.
   *
   * The future returned by this method is completed once the plugin has completed its
   * initialization cycle. As such, it may be used to delay application startups to the point at
   * which all plugins have fully initialized.
   */
  fun start(): Future<Unit> = this.lock.write {
    if (this.process != null || this.stdoutRedirectorThread != null || this.stderrRedirectorThread != null) {
      throw IllegalStateException("Failed to start plugin: Process is already running")
    }

    logger.info("Starting instance")
    val builder = ProcessBuilder()
        .command(this.command)
        .directory(this.workingDirectory.toFile())

    builder.environment()
        .putAll(this.environmentVariables)

    val process = builder.start()

    try {
      // wait a little bit to give the process a chance to initialize (or potentially fail early)
      if (process.waitFor(250, TimeUnit.MILLISECONDS)) {
        throw IllegalStateException("Process exited with status ${process.exitValue()}")
      }

      val handshake = PluginHandshake.read(process.inputStream)
      logger.debug("Instance is reachable at ${handshake.address}")
      logger.debug(
          "Instance is using protocol ${handshake.protocolVersion} with application protocol ${handshake.applicationProtocolVersion}")

      check(handshake.protocolVersion == PluginHandshake.protocolVersion) {
        "Invalid framework protocol version"
      }
      this.validateProtocolVersion(handshake.applicationProtocolVersion)

      this.stdoutRedirectorThread = thread(name = "plugin-redirector-stdout", isDaemon = true) {
        redirectOutput(process.inputStream) {
          this.writeLogMessage(it, false)
        }
      }
      this.stderrRedirectorThread = thread(name = "plugin-redirector-stderr", isDaemon = true) {
        redirectOutput(process.errorStream) {
          this.writeLogMessage(it, true)
        }
      }

      this.process = process
      this.onSpawned()

      val eventLoopGroup = this.transport.createEventLoopGroup()
      this.eventLoopGroup = eventLoopGroup

      logger.debug("Establishing communication channel")
      val channel = NettyChannelBuilder.forAddress(handshake.address)
          .channelType(this.transport.hostChannelType.java)
          .eventLoopGroup(eventLoopGroup)
          .usePlaintext() // TODO: Use SSL to prevent snooping by other applications
          .build()

      val future = CompletableFuture<Unit>()

      val service = PluginServiceGrpc.newStub(channel)
      this.pluginService = service
      thread(name = "plugin-heartbeat", isDaemon = true) {
        logger.debug("Starting heartbeat communication")
        this.handleHeartbeat(service, future)
      }

      this.allocateClientInstances(channel)
      return@write future
    } catch (ex: Throwable) {
      try {
        this.doStop()
      } catch (ignore: Throwable) {
      }

      // copy all remaining process outputs to the host log before deleting the process all together
      process.logOutput(
          outFunc = { this.writeLogMessage(it, false) },
          errorFunc = { this.writeLogMessage(it, true) }
      )

      throw ex
    }
  }

  private fun handleHeartbeat(service: PluginServiceGrpc.PluginServiceStub,
      initializationFuture: CompletableFuture<Unit>) {
    var previousState = Plugin.State.INITIALIZING
    var connectionAttempts = 0

    try {
      while (connectionAttempts++ < this.maxConnectionAttempts) {
        val observer = CyclicStreamObserver<Plugin.PluginStatus>()
        val upstream = service.heartbeat(observer)

        while (!observer.completed) {
          val future = observer.next
          upstream.onNext(Empty.getDefaultInstance())

          try {
            // wait for a response for the full timeout period
            // when we receive null here, we'll immediately break out of the loop as the plugin has
            // shut down (either by our request or due to an unrelated issue)
            val status = future.get(this.heartbeatTimeout.toMillis(), TimeUnit.MILLISECONDS)
                ?: return

            logger.trace("Received heartbeat with state ${status.state}")
            when (status.state) {
              Plugin.State.UNRECOGNIZED -> throw IllegalStateException(
                  "Protocol violation: Unrecognized state value")
              null -> throw IllegalStateException("Protocol violation: State omitted")

              Plugin.State.INITIALIZING -> {
                // prevent invalid changes to the state machine by potentially faulty plugin
                // implementations
                if (previousState != Plugin.State.INITIALIZING) {
                  throw IllegalStateException("Protocol violation: State reverted to init")
                }
              }
              Plugin.State.HEALTHY -> {
                when (previousState) {
                  // check whether we just finalized plugin initialization and if so complete the
                  // startup future to notify the host
                  Plugin.State.INITIALIZING -> {
                    this.onInitialized()
                    initializationFuture.complete(Unit)
                  }
                  else -> Unit // all good - nothing do here
                }
              }
              // exit early if the plugin indicates that it has entered its shutdown logic - this is
              // incredibly unlikely to occur but we'll handle it anyways
              Plugin.State.SHUTTING_DOWN -> return
            }

            previousState = status.state
          } catch (ex: TimeoutException) {
            logger.error("Instance has timed out")

            // heartbeat request has exceeded timeout
            // this is not an intermittent failure so we will immediately shut down the instance
            return
          } catch (ex: Throwable) {
            // caught unknown error (typically an intermittent network error)
            // retry connection later
            break
          }
        }
      }
    } finally {
      // heartbeat thread has exited - either we encountered an error that we cannot recover from or
      // we are in the process of being shut down (in this case the lock will be held by another
      // thread)
      this.onStop()
      this.tryStop()
    }
  }

  /**
   * Performs a clean shutdown of this plugin instance.
   */
  fun stop(): Unit = this.lock.write(this::doStop)

  private fun tryStop(): Boolean {
    if (!this.lock.writeLock().tryLock()) {
      return false
    }

    if (this.process == null) {
      return false
    }

    try {
      this.doStop()
      return true
    } finally {
      this.lock.writeLock().unlock()
    }
  }

  private fun doStop() {
    logger.info("Stopping instance")
    val process = this.process ?: throw IllegalStateException("Failed to stop plugin: Not running")

    this.pluginService?.let {
      logger.debug("Requesting graceful shutdown")
      val f = async<Empty> { ob -> it.shutdown(Empty.getDefaultInstance(), ob) }

      try {
        f.get(5, TimeUnit.SECONDS)
        logger.debug("Shutdown request succeeded - Awaiting process shutdown")

        process.waitFor(5, TimeUnit.SECONDS)
      } catch (ignore: Throwable) {
      }
    }

    this.channel?.let {
      logger.debug("Closing communication channel")
      if (!it.shutdown().awaitTermination(5, TimeUnit.SECONDS)) {
        // force shutdown if we run out of time here
        it.shutdownNow()
      }
    }
    this.eventLoopGroup?.let {
      logger.debug("Shutting down event thread pool")
      it.shutdownGracefully(1, 5, TimeUnit.SECONDS).await(10, TimeUnit.SECONDS)
    }

    if (process.isAlive) {
      process.destroy()
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly()
      }
    }

    this.process = null
    this.stdoutRedirectorThread = null
    this.stderrRedirectorThread = null
  }

  /**
   * Evaluates whether the plugin instance is considered compatible based on its provided
   * application protocol revision.
   *
   * By default only the application protocol revision "one" is permitted by this
   * implementation. Host applications may wish to override this, however.
   */
  protected open fun validateProtocolVersion(version: Int): Boolean {
    return version == 1
  }

  /**
   * Allocates service client instances for the given communication channel.
   */
  protected abstract fun allocateClientInstances(channel: ManagedChannel)

  /**
   * Writes a single log output from the plugin's stdout or stderr stream to a logging system.
   *
   * By default, the implementation will simply echo all outputs back to the host's standard output
   * streams.
   */
  protected open fun writeLogMessage(message: String, error: Boolean) {
    val level = if (error) {
      Level.ERROR
    } else {
      Level.INFO
    }

    logger.log(level, message)
  }

  /**
   * Event handler which is invoked once the plugin process has been spawned.
   */
  protected open fun onSpawned() {
  }

  /**
   * Event handler which is invoked once the plugin has completed its initialization.
   */
  protected open fun onInitialized() {
  }

  /**
   * Event handler which is invoked once the plugin has entered or completed its shutdown
   * logic (depending on when this fact is detected by the host logic).
   */
  protected open fun onStop() {
  }
}
