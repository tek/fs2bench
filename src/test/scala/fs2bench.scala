package fs2bench

import scala.concurrent.duration._

import cats._
import cats.implicits._
import fs2.{Stream, Task => FTask, Strategy}
import fs2.interop.cats._

import scalaz.stream.Process
import scalaz.concurrent.{Task => ZTask}

import org.specs2._

sealed trait Alg

case class A(num: Int)
extends Alg

case class To(message: Alg, num: Int)
extends Alg

case object Exit
extends Alg

object Types
{
  type AProc = Process[ZTask, Alg]
  type AStream = Stream[FTask, Alg]
}

import Types._

abstract class Bench[S[_]: Monad]
(implicit monoid: Monoid[S[Alg]])
{
  type AS = S[Alg]
  type Trans = AS => AS

  val nums = Iterator.range(1, 10000)

  def runLoop(s: AS, num: Int): AS

  def main(input: AS) = {
    val monad = Monad[S]
    val num = nums.next()
    val s = input
      .flatMap {
        case To(m, n) if num == n => monad.pure(m)
        case To(_, _) => monoid.empty
        case a => monad.pure(a)
      }
    runLoop(s, num)
  }

  def node(input: AS, sub: List[Trans]): AS =
    monoid.combine(main(input), sub.foldMap(_(input)))

  def run(stream: Trans): Unit

  val numTrials = 100

  def nodeCons(sub: List[Trans] = Nil) = (cm: AS) => node(cm, sub)

  def tree = nodeCons(
    List(nodeCons(List(nodeCons(List(nodeCons(List(nodeCons())))))),
      nodeCons(List(nodeCons())), nodeCons()))

  def millis = System.currentTimeMillis

  def single() = {
    val start = millis
    run(tree)
    millis - start
  }

  def bench() = {
    run(tree)
    val s = 1.to(numTrials) map (_ => single())
    println(s"av: ${s.sum / numTrials}")
  }
}

trait Base[S[_]]
extends Specification
{
  def is = s2"""
  run benchmark $benchmark
  """

  def bench: Bench[S]

  def benchmark = {
    bench.bench()
    1 === 1
  }
}

case class Fs2Bench()
(implicit monoid: Monoid[AStream], monad: Monad[Stream[FTask, ?]])
extends Bench[Stream[FTask, ?]]
{
  import fs2.{Handle, Pull}

  implicit val strategy = Strategy.sequential

  def loop(num: Int)(hIn: Handle[FTask, Alg]): Pull[FTask, Alg, Unit] = {
    import Pull.{done, output1}
    hIn.receive1 {
      case (a, h) => a match {
        case Exit =>
          done
        case A(n) if n > 1 && num != -1 =>
          output1(To(A(n - 1), num)) >> loop(num)(h)
        case A(n) if num != -1 =>
          output1(Exit)
        case a =>
          loop(num)(h)
      }
    }
  }

  def runLoop(s: AStream, num: Int) = s.pull(loop(num))

  def run(stream: Trans) =
    Stream.eval(fs2.async.topic[FTask, Alg](A(30)))
      .flatMap(top => stream(top.subscribe(1000)) to top.publish)
      .runLog
      .unsafeRun()
}

class Fs2Spec
extends Base[Stream[FTask, ?]]
{
  implicit def strategy = Strategy.sequential

  implicit val monad = monadToCats[Stream[FTask, ?]](
    implicitly[fs2.util.Monad[Stream[FTask, ?]]])

  implicit def monoid: Monoid[AStream] =
    new Monoid[AStream] {
      def empty = Stream()
      def combine(x: AStream, y: AStream) = x.merge(y)
    }

  def bench = Fs2Bench()
}

case class SzsBench()
(implicit monoid: Monoid[AProc], monad: Monad[Process[ZTask, ?]])
extends Bench[Process[ZTask, ?]]
{
  import scalaz.stream._
  import Process._

  def loop(num: Int): Process1[Alg, Alg] = {
    receive1 {
      case Exit =>
        halt
      case A(n) if n > 1 && num != -1 =>
        emit(To(A(n - 1), num)) ++ loop(num)
      case A(n) if num != -1 =>
        emit(Exit)
      case a =>
        loop(num)
    }
  }

  def runLoop(s: AProc, num: Int) = s.pipe(loop(num))

  implicit val sched = scalaz.stream.DefaultScheduler

  def run(stream: Trans) = {
    val msg = async.topic[Alg](time.sleep(1.millis) ++ Process.emit(A(30)))
      stream(msg.subscribe)
        .to(msg.publish)
        .runLog
        .unsafePerformSync
  }
}

class SzsSpec
extends Base[Process[ZTask, ?]]
{
  import Process._

  implicit val monad: Monad[Process[ZTask, ?]] =
    new Monad[Process[ZTask, ?]] {
      def pure[A](a: A) = emit(a)
      def flatMap[A, B](fa: Process[ZTask, A])(f: A => Process[ZTask, B]) =
        fa.flatMap(f)
      def tailRecM[A, B](a: A)(f: A => Process[ZTask, Either[A, B]])
      : Process[ZTask, B] =
        defaultTailRecM(a)(f)
    }

  implicit def monoid: Monoid[AProc] =
    new Monoid[AProc] {
      def empty = halt
      def combine(x: AProc, y: AProc) = x.merge(y)
    }

  def bench = SzsBench()
}
