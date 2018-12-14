/*
 * Copyright (c) 2014, Oracle America, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  * Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 *  * Neither the name of Oracle nor the names of its contributors may be used
 *    to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
 * THE POSSIBILITY OF SUCH DAMAGE.
 */

package test.group

import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.Setup
import fr.acinq.eclair.router.RouteRequest
import org.openjdk.jmh.annotations._
import test.group.helpers.BitcoindHelper
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.Await
import test.group.helpers.ConfigHelper._

@State(value = Scope.Benchmark)
class Benchmark {

	implicit val timeout = Timeout(5 seconds) //used for akka ask pattern

	println("Preparing the benchmark")

	val buildDir = new File("./target").toPath.toAbsolutePath.toString
	val benchmarkDir = s"$buildDir/benchmark-${UUID.randomUUID().toString}"
	println(s"using tmp dir: $benchmarkDir")

	val pkA = PublicKey("02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73")
	val pkB = PublicKey("029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c")

	val eclairKit = {
		val datadir = new File(benchmarkDir, s"datadir-eclair-bench")
		datadir.mkdirs()

		val akkaConf = ConfigFactory.parseString(rawAkkConf).resolve()

		implicit val system = ActorSystem.create("bench-actor-system", config = akkaConf)

		//starting bitcoin
		val bitcoind = new BitcoindHelper(benchmarkDir = benchmarkDir)
		bitcoind.startBitcoind() //blocking

		val setup = new Setup(datadir, eclairConf)(system)
		val eclairKit = Await.result(setup.bootstrap, 10 seconds)
		eclairKit
	}

	@Benchmark
	@OutputTimeUnit(TimeUnit.SECONDS)
	def benchmarkRouteRequest(): Any = {
		Await.result(
			eclairKit.router ? RouteRequest(pkA, pkB, 42000L),
			timeout.duration
		)
	}

}
