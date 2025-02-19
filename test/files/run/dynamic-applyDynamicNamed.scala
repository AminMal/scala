import scala.tools.partest.DirectTest

object Test extends DirectTest {

  override def extraSettings: String =
    s"-usejavacp -Vprint-pos -Vprint:typer -Ystop-after:typer -cp ${testOutput.path}"

  override def code = """
    object X {
      val d = new D
      d.meth(value1 = 10, value2 = 100)
      d(value1 = 10)
    }
  """.trim

  override def show(): Unit = compile()
}

import language.dynamics
class D extends Dynamic {
  def applyDynamicNamed(name: String)(value: (String, Any)*) = ???
}
