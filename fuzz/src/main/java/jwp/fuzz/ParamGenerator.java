package jwp.fuzz;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.*;

/**
 * Base interface for all parameter generators. A parameter generator is a generator that provides parameter values as
 * an iterator. The iterator can be infinite as specified by {@link #isInfinite()}. Implementors only have to implement
 * {@link #iterator()} and {@link #isInfinite()}. All generators should have {@link #close()} called when it is no
 * longer in use. There are static helpers for generators of common types.
 */
public interface ParamGenerator<T> extends AutoCloseable {

  // Guaranteed to be iterated in a single thread.
  /**
   * Create a new iterator for the parameter type. The iterator returned here is guaranteed to be iterated by a single
   * thread. It is not guaranteed to complete. Also, callers may invoke this many times.
   */
  Iterator<T> iterator();

  /** Whether the result of {@link #iterator()} ever terminates */
  boolean isInfinite();

  /**
   * Called when an execution is complete with a parameter that came from this generator. It could be called with the
   * same value if the value was reused, meaning the iterator may not have iterated multiple times even though this is
   * called multiple times. This default implementation does nothing.
   * <p>
   * Due to the fact that the generator may have its value mapped further down the line, the parameter in the "result"
   * at the "myParamIndex" may not be the same value. This is why "myParam" is provided. Due to way the mapping works
   * on {@link #affectedStream(Function, Function)}, the value of "myParam" may not be the exact same object used as the
   * parameter. Instead it is just mapped back from what was actually used.
   */
  default void onResult(ExecutionResult result, int myParamIndex, T myParam) { }

  /**
   * Closes the generator. This should be called when the generator is no longer used. This default implementation does
   * nothing.
   */
  @Override
  default void close() throws Exception { }

  /** Delegates to {@link #affectedStream(Function, Function)} with an identity change-back mapper */
  default ParamGenerator<T> affectedStream(Function<Stream<T>, Stream<T>> changeFn) {
    return affectedStream(changeFn, Function.identity());
  }

  /**
   * Create a new parameter generator with the given change and change-back functions. The change function accepts
   * streams so the implementor can choose to filter, add, or do other things while running. The change-back function
   * is a simple mapper that is called on result to change a parameter back so this base parameter generator's
   * {@link #onResult(ExecutionResult, int, Object)} gets an accurate value.
   */
  default <U> ParamGenerator<U> affectedStream(Function<Stream<T>, Stream<U>> changeFn,
      Function<U, T> onCompleteChangeParamBackFn) {
    final ParamGenerator<T> self = this;
    return new ParamGenerator<U>() {
      @Override
      public Iterator<U> iterator() {
        return changeFn.apply(StreamSupport.stream(Spliterators.spliteratorUnknownSize(
            self.iterator(), Spliterator.ORDERED), false)).iterator();
      }

      @Override
      public boolean isInfinite() { return self.isInfinite(); }

      @Override
      public void onResult(ExecutionResult result, int myParamIndex, U myParam) {
        self.onResult(result, myParamIndex, onCompleteChangeParamBackFn.apply(myParam));
      }

      @Override
      public void close() throws Exception { self.close(); }
    };
  }

  /**
   * Delegates to {@link #affectedStream(Function, Function)} ignoring all null values in the stream. This is useful
   * to ignore some items while mapping.
   */
  default <U> ParamGenerator<U> mapNotNull(Function<T, U> fnTo, Function<U, T> fnFrom) {
    return affectedStream(s -> s.map(fnTo).filter(Objects::nonNull), fnFrom);
  }

  /** Delegates to {@link #affectedStream(Function)} using the given predicate on the stream */
  default ParamGenerator<T> filter(Predicate<T> pred) {
    return affectedStream(s -> s.filter(pred));
  }

