package com.yahoo.sketches.sampling;

import java.lang.reflect.Array;
import java.util.ArrayList;

import com.yahoo.sketches.ArrayOfItemsSerDe;
import com.yahoo.sketches.ResizeFactor;
import com.yahoo.sketches.SketchesArgumentException;
import com.yahoo.sketches.SketchesStateException;
import com.yahoo.sketches.Util;

/**
 * @author Jon Malkin
 */
public class VarOptItemsSketch<T> {
  /**
   * The smallest sampling array allocated: 16
   */
  private static final int MIN_LG_ARR_ITEMS = 4;

  /**
   * Using 48 bits to capture number of items seen, so sketch cannot process more after this
   * many items capacity
   */
  private static final long MAX_ITEMS_SEEN = 0xFFFFFFFFFFFFL;

  /**
   * Default sampling size multiple when reallocating storage: 8
   */
  private static final ResizeFactor DEFAULT_RESIZE_FACTOR = ResizeFactor.X8;

  private final int k_;      // max size of reservoir
  private int currItemsAlloc_;           // currently allocated array size
  private long itemsSeen_;               // number of items presented to sketch
  private final ResizeFactor rf_;        // resize factor
  private ArrayList<T> data_;            // stored sampled data
  private ArrayList<Double> weights_;    // weights for sampled data

  private int h_;                        // number of items in heap
  private int m_;                        // number of items in middle region
  private int r_;                        // number of items in reservoir-like area
  private double totalWtR_;              // total weight of items in reservoir-like area


  private VarOptItemsSketch(final int k, final ResizeFactor rf) {
    // required due to a theorem about lightness during merging
    if (k < 2) {
      throw new SketchesArgumentException("k must be at least 2");
    }

    k_ = k;
    rf_ = rf;

    itemsSeen_ = 0;
    h_ = 0;
    m_ = 0;
    r_ = 0;
    totalWtR_ = 0;

    final int ceilingLgK = Util.toLog2(Util.ceilingPowerOf2(k_), "VarOptItemsSketch");
    final int initialLgSize =
            SamplingUtil.startingSubMultiple(ceilingLgK, rf_.lg(), MIN_LG_ARR_ITEMS);

    currItemsAlloc_ = SamplingUtil.getAdjustedSize(k_, 1 << initialLgSize);
    if (currItemsAlloc_ == k_) {
      ++currItemsAlloc_;
    }

    data_ = new ArrayList<>(currItemsAlloc_);
    weights_ = new ArrayList<>(currItemsAlloc_);
  }

  public static <T> VarOptItemsSketch<T> getInstance(final int k) {
    return new VarOptItemsSketch<>(k, DEFAULT_RESIZE_FACTOR);
  }

  /**
   * Returns the sketch's value of <i>k</i>, the maximum number of samples stored in the
   * reservoir. The current number of items in the sketch may be lower.
   *
   * @return k, the maximum number of samples in the reservoir
   */
  public int getK() {
    return k_;
  }

  /**
   * Returns the number of items processed from the input stream
   *
   * @return n, the number of stream items the sketch has seen
   */
  public long getN() {
    return itemsSeen_;
  }

  /**
   * Returns the current number of items in the reservoir, which may be smaller than the
   * reservoir capacity.
   *
   * @return the number of items currently in the reservoir
   */
  public int getNumSamples() {
    return (int) Math.min(k_, itemsSeen_);
  }

  /**
   * Randomly decide whether or not to include an item in the sample set.
   *
   * @param item an item of the set being sampled from
   * @param weight a strictly positive weight associated with the item
   */
  /* The word "pseudo" refers to the fact that the comparisons
     are being made against the OLD value of tau, whereas true lightness
     or heaviness during this sampling event depends on the NEW value of tau
     which has yet to be determined */
  public void update(final T item, final double weight) {
    if (itemsSeen_ == MAX_ITEMS_SEEN) {
      throw new SketchesStateException("Sketch has exceeded capacity for total items seen: "
              + MAX_ITEMS_SEEN);
    } else if (weight <= 0.0) {
      throw new SketchesArgumentException("Item weights must be strictly positive: "
              + weight);
    }
    if (item == null) {
      return;
    }

    if (r_ == 0) {
      updateWarmupPhase(item, weight);
    } else {
      final double avgWtR = totalWtR_ / r_;

      if (weight <= avgWtR) {
        updatePseudoLight(item, weight);
      } else if (r_ == 1) {
        updatePseudoHeavyREq1(item, weight);
      } else {
        updatePseudoHeavyGeneral(item, weight);
      }
    }
  }

