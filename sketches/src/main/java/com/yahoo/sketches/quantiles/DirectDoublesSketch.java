/*
 * Copyright 2016, Yahoo! Inc. Licensed under the terms of the
 * Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.quantiles;

import static com.yahoo.sketches.quantiles.PreambleUtil.COMPACT_FLAG_MASK;
import static com.yahoo.sketches.quantiles.PreambleUtil.EMPTY_FLAG_MASK;
import static com.yahoo.sketches.quantiles.PreambleUtil.ORDERED_FLAG_MASK;
import static com.yahoo.sketches.quantiles.PreambleUtil.READ_ONLY_FLAG_MASK;
import static com.yahoo.sketches.quantiles.PreambleUtil.extractFamilyID;
import static com.yahoo.sketches.quantiles.PreambleUtil.extractFlags;
import static com.yahoo.sketches.quantiles.PreambleUtil.extractK;
import static com.yahoo.sketches.quantiles.PreambleUtil.extractMaxDouble;
import static com.yahoo.sketches.quantiles.PreambleUtil.extractMinDouble;
import static com.yahoo.sketches.quantiles.PreambleUtil.extractN;
import static com.yahoo.sketches.quantiles.PreambleUtil.extractPreLongs;
import static com.yahoo.sketches.quantiles.PreambleUtil.extractSerVer;
import static com.yahoo.sketches.quantiles.PreambleUtil.insertFamilyID;
import static com.yahoo.sketches.quantiles.PreambleUtil.insertFlags;
import static com.yahoo.sketches.quantiles.PreambleUtil.insertIntoBaseBuffer;
import static com.yahoo.sketches.quantiles.PreambleUtil.insertK;
import static com.yahoo.sketches.quantiles.PreambleUtil.insertMaxDouble;
import static com.yahoo.sketches.quantiles.PreambleUtil.insertMinDouble;
import static com.yahoo.sketches.quantiles.PreambleUtil.insertN;
import static com.yahoo.sketches.quantiles.PreambleUtil.insertPreLongs;
import static com.yahoo.sketches.quantiles.PreambleUtil.insertSerVer;
import static com.yahoo.sketches.quantiles.Util.computeBitPattern;

import java.util.Arrays;

import com.yahoo.memory.Memory;
import com.yahoo.memory.MemoryRegion;
import com.yahoo.memory.MemoryUtil;
import com.yahoo.sketches.Family;
import com.yahoo.sketches.SketchesArgumentException;

/**
 * Implements the DoublesSketch off-heap.
 *
 * @author Kevin Lang
 * @author Lee Rhodes
 */
final class DirectDoublesSketch extends DoublesSketch {
  private static final int MIN_DIRECT_DOUBLES_SER_VER = 3;
  private Memory mem_;
  private Object memObj_;
  private long memAdd_; //

  //**CONSTRUCTORS**********************************************************
  private DirectDoublesSketch(final int k) {
    super(k); //Checks k
  }

  /**
   * Obtains a new Direct instance of a DoublesSketch, which may be off-heap.
   *
   * @param k Parameter that controls space usage of sketch and accuracy of estimates.
   * Must be greater than 1 and less than 65536 and a power of 2.
   * @param dstMem the destination Memory that will be initialized to hold the data for this sketch.
   * It must initially be at least (16 * k + 32) bytes. As it grows it will request more memory
   * using the MemoryRequest callback.
   * @return a DirectDoublesSketch
   */
  static DirectDoublesSketch newInstance(final int k, final Memory dstMem) {
    checkDirectMemCapacity(k, 0, dstMem.getCapacity());

    final Object memObj = dstMem.array();
    final long memAdd = dstMem.getCumulativeOffset(0L);

    //initialize dstMem
    insertPreLongs(memObj, memAdd, 2);
    insertSerVer(memObj, memAdd, DoublesSketch.DOUBLES_SER_VER);
    insertFamilyID(memObj, memAdd, Family.QUANTILES.getID());
    final int flags = EMPTY_FLAG_MASK; //empty
    insertFlags(memObj, memAdd, flags);
    insertK(memObj, memAdd, k);
    insertN(memObj, memAdd, 0L);
    insertMinDouble(memObj, memAdd, Double.POSITIVE_INFINITY);
    insertMaxDouble(memObj, memAdd, Double.NEGATIVE_INFINITY);

    final DirectDoublesSketch dds = new DirectDoublesSketch(k);
    dds.mem_ = dstMem;
    dds.memObj_ = memObj;
    dds.memAdd_ = memAdd;
    return dds;
  }

