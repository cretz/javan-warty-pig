package jwp.fuzz;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static jwp.fuzz.Util.*;

// Implementations should not keep state
public abstract class ByteArrayStage implements BiFunction<ByteArrayParamGenerator.Config, byte[], Stream<byte[]>> {
  // These are read-only, do not change
  protected static byte[] interestingBytes;
  protected static short[] interestingShorts;
  static {
    int[] ints = ParamGenerator.interestingBytes().toArray();
    interestingBytes = new byte[ints.length];
    for (int i = 0; i < ints.length; i++) interestingBytes[i] = (byte) ints[i];

    ints = ParamGenerator.interestingShorts().toArray();
    interestingShorts = new short[ints.length];
    for (int i = 0; i < ints.length; i++) interestingShorts[i] = (byte) ints[i];
  }

  // This should not mutate buf. Only copies are returned.
  public abstract Stream<byte[]> apply(ByteArrayParamGenerator.Config config, byte[] buf);

  protected static Stream<Integer> arithVals(ByteArrayParamGenerator.Config config) {
    return IntStream.rangeClosed(-config.arithMax, config.arithMax).boxed();
  }

  public static class FlipBits extends ByteArrayStage {
    protected final int consecutiveToFlip;
    public FlipBits(int consecutiveToFlip) { this.consecutiveToFlip = consecutiveToFlip; }

    @Override
    public Stream<byte[]> apply(ByteArrayParamGenerator.Config config, byte[] buf) {
      return IntStream.range(0, (buf.length * 8) - (consecutiveToFlip - 1)).boxed().map(bitIndex ->
          withCopiedBytes(buf, bytes -> {
            for (int i = 0; i < consecutiveToFlip; i++) flipBit(bytes, bitIndex + i);
          })
      );
    }
  }

  public static class FlipBytes extends ByteArrayStage {
    protected final int consecutiveToFlip;
    public FlipBytes(int consecutiveToFlip) { this.consecutiveToFlip = consecutiveToFlip; }

    @Override
    public Stream<byte[]> apply(ByteArrayParamGenerator.Config config, byte[] buf) {
      return IntStream.range(0, buf.length - (consecutiveToFlip - 1)).boxed().map(byteIndex ->
          withCopiedBytes(buf, bytes -> {
            for (int i = 0; i < consecutiveToFlip; i++) bytes[byteIndex + i] = (byte) ~bytes[byteIndex + i];
          })
      );
    }
  }

  public static class Arith8 extends ByteArrayStage {
    @Override
    public Stream<byte[]> apply(ByteArrayParamGenerator.Config config, byte[] buf) {
      return IntStream.range(0, buf.length).boxed().flatMap(byteIndex ->
          arithVals(config).map(arithVal -> {
            byte newByte = (byte) (buf[byteIndex] + arithVal);
            if (couldHaveBitFlippedTo(buf[byteIndex], newByte)) return null;
            return withCopiedBytes(buf, bytes -> bytes[byteIndex] = newByte);
          }).filter(Objects::nonNull)
      );
    }
  }

  public static class Arith16 extends ByteArrayStage {
    protected static boolean affectsBothBytes(short origShort, short newShort) {
      return byte0(origShort) != byte0(newShort) && byte1(origShort) != byte1(newShort);
    }

    @Override
    public Stream<byte[]> apply(ByteArrayParamGenerator.Config config, byte[] buf) {
      return IntStream.range(0, buf.length - 1).boxed().flatMap(byteIndex -> {
        short origLe = getShortLe(buf, byteIndex), origBe = getShortBe(buf, byteIndex);
        return arithVals(config).flatMap(arithVal -> {
          short newLe = (short) (origLe + arithVal), newBe = (short) (origBe + arithVal);
          byte[] leBytes = null, beBytes = null;
          if (affectsBothBytes(origLe, newLe) && !couldHaveBitFlippedTo(origLe, newLe))
            leBytes = withCopiedBytes(buf, arr -> putShortLe(arr, byteIndex, newLe));
          if (affectsBothBytes(origBe, newBe) && !couldHaveBitFlippedTo(origLe, endianSwapped(newBe)))
            beBytes = withCopiedBytes(buf, arr -> putShortBe(arr, byteIndex, newBe));
          return streamOfNotNull(leBytes, beBytes);
        });
      });
    }
  }