  /**
   * Returns a copy of the items in the reservoir, or null if empty. The returned array length
   * may be smaller than the reservoir capacity.
   *
   * <p>In order to allocate an array of generic type T, uses the class of the first item in
   * the array. This method method may throw an <tt>ArrayAssignmentException</tt> if the
   * reservoir stores instances of a polymorphic base class.</p>
   *
   * @return A copy of the reservoir array
   */
  @SuppressWarnings("unchecked")
  public T[] getSamples() {
    if (itemsSeen_ == 0) {
      return null;
    }

    final Class<?> clazz = data_.get(0).getClass();
    return data_.toArray((T[]) Array.newInstance(clazz, 0));
  }

  /**
   * Returns a copy of the items in the reservoir as members of Class <em>clazz</em>, or null
   * if empty. The returned array length may be smaller than the reservoir capacity.
   *
   * <p>This method allocates an array of class <em>clazz</em>, which must either match or
   * extend T. This method should be used when objects in the array are all instances of T but
   * are not necessarily instances of the base class.</p>
   *
   * @param clazz A class to which the items are cast before returning
   * @return A copy of the reservoir array
   */
  @SuppressWarnings("unchecked")
  public T[] getSamples(final Class<?> clazz) {
    if (itemsSeen_ == 0) {
      return null;
    }

    return data_.toArray((T[]) Array.newInstance(clazz, 0));
  }

  /**
   * Returns a human-readable summary of the sketch, without data.
   *
   * @return A string version of the sketch summary
   */
  @Override
  public String toString() {
    throw new RuntimeException("Write me!");

    /*
    final StringBuilder sb = new StringBuilder();

    final String thisSimpleName = this.getClass().getSimpleName();

    sb.append(LS);
    sb.append("### ").append(thisSimpleName).append(" SUMMARY: ").append(LS);
    sb.append("   k            : ").append(k_).append(LS);
    sb.append("   n            : ").append(itemsSeen_).append(LS);
    sb.append("   Current size : ").append(currItemsAlloc_).append(LS);
    sb.append("   Resize factor: ").append(rf_).append(LS);
    sb.append("### END SKETCH SUMMARY").append(LS);

    return sb.toString();
    */
  }

  /**
   * Returns a byte array representation of this sketch. May fail for polymorphic item types.
   *
   * @param serDe An instance of ArrayOfItemsSerDe
   * @return a byte array representation of this sketch
   */
  public byte[] toByteArray(final ArrayOfItemsSerDe<T> serDe) {
    if (itemsSeen_ == 0) {
      // null class is ok since empty -- no need to call serDe
      return toByteArray(serDe, null);
    } else {
      return toByteArray(serDe, data_.get(0).getClass());
    }
  }

