package utils

import chisel3._
import chisel3.util._

object Compress {

  /**
    * compress function
    *
    * @param mask       0 1 0 0 1 1 0 0
    * @param raw        A B C D E F G H
    * @param compressed X X X X X B E F
    */
  def compress[T <: Data](mask: Vec[Bool], raw: Vec[T], compressed: Vec[T]) = {
    val len = mask.getWidth
    val idx = VecInit((0 until len).map(i =>
      if (i == 0) 0.U else PopCount(mask.asUInt(i - 1, 0))
    ))
    for (i <- 0 until len) {
      when(mask(i)) {
        compressed(idx(i)) := raw(i)
      }
    }
  }

}
