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

import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import java.util.concurrent.ThreadFactory

/**
 * Provides a fallback transport implementation which is available on all compatible JVM
 * implementations.
 *
 * @author [Johannes Donath](mailto:johannesd@torchmind.com)
 * @date 07/09/2019
 */
class NIOTransport : Transport<NioServerSocketChannel, NioSocketChannel> {

  override val name = "NIO"
  override val priority = Int.MIN_VALUE

  override val pluginChannelType = NioServerSocketChannel::class
  override val hostChannelType = NioSocketChannel::class

  override fun createEventLoopGroup(threadCount: Int, factory: ThreadFactory?) = NioEventLoopGroup(
      threadCount, factory)
}
