//> using options -Xsource:3 -deprecation -Werror -Xsource-features:eta-expand-always
//
trait AcciSamZero { def apply(): Int }

@FunctionalInterface
trait SamZero { def apply(): Int }

class A {
  def boom(): Unit
}

class EtaExpand214 {
  def m1 = 1
  def m2() = 1
  def m3(x: Int) = x

  val t1: () => Any  = m1   // error
  val t2: () => Any  = m2   // eta-expanded, only warns w/ -Xlint:eta-zero
  val t2AcciSam: AcciSamZero = m2 // error, nilary methods don't eta-expand to SAM types
  val t2Sam: SamZero = m2         // error, nilary methods don't eta-expand to SAM types
  val t3: Int => Any = m3   // ok

  val t4 = m1 // apply
  val t5 = m2 // warn: apply, ()-insertion
  val t6 = m3 // eta-expansion in 3.0

  val t4a: Int        = t4 // ok
  val t5a: Int        = t5 // ok
  val t6a: Int => Any = t6 // ok

  val t7 = m1 _ // error: eta-expanding a nullary method
  val t8 = m2 _
  val t9 = m3 _

  val t7a: () => Any  = t7 // error: t7 is an error
  val t8a: () => Any  = t8 // ok
  val t9a: Int => Any = t9 // ok

  val a = new A
  a.boom // warning: apply, ()-insertion

  import scala.collection.mutable.Map
  val xs = Map(1 -> "foo")
  val ys = xs.clone // ok
}