  /**
   * Wrap this sketch around the given non-compact Memory image of a DoublesSketch.
   *
   * @param srcMem the given non-compact Memory image of a DoublesSketch that may have data,
   * @return a sketch that wraps the given srcMem
   */
  static DirectDoublesSketch wrapInstance(final Memory srcMem) {
    final long memCap = srcMem.getCapacity();
    final Object memObj = srcMem.array(); //may be null
    final long memAdd = srcMem.getCumulativeOffset(0L);

    //Extract the preamble, assumes at least 8 bytes
    final int preLongs = extractPreLongs(memObj, memAdd);
    final int serVer = extractSerVer(memObj, memAdd);
    final int familyID = extractFamilyID(memObj, memAdd);
    final int flags = extractFlags(memObj, memAdd);
    final int k = extractK(memObj, memAdd);
    final boolean empty = (flags & EMPTY_FLAG_MASK) > 0;

    checkDirectMemCapacity(k, 0, memCap); //check for absolute min memCap
    final long n = extractN(memObj, memAdd);

    //VALIDITY CHECKS
    checkPreLongs(preLongs);
    DoublesUtil.checkDoublesSerVer(serVer, MIN_DIRECT_DOUBLES_SER_VER);
    Util.checkFamilyID(familyID);
    checkDirectFlags(flags); //Cannot be compact
    Util.checkK(k);
    checkDirectMemCapacity(k, n, memCap);
    checkCompact(serVer, flags);
    checkEmptyAndN(empty, n);

    final DirectDoublesSketch dds = new DirectDoublesSketch(k);
    dds.mem_ = srcMem;
    dds.memObj_ = memObj;
    dds.memAdd_ = memAdd;
    return dds;
  }

  @Override
  public void update(final double dataItem) {
    if (Double.isNaN(dataItem)) { return; }
    final double maxValue = getMaxValue();
    final double minValue = getMinValue();

    if (dataItem > maxValue) { putMaxValue(dataItem); }
    if (dataItem < minValue) { putMinValue(dataItem); }

    int bbCount = getBaseBufferCount();
    insertIntoBaseBuffer(memObj_, memAdd_, bbCount++, dataItem);
    final long newN = getN() + 1;
    insertFlags(memObj_, memAdd_, 0); //not compact, not ordered, not empty

    if (bbCount == 2 * k_) { //Propagate
      final int curCombBufItemCap = getCombinedBufferItemCapacity(); //K, prev N, Direct case

      // make sure there will be enough levels for the propagation
      final int itemSpaceNeeded = DoublesUpdateImpl.getRequiredItemCapacity(k_, newN);

      //check capacity
      if (itemSpaceNeeded > curCombBufItemCap) {
        // copies base buffer plus old levels, adds space for new level
        mem_ = growCombinedMemBuffer(mem_, itemSpaceNeeded);
        memObj_ = mem_.array(); //may be null
        memAdd_ = mem_.getCumulativeOffset(0L);
      }

      //sort the base buffer
      sortMemory(mem_, 32L, k_ << 1);
      final MemoryRegion memRegion = new MemoryRegion(mem_, 32L, mem_.getCapacity() - 32L);

      final long newBitPattern = DoublesUpdateImpl.inPlacePropagateMemCarry(
        0,       //starting level
        null, 0, //optSrcKBuf, optSrcKBufStrt:  not needed here
        memRegion, 0, //size2Kbuf, size2KStart: the base buffer
        true,    //doUpdateVersion
        k_,
        memRegion,    //the base buffer = the Combined Buffer
        getBitPattern()    //current bitPattern prior to updating n
      );
      assert newBitPattern == computeBitPattern(k_, newN); // internal consistency check
      //bit pattern on direct is always derived, no need to save it.
    }
    putN(newN);
  }

  @Override
  public long getN() {
    return extractN(memObj_, memAdd_);
  }

  @Override
  public boolean isDirect() {
    return true;
  }

  @Override
  public double getMinValue() {
    return extractMinDouble(memObj_, memAdd_);
  }

  @Override
  public double getMaxValue() {
    return extractMaxDouble(memObj_, memAdd_);
  }

  @Override
  public void reset() {
    insertN(memObj_, memAdd_, 0L);
    insertMinDouble(memObj_, memAdd_, Double.POSITIVE_INFINITY);
    insertMaxDouble(memObj_, memAdd_, Double.NEGATIVE_INFINITY);
    insertFlags(memObj_, memAdd_, EMPTY_FLAG_MASK); //not compact, not ordered
  }

  //Restricted overrides
  //Gets

  @Override
  int getBaseBufferCount() {
    return Util.computeBaseBufferItems(getK(), getN());
  }

  @Override
  int getCombinedBufferItemCapacity() {
    return Util.computeCombinedBufferItemCapacity(getK(), getN(), false); //no partial BB allowed
  }

  @Override
  double[] getCombinedBuffer() {
    final int k = getK();
    if (isEmpty()) { return new double[k << 1]; } //2K
    final long n = getN();
    final int itemCap = Util.computeCombinedBufferItemCapacity(k, n, false);
    final double[] combinedBuffer = new double[itemCap];
    mem_.getDoubleArray(32, combinedBuffer, 0, itemCap);
    return combinedBuffer;
  }

