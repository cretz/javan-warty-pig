package jwp.fuzz;

import java.util.Arrays;
import java.util.function.BiFunction;
import static jwp.fuzz.Util.*;

/**
 * Base interface for tweaks that occur as part of {@link ByteArrayStage.RandomHavoc}. They are usually only
 * instantiated once, so they should not store any cross-"apply" state. Many tweaks can occur within a single
 * random havoc iteration. The tweaks are created/set in {@link ByteArrayParamGenerator.Config#havocTweaksCreator} and
 * they use the random at {@link ByteArrayParamGenerator.Config#random}.
 */
@FunctionalInterface
public interface RandomHavocTweak extends BiFunction<ByteArrayParamGenerator, byte[], byte[]> {
  /**
   * Apply the tweak to the given bytes. The given array can be mutated safely and returned and/or a new array can be
   * returned.
   */
  @Override
  byte[] apply(ByteArrayParamGenerator gen, byte[] bytes);

  /** Base class for tweaks that only mutate the given array */
  abstract class MutBytesInPlace implements RandomHavocTweak {
    @Override
    public byte[] apply(ByteArrayParamGenerator gen, byte[] bytes) {
      tweak(gen, bytes);
      return bytes;
    }

    public abstract void tweak(ByteArrayParamGenerator gen, byte[] bytes);
  }

  /** Flip a bit in a random location */
  class FlipSingleBit extends MutBytesInPlace {
    @Override
    public void tweak(ByteArrayParamGenerator gen, byte[] bytes) {
      flipBit(bytes, gen.config.random.nextInt(bytes.length * 8));
    }
  }

  /** Base class for tweaks that do something to a random byte */
  abstract class RandomByte extends MutBytesInPlace {
    @Override
    public void tweak(ByteArrayParamGenerator gen, byte[] bytes) {
      int index = gen.config.random.nextInt(bytes.length);
      bytes[index] = tweak(gen, bytes[index]);
    }

    public abstract byte tweak(ByteArrayParamGenerator gen, byte b);
  }

  /** Set a random value from {@link ParamGenerator#interestingBytes()} in a random location */
  class InterestingByte extends RandomByte {
    protected static final byte[] interestingBytes = streamToByteArray(ParamGenerator.interestingBytes());

    @Override
    public byte tweak(ByteArrayParamGenerator gen, byte b) {
      return interestingBytes[gen.config.random.nextInt(interestingBytes.length)];
    }
  }

  /** Base class for tweaks that do something to a random two-byte set */
  abstract class RandomShort extends MutBytesInPlace {
    @Override
    public void tweak(ByteArrayParamGenerator gen, byte[] bytes) {
      if (bytes.length < 2) return;
      int index = gen.config.random.nextInt(bytes.length - 1);
      if (gen.config.random.nextBoolean()) putShortLe(bytes, index, tweak(gen, getShortLe(bytes, index)));
      else putShortBe(bytes, index, tweak(gen, getShortBe(bytes, index)));
    }

    public abstract short tweak(ByteArrayParamGenerator gen, short s);
  }

  /** Set a random value from {@link ParamGenerator#interestingShorts()} in a random location */
  class InterestingShort extends RandomShort {
    protected static final short[] interestingShorts = streamToShortArray(ParamGenerator.interestingShorts());

    @Override
    public short tweak(ByteArrayParamGenerator gen, short s) {
      return interestingShorts[gen.config.random.nextInt(interestingShorts.length)];
    }
  }

  /** Base class for tweaks that do something to a random four-byte set */
  abstract class RandomInt extends MutBytesInPlace {
    @Override
    public void tweak(ByteArrayParamGenerator gen, byte[] bytes) {
      if (bytes.length < 4) return;
      int index = gen.config.random.nextInt(bytes.length - 3);
      if (gen.config.random.nextBoolean()) putIntLe(bytes, index, tweak(gen, getIntLe(bytes, index)));
      else putIntBe(bytes, index, tweak(gen, getIntBe(bytes, index)));
    }

    public abstract int tweak(ByteArrayParamGenerator gen, int i);
  }

