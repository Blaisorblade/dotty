package dotty.tools
package dotc
package core
package tasty

import util.Util.{bestFit, dble}
import TastyBuffer.{Addr, AddrWidth}
import config.Printers.pickling
import ast.untpd.Tree

class TreeBuffer extends TastyBuffer(50000) {

  private final val ItemsOverOffsets = 2
  private val initialOffsetSize = bytes.length / (AddrWidth * ItemsOverOffsets)
  private var offsets = new Array[Int](initialOffsetSize)
  private var isRelative = new Array[Boolean](initialOffsetSize)
  private var delta: Array[Int] = _
  private var numOffsets = 0

  private type TreeAddrs = Any // really: Addr | List[Addr]

  /** A map from trees to the address(es) at which a tree is pickled. There may be several
   *  such addresses if the tree is shared. To keep the map compact, the value type is a
   *  disjunction of a single address (which is the common case) and a list of addresses.
   */
  private val treeAddrs = new java.util.IdentityHashMap[Tree, TreeAddrs]

  def registerTreeAddr(tree: Tree) =
    treeAddrs.put(tree,
      treeAddrs.get(tree) match {
        case null => currentAddr
        case x: Addr => x :: currentAddr :: Nil
        case xs: List[_] => xs :+ currentAddr
      })

  def addrsOfTree(tree: Tree): List[Addr] = treeAddrs.get(tree) match {
    case null => Nil
    case addr: Addr => addr :: Nil
    case addrs: List[Addr] => addrs
  }

  private def offset(i: Int): Addr = Addr(offsets(i))

  private def keepOffset(relative: Boolean): Unit = {
    if (numOffsets == offsets.length) {
      offsets = dble(offsets)
      isRelative = dble(isRelative)
    }
    offsets(numOffsets) = length
    isRelative(numOffsets) = relative
    numOffsets += 1
  }

  /** Reserve space for a reference, to be adjusted later */
  def reserveRef(relative: Boolean): Addr = {
    val addr = currentAddr
    keepOffset(relative)
    reserveAddr()
    addr
  }

  /** Write reference right adjusted into freshly reserved field. */
  def writeRef(target: Addr) = {
    keepOffset(relative = false)
    fillAddr(reserveAddr(), target)
  }

  /** Fill previously reserved field with a reference */
  def fillRef(at: Addr, target: Addr, relative: Boolean) = {
    val addr = if (relative) target.relativeTo(at) else target
    fillAddr(at, addr)
  }

  /** The amount by which the bytes at the given address are shifted under compression */
  def deltaAt(at: Addr): Int = {
    val idx = bestFit(offsets, numOffsets, at.index - 1)
    if (idx < 0) 0 else delta(idx)
  }

  /** The address to which `x` is translated under compression */
  def adjusted(x: Addr): Addr = x - deltaAt(x)

  /** Compute all shift-deltas */
  private def computeDeltas() = {
    delta = new Array[Int](numOffsets)
    var lastDelta = 0
    var i = 0
    while (i < numOffsets) {
      val off = offset(i)
      val skippedOff = skipZeroes(off)
      val skippedCount = skippedOff.index - off.index
      assert(skippedCount < AddrWidth, s"unset field at position $off")
      lastDelta += skippedCount
      delta(i) = lastDelta
      i += 1
    }
  }

  /** The absolute or relative adjusted address at index `i` of `offsets` array*/
  private def adjustedOffset(i: Int): Addr = {
    val at = offset(i)
    val original = getAddr(at)
    if (isRelative(i)) {
      val start = skipNat(at)
      val len1 = original + delta(i) - deltaAt(original + start.index)
      val len2 = adjusted(original + start.index) - adjusted(start).index
      assert(len1 == len2,
          s"adjusting offset #$i: $at, original = $original, len1 = $len1, len2 = $len2")
      len1
    } else adjusted(original)
  }

  /** Adjust all offsets according to previously computed deltas */
  private def adjustOffsets(): Unit = {
    for (i <- 0 until numOffsets) {
      val corrected = adjustedOffset(i)
      fillAddr(offset(i), corrected)
    }
  }

  /** Adjust deltas to also take account references that will shrink (and thereby
   *  generate additional zeroes that can be skipped) due to previously
   *  computed adjustments.
   */
  private def adjustDeltas(): Int = {
    val delta1 = new Array[Int](delta.length)
    var lastDelta = 0
    var i = 0
    while (i < numOffsets) {
      val corrected = adjustedOffset(i)
      lastDelta += AddrWidth - TastyBuffer.natSize(corrected.index)
      delta1(i) = lastDelta
      i += 1
    }
    val saved =
      if (numOffsets == 0) 0
      else delta1(numOffsets - 1) - delta(numOffsets - 1)
    delta = delta1
    saved
  }

  /** Compress pickle buffer, shifting bytes to close all skipped zeroes. */
  private def compress(): Int = {
    var lastDelta = 0
    var start = 0
    var i = 0
    var wasted = 0
    def shift(end: Int) =
      Array.copy(bytes, start, bytes, start - lastDelta, end - start)
    while (i < numOffsets) {
      val next = offsets(i)
      shift(next)
      start = next + delta(i) - lastDelta
      val pastZeroes = skipZeroes(Addr(next)).index
      assert(pastZeroes >= start, s"something's wrong: eliminated non-zero")
      wasted += (pastZeroes - start)
      lastDelta = delta(i)
      i += 1
    }
    shift(length)
    length -= lastDelta
    wasted
  }

  def adjustTreeAddrs(): Unit = {
    val it = treeAddrs.keySet.iterator
    while (it.hasNext) {
      val tree = it.next
      treeAddrs.get(tree) match {
        case addr: Addr => treeAddrs.put(tree, adjusted(addr))
        case addrs: List[Addr] => treeAddrs.put(tree, addrs.map(adjusted))
      }
    }
  }

  /** Final assembly, involving the following steps:
   *   - compute deltas
   *   - adjust deltas until additional savings are < 1% of total
   *   - adjust offsets according to the adjusted deltas
   *   - shrink buffer, skipping zeroes.
   */
  def compactify(): Unit = {
    val origLength = length
    computeDeltas()
    //println(s"offsets: ${offsets.take(numOffsets).deep}")
    //println(s"deltas: ${delta.take(numOffsets).deep}")
    var saved = 0
    do {
      saved = adjustDeltas()
      pickling.println(s"adjusting deltas, saved = $saved")
    } while (saved > 0 && length / saved < 100)
    adjustOffsets()
    adjustTreeAddrs()
    val wasted = compress()
    pickling.println(s"original length: $origLength, compressed to: $length, wasted: $wasted") // DEBUG, for now.
  }
}
