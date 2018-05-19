package fs2
package async

import java.util.concurrent.Executors

import cats.effect.{IO, Sync}
import cats.implicits._
import org.typelevel.discipline.scalatest.Discipline
import cats.Eq
import cats.laws.discipline.SemigroupalTests.Isomorphisms
import cats.laws.discipline.{ApplicativeTests, FunctorTests}
import org.scalacheck.Arbitrary
import org.scalatest.FunSuite

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

/**
  * This is a separate class from [[SignalSpec]] because [[Discipline]]
  * requires our test class to be a [[FunSuite]], but making [[SignalSpec]]
  * a [[FunSuite]] would clash with its current test configuration.
  */
class SignalTestLaws extends FunSuite with Discipline {

  /**
    * This is unsafe because the Signal created cannot have multiple consumers
    * of its discrete stream since the source stream is restarted for each
    * consumer.
    *
    * This allows for things like checking whether two Signals converge to the
    * same value, which is important for [[unsafeSignalEquality]].
    *
    * We use this to create finite Signals for testing, namely Signals whose
    * discrete streams terminate and whose gets stop changing after their source
    * streams terminate. Using the usual noneTerminate trick (in this case you'd
    * make the underlying Signal work on Options of values and then
    * unNoneTerminate the discrete stream) makes testing Applicatives painful
    * because it's hard to capture what the last get of the Signal should've
    * been, which we need to ensure that Signals are converging to the same
    * value, since the last get just gets overwritten with a None. So we use
    * this instead.
    */
  private def unsafeHold[F[_]: Sync, A](initial: A,
                                        source: Stream[F, A]): F[immutable.Signal[F, A]] =
    refOf[F, A](initial).map { ref =>
      new immutable.Signal[F, A] {
        override def discrete: Stream[F, A] =
          Stream(initial).covary[F] ++ source.observe1(a => ref.setSync(a))

        override def continuous: Stream[F, A] = Stream.repeatEval(get)

        override def get: F[A] = ref.get
      }
    }

  /**
    * In order to generate a Signal we have to effectfully run a stream so we
    * need an unsafeRunSync here.
    */
  private implicit def unsafeSignalArbitrary[A: Arbitrary]: Arbitrary[immutable.Signal[IO, A]] = {
    val gen = for {
      firstElem <- Arbitrary.arbitrary[A]
      finiteElems <- Arbitrary.arbitrary[List[A]]
    } yield {
      val finiteStream = Stream.emits(finiteElems).covary[IO]
      unsafeHold(firstElem, finiteStream)
    }
    Arbitrary(gen.map(_.unsafeRunSync()))
  }

  private type SignalIO[A] = immutable.Signal[IO, A]

  /**
    * We need an instance of Eq for the Discipline laws to work, but actually
    * running a Signal is effectful, so we have to resort to unsafely
    * performing the effect inside the equality check to have a valid Eq
    * instance.
    *
    * Moreover, equality of Signals is kind of murky. Since the exact discrete
    * stream and gets that you see are non-deterministic even if two observers
    * are looking at the same Signal, we need some notion of equality that is
    * robust to this non-determinism.
    *
    * We say that two Signals are equal if they converge to the same value. And
    * two streams converge to the same value if the "last" element of their
    * discrete streams match (where "last" means either truly the last element
    * if the discrete stream is finite or the last element seen if we pause
    * changes to the Signal for a while), calling get after the last element
    * of the discrete stream results in a match with the last element, and the
    * first (or any) element of the continuous stream called after the "last"
    * element of the discrete stream also matches.
    *
    * We will be testing with Signals generated by [[unsafeSignalArbitrary]],
    * which are always finite Signals, so we can just take the actual last
    * element of the discrete stream instead of doing some time-based wait for
    * the Signal to settle.
    */
  private implicit def unsafeSignalEquality[A: Eq]: Eq[SignalIO[A]] = new Eq[SignalIO[A]] {
    override def eqv(x: SignalIO[A], y: SignalIO[A]): Boolean = {
      val action = for {
        lastDiscreteX <- x.discrete.compile.last.map(_.get)
        lastDiscreteY <- y.discrete.compile.last.map(_.get)
        retrievedX <- x.get
        retrievedY <- y.get
        aContinuousX <- x.continuous.head.compile.last.map(_.get)
        aContinuousY <- y.continuous.head.compile.last.map(_.get)
      } yield {
        val lastDiscretesAreSame = Eq[A].eqv(lastDiscreteX, lastDiscreteY)
        val lastGetsAreTheSame = Eq[A].eqv(retrievedX, retrievedY)
        val continuousAfterGetIsTheSame = Eq[A].eqv(aContinuousX, aContinuousY)
        val lastDiscreteAgreesWithGet = Eq[A].eqv(lastDiscreteX, retrievedX)
        val continuousAfterGetAgreesWithGet = Eq[A].eqv(aContinuousX, retrievedX)

        lastDiscretesAreSame &&
        lastGetsAreTheSame &&
        continuousAfterGetIsTheSame &&
        lastDiscreteAgreesWithGet &&
        continuousAfterGetAgreesWithGet
      }
      action.unsafeRunSync()
    }
  }

  checkAll(
    "immutable.Signal",
    FunctorTests.apply[SignalIO](immutable.Signal.signalIsFunctor).functor[String, Int, Double]
  )

  private implicit val testExecutionContext: ExecutionContextExecutor =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))

  // Overlap between Functor and Applicative instances is probably causing this
  // need for an explicit Isomorphisms that would otherwise be implied by
  // Applicative. Note that this would go away if we accepted Applicative and
  // did away with the explicit Functor instance
  private implicit val isomorphismsInstance: Isomorphisms[SignalIO] =
    Isomorphisms.invariant[SignalIO](immutable.Signal.signalIsApplicative)

  checkAll(
    "immutable.Signal",
    ApplicativeTests.apply[SignalIO].applicative[String, Int, Double]
  )
}
