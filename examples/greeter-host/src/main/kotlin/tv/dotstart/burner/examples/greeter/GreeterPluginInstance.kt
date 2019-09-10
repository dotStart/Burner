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
package tv.dotstart.burner.examples.greeter

import com.google.protobuf.StringValue
import io.grpc.ManagedChannel
import tv.dotstart.burner.examples.greeter.protocol.GreeterServiceGrpc
import tv.dotstart.burner.host.PluginInstance

/**
 * @author [Johannes Donath](mailto:johannesd@torchmind.com)
 * @date 10/09/2019
 */
class GreeterPluginInstance(command: List<String>) : PluginInstance(command) {

  private lateinit var service: GreeterServiceGrpc.GreeterServiceBlockingStub

  override fun allocateClientInstances(channel: ManagedChannel) {
    this.service = GreeterServiceGrpc.newBlockingStub(channel)
  }

  fun greet(name: String): String {
    return this.service.getGreeting(StringValue.of(name)).value
  }
}
