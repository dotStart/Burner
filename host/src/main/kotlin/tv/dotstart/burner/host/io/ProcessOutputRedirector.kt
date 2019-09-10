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
package tv.dotstart.burner.host.io

import java.io.InputStream
import java.io.InputStreamReader

/**
 * @author [Johannes Donath](mailto:johannesd@torchmind.com)
 * @date 08/09/2019
 */
class ProcessOutputRedirector(
    private val stream: InputStream,
    private val loggerFunc: (String) -> Unit) {

  operator fun invoke() {
    InputStreamReader(this.stream).useLines {
      it.forEach { this.loggerFunc(it) }
    }
  }
}

fun redirectOutput(stream: InputStream, loggerFunc: (String) -> Unit) {
  val redirector = ProcessOutputRedirector(stream, loggerFunc)
  redirector()
}
