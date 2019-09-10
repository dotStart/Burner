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

import io.netty.channel.kqueue.KQueue
import io.netty.channel.kqueue.KQueueEventLoopGroup
import io.netty.channel.kqueue.KQueueServerSocketChannel
import io.netty.channel.kqueue.KQueueSocketChannel
import java.util.concurrent.ThreadFactory

/**
 * Provides a transport implementation which makes use of the BSD KQueue functionality.
 *
 * This transport implementation is currently only available on x64 versions of Mac OS X.
 *
 * @author [Johannes Donath](mailto:johannesd@torchmind.com)
 * @date 07/09/2019
 */
class KQueueTransport : Transport<KQueueServerSocketChannel, KQueueSocketChannel> {

  override val name = "KQueue"
  override val available by lazy { KQueue.isAvailable() }

  override val pluginChannelType = KQueueServerSocketChannel::class
  override val hostChannelType = KQueueSocketChannel::class

  override fun createEventLoopGroup(threadCount: Int,
      factory: ThreadFactory?) = KQueueEventLoopGroup(threadCount, factory)
}
