/*                     __                                               *\
**     ________ ___   / /  ___      __ ____  Scala.js Test Suite        **
**    / __/ __// _ | / /  / _ | __ / // __/  (c) 2013, LAMP/EPFL        **
**  __\ \/ /__/ __ |/ /__/ __ |/_// /_\ \    http://scala-js.org/       **
** /____/\___/_/ |_/____/_/ | |__/ /____/                               **
**                          |/____/                                     **
\*                                                                      */
package scala.scalajs.test
package javalib

import scala.scalajs.test.JasmineTest
import scala.scalajs.js

import java.lang.{Double => JDouble}

import scala.util.Try

object DoubleTest extends JasmineTest {

  describe("java.lang.Double") {

    it("should provide proper `equals`") {
      expect(Double.box(0.0) == Double.box(-0.0)).toBeFalsy
      expect(Double.box(Double.NaN) == Double.box(Double.NaN)).toBeTruthy
    }

    it("should provide proper `toString`") {
      expect(0.0.toString).toEqual("0.0")
      expect(-0.0.toString).toEqual("-0.0")
      expect(Double.NaN.toString).toEqual("NaN")
      expect(5.0.toString).toEqual("5.0")
      expect(-5.0.toString).toEqual("-5.0")
      expect(1.2.toString).toEqual("1.2")
    }

    it("should parse strings") {
      expect("0.0".toDouble).toEqual(0.0f)
      expect("NaN".toDouble.isNaN).toBeTruthy
      expect(Try("asdf".toDouble).isFailure).toBeTruthy

      def test(s: String, v: Double): Unit = {
        expect(JDouble.parseDouble(s)).toBeCloseTo(v)
        expect(JDouble.valueOf(s).doubleValue()).toBeCloseTo(v)
        expect(new JDouble(s).doubleValue()).toBeCloseTo(v)
      }

      test("0", 0.0)
      test("5.3", 5.3)
      test("127e2", 12700.0)
      test("-123.4", -123.4)
      test("65432.1", 65432.10)
      test("-987654.321", -987654.321)
    }

    it("should provide `compareTo`") {
      def compare(x: Double, y: Double): Int =
        new JDouble(x).compareTo(new JDouble(y))

      expect(compare(0.0, 5.5)).toBeLessThan(0)
      expect(compare(10.5, 10.2)).toBeGreaterThan(0)
      expect(compare(-2.1, -1.0)).toBeLessThan(0)
      expect(compare(3.14, 3.14)).toEqual(0)

      // From compareTo's point of view, NaN is equal to NaN
      expect(compare(Double.NaN, Double.NaN)).toEqual(0)
    }

    it("should be a Comparable") {
      def compare(x: Any, y: Any): Int =
        x.asInstanceOf[Comparable[Any]].compareTo(y)

      expect(compare(0.0, 5.5)).toBeLessThan(0)
      expect(compare(10.5, 10.2)).toBeGreaterThan(0)
      expect(compare(-2.1, -1.0)).toBeLessThan(0)
      expect(compare(3.14, 3.14)).toEqual(0)

      // From compareTo's point of view, NaN is equal to NaN
      expect(compare(Double.NaN, Double.NaN)).toEqual(0)
    }

  }
}