  public static class Arith32 extends ByteArrayStage {
    protected static boolean affectsMoreThanTwoBytes(int origInt, int newInt) {
      return (byte0(origInt) == byte0(newInt) ? 0 : 1) +
          (byte1(origInt) == byte1(newInt) ? 0 : 1) +
          (byte2(origInt) == byte2(newInt) ? 0 : 1) +
          (byte3(origInt) == byte3(newInt) ? 0 : 1) > 2;
    }

    @Override
    public Stream<byte[]> apply(ByteArrayParamGenerator.Config config, byte[] buf) {
      return IntStream.range(0, buf.length - 3).boxed().flatMap(byteIndex -> {
        int origLe = getIntLe(buf, byteIndex), origBe = getIntBe(buf, byteIndex);
        return arithVals(config).flatMap(arithVal -> {
          int newLe = origLe + arithVal, newBe = origBe + arithVal;
          byte[] leBytes = null, beBytes = null;
          if (affectsMoreThanTwoBytes(origLe, newLe) && !couldHaveBitFlippedTo(origLe, newLe))
            leBytes = withCopiedBytes(buf, arr -> putIntLe(arr, byteIndex, newLe));
          if (affectsMoreThanTwoBytes(origBe, newBe) && !couldHaveBitFlippedTo(origLe, endianSwapped(newBe)))
            beBytes = withCopiedBytes(buf, arr -> putIntBe(arr, byteIndex, newBe));
          return streamOfNotNull(leBytes, beBytes);
        });
      });
    }
  }

  public static class Interesting8 extends ByteArrayStage {
    protected static boolean couldBeArith(ByteArrayParamGenerator.Config config, byte origByte, byte newByte) {
      return newByte >= origByte - config.arithMax && newByte <= origByte + config.arithMax;
    }

    @Override
    public Stream<byte[]> apply(ByteArrayParamGenerator.Config config, byte[] buf) {
      return IntStream.range(0, buf.length).boxed().flatMap(byteIndex -> {
        byte origByte = buf[byteIndex];
        return ParamGenerator.interestingBytes().boxed().map(newInt -> {
          byte newByte = newInt.byteValue();
          if (!couldBeArith(config, origByte, newByte) && !couldHaveBitFlippedTo(origByte, newByte))
            return withCopiedBytes(buf, arr -> arr[byteIndex] = newByte);
          return null;
        }).filter(Objects::nonNull);
      });
    }
  }

  public static class Interesting16 extends ByteArrayStage {
    protected static boolean couldBeArith(ByteArrayParamGenerator.Config config, short origShort, short newShort) {
      if (newShort >= origShort - config.arithMax && newShort <= origShort + config.arithMax) return true;
      short origBe = endianSwapped(origShort);
      return newShort >= origBe - config.arithMax && newShort <= origBe + config.arithMax;
    }

    protected static boolean couldBeInteresting8(short origShort, short newShort) {
      byte orig0 = byte0(origShort), orig1 = byte1(origShort), new0 = byte0(newShort), new1 = byte1(newShort);
      for (byte int8 : interestingBytes) {
        if ((orig0 == new0 && int8 == new1) || (int8 == new0 && orig1 == new1)) return true;
      }
      return false;
    }

    @Override
    public Stream<byte[]> apply(ByteArrayParamGenerator.Config config, byte[] buf) {
      return IntStream.range(0, buf.length - 1).boxed().flatMap(byteIndex -> {
        short origLe = getShortLe(buf, byteIndex), origBe = getShortBe(buf, byteIndex);
        return ParamGenerator.interestingShorts().boxed().flatMap(newInt -> {
          short newShortLe = newInt.shortValue(), newShortBe = endianSwapped(newInt.shortValue());
          byte[] leBytes = null, beBytes = null;
          if (!couldBeArith(config, origLe, newShortLe) && !couldBeInteresting8(origLe, newShortLe) &&
              !couldHaveBitFlippedTo(origLe, newShortLe))
            leBytes = withCopiedBytes(buf, arr -> putShortLe(arr, byteIndex, newShortLe));
          if (!couldBeArith(config, origBe, newShortBe) && !couldBeInteresting8(origBe, newShortBe) &&
              !couldHaveBitFlippedTo(origLe, newShortBe))
            beBytes = withCopiedBytes(buf, arr -> putShortBe(arr, byteIndex, newShortLe));
          return streamOfNotNull(leBytes, beBytes);
        });
      });
    }
  }

