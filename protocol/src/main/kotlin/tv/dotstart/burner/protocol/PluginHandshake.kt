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
package tv.dotstart.burner.protocol

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Represents the information presented within the plugin handshake.
 *
 * This object is exchanged between host and plugin via the plugin's stdout stream at the beginning
 * of its lifecycle and used to validate whether the plugin is supported by the host application.
 *
 * If the protocol version or application version is not recognized by the host application, the
 * plugin will be killed immediately.
 *
 * @author [Johannes Donath](mailto:johannesd@torchmind.com)
 * @date 07/09/2019
 */
data class PluginHandshake(
    val protocolVersion: Int = Companion.protocolVersion,
    val applicationProtocolVersion: Int,
    val address: InetSocketAddress) {

  /**
   * Writes the handshake to a given output stream.
   *
   * When no stream is given, the handshake will be written to the application's standard output.
   */
  fun print(out: OutputStream = System.out as OutputStream) {
    out.bufferedWriter(StandardCharsets.UTF_8).use {
      it.write(buildString {
        append(protocolVersion)
        append(separator)
        append(applicationProtocolVersion)
        append(separator)
        append(address.address.hostAddress)
        append(':')
        append(address.port)
        append('\n')
      })
    }
  }

  companion object {

    /**
     * Identifies the protocol revision which is provided by this implementation of the protocol
     * module.
     */
    const val protocolVersion = 1

    private const val separator = '|'

    /**
     * Attempts to read a plugin handshake from the given input stream.
     */
    fun read(input: InputStream): PluginHandshake {
      val reader = BufferedReader(InputStreamReader(input))

      var line = ""
      var elements = emptyList<String>()
      while (true) {
        line = reader.readLine() ?: throw IllegalArgumentException("Reached EOF before handshake")
        if (!line.contains('|')) {
          continue
        }

        elements = line.split(separator)
        if (elements.size == 3) {
          break
        }
      }

      val protocolVersion = try {
        elements[0].toInt()
      } catch (ex: NumberFormatException) {
        throw IllegalArgumentException("Malformed protocol version", ex)
      }
      val applicationProtocolVersion = try {
        elements[1].toInt()
      } catch (ex: NumberFormatException) {
        throw IllegalArgumentException("Malformed application protocol version", ex)
      }
      val (address, portStr) = elements[2].split(':', limit = 2)
      check(address.isNotBlank()) { "Malformed plugin address" }
      check(portStr.isNotBlank()) { "Malformed plugin port" }
      val port = try {
        portStr.toInt()
      } catch (ex: NumberFormatException) {
        throw IllegalArgumentException("Malformed plugin port", ex)
      }

      return PluginHandshake(protocolVersion, applicationProtocolVersion,
          InetSocketAddress(address, port))
    }
  }
}