  /** Set a random value from {@link ParamGenerator#interestingInts()} in a random location */
  class InterestingInt extends RandomInt {
    protected static final int[] interestingInts = ParamGenerator.interestingInts().toArray();

    @Override
    public int tweak(ByteArrayParamGenerator gen, int i) {
      return interestingInts[gen.config.random.nextInt(interestingInts.length)];
    }
  }

  /**
   * Subtract a random single-byte value between 1 and {@link ByteArrayParamGenerator.Config#arithMax} in a random
   * location
   */
  class SubtractFromByte extends RandomByte {
    @Override
    public byte tweak(ByteArrayParamGenerator gen, byte b) {
      return (byte) (b - (1 + gen.config.random.nextInt(gen.config.arithMax)));
    }
  }

  /**
   * Add a random single-byte value between 1 and {@link ByteArrayParamGenerator.Config#arithMax} in a random location
   */
  class AddToByte extends RandomByte {
    @Override
    public byte tweak(ByteArrayParamGenerator gen, byte b) {
      return (byte) (b + (1 + gen.config.random.nextInt(gen.config.arithMax)));
    }
  }

  /**
   * Subtract a random two-byte value between 1 and {@link ByteArrayParamGenerator.Config#arithMax} in a random location
   */
  class SubtractFromShort extends RandomShort {
    @Override
    public short tweak(ByteArrayParamGenerator gen, short s) {
      return (short) (s - (1 + gen.config.random.nextInt(gen.config.arithMax)));
    }
  }

  /** Add a random two-byte value between 1 and {@link ByteArrayParamGenerator.Config#arithMax} in a random location */
  class AddToShort extends RandomShort {
    @Override
    public short tweak(ByteArrayParamGenerator gen, short s) {
      return (short) (s + (1 + gen.config.random.nextInt(gen.config.arithMax)));
    }
  }

  /**
   * Subtract a random four-byte value between 1 and {@link ByteArrayParamGenerator.Config#arithMax} in a random
   * location
   */
  class SubtractFromInt extends RandomInt {
    @Override
    public int tweak(ByteArrayParamGenerator gen, int i) {
      return i - (1 + gen.config.random.nextInt(gen.config.arithMax));
    }
  }

  /** Add a random four-byte value between 1 and {@link ByteArrayParamGenerator.Config#arithMax} in a random location */
  class AddToInt extends RandomInt {
    @Override
    public int tweak(ByteArrayParamGenerator gen, int i) {
      return i + (1 + gen.config.random.nextInt(gen.config.arithMax));
    }
  }

  /** Set a random byte value in a random location */
  class SetRandomByte extends RandomByte {
    @Override
    public byte tweak(ByteArrayParamGenerator gen, byte b) {
      while (true) {
        byte newB = (byte) (gen.config.random.nextInt(256) - 128);
        if (newB != b) return newB;
      }
    }
  }

  /**
   * Delete a set of bytes with size specified by {@link ByteArrayParamGenerator#randomBlockLength(int)} from a random
   * location
   */
  class DeleteBytes implements RandomHavocTweak {
    @Override
    public byte[] apply(ByteArrayParamGenerator gen, byte[] bytes) {
      if (bytes.length < 2) return bytes;
      int delLen = gen.randomBlockLength(bytes.length - 1);
      int delFrom = gen.config.random.nextInt(bytes.length - delLen + 1);
      return withBytesRemoved(bytes, delFrom, delLen);
    }
  }

