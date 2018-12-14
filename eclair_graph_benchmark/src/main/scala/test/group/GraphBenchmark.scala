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
import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.router.{RouteRequest, RouteResponse, Router}
import org.openjdk.jmh.annotations._
import test.group.helpers.NoopActor
import fr.acinq.bitcoin.{BinaryData, Block}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.crypto.LocalKeyManager
import fr.acinq.eclair._

import scala.concurrent.duration._
import test.group.helpers.ConfigHelper._

import scala.concurrent.{Await, Promise}
import scala.util.{Failure, Success}

@State(value = Scope.Benchmark)
class GraphBenchmark {

	println("Preparing the benchmark")

	//mainnet nodes used for route calculation benchmark, there IS a route (of length 3) between them

	//03cb7983dc247f9f81a0fa2dfa3ce1c255365f7279c8dd143e086ca333df10e278 -> 036fc14cbad100a63ce4c058561d470e045318b6e9659484abcb3176ed7f0acbce -> 02a0bc43557fae6af7be8e3a29fdebda819e439bea9c0f8eb8ed6a0201f3471ca9
	val fairlycheap = PublicKey("03cb7983dc247f9f81a0fa2dfa3ce1c255365f7279c8dd143e086ca333df10e278")
	val LightningPeachHub = PublicKey("02a0bc43557fae6af7be8e3a29fdebda819e439bea9c0f8eb8ed6a0201f3471ca9")

	//for akka ask pattern
	implicit val timeout = Timeout(10 seconds)

	val mainnetDbFolder = new File("./mainnetdb-14122018")
	val akkaConf = ConfigFactory.parseString(rawAkkConf).resolve()

	implicit val system = ActorSystem.create("bench-actor-system", config = akkaConf)
	implicit val executorContext = system.dispatcher

	val noopActor = system.actorOf(Props(new NoopActor()))

	val keyManager = new LocalKeyManager(seed = randomKey.toBin, chainHash = Block.LivenetGenesisBlock.hash)
	val params = NodeParams.makeNodeParams(mainnetDbFolder, eclairConf.getConfig("eclair"), keyManager)

	val routerInitialized = Promise[Unit]()
	val router = system.actorOf(Router.props(params, noopActor, Some(routerInitialized)))
	Await.result(routerInitialized.future, 10 seconds)

	@Benchmark
	@BenchmarkMode(value = Array(Mode.AverageTime))
	def routerLoadingTime(): Any = {
		val routerInitializedBench = Promise[Unit]()
		system.actorOf(Router.props(params, noopActor, Some(routerInitializedBench)))
		Await.result(routerInitializedBench.future, 10 seconds)
	}

	@Benchmark
	@BenchmarkMode(value = Array(Mode.AverageTime))
	def findPath() = {

		val routeFuture = router ? RouteRequest(fairlycheap, LightningPeachHub, 1200L) //route 1.2 sats

		Await.result(routeFuture, 10 seconds)
	}

}
