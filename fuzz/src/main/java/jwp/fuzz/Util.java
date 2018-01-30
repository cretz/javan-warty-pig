package jwp.fuzz;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Utility functions and classes */
public class Util {
  public static byte byte0(short val) { return (byte) val; }
  public static byte byte1(short val) { return (byte) (val >> 8); }

  public static byte byte0(int val) { return (byte) val; }
  public static byte byte1(int val) { return (byte) (val >> 8); }
  public static byte byte2(int val) { return (byte) (val >> 16); }
  public static byte byte3(int val) { return (byte) (val >> 24); }

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

  public static String classBytesToString(byte[] bytes) {
    ClassReader reader = new ClassReader(bytes);
    StringWriter stringWriter = new StringWriter();
    reader.accept(new TraceClassVisitor(new PrintWriter(stringWriter)), 0);
    return stringWriter.toString();
  }

  public static boolean contains(byte[] arr, byte item) {
    for (byte arrItem : arr) if (arrItem == item) return true;
    return false;
  }

  public static boolean contains(short[] arr, short item) {
    for (short arrItem : arr) if (arrItem == item) return true;
    return false;
  }

  public static boolean contains(int[] arr, int item) {
    for (int arrItem : arr) if (arrItem == item) return true;
    return false;
  }

  public static boolean couldHaveBitFlippedTo(byte curr, byte... newBytes) {
    return contains(newBytes, curr) ||
        checkConsecutiveBitsFlipped(new byte[] { curr }, arr -> contains(newBytes, arr[0]));
  }

  public static boolean couldHaveBitFlippedTo(short curr, short... newShorts) {
    return contains(newShorts, curr) ||
        checkConsecutiveBitsFlipped(toByteArray(curr), arr -> contains(newShorts, getShortLe(arr, 0)));
  }

  public static boolean couldHaveBitFlippedTo(int curr, int... newInts) {
    return contains(newInts, curr) ||
        checkConsecutiveBitsFlipped(toByteArray(curr), arr -> contains(newInts, getIntLe(arr, 0)));
  }

  public static short endianSwapped(short val) {
    return shortFromBytes(byte1(val), byte0(val));
  }

  public static int endianSwapped(int val) {
    return intFromBytes(byte3(val), byte2(val), byte1(val), byte0(val));
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

  public static int getIntBe(byte[] arr, int byteIndex) {
    return intFromBytes(arr[byteIndex + 3], arr[byteIndex + 2], arr[byteIndex + 1], arr[byteIndex]);
  }

  public static int getIntLe(byte[] arr, int byteIndex) {
    return intFromBytes(arr[byteIndex], arr[byteIndex + 1], arr[byteIndex + 2], arr[byteIndex + 3]);
  }

  public static short getShortBe(byte[] arr, int byteIndex) {
    return shortFromBytes(arr[byteIndex + 1], arr[byteIndex]);
  }

  public static short getShortLe(byte[] arr, int byteIndex) {
    return shortFromBytes(arr[byteIndex], arr[byteIndex + 1]);
  }

  public static int intFromBytes(byte byte0, byte byte1, byte byte2, byte byte3) {
    return (byte3 << 24) | ((byte2 & 0xFF) << 16) | ((byte2 & 0xFF) << 8) | (byte0 & 0xFF);
  }

  public static void putIntBe(byte[] arr, int byteIndex, int val) {
    arr[byteIndex] = byte3(val);
    arr[byteIndex + 1] = byte2(val);
    arr[byteIndex + 2] = byte1(val);
    arr[byteIndex + 3] = byte0(val);
  }

  public static void putIntLe(byte[] arr, int byteIndex, int val) {
    arr[byteIndex] = byte0(val);
    arr[byteIndex + 1] = byte1(val);
    arr[byteIndex + 2] = byte2(val);
    arr[byteIndex + 3] = byte3(val);
  }

  public static void putShortBe(byte[] arr, int byteIndex, short val) {
    arr[byteIndex] = byte1(val);
    arr[byteIndex + 1] = byte0(val);
  }

  public static void putShortLe(byte[] arr, int byteIndex, short val) {
    arr[byteIndex] = byte0(val);
    arr[byteIndex + 1] = byte1(val);
  }

  public static short shortFromBytes(byte byte0, byte byte1) {
    return (short) ((byte1 << 8) | (byte0 & 0xFF));
  }

  @SafeVarargs
  public static <T> Stream<T> streamOfNotNull(T... items) {
    return Stream.of(items).filter(Objects::nonNull);
  }

  public static byte[] streamToByteArray(IntStream stream) {
    int[] ints = stream.toArray();
    byte[] ret = new byte[ints.length];
    for (int i = 0; i < ints.length; i++) ret[i] = (byte) ints[i];
    return ret;
  }

  public static short[] streamToShortArray(IntStream stream) {
    int[] ints = stream.toArray();
    short[] ret = new short[ints.length];
    for (int i = 0; i < ints.length; i++) ret[i] = (short) ints[i];
    return ret;
  }

  public static <T> Stream<T> streamCharacteristics(Stream<T> stream, IntConsumer consumer) {
    Spliterator<T> spliterator = stream.spliterator();
    consumer.accept(spliterator.characteristics());
    return StreamSupport.stream(spliterator, stream.isParallel());
  }

  public static byte[] toByteArray(short val) {
    return new byte[] { byte0(val), byte1(val) };
  }

  public static byte[] toByteArray(int val) {
    return new byte[] { byte0(val), byte1(val), byte2(val), byte3(val) };
  }

  public static byte[] withBytesRemoved(byte[] bytes, int start, int amount) {
    byte[] newArr = new byte[bytes.length - amount];
    System.arraycopy(bytes, 0, newArr, 0, start);
    System.arraycopy(bytes, start + amount, newArr, start, newArr.length - start);
    return newArr;
  }

  public static byte[] withCopiedBytes(byte[] arr, Consumer<byte[]> fn) {
    byte[] ret = Arrays.copyOf(arr, arr.length);
    fn.accept(ret);
    return ret;
  }

  /** An executor service that only runs submissions on the current thread */
  public static class CurrentThreadExecutorService extends ThreadPoolExecutor {
    public CurrentThreadExecutorService() {
      super(0, 1, 0, TimeUnit.SECONDS,
          new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Override
    public void execute(Runnable command) { getRejectedExecutionHandler().rejectedExecution(command, this); }
  }

  /** Base iterator that is considered finished the first time it sees a null */
  public static abstract class NullMeansCompleteIterator<T> implements Iterator<T> {
    protected T prev;
    protected boolean finished = false;

    /** Provide the next item or null to finish this iterator */
    protected abstract T doNext();

    @Override
    public boolean hasNext() { return !finished && next(true) != null; }

    @Override
    public T next() {
      if (finished) throw new NoSuchElementException();
      return next(false);
    }

    protected T next(boolean canCacheResult) {
      T ret = prev == null ? doNext() : prev;
      if (ret == null) finished = true;
      prev = canCacheResult ? ret : null;
      return ret;
    }
  }
}
