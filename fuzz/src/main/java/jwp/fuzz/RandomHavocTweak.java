package jwp.fuzz;

import java.util.Arrays;
import java.util.function.BiFunction;
import static jwp.fuzz.Util.*;

@FunctionalInterface
public interface RandomHavocTweak extends BiFunction<ByteArrayParamGenerator, byte[], byte[]> {
  // The given byte array can be mutated and returned safely. Or a brand new array can be returned.
  byte[] apply(ByteArrayParamGenerator gen, byte[] bytes);


  abstract class MutBytesInPlace implements RandomHavocTweak {
    @Override
    public byte[] apply(ByteArrayParamGenerator gen, byte[] bytes) {
      tweak(gen, bytes);
      return bytes;
    }

    public abstract void tweak(ByteArrayParamGenerator gen, byte[] bytes);
  }

  class FlipSingleBit extends MutBytesInPlace {
    @Override
    public void tweak(ByteArrayParamGenerator gen, byte[] bytes) {
      flipBit(bytes, gen.config.random.nextInt(bytes.length * 8));
    }
  }

  abstract class RandomByte extends MutBytesInPlace {
    @Override
    public void tweak(ByteArrayParamGenerator gen, byte[] bytes) {
      int index = gen.config.random.nextInt(bytes.length);
      bytes[index] = tweak(gen, bytes[index]);
    }

    public abstract byte tweak(ByteArrayParamGenerator gen, byte b);
  }

  class InterestingByte extends RandomByte {
    protected static final byte[] interestingBytes = streamToByteArray(ParamGenerator.interestingBytes());

    @Override
    public byte tweak(ByteArrayParamGenerator gen, byte b) {
      return interestingBytes[gen.config.random.nextInt(interestingBytes.length)];
    }
  }

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

  class InterestingShort extends RandomShort {
    protected static final short[] interestingShorts = streamToShortArray(ParamGenerator.interestingShorts());

    @Override
    public short tweak(ByteArrayParamGenerator gen, short s) {
      return interestingShorts[gen.config.random.nextInt(interestingShorts.length)];
    }
  }

  abstract class RandomInt extends MutBytesInPlace {
    @Override
    public void tweak(ByteArrayParamGenerator gen, byte[] bytes) {
      if (bytes.length < 4) return;
      int index = gen.config.random.nextInt(bytes.length - 1);
      if (gen.config.random.nextBoolean()) putIntLe(bytes, index, tweak(gen, getIntLe(bytes, index)));
      else putIntBe(bytes, index, tweak(gen, getIntBe(bytes, index)));
    }

    public abstract int tweak(ByteArrayParamGenerator gen, int i);
  }

  class InterestingInt extends RandomInt {
    protected static final int[] interestingInts = ParamGenerator.interestingInts().toArray();

    @Override
    public int tweak(ByteArrayParamGenerator gen, int i) {
      return interestingInts[gen.config.random.nextInt(interestingInts.length)];
    }
  }

  class SubtractFromByte extends RandomByte {
    @Override
    public byte tweak(ByteArrayParamGenerator gen, byte b) {
      return (byte) (b - (1 + gen.config.random.nextInt(gen.config.arithMax)));
    }
  }

  class AddToByte extends RandomByte {
    @Override
    public byte tweak(ByteArrayParamGenerator gen, byte b) {
      return (byte) (b + (1 + gen.config.random.nextInt(gen.config.arithMax)));
    }
  }

  class SubtractFromShort extends RandomShort {
    @Override
    public short tweak(ByteArrayParamGenerator gen, short s) {
      return (short) (s - (1 + gen.config.random.nextInt(gen.config.arithMax)));
    }
  }

  class AddToShort extends RandomShort {
    @Override
    public short tweak(ByteArrayParamGenerator gen, short s) {
      return (short) (s + (1 + gen.config.random.nextInt(gen.config.arithMax)));
    }
  }

  class SubtractFromInt extends RandomInt {
    @Override
    public int tweak(ByteArrayParamGenerator gen, int i) {
      return i - (1 + gen.config.random.nextInt(gen.config.arithMax));
    }
  }

  class AddToInt extends RandomInt {
    @Override
    public int tweak(ByteArrayParamGenerator gen, int i) {
      return i + (1 + gen.config.random.nextInt(gen.config.arithMax));
    }
  }

  class SetRandomByte extends RandomByte {
    @Override
    public byte tweak(ByteArrayParamGenerator gen, byte b) {
      while (true) {
        byte newB = (byte) (gen.config.random.nextInt(256) - 128);
        if (newB != b) return newB;
      }
    }
  }

  class DeleteBytes implements RandomHavocTweak {
    @Override
    public byte[] apply(ByteArrayParamGenerator gen, byte[] bytes) {
      if (bytes.length < 2) return bytes;
      int delLen = gen.randomBlockLength(bytes.length - 1);
      int delFrom = gen.config.random.nextInt(bytes.length - delLen + 1);
      return withBytesRemoved(bytes, delFrom, delLen);
    }
  }

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
        Arrays.fill(bytes, copyTo, copyLen, fillWith);
      }
    }
  }

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