  /**
   * Returns a byte array representation of this sketch. Copies contents into an array of the
   * specified class for serialization to allow for polymorphic types.
   *
   * @param serDe An instance of ArrayOfItemsSerDe
   * @param clazz Teh class represented by &lt;T&gt;
   * @return a byte array representation of this sketch
   */
  @SuppressWarnings("null") // bytes will be null only if empty == true
  public byte[] toByteArray(final ArrayOfItemsSerDe<T> serDe, final Class<?> clazz) {
    throw new RuntimeException("Write me!");

    /*
    final int preLongs, outBytes;
    final boolean empty = itemsSeen_ == 0;
    byte[] bytes = null; // for serialized data from serDe

    if (empty) {
      preLongs = 1;
      outBytes = 8;
    } else {
      preLongs = Family.RESERVOIR.getMaxPreLongs();
      bytes = serDe.serializeToByteArray(getSamples(clazz));
      outBytes = (preLongs << 3) + bytes.length;
    }
    final byte[] outArr = new byte[outBytes];
    final Memory mem = new NativeMemory(outArr);

    // build first preLong
    long pre0 = 0L;
    pre0 = PreambleUtil.insertPreLongs(preLongs, pre0);                  // Byte 0
    pre0 = PreambleUtil.insertResizeFactor(rf_.lg(), pre0);
    pre0 = PreambleUtil.insertSerVer(SER_VER, pre0);                     // Byte 1
    pre0 = PreambleUtil.insertFamilyID(Family.RESERVOIR.getID(), pre0);  // Byte 2
    pre0 = (empty)
            ? PreambleUtil.insertFlags(EMPTY_FLAG_MASK, pre0)
            : PreambleUtil.insertFlags(0, pre0);                         // Byte 3
    pre0 = PreambleUtil.insertReservoirSize(k_, pre0);       // Bytes 4-7

    if (empty) {
      mem.putLong(0, pre0);
    } else {
      // second preLong, only if non-empty
      long pre1 = 0L;
      pre1 = PreambleUtil.insertItemsSeenCount(itemsSeen_, pre1);

      final long[] preArr = new long[preLongs];
      preArr[0] = pre0;
      preArr[1] = pre1;
      mem.putLongArray(0, preArr, 0, preLongs);
      final int preBytes = preLongs << 3;
      mem.putByteArray(preBytes, bytes, 0, bytes.length);
    }

    return outArr;
    */
  }

  /* In the "pseudo-light" case the new item has weight <= old_tau, so
     would appear to the right of the R guys in a hypothetical reverse-sorted
     list. It is easy to prove that it is light enough to be part of this
     round's downsampling */
  private void updatePseudoLight(final T item, final double weight) {
    assert r_ >= 1;
    assert r_ + h_ == k_;

    final int mSlot = h_; // index of the gap, which becomes the M region
    data_.set(mSlot, item);
    weights_.set(mSlot, weight);
    ++m_;

    growCandidateSet(totalWtR_ + weight, r_ + 1);
  }

  /* In the "pseudo-heavy" case the new item has weight > old_tau, so would
     appear to the left of items in R in a hypothetical reverse-sorted list and
     might or might not be light enough be part of this round's downsampling.
     [After first splitting off the R=1 case] we greatly simplify the code by
     putting the new item into the H heap whether it needs to be there or not.
     In other words, it might go into the heap and then come right back out,
     but that should be okay because pseudo_heavy items cannot predominate
     in long streams unless (max wt) / (min wt) > o(exp(N)) */
  private void updatePseudoHeavyGeneral(final T item, final double weight) {
    assert m_ == 0;
    assert r_ >= 2;;
    assert r_ + h_ == k_;

    // put into H, although may come back out momentarily
    push(item, weight);

    growCandidateSet(totalWtR_, r_);
  }

  /* The analysis of this case is similar to that of the general pseudo heavy case.
  The one small technical difference is that since R < 2, we must grab an M guy
  to have a valid starting point for continue_by_growing_candidate_set () */
  private void updatePseudoHeavyREq1(final T item, final double weight) {
    assert m_ == 0;
    assert r_ == 1;
    assert r_ + h_ == k_;

    push(item, weight);  // new item into H
    popMinToMRegion();   // pop lightest back into M

    // Any set of two items is downsample-able to one item,
    // so the two lightest items are a valid starting point for the following
    final int mSlot = k_ - 1; // array is k+1, 1 in R, so slot before is M
    growCandidateSet(weights_.get(mSlot) + totalWtR_, 2);
  }

  private void updateWarmupPhase(final T item, final double wt) {
    assert r_ == 0 && m_ == 0 && h_ < getK();

    // store items as they come in, until full
    data_.add(h_, item);
    weights_.add(h_, wt);
    ++h_;

    // lazy heapification
    if (h_ > k_) {
      convertToHeap();
      transitionFromWarmup();
    }
  }

