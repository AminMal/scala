package scala.collection.mutable

import org.junit.Assert.assertEquals
import org.junit.Test

import scala.runtime.PStatics.VM_MaxArraySize
import scala.tools.testkit.AssertUtil.assertThrows

class ArrayBuilderTest {
  @Test
  def t12617: Unit = {
    val ab: ArrayBuilder[Unit] = ArrayBuilder.make[Unit]

    // ArrayBuilder.ofUnit.addAll doesn't iterate if the iterator has a `knownSize`
    ab.addAll(new Iterator[Unit] {
      override def knownSize: Int = VM_MaxArraySize
      def hasNext: Boolean = true
      def next(): Unit = ()
    })

    // reached maximum size without entering an infinite loop?
    assertEquals(ab.length, VM_MaxArraySize)

    // expect an exception when trying to grow larger than maximum size by addOne
    assertThrows[Exception](ab.addOne(()), _.endsWith("Requested length: 2147483640; current length: 2147483639"))

    val arr = Array[Unit]((), (), (), (), (), (), (), (), (), (), (), ())

    // expect an exception when trying to grow larger than maximum size by addAll(iterator)
    assertThrows[Exception](ab.addAll(arr.iterator), _.endsWith("Requested length: -2147483645; current length: 2147483639; increase: 12"))

    // expect an exception when trying to grow larger than maximum size by addAll(array)
    assertThrows[Exception](ab.addAll(arr))
  }
}
