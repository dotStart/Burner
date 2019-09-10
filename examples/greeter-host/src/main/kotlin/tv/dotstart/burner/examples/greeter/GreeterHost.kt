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

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * @author [Johannes Donath](mailto:johannesd@torchmind.com)
 * @date 10/09/2019
 */
fun main(args: Array<String>) {
  if (args.isEmpty()) {
    println("Usage: java -jar greeter-host.jar <name>")
    exitProcess(0)
  }

  val executableSuffix = if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
    ".exe"
  } else {
    ""
  }

  val javaBinary = System.getProperty("java.home")
      ?.let { Paths.get(it) }
      ?.let {

        it.resolve("bin/java")
      }
      ?.let(Path::toString)
      ?: "java"

  val instance = GreeterPluginInstance(
      listOf("$javaBinary$executableSuffix", "-jar", "greeter-plugin/target/greeter-german.jar"))
  instance.start().get()

  try {
    println(instance.greet(args[0]))
  } finally {
    instance.stop()
  }
}