  @Override
  long getBitPattern() {
    final int k = getK();
    final long n = getN();
    return Util.computeBitPattern(k, n);
  }

  @Override
  Memory getMemory() {
    return mem_;
  }

  //Puts

  @Override
  void putMinValue(final double minValue) {
    insertMinDouble(memObj_, memAdd_, minValue);
  }

  @Override
  void putMaxValue(final double maxValue) {
    insertMaxDouble(memObj_, memAdd_, maxValue);
  }

  @Override
  void putN(final long n) {
    insertN(memObj_, memAdd_, n);
  }

  @Override
  void putCombinedBuffer(final double[] combinedBuffer) {
    mem_.putDoubleArray(32, combinedBuffer, 0, combinedBuffer.length);
  }

  @Override
  void putCombinedBufferItemCapacity(final int combBufItemCap) {
    //intentionally a no-op, not kept on-heap, always derived.
  }

  @Override
  void putBaseBufferCount(final int baseBufferCount) {
    //intentionally a no-op, not kept on-heap, always derived.
  }

  @Override
  void putBitPattern(final long bitPattern) {
    //intentionally a no-op, not kept on-heap, always derived.
  }

  @Override
  double[] growCombinedBuffer(final int curCombBufItemCap, final int itemSpaceNeeded) {
    final long memBytes = mem_.getCapacity();
    final int needBytes = (itemSpaceNeeded << 3) + 32; //+ preamble + min, max
    if ((needBytes) > memBytes) {
      final Memory newMem = MemoryUtil.memoryRequestHandler(mem_, needBytes, true);
      //the free has already been handled
      mem_ = newMem;
    }
    //mem is large enough and data may already be there
    final double[] newCombBuf = new double[itemSpaceNeeded];
    mem_.getDoubleArray(32, newCombBuf, 0, curCombBufItemCap);
    return newCombBuf;
  }

  //Direct supporting methods

  static Memory growCombinedMemBuffer(final Memory mem, final int itemSpaceNeeded) {
    final long memBytes = mem.getCapacity();
    final int needBytes = (itemSpaceNeeded << 3) + 32; //+ preamble + min & max
    if ((needBytes) > memBytes) {
      final Memory newMem = MemoryUtil.memoryRequestHandler(mem, needBytes, true);
      //the free has already been handled
      return newMem;
    }
    return mem;
  }

  static final void sortMemory(final Memory mem, final long offsetBytes, final int lengthItems) {
    final double[] baseBuffer = new double[lengthItems];
    mem.getDoubleArray(offsetBytes, baseBuffer, 0, lengthItems);
    Arrays.sort(baseBuffer, 0, lengthItems);
    mem.putDoubleArray(offsetBytes, baseBuffer, 0, lengthItems);
  }

  //Checks

  /**
   * Checks the validity of the direct memory capacity assuming n, k.
   * @param k the given value of k
   * @param n the given value of n
   * @param memCapBytes the current memory capacity in bytes
   */
  static void checkDirectMemCapacity(final int k, final long n, final long memCapBytes) {
    final int metaPre = DoublesSketch.MAX_PRELONGS + 2; //plus min, max
    final int totLevels = Util.computeNumLevelsNeeded(k, n);

    final int reqBufBytes = (metaPre + (2 + totLevels) * k) << 3;

    if (memCapBytes < reqBufBytes) {
      throw new SketchesArgumentException("Possible corruption: Memory capacity too small: "
          + memCapBytes + " < " + reqBufBytes);
    }
  }

  static void checkCompact(final int serVer, final int flags) {
    final boolean compact = (serVer == 2) | ((flags & COMPACT_FLAG_MASK) > 0);
    if (compact) {
      throw new SketchesArgumentException("Compact Memory is not supported for Wrap Instance.");
    }
  }

  static void checkPreLongs(final int preLongs) {
    if ((preLongs < 1) || (preLongs > 2)) {
      throw new SketchesArgumentException(
          "Possible corruption: PreLongs must be 1 or 2: " + preLongs);
    }
  }

  static void checkDirectFlags(final int flags) {
    final int allowedFlags = //Cannot be compact!
        READ_ONLY_FLAG_MASK | EMPTY_FLAG_MASK | ORDERED_FLAG_MASK;
    final int flagsMask = ~allowedFlags;
    if ((flags & flagsMask) > 0) {
      throw new SketchesArgumentException(
         "Possible corruption: Invalid flags field: Cannot be compact! "
             + Integer.toBinaryString(flags));
    }
  }

  static void checkEmptyAndN(final boolean empty, final long n) {
    if (empty && (n > 0)) {
      throw new SketchesArgumentException(
          "Possible corruption: Empty Flag = true and N > 0: " + n);
    }
  }
}
