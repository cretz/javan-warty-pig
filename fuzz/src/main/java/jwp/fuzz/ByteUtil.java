package jwp.fuzz;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class ByteUtil {
  // Mutates array, only use with temp arrays
  public static boolean checkConsecutiveBitsFlipped(byte[] bytes, Predicate<byte[]> pred) {
    int bitCount = bytes.length;
    for (int i = -1; i < bitCount; i++) {
      if (i >= 0) {
        flipBit(bytes, i);
        if (pred.test(bytes)) return true;
      }
      if (i < bitCount - 1) {
        flipBit(bytes, i + 1);
        if (pred.test(bytes)) return true;
        if (i < bitCount - 2) {
          flipBit(bytes, i + 2);
          // Only check this 3 spot if it's exactly 3 until the end
          if (i == bitCount - 3 && pred.test(bytes)) return true;
          if (i < bitCount - 3) {
            flipBit(bytes, i + 3);
            if (pred.test(bytes)) return true;
            flipBit(bytes, i + 3);
          }
          flipBit(bytes, i + 2);
        }
        flipBit(bytes, i + 1);
      }
      if (i >= 0) flipBit(bytes, i);
    }
    return false;
  }

  public static void flipBit(byte[] arr, int bitIndex) {
    flipBit(arr, bitIndex / 8, bitIndex % 8);
  }

  public static void flipBit(byte[] arr, int byteIndex, int bitIndex) {
    arr[byteIndex] ^= (1 << bitIndex);
  }

  public static byte flipBit(byte byt, int bitIndex) {
    return (byte) (byt ^ (1 << bitIndex));
  }

  public static byte[] withCopiedBytes(byte[] arr, Consumer<byte[]> fn) {
    byte[] ret = Arrays.copyOf(arr, arr.length);
    fn.accept(ret);
    return ret;
  }
}
