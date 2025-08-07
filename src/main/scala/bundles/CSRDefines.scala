package bundles

import bundles.CSRFunc._
import chisel3._
import scala.language.experimental.macros
import scala.reflect.runtime.{universe => ru}

object CSRDefines {
  object CSRField1Bits extends CSREnum with CSRMacroApply

  object CSRField2Bits extends CSREnum with CSRMacroApply

  object CSRField3Bits extends CSREnum with CSRMacroApply

  object CSRField4Bits extends CSREnum with CSRMacroApply

  object CSRField5Bits extends CSREnum with CSRMacroApply

  object CSRField6Bits extends CSREnum with CSRMacroApply

  object CSRField7Bits extends CSREnum with CSRMacroApply

  object CSRField8Bits extends CSREnum with CSRMacroApply

  object CSRField9Bits extends CSREnum with CSRMacroApply

  object CSRField10Bits extends CSREnum with CSRMacroApply

  object CSRField11Bits extends CSREnum with CSRMacroApply

  object CSRField12Bits extends CSREnum with CSRMacroApply

  object CSRField13Bits extends CSREnum with CSRMacroApply

  object CSRField14Bits extends CSREnum with CSRMacroApply

  object CSRField15Bits extends CSREnum with CSRMacroApply

  object CSRField16Bits extends CSREnum with CSRMacroApply

  object CSRField17Bits extends CSREnum with CSRMacroApply

  object CSRField18Bits extends CSREnum with CSRMacroApply

  object CSRField19Bits extends CSREnum with CSRMacroApply

  object CSRField20Bits extends CSREnum with CSRMacroApply

  object CSRField21Bits extends CSREnum with CSRMacroApply

  object CSRField22Bits extends CSREnum with CSRMacroApply

  object CSRField23Bits extends CSREnum with CSRMacroApply

  object CSRField24Bits extends CSREnum with CSRMacroApply

  object CSRField25Bits extends CSREnum with CSRMacroApply

  object CSRField26Bits extends CSREnum with CSRMacroApply

  object CSRField27Bits extends CSREnum with CSRMacroApply

  object CSRField28Bits extends CSREnum with CSRMacroApply

  object CSRField29Bits extends CSREnum with CSRMacroApply

  object CSRField30Bits extends CSREnum with CSRMacroApply

  object CSRField31Bits extends CSREnum with CSRMacroApply

  object CSRField32Bits extends CSREnum with CSRMacroApply

  object CSRField33Bits extends CSREnum with CSRMacroApply

  object CSRField34Bits extends CSREnum with CSRMacroApply

  object CSRField35Bits extends CSREnum with CSRMacroApply

  object CSRField36Bits extends CSREnum with CSRMacroApply

  object CSRField37Bits extends CSREnum with CSRMacroApply

  object CSRField38Bits extends CSREnum with CSRMacroApply

  object CSRField39Bits extends CSREnum with CSRMacroApply

  object CSRField40Bits extends CSREnum with CSRMacroApply

  object CSRField41Bits extends CSREnum with CSRMacroApply

  object CSRField42Bits extends CSREnum with CSRMacroApply

  object CSRField43Bits extends CSREnum with CSRMacroApply

  object CSRField44Bits extends CSREnum with CSRMacroApply

  object CSRField45Bits extends CSREnum with CSRMacroApply

  object CSRField46Bits extends CSREnum with CSRMacroApply

  object CSRField47Bits extends CSREnum with CSRMacroApply

  object CSRField48Bits extends CSREnum with CSRMacroApply

  object CSRField49Bits extends CSREnum with CSRMacroApply

  object CSRField50Bits extends CSREnum with CSRMacroApply

  object CSRField51Bits extends CSREnum with CSRMacroApply

  object CSRField52Bits extends CSREnum with CSRMacroApply

  object CSRField53Bits extends CSREnum with CSRMacroApply

  object CSRField54Bits extends CSREnum with CSRMacroApply

  object CSRField55Bits extends CSREnum with CSRMacroApply