  public static class Interesting32 extends ByteArrayStage {
    protected static boolean couldBeArith(ByteArrayParamGenerator.Config config, byte[] origArr, byte[] newArr) {
      for (int i = 0; i < 4; i++) {
        if (arithByteCheck(config, origArr, newArr, i)) return true;
        if (i < 3 && arithShortCheck(config, origArr, newArr, i)) return true;
      }
      return false;
    }

    protected static boolean arithByteCheck(ByteArrayParamGenerator.Config config,
        byte[] origArr, byte[] newArr, int index) {
      for (int i = 0; i < 4; i++) {
        int diff = newArr[i] - origArr[i];
        if (diff != 0 && (i != index || diff < -config.arithMax || diff > config.arithMax)) return false;
      }
      return true;
    }

    protected static boolean arithShortCheck(ByteArrayParamGenerator.Config config,
        byte[] origArr, byte[] newArr, int index) {
      for (int i = 0; i < 4; i++) {
        if (origArr[i] != newArr[i] && i != index + 1) {
          int diff = getShortLe(newArr, i) - getShortLe(origArr, i);
          if (diff < -config.arithMax || diff > config.arithMax) return false;
        }
      }
      return true;
    }

    protected static boolean couldBeInteresting8(byte[] origArr, byte[] newArr) {
      for (byte b : interestingBytes) {
        if ((b == newArr[0] && origArr[1] == newArr[1] && origArr[2] == newArr[2] && origArr[3] == newArr[3]) ||
            (origArr[0] == newArr[0] && b == newArr[1] && origArr[2] == newArr[2] && origArr[3] == newArr[3]) ||
            (origArr[0] == newArr[0] && origArr[1] == newArr[1] && b == newArr[2] && origArr[3] == newArr[3]) ||
            (origArr[0] == newArr[0] && origArr[1] == newArr[1] && origArr[2] == newArr[2] && b == newArr[3]))
          return true;
      }
      return false;
    }

    // Mutates origArr, but always changes it back before returning
    protected static boolean couldBeInteresting16(byte[] origArr, byte[] newArr) {
      boolean found = false;
      for (int i = 0; i < 3; i++) {
        byte pre1 = origArr[i], pre2 = origArr[i + 1];
        for (short s : interestingShorts) {
          putShortLe(origArr, i, s);
          if (Arrays.equals(origArr, newArr)) {
            found = true;
            break;
          }
          putShortBe(origArr, i, s);
          if (Arrays.equals(origArr, newArr)) {
            found = true;
            break;
          }
        }
        origArr[i] = pre1;
        origArr[i + 1] = pre2;
        if (found) return true;
      }
      return false;
    }

    @Override
    public Stream<byte[]> apply(ByteArrayParamGenerator.Config config, byte[] buf) {
      return IntStream.range(0, buf.length - 3).boxed().flatMap(byteIndex -> {
        int origLe = getIntLe(buf, byteIndex), origBe = getIntLe(buf, byteIndex);
        byte[] origLeArr = toByteArray(origLe), origBeArr = toByteArray(origBe);
        return ParamGenerator.interestingInts().boxed().flatMap(newLe -> {
          int newBe = endianSwapped(newLe);
          byte[] newLeArr = toByteArray(newLe), newBeArr = toByteArray(newBe);
          byte[] leBytes = null, beBytes = null;
          if (!couldBeArith(config, origLeArr, newLeArr) && !couldBeInteresting8(origLeArr, newLeArr) &&
              !couldBeInteresting16(origLeArr, newLeArr) && !couldHaveBitFlippedTo(origLe, newLe))
            leBytes = withCopiedBytes(buf, arr -> putIntLe(arr, byteIndex, newLe));
          if (!couldBeArith(config, origBeArr, newBeArr) && !couldBeInteresting8(origLeArr, newBeArr) &&
              !couldBeInteresting16(origLeArr, newBeArr) && !couldHaveBitFlippedTo(origLe, newBe))
            beBytes = withCopiedBytes(buf, arr -> putIntBe(arr, byteIndex, newBe));
          return streamOfNotNull(leBytes, beBytes);
        });
      });
    }
  }
}