  /**
   * Clone or insert a set of bytes with size specified by {@link ByteArrayParamGenerator#randomBlockLength(int)} at a
   * random location. Whether cloning or inserting is random, with cloning being three times as likely. Where to clone
   * from is randomly selected. When inserting, this (evenly) randomly chooses whether to use all of a new random byte
   * or to use all of another randomly selected byte.
   */
  class CloneOrInsertBytes implements RandomHavocTweak {
    @Override
    public byte[] apply(ByteArrayParamGenerator gen, byte[] bytes) {
      if (bytes.length + gen.config.havocBlockXLarge >= gen.config.maxInput) return bytes;
      boolean actuallyClone = gen.config.random.nextInt(4) > 0;
      int cloneLen, cloneFrom;
      if (actuallyClone) {
        cloneLen = gen.randomBlockLength(bytes.length);
        cloneFrom = gen.config.random.nextInt(bytes.length - cloneLen + 1);
      } else {
        cloneLen = gen.randomBlockLength(gen.config.havocBlockXLarge);
        cloneFrom = 0;
      }
      int cloneTo = gen.config.random.nextInt(bytes.length);
      byte[] newArr = new byte[bytes.length + cloneLen];
      System.arraycopy(bytes, 0, newArr, 0, cloneTo);
      if (actuallyClone)
        System.arraycopy(bytes, cloneFrom, newArr, cloneTo, cloneLen);
      else {
        byte fillWith;
        if (gen.config.random.nextBoolean()) fillWith = (byte) (gen.config.random.nextInt(256) - 128);
        else fillWith = bytes[gen.config.random.nextInt(bytes.length)];
        Arrays.fill(newArr, cloneTo, cloneTo + cloneLen, fillWith);
      }
      System.arraycopy(bytes, cloneTo, newArr, cloneTo + cloneLen, bytes.length - cloneTo);
      return newArr;
    }
  }

  /**
   * Overwrite existing bytes with another randomly selected set out of the original or with a new set. Whether it uses
   * another set from the original or a new set is random with the former three times as likely. When using a new set,
   * this (evenly) randomly chooses whether to use all of a new random byte or to use all of another randomly selected
   * byte.
   */
  class OverwriteRandomOrFixedBytes extends MutBytesInPlace {
    @Override
    public void tweak(ByteArrayParamGenerator gen, byte[] bytes) {
      if (bytes.length < 2) return;
      int copyLen = gen.randomBlockLength(bytes.length - 1);
      int copyFrom = gen.config.random.nextInt(bytes.length - copyLen + 1);
      int copyTo = gen.config.random.nextInt(bytes.length - copyLen + 1);
      if (gen.config.random.nextInt(4) > 0) {
        if (copyFrom != copyTo) System.arraycopy(bytes, copyFrom, bytes, copyTo, copyLen);
      } else {
        byte fillWith;
        if (gen.config.random.nextBoolean()) fillWith = (byte) (gen.config.random.nextInt(256) - 128);
        else fillWith = bytes[gen.config.random.nextInt(bytes.length)];
        Arrays.fill(bytes, copyTo, copyTo + copyLen, fillWith);
      }
    }
  }

  /**
   * Overwrite existing bytes at a random location with a randomly selected entry in the
   * {@link ByteArrayParamGenerator.Config#dictionary}
   */
  class OverwriteWithDictionary extends MutBytesInPlace {
    @Override
    public void tweak(ByteArrayParamGenerator gen, byte[] bytes) {
      // TODO: auto extras
      byte[] entry = gen.config.dictionary.get(gen.config.random.nextInt(gen.config.dictionary.size()));
      if (entry.length > bytes.length) return;
      int insertAt = gen.config.random.nextInt(bytes.length - entry.length + 1);
      System.arraycopy(entry, 0, bytes, insertAt, entry.length);
    }
  }


  /**
   * Insert a randomly selected entry at a random location using the {@link ByteArrayParamGenerator.Config#dictionary}
   */
  class InsertWithDictionary implements RandomHavocTweak {
    @Override
    public byte[] apply(ByteArrayParamGenerator gen, byte[] bytes) {
      // TODO: auto extras
      byte[] entry = gen.config.dictionary.get(gen.config.random.nextInt(gen.config.dictionary.size()));
      if (bytes.length + entry.length > gen.config.maxInput) return bytes;
      int insertAt = gen.config.random.nextInt(bytes.length + 1);
      byte[] newArr = new byte[bytes.length + entry.length];
      System.arraycopy(bytes, 0, newArr, 0, insertAt);
      System.arraycopy(entry, 0, newArr, insertAt, entry.length);
      System.arraycopy(bytes, insertAt, newArr, insertAt + entry.length, bytes.length - insertAt);
      return newArr;
    }
  }
}
