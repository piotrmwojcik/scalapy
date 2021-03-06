package me.shadaj.scalapy

import scala.collection.mutable

import scala.concurrent.Future

import me.shadaj.scalapy.interpreter.{CPythonInterpreter, PyValue}
import me.shadaj.scalapy.readwrite.{Reader, Writer}

package object py {
  def module(name: String) = Module(name)
  def module(name: String, subname: String) = Module(name, subname)

  @py.native trait None extends Any
  val None = Any.populateWith(CPythonInterpreter.noneValue).as[None]

  type NoneOr[T] = None | T

  def `with`[T <: py.Any, O](ref: T)(withValue: T => O): O = {
    ref.as[Dynamic](Reader.facadeReader[Dynamic](FacadeCreator.getCreator[Dynamic])).__enter__()
    try {
      withValue(ref)
    } finally {
      ref.as[Dynamic].__exit__(None, None, None)
    }
  }

  def local[T](f: => T): T = {
    PyValue.allocatedValues = List.empty[PyValue] :: PyValue.allocatedValues

    try {
      f
    } finally {
      PyValue.allocatedValues.head.foreach { c =>
        c.cleanup()
      }

      PyValue.allocatedValues = PyValue.allocatedValues.tail
    }
  }

  implicit class SeqConverters[T, C <% Seq[T]](seq: C) {
    def toPythonCopy(implicit elemWriter: Writer[T]): Any = {
      Any.populateWith(CPythonInterpreter.createListCopy(seq, elemWriter.write))
    }

    def toPythonProxy(implicit elemWriter: Writer[T]): Any = {
      Any.populateWith(CPythonInterpreter.createListProxy(seq, elemWriter.write))
    }
  }

  def eval(str: String): Dynamic = {
    Any.populateWith(CPythonInterpreter.load(str)).as[Dynamic]
  }

  final class PyQuotable(val variable: String) extends AnyVal {
    def cleanup() = CPythonInterpreter.cleanupVariableReference(variable)
  }

  object PyQuotable {
    implicit def fromAny(any: py.Any): PyQuotable = {
      new PyQuotable(CPythonInterpreter.getVariableReference(any.value))
    }

    implicit def fromValue[V](value: V)(implicit writer: Writer[V]): PyQuotable = {
      new PyQuotable(CPythonInterpreter.getVariableReference(writer.write(value)))
    }
  }

  implicit class PyQuote(private val sc: StringContext) extends AnyVal {
    def py(values: PyQuotable*): Dynamic = {
      val strings = sc.parts.iterator
      val expressions = values.iterator
      val buf = new StringBuffer(strings.next)
      while (strings.hasNext) {
        val expr = expressions.next
        buf.append(expr.variable)
        buf.append(strings.next)
      }

      try {
        eval(buf.toString)
      } finally {
        values.foreach(_.cleanup())
      }
    }
  }

  import scala.language.experimental.macros
  def native[T]: T = macro FacadeImpl.native_impl[T]
  def nativeNamed[T]: T = macro FacadeImpl.native_named_impl[T]
}
