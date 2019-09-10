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
package tv.dotstart.burner.examples.greeter.plugin

import com.google.protobuf.StringValue
import io.grpc.stub.StreamObserver
import tv.dotstart.burner.examples.greeter.protocol.GreeterServiceGrpc
import tv.dotstart.burner.protocol.util.returnValue

/**
 * @author <a href="mailto:johannesd@torchmind.com">Johannes Donath</a>
 * @date 07/09/2019
 */
object GreeterServiceImpl : GreeterServiceGrpc.GreeterServiceImplBase() {

  override fun getGreeting(req: StringValue, ob: StreamObserver<StringValue>) {
    ob.returnValue(StringValue.newBuilder()
        .setValue("Guten Tag, ${req.value}")
        .build())
  }
}