  private void transitionFromWarmup() {
    // Move 2 lightest items from H to M
    // But the lighter really belongs in R, so update counts to reflect that
    popMinToMRegion();
    popMinToMRegion();
    --m_;
    ++r_;

    assert h_ == k_ - 1;
    assert m_ == 1;
    assert r_ == 1;

    // Update total weight in R then, having grabbed the value, overwrite in
    // weight_ array to help make bugs more obvious
    totalWtR_ = weights_.get(k_); // only one item, known location
    weights_.set(k_, -1.0);

    // Any set of 2 items can be downsampled to one item, so the two lightest
    // items are a valid starting point for the following
    growCandidateSet(weights_.get(k_ - 1) + totalWtR_, 2);
  }

  /* Validates the heap condition for the weight array */
  private void validateHeap() {
    for (int j = h_ - 1; j >= 1; --j) {
      final int p = ((j + 1) / 2) - 1;
      assert weights_.get(p) < weights_.get(j);
    }
  }

  /* Converts the data_ and weights_ arrays to heaps. In contrast to other parts
     of the library, this has nothing to do with on- or off-heap storage or the
     Memory package.
   */
  private void convertToHeap() {
    if (h_ < 2) {
      return; // nothing to do
    }

    final int lastSlot = h_ - 1;
    final int lastNonLeaf = ((lastSlot + 1) / 2) - 1;

    for (int j = lastNonLeaf; j >= 0; --j) {
      restoreTowardsLeaves(j);
    }

    // TODO: remove extra check
    validateHeap();
  }

  private void restoreTowardsLeaves(final int slotIn) {
    assert h_ > 0;
    final int lastSlot = h_ - 1;
    assert slotIn <= lastSlot;

    int slot = slotIn;
    int child = 2 * slotIn + 1; // might be invalid, need to check

    while (child <= lastSlot) {
      final int child2 = child + 1; // might also be invalid
      if (child2 <= lastSlot && weights_.get(child2) < weights_.get(child)) {
        // switch to other child if it's both valid and smaller
        child = child2;
      }

      if (weights_.get(slot) <= weights_.get(child)) {
        // invariant holds so we're done
        break;
      }

      // swap and continue
      swapValues(slot, child);

      slot = child;
      child = 2 * slot + 1; // might be invalid, checked on next loop
    }
  }

  private void restoreTowardsRoot(final int slotIn) {
    int slot = slotIn;
    int p = (((slot + 1) / 2) - 1); // valid if slot >= 1
    while (slot > 0 && weights_.get(slot) < weights_.get(p)) {
      swapValues(slot, p);
      slot = p;
      p = (((slot + 1) / 2) - 1); // valid if slot >= 1
    }
  }

  private void push(final T item, final double wt) {
    data_.set(h_, item);
    weights_.set(h_, wt);
    ++h_;

    restoreTowardsRoot(h_ - 1); // need use old h_, but want accurate h_
  }

  private double peekMin() {
    assert h_ > 0;
    return weights_.get(0);
  }

  private void popMinToMRegion() {
    assert h_ > 0;
    assert h_ + m_ + r_ == k_ + 1;

    if (h_ == 1) {
      // just update bookkeeping
      ++m_;
      --h_;
    } else {
      // main case
      final int tgt = h_ - 1; // last slot, will swap with root
      swapValues(0, tgt);
      ++m_;
      --h_;

      restoreTowardsLeaves(0);
    }
  }