  /** All classes supported by {@link #suggestedFinite(Class)} */
  Set<Class<?>> SUGGESTABLE_FINITE_CLASSES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
      Boolean.TYPE, Boolean.class, Byte.TYPE, Byte.class, Short.TYPE, Short.class, Integer.TYPE, Integer.class,
      Long.TYPE, Long.class, Float.TYPE, Float.class, Double.TYPE, Double.class
  )));

  /** Whether a class will work with {@link #suggested(Class)} */
  static boolean isSuggestableClass(Class<?> cls) {
    return SUGGESTABLE_FINITE_CLASSES.contains(cls) ||
        ByteArrayParamGenerator.SUGGESTABLE_BYTE_ARRAY_CLASSES.contains(cls);
  }

  /**
   * Get a suggested generator from the given type. An exception is thrown if class is not supported.
   * @see #suggestedFinite(Class)
   * @see ByteArrayParamGenerator#suggested(Class)
   */
  static <T> ParamGenerator<T> suggested(Class<T> cls) {
    if (SUGGESTABLE_FINITE_CLASSES.contains(cls)) return suggestedFinite(cls);
    if (ByteArrayParamGenerator.SUGGESTABLE_BYTE_ARRAY_CLASSES.contains(cls))
      return ByteArrayParamGenerator.suggested(cls);
    throw new IllegalArgumentException("No suggested generator for " + cls);
  }

  /**
   * Get a finite parameter generator for the given class. Only some classes are supported. An exception is thrown if a
   * class is not supported.
   */
  @SuppressWarnings("unchecked")
  static <T> ParamGenerator<T> suggestedFinite(Class<T> cls) {
    if (cls == Boolean.TYPE) return (ParamGenerator<T>) of(true, false);
    if (cls == Boolean.class) return (ParamGenerator<T>) of(null, true, false);
    if (cls == Byte.TYPE) return ofFinite(ParamGenerator::interestingBytes);
    if (cls == Byte.class) return ofFinite(() -> Stream.concat(Stream.of((Integer) null), interestingBytes().boxed()));
    if (cls == Short.TYPE) return ofFinite(ParamGenerator::interestingShorts);
    if (cls == Short.class) return ofFinite(() -> Stream.concat(Stream.of((Integer) null), interestingShorts().boxed()));
    if (cls == Integer.TYPE) return ofFinite(ParamGenerator::interestingInts);
    if (cls == Integer.class) return ofFinite(() -> Stream.concat(Stream.of((Integer) null), interestingInts().boxed()));
    if (cls == Long.TYPE) return ofFinite(ParamGenerator::interestingLongs);
    if (cls == Long.class) return ofFinite(() -> Stream.concat(Stream.of((Long) null), interestingLongs().boxed()));
    if (cls == Float.TYPE) return ofFinite(ParamGenerator::interestingFloats);
    if (cls == Float.class) return ofFinite(() -> Stream.concat(Stream.of((Float) null), interestingFloats().boxed()));
    if (cls == Double.TYPE) return ofFinite(ParamGenerator::interestingDoubles);
    if (cls == Double.class)
      return ofFinite(() -> Stream.concat(Stream.of((Double) null), interestingDoubles().boxed()));
    if (cls == Character.TYPE) throw new UnsupportedOperationException("TODO");
    if (cls == Character.class) throw new UnsupportedOperationException("TODO");
    throw new IllegalArgumentException("No suggested generator for " + cls);
  }

  /**
   * Create a finite parameter generator from the given stream supplier with a no-op {@link #close()} and
   * {@link #onResult(ExecutionResult, int, Object)}. The supplier is invoked every time {@link #iterator()} is
   * invoked.
   */
  static <T> ParamGenerator<T> ofFinite(Supplier<BaseStream> streamSupplier) {
    return new ParamGenerator<T>() {
      @Override
      @SuppressWarnings("unchecked")
      public Iterator<T> iterator() { return streamSupplier.get().iterator(); }

      @Override
      public boolean isInfinite() { return false; }
    };
  }

  /** Create finite parameter gen from values via {@link #ofFinite(Supplier)} */
  @SafeVarargs
  static <T> ParamGenerator<T> of(T... items) { return ofFinite(() -> Stream.of(items)); }

  /** An int stream of interesting byte values */
  static IntStream interestingBytes() {
    return IntStream.concat(
        IntStream.of(Byte.MIN_VALUE, 64, 100, Byte.MAX_VALUE),
        IntStream.rangeClosed(-35, 35)
    );
  }

  /** An int stream of interesting short values. Includes {@link #interestingBytes()}. */
  static IntStream interestingShorts() {
    return IntStream.concat(
        interestingBytes(),
        IntStream.of(Short.MIN_VALUE, -129, 128, 255, 256, 512, 1000, 1024, 4096, Short.MAX_VALUE)
    );
  }

  /** An int stream of interesting int values. Includes {@link #interestingShorts()}. */
  static IntStream interestingInts() {
    return IntStream.concat(
        interestingShorts(),
        IntStream.of(Integer.MIN_VALUE, -100663046, -32769, 32768, 65535, 65536, 100663045, Integer.MAX_VALUE)
    );
  }

  /** A long stream of interesting long values. Includes {@link #interestingLongs()}. */
  static LongStream interestingLongs() {
    return LongStream.concat(
        interestingInts().asLongStream(),
        LongStream.of(Long.MIN_VALUE, Long.MAX_VALUE)
    );
  }

  /** A double stream of interesting float values. Includes {@link #interestingLongs()}. */
  static DoubleStream interestingFloats() {
    return DoubleStream.concat(
        interestingLongs().asDoubleStream(),
        DoubleStream.of(Float.MIN_NORMAL, Float.MIN_VALUE, Float.MAX_VALUE,
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NaN)
    );
  }

  /** A double stream of interesting doubles. Includes {@link #interestingFloats()} */
  static DoubleStream interestingDoubles() {
    return DoubleStream.concat(
        interestingFloats(),
        DoubleStream.of(Double.MIN_NORMAL, Double.MIN_VALUE, Double.MAX_VALUE)
    );
  }
}