  object CSRField56Bits extends CSREnum with CSRMacroApply

  object CSRField57Bits extends CSREnum with CSRMacroApply

  object CSRField58Bits extends CSREnum with CSRMacroApply

  object CSRField59Bits extends CSREnum with CSRMacroApply

  object CSRField60Bits extends CSREnum with CSRMacroApply

  object CSRField61Bits extends CSREnum with CSRMacroApply

  object CSRField62Bits extends CSREnum with CSRMacroApply

  object CSRField63Bits extends CSREnum with CSRMacroApply

  object CSRField64Bits extends CSREnum with CSRMacroApply

  object ReflectHelper {
    val mirror: ru.Mirror = ru.runtimeMirror(getClass.getClassLoader)

    def getCSRFieldMethodMirror(typeString: String, msb: Int, lsb: Int): ru.MethodMirror = {
      val moduleSymbol = mirror.typeOf[CSRDefines.type].termSymbol
        .info.decl(ru.TermName(s"CSRField${msb - lsb + 1}Bits")).asModule

      val methodSymbol = moduleSymbol
        .info.member(ru.TermName(typeString)).asMethod

      val instanceMirror: ru.InstanceMirror = mirror.reflect(mirror.reflectModule(moduleSymbol).instance)
      val methodMirror:   ru.MethodMirror   = instanceMirror.reflectMethod(methodSymbol)

      methodMirror
    }
  }

  object CSRWARLField {
    private def helper(msb: Int, lsb: Int, wfn: CSRWfnType, rfn: CSRRfnType): CSREnumType = {
      val methodMirror = ReflectHelper.getCSRFieldMethodMirror("WARL", msb, lsb)
      methodMirror.apply(msb, lsb, wfn, rfn).asInstanceOf[CSREnumType]
    }

    def apply(msb: Int, lsb: Int, fn: CSRRfnType): CSREnumType = this.helper(msb, lsb, null, fn)

    def apply(bit: Int, fn: CSRRfnType): CSREnumType = this.helper(bit, bit, null, fn)

    def apply(msb: Int, lsb: Int, fn: CSRWfnType): CSREnumType = this.helper(msb, lsb, fn, null)

    def apply(bit: Int, fn: CSRWfnType): CSREnumType = this.helper(bit, bit, fn, null)
  }

  object CSRROField {
    private def helper(msb: Int, lsb: Int, rfn: CSRRfnType): CSREnumType = {
      val methodMirror = ReflectHelper.getCSRFieldMethodMirror("RO", msb, lsb)
      methodMirror.apply(msb, lsb, rfn).asInstanceOf[CSREnumType]
    }

    def apply(msb: Int, lsb: Int, rfn: CSRRfnType): CSREnumType = this.helper(msb, lsb, rfn)

    def apply(bit: Int, rfn: CSRRfnType): CSREnumType = this.helper(bit, bit, rfn)

    def apply(msb: Int, lsb: Int): CSREnumType = this.helper(msb, lsb, null)

    def apply(bit: Int): CSREnumType = this.helper(bit, bit, null)
  }

  object CSRRWField {
    private def helper(msb: Int, lsb: Int): CSREnumType = {
      val methodMirror: ru.MethodMirror = ReflectHelper.getCSRFieldMethodMirror("RW", msb, lsb)
      methodMirror.apply(msb, lsb).asInstanceOf[CSREnumType]
    }

    def apply(msb: Int, lsb: Int): CSREnumType = this.helper(msb, lsb)

    def apply(bit: Int): CSREnumType = this.helper(bit, bit)
  }

  object CSRWLRLField {
    private def helper(msb: Int, lsb: Int): CSREnumType = {
      val methodMirror: ru.MethodMirror = ReflectHelper.getCSRFieldMethodMirror("WLRL", msb, lsb)
      methodMirror.apply(msb, lsb).asInstanceOf[CSREnumType]
    }

    def apply(msb: Int, lsb: Int): CSREnumType = this.helper(msb, lsb)
  }
}
