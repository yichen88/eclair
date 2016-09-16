package fr.acinq.eclair.crypto

import java.util.concurrent.{CountDownLatch, TimeUnit}

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.util.ByteString
import fr.acinq.eclair._
import fr.acinq.bitcoin.{BinaryData, Crypto, Hash}
import fr.acinq.eclair.channel.simulator.Pipe
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object EncryptorSpec {
  val random = new scala.util.Random()

  class Ping(pong: ActorRef, sendingKey: BinaryData, receivinKey: BinaryData, latch: CountDownLatch) extends Actor with ActorLogging {
    context.system.scheduler.schedule(50 + random.nextInt(50) milliseconds, 25 milliseconds, self, 'send)

    def receive = running(Encryptor(sendingKey, 0), Decryptor(receivinKey, 0))

    def running(encryptor: Encryptor, decryptor: Decryptor): Receive = {
      case chunk: BinaryData =>
        val decryptor1 = Decryptor.add(decryptor, ByteString.fromArray(chunk))
        decryptor1.bodies.map(_ => latch.countDown())
        context become running(encryptor, decryptor1.copy(bodies = Vector.empty[BinaryData]))
      case 'send =>
        val message: BinaryData = s"it is now ${System.currentTimeMillis()}".getBytes("UTF-8")
        val (encryptor1, ciphertext) = Encryptor.encrypt(encryptor, message)
        pong ! ciphertext
        context become running(encryptor1, decryptor)
    }
  }

}

@RunWith(classOf[JUnitRunner])
class EncryptorSpec extends FunSuite {

  import EncryptorSpec._

  test("encryption/description tests") {
    val key: BinaryData = Crypto.sha256(Hash.Zeroes)
    var enc = Encryptor(key, 0)
    var dec = Decryptor(key, 0)

    def run(e: Encryptor, d: Decryptor): (Encryptor, Decryptor) = {
      val plaintext = new Array[Byte](30)
      random.nextBytes(plaintext)
      val (e1, c) = Encryptor.encrypt(e, plaintext)
      val d1 = Decryptor.add(d, ByteString.fromArray(c))
      assert(java.util.Arrays.equals(plaintext, d1.bodies.head))
      (e1, d1.copy(bodies = Vector()))
    }

    for (i <- 0 until 10) {
      val (e, d) = run(enc, dec)
      enc = e
      dec = d
    }
  }

  test("decryption of several messages in multiples chunk") {
    val key: BinaryData = Crypto.sha256(Hash.Zeroes)
    val enc = Encryptor(key, 0)
    var dec = Decryptor(key, 0)

    val plaintext1: BinaryData = new Array[Byte](200)
    random.nextBytes(plaintext1)
    val plaintext2: BinaryData = new Array[Byte](300)
    random.nextBytes(plaintext2)

    val (enc1, ciphertext1) = Encryptor.encrypt(enc, plaintext1)
    val (enc2, ciphertext2) = Encryptor.encrypt(enc1, plaintext2)

    val chunks = (ciphertext1 ++ ciphertext2).grouped(35).toList
    chunks.map(chunk => dec = Decryptor.add(dec, chunk))

    assert(dec.header == None && dec.bodies == Vector(plaintext1, plaintext2))
  }

  test("decryption of several messages in a single chunk") {
    val key: BinaryData = Crypto.sha256(Hash.Zeroes)
    val random = new scala.util.Random()
    val enc = Encryptor(key, 0)
    val dec = Decryptor(key, 0)

    val plaintext1: BinaryData = new Array[Byte](200)
    random.nextBytes(plaintext1)
    val plaintext2: BinaryData = new Array[Byte](300)
    random.nextBytes(plaintext2)

    val (enc1, ciphertext1) = Encryptor.encrypt(enc, plaintext1)
    val (enc2, ciphertext2) = Encryptor.encrypt(enc1, plaintext2)

    val dec1 = Decryptor.add(dec, ciphertext1 ++ ciphertext2)

    assert(dec1.header == None && dec1.bodies == Vector(plaintext1, plaintext2))
  }

  test("concurrency tests") {
    val system = ActorSystem("mySystem")

    val k1: BinaryData = Crypto.sha256(Hash.One)
    val k0: BinaryData = Crypto.sha256(Hash.Zeroes)
    val pipe = system.actorOf(Props[Pipe], "pipe")
    val latch = new CountDownLatch(100)
    val a = system.actorOf(Props(classOf[Ping], pipe, k0, k1, latch), "a")
    Thread.sleep(7)
    val b = system.actorOf(Props(classOf[Ping], pipe, k1, k0, latch), "b")
    pipe ! (a, b)

    latch.await(5, TimeUnit.SECONDS)
    system.shutdown()
  }

  test("flare debug unitary") {
    val key = BinaryData("de0377022997e0b35fdd13e2bb6f2671890719fcb938d4438743d7a8f0c18eaf")
    val chunk1 = BinaryData("2d837ca635dbb93ad390a679977ecf53598f8cc59c564cba1e9966d5040c51292562b875857e737dca4d26d4cb02c545065f4a6407a7f99e02439e1cd84005729333fbb8744d75931e30f20c8da8a7e1d273d6d82552be1f8be6d7449bd3aacb463ac3093db43c58b2b338d5e3aca6186d3eb362b45d1cac589dc7b61ceae2f741ddc8a279ffe7436076b78126c79da8899399523fda1dc126e5c4f11f6085e586578737952aeee64fa2dc99aad0912813de502a2c6cc7d3b8f3ff375dc7269e425db7414dfa089d533ec349d5a590a8beb1bcc3ecff7c42c446ca240f7ab919f2644adf643958eb658a2b4a16394c5812dee1325b6e3e2cc1e2c38da637893d646c9f830b09bea0fa244c66c1e3dd044ec3feae51803c1836755f4e935267905459f9786c1e4cec3f3988da1fbf2c318ff563b973dc80f3e9402e95c2fa012a83138f4205cc9b7982097a06e82d479f6226ad110a0d92e90aef89be29e0bcadde3b64bf27e6631c4c936d1969934ae66675e39d18ae028c75b47df7fa7399b82775463fce5d70d2c7755c461936c42a27dbfc6fcce517890b5313088b2f88c8b16eecb83c2c5c69c4fd23d64e6b28d6aed1d985bace6ad0f1cb4ebe7d86629565f5c96811d634736f5667886b99a26b531a674607b2ed156768648a5dd8b43df0f585c8636ffca09977be66e1d5f0f969105570e9636412767cae00c96aa79f55d290c5abff234997bd977803817c04e7ca702e618122198ce91ee603cec5ec99237bf3b616c5d8dcd6f7565a63d48acd2723afd3c06ed12b852613e8c61bb08c41d3f50e6a6709b0157120f592425435ed5f59531da48a1a0590ff018e2ff28922a44953997c4c41eb393b98166410a87c5fd5f4f2c8fb54ad69379520917be98af1cfc7239a2ea10f3a22349b1768ff4fde7911786d782dd96bea87e181aa11ca7979118df274aa0e806a0321bee4f300d7e07c3c0a3b70dd221b632e3e0f4887429f03f56f016a87bd09c50844c35c34766e5cc2412ebc80f658101b2779176fd705ad724eaa500afac793866175f34a87fe2c1c8d288d44baa16e41d3fa9c9d9d587f8706682217bce519487a1b4b4aa8f7f3c5649d998d08766b713f13b7f0f2281a64d8c895a8aaa900e581f3415dc063affb31c07055ba1ab003d90dd49dd47f17653c48ecb7683bc89de622485f4b20428a1360916e66d324ee1f59536f8d7f34b002c340c1d56c559aa0094c14d2ea41d266579505b7bac93a76c15a0d558100b9a5ed468c9a89b93527e1457b09141fdfd8f85b49e5a19a6b2b04022d8e4097cdff523b7a47b23cda4e2514ccdccf534b1409a37b4375079dcb0fe467b2d3abb339f7a7c6bcb4f031ea75678a0175a7038bf37fac551cd1e2948777b433cf1e145612a30fccab0ac35ff12bae851ce5a738db2222537697c7fc201c631dd9514a0e7bd79ab93a440b70a27588c03d48a82e4296bb02f150ed616f117d0e641150497897b38cc03877b0cbd9fd5cb6261ce9c9a60e7b6e2929c22bcaf2c56cce484bcc6b5c6943257aa03d9b484bb67590bcb291d69199625e9847617f803bb92d2f780924a6894c4f9ef7939cffed83850456385fdc771e8b9b3a59ddbf914e96756d4d2fac2aea3994f5d7fc18c70fe03085a92bee8f1b7f0903fdd8546cf35ecffb557cfe22e64121ed5dacd82c2ecf52a7bf38d5cc8d1851a5b78b4e5e25230f74f7a17f175dbe72e515956835021bf9819e8033b45b07ef3db5daaeacb851d2c94f571c2093eda094b8d3b90d2af22b56261c5a079d2a56e4ddd138a38cf30af28e11aa5231fcc6e32e032605780ced4b672c1251edc38403ca876e6a830dbd17536ea6e73984761757f2e63fe123c65bd176477d2e3531c0321d6aded27aa2430d17d40669659c041ee956471c68f235fdeff483886364ab93090dd6de22298d73483bf7435398e527e2e250808f0066869f63d907df515d2b5e3fbc5e9057090997bfd49cd560c275a24b689d883c3bd4c2726ed6b3c171c1d83bfda6342339")
    val chunk2 = BinaryData("400865a51aa22319a563292f2471bde84236d6cb19ece6101a1564ec138f48a327e8163ab188c233b3a9b5513c8f2f5809f3a14fcae1c390c337126edd17a7846aba7964a58ea662ee3a74c2c003f8de40dcfe0e89f7bed904b85e0f64951fdee9a5a7b25404a41f0fb154bd082857bc3dc163b7")
    val dec = Decryptor(key, 108)
    val dec12 = Decryptor.add(dec, ByteString.fromArray(BinaryData(chunk1.data ++ chunk2.data)))
    val dec1 = Decryptor.add(dec, chunk1)
    val dec2 = Decryptor.add(dec1.copy(bodies = Vector.empty), chunk2)
    assert(dec12.bodies == dec2.bodies)
  }
}
