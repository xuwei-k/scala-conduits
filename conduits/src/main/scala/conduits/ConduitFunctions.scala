package conduits

import scalaz.Monad
import pipes._
import Pipe._
import scalaz.effect.IO
import resourcet.{ReleaseKey, MonadResource}

/**
* User: arjan
*/
trait ConduitStateResult[S, A, B] {
  def fold[Z](finished: (=> Option[A], => Stream[B]) => Z, producing: (=> S, => Stream[B]) => Z): Z
}

trait ConduitIOResult[A, B] {
  def fold[Z](finished: (=> Option[A], => Stream[B]) => Z, producing: (=> Stream[B]) => Z): Z
}

object ConduitFunctions {
  import FoldUtils._
  object StateFinished {
    def apply[S, A, B](maybeInput: => Option[A], output: => Stream[B]) = new ConduitStateResult[S, A, B] {
      def fold[Z](finished: (=> Option[A], => Stream[B]) => Z, producing: (=> S, => Stream[B]) => Z) = finished(maybeInput, output)
    }
    def unapply[S, A, B](r: ConduitStateResult[S, A, B]): Option[(Option[A], Stream[B])] = r.fold((i, o) => Some(i, o), ToNone2)
  }
  object StateProducing {
    def apply[S, A, B](state: => S, output: => Stream[B]) = new ConduitStateResult[S, A, B] {
      def fold[Z](finished: (=> Option[A], => Stream[B]) => Z, producing: (=> S, => Stream[B]) => Z) = producing(state, output)
    }
    def unapply[S, A, B](r: ConduitStateResult[S, A, B]): Option[(S, Stream[B])] = r.fold(ToNone2, (s, o) => Some(s, o))
  }

  object IOFinished {
    def apply[A, B](maybeInput: => Option[A], output: => Stream[B]) = new ConduitIOResult[A, B] {
      def fold[Z](finished: (=> Option[A], => Stream[B]) => Z, producing: (=> Stream[B]) => Z) = finished(maybeInput, output)
    }
    def unapply[A, B](r: ConduitIOResult[A, B]): Option[(Option[A], Stream[B])] = r.fold((i, o) => Some(i, o), ToNone)
  }

  object IOProducing {
    def apply[A, B](output: => Stream[B]) = new ConduitIOResult[A, B] {
      def fold[Z](finished: (=> Option[A], => Stream[B]) => Z, producing: (=> Stream[B]) => Z) = producing(output)
    }
    def unapply[A, B](r: ConduitIOResult[A, B]): Option[(Stream[B])] = r.fold(ToNone2, Some(_))
  }

  /**
   * Construct a 'Conduit' with some stateful functions. This function addresses
   * threading the state value for you.
   */
  def conduitState[S, A, F[_], B](state: => S, push: (=> S) => (=> A) => F[ConduitStateResult[S, A, B]], close: (=> S) => F[Stream[B]])(implicit M: Monad[F]): Conduit[A, F, B] = {
    def push1(state1: S)(input: A): Conduit[A, F, B] = PipeM(
      M.map(push(state)(input))(r => goRes(r))
      , M.point(()))

    def close1(state: S): Pipe[A, B, F, Unit] = PipeM(M.bind(close(state))(os => M.point(fromList(os))), M.point(()))

    def goRes(res: ConduitStateResult[S, A, B]): Conduit[A, F, B] = res.fold(
      finished = (leftover, output) => haveMore[A, F, B](Done(leftover, ()), M.point(()), output)
     , producing = (state, output) => haveMore[A, F, B](NeedInput(push1(state), close1(state)), M.point(()), output)
    )
    NeedInput(push1(state), close1(state))
  }

  /*Construct a Conduit*/
  def conduitIO[F[_], A, B, S](alloc: IO[S], cleanup: S => IO[Unit], push: S => A => F[ConduitIOResult[A, B]], close: S => F[Stream[B]])(implicit M0: MonadResource[F]): Conduit[A, F, B] = {
    implicit val M = M0.MO
    def push1(key: => ReleaseKey, state: => S, input: => A): F[Conduit[A, F, B]] = {
      M.bind(push(state)(input))(res => res.fold(
        finished = (leftover, output) => M.bind(M0.release(key))(_ => M.point(haveMore(Done(leftover, ()), M.point(()), output)))
        ,
        producing = output => M.point(haveMore(
          NeedInput(i => PipeM(push1(key, state, i), M0.release(key)), close1(key, state))
          , M.bind(M0.release(key))(_ => M.point(()))
          , output
        ))))
    }
    def close1(key: ReleaseKey, state: S): Pipe[A, B, F, Unit] =
      PipeM(M.bind(close(state))(output => M.bind(M0.release(key))(_ => M.point(fromList(output)))), M0.release(key))

    NeedInput(input =>
              PipeM(M.bind(M0.allocate(alloc, cleanup))(ks =>
                push1(ks._1, ks._2, input)), M.point(()))
              , PipeM(M.bind(M0.allocate(alloc, cleanup))(ks =>
               M.bind(close(ks._2))(os =>
                 M.bind(M0.release(ks._1))(_ => M.point(fromList(os))))), M.point(())
               ))
  }

  def haveMore[A, F[_], B](res: Conduit[A, F, B], close: F[Unit], bs: Stream[B])(implicit M: Monad[F]): Conduit[A, F, B] = bs match {
    case Stream.Empty => res
    case x #:: xs => HaveOutput(haveMore(res, close, xs), close, x)
  }

  def fromList[F[_], A, I](as: Stream[A])(implicit M: Monad[F]): Pipe[I, A, F, Unit] = as match {
    case Stream.Empty => Done(None, ())
    case x #:: xs => HaveOutput[I, A, F, Unit](fromList(xs), M.point(()), x)
  }
}