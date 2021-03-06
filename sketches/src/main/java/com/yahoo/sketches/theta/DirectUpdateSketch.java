/*
 * Copyright 2015-16, Yahoo! Inc.
 * Licensed under the terms of the Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.theta;

import static com.yahoo.sketches.Util.MIN_LG_NOM_LONGS;

import com.yahoo.sketches.ResizeFactor;
import com.yahoo.sketches.Util;

/**
 * The parent class for Direct Updatable Theta Sketches.
 *
 * <p>This implementation uses data in a given Memory that is owned and managed by the caller.
 * This Memory can be off-heap, which if managed properly will greatly reduce the need for
 * the JVM to perform garbage collection.</p>
 *
 * @author Lee Rhodes
 */
abstract class DirectUpdateSketch extends UpdateSketch {
  final int lgNomLongs_;
  private final long seed_;
  private final float p_;
  private final ResizeFactor rf_;

  DirectUpdateSketch(final int lgNomLongs, final long seed, final float p, final ResizeFactor rf) {
    lgNomLongs_ = Math.max(lgNomLongs, MIN_LG_NOM_LONGS);
    seed_ = seed;
    p_ = p;
    rf_ = rf;
  }

  //Sketch

  @Override
  public boolean isDirect() {
    return true;
  }

  @Override
  public ResizeFactor getResizeFactor() {
    return rf_;
  }

  //restricted methods

  @Override
  int getLgNomLongs() {
    return lgNomLongs_;
  }

  @Override
  int getLgResizeFactor() {
    return rf_.lg();
  }

  @Override
  long getSeed() {
    return seed_;
  }

  @Override
  float getP() {
    return p_;
  }

  @Override
  short getSeedHash() {
    return Util.computeSeedHash(getSeed());
  }

}
