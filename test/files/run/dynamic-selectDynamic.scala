import scala.tools.partest.DirectTest

object Test extends DirectTest {

  override def extraSettings: String =
    s"-usejavacp -Vprint-pos -Vprint:typer -Ystop-after:typer -cp ${testOutput.path}"

  override def code = """
    object X {
      val d = new D
      d.field
    }
  """.trim

  override def show(): Unit = compile()
}

import language.dynamics
class D extends Dynamic {
  def selectDynamic(name: String) = ???
}
