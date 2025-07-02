package test

trait BaseA { self: BaseB =>
  val a: Int = 0
  val b: Int = 0
  val c: Int = 0

}

trait A extends BaseA { self: B =>
  override val a: Int = 1
  override val b: Int = 2
  override val c: Int = 3
}

trait HasConnect {
  def d: Int
}

class BaseB extends BaseA with HasConnect
{
  def d = a + b + c
  println(s"before:$d")
}

class B extends BaseB with A

object B extends App {
  val b = new B
  println(b.d)
}