  /* When entering here we should be in a well-characterized state where the
     new item has been placed in either h or m and we have a valid but not necessarily
     maximal sampling plan figured out. The array is completely full at this point.
     Everyone in h and m has an explicit weight. The candidates are right-justified
     and are either just the r set or the r set + exactly one m item. The number
     of cands is at least 2. We will now grow the candidate set as much as possible
     by pulling sufficiently light items from h to m.
   */
  private void growCandidateSet(double wtCands, int numCands) {
    assert h_ + m_ + r_ == k_ + 1;
    assert numCands >= 2;       // essential
    assert numCands == m_ + r_; // essential
    assert m_ == 0 || m_ == 1;

    System.out.printf("Before growing: %d cands weighing %.6f, provisional new tau is %.6f\n",
            numCands, wtCands, wtCands / (numCands - 1));

    while (h_ > 0) {
      final double nextWt = peekMin();
      final double nextTotWt = wtCands + nextWt;

      // test for strict lightness of next prospect (denominator multiplied through)
      // ideally: (nextWt * (nextNumCands-1) < nextTotWt) but can just
      //          use numCands directly
      if (nextWt * numCands < nextTotWt) {
        wtCands = nextTotWt;
        ++numCands;
        popMinToMRegion(); // adjusts h_ and m_
      } else {
        break;
      }
    }

    downsampleCandidateSet(wtCands, numCands);
  }

  private int pickRandomSlotInR() {
    assert r_ > 0;
    final int offset = h_ + m_;
    if (r_ == 1) {
      return offset;
    } else {
      return offset + SamplingUtil.rand.nextInt(r_);
    }
  }

  private int chooseDeleteSlot(final double wtCand, final int numCand) {
    assert r_ > 0;

    if (m_ == 0) {
      // this happens if we insert a really heavy item
      return pickRandomSlotInR();
    } else if (m_ == 1) {
      // check if we keep the item in M or pick one from R
      // p(keep) = (numCand - 1) * wt_M / wt_newtotal
      // TODO: is wt_newtotal correct? seems like wt_cand in code
      final double wtMCand = weights_.get(h_); // slot of item in M is h_
      if (wtCand * SamplingUtil.rand.nextDouble() < (numCand - 1) * wtMCand) {
        return pickRandomSlotInR(); // keep item in M
      } else {
        return h_; // index of item in M
      }
    } else {
      // general case
      final int deleteSlot = chooseWeightedDeleteSlot(wtCand, numCand);
      final int firstRSlot = h_ + m_;
      if (deleteSlot == firstRSlot) {
        return pickRandomSlotInR();
      } else {
        return deleteSlot;
      }
    }
  }


  private int chooseWeightedDeleteSlot(final double wtCand, final int numCand) {
    assert m_ >= 1;

    final int offset = h_;
    final int finalM = offset + m_ - 1;
    final int numToKeep = numCand - 1;

    double leftSubtotal = 0.0;
    double rightSubtotal = -1.0 * wtCand * SamplingUtil.rand.nextDouble();

    for (int i = m_; i <= finalM; ++i) {
      leftSubtotal += numToKeep * weights_.get(i);
      rightSubtotal += wtCand;

      if (leftSubtotal < rightSubtotal) {
        return i;
      }
    }

    // this slot tells caller that we need to delete out of R
    return finalM + 1;
  }

  private void downsampleCandidateSet(final double wtCands, final int numCands) {
    assert numCands >= 2;
    assert h_ + numCands == k_ + 1;

    // need this before overwriting anything
    final int deleteSlot = chooseDeleteSlot(wtCands, numCands);
    final int leftmostCandSlot = h_;
    assert deleteSlot >= leftmostCandSlot;
    assert deleteSlot <= k_;

    // overwrite weights for items from M moving into R, to make bugs more obvious
    final int stopIdx = leftmostCandSlot + m_ - 1;
    for (int j = leftmostCandSlot; j < stopIdx; ++j) {
      weights_.set(j, -1.0);
    }

    // The next two lines work even when deleteSlot == leftmostCandSlot
    data_.set(deleteSlot, data_.get(leftmostCandSlot));
    data_.set(leftmostCandSlot, null);

    m_ = 0;
    r_ = numCands - 1;
    totalWtR_ = wtCands;
  }

  /* swap values of data_ and weights_ between src and dst */
  private void swapValues(final int src, final int dst) {
    final T item = data_.get(src);
    data_.set(src, data_.get(dst));
    data_.set(dst, item);

    final Double wt = weights_.get(src);
    weights_.set(src, weights_.get(dst));
    weights_.set(dst, wt);
  }

}