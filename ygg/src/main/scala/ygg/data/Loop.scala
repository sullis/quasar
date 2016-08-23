package ygg.data

import ygg.common._
import java.util.concurrent.ConcurrentHashMap

/**
  * This object contains some methods to do faster iteration over primitives.
  *
  * In particular it doesn't box, allocate intermediate objects, or use a (slow)
  * shared interface with scala collections.
  */
object Loop {
  @tailrec
  def range(i: Int, limit: Int)(f: Int => Unit) {
    if (i < limit) {
      f(i)
      range(i + 1, limit)(f)
    }
  }

  final def forall[@specialized A](as: Array[A])(f: A => Boolean): Boolean = {
    @tailrec def loop(i: Int): Boolean = i == as.length || f(as(i)) && loop(i + 1)

    loop(0)
  }
}

final class LazyMap[A, B, C](source: Map[A, B], f: B => C) extends Map[A, C] {
  private val m = new ConcurrentHashMap[A, C]()

  def iterator: Iterator[A -> C] = source.keysIterator map (a => a -> apply(a))

  def get(a: A): Option[C] = m get a match {
    case null =>
      source get a map { b =>
        val c = f(b)
        m.putIfAbsent(a, c)
        c
      }
    case x => Some(x)
  }
  def +[C1 >: C](kv: (A, C1)): Map[A, C1] = iterator.toMap + kv
  def -(a: A): Map[A, C]                  = iterator.toMap - a
}

final class AtomicIntIdSource[A](f: Int => A) {
  private val source         = new AtomicInt
  def nextId(): A            = f(source.getAndIncrement)
  def nextIdBlock(n: Int): A = f(source.getAndAdd(n + 1) - n)
}

final class AtomicLongIdSource {
  private val source             = new AtomicLong
  def nextId(): Long             = source.getAndIncrement
  def nextIdBlock(n: Long): Long = source.getAndAdd(n + 1) - n
}
